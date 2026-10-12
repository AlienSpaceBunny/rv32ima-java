package com.alienspacebunny.emu;

/**
 * Mutable state of a single RV32IMA hart.
 *
 * <p>The retired-instruction count and the {@code mcycle}/{@code minstret} offsets are reached
 * through accessors ({@link #getInstret()}, {@link #getMcycle()}, {@link #getMinstret()} and
 * their setters). Every other field is public, in two categories:
 *
 * <ul>
 *   <li><em>Stable API fields</em> — safe to read and write between calls to {@link
 *       RV32IMACore#step}: {@link #regs}, {@link #pc}, {@link #hartId}, {@link #mstatus}, {@link
 *       #mscratch}, {@link #mtvec}, {@link #mie}, {@link #mip}, {@link #mepc}, {@link #mtval},
 *       {@link #mcause}, {@link #extraflags}, {@link #reservationAddr}, {@link
 *       #reservationValid}, {@link #fregs}, {@link #fcsr}, {@link #mcounteren}.
 *   <li><em>CLINT layout fields</em> — {@link #cyclel}, {@link #cycleh}, {@link #timerl}, {@link
 *       #timerh}, {@link #timermatchl}, {@link #timermatchh}. These are the raw 32-bit halves of
 *       the 64-bit cycle counter, machine timer ({@code mtime}), and timer-compare value ({@code
 *       mtimecmp}). They are public so that a CLINT MMIO hook can implement byte-accurate register
 *       access. Prefer the accessor methods ({@link #getCycle()}, {@link #getTimer()}, {@link
 *       #getTimerMatch()}, and the setters) for all other use.
 * </ul>
 */
public class RV32IMAState {
    /**
     * A practically distant timer compare, about 584,000 years at 1 MHz. It is not a disable bit:
     * a time of all ones is still due. A new state holds it, so a fresh hart has no timer pending;
     * an embedder writes it again to cancel the timer on reset.
     */
    public static final long TIMER_COMPARE_NEVER = -1L;

    /**
     * Integer register file x0–x31. x0 is not hardwired to zero in this array; {@link
     * RV32IMACore} prevents writes to x0 during instruction execution, but this invariant is not
     * enforced on external access. Keep {@code regs[0]} at zero.
     */
    public final int[] regs = new int[32];

    /** Program counter. */
    public int pc;

    /**
     * Identifies this hart among others sharing a {@link MemoryBus}. Defaults to {@code 0}.
     *
     * <p>{@link RV32IMACore} does not read or write this field itself as of the Phase 1
     * foundation work — it exists so an embedder can assign a stable identity per hart before
     * first use. A multi-hart-aware {@code MemoryBus} implementation is expected to key
     * per-hart state (for example, LR/SC reservation ownership) by this value once bus access
     * metadata is threaded through (see {@code docs/FEATURE_REQUEST_PLAN.md} §2–§3). Embedders
     * with more than one concurrently participating hart must assign distinct, stable IDs.
     */
    public int hartId;

    /**
     * Machine status register ({@code mstatus}). Key bits: bit 3 (MIE) — machine interrupt
     * enable; bit 7 (MPIE) — prior MIE saved on trap entry; bits 12–11 (MPP) — prior privilege
     * level saved on trap entry.
     */
    public int mstatus;

    /** Low 32 bits of the 64-bit cycle counter. See {@link #getCycle()} and {@link #setCycle}. */
    public int cyclel;

    /** High 32 bits of the 64-bit cycle counter. See {@link #getCycle()} and {@link #setCycle}. */
    public int cycleh;

    /**
     * Low 32 bits of the hart's latest time sample. See {@link #getTimer()}; the core overwrites
     * it at every sample.
     */
    public int timerl;

    /**
     * High 32 bits of the hart's latest time sample. See {@link #getTimer()}; the core overwrites
     * it at every sample.
     */
    public int timerh;

    /**
     * Low 32 bits of {@code mtimecmp} (timer compare). See {@link #getTimerMatch()} and {@link
     * #setTimerMatch}.
     */
    public int timermatchl = (int) TIMER_COMPARE_NEVER;

    /**
     * High 32 bits of {@code mtimecmp} (timer compare). See {@link #getTimerMatch()} and {@link
     * #setTimerMatch}.
     */
    public int timermatchh = (int) (TIMER_COMPARE_NEVER >>> 32);

    /** Machine scratch register ({@code mscratch}). */
    public int mscratch;

    /** Machine trap-handler base address ({@code mtvec}). */
    public int mtvec;

