package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The decoded instruction cache: configuration, a lockstep differential against the uncached
 * core over seeded random code, and stale-entry cases for each flush rule in {@link
 * RV32IMACore#flushDecodedCache()}.
 */
public class DecodedCacheTest {
    private static final int BASE = 0x80000000;
    private static final int RAM_SIZE = 16 * 1024;
    private static final IsaConfig FULL = new IsaConfig(true, true, true, true, true);
    private static final IsaConfig NO_C = new IsaConfig(false, true, true, true, true);
    private static final int MCAUSE_FETCH_FAULT = 1;
    private static final int MCAUSE_ILLEGAL = 2;

    /**
     * Little-endian RAM at {@link #BASE}. Stores into {@code [0, writeProtectEnd)} fault, and
     * {@link #denyFetch} can reject chosen fetches.
     */
    static final class Ram implements MemoryBus {
        final byte[] bytes;
        final int base;
        int writeProtectEnd;
        FetchFilter denyFetch = (address, ctx) -> false;
        int fetches;

        interface FetchFilter {
            boolean deny(int address, AccessContext ctx);
        }

        Ram(int base, int size) {
            this.base = base;
            this.bytes = new byte[size];
        }

        private int offset(int address, int width) {
            long off = Integer.toUnsignedLong(address - base);
            if (off + width > bytes.length) {
                throw new IndexOutOfBoundsException("address 0x" + Integer.toHexString(address));
            }
            return (int) off;
        }

        private void checkStore(int address, int width) {
            if (offset(address, width) < writeProtectEnd) {
                throw new IndexOutOfBoundsException("write-protected 0x" + Integer.toHexString(address));
            }
        }

        private void checkFetch(int address, AccessContext ctx) {
            if (ctx.kind() == AccessKind.FETCH) {
                fetches++;
                if (denyFetch.deny(address, ctx)) {
                    throw new IndexOutOfBoundsException("fetch denied at 0x" + Integer.toHexString(address));
                }
            }
        }

        @Override
        public byte readByte(int address) {
            return bytes[offset(address, 1)];
        }

        @Override
        public short readShort(int address) {
            int o = offset(address, 2);
            return (short) ((bytes[o] & 0xff) | (bytes[o + 1] << 8));
        }

        @Override
        public int readInt(int address) {
            int o = offset(address, 4);
            return (bytes[o] & 0xff)
                    | ((bytes[o + 1] & 0xff) << 8)
                    | ((bytes[o + 2] & 0xff) << 16)
                    | (bytes[o + 3] << 24);
        }

        @Override
        public void writeByte(int address, byte value) {
            bytes[offset(address, 1)] = value;
        }

        @Override
        public void writeShort(int address, short value) {
            int o = offset(address, 2);
            bytes[o] = (byte) value;
            bytes[o + 1] = (byte) (value >> 8);
        }

        @Override
        public void writeInt(int address, int value) {
            int o = offset(address, 4);
            bytes[o] = (byte) value;
            bytes[o + 1] = (byte) (value >> 8);
            bytes[o + 2] = (byte) (value >> 16);
            bytes[o + 3] = (byte) (value >> 24);
        }

        @Override
        public short readShort(int address, AccessContext ctx) {
            checkFetch(address, ctx);
            return readShort(address);
        }

        @Override
        public int readInt(int address, AccessContext ctx) {
            checkFetch(address, ctx);
            return readInt(address);
        }

        @Override
        public void writeByte(int address, byte value, AccessContext ctx) {
            checkStore(address, 1);
            writeByte(address, value);
        }

        @Override
        public void writeShort(int address, short value, AccessContext ctx) {
            checkStore(address, 2);
            writeShort(address, value);
        }

        @Override
        public void writeInt(int address, int value, AccessContext ctx) {
            checkStore(address, 4);
            writeInt(address, value);
        }

        @Override
        public void checkAccess(int address, AccessContext ctx) {
            checkStore(address, ctx.width());
        }

        Ram copy() {
            Ram r = new Ram(base, bytes.length);
            System.arraycopy(bytes, 0, r.bytes, 0, bytes.length);
            r.writeProtectEnd = writeProtectEnd;
            return r;
        }
    }

    // ---- Encoders (written from the ISA tables) ----

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

    private static final int FENCE_I = 0x0000100f;
    private static final int MRET = 0x30200073;
    private static final int ECALL = 0x00000073;

    // ---- Configuration ----

    @Test
    public void configAcceptsZeroAndPowersOfTwo() {
        assertFalse(new DecodedCacheConfig(0, false).enabled());
        assertTrue(new DecodedCacheConfig(1, false).enabled());
        assertTrue(new DecodedCacheConfig(1 << 14, true).enabled());
        assertFalse(new RV32IMACore(FULL).hasDecodedCache());
        assertFalse(new RV32IMACore(FULL, DecodedCacheConfig.OFF).hasDecodedCache());
        assertTrue(new RV32IMACore(FULL, new DecodedCacheConfig(4, false)).hasDecodedCache());
    }

    @ParameterizedTest
    @CsvSource({"-1", "3", "6", "1000", "-2147483648"})
    public void configRejectsOtherSizes(int slots) {
        assertThrows(IllegalArgumentException.class, () -> new DecodedCacheConfig(slots, false));
    }

    @Test
    public void constructorRejectsNullCacheConfig() {
        assertThrows(NullPointerException.class, () -> new RV32IMACore(FULL, null));
    }

    @Test
    public void rangeValidation() {
        RV32IMACore core = new RV32IMACore(FULL, new DecodedCacheConfig(4, false));
        assertThrows(IllegalArgumentException.class, () -> core.addDecodedCacheRange(BASE, 0));
        assertThrows(IllegalArgumentException.class, () -> core.addDecodedCacheRange(0xfffffff0, 0x20));
        core.addDecodedCacheRange(0xfffffff0, 0x10);
        core.addDecodedCacheRange(0, 0xffffffff);
    }

    @Test
    public void flushingACachelessCoreDoesNothing() {
        RV32IMACore core = new RV32IMACore(FULL);
        core.flushDecodedCache();
        core.addDecodedCacheRange(BASE, RAM_SIZE);
        core.clearDecodedCacheRanges();
        assertEquals(0, core.getDecodedCacheStats().flushes());
    }

    // ---- Helpers for the directed cases ----

    private static RV32IMACore cachedCore(IsaConfig isa, boolean checkMode) {
        RV32IMACore core = new RV32IMACore(isa, new DecodedCacheConfig(64, checkMode));
        core.addDecodedCacheRange(BASE, RAM_SIZE);
        return core;
    }

    private static RV32IMAState machineState(int pc) {
        RV32IMAState state = new RV32IMAState();
        state.pc = pc;
        state.extraflags = 3;
        state.mtvec = BASE + 0x1000;
        return state;
    }

    private static void writeWords(Ram ram, int address, int... words) {
        for (int i = 0; i < words.length; i++) {
            ram.writeInt(address + 4 * i, words[i]);
        }
    }

    /** Runs {@code addi x5, x5, 1} at BASE, then jumps back: a two-instruction loop. */
    private static Ram counterLoop() {
        Ram ram = new Ram(BASE, RAM_SIZE);
        writeWords(ram, BASE, addi(5, 5, 1), jal(0, -4));
        return ram;
    }

    // ---- Basic operation and counters ----

    @Test
    public void loopHitsAfterOneFillPerInstruction() {
        Ram ram = counterLoop();
        RV32IMACore core = cachedCore(NO_C, false);
        RV32IMAState state = machineState(BASE);
        core.step(state, ram, BASE, RAM_SIZE, 0, 1000, null, null);
        assertEquals(500, state.regs[5]);
        assertEquals(2, core.getDecodedCacheStats().fills());
        assertEquals(0, core.getDecodedCacheStats().delegations());
        assertEquals(2, ram.fetches);
    }

    @Test
    public void nothingIsCachedWithoutARange() {
        Ram ram = counterLoop();
        RV32IMACore core = new RV32IMACore(NO_C, new DecodedCacheConfig(64, false));
        RV32IMAState state = machineState(BASE);
        core.step(state, ram, BASE, RAM_SIZE, 0, 100, null, null);
        assertEquals(50, state.regs[5]);
        assertEquals(0, core.getDecodedCacheStats().fills());
        assertEquals(100, core.getDecodedCacheStats().delegations());
        assertEquals(100, ram.fetches);
    }

    @Test
    public void rangeChangesFlush() {
        RV32IMACore core = cachedCore(NO_C, false);
        long before = core.getDecodedCacheStats().flushes();
        core.addDecodedCacheRange(0, 16);
        core.clearDecodedCacheRanges();
        core.flushDecodedCache();
        assertEquals(before + 3, core.getDecodedCacheStats().flushes());
    }

    @Test
    public void checkModeComparesEveryHit() {
        Ram ram = counterLoop();
        RV32IMACore core = cachedCore(NO_C, true);
        RV32IMAState state = machineState(BASE);
        core.step(state, ram, BASE, RAM_SIZE, 0, 100, null, null);
        assertEquals(98, core.getDecodedCacheStats().checks());
        assertEquals(50, state.regs[5]);
    }

    // ---- Stale-entry cases, each warming an entry first ----

    @Test
    public void revokedFetchPermissionNeedsAFlush() {
        Ram ram = counterLoop();
        RV32IMACore core = cachedCore(NO_C, false);
        RV32IMAState state = machineState(BASE);
        core.step(state, ram, BASE, RAM_SIZE, 0, 4, null, null);
        ram.denyFetch = (address, ctx) -> address == BASE;

        // Rule 2: the embedder flushes when permissions change; then the fetch faults.
        core.flushDecodedCache();
        core.step(state, ram, BASE, RAM_SIZE, 0, 4, null, null);
        assertEquals(MCAUSE_FETCH_FAULT, state.mcause);
        assertEquals(BASE, state.mepc);
        assertEquals(BASE, state.mtval);
        assertEquals(BASE + 0x1000, state.pc);
    }

    @Test
    public void checkModeReportsARevokedFetch() {
        Ram ram = counterLoop();
        RV32IMACore core = cachedCore(NO_C, true);
        RV32IMAState state = machineState(BASE);
        core.step(state, ram, BASE, RAM_SIZE, 0, 4, null, null);
        ram.denyFetch = (address, ctx) -> address == BASE;
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> core.step(state, ram, BASE, RAM_SIZE, 0, 4, null, null));
        assertTrue(e.getMessage().contains("0x80000000"), e.getMessage());
    }

    @Test
    public void windowIsCheckedOnEveryHit() {
        Ram ram = counterLoop();
        RV32IMACore core = cachedCore(NO_C, false);
        RV32IMAState state = machineState(BASE + 4);
        core.step(state, ram, BASE, RAM_SIZE, 0, 3, null, null);
        // Shrink the window so the warmed jal at BASE + 4 lies outside it.
        state.pc = BASE + 4;
        core.step(state, ram, BASE, 4, 0, 1, null, null);
        assertEquals(MCAUSE_FETCH_FAULT, state.mcause);
        assertEquals(BASE + 4, state.mtval);
    }

    @Test
    public void remappedWindowDoesNotAliasOffsets() {
        // The same window offset holds different code in two windows of one bus.
        Ram ram = new Ram(BASE, RAM_SIZE);
        writeWords(ram, BASE, addi(5, 0, 1));
        writeWords(ram, BASE + 0x2000, addi(5, 0, 2));
        RV32IMACore core = new RV32IMACore(NO_C, new DecodedCacheConfig(64, false));
        core.addDecodedCacheRange(BASE, RAM_SIZE);
        RV32IMAState state = machineState(BASE);
        core.step(state, ram, BASE, 0x1000, 0, 1, null, null);
        assertEquals(1, state.regs[5]);
        state.pc = BASE + 0x2000;
        core.step(state, ram, BASE + 0x2000, 0x1000, 0, 1, null, null);
        assertEquals(2, state.regs[5]);
    }

    @Test
    public void changedBusStateOrHartFlushes() {
        Ram first = new Ram(BASE, RAM_SIZE);
        writeWords(first, BASE, addi(5, 0, 1));
        Ram second = new Ram(BASE, RAM_SIZE);
        writeWords(second, BASE, addi(5, 0, 2));
        RV32IMACore core = cachedCore(NO_C, false);
        RV32IMAState state = machineState(BASE);
        core.step(state, first, BASE, RAM_SIZE, 0, 1, null, null);

        // Another bus: its own code runs.
        state.pc = BASE;
        core.step(state, second, BASE, RAM_SIZE, 0, 1, null, null);
        assertEquals(2, state.regs[5]);

        // Another state instance and another hart ID each flush too.
        long flushes = core.getDecodedCacheStats().flushes();
        RV32IMAState other = machineState(BASE);
        core.step(other, second, BASE, RAM_SIZE, 0, 1, null, null);
        assertEquals(flushes + 1, core.getDecodedCacheStats().flushes());
        other.pc = BASE;
        other.hartId = 7;
        second.denyFetch = (address, ctx) -> ctx.hartId() == 7;
        core.step(other, second, BASE, RAM_SIZE, 0, 1, null, null);
        assertEquals(flushes + 2, core.getDecodedCacheStats().flushes());
        assertEquals(MCAUSE_FETCH_FAULT, other.mcause);
    }

    @Test
    public void machineModeEntryNeverHitsInUserMode() {
        // M-mode warms addi at BASE + 8, then MRETs to user mode at the same PC, which the bus
        // forbids user mode to fetch.
        Ram ram = new Ram(BASE, RAM_SIZE);
        writeWords(ram, BASE, jal(0, 8), 0, addi(5, 5, 1), MRET);
        ram.denyFetch = (address, ctx) -> ctx.privilege() == 0;
        RV32IMACore core = cachedCore(NO_C, false);
        RV32IMAState state = machineState(BASE);
        state.mepc = BASE + 8;
        core.step(state, ram, BASE, RAM_SIZE, 0, 3, null, null);
        assertEquals(1, state.regs[5]);
        assertEquals(0, state.extraflags & 3);
        core.step(state, ram, BASE, RAM_SIZE, 0, 1, null, null);
        assertEquals(1, state.regs[5]);
        assertEquals(MCAUSE_FETCH_FAULT, state.mcause);
        assertEquals(BASE + 8, state.mepc);
    }

    /**
     * A guest that patches the instruction at BASE + 0x100 ({@code addi x5, x0, 1} to {@code
     * addi x5, x0, 2}) after running it once, optionally executes FENCE.I, and runs it again.
     */
    private static Ram selfModifyingGuest(boolean fence) {
        Ram ram = new Ram(BASE, RAM_SIZE);
        int target = BASE + 0x100;
        writeWords(ram, target, addi(5, 0, 1), iType(0x67, 0, 0, 1, 0)); // addi; jalr x0, 0(x1)
        writeWords(
                ram,
                BASE,
                lui(6, target),
                addi(6, 6, target - BASE), // x6 = target
                jal(1, 0x100 - 8), // call target (warms it)
                lui(7, addi(5, 0, 2) + 0x800), // x7 = the new instruction word
                addi(7, 7, (addi(5, 0, 2) << 20) >> 20),
                sType(2, 6, 7, 0), // sw x7, 0(x6)
                fence ? FENCE_I : addi(0, 0, 0),
                jal(1, 0x100 - 28)); // call target again
        return ram;
    }

    @Test
    public void fenceIMakesNewCodeVisible() {
        Ram ram = selfModifyingGuest(true);
        RV32IMACore core = cachedCore(NO_C, true);
        RV32IMAState state = machineState(BASE);
        core.step(state, ram, BASE, RAM_SIZE, 0, 12, null, null);
        assertEquals(2, state.regs[5]);
        assertEquals(BASE + 0x20, state.pc);
    }

    @Test
    public void missingFenceIIsStaleAndCheckModeThrows() {
        // Without check mode the old instruction may run (rule 1: unspecified until FENCE.I).
        RV32IMACore plain = cachedCore(NO_C, false);
        RV32IMAState state = machineState(BASE);
        plain.step(state, selfModifyingGuest(false), BASE, RAM_SIZE, 0, 12, null, null);
        assertEquals(1, state.regs[5]);

        RV32IMACore checked = cachedCore(NO_C, true);
        RV32IMAState checkedState = machineState(BASE);
        Ram ram = selfModifyingGuest(false);
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> checked.step(checkedState, ram, BASE, RAM_SIZE, 0, 12, null, null));
        assertTrue(e.getMessage().contains("0x80000100"), e.getMessage());
    }

    @Test
    public void fenceIFromUncacheableCodeStillFlushes() {
        // The FENCE.I runs from outside the cacheable range, so the cached path delegates it.
        Ram ram = selfModifyingGuest(true);
        RV32IMACore core = new RV32IMACore(NO_C, new DecodedCacheConfig(64, true));
        core.addDecodedCacheRange(BASE + 0x100, 0x100);
        RV32IMAState state = machineState(BASE);
        core.step(state, ram, BASE, RAM_SIZE, 0, 12, null, null);
        assertEquals(2, state.regs[5]);
    }

    @Test
    public void hostWriteThroughAnAliasNeedsAnEmbedderFlush() {
        // Two windows alias one RAM; the host patches code through the second.
        Ram ram = counterLoop();
        MemoryBus alias = new MemoryBus() {
            private int phys(int address) {
                return address - 0x1000_0000;
            }

            @Override
            public byte readByte(int address) {
                return ram.readByte(phys(address));
            }

            @Override
            public short readShort(int address) {
                return ram.readShort(phys(address));
            }

            @Override
            public int readInt(int address) {
                return ram.readInt(phys(address));
            }

            @Override
            public void writeByte(int address, byte value) {
                ram.writeByte(phys(address), value);
            }

            @Override
            public void writeShort(int address, short value) {
                ram.writeShort(phys(address), value);
            }

            @Override
            public void writeInt(int address, int value) {
                ram.writeInt(phys(address), value);
            }
        };
        RV32IMACore core = cachedCore(NO_C, true);
        RV32IMAState state = machineState(BASE);
        core.step(state, ram, BASE, RAM_SIZE, 0, 10, null, null);
        assertEquals(5, state.regs[5]);
        alias.writeInt(BASE + 0x1000_0000, addi(5, 5, 100));
        core.flushDecodedCache();
        core.step(state, ram, BASE, RAM_SIZE, 0, 10, null, null);
        assertEquals(505, state.regs[5]);
    }

    @Test
    public void instructionStraddlingARangeEndIsNeverCached() {
        // The range covers [BASE, BASE + 6): the jal at BASE + 4 straddles its end.
        Ram ram = counterLoop();
        RV32IMACore core = new RV32IMACore(FULL, new DecodedCacheConfig(64, true));
        core.addDecodedCacheRange(BASE, 6);
        RV32IMAState state = machineState(BASE);
        core.step(state, ram, BASE, RAM_SIZE, 0, 100, null, null);
        assertEquals(50, state.regs[5]);
        assertEquals(1, core.getDecodedCacheStats().fills());
        assertEquals(50, core.getDecodedCacheStats().delegations());
    }

    @Test
    public void reservedCompressedEncodingIsNotCached() {
        Ram ram = new Ram(BASE, RAM_SIZE);
        ram.writeShort(BASE, (short) 0x0000); // the all-zero halfword is reserved
        RV32IMACore core = cachedCore(FULL, false);
        RV32IMAState state = machineState(BASE);
        core.step(state, ram, BASE, RAM_SIZE, 0, 1, null, null);
        assertEquals(MCAUSE_ILLEGAL, state.mcause);
        assertEquals(0, core.getDecodedCacheStats().fills());
    }

    @Test
    public void fetchFaultInsideARangeTrapsWithoutAHook() {
        Ram ram = counterLoop();
        ram.denyFetch = (address, ctx) -> true;
        RV32IMACore core = cachedCore(NO_C, false);
        RV32IMAState state = machineState(BASE);
        List<Integer> hookPcs = new ArrayList<>();
        core.step(state, ram, BASE, RAM_SIZE, 0, 5, (pc, ir, trap) -> hookPcs.add(pc), null);
        assertEquals(MCAUSE_FETCH_FAULT, state.mcause);
        assertEquals(BASE, state.mtval);
        assertEquals(1, state.getCycle());
        assertTrue(hookPcs.isEmpty());
        assertEquals(0, core.getDecodedCacheStats().fills());
    }

    @Test
    public void ecallFromCachedCodeTraps() {
        Ram ram = new Ram(BASE, RAM_SIZE);
        writeWords(ram, BASE, addi(5, 0, 3), ECALL);
        RV32IMACore core = cachedCore(NO_C, false);
        RV32IMAState state = machineState(BASE);
        core.step(state, ram, BASE, RAM_SIZE, 0, 10, null, null);
        assertEquals(11, state.mcause);
        assertEquals(BASE + 4, state.mepc);
        assertEquals(BASE + 0x1000, state.pc);
        assertEquals(2, state.getCycle());
    }

    // ---- Lockstep differential over seeded random code ----

    private static final int CODE_SIZE = 4096;
    private static final int DATA_MID = BASE + 0x3000;
    private static final int[] POINTER_REGS = {2, 8, 9, 10, 11};
    private static final int[] CSRS = {
        0x300, 0x304, 0x344, 0x340, 0x341, 0x342, 0x343, 0xC00, 0x001, 0x002, 0x003, 0x301
    };

    /**
     * Random code for {@code [BASE, BASE + CODE_SIZE)}. A fixed trap handler at {@code BASE}
     * jumps to a pseudo-random word in {@code [BASE + 0x100, BASE + 0x900)}, so execution keeps
     * moving after traps.
     */
    private static Ram randomProgram(Random rnd, boolean hasC) {
        Ram ram = new Ram(BASE, RAM_SIZE);
        writeWords(
                ram,
                BASE,
                iType(0x73, 2, 7, 0, 0xC00), // csrr x7, cycle
                iType(0x13, 7, 7, 7, 0x7fc), // andi x7, x7, 0x7fc
                lui(6, BASE),
                rType(0x33, 0, 0, 7, 7, 6), // add x7, x7, x6
                addi(7, 7, 0x100),
                iType(0x67, 0, 0, 7, 0)); // jalr x0, 0(x7)
        int at = 24;
        while (at < CODE_SIZE - 8) {
            if (hasC && rnd.nextInt(10) < 3) {
                ram.writeShort(BASE + at, (short) ((rnd.nextInt(0x10000) & ~3) | rnd.nextInt(3)));
                at += 2;
                continue;
            }
            int rd = rnd.nextInt(32);
            int rs1 = rnd.nextInt(32);
            int rs2 = rnd.nextInt(32);
            int ptr = POINTER_REGS[rnd.nextInt(POINTER_REGS.length)];
            int word;
            int kind = rnd.nextInt(100);
            if (kind < 8) {
                ram.writeInt(BASE + at, lui(ptr, DATA_MID));
                at += 4;
                word = addi(ptr, ptr, (rnd.nextInt(512) - 256) * 4);
            } else if (kind < 25) {
                int funct3 = rnd.nextInt(8);
                int funct7 = funct3 == 1 || funct3 == 5 ? new int[] {0, 0, 0x20, 0x30, 0x14, 0x34}[rnd.nextInt(6)] : 0;
                int imm = funct3 == 1 || funct3 == 5 ? (funct7 << 5) | rnd.nextInt(32) : rnd.nextInt(4096);
                if (funct7 == 0x14) {
                    imm = (0x14 << 5) | 7;
                } else if (funct7 == 0x34) {
                    imm = (0x34 << 5) | 0x18;
                }
                word = iType(0x13, funct3, rd, rs1, imm);
            } else if (kind < 40) {
                int funct7 = new int[] {0, 0, 0, 0x20, 0x01, 0x01, 0x10, 0x05, 0x30, 0x04}[rnd.nextInt(10)];
                word = rType(0x33, rnd.nextInt(8), funct7, rd, rs1, rs2);
            } else if (kind < 44) {
                word = (rnd.nextInt() & 0xfffff000) | (rd << 7) | (rnd.nextBoolean() ? 0x37 : 0x17);
            } else if (kind < 54) {
                word = iType(0x03, rnd.nextInt(8), rd, ptr, rnd.nextInt(128) - 64);
            } else if (kind < 62) {
                word = sType(rnd.nextInt(4), ptr, rs2, rnd.nextInt(128) - 64);
            } else if (kind < 72) {
                word = bType(rnd.nextInt(8), rs1, rs2, (rnd.nextInt(128) - 64) * 2);
            } else if (kind < 76) {
                word = jal(rd, (rnd.nextInt(256) - 128) * 2);
            } else if (kind < 78) {
                word = iType(0x67, 0, rd, rs1, rnd.nextInt(4096));
            } else if (kind < 80) {
                word = rnd.nextBoolean() ? FENCE_I : 0x0ff0000f;
            } else if (kind < 86) {
                int funct3 = new int[] {1, 2, 3, 5, 6, 7}[rnd.nextInt(6)];
                word = iType(0x73, funct3, rd, rs1, CSRS[rnd.nextInt(CSRS.length)]);
            } else if (kind < 88) {
                word = new int[] {ECALL, 0x00100073, MRET, 0x10500073}[rnd.nextInt(4)];
            } else if (kind < 91) {
                word = rnd.nextBoolean()
                        ? iType(0x07, 2, rd, ptr, rnd.nextInt(64) * 4 - 128)
                        : (sType(2, ptr, rs2, rnd.nextInt(64) * 4 - 128) & ~0x7f) | 0x27;
            } else if (kind < 95) {
                int funct7 = new int[] {0x00, 0x04, 0x08, 0x0C, 0x2C, 0x60, 0x68, 0x10, 0x14, 0x50, 0x70, 0x78}
                        [rnd.nextInt(12)];
                int rs2Field = funct7 == 0x2C || funct7 == 0x70 || funct7 == 0x78 ? 0 : rs2 & 1;
                word = rType(0x53, rnd.nextInt(8), funct7, rd, rs1, funct7 >= 0x60 ? rs2Field : rs2);
            } else if (kind < 96) {
                word = (rnd.nextInt(32) << 27)
                        | rType(new int[] {0x43, 0x47, 0x4B, 0x4F}[rnd.nextInt(4)], rnd.nextInt(8), 0, rd, rs1, rs2);
            } else if (kind < 99) {
                int funct5 = new int[] {0, 1, 2, 3, 4, 8, 12, 16, 20, 24, 28}[rnd.nextInt(11)];
                int funct3 = rnd.nextInt(4) == 0 ? rnd.nextInt(2) : 2;
                word = rType(0x2f, funct3, (funct5 << 2) | rnd.nextInt(4), rd, ptr, funct5 == 2 ? 0 : rs2);
            } else {
                word = rnd.nextInt();
            }
            ram.writeInt(BASE + at, word);
            at += 4;
        }
        ram.writeProtectEnd = CODE_SIZE;
        return ram;
    }

    private static void seedState(RV32IMAState state, Random rnd) {
        state.pc = BASE + 0x100;
        state.extraflags = 3;
        state.mtvec = BASE;
        for (int i = 1; i < 32; i++) {
            state.regs[i] = rnd.nextInt();
        }
        for (int reg : POINTER_REGS) {
            state.regs[reg] = DATA_MID;
        }
        for (int i = 0; i < 32; i++) {
            state.fregs[i] = 0xffffffff00000000L | Integer.toUnsignedLong(rnd.nextInt());
        }
    }

    private record HookEvent(int pc, int ir, int trap) {}

    private static String describe(RV32IMAState s) {
        StringBuilder b = new StringBuilder();
        b.append("pc=").append(Integer.toHexString(s.pc));
        for (int v : s.regs) {
            b.append(' ').append(Integer.toHexString(v));
        }
        for (long v : s.fregs) {
            b.append(' ').append(Long.toHexString(v));
        }
        return b.append(" mstatus=")
                .append(s.mstatus)
                .append(" mie=")
                .append(s.mie)
                .append(" mip=")
                .append(s.mip)
                .append(" mepc=")
                .append(s.mepc)
                .append(" mcause=")
                .append(s.mcause)
                .append(" mtval=")
                .append(s.mtval)
                .append(" mscratch=")
                .append(s.mscratch)
                .append(" mtvec=")
                .append(s.mtvec)
                .append(" extraflags=")
                .append(s.extraflags)
                .append(" fcsr=")
                .append(s.fcsr)
                .append(" cycle=")
                .append(s.getCycle())
                .append(" timer=")
                .append(s.getTimer())
                .append(" timermatch=")
                .append(s.getTimerMatch())
                .append(" resv=")
                .append(s.reservationValid)
                .append('/')
                .append(s.reservationAddr)
                .toString();
    }

    @ParameterizedTest
    @CsvSource({
        "1, true, 4, false",
        "2, true, 4096, false",
        "3, false, 4, false",
        "4, false, 4096, false",
        "5, true, 1, false",
        "6, true, 64, true",
        "7, false, 64, true",
        "8, true, 16384, false"
    })
    public void lockstepMatchesUncachedCore(long seed, boolean hasC, int slots, boolean checkMode) {
        Random rnd = new Random(seed);
        IsaConfig isa = hasC ? FULL : NO_C;
        Ram plainRam = randomProgram(rnd, hasC);
        Ram cachedRam = plainRam.copy();
        RV32IMAState plainState = new RV32IMAState();
        seedState(plainState, new Random(seed + 1000));
        RV32IMAState cachedState = new RV32IMAState();
        seedState(cachedState, new Random(seed + 1000));
        RV32IMACore plain = new RV32IMACore(isa);
        RV32IMACore cached = new RV32IMACore(isa, new DecodedCacheConfig(slots, checkMode));
        cached.addDecodedCacheRange(BASE, CODE_SIZE);
        List<HookEvent> plainHooks = new ArrayList<>();
        List<HookEvent> cachedHooks = new ArrayList<>();

        for (int iteration = 0; iteration < 40_000; iteration++) {
            int event = rnd.nextInt(200);
            if (event == 0) {
                int bit = rnd.nextBoolean() ? 3 : 11;
                RV32IMACore.injectInterrupt(plainState, bit);
                RV32IMACore.injectInterrupt(cachedState, bit);
            } else if (event == 1) {
                plainState.mip = 0;
                cachedState.mip = 0;
            } else if (event == 2) {
                long match = plainState.getTimer() + rnd.nextInt(50);
                plainState.setTimerMatch(match);
                cachedState.setTimerMatch(match);
            } else if (event == 3) {
                plainState.extraflags &= ~4; // release a WFI stall
                cachedState.extraflags &= ~4;
            }
            int count = 1 + rnd.nextInt(24);
            int elapsed = rnd.nextInt(3);
            int plainResult = plain.step(
                    plainState,
                    plainRam,
                    BASE,
                    RAM_SIZE,
                    elapsed,
                    count,
                    (pc, ir, trap) -> plainHooks.add(new HookEvent(pc, ir, trap)),
                    null);
            int cachedResult = cached.step(
                    cachedState,
                    cachedRam,
                    BASE,
                    RAM_SIZE,
                    elapsed,
                    count,
                    (pc, ir, trap) -> cachedHooks.add(new HookEvent(pc, ir, trap)),
                    null);
            String where = "seed " + seed + ", iteration " + iteration;
            assertEquals(plainResult, cachedResult, where);
            assertEquals(describe(plainState), describe(cachedState), where);
            assertEquals(plainHooks, cachedHooks, where);
            assertArrayEquals(plainRam.bytes, cachedRam.bytes, where);
            plainHooks.clear();
            cachedHooks.clear();
        }
        long executed = cachedState.getCycle();
        RV32IMACore.DecodedCacheStats stats = cached.getDecodedCacheStats();
        long hits = executed - stats.fills() - stats.bypasses();
        // Tiny caches thrash on random code; larger ones must serve a good share of it.
        if (slots >= 64) {
            assertTrue(hits > executed / 4, "too few hits: " + stats + ", executed " + executed);
        }
    }
}
