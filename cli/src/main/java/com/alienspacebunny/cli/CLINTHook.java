package com.alienspacebunny.cli;

import com.alienspacebunny.emu.HardwareHook;
import com.alienspacebunny.emu.RV32IMAState;

/**
 * CLINT (Core Local Interruptor) hook for timer interrupts.
 */
public class CLINTHook implements HardwareHook {
    private final RV32IMAState state;

    public CLINTHook(RV32IMAState state) {
        this.state = state;
    }

    @Override
    public void handleWrite(int address, int value, int width) {
        if (address == 0x11004004) {
            state.timermatchh = value;
        } else if (address == 0x11004000) {
            state.timermatchl = value;
        }
    }

    @Override
    public int handleRead(int address, int width) {
        if (address == 0x1100bffc) {
            return state.timerh;
        } else if (address == 0x1100bff8) {
            return state.timerl;
        }
        return 0;
    }
}
