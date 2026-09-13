package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Verifies RV32C (compressed instruction) decode (Phase 4).
 *
 * <p>Most cases here are <em>differential</em>: the compressed encoding and a hand-assembled
 * 32-bit equivalent are each executed from a fresh, identical machine state, and the resulting
 * register files are asserted equal. This deliberately avoids re-deriving expected register
 * values from the same bit-shuffle formulas {@code RV32IMACore.decodeCompressed} itself uses (its
 * formulas come from the reference simulator's decoder, cross-checked against riscv-opcodes, but
 * an error shared between production code and a same-formula test would still pass) -- the 32-bit
 * side of each comparison is already covered extensively by {@code RV32IComplianceTest}/{@code
 * CoreTest}, so agreement with it is meaningful independent evidence. The compressed-instruction
 * encoders below are written directly from the encoding tables, not by copying {@code
 * RV32IMACore}'s private decode helpers.
 *
 * <p>Reserved/illegal 16-bit patterns and the two places {@code instrLen} threading is externally
 * observable (a compressed jump's link value, and instruction fetch at the RAM window's edge) get
 * their own directly-computed vectors instead.
 */
public class CompressedInstructionTest {
    private static final int RAM_OFFSET = 0x80000000;
    private static final int RAM_SIZE = 256;
    private static final IsaConfig HAS_C = new IsaConfig(true, false, false, false, false);
    private static final IsaConfig HAS_C_F = new IsaConfig(true, true, false, false, false);
    private static final IsaConfig HAS_F = new IsaConfig(false, true, false, false, false);

    // ---- 16-bit compressed instruction encoders (independent of RV32IMACore's internals) ----

    private static int cAddi4spn(int rdP, int nzuimm) {
        int c = ((nzuimm >>> 6) & 0xf) << 7; // nzuimm[9:6] -> c[10:7]
        c |= ((nzuimm >>> 4) & 0x3) << 11; // nzuimm[5:4] -> c[12:11]
        c |= ((nzuimm >>> 3) & 0x1) << 5; // nzuimm[3] -> c[5]
        c |= ((nzuimm >>> 2) & 0x1) << 6; // nzuimm[2] -> c[6]
        c |= ((rdP - 8) & 0x7) << 2;
        return c; // funct3 = 0, quadrant = 0
    }

    private static int lwSwImmBits(int imm) {
        int bits = ((imm >>> 2) & 0x1) << 6;
        bits |= ((imm >>> 3) & 0x7) << 10;
        bits |= ((imm >>> 6) & 0x1) << 5;
        return bits;
    }

    private static int cLw(int rdP, int rs1P, int imm) {
        return (2 << 13) | lwSwImmBits(imm) | (((rs1P - 8) & 0x7) << 7) | (((rdP - 8) & 0x7) << 2);
    }

    private static int cSw(int rs1P, int rs2P, int imm) {
        return (6 << 13) | lwSwImmBits(imm) | (((rs1P - 8) & 0x7) << 7) | (((rs2P - 8) & 0x7) << 2);
    }

    private static int cAddi(int rd, int imm) {
        int u = imm & 0x3f;
        return (((u >>> 5) & 1) << 12) | ((rd & 0x1f) << 7) | ((u & 0x1f) << 2) | 0x1;
    }

    private static int cLi(int rd, int imm) {
        int u = imm & 0x3f;
        return (2 << 13) | (((u >>> 5) & 1) << 12) | ((rd & 0x1f) << 7) | ((u & 0x1f) << 2) | 0x1;
    }

    private static int cLui(int rd, int imm) {
        int u = imm & 0x3f;
        return (3 << 13) | (((u >>> 5) & 1) << 12) | ((rd & 0x1f) << 7) | ((u & 0x1f) << 2) | 0x1;
    }

    private static int cAddi16sp(int imm) {
        int c = ((imm >>> 4) & 0x1) << 6;
        c |= ((imm >>> 5) & 0x1) << 2;
        c |= ((imm >>> 6) & 0x1) << 5;
        c |= ((imm >>> 7) & 0x3) << 3;
        c |= ((imm >>> 9) & 0x1) << 12;
        return (3 << 13) | c | (2 << 7) | 0x1;
    }

