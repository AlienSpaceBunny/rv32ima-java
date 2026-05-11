package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Compliance-style multi-vector tests for every RV32IMA instruction encoding.
 *
 * <p>Layer 1 (CoreTest) established that each mnemonic executes at all. This class adds
 * representative values and sign-boundary edge cases derived from the riscv-tests reference suite.
 * Each parameterized test method maps to one instruction mnemonic; each argument set is one test
 * vector.
 */
public class RV32IComplianceTest {
    private static final int RAM_OFFSET = 0x80000000;
    private static final int RAM_SIZE = 256;

    // --- Instruction encoding helpers ---

    private static int op(int funct7, int funct3, int rd, int rs1, int rs2) {
        return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x33;
    }

    private static int opImm(int imm12, int funct3, int rd, int rs1) {
        return ((imm12 & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x13;
    }

    private static int load(int funct3, int rd, int rs1, int imm) {
        return ((imm & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x03;
    }

    private static int store(int funct3, int rs1, int rs2, int imm) {
        return (((imm >> 5) & 0x7f) << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | ((imm & 0x1f) << 7) | 0x23;
    }

    private static int luiInstr(int rd, int imm20) {
        return ((imm20 & 0xfffff) << 12) | (rd << 7) | 0x37;
    }

    private static int auipcInstr(int rd, int imm20) {
        return ((imm20 & 0xfffff) << 12) | (rd << 7) | 0x17;
    }

    // --- Infrastructure ---

    private static RV32IMAState machineState() {
        RV32IMAState state = new RV32IMAState();
        state.pc = RAM_OFFSET;
        state.extraflags |= 3;
        return state;
    }

    /** Runs one OP-type instruction with rd=x3, rs1=x1, rs2=x2 and returns the result. */
    private static int runOp(int funct7, int funct3, int rs1Val, int rs2Val) {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = rs1Val;
            state.regs[2] = rs2Val;
            ram.writeInt(RAM_OFFSET, op(funct7, funct3, 3, 1, 2));
            new RV32IMACore().step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            return state.regs[3];
        }
    }

    /** Runs one OP-IMM instruction with rd=x3, rs1=x1 and returns the result. */
    private static int runOpImm(int funct3, int imm12, int rs1Val) {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = rs1Val;
            ram.writeInt(RAM_OFFSET, opImm(imm12, funct3, 3, 1));
            new RV32IMACore().step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            return state.regs[3];
        }
    }

    // ==========================================================================
    // RV32I — OP (register-register)
    // ==========================================================================

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void add(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(0, 0, rs1, rs2), label);
    }

    static Stream<Arguments> add() {
        return Stream.of(
                Arguments.of("MAX+1=MIN (signed overflow)", 0x7fffffff, 1, 0x80000000),
                Arguments.of("MIN+-1=MAX (signed underflow)", 0x80000000, 0xffffffff, 0x7fffffff),
                Arguments.of("MIN+MIN=0 (wraps to zero)", 0x80000000, 0x80000000, 0),
                Arguments.of("MAX+MAX=-2", 0x7fffffff, 0x7fffffff, 0xfffffffe),
                Arguments.of("-1+1=0", 0xffffffff, 1, 0),
                Arguments.of("0+0xffff8000=0xffff8000 (negative second op)", 0, 0xffff8000, 0xffff8000),
                Arguments.of("MIN+0x7fff=0x80007fff", 0x80000000, 0x7fff, 0x80007fff),
                Arguments.of("MAX+0xffff8000=0x7fff7fff", 0x7fffffff, 0xffff8000, 0x7fff7fff));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void sub(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(0x20, 0, rs1, rs2), label);
    }

    static Stream<Arguments> sub() {
        return Stream.of(
                Arguments.of("MIN-1=MAX (wraps)", 0x80000000, 1, 0x7fffffff),
                Arguments.of("0-MIN=MIN (negate MIN = MIN)", 0, 0x80000000, 0x80000000),
                Arguments.of("MAX-0xffffffff=MIN (MAX-(-1)=MIN)", 0x7fffffff, 0xffffffff, 0x80000000),
                Arguments.of("3-7=-4", 3, 7, 0xfffffffc),
                Arguments.of("0xffffffec-6=0xffffffe6 (-20-6=-26)", 0xffffffec, 6, 0xffffffe6),
                Arguments.of("0-0xffff8000=0x8000 (subtract negative)", 0, 0xffff8000, 0x8000));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void sll(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(0, 1, rs1, rs2), label);
    }

    static Stream<Arguments> sll() {
        return Stream.of(
                Arguments.of("1<<31=MIN", 1, 31, 0x80000000),
                Arguments.of("-1<<31=MIN (fills with zeros from right)", 0xffffffff, 31, 0x80000000),
                Arguments.of("-1<<1=-2", 0xffffffff, 1, 0xfffffffe),
                Arguments.of("only low 5 bits of shamt used: 1<<32=1<<0=1", 1, 32, 1),
                Arguments.of("only low 5 bits of shamt used: 1<<33=1<<1=2", 1, 33, 2),
                Arguments.of("0x01234567<<4=0x12345670", 0x01234567, 4, 0x12345670));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void slt(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(0, 2, rs1, rs2), label);
    }

    static Stream<Arguments> slt() {
        return Stream.of(
                Arguments.of("MIN<0 → 1", 0x80000000, 0, 1),
                Arguments.of("0>MIN → 0", 0, 0x80000000, 0),
                Arguments.of("-1<0 → 1", 0xffffffff, 0, 1),
                Arguments.of("0<MAX → 1", 0, 0x7fffffff, 1),
                Arguments.of("MAX>0 → 0", 0x7fffffff, 0, 0),
                Arguments.of("x==x → 0", 0x12345678, 0x12345678, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void sltu(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(0, 3, rs1, rs2), label);
    }

    static Stream<Arguments> sltu() {
        return Stream.of(
                Arguments.of("0 <_u MAX_UINT → 1", 0, 0xffffffff, 1),
                Arguments.of("MAX_UINT >_u 0 → 0", 0xffffffff, 0, 0),
                Arguments.of("0x80000000 <_u 0xffffffff → 1", 0x80000000, 0xffffffff, 1),
                Arguments.of("0xffffffff >_u 0x80000000 → 0", 0xffffffff, 0x80000000, 0),
                Arguments.of("0x7fffffff <_u 0x80000000 → 1 (sign bit matters)", 0x7fffffff, 0x80000000, 1),
                Arguments.of("x==x → 0", 0xdeadbeef, 0xdeadbeef, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void xor(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(0, 4, rs1, rs2), label);
    }

    static Stream<Arguments> xor() {
        return Stream.of(
                Arguments.of("x^x=0", 0xdeadbeef, 0xdeadbeef, 0),
                Arguments.of("x^0=x", 0xdeadbeef, 0, 0xdeadbeef),
                Arguments.of("x^0xffffffff=~x", 0xaaaaaaaa, 0xffffffff, 0x55555555),
                Arguments.of("bitpattern: 0xff00ff00^0x0f0f0f0f=0xf00ff00f", 0xff00ff00, 0x0f0f0f0f, 0xf00ff00f),
                Arguments.of("bitpattern: 0x0ff00ff0^0xf0f0f0f0=0xff00ff00", 0x0ff00ff0, 0xf0f0f0f0, 0xff00ff00));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void srl(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(0, 5, rs1, rs2), label);
    }

    static Stream<Arguments> srl() {
        return Stream.of(
                Arguments.of("MIN>>1=0x40000000 (no sign extension)", 0x80000000, 1, 0x40000000),
                Arguments.of("-1>>1=0x7fffffff (no sign extension)", 0xffffffff, 1, 0x7fffffff),
                Arguments.of("-1>>31=1", 0xffffffff, 31, 1),
                Arguments.of("only low 5 bits used: 0x21212121>>32=>>0 (no shift)", 0x21212121, 32, 0x21212121),
                Arguments.of("only low 5 bits used: 0x21212121>>33=>>1=0x10909090", 0x21212121, 33, 0x10909090),
                Arguments.of("only low 5 bits used: 0x21212121>>63=>>31=0", 0x21212121, 63, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void sra(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(0x20, 5, rs1, rs2), label);
    }

    static Stream<Arguments> sra() {
        return Stream.of(
                Arguments.of("MIN>>0=MIN (no shift)", 0x80000000, 0, 0x80000000),
                Arguments.of("MIN>>1=0xC0000000 (sign bit replicated)", 0x80000000, 1, 0xc0000000),
                Arguments.of("MIN>>7=0xFF000000", 0x80000000, 7, 0xff000000),
                Arguments.of("MIN>>31=0xFFFFFFFF (all sign bits)", 0x80000000, 31, 0xffffffff),
                Arguments.of("-1>>15=-1 (arithmetic shift of -1 is always -1)", 0xffffffff, 15, 0xffffffff),
                Arguments.of("MAX>>31=0 (positive, sign bit is 0)", 0x7fffffff, 31, 0),
                Arguments.of("only low 5 bits used: MIN>>32=MIN>>0=MIN", 0x80000000, 32, 0x80000000));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void or(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(0, 6, rs1, rs2), label);
    }

    static Stream<Arguments> or() {
        return Stream.of(
                Arguments.of("x|0=x", 0xdeadbeef, 0, 0xdeadbeef),
                Arguments.of("x|0xffffffff=0xffffffff", 0x12345678, 0xffffffff, 0xffffffff),
                Arguments.of("x|x=x", 0xaaaaaaaa, 0xaaaaaaaa, 0xaaaaaaaa),
                Arguments.of("bitpattern: 0xff00ff00|0x0f0f0f0f=0xff0fff0f", 0xff00ff00, 0x0f0f0f0f, 0xff0fff0f),
                Arguments.of("bitpattern: 0x00ff00ff|0xf0f0f0f0=0xf0fff0ff", 0x00ff00ff, 0xf0f0f0f0, 0xf0fff0ff));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void and(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(0, 7, rs1, rs2), label);
    }

    static Stream<Arguments> and() {
        return Stream.of(
                Arguments.of("x&0=0", 0xdeadbeef, 0, 0),
                Arguments.of("x&0xffffffff=x", 0x12345678, 0xffffffff, 0x12345678),
                Arguments.of("x&x=x", 0x55555555, 0x55555555, 0x55555555),
                Arguments.of("bitpattern: 0xff00ff00&0x0f0f0f0f=0x0f000f00", 0xff00ff00, 0x0f0f0f0f, 0x0f000f00),
                Arguments.of("bitpattern: 0x00ff00ff&0xf0f0f0f0=0x00f000f0", 0x00ff00ff, 0xf0f0f0f0, 0x00f000f0));
    }

    // ==========================================================================
    // RV32I — OP-IMM (register-immediate)
    // ==========================================================================

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void addi(String label, int imm12, int rs1, int expected) {
        assertEquals(expected, runOpImm(0, imm12, rs1), label);
    }

    static Stream<Arguments> addi() {
        return Stream.of(
                Arguments.of("0+0x7ff=0x7ff (max positive imm)", 0x7ff, 0, 0x7ff),
                Arguments.of("0+0x800=0xfffff800 (max negative imm sign-extends)", 0x800, 0, 0xfffff800),
                Arguments.of("0+0xfff=-1 (0xfff sign-extends to -1)", 0xfff, 0, 0xffffffff),
                Arguments.of("MAX+1=MIN (signed overflow)", 1, 0x7fffffff, 0x80000000),
                Arguments.of("MIN+0xfff=MAX (MIN + -1 = MAX)", 0xfff, 0x80000000, 0x7fffffff),
                Arguments.of("0x80000000+0x800=0x7ffff800", 0x800, 0x80000000, 0x7ffff800));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void slti(String label, int imm12, int rs1, int expected) {
        assertEquals(expected, runOpImm(2, imm12, rs1), label);
    }

    static Stream<Arguments> slti() {
        return Stream.of(
                Arguments.of("MIN<0 → 1", 0x000, 0x80000000, 1),
                Arguments.of("0<2047 (max pos imm) → 1", 0x7ff, 0, 1),
                Arguments.of("MAX>0 → 0", 0x000, 0x7fffffff, 0),
                Arguments.of("-1<0 → 1", 0x000, 0xffffffff, 1),
                Arguments.of("0 vs -2048 (imm=0x800): 0>-2048 → 0", 0x800, 0, 0),
                Arguments.of("MIN vs -2048 (imm=0x800): MIN<-2048 → 1", 0x800, 0x80000000, 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void sltiu(String label, int imm12, int rs1, int expected) {
        assertEquals(expected, runOpImm(3, imm12, rs1), label);
    }

    static Stream<Arguments> sltiu() {
        return Stream.of(
                Arguments.of("3 <_u 7 → 1", 0x007, 3, 1),
                Arguments.of("7 >_u 3 → 0", 0x003, 7, 0),
                Arguments.of("0 <_u 0xFFFFF800 (imm=0x800 sign-ext) → 1", 0x800, 0, 1),
                Arguments.of("MAX_UINT >_u 0xFFFFF800 → 0", 0x800, 0xffffffff, 0),
                Arguments.of("1 <_u MAX_UINT (imm=0xfff sign-ext) → 1", 0xfff, 1, 1),
                Arguments.of("0x80000000 >_u 2047 → 0", 0x7ff, 0x80000000, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void xori(String label, int imm12, int rs1, int expected) {
        assertEquals(expected, runOpImm(4, imm12, rs1), label);
    }

    static Stream<Arguments> xori() {
        return Stream.of(
                Arguments.of("x^(-1)=~x (imm=0xfff sign-extends to -1)", 0xfff, 0xaaaaaaaa, 0x55555555),
                Arguments.of("x^0=x", 0x000, 0xdeadbeef, 0xdeadbeef),
                Arguments.of("0^(-1)=-1", 0xfff, 0, 0xffffffff),
                Arguments.of("0xff00ff00^0x00f=0xff00ff0f", 0x00f, 0xff00ff00, 0xff00ff0f),
                Arguments.of("0xff00ff0f^0xFFFFFF00(imm=0xf00)=0x00ff000f", 0xf00, 0xff00ff0f, 0x00ff000f));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void ori(String label, int imm12, int rs1, int expected) {
        assertEquals(expected, runOpImm(6, imm12, rs1), label);
    }

    static Stream<Arguments> ori() {
        return Stream.of(
                Arguments.of("x|(-1)=-1 (imm=0xfff)", 0xfff, 0xdeadbeef, 0xffffffff),
                Arguments.of("x|0=x", 0x000, 0xdeadbeef, 0xdeadbeef),
                Arguments.of("0|2047=2047", 0x7ff, 0, 0x7ff),
                Arguments.of("0xff00ff00|0xf0=0xff00fff0", 0x0f0, 0xff00ff00, 0xff00fff0),
                Arguments.of("0|(-1)=-1", 0xfff, 0, 0xffffffff));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void andi(String label, int imm12, int rs1, int expected) {
        assertEquals(expected, runOpImm(7, imm12, rs1), label);
    }

    static Stream<Arguments> andi() {
        return Stream.of(
                Arguments.of("x&0=0", 0x000, 0xdeadbeef, 0),
                Arguments.of("x&(-1)=x (imm=0xfff)", 0xfff, 0x12345678, 0x12345678),
                Arguments.of("0xffffffff&0x7ff=0x7ff", 0x7ff, 0xffffffff, 0x7ff),
                Arguments.of("0xf0f0f0f0&0x0f0=0x0f0 (low bits only)", 0x0f0, 0xf0f0f0f0, 0x0f0),
                Arguments.of("0xff00ff0f&0xFFFFFF00(imm=0xf00): clears low byte", 0xf00, 0xff00ff0f, 0xff00ff00));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void slli(String label, int shamt, int rs1, int expected) {
        assertEquals(expected, runOpImm(1, shamt, rs1), label);
    }

    static Stream<Arguments> slli() {
        return Stream.of(
                Arguments.of("1<<31=MIN", 31, 1, 0x80000000),
                Arguments.of("-1<<1=-2", 1, 0xffffffff, 0xfffffffe),
                Arguments.of("-1<<31=MIN (low bit survives, rest shift out)", 31, 0xffffffff, 0x80000000),
                Arguments.of("0x01234567<<4=0x12345670", 4, 0x01234567, 0x12345670),
                Arguments.of("MIN<<1=0 (high bit shifts out)", 1, 0x80000000, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void srli(String label, int shamt, int rs1, int expected) {
        assertEquals(expected, runOpImm(5, shamt, rs1), label);
    }

    static Stream<Arguments> srli() {
        return Stream.of(
                Arguments.of("MIN>>1=0x40000000 (no sign fill)", 1, 0x80000000, 0x40000000),
                Arguments.of("-1>>1=0x7fffffff (no sign fill)", 1, 0xffffffff, 0x7fffffff),
                Arguments.of("-1>>31=1", 31, 0xffffffff, 1),
                Arguments.of("0x21212121>>7=0x00424242", 7, 0x21212121, 0x00424242),
                Arguments.of("0x21212121>>14=0x00008484", 14, 0x21212121, 0x00008484),
                Arguments.of("0x21212121>>31=0 (positive value, bit 31 is 0)", 31, 0x21212121, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void srai(String label, int shamt, int rs1, int expected) {
        assertEquals(expected, runOpImm(5, 0x400 | shamt, rs1), label);
    }

    static Stream<Arguments> srai() {
        return Stream.of(
                Arguments.of("MIN>>1=0xC0000000 (sign bit replicated)", 1, 0x80000000, 0xc0000000),
                Arguments.of("MIN>>7=0xFF000000", 7, 0x80000000, 0xff000000),
                Arguments.of("MIN>>31=0xFFFFFFFF (all sign bits)", 31, 0x80000000, 0xffffffff),
                Arguments.of("-1>>15=-1 (sign fill of -1 is always -1)", 15, 0xffffffff, 0xffffffff),
                Arguments.of("MAX>>1=0x3FFFFFFF (positive, no sign fill)", 1, 0x7fffffff, 0x3fffffff),
                Arguments.of("MAX>>31=0 (positive, sign bit is 0)", 31, 0x7fffffff, 0));
    }

    // ==========================================================================
    // RV32M — Multiply and Divide
    // ==========================================================================

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void mul(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(1, 0, rs1, rs2), label);
    }

    static Stream<Arguments> mul() {
        return Stream.of(
                Arguments.of("2*MAX overflows: low 32 bits only", 2, 0x7fffffff, 0xfffffffe),
                Arguments.of("-1*-1=1 (low 32 bits of 1)", 0xffffffff, 0xffffffff, 1),
                Arguments.of("-1*1=-1", 0xffffffff, 1, 0xffffffff),
                Arguments.of("MIN*MIN=0 (2^62 low 32 bits = 0)", 0x80000000, 0x80000000, 0),
                Arguments.of("MAX*MAX low=1 (2^62-2^32+1 low 32 bits)", 0x7fffffff, 0x7fffffff, 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void mulh(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(1, 1, rs1, rs2), label);
    }

    static Stream<Arguments> mulh() {
        return Stream.of(
                Arguments.of("MAX*MAX upper=0x3FFFFFFF", 0x7fffffff, 0x7fffffff, 0x3fffffff),
                Arguments.of("MIN*MIN upper=0x40000000", 0x80000000, 0x80000000, 0x40000000),
                Arguments.of("-1*-1 upper=0 (product=1 fits in low 32)", 0xffffffff, 0xffffffff, 0),
                Arguments.of("-1*1 upper=-1 (product=-1 sign-fills high bits)", 0xffffffff, 1, 0xffffffff),
                Arguments.of("MIN*-1 upper=0 (product=2^31 fits in low 32 unsigned)", 0x80000000, 0xffffffff, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void mulhsu(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(1, 2, rs1, rs2), label);
    }

    static Stream<Arguments> mulhsu() {
        return Stream.of(
                Arguments.of(
                        "-1(signed) * MAX_UINT(unsigned) upper=-1 (discriminates from MULH)",
                        0xffffffff,
                        0xffffffff,
                        0xffffffff),
                Arguments.of("1*1 upper=0", 1, 1, 0),
                Arguments.of("-1(signed)*1(unsigned) upper=-1", 0xffffffff, 1, 0xffffffff),
                Arguments.of("MIN * 2 upper=-1 (product=-2^32)", 0x80000000, 2, 0xffffffff),
                Arguments.of("0 * anything upper=0", 0, 0xdeadbeef, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void mulhu(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(1, 3, rs1, rs2), label);
    }

    static Stream<Arguments> mulhu() {
        return Stream.of(
                Arguments.of(
                        "MAX_UINT*MAX_UINT upper=0xFFFFFFFE ((2^32-1)^2 upper 32)", 0xffffffff, 0xffffffff, 0xfffffffe),
                Arguments.of("MAX_UINT*2 upper=1 (2*(2^32-1) = 2^33-2; upper=1)", 0xffffffff, 2, 1),
                Arguments.of(
                        "0x80000000*0x80000000 upper=0x40000000 (2^62 upper 32)", 0x80000000, 0x80000000, 0x40000000),
                Arguments.of("1*1 upper=0", 1, 1, 0),
                Arguments.of("MAX_UINT*1 upper=0 (product fits in 32 bits)", 0xffffffff, 1, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void div(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(1, 4, rs1, rs2), label);
    }

    static Stream<Arguments> div() {
        return Stream.of(
                Arguments.of("20/6=3", 20, 6, 3),
                Arguments.of("-20/6=-3 (truncates toward zero)", 0xffffffec, 6, 0xfffffffd),
                Arguments.of("20/-6=-3", 20, 0xfffffffa, 0xfffffffd),
                Arguments.of("-20/-6=3", 0xffffffec, 0xfffffffa, 3),
                Arguments.of("MIN/2=-0x40000000", 0x80000000, 2, 0xc0000000));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void divu(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(1, 5, rs1, rs2), label);
    }

    static Stream<Arguments> divu() {
        return Stream.of(
                Arguments.of("20/6=3", 20, 6, 3),
                Arguments.of("MAX_UINT/1=MAX_UINT", 0xffffffff, 1, 0xffffffff),
                Arguments.of("MAX_UINT/MAX_UINT=1", 0xffffffff, 0xffffffff, 1),
                Arguments.of("0x80000000/1=0x80000000 (MIN as unsigned)", 0x80000000, 1, 0x80000000),
                Arguments.of("1/MAX_UINT=0 (smaller/bigger)", 1, 0xffffffff, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void rem(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(1, 6, rs1, rs2), label);
    }

    static Stream<Arguments> rem() {
        return Stream.of(
                Arguments.of("20%6=2", 20, 6, 2),
                Arguments.of("-20%6=-2 (sign follows dividend)", 0xffffffec, 6, 0xfffffffe),
                Arguments.of("20%-6=2 (sign follows dividend)", 20, 0xfffffffa, 2),
                Arguments.of("-20%-6=-2", 0xffffffec, 0xfffffffa, 0xfffffffe),
                Arguments.of("7%3=1", 7, 3, 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void remu(String label, int rs1, int rs2, int expected) {
        assertEquals(expected, runOp(1, 7, rs1, rs2), label);
    }

    static Stream<Arguments> remu() {
        return Stream.of(
                Arguments.of("20%6=2", 20, 6, 2),
                Arguments.of("MAX_UINT%MAX_UINT=0", 0xffffffff, 0xffffffff, 0),
                Arguments.of("MAX_UINT%1=0", 0xffffffff, 1, 0),
                Arguments.of("5%0x80000001=5 (dividend < divisor)", 5, 0x80000001, 5),
                Arguments.of("0x80000000%3=2 (unsigned dividend)", 0x80000000, 3, 2));
    }

    // ==========================================================================
    // RV32I — Upper-immediate and memory (flat tests for non-ALU instructions)
    // ==========================================================================

    @Test
    public void luiSetsUpperBitsToImmediate() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeInt(RAM_OFFSET, luiInstr(1, 0x12345));
            new RV32IMACore().step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(0x12345000, state.regs[1]);
        }
    }

    @Test
    public void luiWithSignBitInImm20() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeInt(RAM_OFFSET, luiInstr(1, 0x80000));
            new RV32IMACore().step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(0x80000000, state.regs[1]);
        }
    }

    @Test
    public void auipcAddsImmediateToPC() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeInt(RAM_OFFSET, auipcInstr(1, 1));
            new RV32IMACore().step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 0x1000, state.regs[1]);
        }
    }

    @Test
    public void auipcWithZeroImmEqualsPC() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeInt(RAM_OFFSET, auipcInstr(1, 0));
            new RV32IMACore().step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(RAM_OFFSET, state.regs[1]);
        }
    }

    @Test
    public void lbMaxPositiveByte() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeByte(RAM_OFFSET + 8, (byte) 0x7f);
            state.regs[1] = RAM_OFFSET;
            ram.writeInt(RAM_OFFSET, load(0, 2, 1, 8)); // LB x2, 8(x1)
            new RV32IMACore().step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(127, state.regs[2]);
        }
    }

    @Test
    public void lhMaxPositiveHalfword() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeShort(RAM_OFFSET + 8, (short) 0x7fff);
            state.regs[1] = RAM_OFFSET;
            ram.writeInt(RAM_OFFSET, load(1, 2, 1, 8)); // LH x2, 8(x1)
            new RV32IMACore().step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(0x7fff, state.regs[2]);
        }
    }

    @Test
    public void lbWithNonZeroOffset() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeByte(RAM_OFFSET + 12, (byte) 0xab);
            state.regs[1] = RAM_OFFSET + 8;
            ram.writeInt(RAM_OFFSET, load(0, 2, 1, 4)); // LB x2, 4(x1) → addr RAM_OFFSET+12
            new RV32IMACore().step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals((byte) 0xab, (byte) state.regs[2]);
        }
    }

    @Test
    public void sbWritesOnlyTargetByteAtOffset() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeInt(RAM_OFFSET + 8, 0xdeadbeef);
            state.regs[1] = RAM_OFFSET;
            state.regs[2] = 0x42;
            ram.writeInt(RAM_OFFSET, store(0, 1, 2, 9)); // SB x2, 9(x1) → writes byte at +9
            new RV32IMACore().step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            // Byte at offset 9 (little-endian: second byte of the word at +8) should be 0x42
            assertEquals(0x42, Byte.toUnsignedInt(ram.readByte(RAM_OFFSET + 9)));
            // Adjacent bytes should be unchanged
            assertEquals(0xef, Byte.toUnsignedInt(ram.readByte(RAM_OFFSET + 8)));
            assertEquals(0xad, Byte.toUnsignedInt(ram.readByte(RAM_OFFSET + 10)));
        }
    }
}
