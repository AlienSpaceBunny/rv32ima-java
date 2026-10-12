package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Lockstep differential between {@link RV32IMACore} and the independent {@link ReferenceCore}.
 * The core comes from {@link TestCores}, so the build runs this uncached and with the decoded
 * cache at two sizes.
 *
 * <p>Both cores run the same program on identical machines, with the same host events between
 * calls (interrupt injection, compare changes, WFI release, a remote reservation loss, a
 * relaunch elsewhere in the code) and the same random batch sizes. After every call the test
 * compares the call's result and the whole committed state (registers, FP registers, pc, CSRs, privilege, {@code extraflags}, counters and
 * the hart's reservation fields). Within a batch, each {@code PostExecHook} event and the state
 * it sees are compared too, except pc and the cycle count, which a batch keeps in locals. Every
 * data access, device access, CSR-hook call and reservation decision is logged in order and
 * compared, as are memory, device state and the bus's own reservation entry. Fetches are not
 * logged, since the cache legitimately skips them.
 *
 * <p>Programs are seeded random code plus directed cases for paths random code rarely reaches:
 * LR/SC sequences, user mode, WFI and interrupts, faults, and CSR-hook accesses.
 */
public class ReferenceDifferentialTest {
    private static final int BASE = 0x80000000;
    private static final int RAM_SIZE = 16 * 1024;
    private static final int CODE_SIZE = 4096;
    private static final int START = BASE + 0x100;
    private static final int DATA_MID = BASE + 0x3000;
    private static final int DEVICE = 0x10000000;
    private static final int DEVICE_SIZE = 0x100;
    private static final int[] POINTER_REGS = {2, 8, 9, 10, 11};
    private static final int[] CSRS = {
        0x300, 0x304, 0x344, 0x340, 0x341, 0x342, 0x343, 0xC00, 0x001, 0x002, 0x003, 0x301, 0xF11, 0x7C0, 0xC01, 0xC80,
        0xB00, 0xF14, 0x305
    };

    private static final int ECALL = 0x00000073;
    private static final int EBREAK = 0x00100073;
    private static final int MRET = 0x30200073;
    private static final int WFI = 0x10500073;
    private static final int FENCE = 0x0ff0000f;
    private static final int FENCE_I = 0x0000100f;

    private static IsaConfig config(String name) {
        return switch (name) {
            case "full" -> new IsaConfig(true, true, true, true, true, true);
            case "noC" -> new IsaConfig(false, true, true, true, true, true);
            case "ap" -> IsaConfig.RV32IMFC_ZBA_ZBB_ZICSR;
            case "iop" -> IsaConfig.RV32IMC_ZBB_ZICSR;
            case "base" -> IsaConfig.RV32IMA_ZICSR;
            default -> throw new IllegalArgumentException(name);
        };
    }

    // ---- Encoders ----

    private static int rType(int opcode, int funct3, int funct7, int rd, int rs1, int rs2) {
        return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode;
    }