    private static int cAndi(int rdP, int imm) {
        int u = imm & 0x3f;
        return (4 << 13) | (((u >>> 5) & 1) << 12) | (0x2 << 10) | (((rdP - 8) & 0x7) << 7) | ((u & 0x1f) << 2) | 0x1;
    }

    private static int cSrli(int rdP, int shamt) {
        return (4 << 13) | (((shamt >>> 5) & 1) << 12) | (((rdP - 8) & 0x7) << 7) | ((shamt & 0x1f) << 2) | 0x1;
    }

    private static int cSrai(int rdP, int shamt) {
        return (4 << 13)
                | (((shamt >>> 5) & 1) << 12)
                | (0x1 << 10)
                | (((rdP - 8) & 0x7) << 7)
                | ((shamt & 0x1f) << 2)
                | 0x1;
    }

    private static int cArithReg(int subop2, int rdP, int rs2P) {
        return (4 << 13)
                | (0x3 << 10)
                | (((rdP - 8) & 0x7) << 7)
                | ((subop2 & 0x3) << 5)
                | (((rs2P - 8) & 0x7) << 2)
                | 0x1;
    }

    private static int jImmBits(int imm) {
        int c = ((imm >>> 1) & 0x7) << 3;
        c |= ((imm >>> 4) & 0x1) << 11;
        c |= ((imm >>> 5) & 0x1) << 2;
        c |= ((imm >>> 6) & 0x1) << 7;
        c |= ((imm >>> 7) & 0x1) << 6;
        c |= ((imm >>> 8) & 0x3) << 9;
        c |= ((imm >>> 10) & 0x1) << 8;
        c |= ((imm >>> 11) & 0x1) << 12;
        return c;
    }

    private static int cJ(int imm) {
        return (5 << 13) | jImmBits(imm) | 0x1;
    }

    private static int cJal(int imm) {
        return (1 << 13) | jImmBits(imm) | 0x1;
    }

    private static int bImmBits(int imm) {
        int c = ((imm >>> 1) & 0x3) << 3;
        c |= ((imm >>> 3) & 0x3) << 10;
        c |= ((imm >>> 5) & 0x1) << 2;
        c |= ((imm >>> 6) & 0x3) << 5;
        c |= ((imm >>> 8) & 0x1) << 12;
        return c;
    }

    private static int cBeqz(int rs1P, int imm) {
        return (6 << 13) | bImmBits(imm) | (((rs1P - 8) & 0x7) << 7) | 0x1;
    }

    private static int cBnez(int rs1P, int imm) {
        return (7 << 13) | bImmBits(imm) | (((rs1P - 8) & 0x7) << 7) | 0x1;
    }

    private static int cSlli(int rd, int shamt) {
        return (((shamt >>> 5) & 1) << 12) | ((rd & 0x1f) << 7) | ((shamt & 0x1f) << 2) | 0x2;
    }

    private static int cLwsp(int rd, int imm) {
        int c = ((imm >>> 2) & 0x7) << 4;
        c |= ((imm >>> 5) & 0x1) << 12;
        c |= ((imm >>> 6) & 0x3) << 2;
        return (2 << 13) | c | ((rd & 0x1f) << 7) | 0x2;
    }

    private static int cSwsp(int rs2, int imm) {
        int c = ((imm >>> 2) & 0xf) << 9;
        c |= ((imm >>> 6) & 0x3) << 7;
        return (6 << 13) | c | ((rs2 & 0x1f) << 2) | 0x2;
    }

    private static int cFlw(int rdP, int rs1P, int imm) {
        return (3 << 13) | lwSwImmBits(imm) | (((rs1P - 8) & 0x7) << 7) | (((rdP - 8) & 0x7) << 2);
    }

    private static int cFsw(int rs1P, int rs2P, int imm) {
        return (7 << 13) | lwSwImmBits(imm) | (((rs1P - 8) & 0x7) << 7) | (((rs2P - 8) & 0x7) << 2);
    }

    private static int cFlwsp(int rd, int imm) {
        int c = ((imm >>> 2) & 0x7) << 4;
        c |= ((imm >>> 5) & 0x1) << 12;
        c |= ((imm >>> 6) & 0x3) << 2;
        return (3 << 13) | c | ((rd & 0x1f) << 7) | 0x2;
    }

