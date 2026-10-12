package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * V-32's committed-attempt oracle (V-32 repository: {@code
 * docs/performance-evidence/2026-10-09/ap-boundary-api/candidate/AttemptBoundaryTest.java}),
 * ported to {@link RV32IMACore#stepAttempts}: its 33 scenarios, five budgets each with and
 * without a controller (330 comparisons), and its three directed tests. Each comparison runs the
 * core from {@link TestCores} (uncached, or cached at 4 and 4096 slots as the build reruns the
 * suite) and a check-mode cached core against the independent {@link ReferenceCore}'s attempt
 * driver, comparing results, every boundary's snapshot, memory, device effects and both
 * reservation layers.
 *
 * <p><b>Changed expectations</b>, each following an intentional change; the frozen evidence is
 * unchanged:
 *
 * <ul>
 *   <li><b>Nonpositive budgets</b> throw {@link IllegalArgumentException} before any callback,
 *       sample or state change. The oracle ran one {@code beforeAttempt} and a step with the
 *       budget.
 *   <li><b>Time</b> comes from a source that advances by one on every read, starting at 1, in
 *       place of {@code elapsedUs} 1 on each call's first attempt; the sample count is compared.
 *   <li><b>{@code TIMER_ZERO}</b> keeps the new default compare, {@link
 *       RV32IMAState#TIMER_COMPARE_NEVER}, which replaces zero as the never-due compare, so its
 *       direct {@code MTIP} edit is still overwritten. The new {@code TIMER_ZERO_DUE} sets zero,
 *       which is now due.
 *   <li><b>{@code WFI}</b> is standard: it no longer sets {@code mstatus.MIE}.
 *   <li><b>Counters:</b> snapshots include the retired-instruction count and the counter
 *       offsets.
 *   <li><b>Hook observations</b> move to {@link #hookObservationsUnderRepeatedStep}, since
 *       {@code stepAttempts} has no hook. There a taken jump reports its own PC, and an executed
 *       {@code WFI} that stalls calls the hook, as {@code step} now does.
 *   <li><b>Fetches</b> are not logged, since the decoded cache legitimately skips them.
 * </ul>
 */
class AttemptBoundaryTest {
    private static final int MEIP = 1 << 11;
    private static final int MTIP = 1 << 7;
    private static final int NOP = 0x13;
    private static final int STORE = 0x0020a023;
    private static final int LR = 0x1000a1af;
    private static final int SC = 0x1820a22f;
    private static final int WFI = 0x10500073;
    private static final IsaConfig ISA = new IsaConfig(true, true, true, true, true, false, true);
    private static final int[] CONTROLLED_BUDGETS = {-2, 0, 1, 2, 6};
    private static final int[] UNCONTROLLED_BUDGETS = {-1, 0, 1, 2, 6};

    enum Scenario {
        ORDINARY,
        COMPRESSED,
        TERMINAL,
        ARRIVAL_BEFORE,
        ARRIVAL_AFTER,
        ACK_BEFORE,
        ACK_AFTER,
        ACK_BETWEEN,
        ENTRY_IRQ,
        FETCH_WINDOW,
        FETCH_BUS,
        LOAD_FAULT,
        STORE_FAULT,
        ILLEGAL,
        ECALL,
        CSR_DISPATCH,
        MRET_DISPATCH,
        WFI_ENTRY,
        WFI_STALL,
        WFI_WAKE,
        WFI_POST_ARRIVAL,
        WFI_STALL_POST_ARRIVAL,
        TIMER_THRESHOLD,
        TIMER_CHANGED,
        TIMER_ZERO,
        MIP_WRITE,
        LR_SC,
        SC_BUS_INVALIDATED,
        SC_LOCAL_INVALIDATED,
        HALT_AFTER_LR,
        STOP_AFTER_LR,
        INITIAL_HALT,
        INITIAL_STOP,
        TIMER_ZERO_DUE // new: a compare of zero is due
    }

    /** Which engine a fixture runs. */
    enum Kind {
        REFERENCE,
        CORE, // from TestCores
        CHECKED // cached, in check mode
    }

    /** {@code rejected} marks a call that threw {@link IllegalArgumentException}. */
    private record Outcome(int result, int attempts, boolean stopped, boolean rejected) {}

    /** A source that advances by one on every read, starting at 1, and counts its reads. */
    private static final class AdvancingSource implements MachineTimeSource {
        long next = 1;
        long step = 1;
        int reads;

        @Override
        public long nowMicros() {
            reads++;
            long now = next;
            next += step;
            return now;
        }
    }

    private static final class Fixture implements AttemptController {
        final AdvancingSource time = new AdvancingSource();
        final RV32IMACore core;
        final ReferenceCore reference;
        final RV32IMAState state = new RV32IMAState();
        final Bus bus = new Bus();
        final List<String> events = new ArrayList<>();
        final Scenario scenario;
        int beforeCount;
        int afterCount;
        boolean running = true;

        Fixture(Scenario scenario, Kind kind) {
            this.scenario = scenario;
            switch (kind) {
                case REFERENCE -> {
                    core = null;
                    reference = new ReferenceCore(ISA, time);
                }
                case CORE -> {
                    core = TestCores.create(ISA, time);
                    reference = null;
                }
                default -> {
                    core = new RV32IMACore(ISA, new DecodedCacheConfig(64, true), time);
                    core.addDecodedCacheRange(0, 256);
                    reference = null;
                }
            }
            state.extraflags = 3;
            state.hartId = 1;
            state.mtvec = 64;
            state.regs[1] = 128;
            state.regs[2] = 19;
            for (int address = 0; address < 128; address += 4) bus.put(address, NOP);
            bus.put(4, 0x00128293); // addi x5,x5,1
            bus.put(64, 0x00130313); // addi x6,x6,1
            switch (scenario) {
                case COMPRESSED -> bus.put(0, 0x02850285); // two C.ADDI x5,1
                case TERMINAL -> {
                    bus.put(0, STORE);
                    bus.put(4, STORE);
                }
                case ENTRY_IRQ, ACK_BEFORE, ACK_AFTER -> {
                    enableIrq();
                    bus.pending = true;
                }
                case ARRIVAL_BEFORE, ARRIVAL_AFTER, ACK_BETWEEN -> enableIrq();
                case FETCH_WINDOW -> state.pc = 256;
                case FETCH_BUS -> bus.fetchDenied = true;
                case LOAD_FAULT -> {
                    bus.put(0, 0x0000a183);
                    state.regs[1] = 256;
                }
                case STORE_FAULT -> {
                    bus.put(0, STORE);
                    state.regs[1] = 256;
                }
                case ILLEGAL -> bus.put(0, -1);
                case ECALL -> bus.put(0, 0x73);
                case CSR_DISPATCH -> {
                    bus.pending = true;
                    state.mie = MEIP;
                    state.regs[2] = 8;
                    bus.put(0, 0x30011073); // csrrw x0,mstatus,x2
                }
                case MRET_DISPATCH -> {
                    bus.pending = true;
                    state.mie = MEIP;
                    state.mstatus = (3 << 11) | (1 << 7);
                    state.mepc = 4;
                    bus.put(0, 0x30200073);
                }
                case WFI_ENTRY -> bus.put(0, WFI);
                case WFI_POST_ARRIVAL -> {
                    bus.put(0, WFI);
                    enableIrq();
                }
                case WFI_STALL_POST_ARRIVAL -> {
                    state.extraflags |= 4;
                    enableIrq();
                }
                case WFI_STALL -> state.extraflags |= 4;
                case WFI_WAKE -> {
                    state.extraflags |= 4;
                    enableIrq();
                    bus.pending = true;
                }
                case TIMER_THRESHOLD -> {
                    state.mstatus = 8;
                    state.mie = MTIP;
                    state.setTimerMatch(1);
                }
                case TIMER_CHANGED -> {
                    state.mstatus = 8;
                    state.mie = MTIP;
                }
                case TIMER_ZERO -> {
                    state.mstatus = 8;
                    state.mie = MTIP;
                    state.mip = MTIP;
                }
                case TIMER_ZERO_DUE -> {
                    state.mstatus = 8;
                    state.mie = MTIP;
                    state.setTimerMatch(0);
                }
                case MIP_WRITE -> {
                    state.setTimerMatch(1);
                    bus.put(0, 0x34401073); // csrrw x0,mip,x0; MTIP regenerated next attempt
                }
                case LR_SC, SC_BUS_INVALIDATED, SC_LOCAL_INVALIDATED, HALT_AFTER_LR, STOP_AFTER_LR -> {
                    bus.put(0, LR);
                    bus.put(4, SC);
                }
                case INITIAL_HALT -> {
                    bus.halted = true;
                    seedReservation();
                }
                case INITIAL_STOP -> {
                    running = false;
                    seedReservation();
                }
                default -> {}
            }
        }

        void seedReservation() {
            state.reservationValid = true;
            state.reservationAddr = 128;
            bus.reservation = 128;
        }

        void enableIrq() {
            state.mstatus = 8;
            state.mie = MEIP;
        }

        void sample() {
            state.mip = (state.mip & ~MEIP) | (bus.pending ? MEIP : 0);
        }

        void cleanup() {
            state.reservationValid = false;
            bus.reservation = -1;
        }

        @Override
        public void beforeAttempt(RV32IMAState ignored) {
            beforeCount++;
            if (scenario == Scenario.ARRIVAL_BEFORE && beforeCount == 2) bus.pending = true;
            if (scenario == Scenario.ACK_BEFORE) bus.pending = false;
            if (scenario == Scenario.ACK_BETWEEN && beforeCount == 2) {
                assertEquals(MEIP, state.mip & MEIP);
                bus.pending = false;
            }
            sample();
            events.add("before:" + snapshot());
        }

        @Override
        public boolean afterAttempt(RV32IMAState ignored, int result) {
            afterCount++;
            if (afterCount == 1) {
                switch (scenario) {
                    case ARRIVAL_AFTER, ACK_BETWEEN, WFI_POST_ARRIVAL, WFI_STALL_POST_ARRIVAL -> bus.pending = true;
                    case ACK_AFTER -> bus.pending = false;
                    case TIMER_CHANGED -> state.setTimerMatch(1);
                    case SC_BUS_INVALIDATED -> {
                        bus.put(128, 23);
                        bus.reservation = -1;
                    }
                    case SC_LOCAL_INVALIDATED -> state.reservationValid = false;
                    case HALT_AFTER_LR -> bus.halted = true;
                    case STOP_AFTER_LR -> running = false;
                    default -> {}
                }
            }
            sample();
            boolean stop = bus.halted || !running;
            events.add("after:" + result + ":" + stop + ":" + snapshot());
            return stop;
        }

        RV32IMACore.PostExecHook hook() {
            return (pc, ir, trap) -> events.add("hook:" + pc + ":" + ir + ":" + trap + ":" + snapshot());
        }

        /** The embedder's wrapper around one {@code stepAttempts} call, as in the oracle. */
        Outcome execute(int budget, boolean controlled) {
            return execute(budget, controlled ? this : null);
        }

        Outcome execute(int budget, AttemptController controller) {
            if (!running || bus.halted) {
                cleanup();
                return new Outcome(1, 0, true, false);
            }
            AttemptResult result;
            try {
                result = core != null
                        ? core.stepAttempts(state, bus, 0, 256, budget, null, controller)
                        : reference.stepAttempts(state, bus, 0, 256, budget, null, controller);
            } catch (IllegalArgumentException e) {
                return new Outcome(0, 0, false, true);
            }
            Outcome outcome =
                    new Outcome(result.coreResult(), result.attemptsConsumed(), result.controllerStopped(), false);
            if (outcome.stopped()) cleanup();
            return outcome;
        }

        /** The oracle's reference loop: repeated public {@code step(count=1)} with the hook. */
        void executeRepeatedSteps(int budget) {
            int consumed = 0;
            while (consumed < budget) {
                beforeAttempt(state);
                int result = core != null
                        ? core.step(state, bus, 0, 256, 1, hook(), null)
                        : reference.step(state, bus, 0, 256, 1, hook(), null);
                consumed++;
                if (afterAttempt(state, result) || result != 0) {
                    break;
                }
            }
        }

        String snapshot() {
            return Arrays.toString(state.regs) + Arrays.toString(state.fregs)
                    + Arrays.toString(new long[] {
                        state.pc,
                        state.hartId,
                        state.mstatus,
                        state.cyclel,
                        state.cycleh,
                        state.getInstret(),
                        state.mcycleOffset,
                        state.minstretOffset,
                        state.mcounteren,
                        state.timerl,
                        state.timerh,
                        state.timermatchl,
                        state.timermatchh,
                        state.mscratch,
                        state.mtvec,
                        state.mie,
                        state.mip,
                        state.mepc,
                        state.mtval,
                        state.mcause,
                        state.extraflags,
                        state.reservationAddr,
                        state.fcsr
                    })
                    + ":" + state.reservationValid + ":" + Arrays.toString(bus.bytes) + ":" + bus.reservation
                    + ":" + bus.pending + ":" + bus.halted + ":" + running + ":" + bus.accesses;
        }

        private final class Bus implements MemoryBus {
            final byte[] bytes = new byte[256];
            final List<String> accesses = new ArrayList<>();
            boolean halted;
            boolean pending;
            boolean fetchDenied;
            int reservation = -1;

            void put(int address, int value) {
                for (int i = 0; i < 4; i++) bytes[address + i] = (byte) ((value >>> (i * 8)) & 255);
            }

            @Override
            public byte readByte(int address) {
                return bytes[address];
            }

            @Override
            public short readShort(int address) {
                return (short) ((bytes[address] & 255) | ((bytes[address + 1] & 255) << 8));
            }

            @Override
            public int readInt(int address) {
                return (readShort(address) & 65535) | ((readShort(address + 2) & 65535) << 16);
            }

            @Override
            public void writeByte(int address, byte value) {
                bytes[address] = value;
            }

            @Override
            public void writeShort(int address, short value) {
                bytes[address] = (byte) value;
                bytes[address + 1] = (byte) (value >>> 8);
            }

            @Override
            public void writeInt(int address, int value) {
                put(address, value);
                if (scenario == Scenario.TERMINAL) halted = true;
            }

            void access(int address, AccessContext ctx, String operation) {
                if (ctx.kind() != AccessKind.FETCH) {
                    accesses.add(operation + ":" + address + ":" + ctx);
                }
                if (fetchDenied && address == 0 && ctx.kind() == AccessKind.FETCH)
                    throw new IndexOutOfBoundsException("synthetic fetch rejection");
                if (address < 0 || address > bytes.length - ctx.width())
                    throw new IndexOutOfBoundsException("synthetic unmapped address");
            }

            @Override
            public short readShort(int address, AccessContext ctx) {
                access(address, ctx, "read");
                return readShort(address);
            }

            @Override
            public int readInt(int address, AccessContext ctx) {
                access(address, ctx, "read");
                if (ctx.kind() == AccessKind.AMO && ctx.atomicOp() == 2) reservation = address;
                return readInt(address);
            }

            @Override
            public void writeInt(int address, int value, AccessContext ctx) {
                access(address, ctx, "write:" + value);
                writeInt(address, value);
            }

            @Override
            public int tryScAndStore(int hartId, int address, int value, AccessContext ctx) {
                int held = reservation;
                reservation = -1;
                access(address, ctx, "sc:" + hartId + ":" + value);
                if (held != address) return 1;
                writeInt(address, value);
                return 0;
            }

            @Override
            public void checkAccess(int address, AccessContext ctx) {
                access(address, ctx, "check");
            }
        }
    }

    private record Run(Fixture candidate, Outcome outcome) {}

    /** Runs one budget on the reference and on {@code kind}, and compares everything. */
    private static Run compare(Scenario scenario, Kind kind, int budget, boolean controlled) {
        Fixture reference = new Fixture(scenario, Kind.REFERENCE);
        Fixture candidate = new Fixture(scenario, kind);
        String initial = candidate.snapshot();
        Outcome expected = reference.execute(budget, controlled);
        Outcome actual = candidate.execute(budget, controlled);
        String label = scenario + " " + kind + " budget=" + budget + " controlled=" + controlled;
        assertEquals(expected, actual, label);
        assertEquals(reference.snapshot(), candidate.snapshot(), label);
        assertEquals(reference.events, candidate.events, label);
        assertEquals(reference.beforeCount, candidate.beforeCount, label);
        assertEquals(reference.afterCount, candidate.afterCount, label);
        assertEquals(reference.time.reads, candidate.time.reads, label + ": samples");
        if (budget <= 0 && candidate.running && !candidate.bus.halted) {
            assertTrue(actual.rejected(), label);
            assertEquals(0, candidate.beforeCount, label);
            assertEquals(0, candidate.time.reads, label);
            assertEquals(initial, candidate.snapshot(), label);
        } else if (budget > 0 && actual.attempts() > 0) {
            assertEquals(1, candidate.time.reads, label + ": one sample per call");
        }
        return new Run(candidate, actual);
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void differentialBoundaries(Scenario scenario) {
        for (Kind kind : new Kind[] {Kind.CORE, Kind.CHECKED}) {
            for (int budget : CONTROLLED_BUDGETS) {
                Run run = compare(scenario, kind, budget, true);
                Fixture candidate = run.candidate();
                String label = scenario + " " + kind + " budget=" + budget;
                if (budget <= 0) {
                    continue;
                }
                if (scenario == Scenario.TERMINAL) {
                    assertEquals(4, candidate.state.pc, label);
                    assertEquals(1, candidate.afterCount, label);
                    assertTrue(candidate.events.getLast().startsWith("after:0:true:"), label);
                    assertEquals(
                            1,
                            candidate.bus.accesses.stream()
                                    .filter(s -> s.startsWith("write:"))
                                    .count(),
                            label);
                }
                if (scenario == Scenario.ENTRY_IRQ) {
                    assertTrue(candidate.events.get(1).startsWith("after:0:false:"), label);
                    if (budget == 1) assertEquals(0, candidate.state.getCycle(), label);
                }
                if (budget >= 2 && scenario == Scenario.ECALL) assertEquals(1, candidate.state.regs[6], label);
                if (scenario == Scenario.WFI_ENTRY || scenario == Scenario.WFI_STALL) {
                    assertTrue(candidate.events.getLast().startsWith("after:1:false:"), label);
                    assertEquals(1, candidate.afterCount, label);
                    // An executed WFI advances pc and cycle; a stall check does not.
                    long cycle = scenario == Scenario.WFI_ENTRY ? 1 : 0;
                    assertEquals(cycle, candidate.state.getCycle(), label);
                    assertEquals(cycle * 4, candidate.state.pc, label);
                }
                if (scenario == Scenario.CSR_DISPATCH || scenario == Scenario.MRET_DISPATCH) {
                    // Dispatched in the same attempt as the instruction, even at budget 1.
                    assertEquals(0x8000000b, candidate.state.mcause, label);
                    if (budget == 1) assertEquals(1, candidate.state.getCycle(), label);
                }
                if (scenario == Scenario.TIMER_ZERO_DUE || scenario == Scenario.TIMER_THRESHOLD) {
                    assertEquals(0x80000007, candidate.state.mcause, label);
                }
                if (scenario == Scenario.TIMER_ZERO) {
                    assertEquals(0, candidate.state.mip & MTIP, label);
                    assertEquals(0, candidate.state.mcause, label);
                }
                if (run.outcome().stopped()) {
                    assertFalse(candidate.state.reservationValid, label);
                    assertEquals(-1, candidate.bus.reservation, label);
                }
            }
        }
    }

    @Test
    void nullControllerMatchesReference() {
        for (Kind kind : new Kind[] {Kind.CORE, Kind.CHECKED}) {
            for (Scenario scenario : Scenario.values()) {
                for (int budget : UNCONTROLLED_BUDGETS) {
                    assertEquals(0, compare(scenario, kind, budget, false).candidate().beforeCount);
                }
            }
        }
    }

    @Test
    void wfiPostSampleArrivalReturnsThenWakesOnResume() {
        for (Kind kind : new Kind[] {Kind.CORE, Kind.CHECKED}) {
            for (Scenario scenario : new Scenario[] {Scenario.WFI_POST_ARRIVAL, Scenario.WFI_STALL_POST_ARRIVAL}) {
                Fixture reference = new Fixture(scenario, Kind.REFERENCE);
                Fixture candidate = new Fixture(scenario, kind);
                assertEquals(reference.execute(6, true), candidate.execute(6, true));
                assertEquals(1, candidate.afterCount);
                assertEquals(MEIP, candidate.state.mip & MEIP);
                assertTrue((candidate.state.extraflags & 4) != 0);
                long cycles = candidate.state.getCycle();
                assertEquals(reference.execute(1, true), candidate.execute(1, true));
                assertEquals(cycles, candidate.state.getCycle());
                assertEquals(0x8000000b, candidate.state.mcause);
                assertEquals(reference.snapshot(), candidate.snapshot());
                assertEquals(reference.events, candidate.events);
                assertEquals(2, candidate.time.reads); // one per call
            }
        }
    }

    @Test
    void controllerStopWinsOverWfiAndSegmentsResumeByAttempts() {
        for (Kind kind : new Kind[] {Kind.CORE, Kind.CHECKED}) {
            Fixture wfi = new Fixture(Scenario.WFI_ENTRY, kind);
            AttemptResult stopped = wfi.core.stepAttempts(wfi.state, wfi.bus, 0, 256, 6, null, new AttemptController() {
                @Override
                public void beforeAttempt(RV32IMAState state) {}

                @Override
                public boolean afterAttempt(RV32IMAState state, int result) {
                    return true;
                }
            });
            assertEquals(new AttemptResult(1, 1, true), stopped);
            assertEquals(AttemptResult.ExitReason.CONTROLLER_STOP, stopped.exitReason());

            // A fixed clock: the segmented run makes two calls, so two samples, where the
            // reference makes one.
            Fixture segmented = new Fixture(Scenario.ENTRY_IRQ, kind);
            Fixture reference = new Fixture(Scenario.ENTRY_IRQ, Kind.REFERENCE);
            segmented.time.step = 0;
            reference.time.step = 0;
            reference.execute(6, true);
            Outcome first = segmented.execute(6, new AttemptController() {
                @Override
                public void beforeAttempt(RV32IMAState state) {
                    segmented.beforeAttempt(state);
                }

                @Override
                public boolean afterAttempt(RV32IMAState state, int result) {
                    segmented.afterAttempt(state, result);
                    return true;
                }
            });
            assertEquals(1, first.attempts());
            assertEquals(0, segmented.state.getCycle());
            segmented.execute(6 - first.attempts(), true);
            assertEquals(reference.snapshot(), segmented.snapshot());
            assertEquals(reference.events, segmented.events);
        }
    }

    /**
     * The oracle's hook observations, moved to {@link RV32IMACore#step}: its reference loop of
     * repeated {@code step(count=1)} calls with a hook and callbacks, run on the core and on the
     * reference, compared event by event.
     */
    @ParameterizedTest
    @EnumSource(Scenario.class)
    void hookObservationsUnderRepeatedStep(Scenario scenario) {
        for (Kind kind : new Kind[] {Kind.CORE, Kind.CHECKED}) {
            for (int budget : new int[] {1, 2, 6}) {
                Fixture reference = new Fixture(scenario, Kind.REFERENCE);
                Fixture candidate = new Fixture(scenario, kind);
                if (!candidate.running || candidate.bus.halted) {
                    continue; // the embedder's initial checks; no step runs
                }
                reference.executeRepeatedSteps(budget);
                candidate.executeRepeatedSteps(budget);
                String label = scenario + " " + kind + " budget=" + budget;
                assertEquals(reference.events, candidate.events, label);
                assertEquals(reference.snapshot(), candidate.snapshot(), label);
                long hookEvents = candidate.events.stream()
                        .filter(e -> e.startsWith("hook:"))
                        .count();
                if (scenario == Scenario.WFI_ENTRY || scenario == Scenario.WFI_POST_ARRIVAL) {
                    assertEquals(1, hookEvents, label); // the executed, stalling WFI
                    assertTrue(candidate.events.get(1).startsWith("hook:0:" + WFI + ":0:"), label);
                } else if (scenario == Scenario.WFI_STALL || scenario == Scenario.WFI_STALL_POST_ARRIVAL) {
                    assertEquals(0, hookEvents, label); // a stall check executes nothing
                }
            }
        }
    }
}
