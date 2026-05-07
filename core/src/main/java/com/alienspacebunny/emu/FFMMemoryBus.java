package com.alienspacebunny.emu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Implementation of MemoryBus using Java 25 Foreign Function & Memory API.
 * Provides high-performance off-heap memory access.
 */
public class FFMMemoryBus implements MemoryBus, AutoCloseable {
    private final MemorySegment segment;
    private final Arena arena;
    private final int size;
    private final int offset;

    public FFMMemoryBus(int size, int offset) {
        this.size = size;
        this.offset = offset;
        this.arena = Arena.ofShared();
        this.segment = arena.allocate(size);
    }

    private long getInternalAddress(int address) {
        long addr = Integer.toUnsignedLong(address) - Integer.toUnsignedLong(offset);
        if (addr < 0 || addr >= size) {
            throw new IndexOutOfBoundsException("Address out of range: " + Integer.toUnsignedString(address));
        }
        return addr;
    }

    @Override
    public byte readByte(int address) {
        return segment.get(ValueLayout.JAVA_BYTE, getInternalAddress(address));
    }

    @Override
    public short readShort(int address) {
        return segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, getInternalAddress(address));
    }

    @Override
    public int readInt(int address) {
        return segment.get(ValueLayout.JAVA_INT_UNALIGNED, getInternalAddress(address));
    }

    @Override
    public void writeByte(int address, byte value) {
        segment.set(ValueLayout.JAVA_BYTE, getInternalAddress(address), value);
    }

    @Override
    public void writeShort(int address, short value) {
        segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, getInternalAddress(address), value);
    }

    @Override
    public void writeInt(int address, int value) {
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, getInternalAddress(address), value);
    }

    @Override
    public void close() {
        arena.close();
    }

    public int getSize() {
        return size;
    }

    public int getOffset() {
        return offset;
    }

    /**
     * Returns the underlying MemorySegment for advanced use.
     */
    public MemorySegment getSegment() {
        return segment;
    }
}
