package com.alienspacebunny.emu;

/**
 * Core execution engine for a single RV32IMA RISC-V hart.
 *
 * <p>Ported from <a href="https://github.com/cnlohr/mini-rv32ima">mini-rv32ima</a> (MIT licence).
 * Supports the RV32I base integer instruction set, the RV32M integer multiplication extension, the
 * RV32A atomic extension (LR/SC and ten AMO operations), and machine-mode CSR instructions
 * (Zicsr).
 *
 * <p><b>Intentional deviations from the RISC-V specification.</b> Two behaviours are inherited
 * from the upstream C implementation and preserved intentionally:
 *
 * <ul>
 *   <li><b>WFI sets {@code mstatus.MIE} before suspending.</b> The RISC-V specification treats
 *       {@code WFI} as a hint and does not mandate privilege-state changes. This implementation
 *       unconditionally sets bit 3 ({@code MIE}) in {@code mstatus} before entering the WFI stall
 *       so that a pending timer interrupt can wake the hart even if the guest had not enabled
 *       interrupts. Do not remove this behaviour without also adjusting the interrupt-enable check
 *       in {@link #step}.
 *   <li><b>Timer interrupt gated by {@code timerMatch != 0}.</b> {@code MTIP} is raised only when
 *       {@code mtimecmp} is non-zero and {@code mtime >= mtimecmp}. When both are zero (reset
 *       state), no interrupt fires. This prevents a spurious timer interrupt before the guest
 *       configures {@code mtimecmp}.
 * </ul>
 */
public class RV32IMACore {
    /** Creates a new {@code RV32IMACore} execution engine. */
    public RV32IMACore() {}

    private static final int MSTATUS_MIE = 0x08;
    private static final int MSTATUS_MPIE = 0x80;
    private static final int MSTATUS_MPP = 0x1800;

    /**
     * Optional callback invoked after each instruction execution or trap.
     *
     * <p>The hook is called once per instruction cycle:
     *
     * <ul>
     *   <li>For instructions that complete without a trap: called after the result is committed to
     *       the destination register but before the PC is advanced to the next instruction.
     *   <li>For instructions that raise a trap: called before the trap is committed to the machine
     *       CSRs ({@code mepc}, {@code mcause}, {@code mtval}, {@code mstatus}).
     *   <li>For instruction-fetch failures (PC out of the executable range, or misaligned): the
     *       hook is <em>not</em> called.
     * </ul>
     */
    public interface PostExecHook {
        /**
         * Observes an instruction that just executed or is about to trap.
         *
         * @param pc the guest PC of the instruction, not yet advanced by 4.
         * @param ir the raw 32-bit instruction word; zero if fetch failed before decoding.
         * @param trap zero for normal execution; otherwise the trap cause (internal encoding,
         *     before being committed to {@code mcause}).
         */
        void onPostExec(int pc, int ir, int trap);
    }

    private int readCsr(RV32IMAState state, CSRHook csrHook, int csrno, long cycle) {
        return switch (csrno) {
            case 0x340 -> state.mscratch;
            case 0x305 -> state.mtvec;
            case 0x304 -> state.mie;
            case 0xC00 -> (int) cycle;
            case 0x344 -> state.mip;
            case 0x341 -> state.mepc;
            case 0x300 -> state.mstatus;
            case 0x342 -> state.mcause;
            case 0x343 -> state.mtval;
            case 0xf11 -> 0xff0ff0ff; // mvendorid
            case 0x301 -> 0x40401101; // misa
            default -> csrHook != null ? csrHook.handleRead(csrno) : 0;
        };
    }

    private void writeCsr(RV32IMAState state, CSRHook csrHook, int csrno, int writeval) {
        switch (csrno) {
            case 0x340:
                state.mscratch = writeval;
                break;
            case 0x305:
                state.mtvec = writeval;
                break;
            case 0x304:
                state.mie = writeval;
                break;
            case 0x344:
                state.mip = writeval;
                break;
            case 0x341:
                state.mepc = writeval;
                break;
            case 0x300:
                state.mstatus = writeval;
                break;
            case 0x342:
                state.mcause = writeval;
                break;
            case 0x343:
                state.mtval = writeval;
                break;
            default:
                if (csrHook != null) {
                    csrHook.handleWrite(csrno, writeval);
                }
                break;
        }
    }

