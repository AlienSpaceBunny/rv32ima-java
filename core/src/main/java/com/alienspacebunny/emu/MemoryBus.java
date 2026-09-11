package com.alienspacebunny.emu;

/**
 * Abstracts guest physical memory as seen by the RV32IMA processor.
 *
 * <p>All addresses are unsigned 32-bit guest physical addresses represented as Java {@code int}.
 * Addresses at or above {@code 0x80000000} therefore appear negative in Java; use {@link
 * Integer#toUnsignedLong} or {@link Integer#compareUnsigned} when comparing address magnitudes.
 *
 * <p><b>Fault contract.</b> Implementations must throw {@link IndexOutOfBoundsException} for any
 * access to an unmapped or out-of-range address. {@link RV32IMACore} catches this exception and
 * converts it into the appropriate RISC-V trap: load access-fault (cause 5) for reads, or
 * store/AMO access-fault (cause 7) for writes, with {@code mtval} set to the faulting address.
 *
 * <p><b>Byte order.</b> All multi-byte accesses ({@code short} and {@code int}) must use
 * little-endian byte order, consistent with the RISC-V ISA. Big-endian hosts are not supported.
 *
 * <p><b>Signed and unsigned conventions.</b> Java primitives are signed. The raw read methods
 * ({@link #readByte}, {@link #readShort}, {@link #readInt}) return Java signed values; the core
 * uses explicit masking ({@code & 0xFF}, {@code & 0xFFFF}) when a zero-extending load is needed.
 * The {@link #readByteSigned} and {@link #readShortSigned} default methods return a 32-bit
 * sign-extended result used for signed load instructions (LB, LH). Implementations need not
 * distinguish these cases; only one physical read is performed per address.
 *
 * <p><b>Thread safety.</b> Implementations are invoked from the emulator step loop with no
 * additional synchronization. Thread safety is the responsibility of the caller.
 *
 * <p><b>Access context (Phase 2).</b> Each read/write method has a context-bearing overload
 * accepting an {@link AccessContext} (hart identity, privilege, access kind, width, and atomic
 * operation). {@link RV32IMACore} calls only the context-bearing overloads internally. The
 * default implementation of each simply delegates to the no-context version, so an
 * implementation of just the six abstract methods above continues to work unmodified. A bus that
 * needs the metadata — for per-hart MPU enforcement, or cross-hart LR/SC/AMO coordination —
 * overrides the context-bearing overloads instead.
 *
 * <p><b>Atomics (Phase 2).</b> {@link #atomicRmw} handles every RV32A read-modify-write AMO
 * except {@code LR.W}/{@code SC.W}; {@code LR.W} routes through {@link #readInt(int,
 * AccessContext)} and {@code SC.W} through {@link #tryScAndStore}. The default implementations of
 * all three are correct only for a single hart — see each method's Javadoc for what a
 * multi-hart-aware override must do.
 */
public interface MemoryBus {
    /**
     * Reads one byte from a guest address.
     *
     * @param address the unsigned 32-bit guest address.
     * @return the byte at that address, as a signed Java {@code byte}. Use {@code & 0xFF} for the
     *     unsigned value.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    byte readByte(int address);

    /**
     * Reads a 16-bit halfword from a guest address in little-endian byte order.
     *
     * @param address the unsigned 32-bit guest address.
     * @return the halfword, as a signed Java {@code short}. Use {@code & 0xFFFF} for the unsigned
     *     value.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    short readShort(int address);

    /**
     * Reads a 32-bit word from a guest address in little-endian byte order.
     *
     * @param address the unsigned 32-bit guest address.
     * @return the word.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    int readInt(int address);

    /**
     * Writes one byte to a guest address.
     *
     * @param address the unsigned 32-bit guest address.
     * @param value the byte to write.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    void writeByte(int address, byte value);

    /**
     * Writes a 16-bit halfword to a guest address in little-endian byte order.
     *
     * @param address the unsigned 32-bit guest address.
     * @param value the halfword to write.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    void writeShort(int address, short value);

    /**
     * Writes a 32-bit word to a guest address in little-endian byte order.
     *
     * @param address the unsigned 32-bit guest address.
     * @param value the word to write.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    void writeInt(int address, int value);

    /**
     * Reads a 16-bit halfword and sign-extends it to 32 bits.
     *
     * <p>Used by the LH (load halfword, signed) instruction.
     *
     * @param address the unsigned 32-bit guest address.
     * @return the halfword sign-extended to a 32-bit {@code int}.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    default int readShortSigned(int address) {
        return (int) readShort(address);
    }

    /**
     * Reads one byte and sign-extends it to 32 bits.
     *
     * <p>Used by the LB (load byte, signed) instruction.
     *
     * @param address the unsigned 32-bit guest address.
     * @return the byte sign-extended to a 32-bit {@code int}.
     * @throws IndexOutOfBoundsException if the address is not mapped.
     */
    default int readByteSigned(int address) {
        return (int) readByte(address);
    }

