package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Tests for the RV32F Phase 5a subset: register file / {@code fcsr} plumbing, {@code FLW}/{@code
 * FSW}, moves, sign injection, classification, comparisons, and min/max. None of these
 * instructions consult the rounding mode; the rounding-mode-dependent instructions
 * (FADD/FSUB/FMUL/FDIV/FSQRT.S, the FMADD family, FCVT.{W,WU}.S/FCVT.S.{W,WU}) are Phase 5b.
 */
public class FExtensionTest {
    private static final int RAM_OFFSET = 0x80000000;
    private static final int RAM_SIZE = 256;
    private static final int DATA_OFFSET = 64;
    private static final IsaConfig HAS_F = new IsaConfig(false, true, false, false, false);
    private static final IsaConfig NO_F = IsaConfig.RV32IMA_ZICSR;

    private static final int FUNCT7_FSGNJ = 0x10;
    private static final int FUNCT7_FMIN_FMAX = 0x14;
    private static final int FUNCT7_FCOMPARE = 0x50;
    private static final int FUNCT7_FMV_X_W_FCLASS = 0x70;
    private static final int FUNCT7_FMV_W_X = 0x78;

    private static final int POS_ZERO = 0x00000000;
    private static final int NEG_ZERO = 0x80000000;
    private static final int POS_SUBNORMAL = 0x00000001;
    private static final int NEG_SUBNORMAL = 0x80000001;
    private static final int POS_INFINITY = 0x7f800000;
    private static final int NEG_INFINITY = 0xff800000;
    private static final int SIGNALING_NAN = 0x7f800001;
    private static final int QUIET_NAN = 0x7fc00000;
    private static final int ONE = Float.floatToRawIntBits(1.0f);
    private static final int NEG_ONE = Float.floatToRawIntBits(-1.0f);
    private static final int TWO = Float.floatToRawIntBits(2.0f);

    private static int flw(int rd, int rs1, int imm12) {
        return ((imm12 & 0xfff) << 20) | (rs1 << 15) | (2 << 12) | (rd << 7) | 0x07;
    }

    private static int fsw(int rs1, int rs2, int imm12) {
        return (((imm12 >> 5) & 0x7f) << 25) | (rs2 << 20) | (rs1 << 15) | (2 << 12) | ((imm12 & 0x1f) << 7) | 0x27;
    }

    private static int opfp(int funct7, int funct3, int rd, int rs1, int rs2) {
        return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x53;
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

    // --- FLW / FSW ---

    @Test
    void flwLoadsWordAndNaNBoxesTheFRegister() {
        int bits = Float.floatToRawIntBits(3.5f);
        RV32IMAState state;
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            state = machineState();
            state.regs[1] = RAM_OFFSET;
            ram.writeInt(RAM_OFFSET, flw(5, 1, DATA_OFFSET));
            ram.writeInt(RAM_OFFSET + DATA_OFFSET, bits);
            new RV32IMACore(HAS_F).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
        }
        assertEquals(0xFFFFFFFF00000000L | (bits & 0xFFFFFFFFL), state.fregs[5]);
    }

