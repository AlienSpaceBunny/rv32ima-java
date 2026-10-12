package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Directed cases for {@link RV32IMACore#stepAttempts} beyond V-32's ported oracle ({@link
 * AttemptBoundaryTest}): callback timing in both phases, exit reasons, rejected budgets, fetch
 * faults, a controller stop on {@code FENCE.I}, the call's time sample, and exceptions. Cores come
 * from {@link TestCores}, so the build runs these uncached and cached.
 */
public class StepAttemptsTest {
    private static final int RAM_OFFSET = 0x80000000;
    private static final int RAM_SIZE = 0x1000;
    private static final int HANDLER = RAM_OFFSET + 0x100;
    private static final int NOP = 0x00000013;
    private static final int FENCE_I = 0x0000100f;
    private static final int WFI = 0x10500073;
    private static final int MTIP = 1 << 7;
    private static final int MSIP = 1 << 3;

    private static int addi(int rd, int rs1, int imm) {
        return ((imm & 0xfff) << 20) | (rs1 << 15) | (rd << 7) | 0x13;
    }

    private static int csrr(int rd, int csr) {
        return (csr << 20) | (2 << 12) | (rd << 7) | 0x73; // csrrs rd, csr, x0
    }

    /** A source that advances by one on every read, starting at 1, and counts its reads. */
    private static final class AdvancingSource implements MachineTimeSource {
        long next = 1;
        int reads;

        @Override
        public long nowMicros() {
            reads++;
            return next++;
        }
    }

    /** A controller from two lambdas; {@code after} may be {@code null} to never stop. */
    private static AttemptController controller(
            Consumer<RV32IMAState> before, BiPredicate<RV32IMAState, Integer> after) {
        return new AttemptController() {
            @Override
            public void beforeAttempt(RV32IMAState state) {
                before.accept(state);
            }

            @Override
            public boolean afterAttempt(RV32IMAState state, int coreResult) {
                return after != null && after.test(state, coreResult);
            }
        };
    }

