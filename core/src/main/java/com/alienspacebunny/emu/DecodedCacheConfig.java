package com.alienspacebunny.emu;

/**
 * Configuration of an {@link RV32IMACore}'s optional decoded instruction cache.
 *
 * <p>The cache is per core, direct-mapped and off by default. It holds decoded instructions, never
 * data, and is invisible to the guest: it models no timing. Its coherence contract is {@code
 * FENCE.I} plus an embedder flush; see {@link RV32IMACore#flushDecodedCache()} for the rules an
 * embedder must follow before enabling it. Host memory is 16 bytes per slot, so 4096 slots take
 * 64 KiB.
 *
 * @param slots the number of cache slots: {@code 0} for no cache, otherwise a power of two.
 * @param checkMode if {@code true}, every cache hit also fetches the instruction through the bus,
 *     exactly as the uncached path would, and throws {@link IllegalStateException} if the cached
 *     instruction is stale. This finds missing {@code FENCE.I}s and flushes in tests; it removes
 *     most of the cache's speed benefit.
 */
public record DecodedCacheConfig(int slots, boolean checkMode) {
    /** No cache: the behaviour of every core constructed without a {@code DecodedCacheConfig}. */
    public static final DecodedCacheConfig OFF = new DecodedCacheConfig(0, false);

    /**
     * Validates the configuration.
     *
     * @throws IllegalArgumentException if {@code slots} is negative or not a power of two.
     */
    public DecodedCacheConfig {
        if (slots < 0 || (slots & (slots - 1)) != 0) {
            throw new IllegalArgumentException("slots must be 0 or a power of two: " + slots);
        }
    }

    /**
     * Returns whether a cache is configured.
     *
     * @return {@code true} if {@code slots > 0}.
     */
    public boolean enabled() {
        return slots > 0;
    }
}
