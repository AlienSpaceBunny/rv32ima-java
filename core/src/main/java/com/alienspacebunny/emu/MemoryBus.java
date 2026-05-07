package com.alienspacebunny.emu;

/**
 * Interface for memory access in the RV32IMA emulator.
 * Supports 1, 2, and 4-byte read/write operations.
 * All addresses are 32-bit unsigned integers (represented as ints).
 */
public interface MemoryBus {
    byte readByte(int address);
    short readShort(int address);
    int readInt(int address);

    void writeByte(int address, byte value);
    void writeShort(int address, short value);
    void writeInt(int address, int value);

    /**
     * Optional: Efficiently read a 32-bit signed integer.
     */
    default int readIntSigned(int address) {
        return readInt(address);
    }

    /**
     * Optional: Efficiently read a 16-bit signed integer (sign-extended to 32-bit).
     */
    default int readShortSigned(int address) {
        return (int) readShort(address);
    }

    /**
     * Optional: Efficiently read an 8-bit signed integer (sign-extended to 32-bit).
     */
    default int readByteSigned(int address) {
        return (int) readByte(address);
    }
}