    /**
     * Reads one byte from a guest address, with access context. Default implementation delegates
     * to {@link #readByte(int)}, discarding {@code ctx}.
     *
     * @param address the unsigned 32-bit guest address.
     * @param ctx metadata describing this access. See {@link AccessContext}.
     * @return the byte at that address, as a signed Java {@code byte}.
     * @throws IndexOutOfBoundsException if the address is not mapped or otherwise disallowed.
     */
    default byte readByte(int address, AccessContext ctx) {
        return readByte(address);
    }

    /**
     * Reads a 16-bit halfword from a guest address in little-endian byte order, with access
     * context. Default implementation delegates to {@link #readShort(int)}, discarding {@code
     * ctx}.
     *
     * @param address the unsigned 32-bit guest address.
     * @param ctx metadata describing this access. See {@link AccessContext}.
     * @return the halfword, as a signed Java {@code short}.
     * @throws IndexOutOfBoundsException if the address is not mapped or otherwise disallowed.
     */
    default short readShort(int address, AccessContext ctx) {
        return readShort(address);
    }

    /**
     * Reads a 32-bit word from a guest address in little-endian byte order, with access context.
     * Default implementation delegates to {@link #readInt(int)}, discarding {@code ctx}.
     *
     * <p>A multi-hart-aware bus overriding this method to detect a load-reserve ({@code
     * ctx.kind() == AccessKind.AMO && ctx.atomicOp() == 2}) can record {@code (ctx.hartId(),
     * address)} in its own reservation tracking here, since {@link RV32IMACore} routes {@code
     * LR.W} through this method (see {@link AccessContext}).
     *
     * @param address the unsigned 32-bit guest address.
     * @param ctx metadata describing this access. See {@link AccessContext}.
     * @return the word.
     * @throws IndexOutOfBoundsException if the address is not mapped or otherwise disallowed.
     */
    default int readInt(int address, AccessContext ctx) {
        return readInt(address);
    }

    /**
     * Writes one byte to a guest address, with access context. Default implementation delegates
     * to {@link #writeByte(int, byte)}, discarding {@code ctx}.
     *
     * @param address the unsigned 32-bit guest address.
     * @param value the byte to write.
     * @param ctx metadata describing this access. See {@link AccessContext}.
     * @throws IndexOutOfBoundsException if the address is not mapped or otherwise disallowed.
     */
    default void writeByte(int address, byte value, AccessContext ctx) {
        writeByte(address, value);
    }

    /**
     * Writes a 16-bit halfword to a guest address in little-endian byte order, with access
     * context. Default implementation delegates to {@link #writeShort(int, short)}, discarding
     * {@code ctx}.
     *
     * @param address the unsigned 32-bit guest address.
     * @param value the halfword to write.
     * @param ctx metadata describing this access. See {@link AccessContext}.
     * @throws IndexOutOfBoundsException if the address is not mapped or otherwise disallowed.
     */
    default void writeShort(int address, short value, AccessContext ctx) {
        writeShort(address, value);
    }

    /**
     * Writes a 32-bit word to a guest address in little-endian byte order, with access context.
     * Default implementation delegates to {@link #writeInt(int, int)}, discarding {@code ctx}.
     *
     * @param address the unsigned 32-bit guest address.
     * @param value the word to write.
     * @param ctx metadata describing this access. See {@link AccessContext}.
     * @throws IndexOutOfBoundsException if the address is not mapped or otherwise disallowed.
     */
    default void writeInt(int address, int value, AccessContext ctx) {
        writeInt(address, value);
    }

    /**
     * Reads a 16-bit halfword and sign-extends it to 32 bits, with access context. Default
     * implementation delegates to {@link #readShortSigned(int)}, discarding {@code ctx}.
     *
     * <p>Used by the LH (load halfword, signed) instruction.
     *
     * @param address the unsigned 32-bit guest address.
     * @param ctx metadata describing this access. See {@link AccessContext}.
     * @return the halfword sign-extended to a 32-bit {@code int}.
     * @throws IndexOutOfBoundsException if the address is not mapped or otherwise disallowed.
     */
    default int readShortSigned(int address, AccessContext ctx) {
        return readShortSigned(address);
    }

    /**
     * Reads one byte and sign-extends it to 32 bits, with access context. Default implementation
     * delegates to {@link #readByteSigned(int)}, discarding {@code ctx}.
     *
     * <p>Used by the LB (load byte, signed) instruction.
     *
     * @param address the unsigned 32-bit guest address.
     * @param ctx metadata describing this access. See {@link AccessContext}.
     * @return the byte sign-extended to a 32-bit {@code int}.
     * @throws IndexOutOfBoundsException if the address is not mapped or otherwise disallowed.
     */
    default int readByteSigned(int address, AccessContext ctx) {
        return readByteSigned(address);
    }

