package com.alienspacebunny.bench;

import com.alienspacebunny.emu.MemoryBus;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/** Diagnostic backend: {@code byte[]} accessed through little-endian byte-array view VarHandles. */
final class VarHandleBus implements MemoryBus {
    private static final VarHandle SHORT = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private final byte[] mem;

    VarHandleBus(int size) {
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
        return (short) SHORT.get(mem, index(address, 2));
    }

    @Override
    public int readInt(int address) {
        return (int) INT.get(mem, index(address, 4));
    }

    @Override
    public void writeByte(int address, byte value) {
        mem[index(address, 1)] = value;
    }

    @Override
    public void writeShort(int address, short value) {
        SHORT.set(mem, index(address, 2), value);
    }

    @Override
    public void writeInt(int address, int value) {
        INT.set(mem, index(address, 4), value);
    }
}
