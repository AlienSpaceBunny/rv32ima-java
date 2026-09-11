package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies Zabha (byte/halfword AMOs, Phase 3): width-aware dispatch through {@link
 * MemoryBus#atomicRmw}, neighboring-byte preservation, sign extension into {@code rd}, signed vs.
 * unsigned comparison at reduced width, ignoring {@code rs2}'s bits above the operand width, the
 * absence of byte/halfword {@code LR}/{@code SC}, and gating by {@link IsaConfig#hasZabha}.
 */
public class ZabhaTest {
    private static final int RAM_OFFSET = 0x80000000;
    private static final int RAM_SIZE = 256;
    private static final IsaConfig ZABHA = new IsaConfig(false, false, false, false, true);

    private static int amoInstruction(int funct5, int funct3, int rd, int rs1, int rs2) {
        return (funct5 << 27) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x2f;
    }

    private static RV32IMAState machineState() {
        RV32IMAState state = new RV32IMAState();
        state.pc = RAM_OFFSET;
        state.extraflags |= 3;
        return state;
    }

    @Test
    public void amoaddBPreservesNeighboringBytes() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            ram.writeInt(RAM_OFFSET + 0x40, 0xdeadbe07); // byte at +0x40 is 0x07 (little-endian)
            RV32IMAState state = machineState();
            state.regs[1] = RAM_OFFSET + 0x40;
            state.regs[2] = 0x05;
            ram.writeInt(RAM_OFFSET, amoInstruction(0, 0, 3, 1, 2)); // amoadd.b x3, x2, (x1)

            new RV32IMACore(ZABHA).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);

            assertEquals(0x07, state.regs[3]); // rd = old value, sign-extended (positive here)
            assertEquals(0x0c, Byte.toUnsignedInt(ram.readByte(RAM_OFFSET + 0x40))); // 0x07+0x05
            assertEquals(0xbe, Byte.toUnsignedInt(ram.readByte(RAM_OFFSET + 0x41)));
            assertEquals(0xde, Byte.toUnsignedInt(ram.readByte(RAM_OFFSET + 0x43)));
        }
    }

    @Test
    public void amoswapBSignExtendsOldValueIntoRd() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            ram.writeByte(RAM_OFFSET + 0x40, (byte) 0xff); // -1 signed
            RV32IMAState state = machineState();
            state.regs[1] = RAM_OFFSET + 0x40;
            state.regs[2] = 0x01;
            ram.writeInt(RAM_OFFSET, amoInstruction(1, 0, 3, 1, 2)); // amoswap.b x3, x2, (x1)

            new RV32IMACore(ZABHA).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);

            assertEquals(0xffffffff, state.regs[3]);
            assertEquals(0x01, Byte.toUnsignedInt(ram.readByte(RAM_OFFSET + 0x40)));
        }
    }

    @Test
    public void amoswapHPreservesNeighboringBytesAndSignExtends() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            ram.writeInt(RAM_OFFSET + 0x40, 0xcafeffff); // halfword at +0x40 is 0xffff (-1 signed)
            RV32IMAState state = machineState();
            state.regs[1] = RAM_OFFSET + 0x40;
            state.regs[2] = 0x1234;
            ram.writeInt(RAM_OFFSET, amoInstruction(1, 1, 3, 1, 2)); // amoswap.h x3, x2, (x1)

            new RV32IMACore(ZABHA).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);

            assertEquals(0xffffffff, state.regs[3]);
            assertEquals((short) 0x1234, ram.readShort(RAM_OFFSET + 0x40));
            assertEquals(0xcafe, ram.readShort(RAM_OFFSET + 0x42) & 0xffff);
        }
    }

    @Test
    public void amominBUsesSignedComparisonAtByteWidth() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            ram.writeByte(RAM_OFFSET + 0x40, (byte) 0x80); // -128 signed / 128 unsigned
            RV32IMAState state = machineState();
            state.regs[1] = RAM_OFFSET + 0x40;
            state.regs[2] = 0x01;
            ram.writeInt(RAM_OFFSET, amoInstruction(16, 0, 3, 1, 2)); // amomin.b x3, x2, (x1)

            new RV32IMACore(ZABHA).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);

            assertEquals(0xffffff80, state.regs[3]); // old value, sign-extended
            // signed min(-128, 1) == -128: memory unchanged
            assertEquals(0x80, Byte.toUnsignedInt(ram.readByte(RAM_OFFSET + 0x40)));
        }
    }

    @Test
    public void amominuBUsesUnsignedComparisonAtByteWidth() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            ram.writeByte(RAM_OFFSET + 0x40, (byte) 0x80); // 128 unsigned
            RV32IMAState state = machineState();
            state.regs[1] = RAM_OFFSET + 0x40;
            state.regs[2] = 0x01;
            ram.writeInt(RAM_OFFSET, amoInstruction(24, 0, 3, 1, 2)); // amominu.b x3, x2, (x1)

            new RV32IMACore(ZABHA).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);

            assertEquals(0xffffff80, state.regs[3]); // old value, still sign-extended
            // unsigned min(128, 1) == 1: memory updated (opposite of the signed case above)
            assertEquals(0x01, Byte.toUnsignedInt(ram.readByte(RAM_OFFSET + 0x40)));
        }
    }

    @Test
    public void amoxorBIgnoresRs2BitsAboveOperandWidth() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            ram.writeByte(RAM_OFFSET + 0x40, (byte) 0x0f);
            RV32IMAState state = machineState();
            state.regs[1] = RAM_OFFSET + 0x40;
            state.regs[2] = 0xdeadbeff; // low byte 0xff; upper bits must be ignored
            ram.writeInt(RAM_OFFSET, amoInstruction(4, 0, 3, 1, 2)); // amoxor.b x3, x2, (x1)

            new RV32IMACore(ZABHA).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);

            assertEquals(0x0f, state.regs[3]);
            assertEquals(0xf0, Byte.toUnsignedInt(ram.readByte(RAM_OFFSET + 0x40))); // 0x0f ^ 0xff
        }
    }

    @Test
    public void subWordLrScRemainIllegalEvenWithZabha() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = RAM_OFFSET + 0x40;
            ram.writeInt(RAM_OFFSET, amoInstruction(2, 0, 3, 1, 0)); // lr.b x3, (x1) -- not a real op

            new RV32IMACore(ZABHA).step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);

            assertEquals(2, state.mcause); // illegal instruction
        }
    }

    @Test
    public void byteAndHalfwordAmosTrapIllegalWithoutZabha() {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = RAM_OFFSET + 0x40;
            state.regs[2] = 1;
            ram.writeInt(RAM_OFFSET, amoInstruction(0, 0, 3, 1, 2)); // amoadd.b, no Zabha

            new RV32IMACore().step(state, ram, RAM_OFFSET, RAM_SIZE, 0, 1, null, null);

            assertEquals(2, state.mcause); // illegal instruction
        }
    }
}
