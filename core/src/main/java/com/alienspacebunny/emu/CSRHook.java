package com.alienspacebunny.emu;

/**
 * Interface for custom CSR (Control and Status Register) hooks.
 *
 * <p>{@link RV32IMACore} handles built-in machine CSRs first. This hook is
 * called for unknown/custom CSRs only when CSR instruction side-effect rules
 * require the read or write to occur.
 */
public interface CSRHook {
    /**
     * Handle a custom CSR read.
     *
     * @param csrNo The 12-bit CSR number.
     * @return The value read from the CSR.
     */
    int handleRead(int csrNo);

    /**
     * Handle a custom CSR write.
     *
     * @param csrNo The 12-bit CSR number.
     * @param value The 32-bit value to write.
     */
    void handleWrite(int csrNo, int value);
}
