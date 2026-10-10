package com.alienspacebunny.bench;

import com.alienspacebunny.emu.FFMMemoryBus;
import com.alienspacebunny.emu.MemoryBus;
import java.util.Map;
import java.util.function.IntFunction;

/** Entry point with only the {@code ffm} bus reachable: a closed-world (monomorphic) native control. */
public final class MonoFfmMain {
    private MonoFfmMain() {}

    /**
     * Runs the harness.
     *
     * @param args see {@link Harness}.
     */
    public static void main(String[] args) {
        Harness.main(args, Map.<String, IntFunction<MemoryBus>>of("ffm", size -> new FFMMemoryBus(size, 0)));
    }
}
