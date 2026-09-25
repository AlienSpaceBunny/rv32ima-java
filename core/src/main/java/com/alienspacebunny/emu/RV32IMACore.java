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
 * extensions being layered on for the V-32 AP/IOP multi-hart feature work. As of the Phase 3 work,
 * {@code Zba}, {@code Zbb}, and {@code Zabha} (byte/halfword AMOs) are decoded when enabled; {@code
 * C} and {@code F} are not decoded yet and only affect the {@code misa} CSR value the guest reads
 * back.
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
    private static final int EXC_LOAD_MISALIGNED = 4;
    private static final int EXC_LOAD_ACCESS_FAULT = 5;
    private static final int EXC_STORE_MISALIGNED = 6;
    private static final int EXC_STORE_ACCESS_FAULT = 7;
    private static final int EXC_ECALL_FROM_U = 8;
    private static final int EXC_ECALL_FROM_M = 11;

    // ---- F extension (fcsr / fflags) support: Phase 5 ----------------------------------------
    private static final int FFLAGS_NV = 1 << 4; // invalid operation
    private static final int FFLAGS_DZ = 1 << 3; // divide by zero
    private static final int FFLAGS_OF = 1 << 2; // overflow
    private static final int FFLAGS_UF = 1 << 1; // underflow
    private static final int FFLAGS_NX = 1; // inexact
    private static final int FFLAGS_MASK = 0x1f;
    private static final int FCSR_FRM_SHIFT = 5;
    private static final int FCSR_FRM_FIELD_MASK = 0x7;
    private static final int FCSR_MASK = 0xff;

    // rm field / frm encodings (Phase 5b). 5 and 6 are reserved; 7 selects frm dynamically and is
    // itself reserved there. See resolveRoundingMode.
    private static final int RM_RNE = 0;
    private static final int RM_RTZ = 1;
    private static final int RM_RDN = 2;
    private static final int RM_RUP = 3;
    private static final int RM_RMM = 4;
    private static final int RM_DYN = 7;

    /** Canonical quiet NaN bit pattern RISC-V mandates in place of any NaN payload it produces. */
    private static final int CANONICAL_NAN_BITS = 0x7fc00000;

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
     * Returns the {@code mip & mie} bits that are currently deliverable under the gating rule
     * documented on {@link #step}: every individually enabled interrupt while in user mode, or
     * only when {@code mstatus.MIE} is set while in machine mode. Shared by the pre-loop dispatch
     * and the {@code WFI} handler so both agree on what counts as a pending interrupt.
     */
    private static int pendingEnabledInterrupts(RV32IMAState state) {
        boolean interruptsGloballyEnabled =
                (state.extraflags & EXTRAFLAG_PRIV_MASK) == PRIV_USER || (state.mstatus & MSTATUS_MIE) != 0;
        return interruptsGloballyEnabled ? (state.mip & state.mie) : 0;
    }

    /**
     * Selects the highest-priority deliverable interrupt as an {@code mcause}-form trap code
     * ({@link #INT_MACHINE_EXTERNAL} > {@link #INT_MACHINE_SOFTWARE} > {@link #INT_MACHINE_TIMER}),
     * or {@code 0} if none is deliverable. Shared by the pre-loop dispatch and the in-batch
     * reevaluation after an interrupt-affecting CSR write or {@code MRET}.
     */
    private static int pendingInterruptTrap(RV32IMAState state) {
        int pendingEnabled = pendingEnabledInterrupts(state);
        if ((pendingEnabled & MIP_MEIP) != 0) {
            return INT_MACHINE_EXTERNAL;
        } else if ((pendingEnabled & MIP_MSIP) != 0) {
            return INT_MACHINE_SOFTWARE;
        } else if ((pendingEnabled & MIP_MTIP) != 0) {
            return INT_MACHINE_TIMER;
        }
        return 0;
    }

    /**
     * Computes a Zba/Zbb bit-manipulation instruction's result. Shared by the OP/OP-IMM decode
     * block's bitmanip branch; the caller has already validated that the {@code (isReg, funct7,
     * funct3, rs2Field)} combination is one of the encodings below.
     */
    private static int computeBitmanip(boolean isReg, int funct7, int funct3, int rs1, int rs2, int rs2Field) {
        if (isReg) {
            if (funct7 == 0x04) {
                return rs1 & 0xffff; // ZEXT.H
            }
            if (funct7 == 0x10) {
                return switch (funct3) {
                    case 2 -> (rs1 << 1) + rs2; // SH1ADD
                    case 4 -> (rs1 << 2) + rs2; // SH2ADD
                    default -> (rs1 << 3) + rs2; // SH3ADD (funct3 == 6)
                };
            }
            if (funct7 == 0x20) {
                return switch (funct3) {
                    case 4 -> ~(rs1 ^ rs2); // XNOR
                    case 6 -> rs1 | ~rs2; // ORN
                    default -> rs1 & ~rs2; // ANDN (funct3 == 7)
                };
            }
            if (funct7 == 0x05) {
                return switch (funct3) {
                    case 4 -> Math.min(rs1, rs2); // MIN
                    case 5 -> Integer.compareUnsigned(rs1, rs2) < 0 ? rs1 : rs2; // MINU
                    case 6 -> Math.max(rs1, rs2); // MAX
                    default -> Integer.compareUnsigned(rs1, rs2) > 0 ? rs1 : rs2; // MAXU (funct3 == 7)
                };
            }
            // funct7 == 0x30: ROL (funct3 == 1) / ROR (funct3 == 5)
            return funct3 == 1 ? Integer.rotateLeft(rs1, rs2 & 0x1f) : Integer.rotateRight(rs1, rs2 & 0x1f);
        }
        if (funct3 == 1) {
            return switch (rs2Field) {
                case 0 -> Integer.numberOfLeadingZeros(rs1); // CLZ
                case 1 -> Integer.numberOfTrailingZeros(rs1); // CTZ
                case 2 -> Integer.bitCount(rs1); // CPOP
                case 4 -> (byte) rs1; // SEXT.B
                default -> (short) rs1; // SEXT.H (rs2Field == 5)
            };
        }
        if (funct7 == 0x14) {
            // ORC.B: each result byte is all-ones if the corresponding source byte is nonzero, else 0.
            int result = 0;
            for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
                int shift = byteIndex * 8;
                if (((rs1 >>> shift) & 0xff) != 0) {
                    result |= 0xff << shift;
                }
            }
            return result;
        }
        if (funct7 == 0x34) {
            return Integer.reverseBytes(rs1); // REV8
        }
        return Integer.rotateRight(rs1, rs2Field); // RORI (funct7 == 0x30)
    }

    // ---- F extension (single-precision floating-point) support: Phase 5a --------------------
    //
    // This block covers only the rounding-mode-independent RV32F instructions: register-file and
    // fcsr plumbing, loads/stores, moves, sign injection, classification, comparisons, and
    // min/max. FADD/FSUB/FMUL/FDIV/FSQRT.S, the FMADD family, and FCVT.{W,WU}.S/FCVT.S.{W,WU} all
    // consult the rounding mode and are deferred to Phase 5b, along with `fflags` accrual beyond
    // NV (see the design discussion in this session: double-precision-as-intermediate is provably
    // safe for those ops' RNE and directed rounding modes, but that machinery doesn't exist yet).

    /** Writes {@code bits} into FP register {@code idx}, NaN-boxed (see {@code RV32IMAState.fregs}). */
    private static void writeFReg(RV32IMAState state, int idx, int bits) {
        state.fregs[idx] = 0xFFFFFFFF00000000L | (bits & 0xFFFFFFFFL);
    }

    /** Reads the low 32 bits of FP register {@code idx}. */
    private static int readFReg(RV32IMAState state, int idx) {
        return (int) state.fregs[idx];
    }

    private static boolean isNaN32(int bits) {
        return (bits & 0x7f800000) == 0x7f800000 && (bits & 0x007fffff) != 0;
    }

    private static boolean isSignalingNaN32(int bits) {
        return isNaN32(bits) && (bits & 0x00400000) == 0;
    }

    /** RISC-V {@code fclass.s}: a one-hot 10-bit classification of {@code bits}. */
    private static int fclassS(int bits) {
        boolean sign = bits < 0;
        int exp = (bits >>> 23) & 0xff;
        int mantissa = bits & 0x7fffff;
        if (exp == 0xff) {
            if (mantissa == 0) {
                return sign ? (1 << 0) : (1 << 7); // -infinity : +infinity
            }
            return ((mantissa & 0x400000) != 0) ? (1 << 9) : (1 << 8); // quiet NaN : signaling NaN
        }
        if (exp == 0) {
            if (mantissa == 0) {
                return sign ? (1 << 3) : (1 << 4); // -0 : +0
            }
            return sign ? (1 << 2) : (1 << 5); // -subnormal : +subnormal
        }
        return sign ? (1 << 1) : (1 << 6); // -normal : +normal
    }

    /**
     * RISC-V {@code feq.s}: quiet comparison. Only a signaling NaN operand sets {@code NV}; a
     * quiet NaN operand silently makes the result false.
     */
    private static int fEqS(RV32IMAState state, int aBits, int bBits) {
        if (isSignalingNaN32(aBits) || isSignalingNaN32(bBits)) {
            state.fcsr |= FFLAGS_NV;
        }
        if (isNaN32(aBits) || isNaN32(bBits)) {
            return 0;
        }
        return (Float.intBitsToFloat(aBits) == Float.intBitsToFloat(bBits)) ? 1 : 0;
    }

    /** RISC-V {@code flt.s}/{@code fle.s}: signaling comparisons — any NaN operand sets {@code NV}. */
    private static int fCompareS(RV32IMAState state, int aBits, int bBits, boolean orEqual) {
        if (isNaN32(aBits) || isNaN32(bBits)) {
            state.fcsr |= FFLAGS_NV;
            return 0;
        }
        float a = Float.intBitsToFloat(aBits);
        float b = Float.intBitsToFloat(bBits);
        return (orEqual ? (a <= b) : (a < b)) ? 1 : 0;
    }

    /**
     * RISC-V {@code fmin.s}/{@code fmax.s}. Not {@link Math#min(float, float)}/{@link
     * Math#max(float, float)}: those propagate NaN, where RISC-V returns the non-NaN operand
     * (canonical NaN only when both are NaN), and {@code -0.0} must compare below {@code +0.0}.
     */
    private static int fMinMaxS(RV32IMAState state, int aBits, int bBits, boolean max) {
        boolean aNaN = isNaN32(aBits);
        boolean bNaN = isNaN32(bBits);
        if (isSignalingNaN32(aBits) || isSignalingNaN32(bBits)) {
            state.fcsr |= FFLAGS_NV;
        }
        if (aNaN && bNaN) {
            return CANONICAL_NAN_BITS;
        }
        if (aNaN) {
            return bBits;
        }
        if (bNaN) {
            return aBits;
        }
        float a = Float.intBitsToFloat(aBits);
        float b = Float.intBitsToFloat(bBits);
        if (a == 0f && b == 0f) {
            boolean aNegativeZero = (aBits & 0x80000000) != 0;
            return (aNegativeZero == max) ? bBits : aBits;
        }
        return (max ? (a > b) : (a < b)) ? aBits : bBits;
    }

    // ---- F extension (single-precision floating-point) support: Phase 5b --------------------
    //
    // Covers the rounding-mode-dependent RV32F instructions deferred from Phase 5a:
    // FADD/FSUB/FMUL/FDIV/FSQRT.S, the FMADD family, and FCVT.{W,WU}.S/FCVT.S.{W,WU}.
    //
    // Numerical approach (advisor-reviewed): every basic op is computed as a double-precision
    // approximation `approx` of the true (infinite-precision) result, paired with the sign of the
    // residual `true - approx` (`residualSign`: -1/0/+1). `approx` is always within half a
    // double-ulp of the true result -- exact for FMUL (a float product needs at most 48 significant
    // bits) and FMADD's multiply term, and correctly-rounded via TwoSum (FADD/FSUB, and the FMA
    // family's addend step) or a residual-via-fma trick (FDIV, FSQRT) otherwise -- which is what
    // lets roundToFloat derive the float immediately below and above the true result (`lower`/
    // `upper`) without ever needing more than double precision, including at overflow (where
    // Math.nextUp(Float.MAX_VALUE) is +infinity) and subnormal boundaries. This does NOT extend to
    // computing an FMA as a double-rounded multiply-then-add: the multiply term is exact in double,
    // so folding the addend in via the same TwoSum used for FADD/FSUB gives a single correctly
    // rounded result for the whole fused expression, with no separate FMA machinery needed.

    /** Resolves an instruction's {@code rm} field to an effective rounding mode, consulting {@code
     * frm} for the dynamic selector (7). Returns -1 for a reserved mode (5, 6, or a dynamic
     * selector when {@code frm} itself holds a reserved value) -- the caller must trap
     * illegal-instruction without performing the operation or touching any register or flag. */
    private static int resolveRoundingMode(RV32IMAState state, int instrRm) {
        int rm = instrRm;
        if (rm == RM_DYN) {
            rm = (state.fcsr >>> FCSR_FRM_SHIFT) & FCSR_FRM_FIELD_MASK;
        }
        return (rm <= RM_RMM) ? rm : -1;
    }

    private static boolean isZero32(int bits) {
        return (bits & 0x7fffffff) == 0;
    }

    private static boolean isNegative32(int bits) {
        return bits < 0;
    }

    /** A double-precision approximation of an exact real result, paired with the sign of its
     * residual against the true value (see the Phase 5b design note above). */
    private record ExactApprox(double approx, int residualSign) {}

    /** Knuth's TwoSum: {@code s + e == a + b} exactly, for any doubles {@code a}, {@code b}. */
    private static ExactApprox exactSum(double a, double b) {
        double s = a + b;
        double v = s - a;
        double e = (a - (s - v)) + (b - v);
        int residualSign = e == 0.0 ? 0 : (e > 0.0 ? 1 : -1);
        return new ExactApprox(s, residualSign);
    }

    /** Exact: a float product needs at most 48 significant bits, well within double's 53. */
    private static ExactApprox exactMul(double a, double b) {
        return new ExactApprox(a * b, 0);
    }

    private static ExactApprox exactDiv(double a, double b) {
        double q = a / b;
        double r = Math.fma(-q, b, a); // exact residual of the numerator: a - q*b
        int rSign = r == 0.0 ? 0 : (r > 0.0 ? 1 : -1);
        int bSign = b >= 0.0 ? 1 : -1;
        return new ExactApprox(q, rSign * bSign); // true - q == r/b
    }

    private static ExactApprox exactSqrt(double a) {
        double s = Math.sqrt(a); // a >= 0 guaranteed by the caller
        double r = Math.fma(-s, s, a); // exact residual: a - s*s == (true - s)(true + s)
        int residualSign = r == 0.0 ? 0 : (r > 0.0 ? 1 : -1);
        return new ExactApprox(s, residualSign);
    }

    /** The multiply term of a*b+c is exact, so folding in c via TwoSum yields one correctly
     * rounded approximation of the whole fused expression -- no separate FMA rounding needed. */
    private static ExactApprox exactFma(double a, double b, double c) {
        return exactSum(exactMul(a, b).approx(), c);
    }

    /**
     * Rounds the double-precision approximation {@code approx} of an exact real result to the
     * nearest representable {@code float} per {@code rm}, given {@code residualSign} (the sign of
     * the true result minus {@code approx}). Accrues {@code NX}/{@code OF}/{@code UF} on {@code
     * state.fcsr}; the caller is responsible for {@code NV}/{@code DZ} and any special-value
     * short-circuiting (NaN, infinities, exact-cancellation zero sign) before calling this.
     *
     * <p>Because {@code approx} is always within half a double-ulp of the true result (see the
     * Phase 5b design note above), and a float midpoint is always exactly representable in double
     * (float's precision is far below double's), the true result can never fall strictly between
     * {@code approx} and the nearer of the two floats surrounding it -- so {@code lower}/{@code
     * upper} below are always the correct bracket.
     */
    private static float roundToFloat(RV32IMAState state, double approx, int residualSign, int rm) {
        float rne = (float) approx; // Java's narrowing conversion is round-to-nearest-even.
        double rneAsDouble = rne;
        int cmp = (approx != rneAsDouble) ? Double.compare(approx, rneAsDouble) : residualSign;
        if (cmp == 0) {
            return rne; // the true result is exactly representable as rne.
        }
        boolean trueAboveRne = cmp > 0;
        float lower = trueAboveRne ? rne : Math.nextDown(rne);
        float upper = trueAboveRne ? Math.nextUp(rne) : rne;
        boolean nonNegative = Double.doubleToRawLongBits(approx) >= 0;
        float result = switch (rm) {
            case RM_RTZ -> nonNegative ? lower : upper;
            case RM_RDN -> lower;
            case RM_RUP -> upper;
            case RM_RMM -> {
                boolean tie = residualSign == 0 && approx == (((double) lower + (double) upper) / 2.0);
                yield tie ? (nonNegative ? upper : lower) : rne;
            }
            default -> rne; // RM_RNE
        };
        state.fcsr |= FFLAGS_NX;
        if (Math.abs(approx) > Float.MAX_VALUE) {
            state.fcsr |= FFLAGS_OF;
        } else if (Math.abs(result) < Float.MIN_NORMAL) {
            state.fcsr |= FFLAGS_UF;
        }
        return result;
    }

    private static int fAddSubS(RV32IMAState state, int aBits, int bBits, boolean subtract, int rm) {
        if (isSignalingNaN32(aBits) || isSignalingNaN32(bBits)) {
            state.fcsr |= FFLAGS_NV;
        }
        if (isNaN32(aBits) || isNaN32(bBits)) {
            return CANONICAL_NAN_BITS;
        }
        int bEffBits = subtract ? (bBits ^ 0x80000000) : bBits;
        float a = Float.intBitsToFloat(aBits);
        float bEff = Float.intBitsToFloat(bEffBits);
        boolean aInf = Float.isInfinite(a);
        boolean bInf = Float.isInfinite(bEff);
        if (aInf || bInf) {
            if (aInf && bInf) {
                if (isNegative32(aBits) == isNegative32(bEffBits)) {
                    return aBits; // same-signed infinities: result is that infinity
                }
                state.fcsr |= FFLAGS_NV;
                return CANONICAL_NAN_BITS; // (+inf) + (-inf)
            }
            return aInf ? aBits : bEffBits;
        }
        boolean aZero = isZero32(aBits);
        boolean bZero = isZero32(bEffBits);
        if (aZero && bZero) {
            if (isNegative32(aBits) == isNegative32(bEffBits)) {
                return aBits; // same-signed zeros keep that sign in every rounding mode
            }
            return (rm == RM_RDN) ? 0x80000000 : 0x00000000;
        }
        if (aZero) {
            return bEffBits; // adding zero is exact
        }
        if (bZero) {
            return aBits;
        }
        if (a == -bEff) {
            // Exact cancellation of like-magnitude, opposite-signed nonzero operands: +0 in every
            // rounding mode except round-toward-negative (IEEE 754).
            return (rm == RM_RDN) ? 0x80000000 : 0x00000000;
        }
        ExactApprox exact = exactSum(a, bEff);
        return Float.floatToRawIntBits(roundToFloat(state, exact.approx(), exact.residualSign(), rm));
    }

    private static int fMulS(RV32IMAState state, int aBits, int bBits, int rm) {
        if (isSignalingNaN32(aBits) || isSignalingNaN32(bBits)) {
            state.fcsr |= FFLAGS_NV;
        }
        if (isNaN32(aBits) || isNaN32(bBits)) {
            return CANONICAL_NAN_BITS;
        }
        boolean resultNegative = isNegative32(aBits) != isNegative32(bBits);
        boolean aInf = Float.isInfinite(Float.intBitsToFloat(aBits));
        boolean bInf = Float.isInfinite(Float.intBitsToFloat(bBits));
        boolean aZero = isZero32(aBits);
        boolean bZero = isZero32(bBits);
        if ((aInf && bZero) || (aZero && bInf)) {
            state.fcsr |= FFLAGS_NV;
            return CANONICAL_NAN_BITS; // 0 * infinity
        }
        if (aInf || bInf) {
            return resultNegative ? 0xff800000 : 0x7f800000;
        }
        if (aZero || bZero) {
            return resultNegative ? 0x80000000 : 0x00000000;
        }
        ExactApprox exact = exactMul(Float.intBitsToFloat(aBits), Float.intBitsToFloat(bBits));
        return Float.floatToRawIntBits(roundToFloat(state, exact.approx(), exact.residualSign(), rm));
    }

    private static int fDivS(RV32IMAState state, int aBits, int bBits, int rm) {
        if (isSignalingNaN32(aBits) || isSignalingNaN32(bBits)) {
            state.fcsr |= FFLAGS_NV;
        }
        if (isNaN32(aBits) || isNaN32(bBits)) {
            return CANONICAL_NAN_BITS;
        }
        boolean resultNegative = isNegative32(aBits) != isNegative32(bBits);
        boolean aInf = Float.isInfinite(Float.intBitsToFloat(aBits));
        boolean bInf = Float.isInfinite(Float.intBitsToFloat(bBits));
        boolean aZero = isZero32(aBits);
        boolean bZero = isZero32(bBits);
        if (aInf && bInf) {
            state.fcsr |= FFLAGS_NV;
            return CANONICAL_NAN_BITS; // infinity / infinity
        }
        if (aZero && bZero) {
            state.fcsr |= FFLAGS_NV;
            return CANONICAL_NAN_BITS; // 0 / 0
        }
        if (bZero) {
            state.fcsr |= FFLAGS_DZ;
            return resultNegative ? 0xff800000 : 0x7f800000; // finite nonzero / 0
        }
        if (aInf) {
            return resultNegative ? 0xff800000 : 0x7f800000;
        }
        if (bInf || aZero) {
            return resultNegative ? 0x80000000 : 0x00000000;
        }
        ExactApprox exact = exactDiv(Float.intBitsToFloat(aBits), Float.intBitsToFloat(bBits));
        return Float.floatToRawIntBits(roundToFloat(state, exact.approx(), exact.residualSign(), rm));
    }

    private static int fSqrtS(RV32IMAState state, int aBits, int rm) {
        if (isSignalingNaN32(aBits)) {
            state.fcsr |= FFLAGS_NV;
        }
        if (isNaN32(aBits)) {
            return CANONICAL_NAN_BITS;
        }
        float a = Float.intBitsToFloat(aBits);
        if (a < 0f) { // excludes -0.0, for which sqrt(-0.0) == -0.0 with no flag
            state.fcsr |= FFLAGS_NV;
            return CANONICAL_NAN_BITS;
        }
        if (a == 0f || Float.isInfinite(a)) {
            return aBits; // sqrt(+-0) == +-0 (sign preserved); sqrt(+inf) == +inf
        }
        ExactApprox exact = exactSqrt(a);
        return Float.floatToRawIntBits(roundToFloat(state, exact.approx(), exact.residualSign(), rm));
    }

    /**
     * RISC-V fused multiply-add family: computes {@code (negA ? -a : a) * b + (negC ? -c : c)},
     * correctly rounded as a single operation (see the Phase 5b design note above). {@code negA}/
     * {@code negC} let the four FMADD/FMSUB/FNMSUB/FNMADD opcodes share one implementation.
     */
    private static int fmaS(RV32IMAState state, int aBits, int bBits, int cBits, boolean negA, boolean negC, int rm) {
        if (isSignalingNaN32(aBits) || isSignalingNaN32(bBits) || isSignalingNaN32(cBits)) {
            state.fcsr |= FFLAGS_NV;
        }
        if (isNaN32(aBits) || isNaN32(bBits) || isNaN32(cBits)) {
            return CANONICAL_NAN_BITS;
        }
        int effABits = negA ? (aBits ^ 0x80000000) : aBits;
        int effCBits = negC ? (cBits ^ 0x80000000) : cBits;
        boolean productNegative = isNegative32(effABits) != isNegative32(bBits);
        boolean aInf = Float.isInfinite(Float.intBitsToFloat(effABits));
        boolean bInf = Float.isInfinite(Float.intBitsToFloat(bBits));
        boolean aZero = isZero32(effABits);
        boolean bZero = isZero32(bBits);
        if ((aInf && bZero) || (aZero && bInf)) {
            state.fcsr |= FFLAGS_NV;
            return CANONICAL_NAN_BITS; // 0 * infinity in the product term
        }
        boolean productInf = aInf || bInf;
        boolean cInf = Float.isInfinite(Float.intBitsToFloat(effCBits));
        if (productInf && cInf && productNegative != isNegative32(effCBits)) {
            state.fcsr |= FFLAGS_NV;
            return CANONICAL_NAN_BITS; // (+-infinity product) + (opposite-signed infinity addend)
        }
        if (productInf) {
            return productNegative ? 0xff800000 : 0x7f800000;
        }
        if (cInf) {
            return effCBits;
        }
        boolean productZero = aZero || bZero;
        if (productZero) {
            if (isZero32(effCBits)) {
                if (productNegative == isNegative32(effCBits)) {
                    return productNegative ? 0x80000000 : 0x00000000;
                }
                return (rm == RM_RDN) ? 0x80000000 : 0x00000000;
            }
            return effCBits; // adding zero is exact
        }
        double a = Float.intBitsToFloat(effABits);
        double b = Float.intBitsToFloat(bBits);
        double c = Float.intBitsToFloat(effCBits);
        ExactApprox exact = exactFma(a, b, c);
        if (exact.approx() == 0.0 && exact.residualSign() == 0) {
            // Exact cancellation of a finite nonzero product against a finite nonzero addend.
            return (rm == RM_RDN) ? 0x80000000 : 0x00000000;
        }
        return Float.floatToRawIntBits(roundToFloat(state, exact.approx(), exact.residualSign(), rm));
    }

    /** {@code true - d} where {@code true} is the mathematical integer {@code d} rounds to per
     * {@code rm}. {@code d} always fits exactly in a double, so this is exact. */
    private static double roundToIntegerDouble(double d, int rm) {
        return switch (rm) {
            case RM_RTZ -> (d < 0) ? Math.ceil(d) : Math.floor(d);
            case RM_RDN -> Math.floor(d);
            case RM_RUP -> Math.ceil(d);
            case RM_RMM -> (d < 0) ? -Math.floor(-d + 0.5) : Math.floor(d + 0.5); // ties away from 0
            default -> Math.rint(d); // RM_RNE: ties to even
        };
    }

    /**
     * RISC-V {@code fcvt.w.s}/{@code fcvt.wu.s}: rounds {@code aBits} to an integer per {@code rm},
     * then range-checks the rounded value (not the pre-rounded one -- see class Javadoc example:
     * {@code fcvt.wu.s(-0.5)} is in range under RTZ, which rounds to {@code -0}, but out of range
     * under RDN, which rounds to {@code -1}). Saturates and sets {@code NV} (never {@code NX}) on
     * a NaN or out-of-range input; sets {@code NX} (never {@code NV}) when in range but inexact.
     */
    private static int fcvtWS(RV32IMAState state, int aBits, boolean unsigned, int rm) {
        if (isNaN32(aBits)) {
            state.fcsr |= FFLAGS_NV;
            return unsigned ? 0xffffffff : 0x7fffffff;
        }
        float a = Float.intBitsToFloat(aBits);
        if (Float.isInfinite(a)) {
            state.fcsr |= FFLAGS_NV;
            if (a > 0) {
                return unsigned ? 0xffffffff : 0x7fffffff;
            }
            return unsigned ? 0x00000000 : 0x80000000;
        }
        double d = a;
        double rounded = roundToIntegerDouble(d, rm);
        long min = unsigned ? 0L : Integer.MIN_VALUE;
        long max = unsigned ? 0xffffffffL : Integer.MAX_VALUE;
        if (rounded < min || rounded > max) {
            state.fcsr |= FFLAGS_NV;
            return (rounded < min) ? (unsigned ? 0x00000000 : 0x80000000) : (unsigned ? 0xffffffff : 0x7fffffff);
        }
        if (rounded != d) {
            state.fcsr |= FFLAGS_NX;
        }
        return (int) (long) rounded;
    }

    /** RISC-V {@code fcvt.s.w}/{@code fcvt.s.wu}: {@code value} widens to double exactly (an int32
     * always fits), so rounding it to float via {@link #roundToFloat} needs no residual machinery
     * -- only {@code NX} can ever be accrued here (the magnitude is always far inside float's
     * normal range). */
    private static int fcvtSW(RV32IMAState state, int value, boolean unsigned, int rm) {
        double d = unsigned ? (double) Integer.toUnsignedLong(value) : (double) value;
        return Float.floatToRawIntBits(roundToFloat(state, d, 0, rm));
    }

    // ---- RV32C (compressed instruction) support: Phase 4 ------------------------------------
    //
    // decodeCompressed(int) expands one 16-bit RVC encoding into a representative 32-bit RV32I/M
    // word using the standard, non-scrambled encoding for each target instruction; the caller
    // feeds that word through the ordinary opcode switch in step() unmodified. This works because
    // every RVC instruction is semantically an alias for a base RV32I/M operation with a smaller
    // register or immediate field. The bit-shuffle formulas below (which fields of the 16-bit
    // word compose each immediate) are taken from the reference simulator's decoder
    // (riscv-isa-sim, riscv/decode.h's rvc_* helpers), not hand-derived, and cross-checked against
    // the authoritative riscv-opcodes tables (extensions/rv_c, rv32_c). A reserved or
    // unimplemented 16-bit pattern returns 0 -- an opcode with no case in the switch below, so its
    // default branch produces the same illegal-instruction trap a bad 32-bit encoding would.
    //
    // Quadrant-0 funct3 1/5 (C.FLD/C.FSD) and quadrant-2 funct3 1/5 (C.FLDSP/C.FSDSP) are the
    // D-extension slots -- valid encodings on RV32DC, but D is not decoded by this core (only its
    // misa bit exists, see IsaConfig.hasD), so they still fall through to the reserved case below.
    // Quadrant-0 funct3 3/7 (C.FLW/C.FSW) and quadrant-2 funct3 3/7 (C.FLWSP/C.FSWSP) are the
    // F-extension slots and expand into FLW/FSW below (same immediate-decode helpers as the
    // integer C.LW/C.SW/C.LWSP/C.SWSP forms they're structurally identical to); the resulting
    // 32-bit FLW/FSW re-enters the ordinary opcode switch, whose own IsaConfig.hasF check traps
    // illegal-instruction if F isn't enabled -- decodeCompressed itself stays IsaConfig-agnostic.

    private static int encodeRType(int opcode, int funct3, int funct7, int rd, int rs1, int rs2) {
        return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode;
    }

    private static int encodeIType(int opcode, int funct3, int rd, int rs1, int imm12) {
        return ((imm12 & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode;
    }

    private static int encodeSType(int opcode, int funct3, int rs1, int rs2, int imm12) {
        return (((imm12 >> 5) & 0x7f) << 25)
                | (rs2 << 20)
                | (rs1 << 15)
                | (funct3 << 12)
                | ((imm12 & 0x1f) << 7)
                | opcode;
    }

    /** {@code value} is the already-shifted upper immediate; only its top 20 bits are used. */
    private static int encodeUType(int opcode, int rd, int value) {
        return (value & 0xfffff000) | (rd << 7) | opcode;
    }

    /** Inverse of the JAL decode in the opcode switch's {@code case 0x6F} arm. */
    private static int encodeJal(int rd, int jumpOffset) {
        return ((jumpOffset & 0x100000) << 11)
                | ((jumpOffset & 0x7fe) << 20)
                | ((jumpOffset & 0x800) << 9)
                | (jumpOffset & 0xff000)
                | (rd << 7)
                | 0x6f;
    }

    /** Inverse of the branch decode in the opcode switch's {@code case 0x63} arm. */
    private static int encodeBranch(int funct3, int rs1, int rs2, int branchOffset) {
        return ((branchOffset & 0x1000) << 19)
                | ((branchOffset & 0x7e0) << 20)
                | (rs2 << 20)
                | (rs1 << 15)
                | (funct3 << 12)
                | ((branchOffset & 0x1e) << 7)
                | ((branchOffset & 0x800) >> 4)
                | 0x63;
    }

    /** General signed 6-bit CI-format immediate (bit 12 &lt;&lt; 5 | bits 6:2). Used by C.ADDI,
     * C.LI, C.ANDI, and (before an additional {@code &lt;&lt; 12}) C.LUI. */
    private static int rvcSignedImm6(int c) {
        int imm = ((c >>> 2) & 0x1f) | (((c >>> 12) & 0x1) << 5);
        return (imm << 26) >> 26;
    }

    private static int rvcAddi4spnImm(int c) {
        return (((c >>> 6) & 0x1) << 2)
                | (((c >>> 5) & 0x1) << 3)
                | (((c >>> 11) & 0x3) << 4)
                | (((c >>> 7) & 0xf) << 6);
    }

    /** Shared by C.LW and C.SW. */
    private static int rvcLwImm(int c) {
        return (((c >>> 6) & 0x1) << 2) | (((c >>> 10) & 0x7) << 3) | (((c >>> 5) & 0x1) << 6);
    }

    private static int rvcAddi16spImm(int c) {
        int imm = (((c >>> 6) & 0x1) << 4)
                | (((c >>> 2) & 0x1) << 5)
                | (((c >>> 5) & 0x1) << 6)
                | (((c >>> 3) & 0x3) << 7)
                | (((c >>> 12) & 0x1) << 9);
        return (imm << 22) >> 22;
    }

    private static int rvcLwspImm(int c) {
        return (((c >>> 4) & 0x7) << 2) | (((c >>> 12) & 0x1) << 5) | (((c >>> 2) & 0x3) << 6);
    }

    private static int rvcSwspImm(int c) {
        return (((c >>> 9) & 0xf) << 2) | (((c >>> 7) & 0x3) << 6);
    }

    /** Shared by C.J and C.JAL. */
    private static int rvcJImm(int c) {
        int imm = (((c >>> 3) & 0x7) << 1)
                | (((c >>> 11) & 0x1) << 4)
                | (((c >>> 2) & 0x1) << 5)
                | (((c >>> 7) & 0x1) << 6)
                | (((c >>> 6) & 0x1) << 7)
                | (((c >>> 9) & 0x3) << 8)
                | (((c >>> 8) & 0x1) << 10)
                | (((c >>> 12) & 0x1) << 11);
        return (imm << 20) >> 20;
    }

    /** Shared by C.BEQZ and C.BNEZ. */
    private static int rvcBImm(int c) {
        int imm = (((c >>> 3) & 0x3) << 1)
                | (((c >>> 10) & 0x3) << 3)
                | (((c >>> 2) & 0x1) << 5)
                | (((c >>> 5) & 0x3) << 6)
                | (((c >>> 12) & 0x1) << 8);
        return (imm << 23) >> 23;
    }

    /**
     * Expands one 16-bit RVC-encoded instruction ({@code c}, zero-extended into an {@code int})
     * into a representative 32-bit RV32I/M word, or returns {@code 0} for a reserved or
     * unimplemented pattern. See the block comment above for the overall approach.
     */
    private static int decodeCompressed(int c) {
        int funct3 = (c >>> 13) & 0x7;
        int rdRs1Full = (c >>> 7) & 0x1f;
        int rs2Full = (c >>> 2) & 0x1f;
        int primeHigh = 8 + ((c >>> 7) & 0x7); // bits 9:7 "prime" register field, +8
        int primeLow = 8 + ((c >>> 2) & 0x7); // bits 4:2 "prime" register field, +8

        switch (c & 0x3) {
            case 0:
                return switch (funct3) {
                    case 0 -> { // C.ADDI4SPN
                        int imm = rvcAddi4spnImm(c);
                        yield imm == 0 ? 0 : encodeIType(0x13, 0, primeLow, 2, imm); // ADDI rd', x2, imm
                    }
                    case 2 -> encodeIType(0x03, 2, primeLow, primeHigh, rvcLwImm(c)); // LW rd', imm(rs1')
                    case 3 -> encodeIType(0x07, 2, primeLow, primeHigh, rvcLwImm(c)); // C.FLW: FLW rd', imm(rs1')
                    case 6 -> encodeSType(0x23, 2, primeHigh, primeLow, rvcLwImm(c)); // SW rs2', imm(rs1')
                    case 7 -> encodeSType(0x27, 2, primeHigh, primeLow, rvcLwImm(c)); // C.FSW: FSW rs2', imm(rs1')
                    default -> 0; // reserved, or C.FLD/C.FSD (D, not implemented)
                };
            case 1:
                return switch (funct3) {
                    case 0 -> encodeIType(0x13, 0, rdRs1Full, rdRs1Full, rvcSignedImm6(c)); // C.ADDI / C.NOP
                    case 1 -> encodeJal(1, rvcJImm(c)); // C.JAL (RV32-only; rd = x1)
                    case 2 -> encodeIType(0x13, 0, rdRs1Full, 0, rvcSignedImm6(c)); // C.LI
                    case 3 -> { // C.LUI / C.ADDI16SP
                        if (rdRs1Full == 2) {
                            int imm = rvcAddi16spImm(c);
                            yield imm == 0 ? 0 : encodeIType(0x13, 0, 2, 2, imm); // ADDI x2, x2, imm
                        }
                        int imm = rvcSignedImm6(c);
                        yield imm == 0 ? 0 : encodeUType(0x37, rdRs1Full, imm << 12); // LUI rd, imm
                    }
                    case 4 -> decodeCompressedArithCluster(c, primeHigh, primeLow);
                    case 5 -> encodeJal(0, rvcJImm(c)); // C.J (no link)
                    case 6 -> encodeBranch(0, primeHigh, 0, rvcBImm(c)); // C.BEQZ: BEQ rs1', x0, off
                    default -> encodeBranch(1, primeHigh, 0, rvcBImm(c)); // C.BNEZ: BNE rs1', x0, off
                };
            case 2:
                return switch (funct3) {
                    case 0 -> // C.SLLI
                        ((c >>> 12) & 0x1) != 0
                                ? 0 // shamt[5] set: reserved on RV32
                                : encodeIType(0x13, 1, rdRs1Full, rdRs1Full, (c >>> 2) & 0x1f);
                    case 2 -> // C.LWSP
                        rdRs1Full == 0 ? 0 : encodeIType(0x03, 2, rdRs1Full, 2, rvcLwspImm(c));
                    // C.FLWSP: FLW rd, imm(x2) -- unlike C.LWSP, rd == 0 (f0) is legal: f0 is an
                    // ordinary FP register, not hardwired zero (see RV32IMAState.fregs's Javadoc).
                    case 3 -> encodeIType(0x07, 2, rdRs1Full, 2, rvcLwspImm(c));
                    case 4 -> decodeCompressedJumpMoveCluster(c, rdRs1Full, rs2Full);
                    case 6 -> encodeSType(0x23, 2, 2, rs2Full, rvcSwspImm(c)); // C.SWSP
                    case 7 -> encodeSType(0x27, 2, 2, rs2Full, rvcSwspImm(c)); // C.FSWSP: FSW rs2, imm(x2)
                    default -> 0; // reserved, or C.FLDSP/C.FSDSP (D, not implemented)
                };
            default: // quadrant 3: not a compressed encoding; the caller never reaches this
                return 0;
        }
    }

    /** Quadrant 1, funct3 4: C.SRLI / C.SRAI / C.ANDI / C.SUB / C.XOR / C.OR / C.AND. */
    private static int decodeCompressedArithCluster(int c, int rdRs1P, int rs2P) {
        boolean shamtOverflow = ((c >>> 12) & 0x1) != 0;
        int shamt = (c >>> 2) & 0x1f;
        return switch ((c >>> 10) & 0x3) {
            case 0 -> shamtOverflow ? 0 : encodeIType(0x13, 5, rdRs1P, rdRs1P, shamt); // C.SRLI
            case 1 -> shamtOverflow ? 0 : encodeIType(0x13, 5, rdRs1P, rdRs1P, 0x400 | shamt); // C.SRAI
            case 2 -> encodeIType(0x13, 7, rdRs1P, rdRs1P, rvcSignedImm6(c)); // C.ANDI
            default -> { // bits 11:10 == 3
                if (((c >>> 12) & 0x1) != 0) {
                    yield 0; // reserved on RV32 (RV64 C.SUBW/C.ADDW live here)
                }
                yield switch ((c >>> 5) & 0x3) {
                    case 0 -> encodeRType(0x33, 0, 0x20, rdRs1P, rdRs1P, rs2P); // C.SUB
                    case 1 -> encodeRType(0x33, 4, 0, rdRs1P, rdRs1P, rs2P); // C.XOR
                    case 2 -> encodeRType(0x33, 6, 0, rdRs1P, rdRs1P, rs2P); // C.OR
                    default -> encodeRType(0x33, 7, 0, rdRs1P, rdRs1P, rs2P); // C.AND
                };
            }
        };
    }

    /** Quadrant 2, funct3 4: C.JR / C.MV / C.EBREAK / C.JALR / C.ADD. */
    private static int decodeCompressedJumpMoveCluster(int c, int rdRs1Full, int rs2Full) {
        boolean linking = ((c >>> 12) & 0x1) != 0;
        if (!linking) {
            if (rs2Full == 0) {
                return rdRs1Full == 0 ? 0 : encodeIType(0x67, 0, 0, rdRs1Full, 0); // C.JR
            }
            return encodeRType(0x33, 0, 0, rdRs1Full, 0, rs2Full); // C.MV: ADD rd, x0, rs2
        }
        if (rs2Full == 0) {
            if (rdRs1Full == 0) {
                return (1 << 20) | 0x73; // C.EBREAK: SYSTEM, csr# 1, funct3 0, rs1 0, rd 0
            }
            return encodeIType(0x67, 0, 1, rdRs1Full, 0); // C.JALR: JALR x1, rs1, 0
        }
        return rdRs1Full == 0 ? 0 : encodeRType(0x33, 0, 0, rdRs1Full, rdRs1Full, rs2Full); // C.ADD
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
         * @param pc the guest PC of the instruction, not yet advanced to the next instruction
         *     (which may be {@code pc + 2} or {@code pc + 4}; see {@code IsaConfig.hasC}).
         * @param ir the 32-bit word the opcode decoder acted on; zero if fetch failed before
         *     decoding. For a compressed (RVC) instruction (Phase 4, {@code IsaConfig.hasC}), this
         *     is {@code RV32IMACore}'s internal 32-bit expansion of the 16-bit encoding, not the
         *     original 16-bit bits — the same value used for {@code mtval} on an
         *     illegal-instruction trap raised from a bad compressed encoding.
         * @param trap zero for normal execution; otherwise the trap cause (internal encoding,
         *     before being committed to {@code mcause}).
         */
        void onPostExec(int pc, int ir, int trap);
    }

    private int readCsr(RV32IMAState state, CSRHook csrHook, int csrno, long cycle) {
        if (isaConfig.hasF()) {
            switch (csrno) {
                case 0x001:
                    return state.fcsr & FFLAGS_MASK;
                case 0x002:
                    return (state.fcsr >>> FCSR_FRM_SHIFT) & FCSR_FRM_FIELD_MASK;
                case 0x003:
                    return state.fcsr & FCSR_MASK;
                default:
                    break;
            }
        }
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
        if (isaConfig.hasF()) {
            switch (csrno) {
                case 0x001:
                    state.fcsr = (state.fcsr & ~FFLAGS_MASK) | (writeValue & FFLAGS_MASK);
                    return;
                case 0x002:
                    state.fcsr = (state.fcsr & ~(FCSR_FRM_FIELD_MASK << FCSR_FRM_SHIFT))
                            | ((writeValue & FCSR_FRM_FIELD_MASK) << FCSR_FRM_SHIFT);
                    return;
                case 0x003:
                    state.fcsr = writeValue & FCSR_MASK;
                    return;
                default:
                    break;
            }
        }
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

        int trap = 0;
        int rval = 0;
        int pc = state.pc;
        int ir = 0;
        // Length in bytes of the instruction currently being processed: 4 normally, or 2 for a
        // compressed (RVC) instruction when IsaConfig.hasC is set. Every place that computes "the
        // PC after this instruction" -- JAL/JALR/branch targets, the loop's PC advance, and the
        // pending-interrupt PC correction below -- uses this instead of a literal 4, so those stay
        // correct regardless of whether the instruction that ran was compressed. It is reset to 4
        // at the top of every loop iteration and only lowered to 2 once the fetch stage confirms a
        // compressed encoding; outside the loop (the pending-interrupt path immediately below, where
        // no instruction has been fetched at all) it stays at its initial value of 4.
        int instrLen = 4;
        boolean hasC = isaConfig.hasC();
        long cycle = state.getCycle();

        // Check for a pending machine interrupt before starting the instruction loop. This core
        // models only M-mode and U-mode, so mstatus.MIE gates interrupts only while executing in
        // M-mode: per the privileged spec, a machine interrupt that is individually enabled in
        // mie is always taken while running below M-mode (here, U-mode), regardless of
        // mstatus.MIE. Priority when more than one bit is simultaneously pending and enabled:
        // external > software > timer.
        trap = pendingInterruptTrap(state);

        // If WFI, don't run processor -- unless an enabled interrupt is already pending, in which
        // case the stall ends here and the interrupt is delivered below. This check deliberately
        // comes after pendingInterruptTrap so a pending bit that was set before the WFI
        // executed (or set directly on mip by an embedder, without injectInterrupt's WFI clear)
        // cannot leave the hart stalled with a deliverable interrupt.
        if ((state.extraflags & EXTRAFLAG_WFI) != 0) {
            if (trap == 0) {
                return 1;
            }
            state.extraflags &= ~EXTRAFLAG_WFI;
        }

        // Set by an instruction that can change interrupt deliverability (a write to mstatus,
        // mie, or mip, or MRET); checked once that instruction has fully retired so a newly
        // deliverable interrupt is taken before the next guest instruction in this batch runs.
        boolean reevaluateInterrupts = false;

        if (trap != 0) {
            pc -= instrLen; // Will be incremented back to original PC in the interrupt handler
        } else {
            for (int icount = 0; icount < count; icount++) {
                ir = 0;
                rval = 0;
                instrLen = 4;
                cycle++;
                // Privilege is stable for the duration of one instruction: nothing a load, store,
                // or AMO does can change it before the AccessContext below is built.
                int privilege = state.extraflags & EXTRAFLAG_PRIV_MASK;
                int ofsPc = pc - ramOffset;

                if (Integer.compareUnsigned(ofsPc, ramSize) >= 0) {
                    trap = exceptionTrap(EXC_INSTRUCTION_ACCESS_FAULT);
                    rval = pc;
                    break;
                } else if (hasC ? (ofsPc & 1) != 0 : (ofsPc & 3) != 0) {
                    trap = exceptionTrap(EXC_INSTRUCTION_MISALIGNED);
                    rval = pc;
                    break;
                } else {
                    try {
                        if (hasC) {
                            // Probe the halfword at pc first: bits[1:0] == 0b11 means a normal
                            // 32-bit instruction (which may start at a non-word-aligned address
                            // here, since a preceding compressed instruction can leave pc at an
                            // odd multiple of 2 -- readInt supports that), anything else means a
                            // 16-bit compressed instruction, and no second read is needed.
                            AccessContext fetchCtx16 =
                                    new AccessContext(state.hartId, privilege, AccessKind.FETCH, 2, 0);
                            int lowHalf = mem.readShort(pc, fetchCtx16) & 0xffff;
                            if ((lowHalf & 0x3) == 0x3) {
                                AccessContext fetchCtx32 =
                                        new AccessContext(state.hartId, privilege, AccessKind.FETCH, 4, 0);
                                ir = mem.readInt(pc, fetchCtx32);
                            } else {
                                // decodeCompressed expands the 16-bit encoding into an equivalent
                                // standard 32-bit RV32I/M word that the opcode switch below can
                                // execute unmodified, or returns an opcode with no case in that
                                // switch (its default branch already means illegal instruction) for
                                // any reserved 16-bit pattern.
                                ir = decodeCompressed(lowHalf);
                                instrLen = 2;
                            }
                        } else {
                            AccessContext fetchCtx = new AccessContext(state.hartId, privilege, AccessKind.FETCH, 4, 0);
                            ir = mem.readInt(pc, fetchCtx);
                        }
                    } catch (IndexOutOfBoundsException _) {
                        // The ramOffset/ramSize check above is only a coarse precheck; a bus can
                        // still reject a fetch within that window (for example, fine-grained MPU
                        // enforcement, or -- with hasC -- a 32-bit fetch that starts within the
                        // window but whose last bytes fall past ramSize/the bus's own bounds).
                        // Convert that rejection into the same instruction access-fault trap as the
                        // coarse-window check, rather than letting the exception propagate to the
                        // caller.
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
                                    | (ir & 0x000ff000);
                            if ((jumpOffset & 0x00100000) != 0) jumpOffset |= 0xffe00000;
                            rval = pc + instrLen;
                            pc = pc + jumpOffset - instrLen;
                            break;
                        }
                        case 0x67: // JALR
                        {
                            int imm = ir >>> 20;
                            int immSext = imm | (((imm & 0x800) != 0) ? 0xfffff000 : 0);
                            rval = pc + instrLen;
                            pc = ((state.regs[(ir >> 15) & 0x1f] + immSext) & ~1) - instrLen;
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
                            branchOffset = pc + branchOffset - instrLen;
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
                        case 0x07: // FLW
                        {
                            if (!isaConfig.hasF() || ((ir >> 12) & 0x7) != 2) {
                                trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                break;
                            }
                            int rs1 = state.regs[(ir >> 15) & 0x1f];
                            int imm = ir >>> 20;
                            int immSext = imm | (((imm & 0x800) != 0) ? 0xfffff000 : 0);
                            int addr = rs1 + immSext;
                            int fRd = rdid;
                            rdid = 0;

                            try {
                                int bits = mem.readInt(
                                        addr, new AccessContext(state.hartId, privilege, AccessKind.LOAD, 4, 0));
                                writeFReg(state, fRd, bits);
                            } catch (IndexOutOfBoundsException _) {
                                trap = exceptionTrap(EXC_LOAD_ACCESS_FAULT);
                                rval = addr;
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
                            } catch (IndexOutOfBoundsException _) {
                                trap = exceptionTrap(EXC_LOAD_ACCESS_FAULT);
                                rval = addr;
                            }
                            // Note: C code had some MMIO checks here, but our MemoryBus handles it via MMIOBus
                            break;
                        }
                        case 0x27: // FSW
                        {
                            if (!isaConfig.hasF() || ((ir >> 12) & 0x7) != 2) {
                                trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                break;
                            }
                            int rs1 = state.regs[(ir >> 15) & 0x1f];
                            int fSrcBits = readFReg(state, (ir >> 20) & 0x1f);
                            int imm = ((ir >> 7) & 0x1f) | ((ir & 0xfe000000) >> 20);
                            if ((imm & 0x800) != 0) imm |= 0xfffff000;
                            int addr = rs1 + imm;
                            rdid = 0;

                            try {
                                mem.writeInt(
                                        addr,
                                        fSrcBits,
                                        new AccessContext(state.hartId, privilege, AccessKind.STORE, 4, 0));
                                state.reservationValid = false;
                            } catch (IndexOutOfBoundsException _) {
                                trap = exceptionTrap(EXC_STORE_ACCESS_FAULT);
                                rval = addr;
                            }
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
                            } catch (IndexOutOfBoundsException _) {
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
                            int rs2Field = imm & 0x1f;

                            boolean zba = isaConfig.hasZba();
                            boolean zbb = isaConfig.hasZbb();
                            boolean zbaShAdd =
                                    isReg && zba && funct7 == 0x10 && (funct3 == 2 || funct3 == 4 || funct3 == 6);
                            boolean zbbLogicNegate =
                                    isReg && zbb && funct7 == 0x20 && (funct3 == 4 || funct3 == 6 || funct3 == 7);
                            boolean zbbMinMax = isReg && zbb && funct7 == 0x05 && funct3 >= 4;
                            boolean zbbRotateReg = isReg && zbb && funct7 == 0x30 && (funct3 == 1 || funct3 == 5);
                            boolean zbbZextH = isReg && zbb && funct7 == 0x04 && funct3 == 4 && rs2Field == 0;
                            boolean zbbCountOrSext = !isReg
                                    && zbb
                                    && funct3 == 1
                                    && funct7 == 0x30
                                    && (rs2Field == 0
                                            || rs2Field == 1
                                            || rs2Field == 2
                                            || rs2Field == 4
                                            || rs2Field == 5);
                            boolean zbbRori = !isReg && zbb && funct3 == 5 && funct7 == 0x30;
                            boolean zbbOrcb = !isReg && zbb && funct3 == 5 && funct7 == 0x14 && rs2Field == 0x07;
                            boolean zbbRev8 = !isReg && zbb && funct3 == 5 && funct7 == 0x34 && rs2Field == 0x18;
                            boolean isBitmanip = zbaShAdd
                                    || zbbLogicNegate
                                    || zbbMinMax
                                    || zbbRotateReg
                                    || zbbZextH
                                    || zbbCountOrSext
                                    || zbbRori
                                    || zbbOrcb
                                    || zbbRev8;

                            boolean legalEncoding;
                            if (isBitmanip) {
                                legalEncoding = true;
                            } else if (isReg) {
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

                            if (isBitmanip) {
                                rval = computeBitmanip(isReg, funct7, funct3, rs1, rs2, rs2Field);
                            } else if (isReg && funct7 == 1) {
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
                        case 0x43: // FMADD.S
                        case 0x47: // FMSUB.S
                        case 0x4B: // FNMSUB.S
                        case 0x4F: // FNMADD.S
                        {
                            // bits 26:25 are the fmt field (00=S, 01=D, 10=H, 11=Q); D/H/Q are not
                            // implemented, so anything but S traps illegal-instruction below.
                            if (!isaConfig.hasF() || ((ir >>> 25) & 0x3) != 0) {
                                trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                break;
                            }
                            int fmaRs1 = (ir >> 15) & 0x1f;
                            int fmaRs2 = (ir >> 20) & 0x1f;
                            int fmaRs3 = (ir >>> 27) & 0x1f;
                            int rm = resolveRoundingMode(state, (ir >> 12) & 0x7);
                            if (rm < 0) {
                                trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                break;
                            }
                            int fRd = rdid;
                            rdid = 0;
                            boolean negA = opcode == 0x4B || opcode == 0x4F; // FNMSUB / FNMADD
                            boolean negC = opcode == 0x47 || opcode == 0x4F; // FMSUB / FNMADD
                            int result = fmaS(
                                    state,
                                    readFReg(state, fmaRs1),
                                    readFReg(state, fmaRs2),
                                    readFReg(state, fmaRs3),
                                    negA,
                                    negC,
                                    rm);
                            writeFReg(state, fRd, result);
                            break;
                        }
                        case 0x53: // OP-FP (Phase 5a subset; rounding-mode-dependent ops are Phase 5b)
                        {
                            if (!isaConfig.hasF()) {
                                trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                break;
                            }
                            // funct7 bits 26:25 are the "fmt" field (00=S, 01=D, 10=H, 11=Q); every
                            // funct7 case below has fmt=00, so the exact match already rejects the
                            // D/Q-format encodings of these same operations without a separate check.
                            int funct7 = (ir >>> 25) & 0x7f;
                            int fFunct3 = (ir >> 12) & 0x7;
                            int fRs1 = (ir >> 15) & 0x1f;
                            int fRs2 = (ir >> 20) & 0x1f;
                            int fRd = rdid;
                            int aBits = readFReg(state, fRs1);
                            int bBits = readFReg(state, fRs2);

                            switch (funct7) {
                                case 0x00: // FADD.S
                                case 0x04: // FSUB.S
                                case 0x08: // FMUL.S
                                case 0x0C: // FDIV.S
                                {
                                    int rm = resolveRoundingMode(state, fFunct3);
                                    if (rm < 0) {
                                        trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                        break;
                                    }
                                    rdid = 0;
                                    int result = switch (funct7) {
                                        case 0x00 -> fAddSubS(state, aBits, bBits, false, rm);
                                        case 0x04 -> fAddSubS(state, aBits, bBits, true, rm);
                                        case 0x08 -> fMulS(state, aBits, bBits, rm);
                                        default -> fDivS(state, aBits, bBits, rm); // 0x0C
                                    };
                                    writeFReg(state, fRd, result);
                                    break;
                                }
                                case 0x2C: // FSQRT.S -- fRs2 must be 0 (fixed operand slot, unused)
                                    if (fRs2 != 0) {
                                        trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                        break;
                                    }
                                    int sqrtRm = resolveRoundingMode(state, fFunct3);
                                    if (sqrtRm < 0) {
                                        trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                        break;
                                    }
                                    rdid = 0;
                                    writeFReg(state, fRd, fSqrtS(state, aBits, sqrtRm));
                                    break;
                                case 0x60: // FCVT.W.S / FCVT.WU.S -- integer destination
                                    if (fRs2 > 1) {
                                        trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION); // 2/3: RV64 forms
                                        break;
                                    }
                                    int cvtWRm = resolveRoundingMode(state, fFunct3);
                                    if (cvtWRm < 0) {
                                        trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                        break;
                                    }
                                    rval = fcvtWS(state, aBits, fRs2 == 1, cvtWRm);
                                    break;
                                case 0x68: // FCVT.S.W / FCVT.S.WU -- fRs1 is an INTEGER register
                                    if (fRs2 > 1) {
                                        trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION); // 2/3: RV64 forms
                                        break;
                                    }
                                    int cvtSRm = resolveRoundingMode(state, fFunct3);
                                    if (cvtSRm < 0) {
                                        trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                        break;
                                    }
                                    rdid = 0;
                                    writeFReg(state, fRd, fcvtSW(state, state.regs[fRs1], fRs2 == 1, cvtSRm));
                                    break;
                                case 0x10: // FSGNJ.S / FSGNJN.S / FSGNJX.S
                                    rdid = 0;
                                    switch (fFunct3) {
                                        case 0:
                                            writeFReg(state, fRd, (bBits & 0x80000000) | (aBits & 0x7fffffff));
                                            break;
                                        case 1:
                                            writeFReg(state, fRd, (~bBits & 0x80000000) | (aBits & 0x7fffffff));
                                            break;
                                        case 2:
                                            writeFReg(
                                                    state, fRd, ((aBits ^ bBits) & 0x80000000) | (aBits & 0x7fffffff));
                                            break;
                                        default:
                                            trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                    }
                                    break;
                                case 0x14: // FMIN.S / FMAX.S
                                    rdid = 0;
                                    switch (fFunct3) {
                                        case 0:
                                            writeFReg(state, fRd, fMinMaxS(state, aBits, bBits, false));
                                            break;
                                        case 1:
                                            writeFReg(state, fRd, fMinMaxS(state, aBits, bBits, true));
                                            break;
                                        default:
                                            trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                    }
                                    break;
                                case 0x50: // FLE.S / FLT.S / FEQ.S -- integer destination
                                    switch (fFunct3) {
                                        case 0:
                                            rval = fCompareS(state, aBits, bBits, true);
                                            break;
                                        case 1:
                                            rval = fCompareS(state, aBits, bBits, false);
                                            break;
                                        case 2:
                                            rval = fEqS(state, aBits, bBits);
                                            break;
                                        default:
                                            trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                    }
                                    break;
                                case 0x70: // FMV.X.W / FCLASS.S -- integer destination
                                    if (fRs2 != 0) {
                                        trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                        break;
                                    }
                                    switch (fFunct3) {
                                        case 0:
                                            rval = aBits;
                                            break; // FMV.X.W
                                        case 1:
                                            rval = fclassS(aBits);
                                            break; // FCLASS.S
                                        default:
                                            trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                    }
                                    break;
                                case 0x78: // FMV.W.X -- rs1 is an INTEGER register here, not FP
                                    if (fRs2 != 0 || fFunct3 != 0) {
                                        trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                        break;
                                    }
                                    rdid = 0;
                                    writeFReg(state, fRd, state.regs[fRs1]);
                                    break;
                                default:
                                    trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                            }
                            break;
                        }
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
                                    if (csrno == 0x300 || csrno == 0x304 || csrno == 0x344) {
                                        reevaluateInterrupts = true; // mstatus, mie, mip
                                    }
                                }
                            } else if (microop == 0) {
                                // SYSTEM (MRET, ECALL, etc.)
                                rdid = 0;
                                if (((ir >> 7) & 0x1f) != 0 || ((ir >> 15) & 0x1f) != 0) {
                                    // rd and rs1 are reserved (must be zero) for every funct3 == 0
                                    // SYSTEM instruction; any other encoding is illegal.
                                    trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                } else if (csrno == 0x302) {
                                    // MRET
                                    if ((state.extraflags & EXTRAFLAG_PRIV_MASK) != PRIV_MACHINE) {
                                        // MRET is a machine-mode-only instruction: executing it below
                                        // M-mode is an illegal instruction, whatever MPP holds.
                                        trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                        break;
                                    }
                                    int startmstatus = state.mstatus;
                                    int startextraflags = state.extraflags;
                                    state.mstatus = (startmstatus & ~(MSTATUS_MIE | MSTATUS_MPIE | MSTATUS_MPP))
                                            | ((startmstatus & MSTATUS_MPIE) >> 4)
                                            | MSTATUS_MPIE;
                                    state.extraflags = (startextraflags & ~EXTRAFLAG_PRIV_MASK)
                                            | ((startmstatus & MSTATUS_MPP) >> MSTATUS_MPP_SHIFT);
                                    pc = state.mepc - instrLen;
                                    reevaluateInterrupts = true; // MIE and privilege both changed
                                } else {
                                    switch (csrno) {
                                        case 0: // ECALL
                                            // Only M-mode (3) and U-mode (0) are modelled; any
                                            // non-user privilege is treated as machine here.
                                            trap = exceptionTrap(
                                                    ((state.extraflags & EXTRAFLAG_PRIV_MASK) != PRIV_USER)
                                                            ? EXC_ECALL_FROM_M
                                                            : EXC_ECALL_FROM_U);
                                            break;
                                        case 1: // EBREAK
                                            trap = exceptionTrap(EXC_BREAKPOINT);
                                            break;
                                        case 0x105: // WFI
                                            state.mstatus |= MSTATUS_MIE;
                                            state.setCycle(cycle);
                                            state.pc = pc + instrLen;
                                            if (pendingEnabledInterrupts(state) != 0) {
                                                // An enabled interrupt is already pending (for example,
                                                // MSIP injected before this WFI, or an interrupt that
                                                // mstatus.MIE was masking until the line above set
                                                // it). WFI completes immediately without stalling;
                                                // the next step call delivers the interrupt.
                                                return 0;
                                            }
                                            state.extraflags |= EXTRAFLAG_WFI;
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

                            boolean validAtomicOperation = switch (irmid) {
                                case 0, 1, 2, 3, 4, 8, 12, 16, 20, 24, 28 -> true;
                                default -> false;
                            };
                            boolean isWordWidth = funct3 == 2;
                            boolean isSubWordWidth = isaConfig.hasZabha() && (funct3 == 0 || funct3 == 1);
                            // Zabha omits byte/halfword LR.W/SC.W (irmid 2, 3) -- only the nine RMW ops
                            // get sub-word width.
                            boolean subWordOperationAllowed = isSubWordWidth && irmid != 2 && irmid != 3;
                            if (!validAtomicOperation || !(isWordWidth || subWordOperationAllowed)) {
                                trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                break;
                            }

                            if (irmid == 2 && ((ir >> 20) & 0x1f) != 0) {
                                // LR.W's rs2 field is reserved and must be zero.
                                trap = exceptionTrap(EXC_ILLEGAL_INSTRUCTION);
                                break;
                            }

                            int width = isWordWidth ? 4 : (funct3 == 1 ? 2 : 1);
                            // Every LR.W/SC.W attempt consumes the hart's local reservation, whether it
                            // goes on to succeed, fail, or trap (misaligned below, or a bus fault in
                            // the switch). Decide SC's local validity first, then clear, so no exit
                            // path -- including the alignment trap -- can leave a stale reservation
                            // that a later SC without a fresh LR could consume.
                            boolean scLocallyValid =
                                    irmid == 3 && state.reservationValid && state.reservationAddr == rs1;
                            if (irmid == 2 || irmid == 3) {
                                state.reservationValid = false;
                            }
                            // LR/SC and AMOs must be naturally aligned to their width (the misaligned
                            // atomicity granule PMA is not modelled). Checked before any bus call so a
                            // misaligned atomic never reaches the bus, whose atomicRmw/tryScAndStore
                            // contract promises aligned addresses. Ordinary loads and stores keep
                            // their misaligned tolerance; only atomics trap here.
                            if ((rs1 & (width - 1)) != 0) {
                                trap = exceptionTrap(irmid == 2 ? EXC_LOAD_MISALIGNED : EXC_STORE_MISALIGNED);
                                rval = rs1;
                                break;
                            }
                            int accessFaultTrap =
                                    exceptionTrap(irmid == 2 ? EXC_LOAD_ACCESS_FAULT : EXC_STORE_ACCESS_FAULT);
                            // irmid is the funct5 encoding; also AccessContext.atomicOp. LR.W is irmid 2 --
                            // a multi-hart bus detects it via ctx.kind() == AMO && ctx.atomicOp() == 2 on
                            // this readInt override, per AccessContext's Javadoc.
                            AccessContext amoCtx =
                                    new AccessContext(state.hartId, privilege, AccessKind.AMO, width, irmid);
                            try {
                                switch (irmid) {
                                    case 2: // LR.W
                                        // The previous reservation was already dropped above; a
                                        // successful read establishes the new one. A faulting read
                                        // leaves none.
                                        rval = mem.readInt(rs1, amoCtx);
                                        state.reservationAddr = rs1;
                                        state.reservationValid = true;
                                        break;
                                    case 3: // SC.W
                                        // The local reservation was consumed above, before the bus is
                                        // consulted, so a thrown access fault cannot skip the clear.
                                        if (scLocallyValid) {
                                            // Local fast-path pre-check passed (Design Decision §5); the bus
                                            // still makes the final atomic decision -- it may reject even
                                            // though the local state says valid, if a cross-hart
                                            // invalidation landed between this hart's LR and SC.
                                            rval = mem.tryScAndStore(state.hartId, rs1, rs2, amoCtx);
                                        } else {
                                            // Locally failed SC: no store happens, but the spec still
                                            // requires the SC to pass memory permission checks before it
                                            // retires, so the bus gets a side-effect-free probe that may
                                            // throw (-> store/AMO access fault) instead of a write.
                                            mem.checkAccess(rs1, amoCtx);
                                            rval = 1;
                                        }
                                        break;
                                    default: // the 9 validated non-LR/SC AMOs (ADD/SWAP/XOR/AND/OR/MIN[U]/MAX[U])
                                        rval = mem.atomicRmw(rs1, irmid, rs2, amoCtx);
                                        break;
                                }
                            } catch (IndexOutOfBoundsException _) {
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
                pc += instrLen;

                if (reevaluateInterrupts) {
                    // The instruction that just retired changed mstatus/mie/mip or executed MRET;
                    // an interrupt it made deliverable must be taken before any further guest
                    // instruction. Per the privileged spec, xRET and interrupt-CSR writes require
                    // immediate reevaluation, not just the ordinary bounded-delay rule. pc already
                    // points at the next instruction; back it up by instrLen so the trap handler's
                    // "+ instrLen" for interrupts lands mepc exactly there (same idiom as the
                    // pre-loop dispatch), whatever the retired instruction's length was.
                    reevaluateInterrupts = false;
                    trap = pendingInterruptTrap(state);
                    if (trap != 0) {
                        pc -= instrLen;
                        break;
                    }
                }
            }
        }

        // Handle traps and interrupts.
        if (trap != 0) {
            if ((trap & INTERRUPT_FLAG) != 0) {
                state.mcause = trap;
                state.mtval = 0;
                pc += instrLen;
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
