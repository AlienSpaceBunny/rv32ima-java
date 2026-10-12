package com.alienspacebunny.bench;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alienspacebunny.bench.Workloads.Workload;
import com.alienspacebunny.emu.DecodedCacheConfig;
import com.alienspacebunny.emu.IsaConfig;
import com.alienspacebunny.emu.MemoryBus;
import com.alienspacebunny.emu.RV32IMACore;
import com.alienspacebunny.emu.RV32IMAState;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * Small-scale checks that every workload passes its own verification on every bench bus, and that
 * a perturbed expectation is rejected (so a fast but wrong run cannot be reported).
 */
class WorkloadTest {
    private static void runAll(MemoryBus bus, long skew) {
        runAll(bus, skew, 0);
    }

    private static void runAll(MemoryBus bus, long skew, int cacheSlots) {
        for (Workload w : Workloads.all(Workloads.Scale.TEST, skew, cacheSlots)) {
            w.prepareOnce(bus);
            w.prepare(bus);
            w.run(bus);
            w.verify(bus);
        }
    }

    @Test
    void everyWorkloadVerifiesOnEveryBus() {
        for (Map.Entry<String, IntFunction<MemoryBus>> e : PolyMain.buses().entrySet()) {
            MemoryBus bus = e.getValue().apply(Workloads.MEM_SIZE);
            assertDoesNotThrow(() -> runAll(bus, 0), e.getKey());
        }
    }

    @Test
    void everyWorkloadVerifiesWithADecodedCache() {
        for (int slots : new int[] {4, 4096}) {
            for (String busName : new String[] {"bytearray", "router"}) {
                MemoryBus bus = PolyMain.buses().get(busName).apply(Workloads.MEM_SIZE);
                assertDoesNotThrow(() -> runAll(bus, 0, slots), busName + ", " + slots + " slots");
            }
        }
    }

    /**
     * Runs a prebuilt guest image on a plain core and a cached core in lockstep, in random batch
     * sizes, comparing every PostExecHook event and the architectural state after each batch,
     * and memory at the end.
     */
    private static void lockstep(String image, IsaConfig isa, int slots) {
        byte[] program = Workloads.image(image);
        MemoryBus plainBus = new ByteArrayBus(Workloads.MEM_SIZE);
        MemoryBus cachedBus = new ByteArrayBus(Workloads.MEM_SIZE);
        for (int i = 0; i < program.length; i++) {
            plainBus.writeByte(i, program[i]);
            cachedBus.writeByte(i, program[i]);
        }
        RV32IMACore plain = new RV32IMACore(isa, DecodedCacheConfig.OFF, Workloads.TIME);
        RV32IMACore cached = new RV32IMACore(isa, new DecodedCacheConfig(slots, false), Workloads.TIME);
        cached.addDecodedCacheRange(0, program.length);
        RV32IMAState plainState = new RV32IMAState();
        RV32IMAState cachedState = new RV32IMAState();
        plainState.extraflags = 3;
        cachedState.extraflags = 3;
        long[] plainEvents = new long[64];
        long[] cachedEvents = new long[64];
        int[] counts = new int[2];
        int lcg = image.hashCode() + slots;
        int plainResult = 0;
        while (plainResult == 0) {
            lcg = lcg * 1664525 + 1013904223;
            int count = 1 + (lcg >>> 26);
            counts[0] = 0;
            counts[1] = 0;
            plainResult = plain.step(
                    plainState,
                    plainBus,
                    0,
                    Workloads.MEM_SIZE,
                    count,
                    (pc, ir, trap) -> plainEvents[counts[0]++] = ((long) pc << 32) | Integer.toUnsignedLong(ir ^ trap),
                    null);
            int cachedResult = cached.step(
                    cachedState,
                    cachedBus,
                    0,
                    Workloads.MEM_SIZE,
                    count,
                    (pc, ir, trap) -> cachedEvents[counts[1]++] = ((long) pc << 32) | Integer.toUnsignedLong(ir ^ trap),
                    null);
            String where = image + " at cycle " + plainState.getCycle();
            assertEquals(plainResult, cachedResult, where);
            assertEquals(counts[0], counts[1], where);
            assertTrue(Arrays.equals(plainEvents, 0, counts[0], cachedEvents, 0, counts[1]), where);
            assertEquals(plainState.pc, cachedState.pc, where);
            assertTrue(Arrays.equals(plainState.regs, cachedState.regs), where);
            assertTrue(Arrays.equals(plainState.fregs, cachedState.fregs), where);
            assertEquals(plainState.getCycle(), cachedState.getCycle(), where);
            assertEquals(plainState.fcsr, cachedState.fcsr, where);
            assertEquals(plainState.mstatus, cachedState.mstatus, where);
            assertEquals(plainState.mcause, cachedState.mcause, where);
        }
        assertEquals(Workloads.expected(image + ".instructions"), cachedState.getCycle(), image);
        for (int a = 0; a < Workloads.MEM_SIZE; a += 4) {
            if (plainBus.readInt(a) != cachedBus.readInt(a)) {
                throw new AssertionError(image + ": memory differs at 0x" + Integer.toHexString(a));
            }
        }
    }