    /**
     * Machine interrupt-enable register ({@code mie}). Bit 3 (MSIE) enables software interrupts,
     * bit 7 (MTIE) enables the timer interrupt, bit 11 (MEIE) enables external interrupts. These
     * are the only implemented bits: a guest write keeps only them, and guest reads, {@code WFI}
     * wakeup and interrupt delivery ignore any other bit a host edit sets here.
     */
    public int mie;

    /**
     * Machine interrupt-pending register ({@code mip}). Bit 7 (MTIP) is managed entirely by the
     * core: set when the latest time sample is at or past {@code mtimecmp}, unsigned, and cleared
     * otherwise. Bits 3 (MSIP) and 11 (MEIP) are set by the embedder, typically via {@link
     * RV32IMACore#injectInterrupt}, and cleared by the embedder, for example when the guest
     * acknowledges its device. All three are read-only to the guest: a guest write to {@code mip}
     * changes nothing. Guest reads, {@code WFI} wakeup and interrupt delivery ignore any other bit
     * a host edit sets here.
     */
    public int mip;

    /** Machine exception program counter ({@code mepc}). Set to the trapping PC on trap entry. */
    public int mepc;

    /**
     * Machine trap value ({@code mtval}). On load/store access faults this holds the faulting
     * address. On illegal-instruction traps it holds the faulting instruction encoding. Zero for
     * interrupts and other trap causes.
     */
    public int mtval;

    /** Machine cause register ({@code mcause}). Holds the cause code on trap entry. */
    public int mcause;

    /**
     * Internal state flags carried from the mini-rv32ima state layout.
     *
     * <ul>
     *   <li>Bits 0–1: privilege level. Machine mode = {@code 3}; user mode = {@code 0}.
     *   <li>Bit 2: WFI flag. Set when an executed {@code WFI} stalls the hart; cleared at a
     *       {@link RV32IMACore#step} entry that finds a pending, locally enabled interrupt (see
     *       {@code step}'s WFI paragraph). An embedder may also clear it to release a stall.
     * </ul>
     */
    public int extraflags;

    /**
     * Guest physical address held in the LR/SC reservation, valid only when {@link
     * #reservationValid} is true.
     */
    public int reservationAddr;

    /** Whether this hart currently holds an active LR/SC reservation. */
    public boolean reservationValid;

    /**
     * FP register file f0–f31 ({@code IsaConfig.hasF}, Phase 5). Stored as {@code long} rather
     * than {@code float} so that a later D extension does not need a breaking storage-width
     * change to this public field — see {@code docs/FEATURE_REQUEST_PLAN.md} Design Decision §8.
     * Every write NaN-boxes the value (sets the upper 32 bits to all-ones, per the standard
     * RISC-V convention for a register wider than the value it holds); {@code RV32IMACore} reads
     * only the low 32 bits under F alone. f0 is not hardwired to zero — unlike {@link #regs}, all
     * 32 FP registers are ordinary read/write registers.
     */
    public final long[] fregs = new long[32];

    /**
     * FP control and status register ({@code fcsr}, {@code IsaConfig.hasF}, Phase 5). Bits 7–5
     * are the rounding mode ({@code frm}); bits 4–0 are the accrued exception flags ({@code
     * fflags}: NV, DZ, OF, UF, NX from bit 4 down to bit 0). Also addressable piecewise as CSRs
     * {@code 0x001} ({@code fflags}) and {@code 0x002} ({@code frm}).
     */
    public int fcsr;

    /**
     * Machine counter-enable register ({@code mcounteren}). Bits 0 (CY), 1 (TM) and 2 (IR) let
     * user mode read {@code cycle}, {@code time} and {@code instret}, with their high halves; a
     * clear bit makes such a read an illegal instruction. These are the only implemented bits: a
     * guest write keeps only them, and guest reads and the user-mode check ignore any other bit a
     * host edit sets here. Starts at 0.
     */
    public int mcounteren;

    // Retired instructions, and the offsets that make mcycle and minstret from the two work
    // counts. Package-private so the core can apply a guest write without rebasing its locals.
    private long instret;
    long mcycleOffset;
    long minstretOffset;

    /**
     * Creates a new {@code RV32IMAState} with every field zero or false, except the timer compare,
     * which is {@link #TIMER_COMPARE_NEVER}.
     */
    public RV32IMAState() {}

