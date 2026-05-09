package com.alienspacebunny.emu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
