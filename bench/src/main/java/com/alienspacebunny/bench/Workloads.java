package com.alienspacebunny.bench;

import static com.alienspacebunny.bench.Asm.A0;
import static com.alienspacebunny.bench.Asm.A1;
import static com.alienspacebunny.bench.Asm.A2;
import static com.alienspacebunny.bench.Asm.A3;
import static com.alienspacebunny.bench.Asm.S0;
import static com.alienspacebunny.bench.Asm.S1;
import static com.alienspacebunny.bench.Asm.S2;
import static com.alienspacebunny.bench.Asm.S4;
import static com.alienspacebunny.bench.Asm.S5;
import static com.alienspacebunny.bench.Asm.T0;
import static com.alienspacebunny.bench.Asm.T1;
import static com.alienspacebunny.bench.Asm.T2;
import static com.alienspacebunny.bench.Asm.T3;
import static com.alienspacebunny.bench.Asm.T4;
import static com.alienspacebunny.bench.Asm.T5;
import static com.alienspacebunny.bench.Asm.T6;
import static com.alienspacebunny.bench.Asm.ZERO;

import com.alienspacebunny.emu.AccessContext;
import com.alienspacebunny.emu.AccessKind;
import com.alienspacebunny.emu.IsaConfig;
import com.alienspacebunny.emu.MemoryBus;
import com.alienspacebunny.emu.RV32IMACore;
import com.alienspacebunny.emu.RV32IMAState;
import java.util.List;

/**
 * The fixed benchmark workloads. Every guest workload has an analytical instruction count and an
 * independent Java reference model of its result; every host workload has an independent
 * checksum. {@link Workload#verify} rejects any mismatch, so fast incomplete or excess work fails.
 */
final class Workloads {
    static final int MEM_SIZE = 16 << 20;
    static final int SRC = 0x100000;
    static final int DST = 0x800000;
    static final int MIX = 0xF00000;
    static final int GUARD = 0x5AFEC0DE;
    static final int POISON = 0xDEADBEEF;

    /** V-32's AP profile: I/M/A/C/F/Zicsr/Zba/Zbb/Zabha and user mode. */
    static final IsaConfig AP = new IsaConfig(true, true, true, true, true, true);

    static final IsaConfig IMA = IsaConfig.RV32IMA_ZICSR;

    private static final AccessContext HOST_LOAD = new AccessContext(0, 3, AccessKind.LOAD, 4, 0);
    private static final AccessContext HOST_STORE = new AccessContext(0, 3, AccessKind.STORE, 4, 0);

    private Workloads() {}

    /** Problem sizes: {@code full} for timing, {@code test} for fast unit checks. */
    enum Scale {
        FULL(1_000_000, 1_572_864, 1_536, 200_000),
        TEST(1_000, 4_096, 3, 500);

        final int regIterations;
        final int largeWords;
        final int smallPasses;
        final int mixIterations;

        Scale(int regIterations, int largeWords, int smallPasses, int mixIterations) {
            this.regIterations = regIterations;
            this.largeWords = largeWords;
            this.smallPasses = smallPasses;
            this.mixIterations = mixIterations;
        }
    }

    static List<Workload> all(Scale scale, long skew) {
        int n = scale.regIterations;
        return List.of(
                new RegLoop("reg-ima", IMA, false, n, 4096, skew),
                new RegLoop("reg-ap", AP, false, n, 4096, skew),
                new RegLoop("regc-ap", AP, true, n, 4096, skew),
                new RegLoop("reg-ap-step1", AP, false, n, 1, skew),
                new CopyLoop("copy4k-ap", 1024, scale.smallPasses, skew),
                new CopyLoop("copy6m-ap", scale.largeWords, 1, skew),
                new MixLoop("mix-ap", scale.mixIterations, skew),
                new HostRead("host-read6m", scale.largeWords, 2, skew),
                new HostCopy("host-copy4k", 1024, scale.smallPasses, skew));
    }

    static int pattern(int index) {
        return index * 0x9E3779B1 + 0x2545F491;
    }

