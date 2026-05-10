package com.alienspacebunny.cli;

import com.alienspacebunny.emu.HardwareHook;
import java.io.IOException;

/**
 * Basic UART 8250 / 16550 emulator hook.
 */
public class UARTHook implements HardwareHook {
    @Override
    public void handleWrite(int address, int value, int width) {
        if (address == 0x10000000) {
            System.out.print((char) value);
            System.out.flush();
        }
    }

    @Override
    public int handleRead(int address, int width) {
        if (address == 0x10000005) {
            try {
                return 0x60 | (System.in.available() > 0 ? 1 : 0);
            } catch (IOException e) {
                return 0x60;
            }
        } else if (address == 0x10000000) {
            try {
                if (System.in.available() > 0) {
                    return System.in.read();
                }
            } catch (IOException e) {
                // Ignore
            }
            return 0;
        }
        return 0;
    }
}
