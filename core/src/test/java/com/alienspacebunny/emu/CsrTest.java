package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Zicsr legality and side-effect order: read-only numbers and read suppression (step T5). */
public class CsrTest {
    private static final int RAM_OFFSET = 0x80000000;
    private static final int RAM_SIZE = 1024;
    private static final int HANDLER = RAM_OFFSET + 0x100;
    private static final int EXC_ILLEGAL_INSTRUCTION = 2;
    private static final int RD_SENTINEL = 0x5a5a5a5a;

    private static int csrInstruction(int csr, int funct3, int rd, int rs1OrImmediate) {
        return (csr << 20) | (rs1OrImmediate << 15) | (funct3 << 12) | (rd << 7) | 0x73;
    }

    /** Counts reads and writes separately; reads return a value derived from the number. */
    private static final class CountingCsrHook implements CSRHook {
        int reads;
        int writes;
        int lastWriteValue;

        @Override
        public int handleRead(int csrNo) {
            reads++;
            return csrNo ^ 0x10000;
        }

        @Override
        public void handleWrite(int csrNo, int value) {
            writes++;
            lastWriteValue = value;
        }
    }

    private static final class Run {
        final RV32IMAState state = new RV32IMAState();
        final CountingCsrHook hook = new CountingCsrHook();
        int hookCalls;

        /** Executes {@code ir} once at privilege {@code priv}, with x1 = 0x0f0, x12 = 0. */
        Run(int ir, int priv) {
            try (FFMMemoryBus ram = new FFMMemoryBus(RAM_SIZE, RAM_OFFSET)) {
                state.pc = RAM_OFFSET;
                state.extraflags = priv;
                state.mtvec = HANDLER;
                state.regs[1] = 0x0f0;
                state.regs[5] = RD_SENTINEL;
                state.regs[12] = 0;
                ram.writeInt(RAM_OFFSET, ir);
                TestCores.create().step(state, ram, RAM_OFFSET, RAM_SIZE, 1, (pc, i, trap) -> hookCalls++, hook);
            }
        }

        void assertIllegal(int ir) {
            assertEquals(EXC_ILLEGAL_INSTRUCTION, state.mcause, "mcause");
            assertEquals(ir, state.mtval, "mtval");
            assertEquals(RAM_OFFSET, state.mepc, "mepc");
            assertEquals(HANDLER, state.pc, "pc");
            assertEquals(RD_SENTINEL, state.regs[5], "rd unchanged");
            assertEquals(0, hook.reads, "CSR-hook reads");
            assertEquals(0, hook.writes, "CSR-hook writes");
        }
    }

    /**
     * Every form with write intent on a read-only number traps, built-in or hook-routed: {@code
     * cycle}, {@code mvendorid}, the unimplemented {@code hpmcounter3}, {@code mhartid} and a
     * custom read-only number.
     */
    @ParameterizedTest(name = "{0} on 0x{1}")
    @CsvSource({
        // form, csr (hex), funct3, rd, rs1 or immediate
        "csrrw, C00, 1, 5, 1",
        "csrrw rd=x0, C00, 1, 0, 1",
        "csrrs rs1=x12 holding 0, C00, 2, 5, 12",
        "csrrc rs1=x12 holding 0, C00, 3, 5, 12",
        "csrrwi imm=0, C00, 5, 5, 0",
        "csrrwi rd=x0, C00, 5, 0, 3",
        "csrrsi, C00, 6, 5, 1",
        "csrrci, C00, 7, 5, 1",
        "csrrw, F11, 1, 5, 1",
        "csrrs rs1=x12 holding 0, F11, 2, 5, 12",
        "csrrw rd=x0, C03, 1, 0, 1",
        "csrrc rs1=x1, C03, 3, 5, 1",
        "csrrsi, C83, 6, 5, 4",
        "csrrw rd=x0, F14, 1, 0, 1",
        "csrrci, FC0, 7, 5, 2",
        "csrrs rs1=x12 holding 0, CC0, 2, 5, 12",
    })
    public void writeIntentOnReadOnlyNumberTraps(String form, String csrHex, int funct3, int rd, int rs1) {
        int ir = csrInstruction(Integer.parseInt(csrHex, 16), funct3, rd, rs1);
        Run run = new Run(ir, 3);
        run.assertIllegal(ir);
        assertEquals(1, run.hookCalls, "PostExecHook reports the trap once");
    }

    @Test
    public void userModeWriteToCycleTrapsAfterThePrivilegeCheckPasses() {
        int ir = csrInstruction(0xC00, 1, 5, 1);
        Run run = new Run(ir, 0);
        run.assertIllegal(ir);
        assertEquals(0, run.state.mstatus & 0x1800, "mstatus.MPP records U-mode");
    }

    /** Reads of read-only numbers: {@code rs1} = x0 or a zero immediate means no write intent. */
    @ParameterizedTest(name = "{0} on 0x{1}")
    @CsvSource({
        "csrrs rs1=x0, C03, 2",
        "csrrc rs1=x0, C03, 3",
        "csrrsi imm=0, C03, 6",
        "csrrci imm=0, C03, 7",
        "csrrs rs1=x0, F14, 2",
        "csrrs rs1=x0, FC0, 2",
    })
    public void readOfReadOnlyHookNumberCallsOnlyTheReadHook(String form, String csrHex, int funct3) {
        int csr = Integer.parseInt(csrHex, 16);
        Run run = new Run(csrInstruction(csr, funct3, 5, 0), 3);
        assertEquals(0, run.state.mcause);
        assertEquals(RAM_OFFSET + 4, run.state.pc);
        assertEquals(csr ^ 0x10000, run.state.regs[5]);
        assertEquals(1, run.hook.reads);
        assertEquals(0, run.hook.writes);
    }

    @Test
    public void readOfBuiltInReadOnlyNumbersStillWorks() {
        Run run = new Run(csrInstruction(0xF11, 2, 5, 0), 3); // csrr x5, mvendorid
        assertEquals(0, run.state.mcause);
        assertEquals(0xff0ff0ff, run.state.regs[5]);
        assertEquals(0, run.hook.reads);
    }

    /** A writable hook CSR: CSRRW and CSRRWI with {@code rd} = x0 still skip the read. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        // form, funct3, rd, rs1 or immediate, expected reads, writes, written value
        "csrrw rd=x0, 1, 0, 1, 0, 1, 240",
        "csrrwi rd=x0, 5, 0, 9, 0, 1, 9",
        "csrrw, 1, 5, 1, 1, 1, 240",
        "csrrwi, 5, 5, 9, 1, 1, 9",
        "csrrs rs1=x12 holding 0, 2, 5, 12, 1, 1, 67520",
        "csrrs rs1=x0, 2, 5, 0, 1, 0, 0",
        "csrrsi imm=0 rd=x0, 6, 0, 0, 1, 0, 0",
    })
    public void writableHookCsrReadSuppression(
            String form, int funct3, int rd, int rs1, int reads, int writes, int written) {
        Run run = new Run(csrInstruction(0x7C0, funct3, rd, rs1), 3);
        assertEquals(0, run.state.mcause);
        assertEquals(reads, run.hook.reads, "reads");
        assertEquals(writes, run.hook.writes, "writes");
        assertEquals(written, run.hook.lastWriteValue, "written value");
    }
}
