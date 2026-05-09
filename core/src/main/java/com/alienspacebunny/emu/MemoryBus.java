package com.alienspacebunny.emu;

/**
 * Interface for memory access in the RV32IMA emulator.
 *
 * <p>All addresses are unsigned 32-bit guest addresses represented as Java
 * {@code int}. Implementations should throw {@link IndexOutOfBoundsException}
 * for unmapped or disallowed accesses so {@link RV32IMACore} can convert guest
 * data-access failures into RISC-V traps.
 */
public interface MemoryBus {
    /**
     * Reads one byte from a guest address.
     */
    byte readByte(int address);

    /**
     * Reads a 16-bit halfword from a guest address.
     */
    short readShort(int address);

    /**
     * Reads a 32-bit word from a guest address.
     */
    int readInt(int address);

    /**
     * Writes one byte to a guest address.
     */
    void writeByte(int address, byte value);

    /**
     * Writes a 16-bit halfword to a guest address.
     */
    void writeShort(int address, short value);

    /**
     * Writes a 32-bit word to a guest address.
     */
    void writeInt(int address, int value);

    /**
     * Reads a sign-extended 32-bit word.
     */
    default int readIntSigned(int address) {
        return readInt(address);
    }

    /**
     * Reads a 16-bit halfword and sign-extends it to 32 bits.
     */
    default int readShortSigned(int address) {
        return (int) readShort(address);
    }

    /**
     * Reads an 8-bit byte and sign-extends it to 32 bits.
     */
    default int readByteSigned(int address) {
        return (int) readByte(address);
    }
}
