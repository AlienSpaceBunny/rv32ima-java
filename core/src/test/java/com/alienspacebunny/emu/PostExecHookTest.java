package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Verifies the PC that {@link RV32IMACore.PostExecHook} reports for control transfers: a taken or
 * untaken jump, branch or MRET is reported at its own PC, never at its target. Each case runs the
 * transfer and then one instruction at its destination in a single batch, so the second event
 * also checks where execution went. Also verifies that an executed {@code WFI} calls the hook
 * once, stalling or not, and a check of an existing stall does not. The suite reruns with the
 * decoded cache on (see {@link TestCores}), which covers the cached path's fast handlers and its
 * delegation.
 */
public class PostExecHookTest {
    private static final int RAM_OFFSET = 0x80000000;
    private static final int RAM_SIZE = 256;
    private static final int TARGET = RAM_OFFSET + 0x40;
    private static final IsaConfig HAS_C = new IsaConfig(true, false, false, false, false);
    private static final int NOP = 0x00000013; // ADDI x0, x0, 0
    private static final int C_NOP = 0x0001;
    private static final int MRET = 0x30200073;
    private static final int WFI = 0x10500073;
    private static final int MSIP = 1 << 3;

    private record Event(int pc, int trap) {}

    // ---- Encoders, written from the instruction tables ----

    private static int jal(int rd, int imm) {
        return ((imm & 0x100000) << 11)
                | ((imm & 0x7fe) << 20)
                | ((imm & 0x800) << 9)
                | (imm & 0xff000)
                | (rd << 7)
                | 0x6f;
    }

    private static int jalr(int rd, int rs1, int imm) {
        return ((imm & 0xfff) << 20) | (rs1 << 15) | (rd << 7) | 0x67;
    }

    private static int branch(int funct3, int rs1, int rs2, int imm) {
        return ((imm & 0x1000) << 19)
                | ((imm & 0x7e0) << 20)
                | (rs2 << 20)
                | (rs1 << 15)
                | (funct3 << 12)
                | ((imm & 0x1e) << 7)
                | ((imm & 0x800) >> 4)
                | 0x63;
    }

    private static int cjImm(int imm) {
        return (((imm >>> 1) & 0x7) << 3)
                | (((imm >>> 4) & 0x1) << 11)
                | (((imm >>> 5) & 0x1) << 2)
                | (((imm >>> 6) & 0x1) << 7)
                | (((imm >>> 7) & 0x1) << 6)
                | (((imm >>> 8) & 0x3) << 9)
                | (((imm >>> 10) & 0x1) << 8)
                | (((imm >>> 11) & 0x1) << 12);
    }

    private static int cJ(int imm) {
        return (5 << 13) | cjImm(imm) | 0x1;
    }

    private static int cJal(int imm) {
        return (1 << 13) | cjImm(imm) | 0x1;
    }

    private static int cJr(int rs1) {
        return (4 << 13) | (rs1 << 7) | 0x2;
    }

    private static int cJalr(int rs1) {
        return (4 << 13) | (1 << 12) | (rs1 << 7) | 0x2;
    }

    private static int cbImm(int imm) {
        return (((imm >>> 1) & 0x3) << 3)
                | (((imm >>> 3) & 0x3) << 10)
                | (((imm >>> 5) & 0x1) << 2)
                | (((imm >>> 6) & 0x3) << 5)
                | (((imm >>> 8) & 0x1) << 12);
    }

    private static int cBeqz(int rs1P, int imm) {
        return (6 << 13) | cbImm(imm) | ((rs1P - 8) << 7) | 0x1;
    }

    private static int cBnez(int rs1P, int imm) {
        return (7 << 13) | cbImm(imm) | ((rs1P - 8) << 7) | 0x1;
    }

    // ---- Infrastructure ----

    private static RV32IMAState machineState() {
        RV32IMAState state = new RV32IMAState();
        state.pc = RAM_OFFSET;
        state.extraflags |= 3;
        return state;
    }

