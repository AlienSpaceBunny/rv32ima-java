package com.alienspacebunny.bench;

import com.alienspacebunny.emu.MemoryBus;

/**
 * Primary neutral backend: a plain {@code byte[]} with manual little-endian assembly and
 * full-width bounds checks. It is the cheapest backend that needs no FFM, VarHandle or buffer
 * machinery, so core measurements on it are not dominated by accessor cost.
 */
final class ByteArrayBus implements MemoryBus {
    private final byte[] mem;

    ByteArrayBus(int size) {
        mem = new byte[size];
    }

    private int index(int address, int width) {
        if (Integer.compareUnsigned(address, mem.length - width) > 0) {
            throw Faults.outOfRange(address);
        }
        return address;
    }

    @Override
    public byte readByte(int address) {
        return mem[index(address, 1)];
    }

    @Override
    public short readShort(int address) {
        int i = index(address, 2);
        return (short) ((mem[i] & 0xff) | (mem[i + 1] << 8));
    }

    @Override
    public int readInt(int address) {
        int i = index(address, 4);
        return (mem[i] & 0xff) | ((mem[i + 1] & 0xff) << 8) | ((mem[i + 2] & 0xff) << 16) | (mem[i + 3] << 24);
    }

    @Override
    public void writeByte(int address, byte value) {
        mem[index(address, 1)] = value;
    }

    @Override
    public void writeShort(int address, short value) {
        int i = index(address, 2);
        mem[i] = (byte) value;
        mem[i + 1] = (byte) (value >> 8);
    }

    @Override
    public void writeInt(int address, int value) {
        int i = index(address, 4);
        mem[i] = (byte) value;
        mem[i + 1] = (byte) (value >> 8);
        mem[i + 2] = (byte) (value >> 16);
        mem[i + 3] = (byte) (value >> 24);
    }
}
