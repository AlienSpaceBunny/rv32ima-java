package com.alienspacebunny.emu;

import java.util.ArrayList;
import java.util.List;

/**
 * A MemoryBus implementation that delegates to another MemoryBus for main RAM
 * but intercepts specific address ranges for HardwareHooks (MMIO).
 *
 * <p>Registered ranges are device-owned: once an access matches a hook range,
 * it is routed to the hook and does not fall through to the backing RAM bus.
 */
public class MMIOBus implements MemoryBus {
    private static final long ADDRESS_SPACE_SIZE = 1L << 32;

    private final MemoryBus ram;
    private final List<RangeHook> hooks = new ArrayList<>();

    private record RangeHook(long start, long end, HardwareHook hook) {
        boolean contains(int address) {
            long addr = Integer.toUnsignedLong(address);
            return addr >= start && addr < end;
        }

        boolean overlaps(long otherStart, long otherEnd) {
            return start < otherEnd && otherStart < end;
        }
    }

    public MMIOBus(MemoryBus ram) {
        this.ram = ram;
    }

    /**
     * Registers a device-owned MMIO range.
     *
     * @param start The unsigned 32-bit start address, inclusive.
     * @param size The range size in bytes.
     * @param hook The hook that owns the range.
     * @throws IllegalArgumentException if the size is non-positive, the range
     *     wraps past the 32-bit address space, or the range overlaps an existing
     *     hook.
     * @throws NullPointerException if {@code hook} is null.
     */
    public void registerHook(int start, int size, HardwareHook hook) {
        if (hook == null) {
            throw new NullPointerException("hook");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Hook size must be positive");
        }

        long unsignedStart = Integer.toUnsignedLong(start);
        long unsignedEnd = unsignedStart + Integer.toUnsignedLong(size);
        if (unsignedEnd > ADDRESS_SPACE_SIZE) {
            throw new IllegalArgumentException("Hook range wraps past the end of the 32-bit address space");
        }
        for (RangeHook existing : hooks) {
            if (existing.overlaps(unsignedStart, unsignedEnd)) {
                throw new IllegalArgumentException("Hook range overlaps an existing hook");
            }
        }

        hooks.add(new RangeHook(unsignedStart, unsignedEnd, hook));
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