    /**
     * Runs a two-instruction batch: {@code first} (a word, or a halfword when {@code compressed})
     * at {@code RAM_OFFSET}, then whatever it leads to. Both fall-through slots and {@code TARGET}
     * hold a no-op of the same width.
     */
    private static List<Event> run(IsaConfig config, int first, boolean compressed, Consumer<RV32IMAState> setup) {
        try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
            if (compressed) {
                ram.writeShort(RAM_OFFSET, (short) first);
                ram.writeShort(RAM_OFFSET + 2, (short) C_NOP);
                ram.writeShort(TARGET, (short) C_NOP);
            } else {
                ram.writeInt(RAM_OFFSET, first);
                ram.writeInt(RAM_OFFSET + 4, NOP);
                ram.writeInt(TARGET, NOP);
            }
            RV32IMAState state = machineState();
            setup.accept(state);
            List<Event> events = new ArrayList<>();
            TestCores.create(config)
                    .step(state, ram, RAM_OFFSET, RAM_SIZE, 2, (pc, ir, trap) -> events.add(new Event(pc, trap)), null);
            return events;
        }
    }

    private static void assertTransfer(List<Event> events, int nextPc) {
        assertEquals(List.of(new Event(RAM_OFFSET, 0), new Event(nextPc, 0)), events);
    }

    // ---- 32-bit transfers ----

    @Test
    public void jalReportsItsOwnPc() {
        assertTransfer(run(IsaConfig.RV32IMA_ZICSR, jal(1, TARGET - RAM_OFFSET), false, s -> {}), TARGET);
    }

    @Test
    public void jalrReportsItsOwnPc() {
        assertTransfer(run(IsaConfig.RV32IMA_ZICSR, jalr(1, 5, 8), false, s -> s.regs[5] = TARGET - 8), TARGET);
    }

    @ParameterizedTest(name = "funct3={0} rs1={1} rs2={2} taken={3}")
    @CsvSource({
        "0, 7, 7, true", "0, 7, 8, false", // BEQ
        "1, 7, 8, true", "1, 7, 7, false", // BNE
        "4, -1, 1, true", "4, 1, -1, false", // BLT
        "5, 1, -1, true", "5, -1, 1, false", // BGE
        "6, 1, -1, true", "6, -1, 1, false", // BLTU
        "7, -1, 1, true", "7, 1, -1, false", // BGEU
    })
    public void branchReportsItsOwnPc(int funct3, int rs1, int rs2, boolean taken) {
        List<Event> events = run(IsaConfig.RV32IMA_ZICSR, branch(funct3, 5, 6, TARGET - RAM_OFFSET), false, s -> {
            s.regs[5] = rs1;
            s.regs[6] = rs2;
        });
        assertTransfer(events, taken ? TARGET : RAM_OFFSET + 4);
    }

    @Test
    public void mretReportsItsOwnPc() {
        List<Event> events = run(IsaConfig.RV32IMA_ZICSR, MRET, false, s -> {
            s.mepc = TARGET;
            s.mstatus |= 3 << 11; // MPP = M, so the hart stays in M-mode
        });
        assertTransfer(events, TARGET);
    }

    @Test
    public void reservedBranchTrapReportsItsOwnPc() {
        List<Event> events = run(IsaConfig.RV32IMA_ZICSR, branch(2, 0, 0, TARGET - RAM_OFFSET), false, s -> {});
        assertEquals(List.of(new Event(RAM_OFFSET, 3)), events); // illegal instruction, cause + 1
    }

    // ---- Compressed transfers ----

    @Test
    public void cJReportsItsOwnPc() {
        assertTransfer(run(HAS_C, cJ(TARGET - RAM_OFFSET), true, s -> {}), TARGET);
    }

    @Test
    public void cJalReportsItsOwnPc() {
        assertTransfer(run(HAS_C, cJal(TARGET - RAM_OFFSET), true, s -> {}), TARGET);
    }

    @Test
    public void cJrReportsItsOwnPc() {
        assertTransfer(run(HAS_C, cJr(9), true, s -> s.regs[9] = TARGET), TARGET);
    }

    @Test
    public void cJalrReportsItsOwnPc() {
        assertTransfer(run(HAS_C, cJalr(9), true, s -> s.regs[9] = TARGET), TARGET);
    }

    @ParameterizedTest(name = "bnez={0} value={1} taken={2}")
    @CsvSource({"false, 0, true", "false, 1, false", "true, 1, true", "true, 0, false"})
    public void cBranchReportsItsOwnPc(boolean bnez, int value, boolean taken) {
        int offset = TARGET - RAM_OFFSET;
        int instruction = bnez ? cBnez(8, offset) : cBeqz(8, offset);
        List<Event> events = run(HAS_C, instruction, true, s -> s.regs[8] = value);
        assertTransfer(events, taken ? TARGET : RAM_OFFSET + 2);
    }

    // ---- WFI ----

    @Test
    public void stallingWfiCallsTheHookOnce() {
        RV32IMAState[] seen = new RV32IMAState[1];
        List<Event> events = run(IsaConfig.RV32IMA_ZICSR, WFI, false, s -> seen[0] = s);
        assertEquals(List.of(new Event(RAM_OFFSET, 0)), events);
        assertEquals(RAM_OFFSET + 4, seen[0].pc); // it stalled: the NOP after it did not run
        assertEquals(4, seen[0].extraflags & 4);
    }

    @Test
    public void continuingWfiCallsTheHookOnce() {
        // A pending, locally enabled MSIP that M-mode with MIE clear cannot take: WFI retires and
        // the call continues.
        List<Event> events = run(IsaConfig.RV32IMA_ZICSR, WFI, false, s -> {
            s.mie = MSIP;
            s.mip = MSIP;
        });
        assertEquals(List.of(new Event(RAM_OFFSET, 0), new Event(RAM_OFFSET + 4, 0)), events);
    }

    @Test
    public void checkOfAnExistingStallDoesNotCallTheHook() {
        RV32IMAState[] seen = new RV32IMAState[1];
        List<Event> events = run(IsaConfig.RV32IMA_ZICSR, WFI, false, s -> {
            s.extraflags |= 4;
            seen[0] = s;
        });
        assertEquals(List.of(), events);
        assertEquals(RAM_OFFSET, seen[0].pc);
        assertEquals(0, seen[0].getCycle());
    }
}
