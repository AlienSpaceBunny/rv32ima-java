package com.alienspacebunny.emu;

import java.util.ArrayList;
import java.util.List;

/**
 * A MemoryBus implementation that delegates to another MemoryBus for main RAM
 * but intercepts specific address ranges for HardwareHooks (MMIO).
 */
public class MMIOBus implements MemoryBus {
    private final MemoryBus ram;
    private final List<RangeHook> hooks = new ArrayList<>();

    private record RangeHook(int start, int end, HardwareHook hook) {
        boolean contains(int address) {
            long addr = Integer.toUnsignedLong(address);
            return addr >= Integer.toUnsignedLong(start) && addr < Integer.toUnsignedLong(end);
        }
    }

    public MMIOBus(MemoryBus ram) {
        this.ram = ram;
    }

    public void registerHook(int start, int size, HardwareHook hook) {
        hooks.add(new RangeHook(start, start + size, hook));
    }

    private HardwareHook getHook(int address) {
        for (RangeHook rh : hooks) {
            if (rh.contains(address)) {
                return rh.hook;
            }
        }
        return null;
    }

    @Override
    public byte readByte(int address) {
        HardwareHook hook = getHook(address);
        if (hook != null) {
            return (byte) hook.handleRead(address, 1);
        }
        return ram.readByte(address);
    }

    @Override
    public short readShort(int address) {
        HardwareHook hook = getHook(address);
        if (hook != null) {
            return (short) hook.handleRead(address, 2);
        }
        return ram.readShort(address);
    }

    @Override
    public int readInt(int address) {
        HardwareHook hook = getHook(address);
        if (hook != null) {
            return hook.handleRead(address, 4);
        }
        return ram.readInt(address);
    }

    @Override
    public void writeByte(int address, byte value) {
        HardwareHook hook = getHook(address);
        if (hook != null) {
            hook.handleWrite(address, value & 0xFF, 1);
        } else {
            ram.writeByte(address, value);
        }
    }

    @Override
    public void writeShort(int address, short value) {
        HardwareHook hook = getHook(address);
        if (hook != null) {
            hook.handleWrite(address, value & 0xFFFF, 2);
        } else {
            ram.writeShort(address, value);
        }
    }

    @Override
    public void writeInt(int address, int value) {
        HardwareHook hook = getHook(address);
        if (hook != null) {
            hook.handleWrite(address, value, 4);
        } else {
            ram.writeInt(address, value);
        }
    }
}
