package com.alienspacebunny.bench;

import java.io.ByteArrayOutputStream;

/**
 * Minimal RV32 assembler for the benchmark's fixed guest programs: only the encodings the
 * workloads use, backward branches only, and little-endian output. Encodings follow the RISC-V
 * unprivileged specification; the workloads' Java reference models and the core's own decoder
 * cross-check them on every run.
 */
final class Asm {
    static final int ZERO = 0;
    static final int RA = 1;
    static final int T0 = 5;
    static final int T1 = 6;
    static final int T2 = 7;
    static final int S0 = 8;
    static final int S1 = 9;
    static final int A0 = 10;
    static final int A1 = 11;
    static final int A2 = 12;
    static final int A3 = 13;
    static final int A4 = 14;
    static final int A5 = 15;
    static final int S2 = 18;
    static final int S3 = 19;
    static final int S4 = 20;
    static final int S5 = 21;
    static final int T3 = 28;
    static final int T4 = 29;
    static final int T5 = 30;
    static final int T6 = 31;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private long instructions;

    /** Current assembly address, usable as a backward-branch label. */
    int pc() {
        return out.size();
    }

    /** Number of instructions emitted so far. */
    long count() {
        return instructions;
    }

    byte[] bytes() {
        return out.toByteArray();
    }

    private void word(int w) {
        out.write(w);
        out.write(w >>> 8);
        out.write(w >>> 16);
        out.write(w >>> 24);
        instructions++;
    }

    private void half(int h) {
        out.write(h);
        out.write(h >>> 8);
        instructions++;
    }

