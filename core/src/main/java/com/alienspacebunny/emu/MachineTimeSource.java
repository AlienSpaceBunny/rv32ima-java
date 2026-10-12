package com.alienspacebunny.emu;

/**
 * The machine's shared timeline, which every {@link RV32IMACore} is built with. One instance
 * serves all harts on a machine.
 *
 * <p>The core samples it once per {@link RV32IMACore#step} call, after argument validation and
 * before any instruction runs. Nothing inside a call samples again: not a decoded-cache
 * delegation and not the resume after a {@code FENCE.I}. Each sample becomes the hart's latest
 * ({@link RV32IMAState#getTimer()}) and immediately sets or clears {@code MTIP} against the
 * compare. The core never accumulates or rebases time.
 *
 * <p>A production source is typically {@code (System.nanoTime() - epochNanos) / 1000}. Tests,
 * benchmarks and replay use a deterministic source such as {@link ManualTimeSource}.
 *
 * <p><b>Contract.</b> Implementations must be thread-safe, since harts on different threads
 * share one source. The value must never decrease during a call into a core; an embedder resets
 * its epoch only while every hart using the source is quiescent.
 */
@FunctionalInterface
public interface MachineTimeSource {
    /**
     * Returns the current machine time.
     *
     * @return microseconds since the machine epoch, as an unsigned 64-bit value.
     */
    long nowMicros();
}
