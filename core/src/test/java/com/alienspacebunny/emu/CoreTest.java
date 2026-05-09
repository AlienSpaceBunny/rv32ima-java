package com.alienspacebunny.emu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CoreTest {
    private static final int RAM_OFFSET = 0x80000000;

    private static RV32IMAState machineState() {
        RV32IMAState state = new RV32IMAState();
        state.pc = RAM_OFFSET;
        state.extraflags |= 3;
        return state;
    }

    private static int loadInstruction(int funct3, int rd, int rs1, int imm) {
        return ((imm & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x03;
    }

    private static int storeInstruction(int funct3, int rs1, int rs2, int imm) {
        return (((imm >> 5) & 0x7f) << 25)
                | (rs2 << 20)
                | (rs1 << 15)
                | (funct3 << 12)
                | ((imm & 0x1f) << 7)
                | 0x23;
    }

    @Test
    public void testBasicArithmetic() {
        int ramOffset = RAM_OFFSET;
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, ramOffset)) {
            RV32IMAState state = new RV32IMAState();
            state.pc = ramOffset;
            state.extraflags |= 3; // Machine mode

            RV32IMACore core = new RV32IMACore();

            // addi x1, x0, 10  (0x00a00093)
            // addi x2, x0, 20  (0x01400113)
            // add x3, x1, x2   (0x002081b3)
            ram.writeInt(ramOffset, 0x00a00093);
            ram.writeInt(ramOffset + 4, 0x01400113);
            ram.writeInt(ramOffset + 8, 0x002081b3);

            core.step(state, ram, ramOffset, ramSize, 0, 3, null, null);

            assertEquals(10, state.regs[1]);
            assertEquals(20, state.regs[2]);
            assertEquals(30, state.regs[3]);
            assertEquals(ramOffset + 12, state.pc);
        }
    }

    @Test
    public void testLoadStore() {
        int ramOffset = RAM_OFFSET;
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, ramOffset)) {
            RV32IMAState state = new RV32IMAState();
            state.pc = ramOffset;
            state.extraflags |= 3;

            RV32IMACore core = new RV32IMACore();

            // addi x1, x0, 123
            // addi x2, x0, 0x80000000 (addi x2, x0, 0 then lui x2, 0x80000)
            // Actually let's just use lui x2, 0x80000
            // lui x2, 0x80000 (0x80000137)
            // sw x1, 100(x2)  (0x06112223)
            // lw x3, 100(x2)  (0x06412183)
            ram.writeInt(ramOffset, 0x07b00093); // addi x1, x0, 123
            ram.writeInt(ramOffset + 4, 0x80000137); // lui x2, 0x80000
            ram.writeInt(ramOffset + 8, 0x06112223); // sw x1, 100(x2)
            ram.writeInt(ramOffset + 12, 0x06412183); // lw x3, 100(x2)

            core.step(state, ram, ramOffset, ramSize, 0, 4, null, null);

            assertEquals(123, state.regs[1]);
            assertEquals(0x80000000, state.regs[2]);
            assertEquals(123, state.regs[3]);
            assertEquals(123, ram.readInt(0x80000000 + 100));
        }
    }

    @Test
    public void testMemoryOffset() {
        int ramOffset = 0x1000;
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, ramOffset)) {
            ram.writeInt(0x1000, 0xCAFEBABE);
            ram.writeInt(0x1004, 0xDEADBEEF);
            
            assertEquals(0xCAFEBABE, ram.readInt(0x1000));
            assertEquals(0xDEADBEEF, ram.readInt(0x1004));
            
            assertThrows(IndexOutOfBoundsException.class, () -> ram.readInt(0x0000));
            assertThrows(IndexOutOfBoundsException.class, () -> ram.readInt(0x2000));
        }
    }

    @Test
    public void invalidGuestLoadBelowRamRaisesLoadAccessFault() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int faultAddress = RAM_OFFSET - 4;
            state.regs[1] = faultAddress;
            ram.writeInt(RAM_OFFSET, loadInstruction(2, 2, 1, 0)); // lw x2, 0(x1)

            assertDoesNotThrow(() -> new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null));

            assertEquals(5, state.mcause);
            assertEquals(faultAddress, state.mtval);
            assertEquals(RAM_OFFSET, state.mepc);
        }
    }

    @Test
    public void invalidGuestStorePastRamRaisesStoreAccessFault() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int faultAddress = RAM_OFFSET + ramSize;
            state.regs[1] = faultAddress;
            state.regs[2] = 0x12345678;
            ram.writeInt(RAM_OFFSET, storeInstruction(2, 1, 2, 0)); // sw x2, 0(x1)

            assertDoesNotThrow(() -> new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null));

            assertEquals(7, state.mcause);
            assertEquals(faultAddress, state.mtval);
            assertEquals(RAM_OFFSET, state.mepc);
        }
    }

    @Test
    public void invalidGuestMmioLoadWithoutHookRaisesLoadAccessFault() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            MMIOBus bus = new MMIOBus(ram);
            RV32IMAState state = machineState();
            int faultAddress = 0x10000000;
            state.regs[1] = faultAddress;
            ram.writeInt(RAM_OFFSET, loadInstruction(2, 2, 1, 0)); // lw x2, 0(x1)

            assertDoesNotThrow(() -> new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null));

            assertEquals(5, state.mcause);
            assertEquals(faultAddress, state.mtval);
            assertEquals(RAM_OFFSET, state.mepc);
        }
    }

    @Test
    public void validBoundaryByteAccessesDoNotTrap() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMACore core = new RV32IMACore();

            RV32IMAState firstByteLoad = machineState();
            firstByteLoad.regs[1] = RAM_OFFSET;
            ram.writeByte(RAM_OFFSET, (byte) 0x7f);
            ram.writeInt(RAM_OFFSET + 4, loadInstruction(0, 2, 1, 0)); // lb x2, 0(x1)
            firstByteLoad.pc = RAM_OFFSET + 4;
            core.step(firstByteLoad, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0, firstByteLoad.mcause);
            assertEquals(0x7f, firstByteLoad.regs[2]);

            RV32IMAState lastByteStore = machineState();
            lastByteStore.regs[1] = RAM_OFFSET + ramSize - 1;
            lastByteStore.regs[2] = 0xa5;
            ram.writeInt(RAM_OFFSET + 8, storeInstruction(0, 1, 2, 0)); // sb x2, 0(x1)
            lastByteStore.pc = RAM_OFFSET + 8;
            core.step(lastByteStore, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0, lastByteStore.mcause);
            assertEquals((byte) 0xa5, ram.readByte(RAM_OFFSET + ramSize - 1));
        }
    }
}
