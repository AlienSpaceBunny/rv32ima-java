package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The shared time source, the standard compare, and standard WFI with the implemented interrupt
 * bits (docs/TIMER_COUNTER_DESIGN.md, "Time sampling", "Compare semantics" and "WFI"). Cores come from {@link TestCores}, so the build runs these uncached
 * and with the decoded cache at two sizes.
 */
public class TimerTest {
    private static final int RAM_OFFSET = 0x80000000;
    private static final int RAM_SIZE = 1024;
    private static final int DEVICE = 0x10000000;
    private static final int MTIP = 1 << 7;
    private static final int MCAUSE_TIMER = 0x80000007;
    private static final int IMPLEMENTED = (1 << 3) | MTIP | (1 << 11);
    private static final long NEVER = RV32IMAState.TIMER_COMPARE_NEVER;

    private static final int NOP = 0x00000013;
    private static final int MRET = 0x30200073;
    private static final int WFI = 0x10500073;
    private static final int FENCE_I = 0x0000100f;

    private static int addi(int rd, int rs1, int imm) {
        return ((imm & 0xfff) << 20) | (rs1 << 15) | (rd << 7) | 0x13;
    }

    private static int lui(int rd, int value) {
        return (value & 0xfffff000) | (rd << 7) | 0x37;
    }

    private static int sw(int rs1, int rs2, int imm) {
        return (((imm >> 5) & 0x7f) << 25) | (rs2 << 20) | (rs1 << 15) | (2 << 12) | ((imm & 0x1f) << 7) | 0x23;
    }

    private static int csr(int funct3, int rd, int rs1, int csrNo) {
        return (csrNo << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x73;
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

    /**
     * RAM at {@link #RAM_OFFSET} holding {@code program}, and a device at {@link #DEVICE} whose
     * every write sets the given hart's compare to {@link #NEVER}, as a CLINT store would.
     */
    private static MMIOBus machine(FFMMemoryBus ram, RV32IMAState state, int... program) {
        for (int i = 0; i < program.length; i++) {
            ram.writeInt(RAM_OFFSET + 4 * i, program[i]);
        }
        MMIOBus bus = new MMIOBus(ram);
        bus.registerHook(DEVICE, 0x100, new HardwareHook() {
            @Override
            public void handleWrite(int address, int value, int width) {
                state.setTimerMatch(NEVER);
            }

            @Override
            public int handleRead(int address, int width) {
                return 0;
            }
        });
        return bus;
    }

    private static RV32IMAState machineState() {
        RV32IMAState state = new RV32IMAState();
        state.pc = RAM_OFFSET;
        state.extraflags = 3;
        return state;
    }

    // ---- Sampling ----

    @Test
    public void newStateHasNoTimerPending() {
        RV32IMAState state = new RV32IMAState();
        assertEquals(NEVER, state.getTimerMatch());
        assertEquals(-1, state.timermatchl);
        assertEquals(-1, state.timermatchh);
    }

    @Test
    public void oneSamplePerCallAcrossDelegatedStepsAndFenceI() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            // The CSR accesses and FENCE.Is run through step()'s loop on the cached path, and each
            // FENCE.I makes the cached batch flush and resume.
            MMIOBus bus = machine(
                    ram,
                    state,
                    addi(1, 0, 1),
                    csr(1, 0, 1, 0x340), // csrw mscratch, x1
                    FENCE_I,
                    addi(1, 1, 1),
                    csr(2, 2, 0, 0x340), // csrr x2, mscratch
                    FENCE_I,
                    addi(1, 1, 1),
                    NOP);
            ScriptedSource time = new ScriptedSource(1000);
            RV32IMACore core = TestCores.create(IsaConfig.RV32IMA_ZICSR, time);

            assertEquals(0, core.step(state, bus, RAM_OFFSET, RAM_SIZE, 7, null, null));

            assertEquals(RAM_OFFSET + 28, state.pc);
            assertEquals(3, state.regs[1]);
            assertEquals(1, state.regs[2]);
            assertEquals(1, time.reads);
            assertEquals(1000, state.getTimer());
            if (core.hasDecodedCache()) {
                RV32IMACore.DecodedCacheStats stats = core.getDecodedCacheStats();
                assertTrue(stats.delegations() >= 4, stats.toString());
                assertTrue(stats.flushes() >= 2, stats.toString());
            }

            core.step(state, bus, RAM_OFFSET, RAM_SIZE, 1, null, null);
            assertEquals(2, time.reads);
            assertEquals(1001, state.getTimer());
        }
    }