    /**
     * Executes up to {@code count} instructions on the given hart.
     *
     * <p><b>Timer.</b> Before executing any instructions, the machine timer ({@code mtime}) is
     * advanced by {@code elapsedUs} microseconds. If the updated timer meets or exceeds {@code
     * mtimecmp} and {@code mtimecmp != 0}, {@code MTIP} in {@code mip} is set; otherwise it is
     * cleared. See the class-level note on the startup timer guard.
     *
     * <p><b>WFI.</b> If the hart is in the WFI stall state and no interrupt is pending, no
     * instructions are executed and this method returns {@code 1} immediately. The caller should
     * sleep or yield before calling again.
     *
     * <p><b>Trap handling.</b> If an interrupt or exception occurs, the core commits trap state to
     * {@code mepc}, {@code mcause}, {@code mtval}, and {@code mstatus}, then redirects the PC to
     * {@code mtvec}. The trap is resolved within this call; the next call will fetch from {@code
     * mtvec}. Fewer than {@code count} instructions may be executed when a trap fires.
     *
     * <p><b>Instruction fetch.</b> Instructions are fetched from the window defined by {@code
     * ramOffset} and {@code ramSize}; a PC outside that range causes an instruction access-fault
     * trap. Data accesses are delegated to {@code mem}; an {@link IndexOutOfBoundsException} from
     * the memory bus is converted into a load or store access-fault trap with {@code mtval} set
     * to the faulting address.
     *
     * @param state the mutable processor state to execute.
     * @param mem the memory bus for instruction fetch and data access.
     * @param ramOffset base address of executable RAM (unsigned 32-bit guest address).
     * @param ramSize size of executable RAM in bytes.
     * @param elapsedUs microseconds elapsed since the previous call; added to {@code mtime} before
     *     any instructions run.
     * @param count maximum number of instructions to execute; may execute fewer if a trap fires.
     * @param postExec optional callback invoked after each instruction or trap; may be {@code
     *     null}.
     * @param csrHook optional hook for custom CSR accesses; may be {@code null}, in which case
     *     reads of non-built-in CSRs return {@code 0} and writes are silently discarded.
     * @return {@code 0} after executing instructions or handling a trap; {@code 1} if the hart is
     *     in WFI and no instruction was executed.
     */
    public int step(
            RV32IMAState state,
            MemoryBus mem,
            int ramOffset,
            int ramSize,
            int elapsedUs,
            int count,
            PostExecHook postExec,
            CSRHook csrHook) {
        long currentTimer = state.getTimer();
        long newTimer = currentTimer + elapsedUs;
        state.setTimer(newTimer);

        // Handle Timer interrupt.
        long timerMatch = state.getTimerMatch();
        if (timerMatch != 0 && Long.compareUnsigned(newTimer, timerMatch) >= 0) {
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
        int ir = 0;
        long cycle = state.getCycle();

        // Check for timer interrupt before starting loop
        if ((state.mip & (1 << 7)) != 0 && (state.mie & (1 << 7)) != 0 && (state.mstatus & 0x8) != 0) {
            trap = 0x80000007;
            pc -= 4; // Will be incremented back to original PC in the interrupt handler
        } else {
            for (int icount = 0; icount < count; icount++) {
                ir = 0;
                rval = 0;
                cycle++;
                int ofs_pc = pc - ramOffset;

                if (Integer.compareUnsigned(ofs_pc, ramSize) >= 0) {
                    trap = 1 + 1; // Access violation on instruction read
                    rval = pc;
                    break;
                } else if ((ofs_pc & 3) != 0) {
                    trap = 1 + 0; // PC-misaligned access
                    rval = pc;
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
                            int reladdy = ((ir & 0x80000000) >> 11)
                                    | ((ir & 0x7fe00000) >> 20)
                                    | ((ir & 0x00100000) >> 9)
                                    | ((ir & 0x000ff000));
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
                            int immm4 = ((ir & 0xf00) >> 7)
                                    | ((ir & 0x7e000000) >> 20)
                                    | ((ir & 0x80) << 4)
                                    | ((ir >>> 31) << 12);
                            if ((immm4 & 0x1000) != 0) immm4 |= 0xffffe000;
                            int rs1 = state.regs[(ir >> 15) & 0x1f];
                            int rs2 = state.regs[(ir >> 20) & 0x1f];
                            immm4 = pc + immm4 - 4;
                            rdid = 0;
                            switch ((ir >> 12) & 0x7) {
                                case 0:
                                    if (rs1 == rs2) pc = immm4;
                                    break; // BEQ
                                case 1:
                                    if (rs1 != rs2) pc = immm4;
                                    break; // BNE
                                case 4:
                                    if (rs1 < rs2) pc = immm4;
                                    break; // BLT
                                case 5:
                                    if (rs1 >= rs2) pc = immm4;
                                    break; // BGE
                                case 6:
                                    if (Integer.compareUnsigned(rs1, rs2) < 0) pc = immm4;
                                    break; // BLTU
                                case 7:
                                    if (Integer.compareUnsigned(rs1, rs2) >= 0) pc = immm4;
                                    break; // BGEU
                                default:
                                    trap = (2 + 1);
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
                                    case 0:
                                        rval = mem.readByteSigned(addr);
                                        break; // LB
                                    case 1:
                                        rval = mem.readShortSigned(addr);
                                        break; // LH
                                    case 2:
                                        rval = mem.readInt(addr);
                                        break; // LW
                                    case 4:
                                        rval = mem.readByte(addr) & 0xFF;
                                        break; // LBU
                                    case 5:
                                        rval = mem.readShort(addr) & 0xFFFF;
                                        break; // LHU
                                    default:
                                        trap = (2 + 1);
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
                                    case 0:
                                        mem.writeByte(addr, (byte) rs2);
                                        break; // SB
                                    case 1:
                                        mem.writeShort(addr, (short) rs2);
                                        break; // SH
                                    case 2:
                                        mem.writeInt(addr, rs2);
                                        break; // SW
                                    default:
                                        trap = (2 + 1);
                                }
                                if (trap == 0) {
                                    state.reservationValid = false;
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
                            int funct3 = (ir >> 12) & 7;
                            int funct7 = (ir >>> 25) & 0x7f;
                            boolean legalEncoding;

                            if (is_reg) {
                                legalEncoding =
                                        funct7 == 0 || (funct7 == 0x20 && (funct3 == 0 || funct3 == 5)) || funct7 == 1;
                            } else if (funct3 == 1) {
                                legalEncoding = funct7 == 0;
                            } else if (funct3 == 5) {
                                legalEncoding = funct7 == 0 || funct7 == 0x20;
                            } else {
                                legalEncoding = true;
                            }

                            if (!legalEncoding) {
                                trap = (2 + 1);
                                break;
                            }

                            if (is_reg && funct7 == 1) {
                                // RV32M
                                switch (funct3) {
                                    case 0:
                                        rval = rs1 * rs2;
                                        break; // MUL
                                    case 1:
                                        rval = (int) (((long) rs1 * (long) rs2) >> 32);
                                        break; // MULH
                                    case 2:
                                        rval = (int) (((long) rs1 * Integer.toUnsignedLong(rs2)) >> 32);
                                        break; // MULHSU
                                    case 3:
                                        rval = (int)
                                                ((Integer.toUnsignedLong(rs1) * Integer.toUnsignedLong(rs2)) >> 32);
                                        break; // MULHU
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
                                    default:
                                        break; // unreachable: funct7==1 validation above covers 0-7
                                }
                            } else {
                                switch (funct3) {
                                    case 0:
                                        rval = (is_reg && (ir & 0x40000000) != 0) ? (rs1 - rs2) : (rs1 + rs2);
                                        break;
                                    case 1:
                                        rval = rs1 << (rs2 & 0x1F);
                                        break;
                                    case 2:
                                        rval = rs1 < rs2 ? 1 : 0;
                                        break;
                                    case 3:
                                        rval = Integer.compareUnsigned(rs1, rs2) < 0 ? 1 : 0;
                                        break;
                                    case 4:
                                        rval = rs1 ^ rs2;
                                        break;
                                    case 5:
                                        rval = ((ir & 0x40000000) != 0)
                                                ? (rs1 >> (rs2 & 0x1F))
                                                : (rs1 >>> (rs2 & 0x1F));
                                        break;
                                    case 6:
                                        rval = rs1 | rs2;
                                        break;
                                    case 7:
                                        rval = rs1 & rs2;
                                        break;
                                    default:
                                        break; // unreachable: funct3 is 3 bits (0-7), all cases handled above
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
                                boolean isWrite = microop == 1 || microop == 5;
                                boolean shouldRead = !(isWrite && rdid == 0);
                                boolean shouldWrite = isWrite || rs1imm != 0;

                                rval = shouldRead ? readCsr(state, csrHook, csrno, cycle) : 0;
                                int writeval = rs1;

                                switch (microop) {
                                    case 1:
                                        writeval = rs1;
                                        break; // CSRRW
                                    case 2:
                                        writeval = rval | rs1;
                                        break; // CSRRS
                                    case 3:
                                        writeval = rval & ~rs1;
                                        break; // CSRRC
                                    case 5:
                                        writeval = rs1imm;
                                        break; // CSRRWI
                                    case 6:
                                        writeval = rval | rs1imm;
                                        break; // CSRRSI
                                    case 7:
                                        writeval = rval & ~rs1imm;
                                        break; // CSRRCI
                                    default:
                                        break; // unreachable: outer (microop & 3) != 0 excludes 0 and 4
                                }

                                if (shouldWrite) {
                                    writeCsr(state, csrHook, csrno, writeval);
                                }
                            } else if (microop == 0) {
                                // SYSTEM (MRET, ECALL, etc.)
                                rdid = 0;
                                if (csrno == 0x302) {
                                    // MRET
                                    int startmstatus = state.mstatus;
                                    int startextraflags = state.extraflags;
                                    state.mstatus = (startmstatus & ~(MSTATUS_MIE | MSTATUS_MPIE | MSTATUS_MPP))
                                            | ((startmstatus & MSTATUS_MPIE) >> 4)
                                            | MSTATUS_MPIE;
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
                            int funct3 = (ir >> 12) & 7;

                            boolean validAtomicOperation =
                                    switch (irmid) {
                                        case 0, 1, 2, 3, 4, 8, 12, 16, 20, 24, 28 -> true;
                                        default -> false;
                                    };
                            if (funct3 != 2 || !validAtomicOperation) {
                                trap = (2 + 1);
                                break;
                            }

                            boolean dowrite = true;
                            int accessFaultTrap = (irmid == 2) ? (5 + 1) : (7 + 1);
                            try {
                                // We'll assume the memory bus handles atomics or we just implement them simply
                                rval = mem.readInt(rs1);
                                switch (irmid) {
                                    case 2: // LR.W
                                        dowrite = false;
                                        state.reservationAddr = rs1;
                                        state.reservationValid = true;
                                        break;
                                    case 3: // SC.W
                                        if (state.reservationValid && state.reservationAddr == rs1) {
                                            rval = 0;
                                            dowrite = true;
                                        } else {
                                            rval = 1;
                                            dowrite = false;
                                        }
                                        state.reservationValid = false;
                                        break;
                                    case 1:
                                        break; // AMOSWAP.W
                                    case 0:
                                        rs2 += rval;
                                        break; // AMOADD.W
                                    case 4:
                                        rs2 ^= rval;
                                        break; // AMOXOR.W
                                    case 12:
                                        rs2 &= rval;
                                        break; // AMOAND.W
                                    case 8:
                                        rs2 |= rval;
                                        break; // AMOOR.W
                                    case 16:
                                        rs2 = (rs2 < rval) ? rs2 : rval;
                                        break; // AMOMIN.W
                                    case 20:
                                        rs2 = (rs2 > rval) ? rs2 : rval;
                                        break; // AMOMAX.W
                                    case 24:
                                        rs2 = Integer.compareUnsigned(rs2, rval) < 0 ? rs2 : rval;
                                        break; // AMOMINU.W
                                    case 28:
                                        rs2 = Integer.compareUnsigned(rs2, rval) > 0 ? rs2 : rval;
                                        break; // AMOMAXU.W
                                    default:
                                        trap = (2 + 1);
                                        dowrite = false;
                                        break;
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
                state.mtval = state.mcause == 2 ? ir : rval;
            }
            state.mepc = pc;
            state.mstatus = (state.mstatus & ~(MSTATUS_MIE | MSTATUS_MPIE | MSTATUS_MPP))
                    | ((state.mstatus & MSTATUS_MIE) << 4)
                    | ((state.extraflags & 3) << 11);
            pc = state.mtvec;
            state.extraflags |= 3; // Enter machine mode
            trap = 0;
        }

        state.setCycle(cycle);
        state.pc = pc;
        return 0;
    }
}
