package com.alienspacebunny.emu;

/**
 * Represents the state of a RISC-V RV32IMA processor.
 * Mirrors the struct MiniRV32IMAState from the C implementation.
 */
public class RV32IMAState {
    public final int[] regs = new int[32];

    public int pc;
    public int mstatus;
    public int cyclel;
    public int cycleh;

    public int timerl;
    public int timerh;
    public int timermatchl;
    public int timermatchh;

    public int mscratch;
    public int mtvec;
    public int mie;
    public int mip;

    public int mepc;
    public int mtval;
    public int mcause;

    /**
     * Internal flags carried over from the mini-rv32ima state layout.
     *
     * <p>Note: only a few bits are used. (Machine = 3, User = 0)
     * Bits 0..1 = privilege.
     * Bit 2 = WFI (Wait for interrupt)
     * Bit 3+ = Load/Store reservation LSBs.
     */
    public int extraflags;

    /**
     * Returns the 64-bit cycle counter composed from {@link #cycleh} and
     * {@link #cyclel}.
     */
    public long getCycle() {
        return (Integer.toUnsignedLong(cycleh) << 32) | Integer.toUnsignedLong(cyclel);
    }

    /**
     * Sets the 64-bit cycle counter split across {@link #cycleh} and
     * {@link #cyclel}.
     */
    public void setCycle(long cycle) {
        this.cyclel = (int) cycle;
        this.cycleh = (int) (cycle >>> 32);
    }

    /**
     * Returns the 64-bit machine timer composed from {@link #timerh} and
     * {@link #timerl}.
     */
    public long getTimer() {
        return (Integer.toUnsignedLong(timerh) << 32) | Integer.toUnsignedLong(timerl);
    }

    /**
     * Sets the 64-bit machine timer split across {@link #timerh} and
     * {@link #timerl}.
     */
    public void setTimer(long timer) {
        this.timerl = (int) timer;
        this.timerh = (int) (timer >>> 32);
    }

    /**
     * Returns the 64-bit timer compare value composed from {@link #timermatchh}
     * and {@link #timermatchl}.
     */
    public long getTimerMatch() {
        return (Integer.toUnsignedLong(timermatchh) << 32) | Integer.toUnsignedLong(timermatchl);
    }

    /**
     * Sets the 64-bit timer compare value split across {@link #timermatchh} and
     * {@link #timermatchl}.
     */
    public void setTimerMatch(long timerMatch) {
        this.timermatchl = (int) timerMatch;
        this.timermatchh = (int) (timerMatch >>> 32);
    }
}
