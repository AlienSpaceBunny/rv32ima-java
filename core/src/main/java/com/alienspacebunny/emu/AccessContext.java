package com.alienspacebunny.emu;

/**
 * Metadata describing one guest memory access, passed to the context-bearing overloads on {@link
 * MemoryBus} (for example {@link MemoryBus#readInt(int, AccessContext)}).
 *
 * <p>A {@link MemoryBus} implementation that needs to authorize accesses per-hart, per-privilege,
 * or per-kind (for example, a fantasy console's AP MPU, or a multi-hart bus tracking LR/SC
 * reservations) overrides the context-bearing overloads and inspects this record; the
 * no-context overloads remain source-compatible for buses that don't need this information (see
 * {@link MemoryBus}'s default methods).
 *
 * <p><b>Memory ordering.</b> This record does not carry RISC-V {@code aq}/{@code rl} acquire/
 * release bits. Per-access exclusion alone is not a cross-hart payload-publication guarantee; a
 * bus implementation relying on this context for shared-memory IPC ordering (beyond LR/SC and AMO
 * atomicity) must independently document and verify how it provides that ordering.
 *
 * @param hartId the accessing hart's {@link RV32IMAState#hartId}.
 * @param privilege the hart's current privilege level at the time of the access, taken from
 *     {@code extraflags & 3}: {@code 0} for user mode, {@code 3} for machine mode.
 * @param kind the kind of access; see {@link AccessKind}.
 * @param width the guest access width in bytes: {@code 1}, {@code 2}, or {@code 4}. A bus
 *     enforcing access control should authorize the entire {@code [address, address + width)}
 *     range, not just the first byte.
 * @param atomicOp the AMO/LR/SC {@code funct5} encoding when {@link #kind} is {@link
 *     AccessKind#AMO}, or {@code 0} for a non-atomic access. {@code LR.W}'s {@code funct5} is
 *     {@code 2}; a bus that wants to register a load-reserve checks {@code kind ==
 *     AccessKind.AMO && atomicOp == 2} on the {@code readInt} override.
 */
public record AccessContext(int hartId, int privilege, AccessKind kind, int width, int atomicOp) {}
