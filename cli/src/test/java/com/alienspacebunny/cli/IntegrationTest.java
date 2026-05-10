package com.alienspacebunny.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alienspacebunny.emu.FFMMemoryBus;
import com.alienspacebunny.emu.MMIOBus;
import com.alienspacebunny.emu.RV32IMACore;
import com.alienspacebunny.emu.RV32IMAState;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

public class IntegrationTest {

    @Test
    public void testBaremetalBinary() throws Exception {
        int ramAmt = 64 * 1024 * 1024;
        int ramOffset = 0x80000000;

        // Load the binary from resources
        byte[] binaryData;
        try (InputStream is = getClass().getResourceAsStream("/bin/baremetal.bin")) {
            if (is == null) {
                // Fallback for file system if not in classpath yet
                Path path = Paths.get("src/test/resources/bin/baremetal.bin");
                binaryData = Files.readAllBytes(path);
            } else {
                binaryData = is.readAllBytes();
            }
        }

        // Capture stdout
        PrintStream oldOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));

        try (FFMMemoryBus ram = new FFMMemoryBus(ramAmt, ramOffset)) {
            ram.getSegment().copyFrom(java.lang.foreign.MemorySegment.ofArray(binaryData));

            RV32IMAState state = new RV32IMAState();
            state.pc = ramOffset;
            state.extraflags |= 3;

            MMIOBus bus = new MMIOBus(ram);
            UARTHook uart = new UARTHook();
            CLINTHook clint = new CLINTHook(state);
            SysconHook syscon = new SysconHook();

            bus.registerHook(0x10000000, 0x100, uart);
            bus.registerHook(0x11000000, 0xC000, clint);
            bus.registerHook(0x11100000, 0x100, syscon);

            RV32IMACore core = new RV32IMACore();
            MiniRV32IMACSRHook csrHook = new MiniRV32IMACSRHook(bus, ramOffset, ramAmt);

            // Run until poweroff or 10M instructions
            for (long i = 0; i < 10000000; i += 1024) {
                core.step(state, bus, ramOffset, ramAmt, 100, 1024, null, csrHook);
                if (syscon.getLastStatus() == 0x5555) break;
            }
        } finally {
            System.setOut(oldOut);
        }

        String output = baos.toString();
        assertTrue(output.contains("Hello world from RV32 land."));
        assertTrue(output.contains("Assembly code: I'm an assembly function."));
    }
}
