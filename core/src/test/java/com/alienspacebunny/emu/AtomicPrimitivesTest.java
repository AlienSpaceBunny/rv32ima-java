package com.alienspacebunny.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link RV32IMACore} routes AMOs and {@code SC.W} through {@link
 * MemoryBus#atomicRmw} and {@link MemoryBus#tryScAndStore} respectively (multi-hart Phase 2,
 * Design Decisions §4-§5) — not just that the default implementations compute the right answer
 * (already covered extensively by the pre-existing AMO/LR/SC tests in {@code CoreTest}, which now
 * exercise those defaults as a side effect).
 */
public class AtomicPrimitivesTest {
    private static final int RAM_OFFSET = 0x80000000;

    private static int amoInstruction(int funct5, int funct3, int rd, int rs1, int rs2) {
        return (funct5 << 27) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x2f;
    }

    /**
     * Records calls to {@link #atomicRmw} and {@link #tryScAndStore}, delegating to {@code
     * MemoryBus}'s default behavior via {@code MemoryBus.super} unless {@link #forceScFailure} is
     * set, which simulates a multi-hart bus rejecting an {@code SC.W} the core's local state
     * believed would succeed.
     */
    private static final class RecordingBus implements MemoryBus {
        private final FFMMemoryBus delegate;

        int atomicRmwCalls;
        int tryScAndStoreCalls;
        Integer lastAtomicRmwFunct5;
        Integer lastAtomicRmwOperand;
        AccessContext lastAtomicRmwCtx;
        Integer lastTryScHartId;
        Integer lastTryScValue;
        AccessContext lastTryScCtx;
        boolean forceScFailure;
        Integer fixedAtomicRmwResult;
        boolean throwOnAtomicRmw;
        boolean throwOnTryScAndStore;

        RecordingBus(FFMMemoryBus delegate) {
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

        @Override
        public int atomicRmw(int address, int funct5, int operand, AccessContext ctx) {
            atomicRmwCalls++;
            lastAtomicRmwFunct5 = funct5;
            lastAtomicRmwOperand = operand;
            lastAtomicRmwCtx = ctx;
            if (throwOnAtomicRmw) {
                throw new IndexOutOfBoundsException("simulated fault");
            }
            if (fixedAtomicRmwResult != null) {
                return fixedAtomicRmwResult;
            }
            return MemoryBus.super.atomicRmw(address, funct5, operand, ctx);
        }

        @Override
        public int tryScAndStore(int hartId, int address, int value, AccessContext ctx) {
            tryScAndStoreCalls++;
            lastTryScHartId = hartId;
            lastTryScValue = value;
            lastTryScCtx = ctx;
            if (throwOnTryScAndStore) {
                throw new IndexOutOfBoundsException("simulated fault");
            }
            if (forceScFailure) {
                return 99; // any non-zero: simulates a cross-hart invalidation the core couldn't see
            }
            return MemoryBus.super.tryScAndStore(hartId, address, value, ctx);
        }
    }

