package com.alienspacebunny.emu;

import java.util.Objects;

/**
 * Core execution engine for a single RV32IMA RISC-V hart.
 *
 * <p>Ported from <a href="https://github.com/cnlohr/mini-rv32ima">mini-rv32ima</a> (MIT licence).
 * Supports the RV32I base integer instruction set, the RV32M integer multiplication extension, the
 * RV32A atomic extension (LR/SC and ten AMO operations), and machine-mode CSR instructions
 * (Zicsr). The zero-argument constructor configures exactly this base ISA; the {@link
 * #RV32IMACore(IsaConfig)} constructor accepts an {@link IsaConfig} for the additional optional
 * extensions being layered on for the V-32 AP/IOP multi-hart feature work — as of this writing,
 * only the {@code misa} CSR value reflects that configuration; none of the optional extensions
 * are decoded yet.
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
 *
 * <p><b>Interrupts.</b> {@code MTIP} (timer) is managed entirely by this class from {@code
 * mtimecmp}. {@code MSIP} (software) and {@code MEIP} (external) are pending/enable bits an
 * embedder sets directly on {@code mip}/{@code mie}, typically via {@link #injectInterrupt}. All
 * three are gated identically: individually enabled in {@code mie}, and either the hart is
 * running in user mode or {@code mstatus.MIE} is set (machine-mode interrupts are not maskable by
 * {@code mstatus.MIE} while executing below machine mode). When more than one is simultaneously
 * pending and enabled, external takes priority over software, which takes priority over timer.
 */
public class RV32IMACore {
    private final IsaConfig isaConfig;

    /**
     * Creates a new {@code RV32IMACore} execution engine configured for {@link
     * IsaConfig#RV32IMA_ZICSR} (no optional extensions). Identical to the core before {@link
     * IsaConfig} existed.
     */
    public RV32IMACore() {
        this(IsaConfig.RV32IMA_ZICSR);
    }

    /**
     * Creates a new {@code RV32IMACore} execution engine configured for the given extension set.
     *
     * @param isaConfig the extension configuration; only affects the {@code misa} CSR value as of
     *     the Phase 1 foundation work (see {@link IsaConfig}'s class Javadoc).
     * @throws NullPointerException if {@code isaConfig} is {@code null}.
     */
    public RV32IMACore(IsaConfig isaConfig) {
        this.isaConfig = Objects.requireNonNull(isaConfig, "isaConfig");
    }

    private static final int MSTATUS_MIE = 0x08;
    private static final int MSTATUS_MPIE = 0x80;
    private static final int MSTATUS_MPP = 0x1800;

    /** Bit position of the low end of {@code mstatus.MPP} (bits 12–11). */
    private static final int MSTATUS_MPP_SHIFT = 11;

    /** {@code mip}/{@code mie} bit 3: machine software interrupt pending/enable (MSIP/MSIE). */
    private static final int MIP_MSIP = 1 << 3;

    /** {@code mip}/{@code mie} bit 7: machine timer interrupt pending/enable (MTIP/MTIE). */
    private static final int MIP_MTIP = 1 << 7;

    /** {@code mip}/{@code mie} bit 11: machine external interrupt pending/enable (MEIP/MEIE). */
    private static final int MIP_MEIP = 1 << 11;

    /** {@code extraflags} bits 0–1: current privilege level ({@link #PRIV_MACHINE}/{@link #PRIV_USER}). */
    private static final int EXTRAFLAG_PRIV_MASK = 0x3;

    /** {@code extraflags} bit 2: WFI stall flag. Set on {@code WFI}; cleared when an interrupt arrives. */
    private static final int EXTRAFLAG_WFI = 0x4;

    private static final int PRIV_USER = 0;
    private static final int PRIV_MACHINE = 3;

    /**
     * Bit position of a CSR address's minimum-privilege field (bits 9–8 of the 12-bit CSR
     * address). Numerically equal to {@link #EXTRAFLAG_PRIV_MASK} by coincidence of the encoding,
     * not by relation between the two.
     */
    private static final int CSR_PRIVILEGE_SHIFT = 8;

    /** Mask for the 2-bit field extracted via {@link #CSR_PRIVILEGE_SHIFT}. */
    private static final int CSR_PRIVILEGE_FIELD_MASK = 0x3;