    static void writeSource(MemoryBus bus, int words) {
        for (int i = 0; i < words; i++) {
            bus.writeInt(SRC + 4 * i, pattern(i));
        }
    }

    static int sourceSum(int words) {
        int sum = 0;
        for (int i = 0; i < words; i++) {
            sum += pattern(i);
        }
        return sum;
    }

    static void poisonDestination(MemoryBus bus, int words) {
        bus.writeInt(DST - 4, GUARD);
        for (int i = 0; i < words; i++) {
            bus.writeInt(DST + 4 * i, POISON);
        }
        bus.writeInt(DST + 4 * words, GUARD);
    }

    static void checkDestination(MemoryBus bus, int words) {
        check(bus.readInt(DST - 4) == GUARD, "low guard overwritten");
        check(bus.readInt(DST + 4 * words) == GUARD, "high guard overwritten");
        for (int i = 0; i < words; i++) {
            if (bus.readInt(DST + 4 * i) != pattern(i)) {
                throw new IllegalStateException("destination word " + i + " mismatch");
            }
        }
    }

    static void check(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException(message);
        }
    }

    /** One timed unit of work, with untimed preparation and verification around it. */
    abstract static class Workload {
        final String name;

        Workload(String name) {
            this.name = name;
        }

        /** Writes data that no invocation modifies. Called once per process, untimed. */
        void prepareOnce(MemoryBus bus) {}

        /** Resets per-invocation state. Untimed. */
        abstract void prepare(MemoryBus bus);

        /** The timed region. Returns the work performed (guest instructions or host words). */
        abstract long run(MemoryBus bus);

        /** Checks the invocation's complete result. Untimed; throws on any mismatch. */
        abstract void verify(MemoryBus bus);
    }

    /** A guest program run to its terminating {@code WFI}. */
    abstract static class GuestWorkload extends Workload {
        private final RV32IMACore core;
        private final int chunk;
        private final byte[] program;
        private final long expectedInstructions;
        RV32IMAState state;

        GuestWorkload(String name, IsaConfig isa, int chunk, Asm asm, long analyticalCount, long skew) {
            super(name);
            this.core = new RV32IMACore(isa);
            this.chunk = chunk;
            this.program = asm.bytes();
            this.expectedInstructions = analyticalCount + skew;
        }

        @Override
        void prepare(MemoryBus bus) {
            for (int i = 0; i < program.length; i++) {
                bus.writeByte(i, program[i]);
            }
            state = new RV32IMAState();
            state.extraflags = 3; // machine mode, as after reset
            prepareData(bus);
        }

        abstract void prepareData(MemoryBus bus);

        @Override
        long run(MemoryBus bus) {
            RV32IMAState s = state;
            long maxCalls = expectedInstructions + 16;
            long calls = 0;
            while (core.step(s, bus, 0, MEM_SIZE, 0, chunk, null, null) == 0) {
                if (++calls > maxCalls) {
                    throw new IllegalStateException(name + ": no WFI after " + calls + " step calls");
                }
            }
            return s.getCycle();
        }

        @Override
        void verify(MemoryBus bus) {
            check(
                    state.getCycle() == expectedInstructions,
                    name + ": executed " + state.getCycle() + " instructions, expected " + expectedInstructions);
            check(state.pc == program.length, name + ": stopped at pc " + Integer.toHexString(state.pc));
            check(state.mcause == 0 && state.mepc == 0, name + ": unexpected trap, mcause " + state.mcause);
            checkResult(bus);
        }

        abstract void checkResult(MemoryBus bus);
    }

    /** Register-only ALU/branch loop; instruction fetch is its only bus traffic. */
    static final class RegLoop extends GuestWorkload {
        private final int iterations;

        RegLoop(String name, IsaConfig isa, boolean compressed, int iterations, int chunk, long skew) {
            this(name, isa, compressed, iterations, chunk, skew, new Asm());
        }

        private RegLoop(String name, IsaConfig isa, boolean compressed, int iterations, int chunk, long skew, Asm asm) {
            super(name, isa, chunk, assemble(asm, compressed, iterations), 6L + 5L * iterations, skew);
            this.iterations = iterations;
        }

        private static Asm assemble(Asm asm, boolean compressed, int iterations) {
            asm.li(A0, iterations);
            asm.addi(A1, ZERO, 1);
            asm.addi(A2, ZERO, 7);
            asm.addi(A3, ZERO, 0);
            int loop = asm.pc();
            if (compressed) {
                asm.cAdd(A1, A0);
                asm.cXor(A2, A1);
                asm.sub(A3, A2, A3); // rd != rs1: no compressed form, so the loop mixes widths
                asm.cAddi(A0, -1);
                asm.cBnez(A0, loop);
            } else {
                asm.add(A1, A1, A0);
                asm.xor(A2, A2, A1);
                asm.sub(A3, A2, A3);
                asm.addi(A0, A0, -1);
                asm.bne(A0, ZERO, loop);
            }
            asm.wfi();
            check(asm.count() == 11, "unexpected RegLoop length");
            return asm;
        }

        @Override
        void prepareData(MemoryBus bus) {}

        @Override
        void checkResult(MemoryBus bus) {
            int a0 = iterations;
            int a1 = 1;
            int a2 = 7;
            int a3 = 0;
            while (a0 != 0) {
                a1 += a0;
                a2 ^= a1;
                a3 = a2 - a3;
                a0--;
            }
            int[] r = state.regs;
            check(r[A0] == 0 && r[A1] == a1 && r[A2] == a2 && r[A3] == a3, name + ": register result mismatch");
        }
    }

    /** Word copy plus checksum, repeated over the same source. */
    static final class CopyLoop extends GuestWorkload {
        private final int words;
        private final int passes;

        CopyLoop(String name, int words, int passes, long skew) {
            super(name, AP, 4096, assemble(words, passes), 8L + passes * (6L + 7L * words), skew);
            this.words = words;
            this.passes = passes;
        }

        private static Asm assemble(int words, int passes) {
            Asm asm = new Asm();
            asm.li(S4, SRC);
            asm.li(S5, DST);
            asm.li(T2, passes);
            asm.addi(A1, ZERO, 0);
            int outer = asm.pc();
            asm.addi(S0, S4, 0);
            asm.addi(S1, S5, 0);
            asm.li(T0, words);
            int inner = asm.pc();
            asm.lw(T1, S0, 0);
            asm.sw(T1, S1, 0);
            asm.add(A1, A1, T1);
            asm.addi(S0, S0, 4);
            asm.addi(S1, S1, 4);
            asm.addi(T0, T0, -1);
            asm.bne(T0, ZERO, inner);
            asm.addi(T2, T2, -1);
            asm.bne(T2, ZERO, outer);
            asm.wfi();
            check(asm.count() == 21, "unexpected CopyLoop length");
            return asm;
        }

        @Override
        void prepareOnce(MemoryBus bus) {
            writeSource(bus, words);
        }

        @Override
        void prepareData(MemoryBus bus) {
            poisonDestination(bus, words);
        }

        @Override
        void checkResult(MemoryBus bus) {
            int[] r = state.regs;
            check(r[A1] == passes * sourceSum(words), name + ": checksum mismatch");
            check(r[S0] == SRC + 4 * words && r[S1] == DST + 4 * words, name + ": final pointers mismatch");
            check(r[T0] == 0 && r[T2] == 0, name + ": loop counters not exhausted");
            checkDestination(bus, words);
        }
    }

    /** Every data-access context path: LB/LH/LW/SB/SH/SW, AMOADD.W and an LR.W/SC.W pair. */
    static final class MixLoop extends GuestWorkload {
        private final int iterations;

        MixLoop(String name, int iterations, long skew) {
            super(name, AP, 4096, assemble(iterations), 8L + 17L * iterations, skew);
            this.iterations = iterations;
        }

        private static Asm assemble(int iterations) {
            Asm asm = new Asm();
            asm.li(S0, MIX);
            asm.addi(S1, S0, 16);
            asm.addi(S2, S0, 32);
            asm.li(A0, iterations);
            asm.addi(A1, ZERO, 0);
            int loop = asm.pc();
            asm.lw(T1, S0, 0);
            asm.addi(T1, T1, 1);
            asm.sw(T1, S0, 0);
            asm.lb(T2, S0, 4);
            asm.add(A1, A1, T2);
            asm.sb(A0, S0, 4);
            asm.lh(T3, S0, 8);
            asm.add(A1, A1, T3);
            asm.sh(A0, S0, 8);
            asm.amoaddW(T4, A0, S1);
            asm.add(A1, A1, T4);
            asm.lrW(T5, S2);
            asm.addi(T5, T5, 3);
            asm.scW(T6, T5, S2);
            asm.add(A1, A1, T6);
            asm.addi(A0, A0, -1);
            asm.bne(A0, ZERO, loop);
            asm.wfi();
            check(asm.count() == 25, "unexpected MixLoop length");
            return asm;
        }

        @Override
        void prepareData(MemoryBus bus) {
            for (int i = 0; i < 64; i += 4) {
                bus.writeInt(MIX + i, 0);
            }
        }

        @Override
        void checkResult(MemoryBus bus) {
            int word = 0;
            byte b = 0;
            short h = 0;
            int amo = 0;
            int lr = 0;
            int sum = 0;
            for (int a0 = iterations; a0 != 0; a0--) {
                word++;
                sum += b;
                b = (byte) a0;
                sum += h;
                h = (short) a0;
                sum += amo;
                amo += a0;
                lr += 3;
            }
            int[] r = state.regs;
            check(r[A1] == sum && r[T6] == 0, name + ": register result mismatch");
            check(bus.readInt(MIX) == word, name + ": word counter mismatch");
            check(bus.readByte(MIX + 4) == b && bus.readShort(MIX + 8) == h, name + ": sub-word store mismatch");
            check(bus.readInt(MIX + 16) == amo && bus.readInt(MIX + 32) == lr, name + ": atomic result mismatch");
        }
    }

    /** Host-only scalar read loop through the bus's context overload, with no core. */
    static final class HostRead extends Workload {
        private final int words;
        private final int passes;
        private final long skew;
        private int sum;

        HostRead(String name, int words, int passes, long skew) {
            super(name);
            this.words = words;
            this.passes = passes;
            this.skew = skew;
        }

        @Override
        void prepareOnce(MemoryBus bus) {
            writeSource(bus, words);
        }

        @Override
        void prepare(MemoryBus bus) {
            sum = 0;
        }

        @Override
        long run(MemoryBus bus) {
            int s = 0;
            for (int p = 0; p < passes; p++) {
                for (int i = 0; i < words; i++) {
                    s += bus.readInt(SRC + 4 * i, HOST_LOAD);
                }
            }
            sum = s;
            return (long) words * passes;
        }

        @Override
        void verify(MemoryBus bus) {
            check(sum == passes * sourceSum(words) + (int) skew, name + ": checksum mismatch");
        }
    }

    /** Host-only scalar copy loop through the bus's context overloads, with no core. */
    static final class HostCopy extends Workload {
        private final int words;
        private final int passes;
        private final long skew;
        private int sum;

        HostCopy(String name, int words, int passes, long skew) {
            super(name);
            this.words = words;
            this.passes = passes;
            this.skew = skew;
        }

        @Override
        void prepareOnce(MemoryBus bus) {
            writeSource(bus, words);
        }

        @Override
        void prepare(MemoryBus bus) {
            sum = 0;
            poisonDestination(bus, words);
        }

        @Override
        long run(MemoryBus bus) {
            int s = 0;
            for (int p = 0; p < passes; p++) {
                for (int i = 0; i < words; i++) {
                    int v = bus.readInt(SRC + 4 * i, HOST_LOAD);
                    bus.writeInt(DST + 4 * i, v, HOST_STORE);
                    s += v;
                }
            }
            sum = s;
            return (long) words * passes;
        }

        @Override
        void verify(MemoryBus bus) {
            check(sum == passes * sourceSum(words) + (int) skew, name + ": checksum mismatch");
            checkDestination(bus, words);
        }
    }
}