    private static int cFswsp(int rs2, int imm) {
        int c = ((imm >>> 2) & 0xf) << 9;
        c |= ((imm >>> 6) & 0x3) << 7;
        return (7 << 13) | c | ((rs2 & 0x1f) << 2) | 0x2;
    }

    private static int cJr(int rs1) {
        return (4 << 13) | ((rs1 & 0x1f) << 7) | 0x2;
    }

    private static int cMv(int rd, int rs2) {
        return (4 << 13) | ((rd & 0x1f) << 7) | ((rs2 & 0x1f) << 2) | 0x2;
    }

    private static int cEbreak() {
        return (4 << 13) | (1 << 12) | 0x2;
    }

    private static int cJalr(int rs1) {
        return (4 << 13) | (1 << 12) | ((rs1 & 0x1f) << 7) | 0x2;
    }

    private static int cAdd(int rd, int rs2) {
        return (4 << 13) | (1 << 12) | ((rd & 0x1f) << 7) | ((rs2 & 0x1f) << 2) | 0x2;
    }

    // ---- Infrastructure ----

    private static RV32IMAState machineState() {
        RV32IMAState state = new RV32IMAState();
        state.pc = RAM_OFFSET;
        state.extraflags |= 3;
        return state;
    }

    private static RV32IMAState run(
            IsaConfig config,
            int instrWord,
            boolean compressed,
            Consumer<RV32IMAState> stateSetup,
            Consumer<FFMMemoryBus> memSetup) {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            if (memSetup != null) {
                memSetup.accept(ram);
            }
            stateSetup.accept(state);
            if (compressed) {
                ram.writeShort(RAM_OFFSET, (short) instrWord);
            } else {
                ram.writeInt(RAM_OFFSET, instrWord);
            }
            new RV32IMACore(config).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            return state;
        }
    }

    private static void assertSameEffect(int chalf, int word32, Consumer<RV32IMAState> stateSetup) {
        assertSameEffect(chalf, word32, stateSetup, null);
    }

    private static void assertSameEffect(
            int chalf, int word32, Consumer<RV32IMAState> stateSetup, Consumer<FFMMemoryBus> memSetup) {
        RV32IMAState viaCompressed = run(HAS_C, chalf, true, stateSetup, memSetup);
        RV32IMAState via32 = run(new IsaConfig(false, false, false, false, false), word32, false, stateSetup, memSetup);
        assertArrayEquals(via32.regs, viaCompressed.regs);
    }

    /**
     * Like {@link #assertSameEffect}, but for the C.FLW/C.FLWSP compressed loads: compares {@code
     * fregs} (both sides configured with {@code hasF}, since the 32-bit comparison instruction is
     * itself FLW) instead of {@code regs}.
     */
    private static void assertSameFregEffect(
            int chalf, int word32, Consumer<RV32IMAState> stateSetup, Consumer<FFMMemoryBus> memSetup) {
        RV32IMAState viaCompressed = run(HAS_C_F, chalf, true, stateSetup, memSetup);
        RV32IMAState via32 = run(HAS_F, word32, false, stateSetup, memSetup);
        assertArrayEquals(via32.fregs, viaCompressed.fregs);
    }

    /**
     * Like {@link #assertSameEffect}, but also asserts the final PC matches -- meaningful only for
     * a taken branch/jump, where the target is {@code pc + offset} independent of the executing
     * instruction's own length, unlike the fall-through case.
     */
    private static void assertSameEffectAndPc(int chalf, int word32, Consumer<RV32IMAState> stateSetup) {
        RV32IMAState viaCompressed = run(HAS_C, chalf, true, stateSetup, null);
        RV32IMAState via32 = run(new IsaConfig(false, false, false, false, false), word32, false, stateSetup, null);
        assertArrayEquals(via32.regs, viaCompressed.regs);
        assertEquals(via32.pc, viaCompressed.pc);
    }

    // ---- Differential: register/immediate forms ----

    @Test
    public void addi() {
        assertSameEffect(cAddi(8, 5), opImm(5, 0, 8, 8), s -> s.regs[8] = 100);
        assertSameEffect(cAddi(8, -5), opImm(-5, 0, 8, 8), s -> s.regs[8] = 100);
        assertSameEffect(cAddi(0, 0), opImm(0, 0, 0, 0), s -> {}); // C.NOP
    }

    @Test
    public void li() {
        assertSameEffect(cLi(9, -1), opImm(-1, 0, 9, 0), s -> s.regs[9] = 0xdead);
    }

    @Test
    public void lui() {
        assertSameEffect(cLui(9, -1), luiInstr(9, -1 & 0xfffff), s -> {});
        assertSameEffect(cLui(9, 5), luiInstr(9, 5), s -> {});
    }

    @Test
    public void addi16sp() {
        assertSameEffect(cAddi16sp(-16), opImm(-16, 0, 2, 2), s -> s.regs[2] = RAM_OFFSET);
        assertSameEffect(cAddi16sp(496), opImm(496, 0, 2, 2), s -> s.regs[2] = RAM_OFFSET);
    }

    @Test
    public void addi4spn() {
        assertSameEffect(cAddi4spn(8, 4), opImm(4, 0, 8, 2), s -> s.regs[2] = RAM_OFFSET);
        assertSameEffect(cAddi4spn(15, 1020), opImm(1020, 0, 15, 2), s -> s.regs[2] = RAM_OFFSET);
    }

    @Test
    public void andi() {
        assertSameEffect(cAndi(9, -8), opImm(-8, 7, 9, 9), s -> s.regs[9] = 0xff);
    }

    @Test
    public void srli() {
        assertSameEffect(cSrli(9, 4), opImm(4, 5, 9, 9), s -> s.regs[9] = 0xff00);
    }

    @Test
    public void srai() {
        assertSameEffect(cSrai(9, 4), opImm(0x400 | 4, 5, 9, 9), s -> s.regs[9] = 0x80000000);
    }

    @Test
    public void subXorOrAnd() {
        assertSameEffect(cArithReg(0, 9, 10), op(0x20, 0, 9, 9, 10), s -> {
            s.regs[9] = 20;
            s.regs[10] = 5;
        });
        assertSameEffect(cArithReg(1, 9, 10), op(0, 4, 9, 9, 10), s -> {
            s.regs[9] = 0xff00;
            s.regs[10] = 0x0ff0;
        });
        assertSameEffect(cArithReg(2, 9, 10), op(0, 6, 9, 9, 10), s -> {
            s.regs[9] = 0xff00;
            s.regs[10] = 0x0ff0;
        });
        assertSameEffect(cArithReg(3, 9, 10), op(0, 7, 9, 9, 10), s -> {
            s.regs[9] = 0xff00;
            s.regs[10] = 0x0ff0;
        });
    }

    @Test
    public void slli() {
        assertSameEffect(cSlli(9, 3), opImm(3, 1, 9, 9), s -> s.regs[9] = 1);
    }

    @Test
    public void mv() {
        assertSameEffect(cMv(9, 10), op(0, 0, 9, 0, 10), s -> {
            s.regs[9] = 111;
            s.regs[10] = 222;
        });
    }

    @Test
    public void add() {
        assertSameEffect(cAdd(9, 10), op(0, 0, 9, 9, 10), s -> {
            s.regs[9] = 111;
            s.regs[10] = 222;
        });
    }

    @Test
    public void lwAndSw() {
        assertSameEffect(
                cLw(9, 8, 8),
                load(2, 9, 8, 8),
                s -> s.regs[8] = RAM_OFFSET + 0x40,
                ram -> ram.writeInt(RAM_OFFSET + 0x48, 0x12345678));
        assertSameEffect(
                cSw(8, 9, 8),
                store(2, 8, 9, 8),
                s -> {
                    s.regs[8] = RAM_OFFSET + 0x40;
                    s.regs[9] = 0xcafef00d;
                },
                null);
    }

    @Test
    public void lwspAndSwsp() {
        assertSameEffect(
                cLwsp(9, 8),
                load(2, 9, 2, 8),
                s -> s.regs[2] = RAM_OFFSET + 0x40,
                ram -> ram.writeInt(RAM_OFFSET + 0x48, 0x12345678));
        assertSameEffect(
                cSwsp(9, 8),
                store(2, 2, 9, 8),
                s -> {
                    s.regs[2] = RAM_OFFSET + 0x40;
                    s.regs[9] = 0xcafef00d;
                },
                null);
    }

    @Test
    public void beqzAndBnez() {
        // Both vectors take the branch: the target (pc + offset) is instrLen-independent, so
        // comparing final pc between the compressed and 32-bit forms is meaningful here (unlike
        // the fall-through case, where pc legitimately differs by the executing instruction's own
        // length).
        assertSameEffectAndPc(cBeqz(8, 16), branch(0, 8, 0, 16), s -> s.regs[8] = 0);
        assertSameEffectAndPc(cBnez(8, 16), branch(1, 8, 0, 16), s -> s.regs[8] = 1);
    }

    // ---- instrLen-sensitive: link value and control flow ----

    @Test
    public void jalLinksToPcPlus2NotPcPlus4() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeShort(RAM_OFFSET, (short) cJal(16));
            new RV32IMACore(HAS_C).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 2, state.regs[1]);
            assertEquals(RAM_OFFSET + 16, state.pc);
        }
    }

    @Test
    public void jDoesNotLink() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeShort(RAM_OFFSET, (short) cJ(16));
            new RV32IMACore(HAS_C).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(0, state.regs[1]);
            assertEquals(RAM_OFFSET + 16, state.pc);
        }
    }

    @Test
    public void jrJumpsWithoutLinking() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[9] = RAM_OFFSET + 0x40;
            ram.writeShort(RAM_OFFSET, (short) cJr(9));
            new RV32IMACore(HAS_C).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(0, state.regs[1]);
            assertEquals(RAM_OFFSET + 0x40, state.pc);
        }
    }

    @Test
    public void jalrLinksToPcPlus2AndSetsRa() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[9] = RAM_OFFSET + 0x40;
            ram.writeShort(RAM_OFFSET, (short) cJalr(9));
            new RV32IMACore(HAS_C).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 2, state.regs[1]);
            assertEquals(RAM_OFFSET + 0x40, state.pc);
        }
    }

    @Test
    public void ebreakTrapsAsBreakpointAtCompressedPc() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.pc = RAM_OFFSET + 2; // odd-halfword address, reachable only under hasC
            ram.writeShort(RAM_OFFSET + 2, (short) cEbreak());
            new RV32IMACore(HAS_C).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(3, state.mcause); // EXC_BREAKPOINT
            assertEquals(RAM_OFFSET + 2, state.mepc);
        }
    }

    // ---- Mixed-length instruction streams: the actual point of the C extension ----

    @Test
    public void compressedThenWordInstructionAtHalfwordAlignedStart() {
        // The 32-bit ADDI starts at RAM_OFFSET + 2 -- not word-aligned -- exercising mem.readInt
        // at a halfword-aligned address, and the next iteration's fetch stage being pointed at the
        // right place by pc += instrLen from the previous (compressed) iteration.
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[8] = 100;
            state.regs[9] = 0;
            ram.writeShort(RAM_OFFSET, (short) cAddi(8, 5)); // 2 bytes
            ram.writeInt(RAM_OFFSET + 2, opImm(7, 0, 9, 9)); // 4 bytes: ADDI x9, x9, 7
            new RV32IMACore(HAS_C).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 2, null, null);
            assertEquals(105, state.regs[8]);
            assertEquals(7, state.regs[9]);
            assertEquals(RAM_OFFSET + 6, state.pc);
        }
    }

    @Test
    public void wordThenCompressedInstructionAtHalfwordAlignedStart() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[8] = 100;
            state.regs[9] = 0;
            ram.writeInt(RAM_OFFSET, opImm(7, 0, 9, 9)); // 4 bytes: ADDI x9, x9, 7
            ram.writeShort(RAM_OFFSET + 4, (short) cAddi(8, 5)); // 2 bytes, starts word-aligned this time
            new RV32IMACore(HAS_C).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 2, null, null);
            assertEquals(105, state.regs[8]);
            assertEquals(7, state.regs[9]);
            assertEquals(RAM_OFFSET + 6, state.pc);
        }
    }

    // ---- F extension: C.FLW/C.FSW/C.FLWSP/C.FSWSP (Phase 5's follow-up to this phase) ----

    @Test
    public void cFlwMatchesFlw() {
        int bits = Float.floatToRawIntBits(3.5f);
        assertSameFregEffect(
                cFlw(9, 8, 8),
                flw(9, 8, 8),
                s -> s.regs[8] = RAM_OFFSET + 0x40,
                ram -> ram.writeInt(RAM_OFFSET + 0x48, bits));
    }

    @Test
    public void cFlwspMatchesFlwAndAllowsRdZero() {
        // f0 is an ordinary FP register, not hardwired zero (unlike x0/C.LWSP): rd == 0 must NOT
        // be reserved here.
        int bits = Float.floatToRawIntBits(-2.5f);
        assertSameFregEffect(
                cFlwsp(0, 8),
                flw(0, 2, 8),
                s -> s.regs[2] = RAM_OFFSET + 0x40,
                ram -> ram.writeInt(RAM_OFFSET + 0x48, bits));
    }

    @Test
    public void cFswStoresToMemoryLikeFsw() {
        int bits = Float.floatToRawIntBits(-1.25f);
        int viaCompressed;
        int via32;
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[8] = RAM_OFFSET + 0x40;
            setFReg(state, 9, bits);
            ram.writeShort(RAM_OFFSET, (short) cFsw(8, 9, 8));
            new RV32IMACore(HAS_C_F).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            viaCompressed = ram.readInt(RAM_OFFSET + 0x48);
        }
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[8] = RAM_OFFSET + 0x40;
            setFReg(state, 9, bits);
            ram.writeInt(RAM_OFFSET, fsw(8, 9, 8));
            new RV32IMACore(HAS_F).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            via32 = ram.readInt(RAM_OFFSET + 0x48);
        }
        assertEquals(bits, viaCompressed);
        assertEquals(via32, viaCompressed);
    }

    @Test
    public void cFswspStoresToMemoryLikeFsw() {
        int bits = Float.floatToRawIntBits(4.0f);
        int viaCompressed;
        int via32;
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[2] = RAM_OFFSET + 0x40;
            setFReg(state, 9, bits);
            ram.writeShort(RAM_OFFSET, (short) cFswsp(9, 8));
            new RV32IMACore(HAS_C_F).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            viaCompressed = ram.readInt(RAM_OFFSET + 0x48);
        }
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[2] = RAM_OFFSET + 0x40;
            setFReg(state, 9, bits);
            ram.writeInt(RAM_OFFSET, fsw(2, 9, 8));
            new RV32IMACore(HAS_F).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            via32 = ram.readInt(RAM_OFFSET + 0x48);
        }
        assertEquals(bits, viaCompressed);
        assertEquals(via32, viaCompressed);
    }

    @Test
    public void quadrant0FlwFswSlotsTrapIllegalWithoutHasFEvenWithHasC() {
        // decodeCompressed itself is IsaConfig-agnostic: funct3 3/7 at quadrant 0 always expand
        // into FLW/FSW, and it's the ordinary opcode switch's own IsaConfig.hasF check that traps
        // illegal-instruction here, since HAS_C has hasC but not hasF.
        assertIllegal(3 << 13); // funct3 = 3, quadrant = 0
        assertIllegal(7 << 13); // funct3 = 7, quadrant = 0
    }

    // ---- Reserved / illegal 16-bit patterns ----

    @Test
    public void addi4spnZeroImmIsIllegal() {
        assertIllegal(cAddi4spn(8, 0));
    }

    @Test
    public void sraiShamtBit5SetIsIllegalOnRv32() {
        assertIllegal(cSrai(9, 32));
    }

    @Test
    public void quadrant1SubwAddwSlotIsReservedOnRv32() {
        // bits 11:10 == 3 (the SUB/XOR/OR/AND cluster), bit 12 == 1: RV64's C.SUBW/C.ADDW live
        // here; RV32 has no instruction in this slot.
        assertIllegal(cArithReg(0, 9, 10) | (1 << 12));
    }

    @Test
    public void luiZeroImmIsIllegal() {
        assertIllegal(cLui(9, 0));
    }

    @Test
    public void addi16spZeroImmIsIllegal() {
        assertIllegal(cAddi16sp(0));
    }

    @Test
    public void slliShamtBit5SetIsIllegalOnRv32() {
        assertIllegal(cSlli(9, 32));
    }

    @Test
    public void srliShamtBit5SetIsIllegalOnRv32() {
        assertIllegal(cSrli(9, 32));
    }

    @Test
    public void jrWithRs1ZeroIsReserved() {
        assertIllegal(cJr(0));
    }

    @Test
    public void jalrClusterWithRs1ZeroAndRs2NonzeroIsReserved() {
        // bit12=1 (linking), rs2!=0, rs1==0: matches neither C.JALR (needs rs1!=0) nor C.ADD
        // (needs rd/rs1!=0) -- reserved.
        assertIllegal((4 << 13) | (1 << 12) | (9 << 2) | 0x2);
    }

    @Test
    public void addClusterWithRdZeroAndRs2NonzeroIsReserved() {
        assertIllegal(cAdd(0, 9));
    }

    @Test
    public void quadrant0Funct3OneAndFiveAreReservedForD() {
        // funct3=1 (C.FLD) and funct3=5 (C.FSD): valid on RV32DC, but D isn't decoded by this
        // core (only IsaConfig.hasD's misa bit exists) -- reserved regardless of IsaConfig.
        assertIllegal(1 << 13);
        assertIllegal(5 << 13);
    }

    @Test
    public void quadrant2Funct3OneAndFiveAreReservedForD() {
        // funct3=1 (C.FLDSP) and funct3=5 (C.FSDSP): same D-not-decoded story as above.
        assertIllegal((1 << 13) | 0x2);
        assertIllegal((5 << 13) | 0x2);
    }

    private static void assertIllegal(int chalf) {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeShort(RAM_OFFSET, (short) chalf);
            new RV32IMACore(HAS_C).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(2, state.mcause); // EXC_ILLEGAL_INSTRUCTION
        }
    }

    // ---- Gating and fetch-window-edge behavior ----

    @Test
    public void misalignedTwoByteFetchTrapsOnlyWithoutHasC() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.pc = RAM_OFFSET + 2;
            state.regs[8] = 100;
            ram.writeShort(RAM_OFFSET + 2, (short) cAddi(8, 5));
            new RV32IMACore().step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(0, state.mcause); // EXC_INSTRUCTION_MISALIGNED
        }
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.pc = RAM_OFFSET + 2;
            state.regs[8] = 100;
            ram.writeShort(RAM_OFFSET + 2, (short) cAddi(8, 5));
            new RV32IMACore(HAS_C).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(105, state.regs[8]);
            assertEquals(RAM_OFFSET + 4, state.pc);
        }
    }

    @Test
    public void wordFetchOverrunningRamWindowFaultsInsteadOfReadingPastIt() {
        // A 32-bit instruction (low halfword bits[1:0] == 3) starting 2 bytes before the end of
        // the RAM window: the coarse ramOffset/ramSize check alone would let this through, but the
        // 4-byte read needed to complete it runs 2 bytes past the window/backing memory.
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.pc = RAM_OFFSET + RAM_SIZE - 2;
            ram.writeShort(RAM_OFFSET + RAM_SIZE - 2, (short) 0x0013); // low bits 11, otherwise NOP-shaped
            new RV32IMACore(HAS_C).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);
            assertEquals(1, state.mcause); // EXC_INSTRUCTION_ACCESS_FAULT
            assertEquals(RAM_OFFSET + RAM_SIZE - 2, state.mtval);
        }
    }

    // ---- Encoding helpers reused from RV32IComplianceTest's conventions ----

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

    private static int flw(int rd, int rs1, int imm12) {
        return ((imm12 & 0xfff) << 20) | (rs1 << 15) | (2 << 12) | (rd << 7) | 0x07;
    }

    private static int fsw(int rs1, int rs2, int imm12) {
        return (((imm12 >> 5) & 0x7f) << 25) | (rs2 << 20) | (rs1 << 15) | (2 << 12) | ((imm12 & 0x1f) << 7) | 0x27;
    }

    private static void setFReg(RV32IMAState state, int idx, int bits) {
        state.fregs[idx] = 0xFFFFFFFF00000000L | (bits & 0xFFFFFFFFL);
    }

    private static int luiInstr(int rd, int imm20) {
        return ((imm20 & 0xfffff) << 12) | (rd << 7) | 0x37;
    }

    private static int branch(int funct3, int rs1, int rs2, int branchOffset) {
        return ((branchOffset & 0x1000) << 19)
                | ((branchOffset & 0x7e0) << 20)
                | (rs2 << 20)
                | (rs1 << 15)
                | (funct3 << 12)
                | ((branchOffset & 0x1e) << 7)
                | ((branchOffset & 0x800) >> 4)
                | 0x63;
    }
}