    @Test
    public void amoaddRoutesThroughAtomicRmwExactlyOnce() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 10;
            backing.writeInt(dataAddr, 5);
            backing.writeInt(RAM_OFFSET, amoInstruction(0, 2, 3, 1, 2)); // amoadd.w x3, x2, (x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(1, bus.atomicRmwCalls);
            assertEquals(0, bus.lastAtomicRmwFunct5); // AMOADD.W's funct5
            assertEquals(10, bus.lastAtomicRmwOperand);
            assertEquals(AccessKind.AMO, bus.lastAtomicRmwCtx.kind());
            assertEquals(5, state.regs[3]); // old value returned
            assertEquals(15, backing.readInt(dataAddr));
            assertEquals(0, bus.tryScAndStoreCalls);
        }
    }

    @Test
    public void atomicRmwReturnValueBecomesDestinationRegisterVerbatim() {
        // A bus's atomicRmw is authoritative: the core must place exactly what it returns into rd,
        // not recompute or re-derive the "old" value itself.
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            bus.fixedAtomicRmwResult = 0x7777;
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.regs[1] = RAM_OFFSET + 0x100;
            state.regs[2] = 1;
            backing.writeInt(RAM_OFFSET, amoInstruction(1, 2, 3, 1, 2)); // amoswap.w x3, x2, (x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0x7777, state.regs[3]);
        }
    }

    @Test
    public void lrDoesNotCallAtomicRmwOrTryScAndStore() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.regs[1] = RAM_OFFSET + 0x100;
            backing.writeInt(RAM_OFFSET, amoInstruction(2, 2, 3, 1, 0)); // lr.w x3, (x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0, bus.atomicRmwCalls);
            assertEquals(0, bus.tryScAndStoreCalls);
        }
    }

    @Test
    public void scWithoutPriorLrNeverCallsTryScAndStore() {
        // The local fast-path pre-check fails, so the core must not call the bus at all.
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.regs[1] = RAM_OFFSET + 0x100;
            backing.writeInt(RAM_OFFSET, amoInstruction(3, 2, 3, 1, 0)); // sc.w x3, x0, (x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(1, state.regs[3]); // failed
            assertEquals(0, bus.tryScAndStoreCalls);
            assertNull(bus.lastTryScHartId);
        }
    }

    @Test
    public void scWithValidLocalReservationCallsTryScAndStoreWithCorrectArgs() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.hartId = 9;
            state.regs[1] = RAM_OFFSET + 0x100;
            state.regs[2] = 0x55aa;
            state.reservationValid = true;
            state.reservationAddr = RAM_OFFSET + 0x100;
            backing.writeInt(RAM_OFFSET, amoInstruction(3, 2, 3, 1, 2)); // sc.w x3, x2, (x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(0, state.regs[3]); // succeeded
            assertEquals(1, bus.tryScAndStoreCalls);
            assertEquals(9, bus.lastTryScHartId);
            assertEquals(0x55aa, bus.lastTryScValue);
            assertEquals(AccessKind.AMO, bus.lastTryScCtx.kind());
            assertEquals(3, bus.lastTryScCtx.atomicOp()); // SC.W's funct5
            assertEquals(0x55aa, backing.readInt(RAM_OFFSET + 0x100));
        }
    }

    @Test
    public void busCanRejectScEvenWhenLocalPreCheckPasses() {
        // Simulates the cross-hart case the plan describes: the core's local state says the
        // reservation is valid, but the bus's own tracking (a cross-hart invalidation this hart
        // can't see locally) makes the final call and rejects it.
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            bus.forceScFailure = true;
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            state.regs[1] = RAM_OFFSET + 0x100;
            state.regs[2] = 0x55aa;
            state.reservationValid = true;
            state.reservationAddr = RAM_OFFSET + 0x100;
            backing.writeInt(RAM_OFFSET + 0x100, 0xdeadbeef);
            backing.writeInt(RAM_OFFSET, amoInstruction(3, 2, 3, 1, 2)); // sc.w x3, x2, (x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(99, state.regs[3]); // the bus's rejection code, not the local check's
            assertEquals(1, bus.tryScAndStoreCalls);
            assertEquals(0xdeadbeef, backing.readInt(RAM_OFFSET + 0x100)); // never written
            assertFalse(state.reservationValid); // core still consumes the reservation
        }
    }

    @Test
    public void atomicRmwFaultBecomesStoreAmoAccessFault() {
        // A multi-hart bus (e.g. an AP MPU) rejects an AMO by throwing from atomicRmw. The core
        // must translate that into cause 7 (store/AMO access fault) with mtval == the AMO address,
        // exactly as it would for an ordinary faulting store.
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            bus.throwOnAtomicRmw = true;
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 10;
            backing.writeInt(RAM_OFFSET, amoInstruction(0, 2, 3, 1, 2)); // amoadd.w x3, x2, (x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(1, bus.atomicRmwCalls);
            assertEquals(7, state.mcause);
            assertEquals(dataAddr, state.mtval);
            assertEquals(RAM_OFFSET, state.mepc);
        }
    }

    @Test
    public void tryScAndStoreFaultBecomesStoreAmoAccessFault() {
        int ramSize = 1024;
        try (FFMMemoryBus backing = new FFMMemoryBus(ramSize, RAM_OFFSET)) {
            RecordingBus bus = new RecordingBus(backing);
            bus.throwOnTryScAndStore = true;
            RV32IMAState state = new RV32IMAState();
            state.pc = RAM_OFFSET;
            state.extraflags |= 3;
            int dataAddr = RAM_OFFSET + 0x100;
            state.regs[1] = dataAddr;
            state.regs[2] = 0x55aa;
            state.reservationValid = true;
            state.reservationAddr = dataAddr;
            backing.writeInt(RAM_OFFSET, amoInstruction(3, 2, 3, 1, 2)); // sc.w x3, x2, (x1)

            new RV32IMACore().step(state, bus, RAM_OFFSET, ramSize, 0, 1, null, null);

            assertEquals(1, bus.tryScAndStoreCalls);
            assertEquals(7, state.mcause);
            assertEquals(dataAddr, state.mtval);
            assertEquals(RAM_OFFSET, state.mepc);
        }
    }
}
