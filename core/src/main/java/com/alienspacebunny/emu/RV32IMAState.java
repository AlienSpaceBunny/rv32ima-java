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
     * Note: only a few bits are used. (Machine = 3, User = 0)
     * Bits 0..1 = privilege.
     * Bit 2 = WFI (Wait for interrupt)
     * Bit 3+ = Load/Store reservation LSBs.
     */
    public int extraflags;

    public long getCycle() {
        return (Integer.toUnsignedLong(cycleh) << 32) | Integer.toUnsignedLong(cyclel);
    }

    public void setCycle(long cycle) {
        this.cyclel = (int) cycle;
        this.cycleh = (int) (cycle >>> 32);
    }

    public long getTimer() {
        return (Integer.toUnsignedLong(timerh) << 32) | Integer.toUnsignedLong(timerl);
    }

    public void setTimer(long timer) {
        this.timerl = (int) timer;
        this.timerh = (int) (timer >>> 32);
    }

    public long getTimerMatch() {
        return (Integer.toUnsignedLong(timermatchh) << 32) | Integer.toUnsignedLong(timermatchl);
    }

    public void setTimerMatch(long timerMatch) {
        this.timermatchl = (int) timerMatch;
        this.timermatchh = (int) (timerMatch >>> 32);
    }
}