    @Test
    void fswStoresLowThirtyTwoBitsOfFRegister() {
        int bits = Float.floatToRawIntBits(-1.25f);
        int readBack;
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = RAM_OFFSET;
            setFReg(state, 5, bits);
            ram.writeInt(RAM_OFFSET, fsw(1, 5, DATA_OFFSET));
            new RV32IMACore(HAS_F).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            readBack = ram.readInt(RAM_OFFSET + DATA_OFFSET);
        }
        assertEquals(bits, readBack);
    }

    @Test
    void fswInvalidatesLrScReservationLikeAnyOtherStore() {
        RV32IMAState state = run(HAS_F, fsw(1, 5, DATA_OFFSET), s -> {
            s.regs[1] = RAM_OFFSET;
            setFReg(s, 5, ONE);
            s.reservationValid = true;
        });
        assertEquals(false, state.reservationValid);
    }

    @Test
    void flwTrapsIllegalWithoutHasF() {
        RV32IMAState state = run(NO_F, flw(5, 1, 0), s -> s.regs[1] = RAM_OFFSET);
        assertEquals(2, state.mcause);
    }

    @Test
    void fswTrapsIllegalWithoutHasF() {
        RV32IMAState state = run(NO_F, fsw(1, 5, 0), s -> s.regs[1] = RAM_OFFSET);
        assertEquals(2, state.mcause);
    }

    // --- FMV.X.W / FMV.W.X ---

    @Test
    void fmvXWMovesRawBitsToIntegerRegister() {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FMV_X_W_FCLASS, 0, 3, 1, 0), s -> setFReg(s, 1, NEG_ONE));
        assertEquals(NEG_ONE, state.regs[3]);
    }

    @Test
    void fmvWXMovesRawBitsToFRegisterAndNaNBoxes() {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FMV_W_X, 0, 5, 1, 0), s -> s.regs[1] = NEG_ONE);
        assertEquals(0xFFFFFFFF00000000L | (NEG_ONE & 0xFFFFFFFFL), state.fregs[5]);
    }

    // --- FSGNJ.S / FSGNJN.S / FSGNJX.S ---

    @Test
    void fsgnjTakesMagnitudeFromRs1AndSignFromRs2() {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FSGNJ, 0, 3, 1, 2), s -> {
            setFReg(s, 1, ONE); // magnitude source: +1.0
            setFReg(s, 2, NEG_ONE); // sign source: negative
        });
        assertEquals(NEG_ONE, (int) state.fregs[3]);
    }

    @Test
    void fsgnjnTakesMagnitudeFromRs1AndInvertedSignFromRs2() {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FSGNJ, 1, 3, 1, 2), s -> {
            setFReg(s, 1, ONE);
            setFReg(s, 2, ONE); // positive sign, inverted -> negative
        });
        assertEquals(NEG_ONE, (int) state.fregs[3]);
    }

    @Test
    void fsgnjnWithNegativeRs2InvertsToPositive() {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FSGNJ, 1, 3, 1, 2), s -> {
            setFReg(s, 1, NEG_ONE); // magnitude source: 1.0 (sign discarded)
            setFReg(s, 2, NEG_ONE); // negative sign, inverted -> positive
        });
        assertEquals(ONE, (int) state.fregs[3]);
    }

    @Test
    void fsgnjxXorsSignsOfRs1AndRs2() {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FSGNJ, 2, 3, 1, 2), s -> {
            setFReg(s, 1, NEG_ONE); // negative
            setFReg(s, 2, NEG_ONE); // negative ^ negative -> positive
        });
        assertEquals(ONE, (int) state.fregs[3]);
    }

    @Test
    void fpDestinationDoesNotClobberIntegerRegisterAtSameIndex() {
        // FSGNJ.S rd=3 writes fregs[3]; the integer register x3 shares the same 5-bit rd field
        // but must be left completely alone (this is what the "fRd = rdid; rdid = 0;" dance in
        // the OP-FP decode exists to guarantee).
        int sentinel = 0x12345678;
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FSGNJ, 0, 3, 1, 2), s -> {
            s.regs[3] = sentinel;
            setFReg(s, 1, ONE);
            setFReg(s, 2, NEG_ONE);
        });
        assertEquals(sentinel, state.regs[3]);
        assertEquals(NEG_ONE, (int) state.fregs[3]);
    }

    // --- FCLASS.S ---

    @Test
    void fclassNegativeInfinity() {
        assertEquals(1 << 0, fclassOf(NEG_INFINITY));
    }

    @Test
    void fclassNegativeNormal() {
        assertEquals(1 << 1, fclassOf(NEG_ONE));
    }

    @Test
    void fclassNegativeSubnormal() {
        assertEquals(1 << 2, fclassOf(NEG_SUBNORMAL));
    }

    @Test
    void fclassNegativeZero() {
        assertEquals(1 << 3, fclassOf(NEG_ZERO));
    }

    @Test
    void fclassPositiveZero() {
        assertEquals(1 << 4, fclassOf(POS_ZERO));
    }

    @Test
    void fclassPositiveSubnormal() {
        assertEquals(1 << 5, fclassOf(POS_SUBNORMAL));
    }

    @Test
    void fclassPositiveNormal() {
        assertEquals(1 << 6, fclassOf(ONE));
    }

    @Test
    void fclassPositiveInfinity() {
        assertEquals(1 << 7, fclassOf(POS_INFINITY));
    }

    @Test
    void fclassSignalingNaN() {
        assertEquals(1 << 8, fclassOf(SIGNALING_NAN));
    }

    @Test
    void fclassQuietNaN() {
        assertEquals(1 << 9, fclassOf(QUIET_NAN));
    }

    private static int fclassOf(int bits) {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FMV_X_W_FCLASS, 1, 3, 1, 0), s -> setFReg(s, 1, bits));
        return state.regs[3];
    }

    // --- FEQ.S / FLT.S / FLE.S ---

    @Test
    void feqTrueForEqualValues() {
        assertEquals(1, compare(2, ONE, ONE));
    }

    @Test
    void feqTrueForPositiveAndNegativeZero() {
        assertEquals(1, compare(2, POS_ZERO, NEG_ZERO));
    }

    @Test
    void fltTrueWhenLess() {
        assertEquals(1, compare(1, ONE, TWO));
    }

    @Test
    void fltFalseWhenGreater() {
        assertEquals(0, compare(1, TWO, ONE));
    }

    @Test
    void fleTrueWhenEqual() {
        assertEquals(1, compare(0, ONE, ONE));
    }

    @Test
    void feqWithQuietNaNIsFalseWithoutSettingInvalid() {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FCOMPARE, 2, 3, 1, 2), s -> {
            setFReg(s, 1, QUIET_NAN);
            setFReg(s, 2, ONE);
        });
        assertEquals(0, state.regs[3]);
        assertEquals(0, state.fcsr & 0x1f);
    }

    @Test
    void feqWithSignalingNaNSetsInvalidFlag() {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FCOMPARE, 2, 3, 1, 2), s -> {
            setFReg(s, 1, SIGNALING_NAN);
            setFReg(s, 2, ONE);
        });
        assertEquals(0, state.regs[3]);
        assertEquals(0x10, state.fcsr & 0x1f);
    }

    @Test
    void fltWithQuietNaNSetsInvalidFlag() {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FCOMPARE, 1, 3, 1, 2), s -> {
            setFReg(s, 1, QUIET_NAN);
            setFReg(s, 2, ONE);
        });
        assertEquals(0, state.regs[3]);
        assertEquals(0x10, state.fcsr & 0x1f);
    }

    private static int compare(int funct3, int aBits, int bBits) {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FCOMPARE, funct3, 3, 1, 2), s -> {
            setFReg(s, 1, aBits);
            setFReg(s, 2, bBits);
        });
        return state.regs[3];
    }

    // --- FMIN.S / FMAX.S ---

    @Test
    void fminReturnsSmallerOperand() {
        assertEquals(ONE, minMax(0, ONE, TWO));
    }

    @Test
    void fmaxReturnsLargerOperand() {
        assertEquals(TWO, minMax(1, ONE, TWO));
    }

    @Test
    void fminOfPositiveAndNegativeZeroIsNegativeZero() {
        assertEquals(NEG_ZERO, minMax(0, POS_ZERO, NEG_ZERO));
        assertEquals(NEG_ZERO, minMax(0, NEG_ZERO, POS_ZERO));
    }

    @Test
    void fmaxOfPositiveAndNegativeZeroIsPositiveZero() {
        assertEquals(POS_ZERO, minMax(1, POS_ZERO, NEG_ZERO));
        assertEquals(POS_ZERO, minMax(1, NEG_ZERO, POS_ZERO));
    }

    @Test
    void fminWithOneQuietNaNReturnsTheNonNaNOperand() {
        assertEquals(ONE, minMax(0, QUIET_NAN, ONE));
        assertEquals(ONE, minMax(0, ONE, QUIET_NAN));
    }

    @Test
    void fminWithBothOperandsNaNReturnsCanonicalNaN() {
        assertEquals(0x7fc00000, minMax(0, QUIET_NAN, SIGNALING_NAN));
    }

    @Test
    void fminWithSignalingNaNSetsInvalidFlag() {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FMIN_FMAX, 0, 3, 1, 2), s -> {
            setFReg(s, 1, SIGNALING_NAN);
            setFReg(s, 2, ONE);
        });
        assertEquals(0x10, state.fcsr & 0x1f);
    }

    private static int minMax(int funct3, int aBits, int bBits) {
        RV32IMAState state = run(HAS_F, opfp(FUNCT7_FMIN_FMAX, funct3, 3, 1, 2), s -> {
            setFReg(s, 1, aBits);
            setFReg(s, 2, bBits);
        });
        return (int) state.fregs[3];
    }

    // --- Gating ---

    @Test
    void opFpTrapsIllegalWithoutHasF() {
        RV32IMAState state = run(NO_F, opfp(FUNCT7_FSGNJ, 0, 3, 1, 2), s -> setFReg(s, 1, ONE));
        assertEquals(2, state.mcause);
    }

    // --- fcsr / fflags / frm CSRs ---

    private static int csrrw(int csrno, int rd, int rs1) {
        return (csrno << 20) | (rs1 << 15) | (1 << 12) | (rd << 7) | 0x73;
    }

    private static int csrrs(int csrno, int rd, int rs1) {
        return (csrno << 20) | (rs1 << 15) | (2 << 12) | (rd << 7) | 0x73;
    }

    @Test
    void fflagsCsrReadsAndWritesLowFiveBitsOfFcsr() {
        RV32IMAState state = run(HAS_F, csrrw(0x001, 0, 1), s -> {
            s.regs[1] = 0x1f;
            s.fcsr = 0xe0; // frm bits pre-set, must survive the fflags-only write
        });
        assertEquals(0xff, state.fcsr);
    }

    @Test
    void frmCsrReadsAndWritesBitsSevenToFive() {
        RV32IMAState state = run(HAS_F, csrrw(0x002, 0, 1), s -> {
            s.regs[1] = 0x7;
            s.fcsr = 0x1f; // fflags bits pre-set, must survive the frm-only write
        });
        assertEquals(0xff, state.fcsr);
    }

    @Test
    void fcsrCsrReadsAndWritesAllEightBits() {
        RV32IMAState state = run(HAS_F, csrrs(0x003, 3, 0), s -> s.fcsr = 0xab);
        assertEquals(0xab, state.regs[3]);
    }

    @Test
    void fflagsCsrIsUnhandledWithoutHasF() {
        // No hasF-specific existence check exists (or is needed): fflags/frm/fcsr just fall
        // through to the same csrHook-or-noop default every other unimplemented CSR number gets.
        RV32IMAState state = run(NO_F, csrrw(0x001, 0, 1), s -> {
            s.regs[1] = 0x1f;
            s.fcsr = 0;
        });
        assertEquals(0, state.mcause);
        assertEquals(0, state.fcsr);
    }
}