    /*
     * Trap dispatch uses a "+1" internal encoding on the local {@code trap} variable so that
     * {@code trap == 0} unambiguously means "no trap". A synchronous exception is held as
     * {@code cause + 1} (see {@link #exceptionTrap}); the trap handler writes {@code trap - 1}
     * to {@code mcause}. An interrupt is held with the high bit set and written to {@code mcause}
     * verbatim.
     */
    private static final int EXC_INSTRUCTION_MISALIGNED = 0;
    private static final int EXC_INSTRUCTION_ACCESS_FAULT = 1;
    private static final int EXC_ILLEGAL_INSTRUCTION = 2;
    private static final int EXC_BREAKPOINT = 3;
    private static final int EXC_LOAD_ACCESS_FAULT = 5;
    private static final int EXC_STORE_ACCESS_FAULT = 7;
    private static final int EXC_ECALL_FROM_U = 8;
    private static final int EXC_ECALL_FROM_M = 11;

    /** {@code mcause} high bit: set for an interrupt, clear for a synchronous exception. */
    private static final int INTERRUPT_FLAG = 0x80000000;

    /** Machine software interrupt, already in {@code mcause} form (interrupt bit set, code 3). */
    private static final int INT_MACHINE_SOFTWARE = INTERRUPT_FLAG | 3;

    /** Machine timer interrupt, already in {@code mcause} form (interrupt bit set, code 7). */
    private static final int INT_MACHINE_TIMER = INTERRUPT_FLAG | 7;

    /** Machine external interrupt, already in {@code mcause} form (interrupt bit set, code 11). */
    private static final int INT_MACHINE_EXTERNAL = INTERRUPT_FLAG | 11;

    /** Encodes a synchronous exception cause into the local {@code trap} variable's "+1" form. */
    private static int exceptionTrap(int cause) {
        return cause + 1;
    }