    @Test
    public void wfiStallAndInterruptEntryEachSampleOnce() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            MMIOBus bus = machine(ram, state, WFI);
            ScriptedSource time = new ScriptedSource(5);
            RV32IMACore core = TestCores.create(IsaConfig.RV32IMA_ZICSR, time);

            assertEquals(1, core.step(state, bus, RAM_OFFSET, RAM_SIZE, 4, null, null)); // executes WFI
            assertEquals(1, time.reads);
            assertEquals(1, core.step(state, bus, RAM_OFFSET, RAM_SIZE, 4, null, null)); // still stalled
            assertEquals(2, time.reads);
            assertEquals(6, state.getTimer()); // time advances while stalled
            assertEquals(1, state.getCycle());

            state.mie = 1 << 3;
            state.mstatus = 0x08; // MIE, so the wakeup is delivered
            state.mtvec = RAM_OFFSET + 0x80;
            RV32IMACore.injectInterrupt(state, 3);
            assertEquals(0, core.step(state, bus, RAM_OFFSET, RAM_SIZE, 4, null, null)); // entry only
            assertEquals(0x80000003, state.mcause);
            assertEquals(RAM_OFFSET + 0x80, state.pc);
            assertEquals(3, time.reads);
            assertEquals(7, state.getTimer());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    public void nonpositiveCountIsRejectedWithoutSampleOrStateChange(int count) {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            MMIOBus bus = machine(ram, state, NOP);
            // A due compare and a deliverable interrupt: a sample or an entry would change state.
            state.setTimerMatch(0);
            state.mstatus = 0x08;
            state.mie = MTIP | (1 << 3);
            state.mip = 1 << 3;
            state.extraflags |= 4; // WFI
            int[] before = snapshot(state);
            ScriptedSource time = new ScriptedSource(100);
            RV32IMACore core = TestCores.create(IsaConfig.RV32IMA_ZICSR, time);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> core.step(state, bus, RAM_OFFSET, RAM_SIZE, count, null, null));

