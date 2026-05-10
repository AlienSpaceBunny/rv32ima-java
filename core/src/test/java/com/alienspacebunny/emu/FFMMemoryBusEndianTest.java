package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class FFMMemoryBusEndianTest {
    private static final int BASE = 0x1000;

    @Test
    public void writeBytesReadAsIntIsLittleEndian() {
        try (FFMMemoryBus bus = new FFMMemoryBus(16, BASE)) {
            bus.writeByte(BASE, (byte) 0x01);
            bus.writeByte(BASE + 1, (byte) 0x02);
            bus.writeByte(BASE + 2, (byte) 0x03);
            bus.writeByte(BASE + 3, (byte) 0x04);

            assertEquals(0x04030201, bus.readInt(BASE));
        }
    }

    @Test
    public void writeIntReadAsBytesIsLittleEndian() {
        try (FFMMemoryBus bus = new FFMMemoryBus(16, BASE)) {
            bus.writeInt(BASE, 0x04030201);

            assertEquals((byte) 0x01, bus.readByte(BASE));
            assertEquals((byte) 0x02, bus.readByte(BASE + 1));
            assertEquals((byte) 0x03, bus.readByte(BASE + 2));
            assertEquals((byte) 0x04, bus.readByte(BASE + 3));
        }
    }

    @Test
    public void writeBytesReadAsShortIsLittleEndian() {
        try (FFMMemoryBus bus = new FFMMemoryBus(16, BASE)) {
            bus.writeByte(BASE, (byte) 0x01);
            bus.writeByte(BASE + 1, (byte) 0x02);

            assertEquals((short) 0x0201, bus.readShort(BASE));
        }
    }

    @Test
    public void writeShortReadAsBytesIsLittleEndian() {
        try (FFMMemoryBus bus = new FFMMemoryBus(16, BASE)) {
            bus.writeShort(BASE, (short) 0x0201);

            assertEquals((byte) 0x01, bus.readByte(BASE));
            assertEquals((byte) 0x02, bus.readByte(BASE + 1));
        }
    }
}