    private void rType(int funct7, int rs2, int rs1, int funct3, int rd, int opcode) {
        word((funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode);
    }

    private void iType(int imm, int rs1, int funct3, int rd, int opcode) {
        if (imm < -2048 || imm > 2047) {
            throw new IllegalArgumentException("I-type immediate out of range: " + imm);
        }
        word(((imm & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode);
    }

    private void sType(int imm, int rs2, int rs1, int funct3) {
        word((((imm >> 5) & 0x7f) << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | ((imm & 0x1f) << 7) | 0x23);
    }

    void add(int rd, int rs1, int rs2) {
        rType(0, rs2, rs1, 0, rd, 0x33);
    }

    void sub(int rd, int rs1, int rs2) {
        rType(0x20, rs2, rs1, 0, rd, 0x33);
    }

    void xor(int rd, int rs1, int rs2) {
        rType(0, rs2, rs1, 4, rd, 0x33);
    }

    void addi(int rd, int rs1, int imm) {
        iType(imm, rs1, 0, rd, 0x13);
    }

    void xori(int rd, int rs1, int imm) {
        iType(imm, rs1, 4, rd, 0x13);
    }

    void andi(int rd, int rs1, int imm) {
        iType(imm, rs1, 7, rd, 0x13);
    }

    void slli(int rd, int rs1, int shamt) {
        iType(shamt & 0x1f, rs1, 1, rd, 0x13);
    }

    void srli(int rd, int rs1, int shamt) {
        iType(shamt & 0x1f, rs1, 5, rd, 0x13);
    }

    void mul(int rd, int rs1, int rs2) {
        rType(1, rs2, rs1, 0, rd, 0x33);
    }

    void jalr(int rd, int rs1, int imm) {
        iType(imm, rs1, 0, rd, 0x67);
    }

    /** Pads with zero bytes, which are not instructions, up to {@code address}. */
    void padTo(int address) {
        if (address < pc()) {
            throw new IllegalArgumentException("cannot pad backwards to " + address);
        }
        while (pc() < address) {
            out.write(0);
        }
    }

    /** Always two instructions ({@code lui} + {@code addi}), so instruction counts stay fixed. */
    void li(int rd, int value) {
        int hi = (value + 0x800) >>> 12;
        int lo = value - (hi << 12);
        word((hi << 12) | (rd << 7) | 0x37);
        addi(rd, rd, lo);
    }

    void lb(int rd, int rs1, int imm) {
        iType(imm, rs1, 0, rd, 0x03);
    }

    void lh(int rd, int rs1, int imm) {
        iType(imm, rs1, 1, rd, 0x03);
    }

    void lw(int rd, int rs1, int imm) {
        iType(imm, rs1, 2, rd, 0x03);
    }

    void sb(int rs2, int rs1, int imm) {
        sType(imm, rs2, rs1, 0);
    }

    void sh(int rs2, int rs1, int imm) {
        sType(imm, rs2, rs1, 1);
    }

    void sw(int rs2, int rs1, int imm) {
        sType(imm, rs2, rs1, 2);
    }

    /** {@code bne rs1, rs2, target} for a target at or before the current address. */
    void bne(int rs1, int rs2, int target) {
        int off = target - pc();
        if (off > 0 || off < -4096) {
            throw new IllegalArgumentException("Branch offset out of range: " + off);
        }
        word(((off >>> 12) & 1) << 31
                | ((off >>> 5) & 0x3f) << 25
                | (rs2 << 20)
                | (rs1 << 15)
                | (1 << 12)
                | ((off >>> 1) & 0xf) << 8
                | ((off >>> 11) & 1) << 7
                | 0x63);
    }

    private void amo(int funct5, int rd, int rs2, int rs1) {
        word((funct5 << 27) | (rs2 << 20) | (rs1 << 15) | (2 << 12) | (rd << 7) | 0x2f);
    }

    void amoaddW(int rd, int rs2, int rs1) {
        amo(0, rd, rs2, rs1);
    }

    void lrW(int rd, int rs1) {
        amo(2, rd, 0, rs1);
    }

    void scW(int rd, int rs2, int rs1) {
        amo(3, rd, rs2, rs1);
    }

    // ---- RV32F (rm = 0, round to nearest even) ---------------------------------------------

    void flw(int frd, int rs1, int imm) {
        iType(imm, rs1, 2, frd, 0x07);
    }

    void fsw(int frs2, int rs1, int imm) {
        word((((imm >> 5) & 0x7f) << 25) | (frs2 << 20) | (rs1 << 15) | (2 << 12) | ((imm & 0x1f) << 7) | 0x27);
    }

    void faddS(int frd, int frs1, int frs2) {
        rType(0x00, frs2, frs1, 0, frd, 0x53);
    }

    void fmulS(int frd, int frs1, int frs2) {
        rType(0x08, frs2, frs1, 0, frd, 0x53);
    }

    /** {@code fmadd.s frd, frs1, frs2, frs3}: {@code frs1 * frs2 + frs3} with one rounding. */
    void fmaddS(int frd, int frs1, int frs2, int frs3) {
        word((frs3 << 27) | (frs2 << 20) | (frs1 << 15) | (frd << 7) | 0x43);
    }

    void wfi() {
        word(0x10500073);
    }

    // ---- RVC ---------------------------------------------------------------------------

    void cAddi(int rd, int imm) {
        if (rd == ZERO || imm < -32 || imm > 31 || imm == 0) {
            throw new IllegalArgumentException("Unsupported c.addi operands");
        }
        half((((imm >> 5) & 1) << 12) | (rd << 7) | ((imm & 0x1f) << 2) | 0x1);
    }

    void cAdd(int rd, int rs2) {
        half((4 << 13) | (1 << 12) | (rd << 7) | (rs2 << 2) | 0x2);
    }

    void cXor(int rd, int rs2) {
        half((4 << 13) | (3 << 10) | (prime(rd) << 7) | (1 << 5) | (prime(rs2) << 2) | 0x1);
    }

    /** {@code c.bnez rs1, target} for a target at or before the current address. */
    void cBnez(int rs1, int target) {
        int off = target - pc();
        if (off > 0 || off < -256) {
            throw new IllegalArgumentException("Compressed branch offset out of range: " + off);
        }
        half((7 << 13)
                | ((off >> 8) & 1) << 12
                | ((off >> 3) & 3) << 10
                | (prime(rs1) << 7)
                | ((off >> 6) & 3) << 5
                | ((off >> 1) & 3) << 3
                | ((off >> 5) & 1) << 2
                | 0x1);
    }

    private static int prime(int reg) {
        if (reg < 8 || reg > 15) {
            throw new IllegalArgumentException("Not a compressed (x8-x15) register: x" + reg);
        }
        return reg - 8;
    }
}