    /**
     * Performs an atomic read-modify-write AMO (everything in the RV32A extension except {@code
     * LR.W}/{@code SC.W}, which route through {@link #readInt(int, AccessContext)} and {@link
     * #tryScAndStore} instead).
     *
     * <p>The default implementation reads, computes the new value, and writes it back as two
     * separate calls to {@link #readInt(int, AccessContext)} and {@link #writeInt(int, int,
     * AccessContext)} — correct for a single hart, but <b>not atomic with respect to a concurrent
     * hart sharing this bus</b>, even if each call individually holds a lock. A multi-hart-aware
     * override must hold one lock over the granule for the read, the computation, the write, and
     * the invalidation of any overlapping LR/SC reservations in its own tracking (see {@link
     * #tryScAndStore}), so no other hart's access can be interleaved with any part of this
     * operation.
     *
     * @param address the unsigned 32-bit guest address. Word-aligned; there is no sub-word AMO
     *     support yet (Zabha, byte/halfword AMOs, is a later phase).
     * @param funct5 the RV32A {@code funct5} encoding identifying the operation (for example,
     *     {@code 0} for {@code AMOADD.W}, {@code 1} for {@code AMOSWAP.W}); equal to {@link
     *     AccessContext#atomicOp()} on {@code ctx}. Must be one of the values {@link RV32IMACore}
     *     validates before calling this method: {@code 0, 1, 4, 8, 12, 16, 20, 24, 28}.
     * @param operand the AMO's second operand (the value from {@code rs2}).
     * @param ctx metadata describing this access. See {@link AccessContext}.
     * @return the value at {@code address} <em>before</em> the write — this becomes the
     *     destination register's value.
     * @throws IndexOutOfBoundsException if the address is not mapped or otherwise disallowed.
     * @throws IllegalArgumentException if {@code funct5} is not one of the values documented above.
     *     {@link RV32IMACore} never triggers this; it applies only to a caller invoking this method
     *     directly with an unsupported encoding.
     */
    default int atomicRmw(int address, int funct5, int operand, AccessContext ctx) {
        int old = readInt(address, ctx);
        int result = computeAmo(funct5, old, operand);
        writeInt(address, result, ctx);
        return old;
    }

    /**
     * Computes an AMO's new value from its {@code funct5} encoding, the value currently at the
     * address, and the operand. Shared by {@link #atomicRmw}'s default implementation.
     */
    private static int computeAmo(int funct5, int old, int operand) {
        return switch (funct5) {
            case 1 -> operand; // AMOSWAP.W
            case 0 -> old + operand; // AMOADD.W
            case 4 -> old ^ operand; // AMOXOR.W
            case 12 -> old & operand; // AMOAND.W
            case 8 -> old | operand; // AMOOR.W
            case 16 -> Math.min(operand, old); // AMOMIN.W (signed)
            case 20 -> Math.max(operand, old); // AMOMAX.W (signed)
            case 24 -> Integer.compareUnsigned(operand, old) < 0 ? operand : old; // AMOMINU.W
            case 28 -> Integer.compareUnsigned(operand, old) > 0 ? operand : old; // AMOMAXU.W
            default -> throw new IllegalArgumentException("Unsupported AMO funct5: " + funct5);
        };
    }

    /**
     * Makes the final atomic decision for {@code SC.W} and, if it succeeds, performs the store.
     *
     * <p>{@link RV32IMACore} calls this only after its own local fast-path check passes (the
     * hart's {@code state.reservationValid} is true and its {@code reservationAddr} matches). This
     * method is the bus's opportunity to reject the store anyway — a multi-hart bus may have
     * observed a cross-hart invalidation (another hart's plain store or AMO to the same granule)
     * between this hart's {@code LR.W} and this {@code SC.W}, which the core's purely local state
     * cannot see.
     *
     * <p>The default implementation provides correct single-hart behavior: it unconditionally
     * writes and reports success, since the core's local check is already sufficient when there is
     * only one hart. A multi-hart-aware override must, as one critical section under a per-granule
     * lock: check its own private reservation tracking for this {@code (hartId, address)}, and if
     * still valid, perform the write and invalidate the reservation; either way, the reservation
     * entry is consumed (removed) by this call, regardless of success.
     *
     * @param hartId the calling hart's identity (see {@link AccessContext#hartId()}), passed
     *     separately rather than requiring it be re-derived from {@code ctx}.
     * @param address the unsigned 32-bit guest address, matching the preceding {@code LR.W}.
     * @param value the value to conditionally store (from {@code rs2}).
     * @param ctx metadata describing this access. See {@link AccessContext}.
     * @return {@code 0} on success; any non-zero value on failure. This becomes the destination
     *     register's value, per the RV32A {@code SC.W} semantics.
     * @throws IndexOutOfBoundsException if the address is not mapped or otherwise disallowed.
     */
    default int tryScAndStore(int hartId, int address, int value, AccessContext ctx) {
        writeInt(address, value, ctx);
        return 0;
    }
}