            assertEquals(0, time.reads);
            assertArrayEquals(before, snapshot(state));
        }
    }

    private static int[] snapshot(RV32IMAState s) {
        return new int[] {
            s.pc,
            s.mstatus,
            s.mie,
            s.mip,
            s.mepc,
            s.mcause,
            s.mtval,
            s.extraflags,
            s.cyclel,
            s.cycleh,
            s.timerl,
            s.timerh,
            s.timermatchl,
            s.timermatchh,
            s.regs[1]
        };
    }

    @Test
    public void twoHartsShareOneSource() {
        try (FFMMemoryBus ramA = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET);
                FFMMemoryBus ramB = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            ManualTimeSource time = new ManualTimeSource(100);
            RV32IMAState a = machineState();
            RV32IMAState b = machineState();
            MMIOBus busA = machine(ramA, a, NOP, NOP);
            MMIOBus busB = machine(ramB, b, NOP, NOP);
            RV32IMACore coreA = TestCores.create(IsaConfig.RV32IMA_ZICSR, time);
            RV32IMACore coreB = TestCores.create(IsaConfig.RV32IMA_ZICSR, time);
            a.setTimerMatch(50);
            b.setTimerMatch(150);

            coreA.step(a, busA, RAM_OFFSET, RAM_SIZE, 1, null, null);
            coreB.step(b, busB, RAM_OFFSET, RAM_SIZE, 1, null, null);
            assertEquals(100, a.getTimer());
            assertEquals(100, b.getTimer());
            assertEquals(MTIP, a.mip & MTIP);
            assertEquals(0, b.mip & MTIP);

            time.advance(100);
            coreB.step(b, busB, RAM_OFFSET, RAM_SIZE, 1, null, null);
            assertEquals(200, b.getTimer());
            assertEquals(MTIP, b.mip & MTIP);
            assertEquals(100, a.getTimer()); // a sample, not a live clock
        }
    }

    // ---- Compare ----

    /** Runs one NOP at {@code time} against {@code compare} and returns the hart's {@code mip}. */
    private static int mipAfterOneCall(long time, long compare, int mipBefore) {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            MMIOBus bus = machine(ram, state, NOP);
            state.setTimerMatch(compare);
            state.mip = mipBefore;
            TestCores.create(IsaConfig.RV32IMA_ZICSR, new ManualTimeSource(time))
                    .step(state, bus, RAM_OFFSET, RAM_SIZE, 1, null, null);
            assertEquals(RAM_OFFSET + 4, state.pc); // MTIE is clear: no trap
            return state.mip;
        }
    }

    @Test
    public void compareIsUnsignedWithNoZeroSpecialCase() {
        assertEquals(MTIP, mipAfterOneCall(0, 0, 0)); // zero is due at time zero
        assertEquals(0, mipAfterOneCall(0, NEVER, 0));
        assertEquals(0, mipAfterOneCall(Long.MAX_VALUE, NEVER, 0));
        assertEquals(MTIP, mipAfterOneCall(-1L, NEVER, 0)); // equality at all ones is still due
        assertEquals(MTIP, mipAfterOneCall(Long.MIN_VALUE, Long.MAX_VALUE, 0)); // unsigned
        assertEquals(0, mipAfterOneCall(Long.MAX_VALUE, Long.MIN_VALUE, MTIP)); // cleared, unsigned
        assertEquals(MTIP, mipAfterOneCall(10, 10, 0));
        assertEquals(0, mipAfterOneCall(9, 10, MTIP));
    }

    @Test
    public void compareChangesOnlyMtip() {
        int others = (1 << 3) | (1 << 11);
        assertEquals(others | MTIP, mipAfterOneCall(5, 5, others));
        assertEquals(others, mipAfterOneCall(4, 5, others | MTIP));
    }

    @Test
    public void rearmingClearsMtipUntilTheNewDeadline() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            MMIOBus bus = machine(ram, state, NOP, NOP, NOP);
            ManualTimeSource time = new ManualTimeSource(100);
            RV32IMACore core = TestCores.create(IsaConfig.RV32IMA_ZICSR, time);
            state.setTimerMatch(50);
            core.step(state, bus, RAM_OFFSET, RAM_SIZE, 1, null, null);
            assertEquals(MTIP, state.mip & MTIP);

            state.setTimerMatch(200); // a callback or device re-arms
            core.step(state, bus, RAM_OFFSET, RAM_SIZE, 1, null, null);
            assertEquals(0, state.mip & MTIP);

            time.set(200);
            core.step(state, bus, RAM_OFFSET, RAM_SIZE, 1, null, null);
            assertEquals(MTIP, state.mip & MTIP);
        }
    }

    // ---- Reevaluation recomputes MTIP ----

    /**
     * A handler running with MTIP pending and not deliverable moves the compare through MMIO
     * (or not), then executes {@code MRET}, which enables {@code mstatus.MIE}.
     */
    private static RV32IMAState runHandlerEndingInMret(boolean moveCompare) {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            MMIOBus bus = machine(ram, state, lui(5, DEVICE), moveCompare ? sw(5, 0, 0) : NOP, MRET);
            ram.writeInt(RAM_OFFSET + 0x40, NOP);
            state.mepc = RAM_OFFSET + 0x40;
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = 0x80 | 0x1800; // MPIE set, MPP = M, MIE clear
            state.mie = MTIP;
            state.setTimerMatch(50);
            TestCores.create(IsaConfig.RV32IMA_ZICSR, new ManualTimeSource(100))
                    .step(state, bus, RAM_OFFSET, RAM_SIZE, 4, null, null);
            return state;
        }
    }

    @Test
    public void compareMovedThroughMmioBeforeMretDoesNotReenter() {
        RV32IMAState moved = runHandlerEndingInMret(true);
        assertEquals(0, moved.mcause);
        assertEquals(0, moved.mip & MTIP);
        assertEquals(RAM_OFFSET + 0x44, moved.pc); // the NOP after MRET ran
        assertEquals(NEVER, moved.getTimerMatch());

        RV32IMAState kept = runHandlerEndingInMret(false); // control: still due, so MRET re-enters
        assertEquals(MCAUSE_TIMER, kept.mcause);
        assertEquals(RAM_OFFSET + 0x40, kept.mepc);
        assertEquals(RAM_OFFSET + 0x80, kept.pc);
    }

    /** As {@link #runHandlerEndingInMret}, but the enabling instruction is a {@code mie} write. */
    private static RV32IMAState runMieWriteAfterStore(boolean moveCompare) {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            MMIOBus bus = machine(
                    ram,
                    state,
                    lui(5, DEVICE),
                    moveCompare ? sw(5, 0, 0) : NOP,
                    addi(6, 0, MTIP),
                    csr(2, 0, 6, 0x304), // csrs mie, x6
                    NOP);
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = 0x08; // MIE set
            state.setTimerMatch(50);
            TestCores.create(IsaConfig.RV32IMA_ZICSR, new ManualTimeSource(100))
                    .step(state, bus, RAM_OFFSET, RAM_SIZE, 5, null, null);
            return state;
        }
    }

    @Test
    public void compareMovedThroughMmioBeforeMieWriteIsNotDelivered() {
        RV32IMAState moved = runMieWriteAfterStore(true);
        assertEquals(0, moved.mcause);
        assertEquals(0, moved.mip & MTIP);
        assertEquals(RAM_OFFSET + 20, moved.pc);

        RV32IMAState kept = runMieWriteAfterStore(false);
        assertEquals(MCAUSE_TIMER, kept.mcause);
        assertEquals(RAM_OFFSET + 16, kept.mepc);
    }

    // ---- WFI and the implemented interrupt bits ----

    private static final int HANDLER = RAM_OFFSET + 0x80;

    /** Makes interrupt {@code bit} pending: MSIP and MEIP as a device would, MTIP by a due compare. */
    private static void raise(RV32IMAState state, ManualTimeSource time, int bit) {
        if (bit == 7) {
            state.setTimerMatch(time.nowMicros());
        } else {
            RV32IMACore.injectInterrupt(state, bit);
        }
    }

    /** A hart in the given mode with only interrupt {@code bit} enabled in {@code mie}. */
    private static RV32IMAState wfiState(int bit, boolean user, boolean globalEnable) {
        RV32IMAState state = machineState();
        state.extraflags = user ? 0 : 3;
        state.mstatus = globalEnable ? 0x08 : 0;
        state.mie = 1 << bit;
        state.mtvec = HANDLER;
        return state;
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 7, 11})
    public void wfiWithLocallyEnabledSourceRetiresWithoutSleepingWhenNotDeliverable(int bit) {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = wfiState(bit, false, false);
            MMIOBus bus = machine(ram, state, WFI, addi(4, 0, 1), NOP);
            ManualTimeSource time = new ManualTimeSource(100);
            raise(state, time, bit);

            assertEquals(
                    0,
                    TestCores.create(IsaConfig.RV32IMA_ZICSR, time)
                            .step(state, bus, RAM_OFFSET, RAM_SIZE, 2, null, null));

            assertEquals(RAM_OFFSET + 8, state.pc); // the call continued past WFI
            assertEquals(1, state.regs[4]);
            assertEquals(2, state.getCycle());
            assertEquals(0, state.extraflags & 4);
            assertEquals(0, state.mstatus); // WFI does not set MIE
            assertEquals(0, state.mcause);
        }
    }

    @ParameterizedTest
    @CsvSource({"3, true", "7, true", "11, true", "3, false", "7, false", "11, false"})
    public void deliverableSourceIsTakenAtEntryBeforeWfiExecutes(int bit, boolean user) {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = wfiState(bit, user, !user);
            MMIOBus bus = machine(ram, state, WFI);
            ManualTimeSource time = new ManualTimeSource(100);
            raise(state, time, bit);

            assertEquals(
                    0,
                    TestCores.create(IsaConfig.RV32IMA_ZICSR, time)
                            .step(state, bus, RAM_OFFSET, RAM_SIZE, 4, null, null));

            assertEquals(0x80000000 | bit, state.mcause);
            assertEquals(RAM_OFFSET, state.mepc); // WFI did not execute
            assertEquals(HANDLER, state.pc);
            assertEquals(0, state.getCycle());
            assertEquals(3, state.extraflags); // machine mode, not stalled
        }
    }

    @ParameterizedTest
    @CsvSource({
        "3, false, true", "7, false, true", "11, false, true",
        "3, false, false", "7, false, false", "11, false, false",
        "3, true, false", "7, true, false", "11, true, false"
    })
    public void stallEndsWhenAnEnabledSourceArrives(int bit, boolean user, boolean globalEnable) {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = wfiState(bit, user, globalEnable);
            MMIOBus bus = machine(ram, state, WFI, addi(4, 0, 1), NOP);
            ManualTimeSource time = new ManualTimeSource(100);
            RV32IMACore core = TestCores.create(IsaConfig.RV32IMA_ZICSR, time);

            assertEquals(1, core.step(state, bus, RAM_OFFSET, RAM_SIZE, 4, null, null)); // executes WFI
            assertEquals(1, core.step(state, bus, RAM_OFFSET, RAM_SIZE, 4, null, null)); // stalled
            assertEquals(RAM_OFFSET + 4, state.pc);
            assertEquals(1, state.getCycle());
            assertEquals(4, state.extraflags & 4);

            raise(state, time, bit);
            assertEquals(0, core.step(state, bus, RAM_OFFSET, RAM_SIZE, 1, null, null));

            assertEquals(0, state.extraflags & 4);
            if (user || globalEnable) { // delivered at entry
                assertEquals(0x80000000 | bit, state.mcause);
                assertEquals(RAM_OFFSET + 4, state.mepc);
                assertEquals(HANDLER, state.pc);
                assertEquals(0, state.regs[4]);
                assertEquals(1, state.getCycle());
            } else { // woken but not delivered: continues after WFI
                assertEquals(0, state.mcause);
                assertEquals(1, state.regs[4]);
                assertEquals(RAM_OFFSET + 8, state.pc);
                assertEquals(2, state.getCycle());
            }
        }
    }

    @Test
    public void maskedDueCompareDoesNotWake() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = wfiState(3, false, true);
            MMIOBus bus = machine(ram, state, WFI, NOP);
            state.setTimerMatch(0); // due, but MTIE is clear
            RV32IMACore core = TestCores.create(IsaConfig.RV32IMA_ZICSR, new ManualTimeSource(100));

            assertEquals(1, core.step(state, bus, RAM_OFFSET, RAM_SIZE, 4, null, null)); // executing WFI stalls
            for (int i = 0; i < 3; i++) {
                assertEquals(1, core.step(state, bus, RAM_OFFSET, RAM_SIZE, 4, null, null));
            }
            assertEquals(MTIP, state.mip & MTIP);
            assertEquals(4, state.extraflags & 4);
            assertEquals(RAM_OFFSET + 4, state.pc);
            assertEquals(1, state.getCycle());

            state.mie |= MTIP; // the guest's mask lifted, as by a host-side edit
            assertEquals(0, core.step(state, bus, RAM_OFFSET, RAM_SIZE, 4, null, null));
            assertEquals(MCAUSE_TIMER, state.mcause);
            assertEquals(RAM_OFFSET + 4, state.mepc);
        }
    }

    @Test
    public void reservedBitsSetByTheHostReadAsZeroAndNeitherWakeNorDeliver() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = wfiState(3, false, true);
            MMIOBus bus = machine(
                    ram,
                    state,
                    csr(2, 5, 0, 0x304), // csrr x5, mie
                    csr(2, 6, 0, 0x344), // csrr x6, mip
                    WFI,
                    addi(4, 0, 1));
            state.mie = ~IMPLEMENTED;
            state.mip = ~IMPLEMENTED;
            state.regs[5] = -1;
            state.regs[6] = -1;
            RV32IMACore core = TestCores.create(IsaConfig.RV32IMA_ZICSR, new ManualTimeSource(100));

            assertEquals(1, core.step(state, bus, RAM_OFFSET, RAM_SIZE, 4, null, null));
            assertEquals(0, state.regs[5]);
            assertEquals(0, state.regs[6]);
            assertEquals(RAM_OFFSET + 12, state.pc); // WFI stalled
            assertEquals(1, core.step(state, bus, RAM_OFFSET, RAM_SIZE, 4, null, null));
            assertEquals(4, state.extraflags & 4);
            assertEquals(0, state.mcause);
            assertEquals(0, state.regs[4]);
        }
    }

    @Test
    public void guestWritesKeepOnlyImplementedEnablesAndLeaveMipUnchanged() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            MMIOBus bus = machine(
                    ram,
                    state,
                    addi(5, 0, -1),
                    csr(1, 0, 5, 0x304), // csrw mie, x5
                    csr(2, 7, 0, 0x304), // csrr x7, mie
                    csr(1, 0, 5, 0x344), // csrw mip, x5
                    csr(3, 0, 5, 0x344), // csrc mip, x5
                    csr(7, 0, 8, 0x344), // csrci mip, MSIP
                    csr(2, 8, 0, 0x344)); // csrr x8, mip
            state.mip = (1 << 3) | (1 << 11); // MSIP and MEIP, raised by devices
            RV32IMACore core = TestCores.create(IsaConfig.RV32IMA_ZICSR, new ManualTimeSource(100));

            assertEquals(0, core.step(state, bus, RAM_OFFSET, RAM_SIZE, 7, null, null));

            assertEquals(IMPLEMENTED, state.mie);
            assertEquals(IMPLEMENTED, state.regs[7]);
            assertEquals((1 << 3) | (1 << 11), state.mip);
            assertEquals((1 << 3) | (1 << 11), state.regs[8]);
            assertEquals(RAM_OFFSET + 28, state.pc); // mstatus.MIE is clear: nothing delivered
        }
    }
}
