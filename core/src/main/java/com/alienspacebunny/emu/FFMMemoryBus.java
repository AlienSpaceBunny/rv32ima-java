package com.alienspacebunny.emu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * {@link MemoryBus} implementation backed by Java 25 Foreign Function &amp; Memory (FFM) API
 * off-heap memory.
 *
 * <p>Multi-byte accesses ({@code short}, {@code int}) are explicitly little-endian, consistent
 * with the RISC-V ISA. Big-endian hosts are not supported.
 */
public class FFMMemoryBus implements MemoryBus, AutoCloseable {
    private static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final MemorySegment segment;
    private final Arena arena;
    private final int size;
    private final int offset;

    /**
     * Allocates {@code size} bytes of off-heap memory mapped to guest addresses starting at {@code
     * offset}.
     *
     * @param size the number of bytes to allocate.
     * @param offset the unsigned 32-bit guest base address for this memory region.
     */
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
        return segment.get(LE_SHORT, getInternalAddress(address));
    }

    @Override
    public int readInt(int address) {
        return segment.get(LE_INT, getInternalAddress(address));
    }

    @Override
    public void writeByte(int address, byte value) {
        segment.set(ValueLayout.JAVA_BYTE, getInternalAddress(address), value);
    }

    @Override
    public void writeShort(int address, short value) {
        segment.set(LE_SHORT, getInternalAddress(address), value);
    }

    @Override
    public void writeInt(int address, int value) {
        segment.set(LE_INT, getInternalAddress(address), value);
    }

    @Override
    public void close() {
        arena.close();
    }

    /** Returns the size of this memory region in bytes. */
    public int getSize() {
        return size;
    }

    /** Returns the unsigned 32-bit guest base address of this memory region. */
    public int getOffset() {
        return offset;
    }

    /**
     * Returns the underlying {@link MemorySegment} for bulk I/O or direct access.
     *
     * <p>The returned segment is the live backing store; modifications through it are immediately
     * visible to the emulator.
     *
     * @return the off-heap memory segment.
     */
    public MemorySegment getSegment() {
        return segment;
    }
}
