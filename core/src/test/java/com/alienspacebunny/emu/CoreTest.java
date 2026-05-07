package com.alienspacebunny.emu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CoreTest {

    @Test
    public void testBasicArithmetic() {
        int ramOffset = 0x80000000;
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
        int ramOffset = 0x80000000;
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
}
