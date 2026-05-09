package com.alienspacebunny.emu;

/**
 * Interface for injectable hardware hooks (MMIO).
 *
 * <p>A hook owns the entire address range it is registered for. Once
 * {@link MMIOBus} matches a range, reads and writes are routed to that hook and
 * never fall through to the backing RAM bus.
 */
public interface HardwareHook {
    /**
     * Handle a memory-mapped I/O write.
     *
     * Unrecognized offsets inside the registered range should be ignored or
     * handled according to the device's own contract.
     *
     * @param address The unsigned 32-bit guest address being written.
     * @param value The write value, masked to {@code width} bytes for byte and
     *     halfword writes.
     * @param width The write width in bytes: 1, 2, or 4.
     */
    void handleWrite(int address, int value, int width);

    /**
     * Handle a memory-mapped I/O load.
     *
     * @param address The unsigned 32-bit guest address being read.
     * @param width The read width in bytes: 1, 2, or 4.
     * @return The value read from the hardware.
     */
    int handleRead(int address, int width);
}
