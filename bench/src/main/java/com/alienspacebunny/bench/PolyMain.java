package com.alienspacebunny.bench;

import com.alienspacebunny.emu.FFMMemoryBus;
import com.alienspacebunny.emu.HardwareHook;
import com.alienspacebunny.emu.MMIOBus;
import com.alienspacebunny.emu.MemoryBus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Entry point with every bench bus reachable. In a native image this makes the core's
 * {@code MemoryBus} call sites polymorphic, as in a real embedder with several bus classes.
 */
public final class PolyMain {
    private PolyMain() {}

    static Map<String, IntFunction<MemoryBus>> buses() {
        Map<String, IntFunction<MemoryBus>> buses = new LinkedHashMap<>();
        buses.put("bytearray", ByteArrayBus::new);
        buses.put("varhandle", VarHandleBus::new);
        buses.put("heapsegment", HeapSegmentBus::new);
        buses.put("ffm", size -> new FFMMemoryBus(size, 0));
        buses.put("directbuffer", ByteBufferBus::direct);
        buses.put("heapbuffer", ByteBufferBus::heap);
        buses.put("wrapper", size -> new ForwardingBus(new ByteArrayBus(size)));
        buses.put("mmio", PolyMain::mmio);
        buses.put("router", RouterBus::new);
        return buses;
    }

    /** {@code MMIOBus} over the primary bus with one device hook outside RAM, like the CLI. */
    private static MemoryBus mmio(int size) {
        MMIOBus bus = new MMIOBus(new ByteArrayBus(size));
        bus.registerHook(0x10000000, 0x100, new HardwareHook() {
            @Override
            public int handleRead(int address, int width) {
                return 0;
            }

            @Override
            public void handleWrite(int address, int value, int width) {}
        });
        return bus;
    }

    /**
     * Runs the harness.
     *
     * @param args see {@link Harness}.
     */
    public static void main(String[] args) {
        Harness.main(args, buses());
    }
}
