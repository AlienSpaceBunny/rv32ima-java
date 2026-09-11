package com.alienspacebunny.emu;

/** The kind of guest memory access an {@link AccessContext} describes. */
public enum AccessKind {
    /** Instruction fetch. */
    FETCH,

    /** An ordinary (non-atomic) load. */
    LOAD,

    /** An ordinary (non-atomic) store. */
    STORE,

    /**
     * An atomic memory operation, including {@code LR.W} and {@code SC.W}. Use {@link
     * AccessContext#atomicOp()} to distinguish which one.
     */
    AMO
}