    /** RAM holding {@code code} at {@code RAM_OFFSET} and NOPs everywhere else. */
    private static FFMMemoryBus ram(int... code) {
        FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET);
        for (int address = 0; address < RAM_SIZE; address += 4) {
            ram.writeInt(RAM_OFFSET + address, address < 4 * code.length ? code[address / 4] : NOP);
        }
        return ram;
    }

    private static RV32IMAState machineState() {
        RV32IMAState state = new RV32IMAState();
        state.pc = RAM_OFFSET;
        state.extraflags = 3;
        state.mtvec = HANDLER;
        return state;
    }

    @Test
    public void beforeAttemptEditsApplyToTheSameAttempt() {
        try (FFMMemoryBus ram = ram(addi(5, 6, 1), addi(5, 6, 1))) {
            RV32IMAState state = machineState();
            RV32IMACore core = TestCores.create();
            List<Integer> seen = new ArrayList<>();
            core.stepAttempts(state, ram, RAM_OFFSET, RAM_SIZE, 2, null, controller(s -> s.regs[6] += 10, (s, r) -> {
                seen.add(s.regs[5]);
                return false;
            }));
            assertEquals(List.of(11, 21), seen);
        }
    }

    @Test
    public void afterAttemptEditsApplyToTheNextAttempt() {
        try (FFMMemoryBus ram = ram(addi(5, 6, 1), addi(5, 6, 1), addi(5, 6, 1))) {
            RV32IMAState state = machineState();
            RV32IMACore core = TestCores.create();
            List<Integer> seen = new ArrayList<>();
            int[] after = {0};
            core.stepAttempts(
                    state, ram, RAM_OFFSET, RAM_SIZE, 3, null, controller(s -> seen.add(s.regs[5]), (s, r) -> {
                        after[0]++;
                        if (after[0] == 1) {
                            s.regs[6] = 40; // a register
                            s.pc = RAM_OFFSET + 8; // pc, skipping the second instruction
                        } else if (after[0] == 2) {
                            s.extraflags &= ~3; // privilege: drop to user mode
                            s.mtvec = HANDLER + 0x40; // a CSR
                        }
                        return false;
                    }));
            assertEquals(List.of(0, 1, 41), seen);
            assertEquals(RAM_OFFSET + 16, state.pc); // the third attempt ran the NOP at +12
            assertEquals(0, state.extraflags & 3);
            assertEquals(3, state.getCycle());
        }
    }

    @Test
    public void callbacksSeePublishedWorkCountsAndKeepCounterWrites() {
        try (FFMMemoryBus ram = ram(NOP, NOP, csrr(5, 0xB02), NOP)) {
            RV32IMAState state = machineState();
            RV32IMACore core = TestCores.create();
            List<Long> before = new ArrayList<>();
            List<Long> after = new ArrayList<>();
            AttemptResult result = core.stepAttempts(
                    state, ram, RAM_OFFSET, RAM_SIZE, 4, null, controller(s -> before.add(s.getInstret()), (s, r) -> {
                        after.add(s.getCycle());
                        if (after.size() == 1) {
                            s.setMinstret(1000); // read back by the third instruction
                        }
                        return false;
                    }));
            assertEquals(List.of(0L, 1L, 2L, 3L), before);
            assertEquals(List.of(1L, 2L, 3L, 4L), after);
            assertEquals(1001, state.regs[5]); // 1000 after the first, plus the second
            assertEquals(4, result.attemptsConsumed());
            assertEquals(AttemptResult.ExitReason.BUDGET, result.exitReason());
        }
    }

    @Test
    public void exitReasonPrefersControllerStopThenCoreResult() {
        assertEquals(AttemptResult.ExitReason.CONTROLLER_STOP, new AttemptResult(1, 3, true).exitReason());
        assertEquals(AttemptResult.ExitReason.CONTROLLER_STOP, new AttemptResult(0, 3, true).exitReason());
        assertEquals(AttemptResult.ExitReason.CORE_RESULT, new AttemptResult(1, 3, false).exitReason());
        assertEquals(AttemptResult.ExitReason.BUDGET, new AttemptResult(0, 3, false).exitReason());
    }

    @Test
    public void stalledWfiEndsTheCallWithCoreResult() {
        try (FFMMemoryBus ram = ram(NOP, WFI, NOP)) {
            RV32IMAState state = machineState();
            AttemptResult result = TestCores.create().stepAttempts(state, ram, RAM_OFFSET, RAM_SIZE, 6, null, null);
            assertEquals(new AttemptResult(1, 2, false), result);
            assertEquals(AttemptResult.ExitReason.CORE_RESULT, result.exitReason());
            assertEquals(RAM_OFFSET + 8, state.pc);
            assertEquals(2, state.getCycle());
            assertEquals(2, state.getInstret());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    public void nonpositiveBudgetIsRejectedBeforeAnyCallbackOrSample(int budget) {
        try (FFMMemoryBus ram = ram(NOP)) {
            RV32IMAState state = machineState();
            state.mie = MSIP;
            state.mip = MSIP;
            state.mstatus = 8; // a deliverable interrupt, so a step would change state
            AdvancingSource time = new AdvancingSource();
            RV32IMACore core = TestCores.create(IsaConfig.RV32IMA_ZICSR, time);
            int[] callbacks = {0};
            assertThrows(
                    IllegalArgumentException.class,
                    () -> core.stepAttempts(
                            state, ram, RAM_OFFSET, RAM_SIZE, budget, null, controller(s -> callbacks[0]++, (s, r) -> {
                                callbacks[0]++;
                                return false;
                            })));
            assertEquals(0, callbacks[0]);
            assertEquals(0, time.reads);
            assertEquals(RAM_OFFSET, state.pc);
            assertEquals(8, state.mstatus);
            assertEquals(0, state.getTimer());
        }
    }

    @Test
    public void fetchFaultConsumesAnAttemptAndContinuesIntoTheHandler() {
        try (FFMMemoryBus ram = ram()) {
            RV32IMAState state = machineState();
            state.pc = RAM_OFFSET + RAM_SIZE; // outside the window
            List<Integer> pcs = new ArrayList<>();
            AttemptResult result = TestCores.create()
                    .stepAttempts(state, ram, RAM_OFFSET, RAM_SIZE, 2, null, controller(s -> {}, (s, r) -> {
                        pcs.add(s.pc);
                        return false;
                    }));
            assertEquals(new AttemptResult(0, 2, false), result);
            assertEquals(List.of(HANDLER, HANDLER + 4), pcs);
            assertEquals(1, state.mcause); // instruction access fault
            assertEquals(2, state.getCycle()); // the fault counts as attempted
            assertEquals(1, state.getInstret()); // but did not retire
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void controllerStopOnFenceIFindsTheFlushDone(boolean checkMode) {
        try (FFMMemoryBus ram = ram(NOP, FENCE_I, NOP)) {
            RV32IMAState state = machineState();
            RV32IMACore core = new RV32IMACore(
                    IsaConfig.RV32IMA_ZICSR, new DecodedCacheConfig(64, checkMode), new ManualTimeSource());
            core.addDecodedCacheRange(RAM_OFFSET, RAM_SIZE);
            List<Long> flushes = new ArrayList<>();
            AttemptResult result =
                    core.stepAttempts(state, ram, RAM_OFFSET, RAM_SIZE, 6, null, controller(s -> {}, (s, r) -> {
                        flushes.add(core.getDecodedCacheStats().flushes());
                        return s.pc == RAM_OFFSET + 8; // stop right after the FENCE.I
                    }));
            assertEquals(new AttemptResult(0, 2, true), result);
            // The FENCE.I's flush is done when its afterAttempt runs.
            assertEquals(1, flushes.get(1) - flushes.get(0));
        }
    }

    /**
     * A {@code time} read crosses a deadline, and its sample is the one every later attempt
     * keeps, across a {@code FENCE.I} too: compare edits between attempts move {@code MTIP}
     * exactly as that sample says, which neither an extra sample nor a restored older one would.
     */
    @Test
    public void timeReadSampleIsKeptByLaterAttempts() {
        try (FFMMemoryBus ram = ram(csrr(5, 0xC01))) {
            ram.writeInt(HANDLER, FENCE_I);
            RV32IMAState state = machineState();
            state.mstatus = 8;
            state.mie = MTIP;
            state.setTimerMatch(2);
            AdvancingSource time = new AdvancingSource();
            RV32IMACore core = TestCores.create(IsaConfig.RV32IMA_ZICSR, time);
            List<String> seen = new ArrayList<>();
            int[] after = {0};
            AttemptResult result =
                    core.stepAttempts(state, ram, RAM_OFFSET, RAM_SIZE, 4, null, controller(s -> {}, (s, r) -> {
                        seen.add(s.getTimer() + ":" + ((s.mip & MTIP) != 0));
                        after[0]++;
                        // Sample 2: not due at 3, then due at 2 again.
                        s.setTimerMatch(after[0] == 1 ? 3 : 2);
                        return false;
                    }));
            assertEquals(4, result.attemptsConsumed());
            assertEquals(2, state.regs[5]); // the read's sample
            assertEquals(0x80000007, state.mcause); // the read's deadline, taken in its attempt
            assertEquals(RAM_OFFSET + 4, state.mepc);
            assertEquals(List.of("2:true", "2:false", "2:true", "2:true"), seen);
            assertEquals(2, time.reads); // the call's sample and the read's
        }
    }

    @Test
    public void callSamplesOnceAfterTheFirstBeforeAttempt() {
        try (FFMMemoryBus ram = ram()) {
            RV32IMAState state = machineState();
            AdvancingSource time = new AdvancingSource();
            RV32IMACore core = TestCores.create(IsaConfig.RV32IMA_ZICSR, time);
            List<Integer> readsBefore = new ArrayList<>();
            core.stepAttempts(
                    state, ram, RAM_OFFSET, RAM_SIZE, 5, null, controller(s -> readsBefore.add(time.reads), null));
            assertEquals(List.of(0, 1, 1, 1, 1), readsBefore);
            assertEquals(1, time.reads);
            core.stepAttempts(state, ram, RAM_OFFSET, RAM_SIZE, 5, null, null);
            assertEquals(2, time.reads);
            assertEquals(2, state.getTimer());
        }
    }

    @Test
    public void callbackExceptionPropagatesAndLeavesStepSamplingNormally() {
        try (FFMMemoryBus ram = ram()) {
            RV32IMAState state = machineState();
            AdvancingSource time = new AdvancingSource();
            RV32IMACore core = TestCores.create(IsaConfig.RV32IMA_ZICSR, time);
            IllegalStateException thrown = new IllegalStateException("callback");
            IllegalStateException caught = assertThrows(
                    IllegalStateException.class,
                    () -> core.stepAttempts(state, ram, RAM_OFFSET, RAM_SIZE, 5, null, controller(s -> {}, (s, r) -> {
                        throw thrown;
                    })));
            assertSame(thrown, caught);
            assertEquals(RAM_OFFSET + 4, state.pc); // no rollback
            core.step(state, ram, RAM_OFFSET, RAM_SIZE, 1, null, null);
            assertEquals(2, time.reads);
        }
    }

    @Test
    public void busExceptionPropagatesAndLeavesStepSamplingNormally() {
        try (FFMMemoryBus ram = ram(NOP, 0x0002a283)) { // lw x5, 0(x5)
            MemoryBus failing = new MemoryBus() {
                @Override
                public byte readByte(int address) {
                    return ram.readByte(address);
                }

                @Override
                public short readShort(int address) {
                    return ram.readShort(address);
                }

                @Override
                public int readInt(int address, AccessContext ctx) {
                    if (ctx.kind() == AccessKind.LOAD) {
                        throw new IllegalStateException("device failure");
                    }
                    return ram.readInt(address);
                }

                @Override
                public int readInt(int address) {
                    return ram.readInt(address);
                }

                @Override
                public void writeByte(int address, byte value) {
                    ram.writeByte(address, value);
                }

                @Override
                public void writeShort(int address, short value) {
                    ram.writeShort(address, value);
                }

                @Override
                public void writeInt(int address, int value) {
                    ram.writeInt(address, value);
                }
            };
            RV32IMAState state = machineState();
            state.regs[5] = RAM_OFFSET;
            AdvancingSource time = new AdvancingSource();
            RV32IMACore core = TestCores.create(IsaConfig.RV32IMA_ZICSR, time);
            assertThrows(
                    IllegalStateException.class,
                    () -> core.stepAttempts(state, failing, RAM_OFFSET, RAM_SIZE, 5, null, null));
            assertEquals(1, time.reads);
            core.step(state, ram, RAM_OFFSET, RAM_SIZE, 1, null, null);
            assertEquals(2, time.reads); // a public step samples again
            assertEquals(2, state.getTimer());
        }
    }
}
