package com.alienspacebunny.emu;

/**
 * Interface for custom CSR (Control and Status Register) hooks.
 */
public interface CSRHook {
    /**
     * Handle a custom CSR read.
     * @return The value read from the CSR.
     */
    int handleRead(int csrNo);

    /**
     * Handle a custom CSR write.
     */
    void handleWrite(int csrNo, int value);
}
