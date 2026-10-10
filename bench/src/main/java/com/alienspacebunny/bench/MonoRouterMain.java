package com.alienspacebunny.bench;

import com.alienspacebunny.emu.MemoryBus;
import java.util.Map;
import java.util.function.IntFunction;

/** Entry point with only the consumer-style {@code router} bus reachable: see {@link RouterBus}. */
public final class MonoRouterMain {
    private MonoRouterMain() {}

    /**
     * Runs the harness.
     *
     * @param args see {@link Harness}.
     */
    public static void main(String[] args) {
        Harness.main(args, Map.<String, IntFunction<MemoryBus>>of("router", RouterBus::new));
    }
}
