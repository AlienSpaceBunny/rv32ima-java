package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static int luiInstruction(int rd, int imm20) {
        return ((imm20 & 0xfffff) << 12) | (rd << 7) | 0x37;
    }

    private static int auipcInstruction(int rd, int imm20) {
        return ((imm20 & 0xfffff) << 12) | (rd << 7) | 0x17;
    }

    private static int jalInstruction(int rd, int relImm) {
        return ((relImm & 0x100000) << 11)
                | ((relImm & 0x7fe) << 20)
                | ((relImm & 0x800) << 9)
                | (relImm & 0xff000)
                | (rd << 7)
                | 0x6f;
    }

    private static int jalrInstruction(int rd, int rs1, int imm12) {
        return ((imm12 & 0xfff) << 20) | (rs1 << 15) | (rd << 7) | 0x67;
    }

    private static int branchInstruction(int funct3, int rs1, int rs2, int relImm) {
        return ((relImm & 0x1000) << 19)
                | ((relImm & 0x7e0) << 20)
                | (rs2 << 20)
                | (rs1 << 15)
                | (funct3 << 12)
                | ((relImm & 0x1e) << 7)
                | ((relImm & 0x800) >> 4)
                | 0x63;
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

    // MSIP/MEIP interrupt tests — the U-mode gating rule and non-timer interrupt dispatch added
    // alongside the multi-hart feature work.

    @Test
    public void softwareInterruptTrapsInUserModeWithMstatusMieClear() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = new RV32IMAState(); // extraflags == 0: user mode
            state.pc = RAM_OFFSET;
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = 0; // MIE clear — must not matter while running in user mode
            state.mie = 1 << 3; // MSIE set
            state.mip = 1 << 3; // MSIP pending

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0x80000003, state.mcause);
            assertEquals(RAM_OFFSET, state.mepc);
            assertEquals(RAM_OFFSET + 0x80, state.pc);
            assertEquals(3, state.extraflags & 3); // trap entry always lands in machine mode
        }
    }

    @Test
    public void softwareInterruptDoesNotTrapInMachineModeWithMstatusMieClear() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mstatus = 0; // MIE clear — must mask the interrupt while already in machine mode
            state.mie = 1 << 3;
            state.mip = 1 << 3;
            ram.writeInt(RAM_OFFSET, 0x00000013); // nop

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0, state.mcause);
            assertEquals(RAM_OFFSET + 4, state.pc); // the nop executed; no trap taken
        }
    }

    @Test
    public void softwareInterruptTrapsInMachineModeWithMstatusMieSet() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = 0x08; // MIE set
            state.mie = 1 << 3;
            state.mip = 1 << 3;

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0x80000003, state.mcause);
            assertEquals(RAM_OFFSET + 0x80, state.pc);
        }
    }

    @Test
    public void externalInterruptTrapsInUserMode() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = 0;
            state.mie = 1 << 11; // MEIE set
            state.mip = 1 << 11; // MEIP pending

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0x8000000b, state.mcause);
            assertEquals(RAM_OFFSET + 0x80, state.pc);
        }
    }

    @Test
    public void pendingButDisabledInterruptDoesNotTrapInUserMode() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.mstatus = 0;
            state.mie = 0; // MSIE clear: pending-but-disabled must not trap even in user mode
            state.mip = 1 << 3;
            ram.writeInt(RAM_OFFSET, 0x00000013); // nop

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0, state.mcause);
            assertEquals(RAM_OFFSET + 4, state.pc);
        }
    }

    @Test
    public void externalInterruptTakesPriorityOverSoftwareAndTimer() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = 0x08;
            state.mie = (1 << 3) | (1 << 7) | (1 << 11); // all three enabled
            state.mip = (1 << 3) | (1 << 7) | (1 << 11); // all three pending

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0x8000000b, state.mcause); // MEIP wins
        }
    }

    @Test
    public void softwareInterruptTakesPriorityOverTimerWhenExternalNotPending() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = 0x08;
            state.mie = (1 << 3) | (1 << 7);
            state.mip = (1 << 3) | (1 << 7);

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0x80000003, state.mcause); // MSIP wins over MTIP
        }
    }

    @Test
    public void injectInterruptSetsPendingBitAndWakesFromWfi() {
        RV32IMAState state = new RV32IMAState();
        state.extraflags |= 4; // WFI active

        RV32IMACore.injectInterrupt(state, 3); // MSIP

        assertEquals(1 << 3, state.mip);
        assertEquals(0, state.extraflags & 4); // WFI cleared
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

    // -------------------------------------------------------------------------
    // OP — register-register (remaining funct3 / funct7 combinations)
    // -------------------------------------------------------------------------

    @Test
    public void subSubtracts() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 10;
            state.regs[2] = 3;
            ram.writeInt(RAM_OFFSET, opInstruction(0x20, 0, 3, 1, 2)); // sub x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(7, state.regs[3]);
        }
    }

    @Test
    public void sllShiftsLeftByRegister() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 1;
            state.regs[2] = 4;
            ram.writeInt(RAM_OFFSET, opInstruction(0, 1, 3, 1, 2)); // sll x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(16, state.regs[3]);
        }
    }

    @Test
    public void sltSignedComparison() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = -1; // -1 < 0 signed
            state.regs[2] = 0;
            ram.writeInt(RAM_OFFSET, opInstruction(0, 2, 3, 1, 2)); // slt x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(1, state.regs[3]);
        }
    }

    @Test
    public void sltuUnsignedComparison() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0; // 0 < 0xFFFFFFFF unsigned
            state.regs[2] = 0xffffffff;
            ram.writeInt(RAM_OFFSET, opInstruction(0, 3, 3, 1, 2)); // sltu x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(1, state.regs[3]);
        }
    }

    @Test
    public void xorXorsBits() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0x0f0f0f0f;
            state.regs[2] = 0xffffffff;
            ram.writeInt(RAM_OFFSET, opInstruction(0, 4, 3, 1, 2)); // xor x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0xf0f0f0f0, state.regs[3]);
        }
    }

    @Test
    public void srlShiftsRightLogical() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0x80000010;
            state.regs[2] = 4;
            ram.writeInt(RAM_OFFSET, opInstruction(0, 5, 3, 1, 2)); // srl x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0x08000001, state.regs[3]);
        }
    }

    @Test
    public void sraShiftsRightArithmetic() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0x80000010;
            state.regs[2] = 4;
            ram.writeInt(RAM_OFFSET, opInstruction(0x20, 5, 3, 1, 2)); // sra x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0xf8000001, state.regs[3]); // sign-filled
        }
    }

    @Test
    public void orOrsBits() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0x00ff0000;
            state.regs[2] = 0x0000ff00;
            ram.writeInt(RAM_OFFSET, opInstruction(0, 6, 3, 1, 2)); // or x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0x00ffff00, state.regs[3]);
        }
    }

    @Test
    public void andAndsBits() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0x0f0f0f0f;
            state.regs[2] = 0x00ff00ff;
            ram.writeInt(RAM_OFFSET, opInstruction(0, 7, 3, 1, 2)); // and x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0x000f000f, state.regs[3]);
        }
    }

    // -------------------------------------------------------------------------
    // Misc
    // -------------------------------------------------------------------------

    @Test
    public void writeToX0IsDiscarded() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 42;
            ram.writeInt(RAM_OFFSET, opImmInstruction(1, 0, 0, 1)); // addi x0, x1, 1
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0, state.regs[0]);
        }
    }

    @Test
    public void fenceIsNoOp() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeInt(RAM_OFFSET, 0x0ff0000f); // fence iorw, iorw
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0, state.mcause);
            assertEquals(RAM_OFFSET + 4, state.pc);
        }
    }

    @Test
    public void ebreakRaisesBreakpointTrap() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeInt(RAM_OFFSET, 0x00100073); // ebreak
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(3, state.mcause);
            assertEquals(RAM_OFFSET, state.mepc);
        }
    }

    // -------------------------------------------------------------------------
    // RV32M — edge cases
    // -------------------------------------------------------------------------

    @Test
    public void mulhSignedUpperHalf() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0x80000000; // Integer.MIN_VALUE
            state.regs[2] = 2;
            // mulh x3, x1, x2 — signed * signed upper 32 bits
            ram.writeInt(RAM_OFFSET, opInstruction(1, 1, 3, 1, 2));
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            long expected = ((long) 0x80000000 * 2L) >> 32;
            assertEquals((int) expected, state.regs[3]);
        }
    }

    @Test
    public void mulhsuMixedSignUpperHalf() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = -1; // signed -1
            state.regs[2] = 0xffffffff; // unsigned max
            // mulhsu x3, x1, x2 — signed * unsigned upper 32 bits
            ram.writeInt(RAM_OFFSET, opInstruction(1, 2, 3, 1, 2));
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            long expected = ((long) -1 * Integer.toUnsignedLong(0xffffffff)) >> 32;
            assertEquals((int) expected, state.regs[3]);
        }
    }

    @Test
    public void mulhuUnsignedUpperHalf() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0xffffffff;
            state.regs[2] = 0xffffffff;
            // mulhu x3, x1, x2 — unsigned * unsigned upper 32 bits
            ram.writeInt(RAM_OFFSET, opInstruction(1, 3, 3, 1, 2));
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            long expected = (Integer.toUnsignedLong(0xffffffff) * Integer.toUnsignedLong(0xffffffff)) >>> 32;
            assertEquals((int) expected, state.regs[3]);
        }
    }

    @Test
    public void divByZeroReturnsMinusOne() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 42;
            state.regs[2] = 0;
            ram.writeInt(RAM_OFFSET, opInstruction(1, 4, 3, 1, 2)); // div x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(-1, state.regs[3]);
        }
    }

    @Test
    public void divuByZeroReturnsMaxUnsigned() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 42;
            state.regs[2] = 0;
            ram.writeInt(RAM_OFFSET, opInstruction(1, 5, 3, 1, 2)); // divu x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0xffffffff, state.regs[3]);
        }
    }

    @Test
    public void divSignedOverflowMinValueByMinusOne() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = Integer.MIN_VALUE;
            state.regs[2] = -1;
            ram.writeInt(RAM_OFFSET, opInstruction(1, 4, 3, 1, 2)); // div x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(Integer.MIN_VALUE, state.regs[3]); // overflow result = dividend
        }
    }

    @Test
    public void remByZeroReturnsDividend() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 42;
            state.regs[2] = 0;
            ram.writeInt(RAM_OFFSET, opInstruction(1, 6, 3, 1, 2)); // rem x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(42, state.regs[3]);
        }
    }

    @Test
    public void remuByZeroReturnsDividend() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 42;
            state.regs[2] = 0;
            ram.writeInt(RAM_OFFSET, opInstruction(1, 7, 3, 1, 2)); // remu x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(42, state.regs[3]);
        }
    }

    @Test
    public void remSignedOverflowMinValueByMinusOne() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = Integer.MIN_VALUE;
            state.regs[2] = -1;
            ram.writeInt(RAM_OFFSET, opInstruction(1, 6, 3, 1, 2)); // rem x3, x1, x2
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0, state.regs[3]); // overflow result = 0
        }
    }

    // -------------------------------------------------------------------------
    // RV32A — basic correctness for remaining AMO operations
    // -------------------------------------------------------------------------

    @Test
    public void amoswapWritesNewValueAndReturnsOld() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 0xdeadbeef;
            ram.writeInt(dataAddr, 0x12345678);
            ram.writeInt(RAM_OFFSET, amoInstruction(1, 2, 3, 1, 2)); // amoswap.w x3,x2,(x1)
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0x12345678, state.regs[3]);
            assertEquals(0xdeadbeef, ram.readInt(dataAddr));
        }
    }

    @Test
    public void amoaddAddsAndReturnsOld() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 10;
            ram.writeInt(dataAddr, 5);
            ram.writeInt(RAM_OFFSET, amoInstruction(0, 2, 3, 1, 2)); // amoadd.w x3,x2,(x1)
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(5, state.regs[3]);
            assertEquals(15, ram.readInt(dataAddr));
        }
    }

    @Test
    public void amoxorXorsAndReturnsOld() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 0x0f0f0f0f;
            ram.writeInt(dataAddr, 0xff00ff00);
            ram.writeInt(RAM_OFFSET, amoInstruction(4, 2, 3, 1, 2)); // amoxor.w x3,x2,(x1)
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0xff00ff00, state.regs[3]);
            assertEquals(0xf00ff00f, ram.readInt(dataAddr));
        }
    }

    @Test
    public void amoandAndsAndReturnsOld() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 0x0f0f0f0f;
            ram.writeInt(dataAddr, 0xff00ff00);
            ram.writeInt(RAM_OFFSET, amoInstruction(12, 2, 3, 1, 2)); // amoand.w x3,x2,(x1)
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0xff00ff00, state.regs[3]);
            assertEquals(0x0f000f00, ram.readInt(dataAddr));
        }
    }

    @Test
    public void amoorOrsAndReturnsOld() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 0x0f0f0f0f;
            ram.writeInt(dataAddr, 0xff00ff00);
            ram.writeInt(RAM_OFFSET, amoInstruction(8, 2, 3, 1, 2)); // amoor.w x3,x2,(x1)
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0xff00ff00, state.regs[3]);
            assertEquals(0xff0fff0f, ram.readInt(dataAddr));
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

    // -------------------------------------------------------------------------
    // Loads — sign/zero extension
    // -------------------------------------------------------------------------

    @Test
    public void lbSignExtendsNegativeByte() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            ram.writeByte(dataAddr, (byte) 0xff);
            ram.writeInt(RAM_OFFSET, loadInstruction(0, 2, 1, 0)); // lb x2, 0(x1)
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(-1, state.regs[2]);
        }
    }

    @Test
    public void lbuZeroExtendsByte() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            ram.writeByte(dataAddr, (byte) 0xff);
            ram.writeInt(RAM_OFFSET, loadInstruction(4, 2, 1, 0)); // lbu x2, 0(x1)
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0xff, state.regs[2]);
        }
    }

    @Test
    public void lhSignExtendsNegativeHalfword() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            ram.writeShort(dataAddr, (short) 0x8000);
            ram.writeInt(RAM_OFFSET, loadInstruction(1, 2, 1, 0)); // lh x2, 0(x1)
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0xffff8000, state.regs[2]);
        }
    }

    @Test
    public void lhuZeroExtendsHalfword() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            ram.writeShort(dataAddr, (short) 0x8000);
            ram.writeInt(RAM_OFFSET, loadInstruction(5, 2, 1, 0)); // lhu x2, 0(x1)
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0x8000, state.regs[2]);
        }
    }

    // -------------------------------------------------------------------------
    // Stores — byte/halfword
    // -------------------------------------------------------------------------

    @Test
    public void sbWritesLowByte() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 0xdeadbeab;
            ram.writeInt(dataAddr, 0x12345678);
            ram.writeInt(RAM_OFFSET, storeInstruction(0, 1, 2, 0)); // sb x2, 0(x1)
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals((byte) 0xab, ram.readByte(dataAddr));
            assertEquals((byte) 0x56, ram.readByte(dataAddr + 1)); // untouched
        }
    }

    @Test
    public void shWritesLowHalfword() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 0xdeadabcd;
            ram.writeInt(dataAddr, 0x12345678);
            ram.writeInt(RAM_OFFSET, storeInstruction(1, 1, 2, 0)); // sh x2, 0(x1)
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals((short) 0xabcd, ram.readShort(dataAddr));
            assertEquals((short) 0x1234, ram.readShort(dataAddr + 2)); // untouched
        }
    }

    // -------------------------------------------------------------------------
    // OP-IMM — remaining funct3 values
    // -------------------------------------------------------------------------

    @Test
    public void addiNegativeImmediate() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 10;
            ram.writeInt(RAM_OFFSET, opImmInstruction(-3, 0, 2, 1)); // addi x2, x1, -3
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(7, state.regs[2]);
        }
    }

    @Test
    public void sltiSignedComparison() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = -1; // -1 < 0 signed → rd = 1
            ram.writeInt(RAM_OFFSET, opImmInstruction(0, 2, 2, 1)); // slti x2, x1, 0
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(1, state.regs[2]);
        }
    }

    @Test
    public void sltiuUnsignedComparison() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0; // 0 < 1 unsigned → rd = 1
            ram.writeInt(RAM_OFFSET, opImmInstruction(1, 3, 2, 1)); // sltiu x2, x1, 1
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(1, state.regs[2]);
        }
    }

    @Test
    public void xoriFlipsBits() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0x0f0f0f0f;
            // xori x2, x1, -1  (imm = 0xFFF sign-extended = all ones → NOT operation)
            ram.writeInt(RAM_OFFSET, opImmInstruction(-1, 4, 2, 1));
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0xf0f0f0f0, state.regs[2]);
        }
    }

    @Test
    public void oriSetsBits() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0x00ff00ff;
            ram.writeInt(RAM_OFFSET, opImmInstruction(0x0f0, 6, 2, 1)); // ori x2, x1, 0xf0
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0x00ff00ff | 0xf0, state.regs[2]);
        }
    }

    @Test
    public void andiClearsBits() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0xffffffff;
            ram.writeInt(RAM_OFFSET, opImmInstruction(0x0ff, 7, 2, 1)); // andi x2, x1, 0xff
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0xff, state.regs[2]);
        }
    }

    @Test
    public void slliShiftsLeft() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 1;
            ram.writeInt(RAM_OFFSET, opImmInstruction(3, 1, 2, 1)); // slli x2, x1, 3
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(8, state.regs[2]);
        }
    }

    @Test
    public void srliShiftsRightLogical() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0x80000008;
            ram.writeInt(RAM_OFFSET, opImmInstruction(3, 5, 2, 1)); // srli x2, x1, 3
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0x10000001, state.regs[2]); // zero-fills from left
        }
    }

    @Test
    public void sraiShiftsRightArithmetic() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0x80000008;
            ram.writeInt(RAM_OFFSET, opImmInstruction(0x403, 5, 2, 1)); // srai x2, x1, 3
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0xf0000001, state.regs[2]); // sign-fills from left
        }
    }

    // -------------------------------------------------------------------------
    // LUI / AUIPC
    // -------------------------------------------------------------------------

    @Test
    public void luiLoadsUpperImmediate() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeInt(RAM_OFFSET, luiInstruction(1, 0xabcde)); // lui x1, 0xabcde
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(0xabcde000, state.regs[1]);
        }
    }

    @Test
    public void auipcAddsUpperImmediateToPC() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.pc = RAM_OFFSET + 8;
            ram.writeInt(RAM_OFFSET + 8, auipcInstruction(1, 1)); // auipc x1, 1
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 8 + 0x1000, state.regs[1]);
        }
    }

    // -------------------------------------------------------------------------
    // JAL / JALR
    // -------------------------------------------------------------------------

    @Test
    public void jalLinksAndJumpsForward() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            // jal x1, +12  (skip two slots, land at RAM_OFFSET+12)
            ram.writeInt(RAM_OFFSET, jalInstruction(1, 12));
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 4, state.regs[1]); // return address
            assertEquals(RAM_OFFSET + 12, state.pc);
        }
    }

    @Test
    public void jalLinksAndJumpsBackward() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.pc = RAM_OFFSET + 8;
            // jal x1, -4  (jump back 4 bytes to RAM_OFFSET+4)
            ram.writeInt(RAM_OFFSET + 8, jalInstruction(1, -4));
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 12, state.regs[1]);
            assertEquals(RAM_OFFSET + 4, state.pc);
        }
    }

    @Test
    public void jalrJumpsToRegisterPlusOffset() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[2] = RAM_OFFSET + 0x100;
            // jalr x1, x2, 8
            ram.writeInt(RAM_OFFSET, jalrInstruction(1, 2, 8));
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 4, state.regs[1]);
            assertEquals(RAM_OFFSET + 0x108, state.pc);
        }
    }

    @Test
    public void jalrClearsLowBitOfTarget() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[2] = RAM_OFFSET + 0x101; // odd address
            ram.writeInt(RAM_OFFSET, jalrInstruction(1, 2, 0));
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 0x100, state.pc); // low bit cleared
        }
    }

    // -------------------------------------------------------------------------
    // Branches
    // -------------------------------------------------------------------------

    @Test
    public void beqTakenWhenEqual() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 5;
            state.regs[2] = 5;
            ram.writeInt(RAM_OFFSET, branchInstruction(0, 1, 2, 8)); // beq x1,x2,+8
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 8, state.pc);
        }
    }

    @Test
    public void beqNotTakenWhenNotEqual() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 5;
            state.regs[2] = 6;
            ram.writeInt(RAM_OFFSET, branchInstruction(0, 1, 2, 8));
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 4, state.pc); // fall-through
        }
    }

    @Test
    public void bneTakenWhenNotEqual() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 5;
            state.regs[2] = 6;
            ram.writeInt(RAM_OFFSET, branchInstruction(1, 1, 2, 8)); // bne
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 8, state.pc);
        }
    }

    @Test
    public void bltTakenWhenLessThanSigned() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = -1; // -1 < 1 signed
            state.regs[2] = 1;
            ram.writeInt(RAM_OFFSET, branchInstruction(4, 1, 2, 8)); // blt
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 8, state.pc);
        }
    }

    @Test
    public void bltNotTakenWhenGreaterSigned() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 1;
            state.regs[2] = -1; // 1 > -1 signed, blt not taken
            ram.writeInt(RAM_OFFSET, branchInstruction(4, 1, 2, 8));
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 4, state.pc);
        }
    }

    @Test
    public void bgeTakenWhenGreaterOrEqualSigned() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 5;
            state.regs[2] = 5;
            ram.writeInt(RAM_OFFSET, branchInstruction(5, 1, 2, 8)); // bge
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 8, state.pc);
        }
    }

    @Test
    public void bltuTakenWhenLessThanUnsigned() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 1; // 1 < 0xFFFFFFFF unsigned
            state.regs[2] = 0xffffffff;
            ram.writeInt(RAM_OFFSET, branchInstruction(6, 1, 2, 8)); // bltu
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 8, state.pc);
        }
    }

    @Test
    public void bgeuTakenWhenGreaterOrEqualUnsigned() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.regs[1] = 0xffffffff; // 0xFFFFFFFF >= 1 unsigned
            state.regs[2] = 1;
            ram.writeInt(RAM_OFFSET, branchInstruction(7, 1, 2, 8)); // bgeu
            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);
            assertEquals(RAM_OFFSET + 8, state.pc);
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

    // IsaConfig / misa / hartId tests (Phase 1 foundation work).

    @Test
    public void defaultConstructorMisaMatchesBaseIsaConfig() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeInt(RAM_OFFSET, csrInstruction(0x301, 2, 5, 0)); // csrrs x5, misa, x0

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(IsaConfig.RV32IMA_ZICSR.misa(), state.regs[5]);
        }
    }

    @Test
    public void isaConfigConstructorRejectsNull() {
        assertThrows(NullPointerException.class, () -> new RV32IMACore(null));
    }

    @Test
    public void isaConfigConstructorMisaReflectsConfig() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            ram.writeInt(RAM_OFFSET, csrInstruction(0x301, 2, 5, 0)); // csrrs x5, misa, x0

            new RV32IMACore(IsaConfig.RV32IMFC_ZBA_ZBB_ZICSR).step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(IsaConfig.RV32IMFC_ZBA_ZBB_ZICSR.misa(), state.regs[5]);
        }
    }

    @Test
    public void hartIdDefaultsToZeroAndIsNotTouchedByStep() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState defaultState = new RV32IMAState();
            assertEquals(0, defaultState.hartId);

            RV32IMAState state = machineState();
            state.hartId = 7;
            ram.writeInt(RAM_OFFSET, 0x00000013); // nop

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(7, state.hartId);
        }
    }

    // Instruction-fetch IndexOutOfBoundsException handling (Phase 1 foundation work).

    /**
     * Wraps a {@link MemoryBus} and rejects a single fetch address, to simulate a bus that
     * enforces access control finer-grained than the {@code ramOffset}/{@code ramSize} window
     * (for example, an AP MPU bus), independent of the coarse window check.
     */
    private static final class FaultingFetchBus implements MemoryBus {
        private final MemoryBus delegate;
        private final int faultAddress;

        FaultingFetchBus(MemoryBus delegate, int faultAddress) {
            this.delegate = delegate;
            this.faultAddress = faultAddress;
        }

        @Override
        public byte readByte(int address) {
            return delegate.readByte(address);
        }

        @Override
        public short readShort(int address) {
            return delegate.readShort(address);
        }

        @Override
        public int readInt(int address) {
            if (address == faultAddress) {
                throw new IndexOutOfBoundsException("fetch rejected: " + Integer.toHexString(address));
            }
            return delegate.readInt(address);
        }

        @Override
        public void writeByte(int address, byte value) {
            delegate.writeByte(address, value);
        }

        @Override
        public void writeShort(int address, short value) {
            delegate.writeShort(address, value);
        }

        @Override
        public void writeInt(int address, int value) {
            delegate.writeInt(address, value);
        }
    }

    @Test
    public void fetchIndexOutOfBoundsWithinWindowBecomesInstructionAccessFaultTrap() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            ram.writeInt(RAM_OFFSET, 0x00000013); // nop -- never actually fetched
            MemoryBus faultingBus = new FaultingFetchBus(ram, RAM_OFFSET);

            new RV32IMACore().step(state, faultingBus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(1, state.mcause); // instruction access fault
            assertEquals(RAM_OFFSET, state.mtval); // faulting PC
            assertEquals(RAM_OFFSET, state.mepc);
            assertEquals(RAM_OFFSET + 0x80, state.pc);
            assertEquals(3, state.extraflags & 3); // trap entry always lands in machine mode
        }
    }

    @Test
    public void fetchIndexOutOfBoundsMidLoopFaultsAtCorrectPc() {
        // Simulates an AP MPU-style bus that allows the first couple of instructions and then
        // rejects a later one -- not the same as faulting on the very first fetch, and a
        // different code path could plausibly get mepc/mtval off by one instruction here.
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            ram.writeInt(RAM_OFFSET, 0x00000013); // nop
            ram.writeInt(RAM_OFFSET + 4, 0x00000013); // nop
            ram.writeInt(RAM_OFFSET + 8, 0x00000013); // never actually fetched
            MemoryBus faultingBus = new FaultingFetchBus(ram, RAM_OFFSET + 8);

            new RV32IMACore().step(state, faultingBus, RAM_OFFSET, ramSize, 0, 5, null, null);

            assertEquals(1, state.mcause);
            assertEquals(RAM_OFFSET + 8, state.mtval);
            assertEquals(RAM_OFFSET + 8, state.mepc);
            assertEquals(RAM_OFFSET + 0x80, state.pc);
        }
    }

    @Test
    public void fetchIndexOutOfBoundsDoesNotInvokePostExec() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            ram.writeInt(RAM_OFFSET, 0x00000013);
            MemoryBus faultingBus = new FaultingFetchBus(ram, RAM_OFFSET);
            int[] postExecCalls = {0};

            new RV32IMACore()
                    .step(state, faultingBus, RAM_OFFSET, ramSize, 0, 1, (pc, ir, trap) -> postExecCalls[0]++, null);

            assertEquals(0, postExecCalls[0]);
        }
    }

    // U-mode CSR access privilege check (Phase 2, Design Decision §7).

    @Test
    public void userModeCannotReadMachineOnlyCsr() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = new RV32IMAState(); // extraflags == 0: user mode
            state.pc = RAM_OFFSET;
            state.mstatus = 0x12345678; // any value; must be unreadable, not just unwritable
            ram.writeInt(RAM_OFFSET, csrInstruction(0x300, 2, 5, 0)); // csrrs x5, mstatus, x0

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(2, state.mcause); // illegal instruction
            assertEquals(0, state.regs[5]); // never written
        }
    }

    @Test
    public void userModeCannotWriteMachineOnlyCsr() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.mtvec = 0x11111111;
            state.regs[1] = 0x22222222;
            ram.writeInt(RAM_OFFSET, csrInstruction(0x305, 1, 0, 1)); // csrrw x0, mtvec, x1

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(2, state.mcause);
            assertEquals(0x11111111, state.mtvec); // never written
        }
    }

    @Test
    public void userModeCanReadUserAccessibleCsr() {
        // 0xC00 (cycle) has privilege field 0b00 in its address encoding -- must pass in user
        // mode, unlike M-mode-only CSRs such as mstatus.
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.setCycle(42);
            ram.writeInt(RAM_OFFSET, csrInstruction(0xC00, 2, 5, 0)); // csrrs x5, cycle, x0

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0, state.mcause);
            assertEquals(43, state.regs[5]); // cycle increments once per instruction before fetch
        }
    }

    @Test
    public void machineModeCanStillAccessMachineOnlyCsr() {
        // No regression: the existing (M-mode) behavior of every other CSR test in this file
        // depends on this continuing to work.
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = 0x11111111;
            state.regs[1] = 0x22222222;
            ram.writeInt(RAM_OFFSET, csrInstruction(0x305, 1, 0, 1)); // csrrw x0, mtvec, x1

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0, state.mcause);
            assertEquals(0x22222222, state.mtvec);
        }
    }

    // Regressions for the V-32 CPU integration review (docs/CPU_INTEGRATION_RESPONSE.md).

    @Test
    public void mretInUserModeIsIllegalEvenWithMppMachine() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.extraflags &= ~3; // U-mode
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = 0x1800; // MPP = M: must not matter
            state.mepc = RAM_OFFSET + 0x40;
            ram.writeInt(RAM_OFFSET, 0x30200073); // mret

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(2, state.mcause);
            assertEquals(0x30200073, state.mtval);
            assertEquals(RAM_OFFSET, state.mepc);
            assertEquals(RAM_OFFSET + 0x80, state.pc);
            assertEquals(3, state.extraflags & 3); // trap entry, not the MRET's MPP restore
            assertEquals(0, (state.mstatus & 0x1800)); // MPP recorded the U-mode origin
        }
    }

    @Test
    public void systemInstructionsWithNonZeroRdOrRs1AreIllegal() {
        int ramSize = 1024;
        int[] encodings = {
            0x30200073 | (1 << 7), // mret with rd = x1
            0x30200073 | (1 << 15), // mret with rs1 = x1
            0x00000073 | (2 << 7), // ecall with rd = x2
            0x10500073 | (3 << 15), // wfi with rs1 = x3
        };
        for (int encoding : encodings) {
            try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
                RV32IMAState state = machineState();
                state.mtvec = RAM_OFFSET + 0x80;
                ram.writeInt(RAM_OFFSET, encoding);

                new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

                assertEquals(2, state.mcause, Integer.toHexString(encoding));
                assertEquals(encoding, state.mtval);
            }
        }
    }

    @Test
    public void wfiWithSoftwareInterruptAlreadyPendingDoesNotStall() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mie = 1 << 3; // MSIE
            state.mstatus = 0; // global MIE clear: the interrupt was masked until WFI
            RV32IMACore.injectInterrupt(state, 3); // MSIP pending before WFI executes
            ram.writeInt(RAM_OFFSET, 0x10500073); // wfi

            int first = new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0, first); // completed immediately, no stall
            assertEquals(0, state.extraflags & 4);
            assertEquals(RAM_OFFSET + 4, state.pc);

            int second = new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0, second);
            assertEquals(0x80000003, state.mcause);
            assertEquals(RAM_OFFSET + 4, state.mepc); // resumes after the WFI
            assertEquals(RAM_OFFSET + 0x80, state.pc);
        }
    }

    @Test
    public void pendingBitSetDirectlyOnMipWakesStalledHart() {
        // An embedder may set mip directly rather than via injectInterrupt (which also clears the
        // WFI flag); the stall must still end once the interrupt is deliverable.
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = 0x08;
            state.mie = 1 << 11; // MEIE
            state.extraflags |= 4; // stalled in WFI
            state.pc = RAM_OFFSET + 4;

            assertEquals(1, new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null));

            state.mip |= 1 << 11; // MEIP, without injectInterrupt
            int result = new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0, result);
            assertEquals(0, state.extraflags & 4);
            assertEquals(0x8000000b, state.mcause);
            assertEquals(RAM_OFFSET + 4, state.mepc);
        }
    }

    @Test
    public void stalledHartStaysStalledWhilePendingInterruptIsNotEnabled() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mstatus = 0x08;
            state.mie = 0; // nothing enabled
            state.mip = 1 << 3;
            state.extraflags |= 4;

            assertEquals(1, new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 1, null, null));
            assertEquals(4, state.extraflags & 4);
        }
    }

    @Test
    public void enablingInterruptThenWfiInOneBatchWithPendingBitDoesNotStall() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mstatus = 0;
            state.mip = 1 << 3; // MSIP pending, but MSIE not yet set
            state.regs[1] = 1 << 3;
            ram.writeInt(RAM_OFFSET, csrInstruction(0x304, 2, 0, 1)); // csrs mie, x1
            ram.writeInt(RAM_OFFSET + 4, 0x10500073); // wfi

            int result = new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 2, null, null);

            assertEquals(0, result);
            assertEquals(0, state.extraflags & 4);
            assertEquals(RAM_OFFSET + 8, state.pc);
        }
    }

    // In-batch interrupt reevaluation after interrupt-affecting CSR writes and MRET (V-32
    // follow-up review, docs/CPU_INTEGRATION_RESPONSE_2.md request 2). Every case runs with
    // count > 1 and asserts the instruction after the boundary did NOT execute.

    private static final int ADDI_X4_1 = 0x00100213; // addi x4, x0, 1

    @Test
    public void csrWriteEnablingMstatusMieDeliversPendingInterruptBeforeNextInstruction() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mip = 1 << 3;
            state.mie = 1 << 3;
            state.mstatus = 0;
            state.regs[1] = 8;
            ram.writeInt(RAM_OFFSET, 0x3000a073); // csrs mstatus, x1
            ram.writeInt(RAM_OFFSET + 4, ADDI_X4_1);

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 2, null, null);

            assertEquals(0, state.regs[4]); // addi did not run
            assertEquals(0x80000003, state.mcause);
            assertEquals(RAM_OFFSET + 4, state.mepc);
            assertEquals(RAM_OFFSET + 0x80, state.pc);
            assertEquals(1, state.getCycle()); // only the csrs retired
            assertEquals(0x1880, state.mstatus); // MPIE=1 (MIE was 1), MIE=0, MPP=M
        }
    }

    @Test
    public void csrWriteEnablingMieBitWithMstatusMieAlreadySetDeliversBeforeNextInstruction() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mip = 1 << 11;
            state.mie = 0;
            state.mstatus = 0x08;
            state.regs[1] = 1 << 11;
            ram.writeInt(RAM_OFFSET, csrInstruction(0x304, 2, 0, 1)); // csrs mie, x1
            ram.writeInt(RAM_OFFSET + 4, ADDI_X4_1);

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 2, null, null);

            assertEquals(0, state.regs[4]);
            assertEquals(0x8000000b, state.mcause);
            assertEquals(RAM_OFFSET + 4, state.mepc);
        }
    }

    @Test
    public void csrWriteSettingPendingBitInMipDeliversBeforeNextInstruction() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mip = 0;
            state.mie = 1 << 3;
            state.mstatus = 0x08;
            state.regs[1] = 1 << 3;
            ram.writeInt(RAM_OFFSET, csrInstruction(0x344, 2, 0, 1)); // csrs mip, x1
            ram.writeInt(RAM_OFFSET + 4, ADDI_X4_1);

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 2, null, null);

            assertEquals(0, state.regs[4]);
            assertEquals(0x80000003, state.mcause);
            assertEquals(RAM_OFFSET + 4, state.mepc);
        }
    }

    @Test
    public void csrWriteReevaluationPreservesInterruptPriority() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mip = (1 << 3) | (1 << 11);
            state.mie = (1 << 3) | (1 << 11);
            state.mstatus = 0;
            ram.writeInt(RAM_OFFSET, csrInstruction(0x300, 6, 0, 8)); // csrsi mstatus, 8
            ram.writeInt(RAM_OFFSET + 4, ADDI_X4_1);

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 2, null, null);

            assertEquals(0, state.regs[4]);
            assertEquals(0x8000000b, state.mcause); // external beats software
        }
    }

    @Test
    public void csrWriteWithNothingDeliverableDoesNotEndTheBatch() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mip = 1 << 3;
            state.mie = 0; // pending but not enabled
            state.regs[1] = 8;
            ram.writeInt(RAM_OFFSET, 0x3000a073); // csrs mstatus, x1
            ram.writeInt(RAM_OFFSET + 4, ADDI_X4_1);

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 2, null, null);

            assertEquals(1, state.regs[4]);
            assertEquals(0, state.mcause);
            assertEquals(RAM_OFFSET + 8, state.pc);
            assertEquals(2, state.getCycle());
        }
    }

    @Test
    public void mretToUserModeDeliversPendingInterruptBeforeTargetInstruction() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mip = 1 << 3;
            state.mie = 1 << 3;
            state.mstatus = 0; // MIE=0, MPIE=0, MPP=U: still deliverable in U-mode
            state.mepc = RAM_OFFSET + 0x40;
            ram.writeInt(RAM_OFFSET, 0x30200073); // mret
            ram.writeInt(RAM_OFFSET + 0x40, ADDI_X4_1);

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 2, null, null);

            assertEquals(0, state.regs[4]); // target instruction waited
            assertEquals(0x80000003, state.mcause);
            assertEquals(RAM_OFFSET + 0x40, state.mepc);
            assertEquals(RAM_OFFSET + 0x80, state.pc);
            assertEquals(3, state.extraflags & 3); // trap entered M-mode
            assertEquals(0, state.mstatus & 0x1800); // MPP records the U-mode it was returning to
            assertEquals(1, state.getCycle());
        }
    }

    @Test
    public void mretReenablingMachineInterruptsDeliversBeforeTargetInstruction() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mip = 1 << 11;
            state.mie = 1 << 11;
            state.mstatus = 0x80 | 0x1800; // MPIE=1, MPP=M -> MRET sets MIE, stays in M
            state.mepc = RAM_OFFSET + 0x40;
            ram.writeInt(RAM_OFFSET, 0x30200073); // mret
            ram.writeInt(RAM_OFFSET + 0x40, ADDI_X4_1);

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 2, null, null);

            assertEquals(0, state.regs[4]);
            assertEquals(0x8000000b, state.mcause);
            assertEquals(RAM_OFFSET + 0x40, state.mepc);
            assertEquals(0x1880, state.mstatus); // MPIE=1 (from MIE=1), MIE=0, MPP=M
        }
    }

    @Test
    public void mretToCompressedTargetDeliversWithExactTargetMepc() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mtvec = RAM_OFFSET + 0x80;
            state.mip = 1 << 3;
            state.mie = 1 << 3;
            state.mstatus = 0; // return to U-mode
            state.mepc = RAM_OFFSET + 0x42; // halfword-aligned compressed target
            ram.writeInt(RAM_OFFSET, 0x30200073); // mret
            ram.writeShort(RAM_OFFSET + 0x42, (short) 0x0205); // c.addi x4, 1

            new RV32IMACore(IsaConfig.RV32IMC_ZBB_ZICSR).step(state, ram, RAM_OFFSET, ramSize, 0, 2, null, null);

            assertEquals(0, state.regs[4]);
            assertEquals(0x80000003, state.mcause);
            assertEquals(RAM_OFFSET + 0x42, state.mepc);
        }
    }

    @Test
    public void mretWithNothingDeliverableContinuesAtTarget() {
        int ramSize = 1024;
        try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RV32IMAState state = machineState();
            state.mip = 1 << 3;
            state.mie = 0;
            state.mstatus = 0x80 | 0x1800;
            state.mepc = RAM_OFFSET + 0x40;
            ram.writeInt(RAM_OFFSET, 0x30200073); // mret
            ram.writeInt(RAM_OFFSET + 0x40, ADDI_X4_1);

            new RV32IMACore().step(state, ram, RAM_OFFSET, ramSize, 0, 2, null, null);

            assertEquals(1, state.regs[4]);
            assertEquals(0, state.mcause);
            assertEquals(RAM_OFFSET + 0x44, state.pc);
        }
    }
}
