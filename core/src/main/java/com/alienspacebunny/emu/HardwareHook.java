package com.alienspacebunny.emu;

/**
 * Interface for injectable hardware hooks (MMIO).
 */
public interface HardwareHook {
    /**
     * Handle a memory-mapped I/O write.
     * @return true if the write was handled, false if it should fall through (or be ignored).
     */
    boolean handleWrite(int address, int value, int width);

    /**
     * Handle a memory-mapped I/O load.
     * @param address The address being read.
     * @param width The width of the read (1, 2, or 4 bytes).
     * @return The value read from the hardware.
     */
    int handleRead(int address, int width);
}
