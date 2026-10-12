package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Zicntr counters and {@code time} reads (docs/TIMER_COUNTER_DESIGN.md, "Counters" and "Time
 * sampling"; step T6). Cores come from {@link TestCores}, so the build runs these uncached and with
 * the decoded cache at two sizes.
 */
public class CounterTest {
    private static final int RAM_OFFSET = 0x80000000;
    private static final int RAM_SIZE = 1024;
    private static final int HANDLER = RAM_OFFSET + 0x200;
    private static final int DEVICE = 0x10000000;
    private static final int EXC_ILLEGAL_INSTRUCTION = 2;
    private static final int MSIP = 1 << 3;
    private static final int MTIP = 1 << 7;
    private static final int MCAUSE_SOFTWARE = 0x80000003;
    private static final int MCAUSE_TIMER = 0x80000007;

    private static final int NOP = 0x00000013;
    private static final int ECALL = 0x00000073;
    private static final int EBREAK = 0x00100073;
    private static final int WFI = 0x10500073;
    private static final int FENCE_I = 0x0000100f;

    private static int csr(int funct3, int rd, int rs1, int csrNo) {
        return (csrNo << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x73;
    }

    private static int csrr(int rd, int csrNo) {
        return csr(2, rd, 0, csrNo);
    }

    private static int csrw(int csrNo, int rs1) {
        return csr(1, 0, rs1, csrNo);
    }

    private static int sw(int rs1, int rs2, int imm) {
        return (((imm >> 5) & 0x7f) << 25) | (rs2 << 20) | (rs1 << 15) | (2 << 12) | ((imm & 0x1f) << 7) | 0x23;
    }

    /** A source that advances by one on every read and counts its reads. */
    private static final class ScriptedSource implements MachineTimeSource {
        long next;
        int reads;

        ScriptedSource(long start) {
            next = start;
        }

        @Override
        public long nowMicros() {
            reads++;
            return next++;
        }
    }

    /** Stores what the guest writes, so a hook-held {@code mcountinhibit} reads back. */
    private static final class StoringCsrHook implements CSRHook {
        final Map<Integer, Integer> values = new HashMap<>();
        int reads;

        @Override
        public int handleRead(int csrNo) {
            reads++;
            return values.getOrDefault(csrNo, 0);
        }

        @Override
        public void handleWrite(int csrNo, int value) {
            values.put(csrNo, value);
        }
    }

    /**
     * One M-mode hart at {@link #RAM_OFFSET} running {@code program}, with its trap vector at
     * {@link #HANDLER} and a device at {@link #DEVICE} whose word writes set the compare.
     */
    private static final class Machine implements AutoCloseable {
        final FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET);
        final RV32IMAState state = new RV32IMAState();
        final StoringCsrHook csrHook = new StoringCsrHook();
        final MMIOBus bus = new MMIOBus(ram);
        final RV32IMACore core;

        Machine(MachineTimeSource time, int... program) {
            for (int i = 0; i < program.length; i++) {
                ram.writeInt(RAM_OFFSET + 4 * i, program[i]);
            }
            bus.registerHook(DEVICE, 0x100, new HardwareHook() {
                @Override
                public void handleWrite(int address, int value, int width) {
                    state.setTimerMatch(Integer.toUnsignedLong(value));
                }

                @Override
                public int handleRead(int address, int width) {
                    return 0;
                }
            });
            core = TestCores.create(IsaConfig.RV32IMA_ZICSR, time);
            state.pc = RAM_OFFSET;
            state.extraflags = 3;
            state.mtvec = HANDLER;
        }

        Machine(int... program) {
            this(new ManualTimeSource(), program);
        }

        int step(int count) {
            return core.step(state, bus, RAM_OFFSET, RAM_SIZE, count, null, csrHook);
        }

        @Override
        public void close() {
            ram.close();
        }
    }

    // ---- Reads ----

    /** A low-word rollover exactly at the reading instruction: the read sees the value before it. */
    @ParameterizedTest(name = "0x{0}/0x{1}")
    @CsvSource({"C00, C80", "B00, B80", "C02, C82", "B02, B82"})
    public void readsSeeTheValueBeforeTheReadingInstruction(String lowHex, String highHex) {
        int low = Integer.parseInt(lowHex, 16);
        int high = Integer.parseInt(highHex, 16);
        try (Machine m = new Machine(csrr(5, low), csrr(6, high), csrr(7, low))) {
            m.state.setCycle(0xFFFF_FFFFL);
            m.state.setInstret(0xFFFF_FFFFL);

            m.step(3);

            assertEquals(0xFFFF_FFFF, m.state.regs[5], "low read before the rollover");
            assertEquals(1, m.state.regs[6], "high read after it");
            assertEquals(1, m.state.regs[7]);
            assertEquals(0x1_0000_0002L, m.state.getCycle());
            assertEquals(0x1_0000_0002L, m.state.getInstret());
        }
    }

    /** The RV32 high/low/high loop: every read samples afresh, so the retry sees the carry. */
    @Test
    public void timeReadsSampleAfreshForTheHighLowHighLoop() {
        ScriptedSource time = new ScriptedSource(0xFFFF_FFFEL);
        try (Machine m = new Machine(time, csrr(5, 0xC81), csrr(6, 0xC01), csrr(7, 0xC81))) {
            m.step(3);

            assertEquals(0, m.state.regs[5]); // 0xFFFF_FFFF: the entry sample was 0xFFFF_FFFE
            assertEquals(0, m.state.regs[6]); // 0x1_0000_0000
            assertEquals(1, m.state.regs[7]); // 0x1_0000_0001: high changed, so the guest retries
            assertEquals(4, time.reads);
            assertEquals(0x1_0000_0001L, m.state.getTimer());
        }
    }

    // ---- Retirement ----

    @Test
    public void exceptionsInterruptsAndStallsDoNotRetireButWfiDoes() {
        try (Machine m = new Machine(
                NOP, // 0x00
                ECALL, // 0x04
                EBREAK, // 0x08
                0, // 0x0C: illegal
                WFI, // 0x10
                csr(6, 0, 8, 0x300), // 0x14: csrsi mstatus, MIE
                NOP)) { // 0x18
            m.step(4); // NOP retires, ECALL ends the batch
            assertEquals(2, m.state.getCycle());
            assertEquals(1, m.state.getInstret());
            assertEquals(11, m.state.mcause);

            m.state.pc = RAM_OFFSET + 0x08;
            m.step(4);
            m.state.pc = RAM_OFFSET + 0x0C;
            m.step(4);
            m.state.pc = RAM_OFFSET + RAM_SIZE; // a fetch fault
            m.step(4);
            assertEquals(1, m.state.mcause);
            assertEquals(5, m.state.getCycle(), "trapping instructions are attempted");
            assertEquals(1, m.state.getInstret(), "but do not retire");

            m.state.pc = RAM_OFFSET + 0x10;
            assertEquals(1, m.step(4), "WFI stalls");
            assertEquals(6, m.state.getCycle());
            assertEquals(2, m.state.getInstret(), "an executed WFI retires");
            assertEquals(1, m.step(4), "still stalled");
            assertEquals(6, m.state.getCycle());
            assertEquals(2, m.state.getInstret());

            // An instruction followed by an interrupt in the same call retires.
            m.state.extraflags = 3;
            m.state.pc = RAM_OFFSET + 0x14;
            m.state.mstatus = 0;
            m.state.mie = MSIP;
            m.state.mip = MSIP;
            m.step(4);
            assertEquals(MCAUSE_SOFTWARE, m.state.mcause);
            assertEquals(RAM_OFFSET + 0x18, m.state.mepc);
            assertEquals(7, m.state.getCycle());
            assertEquals(3, m.state.getInstret());

            // An interrupt-only entry attempts and retires nothing.
            m.state.pc = RAM_OFFSET + 0x18;
            m.state.mstatus = 0x8;
            m.step(4);
            assertEquals(MCAUSE_SOFTWARE, m.state.mcause);
            assertEquals(RAM_OFFSET + 0x18, m.state.mepc);
            assertEquals(7, m.state.getCycle());
            assertEquals(3, m.state.getInstret());
        }
    }

    // ---- Writes ----

    @Test
    public void writesReplaceOneHalfAfterTheInstructionWithoutItsOwnIncrement() {
        try (Machine m = new Machine(
                csrw(0xB00, 1), // mcycle = 0x1000
                csrr(5, 0xB00),
                csrw(0xB80, 2), // mcycleh = 7; the low half keeps its value from before
                csrr(6, 0xC00),
                csrr(7, 0xC80),
                csrw(0xB02, 1), // minstret = 0x1000
                csrr(8, 0xB02),
                csrw(0xB82, 2),
                csrr(9, 0xC02),
                csrr(10, 0xC82))) {
            m.state.setCycle(100);
            m.state.setInstret(50);
            m.state.regs[1] = 0x1000;
            m.state.regs[2] = 7;

            m.step(10);

            assertEquals(0x1000, m.state.regs[5], "the written value, read straight after");
            assertEquals(0x1001, m.state.regs[6]);
            assertEquals(7, m.state.regs[7]);
            assertEquals(0x1000, m.state.regs[8]);
            assertEquals(0x1001, m.state.regs[9]);
            assertEquals(7, m.state.regs[10]);
            assertEquals(0x7_0000_1008L, m.state.getMcycle());
            assertEquals(0x7_0000_1003L, m.state.getMinstret());
            assertEquals(110, m.state.getCycle(), "guest writes leave the work counts alone");
            assertEquals(60, m.state.getInstret());
        }
    }

    @Test
    public void writeFollowedByAnInterruptInTheSameCallKeepsTheWrittenValue() {
        try (Machine m = new Machine(
                csrw(0xB02, 1), // minstret = 0x1000
                csrw(0xB00, 1), // mcycle = 0x1000
                csr(6, 0, 8, 0x300), // csrsi mstatus, MIE: the pending MSIP is taken
                NOP)) {
            m.state.regs[1] = 0x1000;
            m.state.mie = MSIP;
            m.state.mip = MSIP;

            m.step(4);

            assertEquals(MCAUSE_SOFTWARE, m.state.mcause);
            assertEquals(RAM_OFFSET + 12, m.state.mepc);
            assertEquals(0x1002L, m.state.getMinstret()); // the mcycle write and csrsi retired
            assertEquals(0x1001L, m.state.getMcycle()); // csrsi
            assertEquals(3, m.state.getCycle());
            assertEquals(3, m.state.getInstret());
        }
    }

    @Test
    public void workCountSettersMoveTheArchitecturalViewAndOffsetSettersDoNot() {
        RV32IMAState state = new RV32IMAState();
        state.setCycle(10);
        state.setMcycle(100);
        assertEquals(10, state.getCycle());
        assertEquals(100, state.getMcycle());
        state.setCycle(20);
        assertEquals(110, state.getMcycle());

        state.setInstret(5);
        state.setMinstret(50);
        assertEquals(5, state.getInstret());
        assertEquals(50, state.getMinstret());
        state.setInstret(6);
        assertEquals(51, state.getMinstret());

        // A reset that wants both at zero sets the work count first.
        state.setCycle(0);
        state.setMcycle(0);
        assertEquals(0, state.getCycle());
        assertEquals(0, state.getMcycle());
    }

    @Test
    public void hostEditsBetweenCallsAreSeenByTheNextCall() {
        try (Machine m = new Machine(NOP, csrr(5, 0xC00), csrr(6, 0xC02))) {
            m.step(1);
            m.state.setMcycle(0x500);
            m.state.setInstret(0x40);

            m.step(2);

            assertEquals(0x500, m.state.regs[5]);
            assertEquals(0x41, m.state.regs[6]); // the cycle read retired after the edit
        }
    }

    // ---- mcounteren and mcountinhibit ----

    /** User mode needs each counter's bit; the check precedes any read, hook call or sample. */
    @ParameterizedTest(name = "0x{0}")
    @ValueSource(strings = {"C00", "C01", "C02", "C80", "C81", "C82"})
    public void userCounterReadsNeedTheirMcounterenBit(String csrHex) {
        int csrNo = Integer.parseInt(csrHex, 16);
        int bit = 1 << (csrNo & 3);
        int ir = csrr(5, csrNo);

        ScriptedSource time = new ScriptedSource(100);
        try (Machine m = new Machine(time, ir)) {
            m.state.extraflags = 0;
            m.state.mcounteren = ~bit; // every other bit, implemented or not
            m.state.regs[5] = 0x5a5a;
            m.step(1);
            assertEquals(EXC_ILLEGAL_INSTRUCTION, m.state.mcause);
            assertEquals(ir, m.state.mtval);
            assertEquals(0x5a5a, m.state.regs[5]);
            assertEquals(1, time.reads, "no sample for a trapping time read");
            assertEquals(0, m.csrHook.reads);
        }

        try (Machine m = new Machine(ir)) {
            m.state.extraflags = 0;
            m.state.mcounteren = bit;
            m.step(1);
            assertEquals(RAM_OFFSET + 4, m.state.pc, "allowed");
        }

        try (Machine m = new Machine(ir)) {
            m.step(1); // machine mode, mcounteren 0
            assertEquals(RAM_OFFSET + 4, m.state.pc, "machine mode always reads");
        }
    }

    @Test
    public void mcounterenKeepsOnlyItsImplementedBitsAndGatesNoOtherCounter() {
        try (Machine m = new Machine(csrw(0x306, 1), csrr(5, 0x306), NOP, csrr(6, 0x306))) {
            m.state.regs[1] = -1;
            m.step(2);
            assertEquals(7, m.state.mcounteren);
            assertEquals(7, m.state.regs[5]);

            m.state.mcounteren = 0xF0; // a host edit with only unimplemented bits
            m.step(2);
            assertEquals(0, m.state.regs[6]);
        }

        // hpmcounter3 is not gated: it is the hook's, as other read-only numbers are.
        try (Machine m = new Machine(csrr(5, 0xC03))) {
            m.state.extraflags = 0;
            m.csrHook.values.put(0xC03, 0x33);
            m.step(1);
            assertEquals(0x33, m.state.regs[5]);
            assertEquals(1, m.csrHook.reads);
        }
    }

    @Test
    public void hookStoredMcountinhibitInhibitsNothing() {
        try (Machine m = new Machine(
                csr(5, 0, 7, 0x320), // csrwi mcountinhibit, CY | TM | IR
                csrr(5, 0x320),
                csrr(6, 0xC00),
                csrr(7, 0xC02))) {
            m.step(4);

            assertEquals(7, m.state.regs[5], "the hook holds the value");
            assertEquals(2, m.state.regs[6]);
            assertEquals(3, m.state.regs[7]);
            assertEquals(4, m.state.getCycle());
            assertEquals(4, m.state.getInstret());
        }
    }

    // ---- time reads and MTIP ----

    @Test
    public void timeReadThatCrossesTheDeadlineIsTakenAfterTheReadInTheSameCall() {
        ScriptedSource time = new ScriptedSource(100);
        try (Machine m = new Machine(time, csrr(5, 0xC01), NOP, NOP)) {
            m.state.setTimerMatch(101);
            m.state.mie = MTIP;
            m.state.mstatus = 0x8;

            m.step(3);

            assertEquals(101, m.state.regs[5]);
            assertEquals(MCAUSE_TIMER, m.state.mcause);
            assertEquals(RAM_OFFSET + 4, m.state.mepc);
            assertEquals(HANDLER, m.state.pc);
            assertEquals(1, m.state.getInstret(), "the read retired");
            assertEquals(2, time.reads);
        }
    }

    /**
     * A time read crosses the deadline but is not deliverable; a compare edit through MMIO and a
     * later reevaluation then use the read's sample, not the call's entry sample, on either side
     * of a {@code FENCE.I} (which the cached path flushes and resumes on).
     */
    @ParameterizedTest(name = "FENCE.I {0}, compare {1}")
    @CsvSource({"false, 101", "true, 101", "false, 102", "true, 102"})
    public void laterReevaluationUsesTheTimeReadsSample(boolean fenceI, int compare) {
        ScriptedSource time = new ScriptedSource(100);
        try (Machine m = new Machine(
                time,
                csrr(5, 0xC01), // samples 101: due, but MIE is clear
                fenceI ? FENCE_I : NOP,
                sw(4, 3, 0), // the compare becomes x3
                csr(6, 0, 8, 0x300), // csrsi mstatus, MIE: reevaluates from the latest sample
                NOP)) {
            m.state.setTimerMatch(101);
            m.state.mie = MTIP;
            m.state.regs[3] = compare;
            m.state.regs[4] = DEVICE;

            m.step(5);

            assertEquals(2, time.reads, "the FENCE.I resume does not sample");
            assertEquals(101, m.state.getTimer());
            if (compare == 101) {
                assertEquals(MCAUSE_TIMER, m.state.mcause);
                assertEquals(RAM_OFFSET + 16, m.state.mepc);
            } else {
                assertEquals(RAM_OFFSET + 20, m.state.pc);
                assertEquals(0, m.state.mip & MTIP);
            }
        }
    }
}
