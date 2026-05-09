package com.alienspacebunny.emu;

/**
 * Core execution logic for the RV32IMA RISC-V processor.
 * Ported from mini-rv32ima.c.
 */
public class RV32IMACore {

    public interface PostExecHook {
        void onPostExec(int pc, int ir, int trap);
    }

    /**
     * Executes a number of instructions.
     *
     * @param state The current processor state.
     * @param mem The memory bus.
     * @param ramOffset The base address of RAM (e.g., 0x80000000).
     * @param ramSize The size of RAM in bytes.
     * @param elapsedUs Microseconds elapsed since last call (for timer).
     * @param count Number of instructions to execute.
     * @param postExec Optional hook called after each instruction.
     * @param csrHook Optional hook for custom CSRs.
     * @return 0 on success, non-zero on special exit conditions (WFI, etc.)
     */
    public int step(RV32IMAState state, MemoryBus mem, int ramOffset, int ramSize, int elapsedUs, int count, PostExecHook postExec, CSRHook csrHook) {
        long currentTimer = state.getTimer();
        long newTimer = currentTimer + elapsedUs;
        state.setTimer(newTimer);

        // Handle Timer interrupt.
        long timerMatch = state.getTimerMatch();
        if (timerMatch != 0 && Long.compareUnsigned(newTimer, timerMatch) > 0) {
            state.extraflags &= ~4; // Clear WFI
            state.mip |= 1 << 7; // MTIP of MIP
        } else {
            state.mip &= ~(1 << 7);
        }

        // If WFI, don't run processor.
        if ((state.extraflags & 4) != 0) {
            return 1;
        }

        int trap = 0;
        int rval = 0;
        int pc = state.pc;
        long cycle = state.getCycle();

        // Check for timer interrupt before starting loop
        if ((state.mip & (1 << 7)) != 0 && (state.mie & (1 << 7)) != 0 && (state.mstatus & 0x8) != 0) {
            trap = 0x80000007;
            pc -= 4; // Will be incremented back to original PC in the interrupt handler
        } else {
            for (int icount = 0; icount < count; icount++) {
                int ir = 0;
                rval = 0;
                cycle++;
                int ofs_pc = pc - ramOffset;

                if (Integer.compareUnsigned(ofs_pc, ramSize) >= 0) {
                    trap = 1 + 1; // Access violation on instruction read
                    break;
                } else if ((ofs_pc & 3) != 0) {
                    trap = 1 + 0; // PC-misaligned access
                    break;
                } else {
                    ir = mem.readInt(pc);
                    int rdid = (ir >> 7) & 0x1f;

                    int opcode = ir & 0x7f;
                    switch (opcode) {
                        case 0x37: // LUI
                            rval = (ir & 0xfffff000);
                            break;
                        case 0x17: // AUIPC
                            rval = pc + (ir & 0xfffff000);
                            break;
                        case 0x6F: // JAL
                        {
                            int reladdy = ((ir & 0x80000000) >> 11) | ((ir & 0x7fe00000) >> 20) | ((ir & 0x00100000) >> 9) | ((ir & 0x000ff000));
                            if ((reladdy & 0x00100000) != 0) reladdy |= 0xffe00000;
                            rval = pc + 4;
                            pc = pc + reladdy - 4;
                            break;
                        }
                        case 0x67: // JALR
                        {
                            int imm = ir >>> 20;
                            int imm_se = imm | (((imm & 0x800) != 0) ? 0xfffff000 : 0);
                            rval = pc + 4;
                            pc = ((state.regs[(ir >> 15) & 0x1f] + imm_se) & ~1) - 4;
                            break;
                        }
                        case 0x63: // Branch
                        {
                            int immm4 = ((ir & 0xf00) >> 7) | ((ir & 0x7e000000) >> 20) | ((ir & 0x80) << 4) | ((ir >>> 31) << 12);
                            if ((immm4 & 0x1000) != 0) immm4 |= 0xffffe000;
                            int rs1 = state.regs[(ir >> 15) & 0x1f];
                            int rs2 = state.regs[(ir >> 20) & 0x1f];
                            immm4 = pc + immm4 - 4;
                            rdid = 0;
                            switch ((ir >> 12) & 0x7) {
                                case 0: if (rs1 == rs2) pc = immm4; break; // BEQ
                                case 1: if (rs1 != rs2) pc = immm4; break; // BNE
                                case 4: if (rs1 < rs2) pc = immm4; break; // BLT
                                case 5: if (rs1 >= rs2) pc = immm4; break; // BGE
                                case 6: if (Integer.compareUnsigned(rs1, rs2) < 0) pc = immm4; break; // BLTU
                                case 7: if (Integer.compareUnsigned(rs1, rs2) >= 0) pc = immm4; break; // BGEU
                                default: trap = (2 + 1);
                            }
                            break;
                        }
                        case 0x03: // Load
                        {
                            int rs1 = state.regs[(ir >> 15) & 0x1f];
                            int imm = ir >>> 20;
                            int imm_se = imm | (((imm & 0x800) != 0) ? 0xfffff000 : 0);
                            int addr = rs1 + imm_se;

                            try {
                                switch ((ir >> 12) & 0x7) {
                                    case 0: rval = mem.readByteSigned(addr); break; // LB
                                    case 1: rval = mem.readShortSigned(addr); break; // LH
                                    case 2: rval = mem.readInt(addr); break; // LW
                                    case 4: rval = mem.readByte(addr) & 0xFF; break; // LBU
                                    case 5: rval = mem.readShort(addr) & 0xFFFF; break; // LHU
                                    default: trap = (2 + 1);
                                }
                            } catch (IndexOutOfBoundsException e) {
                                trap = (5 + 1); // Load access fault
                                rval = addr;
                            }
                            // Note: C code had some MMIO checks here, but our MemoryBus handles it via MMIOBus
                            break;
                        }
                        case 0x23: // Store
                        {
                            int rs1 = state.regs[(ir >> 15) & 0x1f];
                            int rs2 = state.regs[(ir >> 20) & 0x1f];
                            int imm = ((ir >> 7) & 0x1f) | ((ir & 0xfe000000) >> 20);
                            if ((imm & 0x800) != 0) imm |= 0xfffff000;
                            int addr = rs1 + imm;
                            rdid = 0;

                            try {
                                switch ((ir >> 12) & 0x7) {
                                    case 0: mem.writeByte(addr, (byte) rs2); break; // SB
                                    case 1: mem.writeShort(addr, (short) rs2); break; // SH
                                    case 2: mem.writeInt(addr, rs2); break; // SW
                                    default: trap = (2 + 1);
                                }
                            } catch (IndexOutOfBoundsException e) {
                                trap = (7 + 1); // Store access fault
                                rval = addr;
                            }
                            break;
                        }
                        case 0x13: // Op-immediate
                        case 0x33: // Op
                        {
                            int imm = ir >>> 20;
                            imm = imm | (((imm & 0x800) != 0) ? 0xfffff000 : 0);
                            int rs1 = state.regs[(ir >> 15) & 0x1f];
                            boolean is_reg = (opcode & 0x20) != 0;
                            int rs2 = is_reg ? state.regs[imm & 0x1f] : imm;

                            if (is_reg && (ir & 0x02000000) != 0) {
                                // RV32M
                                switch ((ir >> 12) & 7) {
                                    case 0: rval = rs1 * rs2; break; // MUL
                                    case 1: rval = (int) (((long) rs1 * (long) rs2) >> 32); break; // MULH
                                    case 2: rval = (int) (((long) rs1 * Integer.toUnsignedLong(rs2)) >> 32); break; // MULHSU
                                    case 3: rval = (int) ((Integer.toUnsignedLong(rs1) * Integer.toUnsignedLong(rs2)) >> 32); break; // MULHU
                                    case 4: // DIV
                                        if (rs2 == 0) rval = -1;
                                        else rval = (rs1 == Integer.MIN_VALUE && rs2 == -1) ? rs1 : (rs1 / rs2);
                                        break;
                                    case 5: // DIVU
                                        if (rs2 == 0) rval = 0xffffffff;
                                        else rval = (int) (Integer.toUnsignedLong(rs1) / Integer.toUnsignedLong(rs2));
                                        break;
                                    case 6: // REM
                                        if (rs2 == 0) rval = rs1;
                                        else rval = (rs1 == Integer.MIN_VALUE && rs2 == -1) ? 0 : (rs1 % rs2);
                                        break;
                                    case 7: // REMU
                                        if (rs2 == 0) rval = rs1;
                                        else rval = (int) (Integer.toUnsignedLong(rs1) % Integer.toUnsignedLong(rs2));
                                        break;
                                }
                            } else {
                                switch ((ir >> 12) & 7) {
                                    case 0: rval = (is_reg && (ir & 0x40000000) != 0) ? (rs1 - rs2) : (rs1 + rs2); break;
                                    case 1: rval = rs1 << (rs2 & 0x1F); break;
                                    case 2: rval = rs1 < rs2 ? 1 : 0; break;
                                    case 3: rval = Integer.compareUnsigned(rs1, rs2) < 0 ? 1 : 0; break;
                                    case 4: rval = rs1 ^ rs2; break;
                                    case 5: rval = ((ir & 0x40000000) != 0) ? (rs1 >> (rs2 & 0x1F)) : (rs1 >>> (rs2 & 0x1F)); break;
                                    case 6: rval = rs1 | rs2; break;
                                    case 7: rval = rs1 & rs2; break;
                                }
                            }
                            break;
                        }
                        case 0x0f: // FENCE
                            rdid = 0;
                            break;
                        case 0x73: // SYSTEM
                        {
                            int csrno = ir >>> 20;
                            int microop = (ir >> 12) & 0x7;
                            if ((microop & 3) != 0) {
                                // Zicsr
                                int rs1imm = (ir >> 15) & 0x1f;
                                int rs1 = state.regs[rs1imm];
                                int writeval = rs1;

                                switch (csrno) {
                                    case 0x340: rval = state.mscratch; break;
                                    case 0x305: rval = state.mtvec; break;
                                    case 0x304: rval = state.mie; break;
                                    case 0xC00: rval = (int) cycle; break;
                                    case 0x344: rval = state.mip; break;
                                    case 0x341: rval = state.mepc; break;
                                    case 0x300: rval = state.mstatus; break;
                                    case 0x342: rval = state.mcause; break;
                                    case 0x343: rval = state.mtval; break;
                                    case 0xf11: rval = 0xff0ff0ff; break; // mvendorid
                                    case 0x301: rval = 0x40401101; break; // misa
                                    default:
                                        if (csrHook != null) {
                                            rval = csrHook.handleRead(csrno);
                                        } else {
                                            rval = 0;
                                        }
                                        break;
                                }

                                switch (microop) {
                                    case 1: writeval = rs1; break; // CSRRW
                                    case 2: writeval = rval | rs1; break; // CSRRS
                                    case 3: writeval = rval & ~rs1; break; // CSRRC
                                    case 5: writeval = rs1imm; break; // CSRRWI
                                    case 6: writeval = rval | rs1imm; break; // CSRRSI
                                    case 7: writeval = rval & ~rs1imm; break; // CSRRCI
                                }

                                switch (csrno) {
                                    case 0x340: state.mscratch = writeval; break;
                                    case 0x305: state.mtvec = writeval; break;
                                    case 0x304: state.mie = writeval; break;
                                    case 0x344: state.mip = writeval; break;
                                    case 0x341: state.mepc = writeval; break;
                                    case 0x300: state.mstatus = writeval; break;
                                    case 0x342: state.mcause = writeval; break;
                                    case 0x343: state.mtval = writeval; break;
                                    default:
                                        if (csrHook != null) {
                                            csrHook.handleWrite(csrno, writeval);
                                        }
                                        break;
                                }
                            } else if (microop == 0) {
                                // SYSTEM (MRET, ECALL, etc.)
                                rdid = 0;
                                if ((csrno & 0xff) == 0x02) {
                                    // MRET
                                    int startmstatus = state.mstatus;
                                    int startextraflags = state.extraflags;
                                    state.mstatus = ((startmstatus & 0x80) >> 4) | ((startextraflags & 3) << 11) | 0x80;
                                    state.extraflags = (startextraflags & ~3) | ((startmstatus >> 11) & 3);
                                    pc = state.mepc - 4;
                                } else {
                                    switch (csrno) {
                                        case 0: // ECALL
                                            trap = ((state.extraflags & 3) != 0) ? (11 + 1) : (8 + 1);
                                            break;
                                        case 1: // EBREAK
                                            trap = (3 + 1);
                                            break;
                                        case 0x105: // WFI
                                            state.mstatus |= 8;
                                            state.extraflags |= 4;
                                            state.setCycle(cycle);
                                            state.pc = pc + 4;
                                            return 1;
                                        default:
                                            trap = (2 + 1);
                                            break;
                                    }
                                }
                            } else {
                                trap = (2 + 1);
                            }
                            break;
                        }
                        case 0x2f: // RV32A
                        {
                            int rs1 = state.regs[(ir >> 15) & 0x1f];
                            int rs2 = state.regs[(ir >> 20) & 0x1f];
                            int irmid = (ir >> 27) & 0x1f;

                            boolean dowrite = true;
                            int accessFaultTrap = (irmid == 2) ? (5 + 1) : (7 + 1);
                            try {
                                // We'll assume the memory bus handles atomics or we just implement them simply
                                rval = mem.readInt(rs1);
                                switch (irmid) {
                                case 2: // LR.W
                                    dowrite = false;
                                    state.extraflags = (state.extraflags & 0x07) | (rs1 << 3);
                                    break;
                                case 3: // SC.W
                                    rval = ((state.extraflags >>> 3) != (rs1 & 0x1fffffff)) ? 1 : 0;
                                    dowrite = (rval == 0);
                                    break;
                                case 1: break; // AMOSWAP.W
                                case 0: rs2 += rval; break; // AMOADD.W
                                case 4: rs2 ^= rval; break; // AMOXOR.W
                                case 12: rs2 &= rval; break; // AMOAND.W
                                case 8: rs2 |= rval; break; // AMOOR.W
                                case 16: rs2 = (rs2 < rval) ? rs2 : rval; break; // AMOMIN.W
                                case 20: rs2 = (rs2 > rval) ? rs2 : rval; break; // AMOMAX.W
                                case 24: rs2 = Integer.compareUnsigned(rs2, rval) < 0 ? rs2 : rval; break; // AMOMINU.W
                                case 28: rs2 = Integer.compareUnsigned(rs2, rval) > 0 ? rs2 : rval; break; // AMOMAXU.W
                                default: trap = (2 + 1); dowrite = false; break;
                                }
                                if (dowrite) mem.writeInt(rs1, rs2);
                            } catch (IndexOutOfBoundsException e) {
                                trap = accessFaultTrap;
                                rval = rs1;
                            }
                            break;
                        }
                        default:
                            trap = (2 + 1);
                            break;
                    }

                    if (trap != 0) {
                        if (postExec != null) postExec.onPostExec(pc, ir, trap);
                        break;
                    }

                    if (rdid != 0) {
                        state.regs[rdid] = rval;
                    }
                }

                if (postExec != null) postExec.onPostExec(pc, ir, trap);
                pc += 4;
            }
        }

        // Handle traps and interrupts.
        if (trap != 0) {
            if ((trap & 0x80000000) != 0) {
                state.mcause = trap;
                state.mtval = 0;
                pc += 4;
            } else {
                state.mcause = trap - 1;
                state.mtval = (trap > 5 && trap <= 8) ? rval : pc;
            }
            state.mepc = pc;
            state.mstatus = ((state.mstatus & 0x08) << 4) | ((state.extraflags & 3) << 11);
            pc = state.mtvec;
            state.extraflags |= 3; // Enter machine mode
            trap = 0;
        }

        state.setCycle(cycle);
        state.pc = pc;
        return 0;
    }
}
