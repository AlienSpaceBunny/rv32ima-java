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
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;

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
    static final int FP_DATA = 0xF10000;
    static final int GUARD = 0x5AFEC0DE;
    static final int POISON = 0xDEADBEEF;

    /** V-32's AP profile: I/M/A/C/F/Zicsr/Zba/Zbb/Zabha and user mode. */
    static final IsaConfig AP = new IsaConfig(true, true, true, true, true, true);

    static final IsaConfig IMA = IsaConfig.RV32IMA_ZICSR;

    /** The AP profile without C: runs the no-C guest builds, isolating compressed-fetch costs. */
    static final IsaConfig AP_NO_C = new IsaConfig(false, true, true, true, true, true);

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
                new FpLoop("fp-ap", scale.mixIterations, skew),
                new CoreMarkImage("coremark-c", AP, skew),
                new CoreMarkImage("coremark-noc", AP_NO_C, skew),
                new FpKernelsImage("fpkernels-c", AP, skew),
                new FpKernelsImage("fpkernels-noc", AP_NO_C, skew),
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

    /**
     * Single-precision FP loop: FLW/FSW, FMADD.S, FADD.S and FMUL.S (rm = RNE). The reference
     * model uses Java float arithmetic and {@link Math#fma(float, float, float)}, which round to
     * nearest even with a single rounding for the fused op, matching RISC-V.
     */
    static final class FpLoop extends GuestWorkload {
        private static final float A = 1.1f;
        private static final float B = 0.9f;
        private final int iterations;

        FpLoop(String name, int iterations, long skew) {
            super(name, AP, 4096, assemble(iterations), 5L + 8L * iterations, skew);
            this.iterations = iterations;
        }

        private static Asm assemble(int iterations) {
            Asm asm = new Asm();
            asm.li(S0, FP_DATA);
            asm.li(A0, iterations);
            int loop = asm.pc();
            asm.flw(1, S0, 0);
            asm.flw(5, S0, 4);
            asm.fmaddS(2, 1, 5, 2);
            asm.faddS(3, 3, 1);
            asm.fmulS(4, 1, 5);
            asm.fsw(4, S0, 8);
            asm.addi(A0, A0, -1);
            asm.bne(A0, ZERO, loop);
            asm.wfi();
            check(asm.count() == 13, "unexpected FpLoop length");
            return asm;
        }

        @Override
        void prepareData(MemoryBus bus) {
            bus.writeInt(FP_DATA, Float.floatToRawIntBits(A));
            bus.writeInt(FP_DATA + 4, Float.floatToRawIntBits(B));
            bus.writeInt(FP_DATA + 8, 0);
        }

        @Override
        void checkResult(MemoryBus bus) {
            float f2 = 0f;
            float f3 = 0f;
            for (int i = 0; i < iterations; i++) {
                f2 = Math.fma(A, B, f2);
                f3 += A;
            }
            check((int) state.fregs[2] == Float.floatToRawIntBits(f2), name + ": fmadd accumulator mismatch");
            check((int) state.fregs[3] == Float.floatToRawIntBits(f3), name + ": fadd accumulator mismatch");
            check(bus.readInt(FP_DATA + 8) == Float.floatToRawIntBits(A * B), name + ": fsw result mismatch");
            check((state.fcsr & 0x1e) == 0 && (state.fcsr & 1) != 0, name + ": unexpected fflags " + state.fcsr);
        }
    }

    /** Guest result block written by bench/guest/crt0.S and the guest programs. */
    static final int RESULTS = 0x00FF8000;

    static final int RESULTS_MAGIC = 0xC0DEC0DE;

    private static final Properties EXPECTED = loadExpected();

    private static Properties loadExpected() {
        Properties p = new Properties();
        try (InputStream in = Workloads.class.getResourceAsStream("guest/expected.properties")) {
            if (in == null) {
                throw new IllegalStateException("guest/expected.properties missing");
            }
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    static long expected(String key) {
        String value = EXPECTED.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("no pinned expectation for " + key);
        }
        return Long.decode(value.trim());
    }

    private static byte[] image(String name) {
        try (InputStream in = Workloads.class.getResourceAsStream("guest/" + name + ".bin")) {
            if (in == null) {
                throw new IllegalStateException("guest image " + name + ".bin missing; run make in bench/guest");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A prebuilt guest image (bench/guest) run to its terminating WFI. Its instruction count is
     * pinned in guest/expected.properties; correctness is checked independently by each
     * subclass (known CRCs or a Java reference model).
     */
    abstract static class ImageWorkload extends Workload {
        private final RV32IMACore core;
        private final byte[] program;
        private final long expectedInstructions;
        RV32IMAState state;

        ImageWorkload(String name, IsaConfig isa, long skew) {
            super(name);
            this.core = new RV32IMACore(isa);
            this.program = image(name);
            this.expectedInstructions = expected(name + ".instructions") + skew;
        }

        @Override
        void prepare(MemoryBus bus) {
            for (int i = 0; i < program.length; i++) {
                bus.writeByte(i, program[i]);
            }
            for (int i = 0; i < 64; i += 4) {
                bus.writeInt(RESULTS + i, 0);
            }
            state = new RV32IMAState();
            state.extraflags = 3;
        }

        @Override
        long run(MemoryBus bus) {
            RV32IMAState s = state;
            // An unpinned image (count 0) may run up to 2^32 instructions so verification can
            // report its actual count for pinning.
            long maxCalls = expectedInstructions > 0 ? expectedInstructions + 16 : 1L << 20;
            long calls = 0;
            while (core.step(s, bus, 0, MEM_SIZE, 0, 4096, null, null) == 0) {
                if (++calls > maxCalls) {
                    throw new IllegalStateException(name + ": no WFI after " + calls + " step calls");
                }
            }
            return s.getCycle();
        }

        @Override
        void verify(MemoryBus bus) {
            check(bus.readInt(RESULTS) == RESULTS_MAGIC, name + ": guest did not complete");
            check(bus.readInt(RESULTS + 4) == 0, name + ": main returned " + bus.readInt(RESULTS + 4));
            check(state.pc == bus.readInt(RESULTS + 8) + 4, name + ": stopped at pc " + Integer.toHexString(state.pc));
            check(state.mcause == 0 && state.mepc == 0, name + ": unexpected trap, mcause " + state.mcause);
            check(
                    state.getCycle() == expectedInstructions,
                    name + ": executed " + state.getCycle() + " instructions, expected " + expectedInstructions);
            checkResult(bus);
        }

        abstract void checkResult(MemoryBus bus);
    }

    /**
     * EEMBC CoreMark (unmodified Apache-2.0 sources plus our port layer), standard 2K
     * performance-run seeds, 10 iterations. Checked against CoreMark's own known list/matrix/
     * state CRCs for those seeds; the final CRC must equal the pinned value, which is the same
     * for the C and no-C builds. Not a compliant CoreMark score.
     */
    static final class CoreMarkImage extends ImageWorkload {
        CoreMarkImage(String name, IsaConfig isa, long skew) {
            super(name, isa, skew);
        }

        @Override
        void checkResult(MemoryBus bus) {
            checkWord(bus, RESULTS + 16, 0xe714, "list CRC");
            checkWord(bus, RESULTS + 20, 0x1fd7, "matrix CRC");
            checkWord(bus, RESULTS + 24, 0x8e3a, "state CRC");
            checkWord(bus, RESULTS + 28, 0, "CoreMark error count");
            checkWord(bus, RESULTS + 32, 10, "iterations");
            checkWord(bus, RESULTS + 36, 2000 / 3, "per-algorithm data size");
            checkWord(bus, RESULTS + 12, (int) expected("coremark.crcfinal"), "final CRC");
        }

        private void checkWord(MemoryBus bus, int address, int expectedValue, String what) {
            int actual = bus.readInt(address);
            check(
                    actual == expectedValue,
                    name + ": " + what + " 0x" + Integer.toHexString(actual) + ", expected 0x"
                            + Integer.toHexString(expectedValue));
        }
    }

    /** bench/guest/fpkernels.c, checked against {@link FpKernelModel}. */
    static final class FpKernelsImage extends ImageWorkload {
        private static final int[] EXPECTED_SUMS = FpKernelModel.run();

        FpKernelsImage(String name, IsaConfig isa, long skew) {
            super(name, isa, skew);
        }

        @Override
        void checkResult(MemoryBus bus) {
            check(bus.readInt(RESULTS + 12) == EXPECTED_SUMS[0], name + ": sgemm checksum mismatch");
            check(bus.readInt(RESULTS + 16) == EXPECTED_SUMS[1], name + ": nbody checksum mismatch");
            check(bus.readInt(RESULTS + 20) == EXPECTED_SUMS[2], name + ": horner checksum mismatch");
        }
    }

    /**
     * Java reference for bench/guest/fpkernels.c: the same operations in the same order. Java
     * float arithmetic is IEEE single with round-to-nearest-even, {@link Math#fma(float, float,
     * float)} rounds once like {@code fmaf}, and rounding the double square root of a float
     * gives the correctly rounded float square root.
     */
    static final class FpKernelModel {
        private static final int N = 48;
        private static final int NB = 8;
        private static final int STEPS = 600;
        private static final float DT = 0.01f;
        private static final int NP = 1024;
        private static final int REPS = 6;
        private static final int DEG = 8;

        private int lcg = 12345;

        private FpKernelModel() {}

        static int[] run() {
            FpKernelModel m = new FpKernelModel();
            return new int[] {m.sgemm(), m.nbody(), m.horner()};
        }

        private float lcgf() {
            lcg = lcg * 1664525 + 1013904223;
            return (float) (lcg >>> 8) * (1.0f / 16777216.0f) * 2.0f - 1.0f;
        }

        private int sgemm() {
            float[][] a = new float[N][N];
            float[][] b = new float[N][N];
            float[][] c = new float[N][N];
            for (int i = 0; i < N; i++) {
                for (int k = 0; k < N; k++) {
                    a[i][k] = lcgf();
                    b[i][k] = lcgf();
                }
            }
            for (int i = 0; i < N; i++) {
                for (int j = 0; j < N; j++) {
                    float s = 0.0f;
                    for (int k = 0; k < N; k++) {
                        s = s + a[i][k] * b[k][j];
                    }
                    c[i][j] = s;
                }
            }
            int h = 0;
            for (int i = 0; i < N; i++) {
                for (int j = 0; j < N; j++) {
                    h = h * 31 + Float.floatToRawIntBits(c[i][j]);
                }
            }
            return h;
        }

        private int nbody() {
            float[] px = new float[NB];
            float[] py = new float[NB];
            float[] pz = new float[NB];
            float[] vx = new float[NB];
            float[] vy = new float[NB];
            float[] vz = new float[NB];
            float[] m = new float[NB];
            for (int i = 0; i < NB; i++) {
                px[i] = lcgf() * 10.0f;
                py[i] = lcgf() * 10.0f;
                pz[i] = lcgf() * 10.0f;
                vx[i] = lcgf() * 0.1f;
                vy[i] = lcgf() * 0.1f;
                vz[i] = lcgf() * 0.1f;
                m[i] = lcgf() + 1.5f;
            }
            for (int s = 0; s < STEPS; s++) {
                for (int i = 0; i < NB; i++) {
                    for (int j = i + 1; j < NB; j++) {
                        float dx = px[i] - px[j];
                        float dy = py[i] - py[j];
                        float dz = pz[i] - pz[j];
                        float d2 = dx * dx + dy * dy + dz * dz + 0.01f;
                        float d = (float) Math.sqrt(d2);
                        float mag = DT / (d2 * d);
                        vx[i] = vx[i] - dx * m[j] * mag;
                        vy[i] = vy[i] - dy * m[j] * mag;
                        vz[i] = vz[i] - dz * m[j] * mag;
                        vx[j] = vx[j] + dx * m[i] * mag;
                        vy[j] = vy[j] + dy * m[i] * mag;
                        vz[j] = vz[j] + dz * m[i] * mag;
                    }
                }
                for (int i = 0; i < NB; i++) {
                    px[i] = px[i] + DT * vx[i];
                    py[i] = py[i] + DT * vy[i];
                    pz[i] = pz[i] + DT * vz[i];
                }
            }
            int h = 0;
            for (int i = 0; i < NB; i++) {
                h = h * 31 + Float.floatToRawIntBits(px[i]);
                h = h * 31 + Float.floatToRawIntBits(py[i]);
                h = h * 31 + Float.floatToRawIntBits(pz[i]);
                h = h * 31 + Float.floatToRawIntBits(vx[i]);
                h = h * 31 + Float.floatToRawIntBits(vy[i]);
                h = h * 31 + Float.floatToRawIntBits(vz[i]);
            }
            return h;
        }

        private int horner() {
            float[] coef = new float[DEG + 1];
            float[] xs = new float[NP];
            float[] acc = new float[NP];
            for (int k = 0; k <= DEG; k++) {
                coef[k] = lcgf();
            }
            for (int i = 0; i < NP; i++) {
                xs[i] = lcgf();
                acc[i] = 0.0f;
            }
            for (int r = 0; r < REPS; r++) {
                for (int i = 0; i < NP; i++) {
                    float x = xs[i];
                    float y = coef[DEG];
                    for (int k = DEG - 1; k >= 0; k--) {
                        y = Math.fma(y, x, coef[k]);
                    }
                    acc[i] = Math.fma(acc[i], 0.5f, y);
                }
            }
            int h = 0;
            for (int i = 0; i < NP; i++) {
                h = h * 31 + Float.floatToRawIntBits(acc[i]);
            }
            return h;
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
