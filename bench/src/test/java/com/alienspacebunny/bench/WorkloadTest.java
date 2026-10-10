package com.alienspacebunny.bench;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alienspacebunny.bench.Workloads.Workload;
import com.alienspacebunny.emu.MemoryBus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * Small-scale checks that every workload passes its own verification on every bench bus, and that
 * a perturbed expectation is rejected (so a fast but wrong run cannot be reported).
 */
class WorkloadTest {
    private static void runAll(MemoryBus bus, long skew) {
        for (Workload w : Workloads.all(Workloads.Scale.TEST, skew)) {
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
