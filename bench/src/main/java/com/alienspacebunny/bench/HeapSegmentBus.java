package com.alienspacebunny.bench;

import com.alienspacebunny.emu.MemoryBus;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/** Diagnostic backend: a heap {@code MemorySegment.ofArray(byte[])} with unaligned LE layouts. */
final class HeapSegmentBus implements MemoryBus {
    private static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final MemorySegment segment;

    HeapSegmentBus(int size) {
        segment = MemorySegment.ofArray(new byte[size]);
    }

    @Override
    public byte readByte(int address) {
        return segment.get(ValueLayout.JAVA_BYTE, Integer.toUnsignedLong(address));
    }

    @Override
    public short readShort(int address) {
        return segment.get(LE_SHORT, Integer.toUnsignedLong(address));
    }

    @Override
    public int readInt(int address) {
        return segment.get(LE_INT, Integer.toUnsignedLong(address));
    }

    @Override
    public void writeByte(int address, byte value) {
        segment.set(ValueLayout.JAVA_BYTE, Integer.toUnsignedLong(address), value);
    }

    @Override
    public void writeShort(int address, short value) {
        segment.set(LE_SHORT, Integer.toUnsignedLong(address), value);
    }

    @Override
    public void writeInt(int address, int value) {
        segment.set(LE_INT, Integer.toUnsignedLong(address), value);
    }
}
