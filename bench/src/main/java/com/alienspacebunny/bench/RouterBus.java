package com.alienspacebunny.bench;

import com.alienspacebunny.emu.AccessContext;
import com.alienspacebunny.emu.MemoryBus;

/**
 * Models a consumer-side structure, not a core recommendation: one {@code final} router is the
 * only {@link MemoryBus} class in its image, and it dispatches by address range to concrete
 * regions held in {@code final} fields of their own (non-{@code MemoryBus}) types. Each
 * context-bearing access also makes a cheap MPU-style privilege check, so the context's fields
 * are really consumed, as in V-32's AP bus.
 *
 * <p>Built alone ({@link MonoRouterMain}), the core's bus call sites have one possible receiver
 * and can be devirtualized; in {@link PolyMain} the same class shares the call sites with every
 * other bench bus.
 */
final class RouterBus implements MemoryBus {
    /** A 256-byte device window outside RAM, like the CLI's and V-32's MMIO hooks. */
    static final int DEVICE_BASE = 0x10000000;

    static final int DEVICE_SIZE = 0x100;

    /** User-mode accesses below this address fault, as a minimal MPU rule. */
    static final int USER_PROTECTED_LIMIT = 0x1000;

    private final RamRegion ram;
    private final DeviceRegion device = new DeviceRegion();

    RouterBus(int size) {
        ram = new RamRegion(size);
    }

    private static boolean isDevice(int address) {
        return Integer.compareUnsigned(address - DEVICE_BASE, DEVICE_SIZE) < 0;
    }

    private static void authorize(int address, AccessContext ctx) {
        if (ctx.privilege() == 0 && Integer.compareUnsigned(address, USER_PROTECTED_LIMIT - ctx.width()) <= 0) {
            throw Faults.outOfRange(address);
        }
    }

    @Override
    public byte readByte(int address) {
        return isDevice(address) ? (byte) device.read(address) : ram.readByte(address);
    }

    @Override
    public short readShort(int address) {
        return isDevice(address) ? (short) device.read(address) : ram.readShort(address);
    }

    @Override
    public int readInt(int address) {
        return isDevice(address) ? device.read(address) : ram.readInt(address);
    }

    @Override
    public void writeByte(int address, byte value) {
        if (isDevice(address)) {
            device.write(address, value);
        } else {
            ram.writeByte(address, value);
        }
    }

    @Override
    public void writeShort(int address, short value) {
        if (isDevice(address)) {
            device.write(address, value);
        } else {
            ram.writeShort(address, value);
        }
    }

    @Override
    public void writeInt(int address, int value) {
        if (isDevice(address)) {
            device.write(address, value);
        } else {
            ram.writeInt(address, value);
        }
    }

    @Override
    public byte readByte(int address, AccessContext ctx) {
        authorize(address, ctx);
        return readByte(address);
    }

    @Override
    public short readShort(int address, AccessContext ctx) {
        authorize(address, ctx);
        return readShort(address);
    }

    @Override
    public int readInt(int address, AccessContext ctx) {
        authorize(address, ctx);
        return readInt(address);
    }

    @Override
    public int readByteSigned(int address, AccessContext ctx) {
        authorize(address, ctx);
        return readByte(address);
    }

    @Override
    public int readShortSigned(int address, AccessContext ctx) {
        authorize(address, ctx);
        return readShort(address);
    }

    @Override
    public void writeByte(int address, byte value, AccessContext ctx) {
        authorize(address, ctx);
        writeByte(address, value);
    }

    @Override
    public void writeShort(int address, short value, AccessContext ctx) {
        authorize(address, ctx);
        writeShort(address, value);
    }

    @Override
    public void writeInt(int address, int value, AccessContext ctx) {
        authorize(address, ctx);
        writeInt(address, value);
    }

    @Override
    public void checkAccess(int address, AccessContext ctx) {
        authorize(address, ctx);
        if (!isDevice(address)) {
            ram.check(address, ctx.width());
        }
    }

    /** Little-endian {@code byte[]} RAM; deliberately not a {@code MemoryBus}. */
    static final class RamRegion {
        private final byte[] mem;

        RamRegion(int size) {
            mem = new byte[size];
        }

        int check(int address, int width) {
            if (Integer.compareUnsigned(address, mem.length - width) > 0) {
                throw Faults.outOfRange(address);
            }
            return address;
        }

        byte readByte(int address) {
            return mem[check(address, 1)];
        }

        short readShort(int address) {
            int i = check(address, 2);
            return (short) ((mem[i] & 0xff) | (mem[i + 1] << 8));
        }

        int readInt(int address) {
            int i = check(address, 4);
            return (mem[i] & 0xff) | ((mem[i + 1] & 0xff) << 8) | ((mem[i + 2] & 0xff) << 16) | (mem[i + 3] << 24);
        }

        void writeByte(int address, byte value) {
            mem[check(address, 1)] = value;
        }

        void writeShort(int address, short value) {
            int i = check(address, 2);
            mem[i] = (byte) value;
            mem[i + 1] = (byte) (value >> 8);
        }

        void writeInt(int address, int value) {
            int i = check(address, 4);
            mem[i] = (byte) value;
            mem[i + 1] = (byte) (value >> 8);
            mem[i + 2] = (byte) (value >> 16);
            mem[i + 3] = (byte) (value >> 24);
        }
    }

    /** A trivial register file standing in for MMIO devices; deliberately not a {@code MemoryBus}. */
    static final class DeviceRegion {
        private int lastWrite;

        int read(int address) {
            return lastWrite ^ address;
        }

        void write(int address, int value) {
            lastWrite = value;
        }
    }
}
