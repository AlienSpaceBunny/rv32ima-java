package com.alienspacebunny.emu;

/**
 * Memory-mapped I/O (MMIO) device handler registered with {@link MMIOBus}.
 *
 * <p><b>Address range ownership.</b> A hook owns the entire address range for which it is
 * registered. Once {@link MMIOBus} matches an access to a registered range, reads and writes are
 * routed exclusively to that hook and never fall through to the backing RAM bus. The hook is
 * responsible for handling all offsets within its claimed range, including those it does not
 * recognise.
 *
 * <p><b>Unhandled reads.</b> For offsets within the registered range that the device does not
 * recognise, {@link #handleRead} should return {@code 0}. This matches the conventional MMIO
 * behaviour for sparse register maps.
 *
 * <p><b>Thread safety.</b> Hook methods are called from the emulator step loop without additional
 * synchronization. External synchronization is the responsibility of the caller if the hook is
 * also accessed from other threads.
 */
public interface HardwareHook {
    /**
     * Handles a memory-mapped I/O write.
     *
     * <p>For offsets the device does not recognise, the write should be silently ignored.
     *
     * @param address the unsigned 32-bit guest address being written.
     * @param value the write value; byte and halfword writes are zero-extended to 32 bits before
     *     this method is called.
     * @param width the write width in bytes: 1, 2, or 4.
     */
    void handleWrite(int address, int value, int width);

    /**
     * Handles a memory-mapped I/O read.
     *
     * <p>For offsets the device does not recognise, return {@code 0}.
     *
     * @param address the unsigned 32-bit guest address being read.
     * @param width the read width in bytes: 1, 2, or 4.
     * @return the value at that address, zero-extended to 32 bits.
     */
    int handleRead(int address, int width);
}