    @Test
    void guestImagesMatchUncachedInLockstep() {
        lockstep("coremark-c", Workloads.AP, 4096);
        lockstep("coremark-noc", Workloads.AP_NO_C, 256);
        lockstep("fpkernels-c", Workloads.AP, 64);
        lockstep("fpkernels-noc", Workloads.AP_NO_C, 4096);
    }

    @Test
    void perturbedExpectationIsRejectedForEveryWorkload() {
        for (Workload w : Workloads.all(Workloads.Scale.TEST, 1)) {
            MemoryBus bus = new ByteArrayBus(Workloads.MEM_SIZE);
            w.prepareOnce(bus);
            w.prepare(bus);
            w.run(bus);
            assertThrows(IllegalStateException.class, () -> w.verify(bus), w.name);
        }
    }

    @Test
    void busesFaultOutOfRangeAtFullWidth() {
        for (Map.Entry<String, IntFunction<MemoryBus>> e : PolyMain.buses().entrySet()) {
            MemoryBus bus = e.getValue().apply(Workloads.MEM_SIZE);
            String name = e.getKey();
            assertThrows(IndexOutOfBoundsException.class, () -> bus.readInt(Workloads.MEM_SIZE - 3), name);
            assertThrows(IndexOutOfBoundsException.class, () -> bus.readShort(Workloads.MEM_SIZE - 1), name);
            assertThrows(IndexOutOfBoundsException.class, () -> bus.writeByte(-1, (byte) 0), name);
            bus.writeInt(Workloads.MEM_SIZE - 4, 0x11223344);
            assertEquals(0x44, bus.readByte(Workloads.MEM_SIZE - 4), name);
            assertEquals((short) 0x2233, bus.readShort(Workloads.MEM_SIZE - 3), name);
        }
    }

    @Test
    void harnessPrintsOneRowPerReportedInvocation() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        Harness.run(
                new String[] {
                    "--bus",
                    "bytearray",
                    "--scale",
                    "test",
                    "--warmup-seconds",
                    "0",
                    "--min-rounds",
                    "1",
                    "--samples",
                    "2",
                    "--workloads",
                    "reg-ap,mix-ap"
                },
                PolyMain.buses(),
                out);
        String[] lines = bytes.toString(StandardCharsets.UTF_8).strip().split("\n", -1);
        assertEquals(Harness.HEADER, lines[0]);
        assertEquals(1 + 2 + 4, lines.length); // header, two first invocations, 2 workloads x 2 samples
        assertTrue(lines[lines.length - 1].contains(",mix-ap,measured,2,"));
    }
}
