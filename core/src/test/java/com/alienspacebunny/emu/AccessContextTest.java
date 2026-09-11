package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link RV32IMACore} passes a correctly populated {@link AccessContext} to every
 * fetch, load, store, and AMO call on {@link MemoryBus} (multi-hart Phase 2, Design Decision §3).
 */
public class AccessContextTest {
    private static final int RAM_OFFSET = 0x80000000;

    private static int loadInstruction(int funct3, int rd, int rs1, int imm) {
        return ((imm & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x03;
    }

    private static int storeInstruction(int funct3, int rs1, int rs2, int imm) {
        return (((imm >> 5) & 0x7f) << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | ((imm & 0x1f) << 7) | 0x23;
    }

    private static int amoInstruction(int funct5, int funct3, int rd, int rs1, int rs2) {
        return (funct5 << 27) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x2f;
    }

    private static void assertContext(
            AccessContext ctx, int hartId, int privilege, AccessKind kind, int width, int atomicOp) {
        assertNotNull(ctx, "expected this bus method to have been called with a context");
        assertEquals(hartId, ctx.hartId());
        assertEquals(privilege, ctx.privilege());
        assertEquals(kind, ctx.kind());
        assertEquals(width, ctx.width());
        assertEquals(atomicOp, ctx.atomicOp());
    }

    /**
     * Records the {@link AccessContext} seen by each context-bearing method, delegating storage to
     * a backing {@link FFMMemoryBus}. Only the context-bearing overloads are exercised by {@link
     * RV32IMACore}; the no-context methods exist solely to satisfy the interface.
     *
     * <p>Each {@code lastX} field holds the most recent context passed to that method, not a full
     * history. For a single {@code step()} call executing one instruction, a fetch always occurs
     * before the decoded instruction's own access (if any), so the field ends up holding the
     * executed instruction's context, not the fetch's — but this is an ordering property of the
     * test, not a property enforced by this class. Call {@link #reset()} between steps in any test
     * that cares about more than the single most recent access.
     */
    private static final class RecordingBus implements MemoryBus {
        private final FFMMemoryBus delegate;

        AccessContext lastReadByte;
        AccessContext lastReadShort;
        AccessContext lastReadInt;
        AccessContext lastWriteByte;
        AccessContext lastWriteShort;
        AccessContext lastWriteInt;
        AccessContext lastReadByteSigned;
        AccessContext lastReadShortSigned;

        RecordingBus(FFMMemoryBus delegate) {
            this.delegate = delegate;
        }

        void reset() {
            lastReadByte = null;
            lastReadShort = null;
            lastReadInt = null;
            lastWriteByte = null;
            lastWriteShort = null;
            lastWriteInt = null;
            lastReadByteSigned = null;
            lastReadShortSigned = null;
        }

        @Override
        public byte readByte(int address) {
            return delegate.readByte(address);
        }

        @Override
        public short readShort(int address) {
            return delegate.readShort(address);
        }

        @Override
        public int readInt(int address) {
            return delegate.readInt(address);
        }

        @Override
        public void writeByte(int address, byte value) {
            delegate.writeByte(address, value);
        }

        @Override
        public void writeShort(int address, short value) {
            delegate.writeShort(address, value);
        }

        @Override
        public void writeInt(int address, int value) {
            delegate.writeInt(address, value);
        }

        @Override
        public byte readByte(int address, AccessContext ctx) {
            lastReadByte = ctx;
            return delegate.readByte(address);
        }

        @Override
        public short readShort(int address, AccessContext ctx) {
            lastReadShort = ctx;
            return delegate.readShort(address);
        }

        @Override
        public int readInt(int address, AccessContext ctx) {
            lastReadInt = ctx;
            return delegate.readInt(address);
        }

        @Override
        public void writeByte(int address, byte value, AccessContext ctx) {
            lastWriteByte = ctx;
            delegate.writeByte(address, value);
        }

        @Override
        public void writeShort(int address, short value, AccessContext ctx) {
            lastWriteShort = ctx;
            delegate.writeShort(address, value);
        }

        @Override
        public void writeInt(int address, int value, AccessContext ctx) {
            lastWriteInt = ctx;
            delegate.writeInt(address, value);
        }

        @Override
        public int readByteSigned(int address, AccessContext ctx) {
            lastReadByteSigned = ctx;
            return delegate.readByteSigned(address);
        }

        @Override
        public int readShortSigned(int address, AccessContext ctx) {
            lastReadShortSigned = ctx;
            return delegate.readShortSigned(address);
        }
    }

    @Test
    public void fetchPassesCorrectContext() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3; // machine mode
            state.hartId = 7;
            backing.writeInt(RAM_OFFSET, 0x00000013); // nop

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertContext(bus.lastReadInt, 7, 3, AccessKind.FETCH, 4, 0);
        }
    }

    @Test
    public void lbPassesLoadContextWithWidthOne() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.hartId = 2;
            state.regs[1] = RAM_OFFSET + 100;
            backing.writeByte(RAM_OFFSET + 100, (byte) 0x42);
            backing.writeInt(RAM_OFFSET, loadInstruction(0, 5, 1, 0)); // lb x5, 0(x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertContext(bus.lastReadByteSigned, 2, 3, AccessKind.LOAD, 1, 0);
        }
    }

    @Test
    public void lhPassesLoadContextWithWidthTwo() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.hartId = 2;
            state.regs[1] = RAM_OFFSET + 100;
            backing.writeInt(RAM_OFFSET, loadInstruction(1, 5, 1, 0)); // lh x5, 0(x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertContext(bus.lastReadShortSigned, 2, 3, AccessKind.LOAD, 2, 0);
        }
    }

    @Test
    public void lwPassesLoadContextWithWidthFour() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.hartId = 2;
            state.regs[1] = RAM_OFFSET + 100;
            backing.writeInt(RAM_OFFSET, loadInstruction(2, 5, 1, 0)); // lw x5, 0(x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertContext(bus.lastReadInt, 2, 3, AccessKind.LOAD, 4, 0);
        }
    }

    @Test
    public void lbuPassesLoadContextWithWidthOne() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.regs[1] = RAM_OFFSET + 100;
            backing.writeInt(RAM_OFFSET, loadInstruction(4, 5, 1, 0)); // lbu x5, 0(x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertContext(bus.lastReadByte, 0, 3, AccessKind.LOAD, 1, 0);
        }
    }

    @Test
    public void lhuPassesLoadContextWithWidthTwo() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.regs[1] = RAM_OFFSET + 100;
            backing.writeInt(RAM_OFFSET, loadInstruction(5, 5, 1, 0)); // lhu x5, 0(x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertContext(bus.lastReadShort, 0, 3, AccessKind.LOAD, 2, 0);
        }
    }

    @Test
    public void sbPassesStoreContextWithWidthOne() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.regs[1] = RAM_OFFSET + 100;
            backing.writeInt(RAM_OFFSET, storeInstruction(0, 1, 2, 0)); // sb x2, 0(x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertContext(bus.lastWriteByte, 0, 3, AccessKind.STORE, 1, 0);
        }
    }

    @Test
    public void shPassesStoreContextWithWidthTwo() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.regs[1] = RAM_OFFSET + 100;
            backing.writeInt(RAM_OFFSET, storeInstruction(1, 1, 2, 0)); // sh x2, 0(x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertContext(bus.lastWriteShort, 0, 3, AccessKind.STORE, 2, 0);
        }
    }

    @Test
    public void swPassesStoreContextWithWidthFour() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.regs[1] = RAM_OFFSET + 100;
            backing.writeInt(RAM_OFFSET, storeInstruction(2, 1, 2, 0)); // sw x2, 0(x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertContext(bus.lastWriteInt, 0, 3, AccessKind.STORE, 4, 0);
        }
    }

    @Test
    public void amoaddPassesAmoContextOnBothReadAndWrite() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.hartId = 5;
            state.regs[1] = RAM_OFFSET + 100;
            state.regs[2] = 1;
            backing.writeInt(RAM_OFFSET, amoInstruction(0, 2, 3, 1, 2)); // amoadd.w x3, x2, (x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertContext(bus.lastReadInt, 5, 3, AccessKind.AMO, 4, 0);
            assertContext(bus.lastWriteInt, 5, 3, AccessKind.AMO, 4, 0);
        }
    }

    @Test
    public void lrwPassesAmoContextWithAtomicOpTwoAndDoesNotWrite() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.regs[1] = RAM_OFFSET + 100;
            backing.writeInt(RAM_OFFSET, amoInstruction(2, 2, 3, 1, 0)); // lr.w x3, (x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertContext(bus.lastReadInt, 0, 3, AccessKind.AMO, 4, 2);
            assertNull(bus.lastWriteInt); // LR.W never writes
        }
    }

    @Test
    public void scwPassesAmoContextWithAtomicOpThree() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.regs[1] = RAM_OFFSET + 100;
            state.reservationValid = true;
            state.reservationAddr = RAM_OFFSET + 100;
            backing.writeInt(RAM_OFFSET, amoInstruction(3, 2, 3, 1, 0)); // sc.w x3, x0, (x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0, state.regs[3]); // SC succeeded -- this is exercising the write path
            assertContext(bus.lastReadInt, 0, 3, AccessKind.AMO, 4, 3);
            assertContext(bus.lastWriteInt, 0, 3, AccessKind.AMO, 4, 3);
        }
    }

    /**
     * A bus implementing only the six no-context {@link MemoryBus} methods -- exactly what every
     * pre-Phase-2 {@code MemoryBus} implementation looks like.
     */
    private static final class ContextlessBus implements MemoryBus {
        private final FFMMemoryBus delegate;

        ContextlessBus(FFMMemoryBus delegate) {
            this.delegate = delegate;
        }

        @Override
        public byte readByte(int address) {
            return delegate.readByte(address);
        }

        @Override
        public short readShort(int address) {
            return delegate.readShort(address);
        }

        @Override
        public int readInt(int address) {
            return delegate.readInt(address);
        }

        @Override
        public void writeByte(int address, byte value) {
            delegate.writeByte(address, value);
        }

        @Override
        public void writeShort(int address, short value) {
            delegate.writeShort(address, value);
        }

        @Override
        public void writeInt(int address, int value) {
            delegate.writeInt(address, value);
        }
    }

    @Test
    public void contextlessMemoryBusStillExecutesCorrectlyViaDefaults() {
        // Explicit regression guard for the compatibility promise in AccessContext/MemoryBus's
        // Javadoc: a bus implementing only the pre-Phase-2 interface must keep working unmodified,
        // via the context-bearing methods' default delegation. (Every other test in this file uses
        // FFMMemoryBus directly, which also only implements the six abstract methods, so this
        // promise is exercised implicitly by the whole suite -- this test names it explicitly.)
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            ContextlessBus bus = new ContextlessBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.regs[1] = RAM_OFFSET + 100;
            state.regs[2] = 0x12345678;
            backing.writeInt(RAM_OFFSET, storeInstruction(2, 1, 2, 0)); // sw x2, 0(x1)
            backing.writeInt(RAM_OFFSET + 4, loadInstruction(2, 3, 1, 0)); // lw x3, 0(x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 2, null, null);

            assertEquals(0x12345678, state.regs[3]);
        }
    }

    @Test
    public void userModePrivilegeIsReflectedInContext() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState(); // extraflags == 0: user mode
            state.pc = RAM_OFFSET;
            state.regs[1] = RAM_OFFSET + 100;
            backing.writeInt(RAM_OFFSET, loadInstruction(2, 5, 1, 0)); // lw x5, 0(x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0, bus.lastReadInt.privilege());
        }
    }
}
