package com.alienspacebunny.emu;

import java.util.ArrayList;
import java.util.List;

/**
 * A MemoryBus implementation that delegates to another MemoryBus for main RAM
 * but intercepts specific address ranges for HardwareHooks (MMIO).
 *
 * <p>Registered ranges are device-owned: once an access matches a hook range,
 * it is routed to the hook and does not fall through to the backing RAM bus.
 *
 * <p><b>Context and atomic forwarding.</b> For an address not claimed by any hook, every
 * context-bearing overload, {@link #atomicRmw}, {@link #tryScAndStore}, and {@link #checkAccess}
 * is forwarded to the backing bus with its {@link AccessContext} intact, so a multi-hart-aware
 * RAM bus behind this router still sees hart identity and still gets each atomic as one call.
 * For an address a hook owns, the {@link HardwareHook} interface carries no context, so the
 * context is dropped at that boundary; an AMO to a hook address falls back to {@code
 * MemoryBus}'s default read-compute-write through the hook (two hook calls, not atomic across
 * harts), an {@code SC.W} to a hook address is an unconditional hook write, and {@link
 * #checkAccess} on a hook address permits it, since hooks cannot be probed without side effects.
 * A bus that needs per-access metadata on device accesses too should implement {@link MemoryBus}
 * directly rather than wrap this class.
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

    /**
     * Creates an MMIO bus that delegates non-MMIO accesses to {@code ram}.
     *
     * @param ram the backing memory bus for addresses not claimed by any registered hook.
     */
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

    // ---- Context-bearing overloads and atomic primitives: forward to the backing bus for
    // non-hook addresses so the AccessContext and the single-call atomic contract survive this
    // router; hook addresses take the no-context path above.

    @Override
    public byte readByte(int address, AccessContext ctx) {
        return getHook(address) != null ? readByte(address) : ram.readByte(address, ctx);
    }

    @Override
    public short readShort(int address, AccessContext ctx) {
        return getHook(address) != null ? readShort(address) : ram.readShort(address, ctx);
    }

    @Override
    public int readInt(int address, AccessContext ctx) {
        return getHook(address) != null ? readInt(address) : ram.readInt(address, ctx);
    }

    @Override
    public int readByteSigned(int address, AccessContext ctx) {
        return getHook(address) != null ? readByteSigned(address) : ram.readByteSigned(address, ctx);
    }

    @Override
    public int readShortSigned(int address, AccessContext ctx) {
        return getHook(address) != null ? readShortSigned(address) : ram.readShortSigned(address, ctx);
    }

    @Override
    public void writeByte(int address, byte value, AccessContext ctx) {
        if (getHook(address) != null) {
            writeByte(address, value);
        } else {
            ram.writeByte(address, value, ctx);
        }
    }

    @Override
    public void writeShort(int address, short value, AccessContext ctx) {
        if (getHook(address) != null) {
            writeShort(address, value);
        } else {
            ram.writeShort(address, value, ctx);
        }
    }

    @Override
    public void writeInt(int address, int value, AccessContext ctx) {
        if (getHook(address) != null) {
            writeInt(address, value);
        } else {
            ram.writeInt(address, value, ctx);
        }
    }

    @Override
    public int atomicRmw(int address, int funct5, int operand, AccessContext ctx) {
        return getHook(address) != null
                ? MemoryBus.super.atomicRmw(address, funct5, operand, ctx)
                : ram.atomicRmw(address, funct5, operand, ctx);
    }

    @Override
    public int tryScAndStore(int hartId, int address, int value, AccessContext ctx) {
        return getHook(address) != null
                ? MemoryBus.super.tryScAndStore(hartId, address, value, ctx)
                : ram.tryScAndStore(hartId, address, value, ctx);
    }

    @Override
    public void checkAccess(int address, AccessContext ctx) {
        if (getHook(address) == null) {
            ram.checkAccess(address, ctx);
        }
    }
}
