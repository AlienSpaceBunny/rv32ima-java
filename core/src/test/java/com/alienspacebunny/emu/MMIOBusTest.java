package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class MMIOBusTest {
    private static final class RecordingMemoryBus implements MemoryBus {
        int readByteCount;
        int readShortCount;
        int readIntCount;
        int writeByteCount;
        int writeShortCount;
        int writeIntCount;
        int lastAddress;
        int lastValue;

        @Override
        public byte readByte(int address) {
            readByteCount++;
            lastAddress = address;
            return (byte) 0x7a;
        }

        @Override
        public short readShort(int address) {
            readShortCount++;
            lastAddress = address;
            return (short) 0x5678;
        }

        @Override
        public int readInt(int address) {
            readIntCount++;
            lastAddress = address;
            return 0x12345678;
        }

        @Override
        public void writeByte(int address, byte value) {
            writeByteCount++;
            lastAddress = address;
            lastValue = value & 0xff;
        }

        @Override
        public void writeShort(int address, short value) {
            writeShortCount++;
            lastAddress = address;
            lastValue = value & 0xffff;
        }

        @Override
        public void writeInt(int address, int value) {
            writeIntCount++;
            lastAddress = address;
            lastValue = value;
        }
    }

    /** Backing bus that records which context-bearing/atomic entry points were reached. */
    private static final class ContextRecordingBus implements MemoryBus {
        AccessContext lastCtx;
        String lastMethod;
        int lastAddress;
        int legacyCalls;

        private void legacy() {
            legacyCalls++;
        }

        @Override
        public byte readByte(int address) {
            legacy();
            return 1;
        }

        @Override
        public short readShort(int address) {
            legacy();
            return 2;
        }

        @Override
        public int readInt(int address) {
            legacy();
            return 4;
        }

        @Override
        public void writeByte(int address, byte value) {
            legacy();
        }

        @Override
        public void writeShort(int address, short value) {
            legacy();
        }

        @Override
        public void writeInt(int address, int value) {
            legacy();
        }

        private void record(String method, int address, AccessContext ctx) {
            lastMethod = method;
            lastAddress = address;
            lastCtx = ctx;
        }

        @Override
        public byte readByte(int address, AccessContext ctx) {
            record("readByte", address, ctx);
            return 0x11;
        }

        @Override
        public short readShort(int address, AccessContext ctx) {
            record("readShort", address, ctx);
            return 0x2222;
        }

        @Override
        public int readInt(int address, AccessContext ctx) {
            record("readInt", address, ctx);
            return 0x44444444;
        }

        @Override
        public int readByteSigned(int address, AccessContext ctx) {
            record("readByteSigned", address, ctx);
            return -1;
        }

        @Override
        public int readShortSigned(int address, AccessContext ctx) {
            record("readShortSigned", address, ctx);
            return -2;
        }

        @Override
        public void writeByte(int address, byte value, AccessContext ctx) {
            record("writeByte", address, ctx);
        }

        @Override
        public void writeShort(int address, short value, AccessContext ctx) {
            record("writeShort", address, ctx);
        }

        @Override
        public void writeInt(int address, int value, AccessContext ctx) {
            record("writeInt", address, ctx);
        }

        @Override
        public int atomicRmw(int address, int funct5, int operand, AccessContext ctx) {
            record("atomicRmw", address, ctx);
            return 0x77;
        }

        @Override
        public int tryScAndStore(int hartId, int address, int value, AccessContext ctx) {
            record("tryScAndStore", address, ctx);
            return 9;
        }

        @Override
        public void checkAccess(int address, AccessContext ctx) {
            record("checkAccess", address, ctx);
        }
    }

    private static final class RecordingHook implements HardwareHook {
        int readCount;
        int writeCount;
        int lastAddress;
        int lastValue;
        int lastWidth;
        int readValue = 0x89abcdef;

        @Override
        public void handleWrite(int address, int value, int width) {
            writeCount++;
            lastAddress = address;
            lastValue = value;
            lastWidth = width;
        }

        @Override
        public int handleRead(int address, int width) {
            readCount++;
            lastAddress = address;
            lastWidth = width;
            return readValue;
        }
    }

    @Test
    public void unregisteredAccessesDelegateToBackingRam() {
        RecordingMemoryBus ram = new RecordingMemoryBus();
        MMIOBus bus = new MMIOBus(ram);

        assertEquals(0x12345678, bus.readInt(0x80000000));
        bus.writeShort(0x80000004, (short) 0xbeef);

        assertEquals(1, ram.readIntCount);
        assertEquals(1, ram.writeShortCount);
        assertEquals(0x80000004, ram.lastAddress);
        assertEquals(0xbeef, ram.lastValue);
    }

    @Test
    public void matchedHookOwnsReadAndWriteRangeWithoutRamFallthrough() {
        RecordingMemoryBus ram = new RecordingMemoryBus();
        RecordingHook hook = new RecordingHook();
        MMIOBus bus = new MMIOBus(ram);
        bus.registerHook(0x10000000, 0x100, hook);

        assertEquals(0x89abcdef, bus.readInt(0x10000004));
        bus.writeInt(0x10000008, 0x55aa55aa);

        assertEquals(1, hook.readCount);
        assertEquals(1, hook.writeCount);
        assertEquals(0x10000008, hook.lastAddress);
        assertEquals(0x55aa55aa, hook.lastValue);
        assertEquals(4, hook.lastWidth);
        assertEquals(0, ram.readIntCount);
        assertEquals(0, ram.writeIntCount);
    }

    @Test
    public void hookMatchingUsesUnsignedAddressRanges() {
        RecordingMemoryBus ram = new RecordingMemoryBus();
        RecordingHook hook = new RecordingHook();
        MMIOBus bus = new MMIOBus(ram);
        bus.registerHook(0xfffffff0, 0x10, hook);

        bus.writeInt(0xfffffffc, 0x11223344);

        assertEquals(1, hook.writeCount);
        assertEquals(0xfffffffc, hook.lastAddress);
        assertEquals(0, ram.writeIntCount);
    }

    @Test
    public void hookRangesAreStartInclusiveAndEndExclusive() {
        RecordingMemoryBus ram = new RecordingMemoryBus();
        RecordingHook hook = new RecordingHook();
        MMIOBus bus = new MMIOBus(ram);
        bus.registerHook(0x2000, 0x10, hook);

        bus.writeByte(0x2000, (byte) 1);
        bus.writeByte(0x200f, (byte) 2);
        bus.writeByte(0x2010, (byte) 3);

        assertEquals(2, hook.writeCount);
        assertEquals(1, ram.writeByteCount);
        assertEquals(0x2010, ram.lastAddress);
    }

    @Test
    public void readAndWriteWidthsAndUnsignedWriteValuesArePassedToHook() {
        RecordingMemoryBus ram = new RecordingMemoryBus();
        RecordingHook hook = new RecordingHook();
        MMIOBus bus = new MMIOBus(ram);
        bus.registerHook(0x3000, 0x100, hook);

        bus.writeByte(0x3001, (byte) 0xa5);
        assertEquals(0xa5, hook.lastValue);
        assertEquals(1, hook.lastWidth);

        bus.writeShort(0x3002, (short) 0xbeef);
        assertEquals(0xbeef, hook.lastValue);
        assertEquals(2, hook.lastWidth);

        bus.writeInt(0x3004, 0xcafebabe);
        assertEquals(0xcafebabe, hook.lastValue);
        assertEquals(4, hook.lastWidth);

        hook.readValue = 0xa5;
        assertEquals((byte) 0xa5, bus.readByte(0x3008));
        assertEquals(1, hook.lastWidth);

        hook.readValue = 0xbeef;
        assertEquals((short) 0xbeef, bus.readShort(0x300a));
        assertEquals(2, hook.lastWidth);
    }

    @Test
    public void registerHookRejectsInvalidRanges() {
        MMIOBus bus = new MMIOBus(new RecordingMemoryBus());
        RecordingHook hook = new RecordingHook();

        assertThrows(NullPointerException.class, () -> bus.registerHook(0x1000, 0x10, null));
        assertThrows(IllegalArgumentException.class, () -> bus.registerHook(0x1000, 0, hook));
        assertThrows(IllegalArgumentException.class, () -> bus.registerHook(0x1000, -1, hook));
        assertThrows(IllegalArgumentException.class, () -> bus.registerHook(0xfffffff0, 0x20, hook));
    }

    @Test
    public void registerHookRejectsOverlappingRanges() {
        MMIOBus bus = new MMIOBus(new RecordingMemoryBus());
        bus.registerHook(0x4000, 0x100, new RecordingHook());

        assertThrows(IllegalArgumentException.class, () -> bus.registerHook(0x40ff, 0x10, new RecordingHook()));
        bus.registerHook(0x4100, 0x10, new RecordingHook());
    }

    // Context and atomic forwarding (V-32 CPU integration review): a router in front of a
    // multi-hart RAM bus must not drop AccessContext or split atomics at its boundary.

    @Test
    public void contextBearingAccessesToRamForwardContextIntact() {
        ContextRecordingBus ram = new ContextRecordingBus();
        MMIOBus bus = new MMIOBus(ram);
        bus.registerHook(0x10000000, 0x100, new RecordingHook());
        AccessContext ctx = new AccessContext(1, 0, AccessKind.LOAD, 4, 0);

        assertEquals(0x44444444, bus.readInt(0x80000000, ctx));
        assertEquals("readInt", ram.lastMethod);
        assertEquals(0x80000000, ram.lastAddress);
        assertEquals(ctx, ram.lastCtx);

        assertEquals(0x11, bus.readByte(0x80000001, ctx));
        assertEquals("readByte", ram.lastMethod);
        assertEquals(0x2222, bus.readShort(0x80000002, ctx));
        assertEquals("readShort", ram.lastMethod);
        assertEquals(-1, bus.readByteSigned(0x80000003, ctx));
        assertEquals("readByteSigned", ram.lastMethod);
        assertEquals(-2, bus.readShortSigned(0x80000004, ctx));
        assertEquals("readShortSigned", ram.lastMethod);

        AccessContext storeCtx = new AccessContext(1, 0, AccessKind.STORE, 1, 0);
        bus.writeByte(0x80000005, (byte) 1, storeCtx);
        assertEquals("writeByte", ram.lastMethod);
        bus.writeShort(0x80000006, (short) 1, storeCtx);
        assertEquals("writeShort", ram.lastMethod);
        bus.writeInt(0x80000008, 1, storeCtx);
        assertEquals("writeInt", ram.lastMethod);
        assertEquals(storeCtx, ram.lastCtx);

        assertEquals(0, ram.legacyCalls);
    }

    @Test
    public void atomicPrimitivesToRamForwardAsSingleCalls() {
        ContextRecordingBus ram = new ContextRecordingBus();
        MMIOBus bus = new MMIOBus(ram);
        AccessContext amo = new AccessContext(1, 3, AccessKind.AMO, 4, 0);
        AccessContext sc = new AccessContext(1, 3, AccessKind.AMO, 4, 3);

        assertEquals(0x77, bus.atomicRmw(0x80000010, 0, 5, amo));
        assertEquals("atomicRmw", ram.lastMethod);
        assertEquals(amo, ram.lastCtx);

        assertEquals(9, bus.tryScAndStore(1, 0x80000014, 5, sc));
        assertEquals("tryScAndStore", ram.lastMethod);
        assertEquals(sc, ram.lastCtx);

        bus.checkAccess(0x80000018, sc);
        assertEquals("checkAccess", ram.lastMethod);
        assertEquals(0x80000018, ram.lastAddress);

        assertEquals(0, ram.legacyCalls);
    }

    @Test
    public void hookAddressesTakeTheNoContextPath() {
        ContextRecordingBus ram = new ContextRecordingBus();
        RecordingHook hook = new RecordingHook();
        MMIOBus bus = new MMIOBus(ram);
        bus.registerHook(0x10000000, 0x100, hook);
        AccessContext amo = new AccessContext(1, 3, AccessKind.AMO, 4, 1);

        assertEquals(0x89abcdef, bus.readInt(0x10000000, new AccessContext(1, 3, AccessKind.LOAD, 4, 0)));
        assertEquals(1, hook.readCount);

        // AMOSWAP on a device register: default read-compute-write through the hook.
        assertEquals(0x89abcdef, bus.atomicRmw(0x10000004, 1, 0x55, amo));
        assertEquals(2, hook.readCount);
        assertEquals(1, hook.writeCount);
        assertEquals(0x55, hook.lastValue);

        // SC.W on a device register: unconditional hook write, reported as success.
        assertEquals(0, bus.tryScAndStore(1, 0x10000008, 0x66, new AccessContext(1, 3, AccessKind.AMO, 4, 3)));
        assertEquals(2, hook.writeCount);
        assertEquals(0x66, hook.lastValue);

        // checkAccess on a device register is permitted without touching the hook.
        bus.checkAccess(0x1000000c, new AccessContext(1, 3, AccessKind.AMO, 4, 3));
        assertEquals(2, hook.readCount);
        assertEquals(2, hook.writeCount);

        assertEquals(null, ram.lastMethod);
        assertEquals(0, ram.legacyCalls);
    }
}
