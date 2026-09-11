package com.alienspacebunny.emu;

/**
 * Abstracts guest physical memory as seen by the RV32IMA processor.
 *
 * <p>All addresses are unsigned 32-bit guest physical addresses represented as Java {@code int}.
 * Addresses at or above {@code 0x80000000} therefore appear negative in Java; use {@link
 * Integer#toUnsignedLong} or {@link Integer#compareUnsigned} when comparing address magnitudes.
 *
 * <p><b>Fault contract.</b> Implementations must throw {@link IndexOutOfBoundsException} for any
 * access to an unmapped or out-of-range address. {@link RV32IMACore} catches this exception and
 * converts it into the appropriate RISC-V trap: load access-fault (cause 5) for reads, or
 * store/AMO access-fault (cause 7) for writes, with {@code mtval} set to the faulting address.
 *
 * <p><b>Byte order.</b> All multi-byte accesses ({@code short} and {@code int}) must use
 * little-endian byte order, consistent with the RISC-V ISA. Big-endian hosts are not supported.
 *
 * <p><b>Signed and unsigned conventions.</b> Java primitives are signed. The raw read methods
 * ({@link #readByte}, {@link #readShort}, {@link #readInt}) return Java signed values; the core
 * uses explicit masking ({@code & 0xFF}, {@code & 0xFFFF}) when a zero-extending load is needed.
 * The {@link #readByteSigned} and {@link #readShortSigned} default methods return a 32-bit
 * sign-extended result used for signed load instructions (LB, LH). Implementations need not
 * distinguish these cases; only one physical read is performed per address.
 *
 * <p><b>Thread safety.</b> Implementations are invoked from the emulator step loop with no
 * additional synchronization. Thread safety is the responsibility of the caller.
 */
public interface MemoryBus {
    /**
     * Reads one byte from a guest address.
     *
     * @param address the unsigned 32-bit guest address.
     * @return the byte at that address, as a signed Java {@code byte}. Use {@code & 0xFF} for the
     *     unsigned value.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    byte readByte(int address);

    /**
     * Reads a 16-bit halfword from a guest address in little-endian byte order.
     *
     * @param address the unsigned 32-bit guest address.
     * @return the halfword, as a signed Java {@code short}. Use {@code & 0xFFFF} for the unsigned
     *     value.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    short readShort(int address);

    /**
     * Reads a 32-bit word from a guest address in little-endian byte order.
     *
     * @param address the unsigned 32-bit guest address.
     * @return the word.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    int readInt(int address);

    /**
     * Writes one byte to a guest address.
     *
     * @param address the unsigned 32-bit guest address.
     * @param value the byte to write.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    void writeByte(int address, byte value);

    /**
     * Writes a 16-bit halfword to a guest address in little-endian byte order.
     *
     * @param address the unsigned 32-bit guest address.
     * @param value the halfword to write.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    void writeShort(int address, short value);

    /**
     * Writes a 32-bit word to a guest address in little-endian byte order.
     *
     * @param address the unsigned 32-bit guest address.
     * @param value the word to write.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    void writeInt(int address, int value);

    /**
     * Reads a 16-bit halfword and sign-extends it to 32 bits.
     *
     * <p>Used by the LH (load halfword, signed) instruction.
     *
     * @param address the unsigned 32-bit guest address.
     * @return the halfword sign-extended to a 32-bit {@code int}.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    default int readShortSigned(int address) {
        return (int) readShort(address);
    }

    /**
     * Reads one byte and sign-extends it to 32 bits.
     *
     * <p>Used by the LB (load byte, signed) instruction.
     *
     * @param address the unsigned 32-bit guest address.
     * @return the byte sign-extended to a 32-bit {@code int}.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    default int readByteSigned(int address) {
        return (int) readByte(address);
    }
}