    private static int iType(int opcode, int funct3, int rd, int rs1, int imm) {
        return ((imm & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode;
    }

    private static int sType(int funct3, int rs1, int rs2, int imm) {
        return (((imm >> 5) & 0x7f) << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | ((imm & 0x1f) << 7) | 0x23;
    }

    private static int bType(int funct3, int rs1, int rs2, int offset) {
        return (((offset >> 12) & 1) << 31)
                | (((offset >> 5) & 0x3f) << 25)
                | (rs2 << 20)
                | (rs1 << 15)
                | (funct3 << 12)
                | (((offset >> 1) & 0xf) << 8)
                | (((offset >> 11) & 1) << 7)
                | 0x63;
    }

    private static int jal(int rd, int offset) {
        return (((offset >> 20) & 1) << 31)
                | (((offset >> 1) & 0x3ff) << 21)
                | (((offset >> 11) & 1) << 20)
                | (((offset >> 12) & 0xff) << 12)
                | (rd << 7)
                | 0x6f;
    }

    private static int lui(int rd, int value) {
        return (value & 0xfffff000) | (rd << 7) | 0x37;
    }

    private static int addi(int rd, int rs1, int imm) {
        return iType(0x13, 0, rd, rs1, imm);
    }

    private static int csr(int funct3, int rd, int rs1, int csrNo) {
        return iType(0x73, funct3, rd, rs1, csrNo);
    }

    private static int amo(int funct5, int funct3, int rd, int rs1, int rs2) {
        return rType(0x2f, funct3, funct5 << 2, rd, rs1, rs2);
    }

    // ---- The machine both cores run on ----

    /** One logged effect: a bus access, a reservation decision or a CSR-hook call. */
    private record Event(char op, int kind, int width, int atomicOp, int privilege, int address, int value) {}

    /**
     * RAM at {@link #BASE} whose code region is write-protected, plus a device at {@link #DEVICE}
     * whose reads change its state. Everything else is unmapped. It keeps one reservation entry,
     * as a multi-hart bus does for each hart, which a test can drop to model another hart's store.
     */
    private static final class RecordingBus implements MemoryBus {
        final byte[] ram = new byte[RAM_SIZE];
        List<Event> log = new ArrayList<>();
        int deviceReads;
        int deviceWrites;
        int deviceLast;
        boolean reservationHeld;
        int reservationAddress;

        RecordingBus copy() {
            RecordingBus copy = new RecordingBus();
            System.arraycopy(ram, 0, copy.ram, 0, RAM_SIZE);
            return copy;
        }

        void putInt(int address, int value) {
            for (int i = 0; i < 4; i++) {
                ram[address - BASE + i] = (byte) (value >> (8 * i));
            }
        }

        void putShort(int address, int value) {
            ram[address - BASE] = (byte) value;
            ram[address - BASE + 1] = (byte) (value >> 8);
        }

        private static boolean inDevice(int address, int width) {
            return Integer.toUnsignedLong(address - DEVICE) + width <= DEVICE_SIZE;
        }

        private static int ramOffset(int address, int width) {
            long offset = Integer.toUnsignedLong(address - BASE);
            if (offset + width > RAM_SIZE) {
                throw new IndexOutOfBoundsException("unmapped 0x" + Integer.toHexString(address));
            }
            return (int) offset;
        }

        private static void checkWritable(int address, int width) {
            if (!inDevice(address, width) && ramOffset(address, width) < CODE_SIZE) {
                throw new IndexOutOfBoundsException("write-protected 0x" + Integer.toHexString(address));
            }
        }

        private void record(char op, AccessContext ctx, int width, int address, int value) {
            if (ctx == null) {
                log.add(new Event(op, -1, width, 0, -1, address, value));
            } else if (ctx.kind() != AccessKind.FETCH) {
                log.add(new Event(op, ctx.kind().ordinal(), width, ctx.atomicOp(), ctx.privilege(), address, value));
            }
        }

        /** Reads {@code width} bytes zero-extended, logging the access or its fault. */
        private int read(int address, int width, AccessContext ctx) {
            int value = 0;
            try {
                if (inDevice(address, width)) {
                    deviceReads++;
                    value = (deviceReads * 0x9E3779B1) ^ address;
                    value = width == 4 ? value : value & ((1 << (8 * width)) - 1);
                } else {
                    int offset = ramOffset(address, width);
                    for (int i = 0; i < width; i++) {
                        value |= (ram[offset + i] & 0xff) << (8 * i);
                    }
                }
            } catch (IndexOutOfBoundsException e) {
                record('!', ctx, width, address, 0);
                throw e;
            }
            record('R', ctx, width, address, value);
            if (ctx != null && ctx.kind() == AccessKind.AMO && ctx.atomicOp() == 2) {
                reservationHeld = true;
                reservationAddress = address;
            }
            return value;
        }

        private void write(int address, int width, int value, AccessContext ctx) {
            int stored = width == 4 ? value : value & ((1 << (8 * width)) - 1);
            try {
                checkWritable(address, width);
            } catch (IndexOutOfBoundsException e) {
                record('X', ctx, width, address, stored);
                throw e;
            }
            record('W', ctx, width, address, stored);
            if (inDevice(address, width)) {
                deviceWrites++;
                deviceLast = stored;
            } else {
                int offset = ramOffset(address, width);
                for (int i = 0; i < width; i++) {
                    ram[offset + i] = (byte) (value >> (8 * i));
                }
            }
        }

        @Override
        public byte readByte(int address) {
            return (byte) read(address, 1, null);
        }

        @Override
        public short readShort(int address) {
            return (short) read(address, 2, null);
        }

        @Override
        public int readInt(int address) {
            return read(address, 4, null);
        }

        @Override
        public void writeByte(int address, byte value) {
            write(address, 1, value, null);
        }

        @Override
        public void writeShort(int address, short value) {
            write(address, 2, value, null);
        }

        @Override
        public void writeInt(int address, int value) {
            write(address, 4, value, null);
        }

        @Override
        public int readShortSigned(int address) {
            return (short) read(address, 2, null);
        }

        @Override
        public int readByteSigned(int address) {
            return (byte) read(address, 1, null);
        }

        @Override
        public byte readByte(int address, AccessContext ctx) {
            return (byte) read(address, 1, ctx);
        }

        @Override
        public short readShort(int address, AccessContext ctx) {
            return (short) read(address, 2, ctx);
        }

        @Override
        public int readInt(int address, AccessContext ctx) {
            return read(address, 4, ctx);
        }

        @Override
        public void writeByte(int address, byte value, AccessContext ctx) {
            write(address, 1, value, ctx);
        }

        @Override
        public void writeShort(int address, short value, AccessContext ctx) {
            write(address, 2, value, ctx);
        }

        @Override
        public void writeInt(int address, int value, AccessContext ctx) {
            write(address, 4, value, ctx);
        }

        @Override
        public int readShortSigned(int address, AccessContext ctx) {
            return (short) read(address, 2, ctx);
        }

        @Override
        public int readByteSigned(int address, AccessContext ctx) {
            return (byte) read(address, 1, ctx);
        }

        @Override
        public int tryScAndStore(int hartId, int address, int value, AccessContext ctx) {
            boolean valid = reservationHeld && reservationAddress == address;
            reservationHeld = false;
            record(valid ? 'S' : 's', ctx, 4, address, value);
            if (!valid) {
                checkWritable(address, 4);
                return 1;
            }
            write(address, 4, value, ctx);
            return 0;
        }

        @Override
        public void checkAccess(int address, AccessContext ctx) {
            record('C', ctx, ctx.width(), address, 0);
            checkWritable(address, ctx.width());
        }
    }

    /** A CSR hook whose reads change its state. */
    private static final class RecordingCsrHook implements CSRHook {
        final RecordingBus bus;
        int reads;

        RecordingCsrHook(RecordingBus bus) {
            this.bus = bus;
        }

        @Override
        public int handleRead(int csrNo) {
            reads++;
            bus.log.add(new Event('r', -1, 4, 0, -1, csrNo, reads));
            return (csrNo * 0x01000193) ^ reads;
        }

        @Override
        public void handleWrite(int csrNo, int value) {
            bus.log.add(new Event('w', -1, 4, 0, -1, csrNo, value));
        }
    }

    @FunctionalInterface
    private interface Stepper {
        int step(
                RV32IMAState state,
                MemoryBus mem,
                int ramOffset,
                int ramSize,
                int count,
                RV32IMACore.PostExecHook postExec,
                CSRHook csrHook);
    }

    /** One core with its own state, bus, hook and observations. */
    private static final class Machine {
        final RV32IMAState state = new RV32IMAState();
        final RecordingBus bus;
        final RecordingCsrHook csrHook;
        final Stepper stepper;
        final int window;
        final List<int[]> hookEvents = new ArrayList<>();
        final RV32IMACore.PostExecHook postExec;

        Machine(RecordingBus bus, Stepper stepper, int window) {
            this.bus = bus;
            this.window = window;
            this.csrHook = new RecordingCsrHook(bus);
            this.stepper = stepper;
            this.postExec = (pc, ir, trap) -> hookEvents.add(snapshot(state, pc, ir, trap));
        }

        int step(int count, boolean hooked) {
            return stepper.step(state, bus, BASE, window, count, hooked ? postExec : null, csrHook);
        }

        void clearObservations() {
            hookEvents.clear();
            bus.log.clear();
        }
    }

    private static final String[] STATE_FIELDS;

    static {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            names.add("x" + i);
        }
        for (int i = 0; i < 32; i++) {
            names.add("f" + i + ".hi");
            names.add("f" + i + ".lo");
        }
        names.addAll(List.of(
                "mstatus",
                "mie",
                "mip",
                "mepc",
                "mcause",
                "mtval",
                "mscratch",
                "mtvec",
                "extraflags",
                "fcsr",
                "hartId",
                "reservationValid",
                "reservationAddr",
                "timerl",
                "timerh",
                "timermatchl",
                "timermatchh"));
        STATE_FIELDS = names.toArray(String[]::new);
    }

    /** The given prefix values, then every state field except pc and the cycle count. */
    private static int[] snapshot(RV32IMAState s, int... prefix) {
        int[] out = new int[prefix.length + STATE_FIELDS.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        int i = prefix.length;
        for (int reg : s.regs) {
            out[i++] = reg;
        }
        for (long freg : s.fregs) {
            out[i++] = (int) (freg >>> 32);
            out[i++] = (int) freg;
        }
        int[] tail = {
            s.mstatus,
            s.mie,
            s.mip,
            s.mepc,
            s.mcause,
            s.mtval,
            s.mscratch,
            s.mtvec,
            s.extraflags,
            s.fcsr,
            s.hartId,
            s.reservationValid ? 1 : 0,
            s.reservationAddr,
            s.timerl,
            s.timerh,
            s.timermatchl,
            s.timermatchh
        };
        System.arraycopy(tail, 0, out, i, tail.length);
        return out;
    }

    /** Fails with the first differing field when two snapshots differ. */
    private static void assertSnapshot(int[] expected, int[] actual, String[] prefixNames, String where) {
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                String name = i < prefixNames.length ? prefixNames[i] : STATE_FIELDS[i - prefixNames.length];
                fail(where + ": " + name + " reference 0x" + Integer.toHexString(expected[i]) + ", core 0x"
                        + Integer.toHexString(actual[i]));
            }
        }
    }

    private static final String[] HOOK_PREFIX = {"hook pc", "hook ir", "hook trap"};
    private static final String[] BOUNDARY_PREFIX = {"result", "pc", "cyclel", "cycleh"};

    private static void assertSameMachines(Machine ref, Machine core, int refResult, int coreResult, String where) {
        assertEquals(ref.hookEvents.size(), core.hookEvents.size(), where + ": hook event count");
        for (int i = 0; i < ref.hookEvents.size(); i++) {
            assertSnapshot(ref.hookEvents.get(i), core.hookEvents.get(i), HOOK_PREFIX, where + ", hook event " + i);
        }
        assertSnapshot(
                snapshot(ref.state, refResult, ref.state.pc, ref.state.cyclel, ref.state.cycleh),
                snapshot(core.state, coreResult, core.state.pc, core.state.cyclel, core.state.cycleh),
                BOUNDARY_PREFIX,
                where);
        assertEquals(ref.bus.log, core.bus.log, where + ": bus and CSR-hook log");
        assertArrayEquals(ref.bus.ram, core.bus.ram, where + ": memory");
        assertEquals(ref.bus.deviceReads, core.bus.deviceReads, where + ": device reads");
        assertEquals(ref.bus.deviceWrites, core.bus.deviceWrites, where + ": device writes");
        assertEquals(ref.bus.deviceLast, core.bus.deviceLast, where + ": device value");
        assertEquals(ref.bus.reservationHeld, core.bus.reservationHeld, where + ": bus reservation held");
        assertEquals(ref.bus.reservationAddress, core.bus.reservationAddress, where + ": bus reservation address");
        assertEquals(ref.csrHook.reads, core.csrHook.reads, where + ": CSR-hook reads");
    }

    /**
     * Runs the reference and the core in lockstep on copies of {@code program}, applying the same
     * random host events and batch sizes to both, and compares them after every call.
     */
    private static void runLockstep(
            String label,
            IsaConfig isa,
            RecordingBus program,
            int window,
            Consumer<RV32IMAState> setup,
            long seed,
            int iterations) {
        Random rnd = new Random(seed);
        // Separate sources, advanced together, so neither core can see the other's samples.
        ManualTimeSource refTime = new ManualTimeSource();
        ManualTimeSource coreTime = new ManualTimeSource();
        RV32IMACore core = TestCores.create(isa, coreTime);
        Machine ref = new Machine(program.copy(), new ReferenceCore(isa, refTime)::step, window);
        Machine dut = new Machine(program.copy(), core::step, window);
        setup.accept(ref.state);
        setup.accept(dut.state);
        long retiredHookEvents = 0;
        for (int iteration = 0; iteration < iterations; iteration++) {
            switch (rnd.nextInt(300)) {
                case 0, 1 -> {
                    int bit = rnd.nextBoolean() ? 3 : 11;
                    ReferenceCore.injectInterrupt(ref.state, bit);
                    RV32IMACore.injectInterrupt(dut.state, bit);
                }
                case 2 -> {
                    ref.state.mip = 0;
                    dut.state.mip = 0;
                }
                case 3, 4 -> {
                    long match = ref.state.getTimer() + rnd.nextInt(40);
                    ref.state.setTimerMatch(match);
                    dut.state.setTimerMatch(match);
                }
                case 5 -> {
                    ref.state.setTimerMatch(0); // always due: zero is not special
                    dut.state.setTimerMatch(0);
                }
                case 18 -> {
                    ref.state.setTimerMatch(RV32IMAState.TIMER_COMPARE_NEVER);
                    dut.state.setTimerMatch(RV32IMAState.TIMER_COMPARE_NEVER);
                }
                case 6, 7 -> {
                    ref.state.extraflags &= ~4; // release a WFI stall
                    dut.state.extraflags &= ~4;
                }
                case 8, 9, 10, 11, 12, 13, 14, 15 -> {
                    ref.bus.reservationHeld = false; // another hart's store to the granule
                    dut.bus.reservationHeld = false;
                }
                case 16, 17 -> {
                    // The host relaunches the hart elsewhere in its code, as a loader or debugger
                    // may, so a program stuck in a loop keeps exploring.
                    int pc = START + 4 * rnd.nextInt((window == CODE_SIZE ? CODE_SIZE - 0x100 : 0x80) / 4);
                    int mie = rnd.nextBoolean() ? 0x8 : 0;
                    for (RV32IMAState state : new RV32IMAState[] {ref.state, dut.state}) {
                        state.pc = pc;
                        state.extraflags = (state.extraflags & ~7) | 3;
                        state.mstatus = (state.mstatus & ~0x8) | mie;
                    }
                }
                default -> {}
            }
            int count = rnd.nextInt(3) == 0 ? 1 : 1 + rnd.nextInt(32);
            int elapsed = rnd.nextInt(3);
            refTime.advance(elapsed);
            coreTime.advance(elapsed);
            boolean hooked = rnd.nextInt(4) != 0;
            int refResult = ref.step(count, hooked);
            int dutResult = dut.step(count, hooked);
            String where = label + ", seed " + seed + ", slots " + TestCores.SLOTS + ", iteration " + iteration;
            assertSameMachines(ref, dut, refResult, dutResult, where);
            retiredHookEvents += ref.hookEvents.size();
            ref.clearObservations();
            dut.clearObservations();
        }
        assertTrue(retiredHookEvents > iterations, label + ": too little ran: " + retiredHookEvents);
    }

    // ---- Seeded random programs ----

    /**
     * Random code for {@code [BASE, BASE + CODE_SIZE)}, the only executable range. A fixed trap handler at {@code BASE}
     * jumps to a pseudo-random word in {@code [BASE + 0x100, BASE + 0x900)}, so execution keeps
     * moving after traps.
     */
    private static RecordingBus randomProgram(Random rnd, boolean hasC) {
        RecordingBus bus = new RecordingBus();
        int[] handler = {
            csr(2, 7, 0, 0xC00), // csrr x7, cycle
            iType(0x13, 7, 7, 7, 0x7fc), // andi x7, x7, 0x7fc
            lui(6, BASE),
            rType(0x33, 0, 0, 7, 7, 6), // add x7, x7, x6
            addi(7, 7, 0x100),
            iType(0x67, 0, 0, 7, 0) // jalr x0, 0(x7)
        };
        for (int i = 0; i < handler.length; i++) {
            bus.putInt(BASE + 4 * i, handler[i]);
        }
        int at = 4 * handler.length;
        while (at < CODE_SIZE - 8) {
            if (hasC && rnd.nextInt(10) < 3) {
                bus.putShort(BASE + at, (rnd.nextInt(0x10000) & ~3) | rnd.nextInt(3));
                at += 2;
                continue;
            }
            int rd = rnd.nextInt(32);
            int rs1 = rnd.nextInt(32);
            int rs2 = rnd.nextInt(32);
            int ptr = POINTER_REGS[rnd.nextInt(POINTER_REGS.length)];
            int word;
            int kind = rnd.nextInt(100);
            if (kind < 7) {
                boolean device = rnd.nextInt(4) == 0;
                bus.putInt(BASE + at, lui(ptr, device ? DEVICE : DATA_MID));
                at += 4;
                word = addi(ptr, ptr, device ? rnd.nextInt(DEVICE_SIZE / 4) * 4 : (rnd.nextInt(512) - 256) * 4);
            } else if (kind < 23) {
                int funct3 = rnd.nextInt(8);
                int funct7 = funct3 == 1 || funct3 == 5 ? new int[] {0, 0, 0x20, 0x30, 0x14, 0x34}[rnd.nextInt(6)] : 0;
                int imm = funct3 == 1 || funct3 == 5 ? (funct7 << 5) | rnd.nextInt(32) : rnd.nextInt(4096);
                if (funct7 == 0x14) {
                    imm = (0x14 << 5) | 7;
                } else if (funct7 == 0x34) {
                    imm = (0x34 << 5) | 0x18;
                }
                word = iType(0x13, funct3, rd, rs1, imm);
            } else if (kind < 38) {
                int funct7 = new int[] {0, 0, 0, 0x20, 0x01, 0x01, 0x10, 0x05, 0x30, 0x04}[rnd.nextInt(10)];
                word = rType(0x33, rnd.nextInt(8), funct7, rd, rs1, rs2);
            } else if (kind < 42) {
                word = (rnd.nextInt() & 0xfffff000) | (rd << 7) | (rnd.nextBoolean() ? 0x37 : 0x17);
            } else if (kind < 52) {
                word = iType(0x03, rnd.nextInt(8), rd, ptr, rnd.nextInt(128) - 64);
            } else if (kind < 60) {
                word = sType(rnd.nextInt(4), ptr, rs2, rnd.nextInt(128) - 64);
            } else if (kind < 70) {
                word = bType(rnd.nextInt(8), rs1, rs2, (rnd.nextInt(128) - 64) * 2);
            } else if (kind < 74) {
                word = jal(rd, (rnd.nextInt(256) - 128) * 2);
            } else if (kind < 76) {
                word = iType(0x67, 0, rd, rs1, rnd.nextInt(4096));
            } else if (kind < 78) {
                word = rnd.nextBoolean() ? FENCE_I : FENCE;
            } else if (kind < 84) {
                int csrNo = CSRS[rnd.nextInt(CSRS.length)];
                int funct3 = new int[] {1, 2, 3, 5, 6, 7}[rnd.nextInt(6)];
                if (csrNo == 0x305) {
                    // Read mtvec, but never move the trap handler.
                    funct3 = rnd.nextBoolean() ? 2 : 3;
                    rs1 = 0;
                }
                word = csr(funct3, rd, rs1, csrNo);
            } else if (kind < 86) {
                word = new int[] {ECALL, EBREAK, MRET, WFI}[rnd.nextInt(4)];
            } else if (kind < 89) {
                word = rnd.nextBoolean()
                        ? iType(0x07, 2, rd, ptr, rnd.nextInt(64) * 4 - 128)
                        : (sType(2, ptr, rs2, rnd.nextInt(64) * 4 - 128) & ~0x7f) | 0x27;
            } else if (kind < 92) {
                int funct7 = new int[] {0x00, 0x04, 0x08, 0x0C, 0x2C, 0x60, 0x68, 0x10, 0x14, 0x50, 0x70, 0x78}
                        [rnd.nextInt(12)];
                int rs2Field = funct7 == 0x2C || funct7 == 0x70 || funct7 == 0x78 ? 0 : rs2 & 1;
                word = rType(0x53, rnd.nextInt(8), funct7, rd, rs1, funct7 >= 0x60 ? rs2Field : rs2);
            } else if (kind < 93) {
                word = (rnd.nextInt(32) << 27)
                        | rType(new int[] {0x43, 0x47, 0x4B, 0x4F}[rnd.nextInt(4)], rnd.nextInt(8), 0, rd, rs1, rs2);
            } else if (kind < 95) {
                // Clear mstatus.MPP, then MRET to user mode at mepc.
                bus.putInt(BASE + at, lui(28, 0x2000));
                bus.putInt(BASE + at + 4, addi(28, 28, -0x800));
                bus.putInt(BASE + at + 8, csr(3, 0, 28, 0x300));
                at += 12;
                word = MRET;
            } else if (kind < 96) {
                // An LR/SC pair on one pointer, so some SCs find their reservation.
                bus.putInt(BASE + at, amo(2, 2, rd, ptr, 0));
                at += 4;
                word = amo(3, 2, rnd.nextInt(32), ptr, rs2);
            } else if (kind < 99) {
                int funct5 = new int[] {0, 1, 2, 3, 4, 8, 12, 16, 20, 24, 28}[rnd.nextInt(11)];
                int funct3 = rnd.nextInt(4) == 0 ? rnd.nextInt(2) : 2;
                word = rType(0x2f, funct3, (funct5 << 2) | rnd.nextInt(4), rd, ptr, funct5 == 2 ? 0 : rs2);
            } else {
                word = rnd.nextInt();
            }
            bus.putInt(BASE + at, word);
            at += 4;
        }
        return bus;
    }

    private static Consumer<RV32IMAState> randomState(Random rnd) {
        int mie = rnd.nextBoolean() ? 0x888 : 0;
        int[] regs = rnd.ints(32).toArray();
        long[] fregs = rnd.ints(32)
                .mapToLong(v -> 0xffffffff00000000L | Integer.toUnsignedLong(v))
                .toArray();
        regs[0] = 0;
        for (int reg : POINTER_REGS) {
            regs[reg] = DATA_MID;
        }
        return state -> {
            state.pc = START;
            state.extraflags = 3;
            state.mtvec = BASE;
            state.mie = mie;
            System.arraycopy(regs, 0, state.regs, 0, 32);
            System.arraycopy(fregs, 0, state.fregs, 0, 32);
        };
    }

    @ParameterizedTest(name = "seed {0}, {1}")
    @CsvSource({
        "1, full", "2, full", "3, noC", "4, noC", "5, ap", "6, iop", "7, base", "8, full",
    })
    public void randomProgramsMatchReference(long seed, String configName) {
        IsaConfig isa = config(configName);
        RecordingBus program = randomProgram(new Random(seed), isa.hasC());
        // Only the write-protected code is executable, so no fetch can see a store without FENCE.I.
        runLockstep(
                "random " + configName,
                isa,
                program,
                CODE_SIZE,
                randomState(new Random(seed + 1000)),
                seed + 2000,
                15_000);
    }

    // ---- Directed programs ----

    /**
     * A handler at {@code BASE} for directed programs. An interrupt clears its own bit in {@code
     * mie} and returns to the interrupted instruction; a fetch fault returns to {@code x1}, which
     * each faulting jump links; any other exception skips the 4-byte instruction that raised it.
     * It uses x28 to x31 and needs x27 = 1.
     */
    private static final int[] SKIP_HANDLER = {
        csr(2, 31, 0, 0x342), // csrr x31, mcause
        bType(5, 31, 0, 16), // bge x31, x0, +16
        rType(0x33, 1, 0, 28, 27, 31), // sll x28, x27, x31
        csr(3, 0, 28, 0x304), // csrc mie, x28
        MRET,
        csr(2, 30, 0, 0x341), // csrr x30, mepc
        addi(30, 30, 4),
        iType(0x13, 3, 29, 31, 2), // sltiu x29, x31, 2 (a fetch fault)
        bType(0, 29, 0, 8), // beq x29, x0, +8
        addi(30, 1, 0), // return to x1
        csr(1, 0, 30, 0x341), // csrw mepc, x30
        MRET
    };

    /** A program at {@link #START} after {@link #SKIP_HANDLER}, ending with a jump back to the start. */
    private static RecordingBus directedProgram(int... words) {
        RecordingBus bus = new RecordingBus();
        for (int i = 0; i < SKIP_HANDLER.length; i++) {
            bus.putInt(BASE + 4 * i, SKIP_HANDLER[i]);
        }
        int at = START;
        for (int word : words) {
            bus.putInt(at, word);
            at += 4;
        }
        bus.putInt(at, jal(0, START - at));
        // A 32-bit encoding in the last halfword, so a fetch there runs off the end of RAM.
        bus.putShort(BASE + RAM_SIZE - 2, 0x0013);
        // A landing pad for "jalr x1, 2(x10)": with C, a nop and a return; without, a misaligned fetch.
        bus.putShort(BASE + 0x42, 0x0001);
        bus.putInt(BASE + 0x44, iType(0x67, 0, 0, 1, 0));
        return bus;
    }

    /** Machine mode, the handler at {@code BASE}, and fixed pointers in x2 and x8 to x13. */
    private static Consumer<RV32IMAState> directedState(int mie, int mstatus) {
        return state -> {
            state.pc = START;
            state.extraflags = 3;
            state.mtvec = BASE;
            state.mie = mie;
            state.mstatus = mstatus;
            state.regs[2] = DEVICE + 0x10;
            state.regs[6] = 0x1800; // mstatus.MPP
            state.regs[7] = 0x5a5a1234;
            state.regs[8] = DATA_MID;
            state.regs[9] = DATA_MID + 8;
            state.regs[10] = BASE + 0x40; // code: readable, write-protected
            state.regs[11] = DATA_MID + 2; // misaligned for a word atomic
            state.regs[12] = 0; // unmapped
            state.regs[13] = BASE + RAM_SIZE - 2;
            state.regs[18] = 0x888; // every implemented interrupt enable
            state.regs[27] = 1;
        };
    }

    private static void runDirected(String label, RecordingBus program, Consumer<RV32IMAState> setup) {
        for (String configName : new String[] {"full", "noC", "base"}) {
            runLockstep(
                    label + " " + configName, config(configName), program, RAM_SIZE, setup, label.hashCode(), 3_000);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"lrsc", "userMode", "interrupts", "faults", "csrHook"})
    public void directedProgramsMatchReference(String name) {
        switch (name) {
            case "lrsc" ->
                runDirected(
                        name,
                        directedProgram(
                                amo(2, 2, 5, 8, 0), // lr.w x5, (x8)
                                amo(3, 2, 6, 8, 7), // sc.w x6, x7, (x8): succeeds
                                amo(3, 2, 6, 8, 7), // fails locally
                                amo(2, 2, 5, 8, 0),
                                sType(2, 8, 0, 0), // a plain store drops the local reservation
                                amo(3, 2, 6, 8, 7),
                                amo(2, 2, 5, 8, 0),
                                amo(3, 2, 6, 9, 7), // another address
                                amo(2, 2, 5, 10, 0), // reserve code
                                amo(3, 2, 6, 10, 7), // locally valid, faults in the bus
                                amo(3, 2, 6, 10, 7), // locally failing, faults in checkAccess
                                amo(2, 2, 5, 11, 0), // misaligned
                                amo(3, 2, 6, 11, 7),
                                amo(2, 2, 5, 2, 0), // device
                                addi(14, 14, 1), // a gap for a remote reservation loss
                                amo(3, 2, 6, 2, 7),
                                amo(2, 2, 5, 8, 0),
                                amo(0, 2, 5, 8, 7), // amoadd.w keeps the reservation
                                amo(3, 2, 6, 8, 7)),
                        directedState(0, 0));
            case "userMode" ->
                runDirected(
                        name,
                        directedProgram(
                                csr(3, 0, 6, 0x300), // csrc mstatus, x6: MPP = U
                                (5 << 7) | 0x17, // auipc x5, 0
                                addi(5, 5, 16),
                                csr(1, 0, 5, 0x341), // csrw mepc, x5
                                MRET, // to user mode at the next word
                                iType(0x03, 2, 5, 8, 0), // lw x5, 0(x8)
                                sType(2, 9, 7, 0), // sw x7, 0(x9)
                                csr(2, 5, 0, 0x300), // M-mode CSR: illegal
                                csr(2, 5, 0, 0xC00), // cycle
                                csr(1, 0, 5, 0x7C0), // a hook CSR from U
                                csr(2, 5, 0, 0xC01), // time, a hook CSR today
                                ECALL,
                                EBREAK,
                                MRET, // illegal in U
                                WFI,
                                amo(2, 2, 5, 8, 0),
                                amo(3, 2, 6, 8, 7),
                                iType(0x67, 0, 1, 2, 0)), // jalr x1, 0(x2): fetch fault in U
                        directedState(0x888, 0));
            case "interrupts" ->
                runDirected(
                        name,
                        directedProgram(
                                WFI,
                                addi(5, 5, 1),
                                csr(2, 0, 6, 0x300), // csrs mstatus, x6
                                csr(6, 0, 8, 0x300), // csrsi mstatus, MIE
                                addi(5, 5, 1),
                                csr(7, 0, 8, 0x300), // csrci mstatus, MIE
                                csr(1, 15, 16, 0x304), // csrrw x15, mie, x16
                                csr(2, 0, 17, 0x344), // csrs mip, x17
                                csr(3, 0, 17, 0x344), // csrc mip, x17
                                csr(1, 0, 15, 0x304), // restore mie
                                WFI,
                                csr(2, 0, 18, 0x304), // csrs mie, x18
                                addi(5, 5, 1),
                                iType(0x13, 7, 0, 0, 0)),
                        directedState(0x888, 0x8).andThen(state -> {
                            state.regs[16] = 0x080; // MTIE only
                            state.regs[17] = 0x808; // MSIP and MEIP
                        }));
            case "faults" ->
                runDirected(
                        name,
                        directedProgram(
                                iType(0x03, 0, 5, 2, 0), // lb x5, 0(x2): device
                                iType(0x03, 1, 5, 2, 2), // lh
                                iType(0x03, 2, 5, 2, 4), // lw
                                iType(0x03, 4, 5, 2, 1), // lbu
                                iType(0x03, 5, 5, 2, 6), // lhu
                                sType(0, 2, 7, 0), // sb
                                sType(1, 2, 7, 2), // sh
                                sType(2, 2, 7, 4), // sw
                                amo(0, 2, 5, 2, 7), // amoadd.w on the device
                                amo(1, 2, 5, 10, 7), // amoswap.w on code: reads, then faults
                                amo(0, 2, 5, 11, 7), // misaligned
                                amo(0, 0, 5, 2, 7), // amoadd.b: Zabha or illegal
                                amo(28, 1, 5, 8, 7), // amomaxu.h
                                iType(0x03, 2, 5, 12, 0), // lw from unmapped
                                sType(2, 12, 7, 0), // sw to unmapped
                                sType(2, 10, 7, 0), // sw to code
                                iType(0x03, 2, 5, 8, 1), // misaligned lw in RAM
                                sType(2, 8, 7, 3), // misaligned sw in RAM
                                iType(0x67, 0, 1, 2, 0), // jalr x1, 0(x2): fetch from the device
                                iType(0x67, 0, 1, 12, 0), // jalr x1, 0(x12): unmapped
                                iType(0x67, 0, 1, 13, 0), // jalr x1, 0(x13): runs off the end of RAM
                                iType(0x67, 0, 1, 10, 2), // jalr x1, 2(x10): misaligned without C
                                0xffffffff, // illegal
                                0), // illegal
                        directedState(0, 0));
            case "csrHook" ->
                runDirected(
                        name,
                        directedProgram(
                                csr(1, 5, 7, 0x7C0), // csrrw x5, 0x7C0, x7
                                csr(1, 0, 7, 0x7C0), // csrrw x0: no read
                                csr(2, 5, 0, 0x7C0), // csrrs x5, rs1 = x0: no write
                                csr(2, 5, 7, 0x7C0),
                                csr(3, 5, 0, 0x7C0),
                                csr(3, 5, 7, 0x7C0),
                                csr(5, 5, 9, 0x7C0), // csrrwi
                                csr(5, 0, 9, 0x7C0),
                                csr(6, 5, 0, 0x7C0), // csrrsi, zero immediate
                                csr(6, 5, 9, 0x7C0),
                                csr(7, 5, 0, 0x7C0),
                                csr(7, 5, 9, 0x7C0),
                                csr(2, 5, 7, 0xC01), // time
                                csr(1, 5, 7, 0xC00), // cycle: read-only
                                csr(2, 5, 0, 0xC80), // cycleh
                                csr(1, 5, 7, 0xB00), // mcycle
                                csr(2, 5, 0, 0xF14), // mhartid
                                csr(1, 5, 7, 0x003), // fcsr
                                csr(5, 5, 3, 0x002), // frm
                                csr(6, 5, 31, 0x001), // fflags
                                csr(2, 5, 0, 0x301), // misa
                                csr(1, 5, 7, 0x301)),
                        directedState(0, 0));
            default -> throw new IllegalArgumentException(name);
        }
    }
}