    /**
     * Returns the 64-bit cycle counter composed from {@link #cycleh} and {@link #cyclel}: the
     * instructions the hart attempted, including ones that raised an exception and fetch faults,
     * but not interrupt entries or {@code WFI} stalls. The guest never changes it; its writes to
     * {@code mcycle} go to {@link #getMcycle()}'s offset instead.
     *
     * @return the 64-bit cycle count.
     */
    public long getCycle() {
        return (Integer.toUnsignedLong(cycleh) << 32) | Integer.toUnsignedLong(cyclel);
    }

    /**
     * Sets the 64-bit cycle counter split across {@link #cycleh} and {@link #cyclel}. The {@code
     * mcycle} offset is kept, so {@link #getMcycle()} moves by the same amount.
     *
     * @param cycle the new 64-bit cycle count.
     */
    public void setCycle(long cycle) {
        this.cyclel = (int) cycle;
        this.cycleh = (int) (cycle >>> 32);
    }

    /**
     * Returns the retired-instruction count: instructions that completed, including an executed
     * {@code WFI} and an instruction followed by an interrupt, but not ones that raised an
     * exception ({@code ECALL}, {@code EBREAK} and fetch faults included), interrupt entries or
     * {@code WFI} stalls. The guest never changes it, so its deltas are a work count for the
     * embedder; its writes to {@code minstret} go to {@link #getMinstret()}'s offset instead.
     *
     * @return the 64-bit retired count, unsigned.
     */
    public long getInstret() {
        return instret;
    }

    /**
     * Sets the retired-instruction count. The {@code minstret} offset is kept, so {@link
     * #getMinstret()} moves by the same amount.
     *
     * @param instret the new 64-bit retired count, unsigned.
     */
    public void setInstret(long instret) {
        this.instret = instret;
    }

    /**
     * Returns the architectural {@code mcycle}, which the guest reads as {@code cycle} or {@code
     * mcycle}: {@link #getCycle()} plus a per-hart offset that guest writes to {@code mcycle} and
     * {@code mcycleh} change.
     *
     * @return the 64-bit {@code mcycle} value, unsigned.
     */
    public long getMcycle() {
        return getCycle() + mcycleOffset;
    }

    /**
     * Sets the architectural {@code mcycle} by changing its offset; {@link #getCycle()} is
     * unchanged. A reset that wants both at zero calls {@code setCycle(0)} and then {@code
     * setMcycle(0)}.
     *
     * @param mcycle the new 64-bit {@code mcycle} value, unsigned.
     */
    public void setMcycle(long mcycle) {
        mcycleOffset = mcycle - getCycle();
    }

    /**
     * Returns the architectural {@code minstret}, which the guest reads as {@code instret} or
     * {@code minstret}: {@link #getInstret()} plus a per-hart offset that guest writes to {@code
     * minstret} and {@code minstreth} change. Use {@link #getInstret()} deltas, not these, for
     * host accounting.
     *
     * @return the 64-bit {@code minstret} value, unsigned.
     */
    public long getMinstret() {
        return instret + minstretOffset;
    }

    /**
     * Sets the architectural {@code minstret} by changing its offset; {@link #getInstret()} is
     * unchanged.
     *
     * @param minstret the new 64-bit {@code minstret} value, unsigned.
     */
    public void setMinstret(long minstret) {
        minstretOffset = minstret - instret;
    }

    /**
     * Returns the hart's latest sample of its core's {@link MachineTimeSource}, composed from
     * {@link #timerh} and {@link #timerl}. This is not a live clock: it goes stale while the hart
     * is halted or between calls. Read the source itself for the current time.
     *
     * @return the latest sample, in microseconds, unsigned.
     */
    public long getTimer() {
        return (Integer.toUnsignedLong(timerh) << 32) | Integer.toUnsignedLong(timerl);
    }

    /**
     * Returns the 64-bit timer compare value ({@code mtimecmp}) composed from {@link #timermatchh}
     * and {@link #timermatchl}.
     *
     * @return the 64-bit {@code mtimecmp} value.
     */
    public long getTimerMatch() {
        return (Integer.toUnsignedLong(timermatchh) << 32) | Integer.toUnsignedLong(timermatchl);
    }

    /**
     * Sets the 64-bit timer compare value ({@code mtimecmp}) split across {@link #timermatchh}
     * and {@link #timermatchl}.
     *
     * @param timerMatch the new 64-bit {@code mtimecmp} value, unsigned; {@link
     *     #TIMER_COMPARE_NEVER} for a deadline that is practically never reached.
     */
    public void setTimerMatch(long timerMatch) {
        this.timermatchl = (int) timerMatch;
        this.timermatchh = (int) (timerMatch >>> 32);
    }
}
