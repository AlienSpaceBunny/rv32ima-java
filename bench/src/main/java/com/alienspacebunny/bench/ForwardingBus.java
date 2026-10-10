package com.alienspacebunny.bench;

import com.alienspacebunny.emu.AccessContext;
import com.alienspacebunny.emu.MemoryBus;

/**
 * A wrapper that forwards every method, including the context overloads and atomic primitives,
 * as {@link MemoryBus}'s wrapper guidance requires. Models one layer of consumer bus stacking
 * (an MPU view or router) without adding any policy cost of its own.
 */
final class ForwardingBus implements MemoryBus {
    private final MemoryBus inner;

    ForwardingBus(MemoryBus inner) {
        this.inner = inner;
    }

    @Override
    public byte readByte(int address) {
        return inner.readByte(address);
    }

    @Override
    public short readShort(int address) {
        return inner.readShort(address);
    }

    @Override
    public int readInt(int address) {
        return inner.readInt(address);
    }

    @Override
    public void writeByte(int address, byte value) {
        inner.writeByte(address, value);
    }

    @Override
    public void writeShort(int address, short value) {
        inner.writeShort(address, value);
    }

    @Override
    public void writeInt(int address, int value) {
        inner.writeInt(address, value);
    }

    @Override
    public byte readByte(int address, AccessContext ctx) {
        return inner.readByte(address, ctx);
    }

    @Override
    public short readShort(int address, AccessContext ctx) {
        return inner.readShort(address, ctx);
    }

    @Override
    public int readInt(int address, AccessContext ctx) {
        return inner.readInt(address, ctx);
    }

    @Override
    public int readByteSigned(int address, AccessContext ctx) {
        return inner.readByteSigned(address, ctx);
    }

    @Override
    public int readShortSigned(int address, AccessContext ctx) {
        return inner.readShortSigned(address, ctx);
    }

    @Override
    public void writeByte(int address, byte value, AccessContext ctx) {
        inner.writeByte(address, value, ctx);
    }

    @Override
    public void writeShort(int address, short value, AccessContext ctx) {
        inner.writeShort(address, value, ctx);
    }

    @Override
    public void writeInt(int address, int value, AccessContext ctx) {
        inner.writeInt(address, value, ctx);
    }

    @Override
    public int atomicRmw(int address, int funct5, int operand, AccessContext ctx) {
        return inner.atomicRmw(address, funct5, operand, ctx);
    }

    @Override
    public int tryScAndStore(int hartId, int address, int value, AccessContext ctx) {
        return inner.tryScAndStore(hartId, address, value, ctx);
    }

    @Override
    public void checkAccess(int address, AccessContext ctx) {
        inner.checkAccess(address, ctx);
    }
}
