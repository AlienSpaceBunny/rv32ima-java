package com.alienspacebunny.emu;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A deterministic {@link MachineTimeSource} that the embedder sets or advances, for tests,
 * benchmarks and replay. Time stays where it is until {@link #set} or {@link #advance} moves it.
 * Thread-safe.
 *
 * <p>Values are unsigned 64-bit microseconds; {@link #advance} wraps at 2<sup>64</sup>. Moving the
 * time backward is allowed, but, as for any source, never during a call into a core that uses it.
 */
public final class ManualTimeSource implements MachineTimeSource {
    private final AtomicLong micros;

    /** Creates a source at time 0. */
    public ManualTimeSource() {
        this(0);
    }

    /**
     * Creates a source at the given time.
     *
     * @param micros the initial time in microseconds, unsigned.
     */
    public ManualTimeSource(long micros) {
        this.micros = new AtomicLong(micros);
    }

    @Override
    public long nowMicros() {
        return micros.get();
    }

    /**
     * Sets the time.
     *
     * @param micros the new time in microseconds, unsigned.
     */
    public void set(long micros) {
        this.micros.set(micros);
    }

    /**
     * Moves the time forward.
     *
     * @param deltaMicros microseconds to add, unsigned.
     * @return the new time.
     */
    public long advance(long deltaMicros) {
        return micros.addAndGet(deltaMicros);
    }
}
