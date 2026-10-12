package com.alienspacebunny.emu;

/**
 * Creates the cores the shared test suite runs on. By default these are plain cores. The build
 * reruns the suite with the system property {@code rv32emu.test.decodedCacheSlots} set, giving
 * every core a decoded instruction cache of that many slots, with the whole address space
 * declared cacheable, so the suite also checks the cached path.
 */
final class TestCores {
    /** Cache slots for the suite's cores; 0 runs it uncached. */
    static final int SLOTS = Integer.getInteger("rv32emu.test.decodedCacheSlots", 0);

    private TestCores() {}

    static RV32IMACore create() {
        return create(IsaConfig.RV32IMA_ZICSR);
    }

    /** A core on its own time source, which stays at 0, so the compare is never due by default. */
    static RV32IMACore create(IsaConfig isaConfig) {
        return create(isaConfig, new ManualTimeSource());
    }

    static RV32IMACore create(IsaConfig isaConfig, MachineTimeSource time) {
        if (SLOTS == 0) {
            return new RV32IMACore(isaConfig, DecodedCacheConfig.OFF, time);
        }
        RV32IMACore core = new RV32IMACore(isaConfig, new DecodedCacheConfig(SLOTS, false), time);
        core.addDecodedCacheRange(0, 0xFFFFFFFF);
        return core;
    }
}
