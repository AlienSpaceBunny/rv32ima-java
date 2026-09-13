package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Tests for the RV32F Phase 5b rounding-mode layer: FADD/FSUB/FMUL/FDIV/FSQRT.S, the FMADD
 * family, and FCVT.{W,WU}.S/FCVT.S.{W,WU}. Companion to {@link FExtensionTest} (Phase 5a).
 *
 * <p>Where a hand-computed expected value would just re-derive the implementation's own
 * double-residual rounding formulas, tests instead use an independent oracle: {@link
 * java.math.BigDecimal} exact arithmetic (or {@link Math#fma(float, float, float)}, a JDK-provided
 * correctly-rounded single-rounding primitive, for FMA's RNE case) rather than the same
 * TwoSum/fma-residual approximations the production code uses.
 */
public class FExtensionRoundingTest {
    private static final int RAM_OFFSET = 0x80000000;
    private static final int RAM_SIZE = 256;
    private static final IsaConfig HAS_F = new IsaConfig(false, true, false, false, false);
    private static final IsaConfig NO_F = IsaConfig.RV32IMA_ZICSR;

    private static final int RM_RNE = 0;
    private static final int RM_RTZ = 1;
    private static final int RM_RDN = 2;
    private static final int RM_RUP = 3;
    private static final int RM_RMM = 4;
    private static final int RM_DYN = 7;

    private static final int FFLAGS_NV = 1 << 4;
    private static final int FFLAGS_DZ = 1 << 3;
    private static final int FFLAGS_OF = 1 << 2;
    private static final int FFLAGS_UF = 1 << 1;
    private static final int FFLAGS_NX = 1;

    private static final int FUNCT7_FADD = 0x00;
    private static final int FUNCT7_FSUB = 0x04;
    private static final int FUNCT7_FMUL = 0x08;
    private static final int FUNCT7_FDIV = 0x0C;
    private static final int FUNCT7_FSQRT = 0x2C;
    private static final int FUNCT7_FCVT_W_S = 0x60;
    private static final int FUNCT7_FCVT_S_W = 0x68;

    private static final int OPCODE_FMADD = 0x43;
    private static final int OPCODE_FMSUB = 0x47;
    private static final int OPCODE_FNMSUB = 0x4B;
    private static final int OPCODE_FNMADD = 0x4F;

    private static final int SIGNALING_NAN = 0x7f800001;
    private static final int QUIET_NAN = 0x7fc00000;
    private static final int CANONICAL_NAN = 0x7fc00000;
    private static final int POS_INFINITY = 0x7f800000;
    private static final int NEG_INFINITY = 0xff800000;
    private static final int NEG_ZERO = 0x80000000;

    private static int bits(float f) {
        return Float.floatToRawIntBits(f);
    }

    private static int opfp(int funct7, int funct3, int rd, int rs1, int rs2) {
        return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x53;
    }

    private static int fma(int opcode, int rd, int rs1, int rs2, int rs3, int rm) {
        return (rs3 << 27) | (rs2 << 20) | (rs1 << 15) | (rm << 12) | (rd << 7) | opcode;
    }

    private static RV32IMAState machineState() {
        RV32IMAState state = new RV32IMAState();
        state.pc = RAM_OFFSET;
        state.extraflags |= 3;
        return state;
    }

    private static void setFReg(RV32IMAState state, int idx, int bits) {
        state.fregs[idx] = 0xFFFFFFFF00000000L | (bits & 0xFFFFFFFFL);
    }

    private static RV32IMAState run(IsaConfig config, int instrWord, Consumer<RV32IMAState> stateSetup) {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            if (stateSetup != null) {
                stateSetup.accept(state);
            }
            ram.writeInt(RAM_OFFSET, instrWord);
            new RV32IMACore(config).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            return state;
        }
    }

    private static RV32IMAState runAddSub(int funct7, int aBits, int bBits, int rm) {
        return run(HAS_F, opfp(funct7, rm, 3, 1, 2), s -> {
            setFReg(s, 1, aBits);
            setFReg(s, 2, bBits);
        });
    }

    private static RV32IMAState runSqrt(int aBits, int rm) {
        return run(HAS_F, opfp(FUNCT7_FSQRT, rm, 3, 1, 0), s -> setFReg(s, 1, aBits));
    }

    private static RV32IMAState runFma(int opcode, int aBits, int bBits, int cBits, int rm) {
        return run(HAS_F, fma(opcode, 3, 1, 2, 4, rm), s -> {
            setFReg(s, 1, aBits);
            setFReg(s, 2, bBits);
            setFReg(s, 4, cBits);
        });
    }

    private static RV32IMAState runCvtWS(boolean unsigned, int aBits, int rm) {
        return run(HAS_F, opfp(FUNCT7_FCVT_W_S, rm, 3, 1, unsigned ? 1 : 0), s -> setFReg(s, 1, aBits));
    }

    private static RV32IMAState runCvtSW(boolean unsigned, int value, int rm) {
        return run(HAS_F, opfp(FUNCT7_FCVT_S_W, rm, 3, 1, unsigned ? 1 : 0), s -> s.regs[1] = value);
    }

    // --- FADD.S / FSUB.S basic wiring and NaN/infinity special cases ---

    @Test
    void faddComputesSumAtRne() {
        RV32IMAState state = runAddSub(FUNCT7_FADD, bits(1.0f), bits(2.0f), RM_RNE);
        assertEquals(bits(3.0f), (int) state.fregs[3]);
        assertEquals(0, state.fcsr & FFLAGS_NX);
    }

    @Test
    void fsubComputesDifferenceAtRne() {
        RV32IMAState state = runAddSub(FUNCT7_FSUB, bits(5.0f), bits(2.0f), RM_RNE);
        assertEquals(bits(3.0f), (int) state.fregs[3]);
    }

    @Test
    void faddSignalingNanSetsNvAndReturnsCanonicalNan() {
        RV32IMAState state = runAddSub(FUNCT7_FADD, SIGNALING_NAN, bits(1.0f), RM_RNE);
        assertEquals(CANONICAL_NAN, (int) state.fregs[3]);
        assertEquals(FFLAGS_NV, state.fcsr & FFLAGS_NV);
    }

    @Test
    void faddQuietNanPropagatesWithoutNv() {
        RV32IMAState state = runAddSub(FUNCT7_FADD, QUIET_NAN, bits(1.0f), RM_RNE);
        assertEquals(CANONICAL_NAN, (int) state.fregs[3]);
        assertEquals(0, state.fcsr & FFLAGS_NV);
    }

    @Test
    void faddOppositeSignedInfinitiesIsInvalid() {
        RV32IMAState state = runAddSub(FUNCT7_FADD, POS_INFINITY, NEG_INFINITY, RM_RNE);
        assertEquals(CANONICAL_NAN, (int) state.fregs[3]);
        assertEquals(FFLAGS_NV, state.fcsr & FFLAGS_NV);
    }

    @Test
    void faddSameSignedInfinitiesReturnsThatInfinity() {
        RV32IMAState state = runAddSub(FUNCT7_FADD, POS_INFINITY, POS_INFINITY, RM_RNE);
        assertEquals(POS_INFINITY, (int) state.fregs[3]);
        assertEquals(0, state.fcsr);
    }

    @Test
    void fsubOfEqualOperandsIsInvalidUnderInfinityCancellation() {
        // (+inf) - (+inf) == (+inf) + (-inf): invalid, per IEEE 754.
        RV32IMAState state = runAddSub(FUNCT7_FSUB, POS_INFINITY, POS_INFINITY, RM_RNE);
        assertEquals(CANONICAL_NAN, (int) state.fregs[3]);
        assertEquals(FFLAGS_NV, state.fcsr & FFLAGS_NV);
    }

    @Test
    void faddExactCancellationIsPositiveZeroExceptRoundTowardNegative() {
        RV32IMAState rne = runAddSub(FUNCT7_FADD, bits(3.5f), bits(-3.5f), RM_RNE);
        assertEquals(0, (int) rne.fregs[3]);
        RV32IMAState rdn = runAddSub(FUNCT7_FADD, bits(3.5f), bits(-3.5f), RM_RDN);
        assertEquals(NEG_ZERO, (int) rdn.fregs[3]);
    }

    // --- The RDN/RNE divergence the advisor's review caught: X is not the double intermediate ---

    @Test
    void faddRoundsTowardTrueValueNotTheDoubleIntermediateUnderRdn() {
        // a = 1.0f, b = -smallest subnormal. True value 1.0 - 2^-149 rounds to 1.0f under RNE
        // (nearer neighbor by far), but a correct RDN must round down to nextDown(1.0f), since
        // 1.0f itself is above the true value. A double-rounding-unsafe implementation that treats
        // the correctly-rounded-double intermediate (which IS exactly 1.0) as the truth would
        // wrongly return 1.0f here too.
        int smallestSubnormal = 0x00000001;
        int negSmallestSubnormal = smallestSubnormal | 0x80000000;
        RV32IMAState rne = runAddSub(FUNCT7_FADD, bits(1.0f), negSmallestSubnormal, RM_RNE);
        assertEquals(bits(1.0f), (int) rne.fregs[3]);
        assertEquals(FFLAGS_NX, rne.fcsr & FFLAGS_NX);

        RV32IMAState rdn = runAddSub(FUNCT7_FADD, bits(1.0f), negSmallestSubnormal, RM_RDN);
        assertEquals(bits(Math.nextDown(1.0f)), (int) rdn.fregs[3]);
        assertEquals(FFLAGS_NX, rdn.fcsr & FFLAGS_NX);

        RV32IMAState rup = runAddSub(FUNCT7_FADD, bits(1.0f), negSmallestSubnormal, RM_RUP);
        assertEquals(bits(1.0f), (int) rup.fregs[3]);
    }

    // --- FMUL.S / FDIV.S special values ---

    @Test
    void fmulComputesProductAtRne() {
        RV32IMAState state = runAddSub(FUNCT7_FMUL, bits(2.0f), bits(3.5f), RM_RNE);
        assertEquals(bits(7.0f), (int) state.fregs[3]);
    }

    @Test
    void fmulZeroTimesInfinityIsInvalid() {
        RV32IMAState state = runAddSub(FUNCT7_FMUL, 0x00000000, POS_INFINITY, RM_RNE);
        assertEquals(CANONICAL_NAN, (int) state.fregs[3]);
        assertEquals(FFLAGS_NV, state.fcsr & FFLAGS_NV);
    }

    @Test
    void fdivComputesQuotientAtRne() {
        RV32IMAState state = runAddSub(FUNCT7_FDIV, bits(6.0f), bits(3.0f), RM_RNE);
        assertEquals(bits(2.0f), (int) state.fregs[3]);
    }

    @Test
    void fdivFiniteNonzeroByZeroIsDivideByZero() {
        RV32IMAState state = runAddSub(FUNCT7_FDIV, bits(1.0f), 0x00000000, RM_RNE);
        assertEquals(POS_INFINITY, (int) state.fregs[3]);
        assertEquals(FFLAGS_DZ, state.fcsr & FFLAGS_DZ);
        assertEquals(0, state.fcsr & FFLAGS_NV);
    }

    @Test
    void fdivZeroByZeroIsInvalidNotDivideByZero() {
        RV32IMAState state = runAddSub(FUNCT7_FDIV, 0x00000000, 0x00000000, RM_RNE);
        assertEquals(CANONICAL_NAN, (int) state.fregs[3]);
        assertEquals(FFLAGS_NV, state.fcsr & FFLAGS_NV);
        assertEquals(0, state.fcsr & FFLAGS_DZ);
    }

    @Test
    void fdivInfinityByFiniteIsExactInfinityNoFlags() {
        RV32IMAState state = runAddSub(FUNCT7_FDIV, POS_INFINITY, bits(2.0f), RM_RNE);
        assertEquals(POS_INFINITY, (int) state.fregs[3]);
        assertEquals(0, state.fcsr);
    }

    // --- FSQRT.S ---

    @Test
    void fsqrtComputesRootAtRne() {
        RV32IMAState state = runSqrt(bits(4.0f), RM_RNE);
        assertEquals(bits(2.0f), (int) state.fregs[3]);
    }

    @Test
    void fsqrtOfNegativeIsInvalid() {
        RV32IMAState state = runSqrt(bits(-4.0f), RM_RNE);
        assertEquals(CANONICAL_NAN, (int) state.fregs[3]);
        assertEquals(FFLAGS_NV, state.fcsr & FFLAGS_NV);
    }

    @Test
    void fsqrtOfNegativeZeroIsNegativeZeroWithNoFlag() {
        RV32IMAState state = runSqrt(NEG_ZERO, RM_RNE);
        assertEquals(NEG_ZERO, (int) state.fregs[3]);
        assertEquals(0, state.fcsr);
    }

    @Test
    void fsqrtRequiringRoundingSetsNx() {
        RV32IMAState state = runSqrt(bits(2.0f), RM_RNE);
        assertEquals(bits((float) Math.sqrt(2.0)), (int) state.fregs[3]);
        assertEquals(FFLAGS_NX, state.fcsr & FFLAGS_NX);
    }

    // --- Overflow / underflow ---

    @Test
    void overflowSaturatesAndSetsOfPerRoundingMode() {
        int hugeBits = bits(Float.MAX_VALUE);
        int twoBits = bits(2.0f);
        RV32IMAState rne = runAddSub(FUNCT7_FMUL, hugeBits, twoBits, RM_RNE);
        assertEquals(POS_INFINITY, (int) rne.fregs[3]);
        assertEquals(FFLAGS_OF | FFLAGS_NX, rne.fcsr);

        RV32IMAState rtz = runAddSub(FUNCT7_FMUL, hugeBits, twoBits, RM_RTZ);
        assertEquals(bits(Float.MAX_VALUE), (int) rtz.fregs[3]);
        assertEquals(FFLAGS_OF | FFLAGS_NX, rtz.fcsr);

        RV32IMAState rdn = runAddSub(FUNCT7_FMUL, hugeBits, twoBits, RM_RDN);
        assertEquals(bits(Float.MAX_VALUE), (int) rdn.fregs[3]); // positive overflow rounds down to MAX

        RV32IMAState rup = runAddSub(FUNCT7_FMUL, hugeBits, twoBits, RM_RUP);
        assertEquals(POS_INFINITY, (int) rup.fregs[3]); // positive overflow rounds up to +inf

        RV32IMAState negRdn = runAddSub(FUNCT7_FMUL, hugeBits | 0x80000000, twoBits, RM_RDN);
        assertEquals(NEG_INFINITY, (int) negRdn.fregs[3]); // negative overflow rounds down to -inf
        RV32IMAState negRup = runAddSub(FUNCT7_FMUL, hugeBits | 0x80000000, twoBits, RM_RUP);
        assertEquals(bits(-Float.MAX_VALUE), (int) negRup.fregs[3]); // negative overflow rounds up to -MAX
    }

    @Test
    void underflowToSubnormalSetsUf() {
        // Float.MIN_VALUE (smallest positive subnormal) / 3 is a tiny nonzero, inexact, subnormal
        // result.
        RV32IMAState state = runAddSub(FUNCT7_FDIV, bits(Float.MIN_VALUE), bits(3.0f), RM_RNE);
        assertTrue(Math.abs(Float.intBitsToFloat((int) state.fregs[3])) < Float.MIN_NORMAL);
        assertEquals(FFLAGS_UF | FFLAGS_NX, state.fcsr);
    }

    // --- FMADD family ---

    @Test
    void fmaddComputesAAndBTimesCPlusDAtRne() {
        RV32IMAState state = runFma(OPCODE_FMADD, bits(2.0f), bits(3.0f), bits(4.0f), RM_RNE);
        assertEquals(bits(10.0f), (int) state.fregs[3]); // 2*3+4
    }

    @Test
    void fmsubSubtractsTheAddend() {
        RV32IMAState state = runFma(OPCODE_FMSUB, bits(2.0f), bits(3.0f), bits(4.0f), RM_RNE);
        assertEquals(bits(2.0f), (int) state.fregs[3]); // 2*3-4
    }

    @Test
    void fnmsubNegatesTheProduct() {
        RV32IMAState state = runFma(OPCODE_FNMSUB, bits(2.0f), bits(3.0f), bits(4.0f), RM_RNE);
        assertEquals(bits(-2.0f), (int) state.fregs[3]); // -(2*3)+4
    }

    @Test
    void fnmaddNegatesBothTerms() {
        RV32IMAState state = runFma(OPCODE_FNMADD, bits(2.0f), bits(3.0f), bits(4.0f), RM_RNE);
        assertEquals(bits(-10.0f), (int) state.fregs[3]); // -(2*3)-4
    }

    @Test
    void fmaddSignalingNanOperandSetsNv() {
        RV32IMAState state = runFma(OPCODE_FMADD, SIGNALING_NAN, bits(3.0f), bits(4.0f), RM_RNE);
        assertEquals(CANONICAL_NAN, (int) state.fregs[3]);
        assertEquals(FFLAGS_NV, state.fcsr & FFLAGS_NV);
    }

    @Test
    void fmaddZeroTimesInfinityProductIsInvalid() {
        RV32IMAState state = runFma(OPCODE_FMADD, 0x00000000, POS_INFINITY, bits(1.0f), RM_RNE);
        assertEquals(CANONICAL_NAN, (int) state.fregs[3]);
        assertEquals(FFLAGS_NV, state.fcsr & FFLAGS_NV);
    }

    @Test
    void fmaddTrapsIllegalWithoutHasF() {
        RV32IMAState state = run(NO_F, fma(OPCODE_FMADD, 3, 1, 2, 4, RM_RNE), s -> {
            setFReg(s, 1, bits(2.0f));
            setFReg(s, 2, bits(3.0f));
            setFReg(s, 4, bits(4.0f));
        });
        assertEquals(2, state.mcause);
    }

    /**
     * FMA's headline property: {@code a*b+c} is a single correctly-rounded operation, distinct
     * from computing {@code a*b} then rounding, then adding {@code c} and rounding again. Uses
     * {@link Math#fma(float, float, float)} -- a JDK-provided correctly-rounded FMA primitive,
     * genuinely independent of this class's TwoSum/residual implementation -- as the oracle.
     */
    @Test
    void fmaddMatchesJdkFmaAcrossValuesWhereDoubleRoundingWouldDiverge() {
        float[][] cases = {
            {1.0f + Math.ulp(1.0f), 1.0f + Math.ulp(1.0f), -1.0f},
            {0x1.fffffep30f, 0x1.fffffep30f, -0x1.fffffcp61f},
            {1.23456f, 6.54321f, -9.87654f},
            {1e30f, 1e-30f, 1.0f},
        };
        for (float[] c : cases) {
            RV32IMAState state = runFma(OPCODE_FMADD, bits(c[0]), bits(c[1]), bits(c[2]), RM_RNE);
            float expected = Math.fma(c[0], c[1], c[2]);
            assertEquals(
                    Float.floatToRawIntBits(expected),
                    (int) state.fregs[3],
                    () -> "fma(%s,%s,%s)".formatted(c[0], c[1], c[2]));
        }
    }

    // --- FCVT.W.S / FCVT.WU.S ---

    @Test
    void fcvtWSTruncatesTowardZero() {
        RV32IMAState state = runCvtWS(false, bits(3.7f), RM_RTZ);
        assertEquals(3, state.regs[3]);
        assertEquals(FFLAGS_NX, state.fcsr);
    }

    @Test
    void fcvtWSExactConversionSetsNoFlags() {
        RV32IMAState state = runCvtWS(false, bits(4.0f), RM_RNE);
        assertEquals(4, state.regs[3]);
        assertEquals(0, state.fcsr);
    }

    @Test
    void fcvtWSNanSaturatesToIntMaxWithNvOnly() {
        RV32IMAState state = runCvtWS(false, QUIET_NAN, RM_RNE);
        assertEquals(Integer.MAX_VALUE, state.regs[3]);
        assertEquals(FFLAGS_NV, state.fcsr);
    }

    @Test
    void fcvtWuSNanSaturatesToUintMaxWithNvOnly() {
        RV32IMAState state = runCvtWS(true, QUIET_NAN, RM_RNE);
        assertEquals(0xffffffff, state.regs[3]);
        assertEquals(FFLAGS_NV, state.fcsr);
    }

    @Test
    void fcvtWSNegativeInfinitySaturatesToIntMin() {
        RV32IMAState state = runCvtWS(false, NEG_INFINITY, RM_RNE);
        assertEquals(Integer.MIN_VALUE, state.regs[3]);
        assertEquals(FFLAGS_NV, state.fcsr);
    }

    /**
     * The key ordering the RTZ/RDN pair proves: round first, THEN range-check the rounded value --
     * not the other way around.
     */
    @Test
    void fcvtWuSRoundsBeforeRangeChecking() {
        RV32IMAState rtz = runCvtWS(true, bits(-0.5f), RM_RTZ);
        assertEquals(0, rtz.regs[3]); // -0.5 truncates to -0, which IS in unsigned range
        assertEquals(FFLAGS_NX, rtz.fcsr);

        RV32IMAState rdn = runCvtWS(true, bits(-0.5f), RM_RDN);
        assertEquals(0, rdn.regs[3]); // -0.5 rounds down to -1, out of unsigned range -> saturates
        assertEquals(FFLAGS_NV, rdn.fcsr);
    }

    @Test
    void fcvtWSOutOfRangePositiveSaturatesToIntMaxWithNv() {
        RV32IMAState state = runCvtWS(false, bits(1e30f), RM_RNE);
        assertEquals(Integer.MAX_VALUE, state.regs[3]);
        assertEquals(FFLAGS_NV, state.fcsr);
    }

    @Test
    void fcvtRs2FieldTwoOrThreeTrapsIllegal() {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FCVT_W_S, RM_RNE, 3, 1, 2), s -> setFReg(s, 1, bits(1.0f)));
        assertEquals(2, state.mcause);
    }

    // --- FCVT.S.W / FCVT.S.WU ---

    @Test
    void fcvtSWConvertsSignedIntExactly() {
        RV32IMAState state = runCvtSW(false, -5, RM_RNE);
        assertEquals(bits(-5.0f), (int) state.fregs[3]);
        assertEquals(0, state.fcsr);
    }

    @Test
    void fcvtSWuConvertsLargeUnsignedIntRoundingWhenInexact() {
        // UINT_MAX (4294967295) needs 32 significant bits; float has 24, so this rounds (RNE) up
        // to the nearest representable value, 2^32, and must set NX.
        RV32IMAState state = runCvtSW(true, 0xffffffff, RM_RNE);
        assertEquals(bits(4294967296.0f), (int) state.fregs[3]);
        assertEquals(FFLAGS_NX, state.fcsr);
    }

    @Test
    void fcvtSWRoundsAnInexactIntAndSetsNx() {
        int value = Integer.MAX_VALUE; // needs rounding: more than 24 significant bits
        RV32IMAState rne = runCvtSW(false, value, RM_RNE);
        assertEquals(bits((float) value), (int) rne.fregs[3]);
        assertEquals(FFLAGS_NX, rne.fcsr);
    }

    @Test
    void fcvtSWNegativeIntSameMagnitudeDifferentSignFromUnsigned() {
        RV32IMAState signed = runCvtSW(false, -1, RM_RNE);
        assertEquals(bits(-1.0f), (int) signed.fregs[3]);
        RV32IMAState unsigned = runCvtSW(true, -1, RM_RNE); // -1 as uint32 is UINT_MAX, rounds to 2^32
        assertEquals(bits(4294967296.0f), (int) unsigned.fregs[3]);
    }

    // --- Reserved / dynamic rounding mode ---

    @Test
    void reservedStaticRoundingModeTrapsIllegal() {
        RV32IMAState five = runAddSub(FUNCT7_FADD, bits(1.0f), bits(1.0f), 5);
        assertEquals(2, five.mcause);
        RV32IMAState six = runAddSub(FUNCT7_FADD, bits(1.0f), bits(1.0f), 6);
        assertEquals(2, six.mcause);
    }

    @Test
    void dynamicRoundingModeSelectingReservedFrmTrapsIllegal() {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FADD, RM_DYN, 3, 1, 2), s -> {
            setFReg(s, 1, bits(1.0f));
            setFReg(s, 2, bits(1.0f));
            s.fcsr = 5 << 5; // frm = 5 (reserved)
        });
        assertEquals(2, state.mcause);
    }

    @Test
    void dynamicRoundingModeConsultsFrm() {
        int smallestSubnormal = 0x00000001;
        int negSmallestSubnormal = smallestSubnormal | 0x80000000;
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FADD, RM_DYN, 3, 1, 2), s -> {
            setFReg(s, 1, bits(1.0f));
            setFReg(s, 2, negSmallestSubnormal);
            s.fcsr = RM_RDN << 5; // frm = round-toward-negative
        });
        assertEquals(bits(Math.nextDown(1.0f)), (int) state.fregs[3]);
    }

    @Test
    void opFpFmaFamilyOpcodesAreUnaffectedByReservedModeCheckOn5aOpcodes() {
        // 5a's FSGNJ (funct3 selects sub-opcode, not rm) must NOT be subjected to the
        // reserved-rounding-mode check; sanity that it still works with funct3 values that would
        // be reserved rounding modes if this were an arithmetic op.
        int fsgnjFunct7 = 0x10;
        RV32IMAState state = run(HAS_F, opfp(fsgnjFunct7, 0, 3, 1, 2), s -> {
            setFReg(s, 1, bits(1.0f));
            setFReg(s, 2, bits(-2.0f));
        });
        assertEquals(bits(-1.0f), (int) state.fregs[3]);
        assertEquals(0, state.mcause);
    }

    // --- Randomized differential test against BigDecimal exact arithmetic ---

    /**
     * Rounds an exact {@link BigDecimal} value to {@code float} per {@code rm}, using {@code
     * exact.floatValue()} (itself composed of two independent, JDK-provided correctly-rounded
     * narrowings: decimal-to-double, then double-to-float RNE -- safe by the same double-rounding
     * margin argument as this class's own RNE path, but computed by entirely different code) as
     * the RNE anchor, then adjusting for directed modes using exact {@code BigDecimal} comparisons
     * against the candidate's neighbors. Deliberately independent of {@link
     * RV32IMACore}'s TwoSum/fma-residual approximation.
     */
    private static float bigDecimalRoundToFloat(BigDecimal exact, int rm) {
        float rne = exact.floatValue();
        if (Float.isNaN(rne) || Float.isInfinite(rne)) {
            return rne;
        }
        BigDecimal rneExact = new BigDecimal((double) rne);
        int cmp = exact.compareTo(rneExact);
        if (cmp == 0) {
            return rne;
        }
        float lower = cmp > 0 ? rne : Math.nextDown(rne);
        float upper = cmp > 0 ? Math.nextUp(rne) : rne;
        boolean nonNegative = exact.signum() >= 0;
        return switch (rm) {
            case RM_RTZ -> nonNegative ? lower : upper;
            case RM_RDN -> lower;
            case RM_RUP -> upper;
            case RM_RMM -> {
                BigDecimal mid = new BigDecimal((double) lower)
                        .add(new BigDecimal((double) upper))
                        .divide(BigDecimal.TWO);
                yield exact.compareTo(mid) == 0 ? (nonNegative ? upper : lower) : rne;
            }
            default -> rne;
        };
    }

    private static final int[] RMS = {RM_RNE, RM_RTZ, RM_RDN, RM_RUP, RM_RMM};
    private static final MathContext MC = new MathContext(80);

    @Test
    void faddSubMatchesBigDecimalOracleAcrossRoundingModes() {
        float[][] operandPairs = {
            {1.1f, 2.2f},
            {100.25f, -37.125f},
            {0.1f, 0.2f},
            {123456.7f, 0.0009f},
            {-5.5f, 5.4999f},
            {1e10f, 1.0f},
            {7.0f, 7.0f},
            {1.0f, -1.0f + Math.ulp(1.0f)},
        };
        for (float[] pair : operandPairs) {
            for (boolean subtract : new boolean[] {false, true}) {
                BigDecimal a = new BigDecimal((double) pair[0]);
                BigDecimal b = new BigDecimal((double) pair[1]);
                BigDecimal exact = subtract ? a.subtract(b, MC) : a.add(b, MC);
                for (int rm : RMS) {
                    RV32IMAState state =
                            runAddSub(subtract ? FUNCT7_FSUB : FUNCT7_FADD, bits(pair[0]), bits(pair[1]), rm);
                    // Exact cancellation of nonzero operands has an add/sub-specific IEEE 754 sign
                    // rule (+0 except round-toward-negative) that a generic "round this exact value
                    // to float" helper has no way to know about -- apply it here instead.
                    int expectedBits = exact.signum() == 0
                            ? (rm == RM_RDN ? NEG_ZERO : 0x00000000)
                            : Float.floatToRawIntBits(bigDecimalRoundToFloat(exact, rm));
                    assertEquals(
                            expectedBits,
                            (int) state.fregs[3],
                            () -> "%s %s %s rm=%d: expected %s got %s"
                                    .formatted(
                                            pair[0],
                                            subtract ? "-" : "+",
                                            pair[1],
                                            rm,
                                            Float.intBitsToFloat(expectedBits),
                                            Float.intBitsToFloat((int) state.fregs[3])));
                }
            }
        }
    }

    @Test
    void fmulDivMatchesBigDecimalOracleAcrossRoundingModes() {
        float[][] operandPairs = {
            {1.1f, 2.2f}, {100.25f, -37.125f}, {0.1f, 0.2f}, {123456.7f, 0.0009f}, {7.0f, 3.0f}, {1e19f, 1e19f},
        };
        for (float[] pair : operandPairs) {
            BigDecimal a = new BigDecimal((double) pair[0]);
            BigDecimal b = new BigDecimal((double) pair[1]);
            BigDecimal exactMul = a.multiply(b, MC);
            for (int rm : RMS) {
                RV32IMAState state = runAddSub(FUNCT7_FMUL, bits(pair[0]), bits(pair[1]), rm);
                int expectedBits = Float.floatToRawIntBits(bigDecimalRoundToFloat(exactMul, rm));
                assertEquals(expectedBits, (int) state.fregs[3], () -> "%s * %s rm=%d".formatted(pair[0], pair[1], rm));
            }
            if (pair[1] != 0f) {
                BigDecimal exactDiv = a.divide(b, MC);
                for (int rm : RMS) {
                    RV32IMAState state = runAddSub(FUNCT7_FDIV, bits(pair[0]), bits(pair[1]), rm);
                    int expectedBits = Float.floatToRawIntBits(bigDecimalRoundToFloat(exactDiv, rm));
                    assertEquals(
                            expectedBits, (int) state.fregs[3], () -> "%s / %s rm=%d".formatted(pair[0], pair[1], rm));
                }
            }
        }
    }

    @Test
    void fsqrtMatchesBigDecimalOracleAcrossRoundingModes() {
        float[] operands = {2.0f, 3.0f, 5.0f, 10.0f, 0.5f, 123456.7f, 1e-10f, 1e20f};
        for (float a : operands) {
            BigDecimal exact = new BigDecimal((double) a).sqrt(MC);
            for (int rm : RMS) {
                RV32IMAState state = runSqrt(bits(a), rm);
                int expectedBits = Float.floatToRawIntBits(bigDecimalRoundToFloat(exact, rm));
                assertEquals(expectedBits, (int) state.fregs[3], () -> "sqrt(%s) rm=%d".formatted(a, rm));
            }
        }
    }
}
