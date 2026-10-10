package com.alienspacebunny.bench;

import com.alienspacebunny.emu.MemoryBus;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Diagnostic backend: a little-endian {@link ByteBuffer}, either a direct view of a shared-arena
 * segment (V-32's experimental buffer mode) or a heap buffer (its heap-buffer mode). The arena is
 * never closed; each bench process creates one bus.
 */
final class ByteBufferBus implements MemoryBus {
    private final ByteBuffer buffer;

    private ByteBufferBus(ByteBuffer buffer) {
        this.buffer = buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    static ByteBufferBus direct(int size) {
        return new ByteBufferBus(Arena.ofShared().allocate(size).asByteBuffer());
    }

    static ByteBufferBus heap(int size) {
        return new ByteBufferBus(ByteBuffer.wrap(new byte[size]));
    }

    @Override
    public byte readByte(int address) {
        return buffer.get(address);
    }

    @Override
    public short readShort(int address) {
        return buffer.getShort(address);
    }

    @Override
    public int readInt(int address) {
        return buffer.getInt(address);
    }

    @Override
    public void writeByte(int address, byte value) {
        buffer.put(address, value);
    }

    @Override
    public void writeShort(int address, short value) {
        buffer.putShort(address, value);
    }

    @Override
    public void writeInt(int address, int value) {
        buffer.putInt(address, value);
    }
}
