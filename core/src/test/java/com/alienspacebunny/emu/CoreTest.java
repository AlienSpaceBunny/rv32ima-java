package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

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
        return (((imm >> 5) & 0x7f) << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | ((imm & 0x1f) << 7) | 0x23;
    }

    private static int csrInstruction(int csr, int funct3, int rd, int rs1OrImmediate) {
        return (csr << 20) | (rs1OrImmediate << 15) | (funct3 << 12) | (rd << 7) | 0x73;
    }

    private static int opInstruction(int funct7, int funct3, int rd, int rs1, int rs2) {
        return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x33;
    }

    private static int opImmInstruction(int imm, int funct3, int rd, int rs1) {
        return ((imm & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x13;
    }

    private static int amoInstruction(int funct5, int funct3, int rd, int rs1, int rs2) {
        return (funct5 << 27) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x2f;
    }

    private static final class RecordingCSRHook implements CSRHook {
        int readCount;
        int writeCount;
        int readValue = 0x12345678;
        int lastWriteCsr;
        int lastWriteValue;

        @Override
        public int handleRead(int csrNo) {
            readCount++;
            return readValue;
        }

        @Override
        public void handleWrite(int csrNo, int value) {
            writeCount++;
            lastWriteCsr = csrNo;
            lastWriteValue = value;
        }
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

    @Test
    public void csrrwWithX0DestinationDoesNotReadCsr() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0x55aa55aa;
            RecordingCSRHook csrHook = new RecordingCSRHook();
            ram.writeInt(RAM_OFFSET, csrInstruction(0x7c0, 1, 0, 1)); // csrrw x0, 0x7c0, x1

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, csrHook);

            assertEquals(0, csrHook.readCount);
            assertEquals(1, csrHook.writeCount);
            assertEquals(0x7c0, csrHook.lastWriteCsr);
            assertEquals(0x55aa55aa, csrHook.lastWriteValue);
        }
    }

    @Test
    public void csrrsWithX0SourceReadsButDoesNotWriteCsr() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            RecordingCSRHook csrHook = new RecordingCSRHook();
            ram.writeInt(RAM_OFFSET, csrInstruction(0x7c0, 2, 2, 0)); // csrrs x2, 0x7c0, x0

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, csrHook);

            assertEquals(1, csrHook.readCount);
            assertEquals(0, csrHook.writeCount);
            assertEquals(csrHook.readValue, state.regs[2]);
        }
    }

    @Test
    public void csrrsiWithZeroImmediateReadsButDoesNotWriteCsr() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            RecordingCSRHook csrHook = new RecordingCSRHook();
            ram.writeInt(RAM_OFFSET, csrInstruction(0x7c0, 6, 2, 0)); // csrrsi x2, 0x7c0, 0

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, csrHook);

            assertEquals(1, csrHook.readCount);
            assertEquals(0, csrHook.writeCount);
            assertEquals(csrHook.readValue, state.regs[2]);
        }
    }

    @Test
    public void csrrcWithX0SourceReadsButDoesNotWriteCsr() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            RecordingCSRHook csrHook = new RecordingCSRHook();
            ram.writeInt(RAM_OFFSET, csrInstruction(0x7c0, 3, 2, 0)); // csrrc x2, 0x7c0, x0

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, csrHook);

            assertEquals(1, csrHook.readCount);
            assertEquals(0, csrHook.writeCount);
            assertEquals(csrHook.readValue, state.regs[2]);
        }
    }

    @Test
    public void csrrciWithZeroImmediateReadsButDoesNotWriteCsr() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            RecordingCSRHook csrHook = new RecordingCSRHook();
            ram.writeInt(RAM_OFFSET, csrInstruction(0x7c0, 7, 2, 0)); // csrrci x2, 0x7c0, 0

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, csrHook);

            assertEquals(1, csrHook.readCount);
            assertEquals(0, csrHook.writeCount);
            assertEquals(csrHook.readValue, state.regs[2]);
        }
    }

    @Test
    public void ecallTrapPreservesMstatusBitsAndWritesMachineTrapState() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int unrelatedMstatusBits = 0x00020000;
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = unrelatedMstatusBits | 0x08;
            ram.writeInt(RAM_OFFSET, 0x00000073); // ecall

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(11, state.mcause);
            assertEquals(0, state.mtval);
            assertEquals(RAM_OFFSET, state.mepc);
            assertEquals(RAM_OFFSET + 0x80, state.pc);
            assertEquals(unrelatedMstatusBits | 0x80 | 0x1800, state.mstatus);
            assertEquals(3, state.extraflags & 3);
        }
    }

    @Test
    public void mretRestoresPrivilegeAndMstatusBits() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int unrelatedMstatusBits = 0x00020000;
            state.mepc = RAM_OFFSET + 0x20;
            state.mstatus = unrelatedMstatusBits | 0x80 | 0x1800;
            ram.writeInt(RAM_OFFSET, 0x30200073); // mret

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(RAM_OFFSET + 0x20, state.pc);
            assertEquals(unrelatedMstatusBits | 0x80 | 0x08, state.mstatus);
            assertEquals(3, state.extraflags & 3);
        }
    }

    @Test
    public void timerInterruptEntryPreservesMstatusBitsAndWritesMachineTrapState() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int unrelatedMstatusBits = 0x00020000;
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = unrelatedMstatusBits | 0x08; // MIE set
            state.mie = 1 << 7; // MTIE set
            state.setTimer(2);
            state.setTimerMatch(1); // timer already past match; timerMatch != 0 so guard passes

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0x80000007, state.mcause);
            assertEquals(0, state.mtval);
            assertEquals(RAM_OFFSET, state.mepc);
            assertEquals(RAM_OFFSET + 0x80, state.pc);
            assertEquals(unrelatedMstatusBits | 0x80 | 0x1800, state.mstatus);
            assertEquals(3, state.extraflags & 3);
        }
    }

    @Test
    public void timerDoesNotSetMtipWhenMtimeLessThanMtimecmp() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeInt(RAM_OFFSET, 0x00000013); // nop (addi x0, x0, 0)
            state.setTimer(5);
            state.setTimerMatch(10);

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0, state.mip & (1 << 7));
            assertEquals(0, state.mcause);
        }
    }

    @Test
    public void timerSetsMtipWhenMtimeEqualsMtimecmp() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = 0x08; // MIE set
            state.mie = 1 << 7; // MTIE set
            state.setTimer(10);
            state.setTimerMatch(10); // boundary: mtime == mtimecmp must fire

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0x80000007, state.mcause);
        }
    }

    @Test
    public void timerSetsMtipWhenMtimeGreaterThanMtimecmp() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = 0x08;
            state.mie = 1 << 7;
            state.setTimer(11);
            state.setTimerMatch(10);

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0x80000007, state.mcause);
        }
    }

    @Test
    public void wfiWakeupOnTimerFiresInterrupt() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = 0x08; // MIE set (WFI instruction sets this before suspending)
            state.mie = 1 << 7; // MTIE set
            state.extraflags |= 4; // WFI active
            state.setTimer(11);
            state.setTimerMatch(10);

            int result = new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0, state.extraflags & 4); // WFI cleared
            assertEquals(0x80000007, state.mcause);
            assertEquals(RAM_OFFSET + 0x80, state.pc);
            assertEquals(0, result); // normal return, not WFI-still-waiting
        }
    }

    // LR.W / SC.W tests

    @Test
    public void lrwFollowedByScwAtSameAddressSucceeds() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 0x12345678;
            ram.writeInt(dataAddr, 0xdeadbeef);
            ram.writeInt(RAM_OFFSET, amoInstruction(2, 2, 3, 1, 0)); // lr.w x3, (x1)
            ram.writeInt(RAM_OFFSET + 4, amoInstruction(3, 2, 4, 1, 2)); // sc.w x4, x2, (x1)

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 2, null, null);

            assertEquals(0xdeadbeef, state.regs[3]); // lr.w returned original value
            assertEquals(0, state.regs[4]); // sc.w succeeded
            assertEquals(0x12345678, ram.readInt(dataAddr));
        }
    }

    @Test
    public void scwWithoutLrwAtRamBaseFails() {
        // 0x80000000 triggers the old bit-packing bug: (rs1 << 3) overflows to 0
        // and (rs1 & 0x1fffffff) == 0, causing SC.W to spuriously succeed.
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.pc = RAM_OFFSET + 4;
            state.regs[1] = RAM_OFFSET; // address = 0x80000000
            state.regs[2] = 0xdeadbeef;
            ram.writeInt(RAM_OFFSET + 4, amoInstruction(3, 2, 3, 1, 2)); // sc.w x3, x2, (x1)

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(1, state.regs[3]); // must fail: no reservation held
        }
    }

    @Test
    public void scwAtDifferentAddressThanLrwFails() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int addrA = RAM_OFFSET + 0x100;
            int addrB = RAM_OFFSET + 0x200;
            state.regs[1] = addrA;
            state.regs[2] = addrB;
            state.regs[3] = 0x12345678;
            ram.writeInt(addrA, 0xaaaaaaaa);
            ram.writeInt(addrB, 0xbbbbbbbb);
            ram.writeInt(RAM_OFFSET, amoInstruction(2, 2, 4, 1, 0)); // lr.w x4, (x1)
            ram.writeInt(RAM_OFFSET + 4, amoInstruction(3, 2, 5, 2, 3)); // sc.w x5, x3, (x2)

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 2, null, null);

            assertEquals(1, state.regs[5]); // sc.w failed: address mismatch
            assertEquals(0xbbbbbbbb, ram.readInt(addrB)); // addrB not modified
        }
    }

    @Test
    public void scwClearsReservationSoSecondScwFails() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 0x11111111;
            state.regs[3] = 0x22222222;
            ram.writeInt(dataAddr, 0xdeadbeef);
            ram.writeInt(RAM_OFFSET, amoInstruction(2, 2, 4, 1, 0)); // lr.w x4, (x1)
            ram.writeInt(RAM_OFFSET + 4, amoInstruction(3, 2, 5, 1, 2)); // sc.w x5, x2, (x1)
            ram.writeInt(RAM_OFFSET + 8, amoInstruction(3, 2, 6, 1, 3)); // sc.w x6, x3, (x1)

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 3, null, null);

            assertEquals(0, state.regs[5]); // first sc.w succeeded
            assertEquals(1, state.regs[6]); // second sc.w failed: reservation cleared
            assertEquals(0x11111111, ram.readInt(dataAddr)); // only first write committed
        }
    }

    @Test
    public void storeBetweenLrwAndScwClearsReservation() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int lrAddr = RAM_OFFSET + 0x100;
            int storeAddr = RAM_OFFSET + 0x200;
            state.regs[1] = lrAddr;
            state.regs[2] = storeAddr;
            state.regs[3] = 0x99999999;
            state.regs[4] = 0x12345678;
            ram.writeInt(lrAddr, 0xdeadbeef);
            ram.writeInt(RAM_OFFSET, amoInstruction(2, 2, 5, 1, 0)); // lr.w x5, (x1)
            ram.writeInt(RAM_OFFSET + 4, storeInstruction(2, 2, 3, 0)); // sw x3, 0(x2)
            ram.writeInt(RAM_OFFSET + 8, amoInstruction(3, 2, 6, 1, 4)); // sc.w x6, x4, (x1)

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 3, null, null);

            assertEquals(1, state.regs[6]); // sc.w failed: reservation cleared by sw
        }
    }

    // AMO min/max edge-case tests

    @Test
    public void amoMinSignedKeepsSmallerSignedValue() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = -1; // -1 < 1 signed → amomin stores -1
            ram.writeInt(dataAddr, 1);
            ram.writeInt(RAM_OFFSET, amoInstruction(16, 2, 3, 1, 2)); // amomin.w x3, x2, (x1)

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(1, state.regs[3]); // original value returned
            assertEquals(-1, ram.readInt(dataAddr)); // smaller (-1) stored
        }
    }

    @Test
    public void amoMaxSignedKeepsLargerSignedValue() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = Integer.MIN_VALUE; // MIN_VALUE < 1 signed → amomax keeps 1
            ram.writeInt(dataAddr, 1);
            ram.writeInt(RAM_OFFSET, amoInstruction(20, 2, 3, 1, 2)); // amomax.w x3, x2, (x1)

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(1, state.regs[3]); // original value returned
            assertEquals(1, ram.readInt(dataAddr)); // larger (1) kept
        }
    }

    @Test
    public void amoMinUnsignedKeepsSmallerUnsignedValue() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 0xffffffff; // unsigned max → amominu keeps 1
            ram.writeInt(dataAddr, 1);
            ram.writeInt(RAM_OFFSET, amoInstruction(24, 2, 3, 1, 2)); // amominu.w x3, x2, (x1)

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(1, state.regs[3]); // original value returned
            assertEquals(1, ram.readInt(dataAddr)); // smaller unsigned (1) kept
        }
    }

    @Test
    public void amoMaxUnsignedKeepsLargerUnsignedValue() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 0xffffffff; // unsigned max → amomaxu stores 0xffffffff
            ram.writeInt(dataAddr, 1);
            ram.writeInt(RAM_OFFSET, amoInstruction(28, 2, 3, 1, 2)); // amomaxu.w x3, x2, (x1)

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(1, state.regs[3]); // original value returned
            assertEquals(0xffffffff, ram.readInt(dataAddr)); // larger unsigned stored
        }
    }

    @Test
    public void illegalInstructionTrapStoresInstructionBitsInMtval() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int illegalInstruction = 0xffffffff;
            ram.writeInt(RAM_OFFSET, illegalInstruction);

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(2, state.mcause);
            assertEquals(illegalInstruction, state.mtval);
            assertEquals(RAM_OFFSET, state.mepc);
        }
    }

    @Test
    public void invalidOpImmShiftEncodingTrapsWithoutCommittingDestination() {
        assertIllegalInstructionDoesNotCommit(opImmInstruction(0x20, 1, 2, 1), 2);
    }

    @Test
    public void invalidOpFunct7EncodingTrapsWithoutCommittingDestination() {
        assertIllegalInstructionDoesNotCommit(opInstruction(0x10, 0, 2, 1, 1), 2);
    }

    @Test
    public void invalidMExtensionLikeEncodingTrapsWithoutCommittingDestination() {
        assertIllegalInstructionDoesNotCommit(opInstruction(0x03, 0, 2, 1, 1), 2);
    }

    @Test
    public void invalidAtomicFunct3TrapsWithoutCommittingDestination() {
        assertIllegalInstructionDoesNotCommit(amoInstruction(2, 0, 2, 1, 0), 2);
    }

    @Test
    public void unsupportedAtomicOperationTrapsWithoutCommittingDestination() {
        assertIllegalInstructionDoesNotCommit(amoInstruction(5, 2, 2, 1, 0), 2);
    }

    @Test
    public void validNeighboringEncodingsStillExecute() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = RAM_OFFSET + 100;
            state.regs[4] = 10;
            state.regs[5] = 3;
            state.regs[8] = 5;
            ram.writeInt(RAM_OFFSET + 100, 7);
            ram.writeInt(RAM_OFFSET, opImmInstruction(3, 1, 2, 5)); // slli x2, x5, 3
            ram.writeInt(RAM_OFFSET + 4, opInstruction(0x20, 0, 3, 4, 5)); // sub x3, x4, x5
            ram.writeInt(RAM_OFFSET + 8, opInstruction(0x01, 0, 6, 4, 5)); // mul x6, x4, x5
            ram.writeInt(RAM_OFFSET + 12, amoInstruction(0, 2, 7, 1, 8)); // amoadd.w x7, x8, (x1)

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 4, null, null);

            assertEquals(24, state.regs[2]);
            assertEquals(7, state.regs[3]);
            assertEquals(30, state.regs[6]);
            assertEquals(7, state.regs[7]);
            assertEquals(12, ram.readInt(RAM_OFFSET + 100));
        }
    }

    private static void assertIllegalInstructionDoesNotCommit(int instruction, int destinationRegister) {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = RAM_OFFSET + 100;
            state.regs[destinationRegister] = 0x13579bdf;
            ram.writeInt(RAM_OFFSET + 100, 0x2468ace0);
            ram.writeInt(RAM_OFFSET, instruction);

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(2, state.mcause);
            assertEquals(instruction, state.mtval);
            assertEquals(RAM_OFFSET, state.mepc);
            assertEquals(0x13579bdf, state.regs[destinationRegister]);
            assertEquals(0x2468ace0, ram.readInt(RAM_OFFSET + 100));
        }
    }
}
