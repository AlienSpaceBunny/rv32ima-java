package com.alienspacebunny.cli;

import com.alienspacebunny.emu.HardwareHook;

/**
 * SYSCON hook for poweroff and reboot.
 */
public class SysconHook implements HardwareHook {
    private int lastStatus = 0;

    @Override
    public boolean handleWrite(int address, int value, int width) {
        if (address == 0x11100000) {
            lastStatus = value;
            return true;
        }
        return false;
    }

    @Override
    public int handleRead(int address, int width) {
        return 0;
    }

    public int getLastStatus() {
        return lastStatus;
    }
}
