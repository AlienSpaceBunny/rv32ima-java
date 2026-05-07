package com.alienspacebunny.cli;

import com.alienspacebunny.emu.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    private static int ramAmt = 64 * 1024 * 1024;
    private static int ramOffset = 0x80000000;
    private static String imageFileName = null;
    private static int timeDivisor = 1;
    private static boolean fixedUpdate = false;
    private static boolean doSleep = true;
    private static boolean singleStep = false;
    private static long instct = -1;

    public static void main(String[] args) throws IOException {
        if (!parseArgs(args)) {
            printHelp();
            return;
        }

        try (FFMMemoryBus ram = new FFMMemoryBus(ramAmt, ramOffset)) {
            loadBinary(ram, imageFileName);

            RV32IMAState state = new RV32IMAState();
            state.pc = ramOffset;
            state.extraflags |= 3; // Machine-mode

            MMIOBus bus = new MMIOBus(ram);
            UARTHook uart = new UARTHook();
            CLINTHook clint = new CLINTHook(state);
            SysconHook syscon = new SysconHook();

            bus.registerHook(0x10000000, 0x100, uart); // UART
            bus.registerHook(0x11000000, 0xC000, clint); // CLINT
            bus.registerHook(0x11100000, 0x100, syscon); // SYSCON

            RV32IMACore core = new RV32IMACore();
            MiniRV32IMACSRHook csrHook = new MiniRV32IMACSRHook(bus, ramOffset, ramAmt);

            long lastTime = fixedUpdate ? 0 : System.nanoTime() / 1000 / timeDivisor;
            int instrsPerFlip = singleStep ? 1 : 1024;

            for (long rt = 0; instct < 0 || rt < instct; rt += instrsPerFlip) {
                int elapsedUs;
                if (fixedUpdate) {
                    elapsedUs = (int) (state.getCycle() / timeDivisor - lastTime);
                } else {
                    elapsedUs = (int) (System.nanoTime() / 1000 / timeDivisor - lastTime);
                }
                lastTime += elapsedUs;

                if (singleStep) {
                    dumpState(state, ram);
                }

                int ret = core.step(state, bus, ramOffset, ramAmt, elapsedUs, instrsPerFlip, null, csrHook);
                if (ret != 0) {
                    if (ret == 1 && doSleep) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                if (syscon.getLastStatus() == 0x5555) {
                    System.out.printf("POWEROFF@0x%016x%n", state.getCycle());
                    break;
                } else if (syscon.getLastStatus() == 0x7777) {
                    // Restart logic could be implemented here
                    System.out.println("RESTART requested");
                    break;
                }
            }
            
            if (singleStep) {
                dumpState(state, ram);
            }
        }
    }

    private static boolean parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-m": if (++i < args.length) ramAmt = Integer.decode(args[i]); break;
                case "-f": if (++i < args.length) imageFileName = args[i]; break;
                case "-c": if (++i < args.length) instct = Long.decode(args[i]); break;
                case "-t": if (++i < args.length) timeDivisor = Integer.decode(args[i]); break;
                case "-l": fixedUpdate = true; break;
                case "-p": doSleep = false; break;
                case "-s": singleStep = true; break;
                default: return false;
            }
        }
        return imageFileName != null && timeDivisor > 0;
    }

    private static void printHelp() {
        System.err.println("Usage: java -jar rv32emu-cli.jar [parameters]");
        System.err.println("\t-m [ram amount]");
        System.err.println("\t-f [running image]");
        System.err.println("\t-c instruction count");
        System.err.println("\t-s single step with full processor state");
        System.err.println("\t-t time division base");
        System.err.println("\t-l lock time base to instruction count");
        System.err.println("\t-p disable sleep when wfi");
    }

    private static void loadBinary(FFMMemoryBus ram, String fileName) throws IOException {
        Path path = Paths.get(fileName);
        byte[] data = Files.readAllBytes(path);
        if (data.length > ram.getSize()) {
            throw new IOException("Image too large for RAM");
        }
        // Efficiently load data into MemorySegment
        ram.getSegment().copyFrom(java.lang.foreign.MemorySegment.ofArray(data));
    }

    private static void dumpState(RV32IMAState state, FFMMemoryBus ram) {
        System.out.printf("PC: %08x ", state.pc);
        try {
            int ir = ram.readInt(state.pc);
            System.out.printf("[0x%08x] ", ir);
        } catch (Exception e) {
            System.out.print("[xxxxxxxxxx] ");
        }
        for (int i = 0; i < 32; i++) {
            System.out.printf("x%d:%08x ", i, state.regs[i]);
            if (i % 8 == 7) System.out.println();
        }
    }
}
