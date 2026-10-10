package com.alienspacebunny.bench;

import com.alienspacebunny.emu.MemoryBus;
import java.util.Map;
import java.util.function.IntFunction;

/** Entry point with only the {@code bytearray} bus reachable: a closed-world (monomorphic) native control. */
public final class MonoByteArrayMain {
    private MonoByteArrayMain() {}

    /**
     * Runs the harness.
     *
     * @param args see {@link Harness}.
     */
    public static void main(String[] args) {
        Harness.main(args, Map.<String, IntFunction<MemoryBus>>of("bytearray", ByteArrayBus::new));
    }
}