    /**
     * Marks a machine interrupt pending on {@code state} and, if the hart is stalled in {@code
     * WFI}, wakes it.
     *
     * <p>Setting the pending bit alone does not guarantee delivery on the next {@link #step} call:
     * the interrupt must also be individually enabled in {@code mie}, and — per the gating rule
     * documented on {@link #step} — either the hart must be running in {@link #PRIV_USER}, or
     * {@code mstatus.MIE} must be set. The caller is responsible for whatever synchronization
     * guards concurrent access to {@code state}, for example an I/O-processor hart injecting an
     * interrupt into an application-processor hart's state from another thread.
     *
     * @param state the target hart's state.
     * @param interruptBit the {@code mip}/{@code mie} bit index to set: 3 (MSIP), 7 (MTIP — normally
     *     core-managed from {@code mtimecmp}; injecting it directly is unusual), or 11 (MEIP).
     */
    public static void injectInterrupt(RV32IMAState state, int interruptBit) {
        state.mip |= 1 << interruptBit;
        state.extraflags &= ~EXTRAFLAG_WFI;
    }

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
     *   <li>For instruction-fetch failures (PC out of the executable range, misaligned, or the
     *       bus rejecting the fetch with an {@link IndexOutOfBoundsException}): the hook is
     *       <em>not</em> called.
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
            case 0x301 -> isaConfig.misa();
            default -> csrHook != null ? csrHook.handleRead(csrno) : 0;
        };
    }

    private void writeCsr(RV32IMAState state, CSRHook csrHook, int csrno, int writeValue) {
        switch (csrno) {
            case 0x340:
                state.mscratch = writeValue;
                break;
            case 0x305:
                state.mtvec = writeValue;
                break;
            case 0x304:
                state.mie = writeValue;
                break;
            case 0x344:
                state.mip = writeValue;
                break;
            case 0x341:
                state.mepc = writeValue;
                break;
            case 0x300:
                state.mstatus = writeValue;
                break;
            case 0x342:
                state.mcause = writeValue;
                break;
            case 0x343:
                state.mtval = writeValue;
                break;
            default:
                if (csrHook != null) {
                    csrHook.handleWrite(csrno, writeValue);
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
     * ramOffset} and {@code ramSize}; a PC outside that range, or not 4-byte aligned, causes an
     * instruction access-fault or misaligned-fetch trap without calling {@code mem}. Within that
     * window, an {@link IndexOutOfBoundsException} thrown by {@code mem} itself (for example, a
     * bus enforcing finer-grained access control than the coarse window) is likewise converted
     * into an instruction access-fault trap, with {@code mtval} set to the faulting PC. Data
     * accesses are delegated to {@code mem}; an {@link IndexOutOfBoundsException} from a data
     * access is converted into a load or store access-fault trap with {@code mtval} set to the
     * faulting address.
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
            state.extraflags &= ~EXTRAFLAG_WFI;
            state.mip |= MIP_MTIP;
        } else {
            state.mip &= ~MIP_MTIP;
        }

        // If WFI, don't run processor.
        if ((state.extraflags & EXTRAFLAG_WFI) != 0) {
            return 1;
        }

        int trap = 0;
        int rval = 0;
        int pc = state.pc;
        int ir = 0;
        long cycle = state.getCycle();

        // Check for a pending machine interrupt before starting the instruction loop. This core
        // models only M-mode and U-mode, so mstatus.MIE gates interrupts only while executing in
        // M-mode: per the privileged spec, a machine interrupt that is individually enabled in
        // mie is always taken while running below M-mode (here, U-mode), regardless of
        // mstatus.MIE. Priority when more than one bit is simultaneously pending and enabled:
        // external > software > timer.
        boolean interruptsGloballyEnabled =
                (state.extraflags & EXTRAFLAG_PRIV_MASK) == PRIV_USER || (state.mstatus & MSTATUS_MIE) != 0;
        int pendingEnabled = interruptsGloballyEnabled ? (state.mip & state.mie) : 0;
        if ((pendingEnabled & MIP_MEIP) != 0) {
            trap = INT_MACHINE_EXTERNAL;
        } else if ((pendingEnabled & MIP_MSIP) != 0) {
            trap = INT_MACHINE_SOFTWARE;
        } else if ((pendingEnabled & MIP_MTIP) != 0) {
            trap = INT_MACHINE_TIMER;
        }

        if (trap != 0) {
            pc -= 4; // Will be incremented back to original PC in the interrupt handler
        } else {
            for (int icount = 0; icount < count; icount++) {
                ir = 0;
                rval = 0;
                cycle++;
                // Privilege is stable for the duration of one instruction: nothing a load, store,
                // or AMO does can change it before the AccessContext below is built.
                int privilege = state.extraflags & EXTRAFLAG_PRIV_MASK;
                int ofsPc = pc - ramOffset;

                if (Integer.compareUnsigned(ofsPc, ramSize) >= 0) {
                    trap = exceptionTrap(EXC_INSTRUCTION_ACCESS_FAULT);
                    rval = pc;
                    break;
                } else if ((ofsPc & 3) != 0) {
                    trap = exceptionTrap(EXC_INSTRUCTION_MISALIGNED);
                    rval = pc;
                    break;
                } else {
                    AccessContext fetchCtx = new AccessContext(state.hartId, privilege, AccessKind.FETCH, 4, 0);
                    try {
                        ir = mem.readInt(pc, fetchCtx);
                    } catch (IndexOutOfBoundsException e) {
                        // The ramOffset/ramSize check above is only a coarse precheck; a bus can
                        // still reject a fetch within that window (for example, fine-grained MPU
                        // enforcement). Convert that rejection into the same instruction
                        // access-fault trap as the coarse-window check, rather than letting the
                        // exception propagate to the caller.
                        trap = exceptionTrap(EXC_INSTRUCTION_ACCESS_FAULT);
                        rval = pc;
                        break;
                    }
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
                            int jumpOffset = ((ir & 0x80000000) >> 11)
                                    | ((ir & 0x7fe00000) >> 20)
                                    | ((ir & 0x00100000) >> 9)
                                    | ((ir & 0x000ff000));
                            if ((jumpOffset & 0x00100000) != 0) jumpOffset |= 0xffe00000;
                            rval = pc + 4;
                            pc = pc + jumpOffset - 4;
                            break;
                        }
                        case 0x67: // JALR
                        {
                            int imm = ir >>> 20;
                            int immSext = imm | (((imm & 0x800) != 0) ? 0xfffff000 : 0);
                            rval = pc + 4;
                            pc = ((state.regs[(ir >> 15) & 0x1f] + immSext) & ~1) - 4;
                            break;
                        }
                        case 0x63: // Branch
                        {
                            int branchOffset = ((ir & 0xf00) >> 7)
                                    | ((ir & 0x7e000000) >> 20)
                                    | ((ir & 0x80) << 4)
                                    | ((ir >>> 31) << 12);
                            if ((branchOffset & 0x1000) != 0) branchOffset |= 0xffffe000;
                            int rs1 = state.regs[(ir >> 15) & 0x1f];
                            int rs2 = state.regs[(ir >> 20) & 0x1f];
                            branchOffset = pc + branchOffset - 4;
                            rdid = 0;
                            switch ((ir >> 12) & 0x7) {
                                case 0:
                                    if (rs1 == rs2) pc = branchOffset;
                                    break; // BEQ
                                case 1:
                                    if (rs1 != rs2) pc = branchOffset;
                                    break; // BNE
                                case 4:
                                    if (rs1 < rs2) pc = branchOffset;
                                    break; // BLT
                                case 5:
                                    if (rs1 >= rs2) pc = branchOffset;
                                    break; // BGE
                                case 6:
                                    if (Integer.compareUnsigned(rs1, rs2) < 0) pc = branchOffset;
                                    break; // BLTU
                                case 7:
                                    if (Integer.compareUnsigned(rs1, rs2) >= 0) pc = branchOffset;
                                    break; // BGEU
                                default:
                                    trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                            }
                            break;
                        }
                        case 0x03: // Load
                        {
                            int rs1 = state.regs[(ir >> 15) & 0x1f];
                            int imm = ir >>> 20;
                            int immSext = imm | (((imm & 0x800) != 0) ? 0xfffff000 : 0);
                            int addr = rs1 + immSext;

                            try {
                                switch ((ir >> 12) & 0x7) {
                                    case 0:
                                        rval = mem.readByteSigned(
                                                addr,
                                                new AccessContext(state.hartId, privilege, AccessKind.LOAD, 1, 0));
                                        break; // LB
                                    case 1:
                                        rval = mem.readShortSigned(
                                                addr,
                                                new AccessContext(state.hartId, privilege, AccessKind.LOAD, 2, 0));
                                        break; // LH
                                    case 2:
                                        rval = mem.readInt(
                                                addr,
                                                new AccessContext(state.hartId, privilege, AccessKind.LOAD, 4, 0));
                                        break; // LW
                                    case 4:
                                        rval = mem.readByte(
                                                        addr,
                                                        new AccessContext(
                                                                state.hartId, privilege, AccessKind.LOAD, 1, 0))
                                                & 0xFF;
                                        break; // LBU
                                    case 5:
                                        rval = mem.readShort(
                                                        addr,
                                                        new AccessContext(
                                                                state.hartId, privilege, AccessKind.LOAD, 2, 0))
                                                & 0xFFFF;
                                        break; // LHU
                                    default:
                                        trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                }
                            } catch (IndexOutOfBoundsException e) {
                                trap = exceptionTrap(EXC_LOAD_ACCESS_FAULT);
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
                                        mem.writeByte(
                                                addr,
                                                (byte) rs2,
                                                new AccessContext(state.hartId, privilege, AccessKind.STORE, 1, 0));
                                        break; // SB
                                    case 1:
                                        mem.writeShort(
                                                addr,
                                                (short) rs2,
                                                new AccessContext(state.hartId, privilege, AccessKind.STORE, 2, 0));
                                        break; // SH
                                    case 2:
                                        mem.writeInt(
                                                addr,
                                                rs2,
                                                new AccessContext(state.hartId, privilege, AccessKind.STORE, 4, 0));
                                        break; // SW
                                    default:
                                        trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                }
                                if (trap == 0) {
                                    state.reservationValid = false;
                                }
                            } catch (IndexOutOfBoundsException e) {
                                trap = exceptionTrap(EXC_STORE_ACCESS_FAULT);
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
                            boolean isReg = (opcode & 0x20) != 0;
                            int rs2 = isReg ? state.regs[imm & 0x1f] : imm;
                            int funct3 = (ir >> 12) & 7;
                            int funct7 = (ir >>> 25) & 0x7f;
                            boolean legalEncoding;

                            if (isReg) {
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
                                trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                break;
                            }

                            if (isReg && funct7 == 1) {
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
                                        rval = (isReg && (ir & 0x40000000) != 0) ? (rs1 - rs2) : (rs1 + rs2);
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
                                int csrMinPrivilege = (csrno >> CSR_PRIVILEGE_SHIFT) & CSR_PRIVILEGE_FIELD_MASK;
                                if (csrMinPrivilege > (state.extraflags & EXTRAFLAG_PRIV_MASK)) {
                                    trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                    break;
                                }

                                int rs1Index = (ir >> 15) & 0x1f;
                                int rs1 = state.regs[rs1Index];
                                boolean isWrite = microop == 1 || microop == 5;
                                boolean shouldRead = !(isWrite && rdid == 0);
                                boolean shouldWrite = isWrite || rs1Index != 0;

                                rval = shouldRead ? readCsr(state, csrHook, csrno, cycle) : 0;
                                int writeValue = rs1;

                                switch (microop) {
                                    case 1:
                                        writeValue = rs1;
                                        break; // CSRRW
                                    case 2:
                                        writeValue = rval | rs1;
                                        break; // CSRRS
                                    case 3:
                                        writeValue = rval & ~rs1;
                                        break; // CSRRC
                                    case 5:
                                        writeValue = rs1Index;
                                        break; // CSRRWI
                                    case 6:
                                        writeValue = rval | rs1Index;
                                        break; // CSRRSI
                                    case 7:
                                        writeValue = rval & ~rs1Index;
                                        break; // CSRRCI
                                    default:
                                        break; // unreachable: outer (microop & 3) != 0 excludes 0 and 4
                                }

                                if (shouldWrite) {
                                    writeCsr(state, csrHook, csrno, writeValue);
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
                                    state.extraflags = (startextraflags & ~EXTRAFLAG_PRIV_MASK)
                                            | ((startmstatus & MSTATUS_MPP) >> MSTATUS_MPP_SHIFT);
                                    pc = state.mepc - 4;
                                } else {
                                    switch (csrno) {
                                        case 0: // ECALL
                                            // Only M-mode (3) and U-mode (0) are modelled; any
                                            // non-user privilege is treated as machine here.
                                            trap = ((state.extraflags & EXTRAFLAG_PRIV_MASK) != PRIV_USER)
                                                    ? exceptionTrap(EXC_ECALL_FROM_M)
                                                    : exceptionTrap(EXC_ECALL_FROM_U);
                                            break;
                                        case 1: // EBREAK
                                            trap = exceptionTrap(EXC_BREAKPOINT);
                                            break;
                                        case 0x105: // WFI
                                            state.mstatus |= MSTATUS_MIE;
                                            state.extraflags |= EXTRAFLAG_WFI;
                                            state.setCycle(cycle);
                                            state.pc = pc + 4;
                                            return 1;
                                        default:
                                            trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                            break;
                                    }
                                }
                            } else {
                                trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
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
                                trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                break;
                            }

                            int accessFaultTrap = (irmid == 2)
                                    ? exceptionTrap(EXC_LOAD_ACCESS_FAULT)
                                    : exceptionTrap(EXC_STORE_ACCESS_FAULT);
                            // irmid is the funct5 encoding; also AccessContext.atomicOp. LR.W is irmid 2 --
                            // a multi-hart bus detects it via ctx.kind() == AMO && ctx.atomicOp() == 2 on
                            // this readInt override, per AccessContext's Javadoc.
                            AccessContext amoCtx = new AccessContext(state.hartId, privilege, AccessKind.AMO, 4, irmid);
                            try {
                                switch (irmid) {
                                    case 2: // LR.W
                                        rval = mem.readInt(rs1, amoCtx);
                                        state.reservationAddr = rs1;
                                        state.reservationValid = true;
                                        break;
                                    case 3: // SC.W
                                        // Local fast-path pre-check: if it fails, fail immediately with no
                                        // bus call at all (Design Decision §5). If it passes, the bus still
                                        // makes the final atomic decision -- it may reject even though the
                                        // local state says valid, if a cross-hart invalidation landed between
                                        // this hart's LR and SC.
                                        if (state.reservationValid && state.reservationAddr == rs1) {
                                            rval = mem.tryScAndStore(state.hartId, rs1, rs2, amoCtx);
                                        } else {
                                            rval = 1;
                                        }
                                        state.reservationValid = false;
                                        break;
                                    default: // the 9 validated non-LR/SC AMOs (ADD/SWAP/XOR/AND/OR/MIN[U]/MAX[U])
                                        rval = mem.atomicRmw(rs1, irmid, rs2, amoCtx);
                                        break;
                                }
                            } catch (IndexOutOfBoundsException e) {
                                trap = accessFaultTrap;
                                rval = rs1;
                            }
                            break;
                        }
                        default:
                            trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
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
            if ((trap & INTERRUPT_FLAG) != 0) {
                state.mcause = trap;
                state.mtval = 0;
                pc += 4;
            } else {
                state.mcause = trap - 1; // undo the "+1" internal encoding
                state.mtval = state.mcause == EXC_ILLEGAL_INSTRUCTION ? ir : rval;
            }
            state.mepc = pc;
            state.mstatus = (state.mstatus & ~(MSTATUS_MIE | MSTATUS_MPIE | MSTATUS_MPP))
                    | ((state.mstatus & MSTATUS_MIE) << 4)
                    | ((state.extraflags & EXTRAFLAG_PRIV_MASK) << MSTATUS_MPP_SHIFT);
            pc = state.mtvec;
            state.extraflags |= PRIV_MACHINE; // Enter machine mode
            trap = 0;
        }

        state.setCycle(cycle);
        state.pc = pc;
        return 0;
    }
}
