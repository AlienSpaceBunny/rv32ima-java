package com.alienspacebunny.emu;

/**
 * Mutable state of a single RV32IMA hart.
 *
 * <p>All fields are public. They fall into two categories:
 *
 * <ul>
 *   <li><em>Stable API fields</em> — safe to read and write between calls to {@link
 *       RV32IMACore#step}: {@link #regs}, {@link #pc}, {@link #hartId}, {@link #mstatus}, {@link
 *       #mscratch}, {@link #mtvec}, {@link #mie}, {@link #mip}, {@link #mepc}, {@link #mtval},
 *       {@link #mcause}, {@link #extraflags}, {@link #reservationAddr}, {@link
 *       #reservationValid}.
 *   <li><em>CLINT layout fields</em> — {@link #cyclel}, {@link #cycleh}, {@link #timerl}, {@link
 *       #timerh}, {@link #timermatchl}, {@link #timermatchh}. These are the raw 32-bit halves of
 *       the 64-bit cycle counter, machine timer ({@code mtime}), and timer-compare value ({@code
 *       mtimecmp}). They are public so that a CLINT MMIO hook can implement byte-accurate register
 *       access. Prefer the accessor methods ({@link #getCycle()}, {@link #getTimer()}, {@link
 *       #getTimerMatch()}, and their setters) for all other use.
 * </ul>
 */
public class RV32IMAState {
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
     * Low 32 bits of {@code mtime} (machine timer). See {@link #getTimer()} and {@link
     * #setTimer}.
     */
    public int timerl;

    /**
     * High 32 bits of {@code mtime} (machine timer). See {@link #getTimer()} and {@link
     * #setTimer}.
     */
    public int timerh;

    /**
     * Low 32 bits of {@code mtimecmp} (timer compare). See {@link #getTimerMatch()} and {@link
     * #setTimerMatch}.
     */
    public int timermatchl;

    /**
     * High 32 bits of {@code mtimecmp} (timer compare). See {@link #getTimerMatch()} and {@link
     * #setTimerMatch}.
     */
    public int timermatchh;

    /** Machine scratch register ({@code mscratch}). */
    public int mscratch;

    /** Machine trap-handler base address ({@code mtvec}). */
    public int mtvec;

    /**
     * Machine interrupt-enable register ({@code mie}). Bit 3 (MSIE) enables software interrupts,
     * bit 7 (MTIE) enables the timer interrupt, bit 11 (MEIE) enables external interrupts.
     */
    public int mie;

    /**
     * Machine interrupt-pending register ({@code mip}). Bit 7 (MTIP) is managed entirely by the
     * core: set when {@code mtime >= mtimecmp} (and {@code mtimecmp != 0}), cleared otherwise. Bits
     * 3 (MSIP) and 11 (MEIP) are set by the embedder, typically via {@link
     * RV32IMACore#injectInterrupt}, and cleared by guest or embedder acknowledgement.
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
     *   <li>Bit 2: WFI flag. Set when the hart executes {@code WFI}; cleared when a pending
     *       interrupt arrives.
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

    /** Creates a new {@code RV32IMAState} with all fields at their default values (zero/false). */
    public RV32IMAState() {}

    /**
     * Returns the 64-bit cycle counter composed from {@link #cycleh} and {@link #cyclel}.
     *
     * @return the 64-bit cycle count.
     */
    public long getCycle() {
        return (Integer.toUnsignedLong(cycleh) << 32) | Integer.toUnsignedLong(cyclel);
    }

    /**
     * Sets the 64-bit cycle counter split across {@link #cycleh} and {@link #cyclel}.
     *
     * @param cycle the new 64-bit cycle count.
     */
    public void setCycle(long cycle) {
        this.cyclel = (int) cycle;
        this.cycleh = (int) (cycle >>> 32);
    }

    /**
     * Returns the 64-bit machine timer ({@code mtime}) composed from {@link #timerh} and {@link
     * #timerl}.
     *
     * @return the 64-bit {@code mtime} value.
     */
    public long getTimer() {
        return (Integer.toUnsignedLong(timerh) << 32) | Integer.toUnsignedLong(timerl);
    }

    /**
     * Sets the 64-bit machine timer ({@code mtime}) split across {@link #timerh} and {@link
     * #timerl}.
     *
     * @param timer the new 64-bit {@code mtime} value.
     */
    public void setTimer(long timer) {
        this.timerl = (int) timer;
        this.timerh = (int) (timer >>> 32);
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
     * @param timerMatch the new 64-bit {@code mtimecmp} value.
     */
    public void setTimerMatch(long timerMatch) {
        this.timermatchl = (int) timerMatch;
        this.timermatchh = (int) (timerMatch >>> 32);
    }
}
