package com.alienspacebunny.cli;

import com.alienspacebunny.emu.CSRHook;
import com.alienspacebunny.emu.MemoryBus;

import java.io.IOException;

/**
 * Custom CSR hook for mini-rv32ima console features.
 */
public class MiniRV32IMACSRHook implements CSRHook {
    private final MemoryBus mem;
    private final int ramOffset;
    private final int ramSize;

    public MiniRV32IMACSRHook(MemoryBus mem, int ramOffset, int ramSize) {
        this.mem = mem;
        this.ramOffset = ramOffset;
        this.ramSize = ramSize;
    }

    @Override
    public int handleRead(int csrNo) {
        if (csrNo == 0x140) {
            try {
                if (System.in.available() > 0) {
                    return System.in.read();
                }
            } catch (IOException e) {
                // Ignore
            }
            return -1;
        }
        return 0;
    }

    @Override
    public void handleWrite(int csrNo, int value) {
        switch (csrNo) {
            case 0x136 -> {
                System.out.print(value);
                System.out.flush();
            }
            case 0x137 -> {
                System.out.printf("%08x", value);
                System.out.flush();
            }
            case 0x138 -> {
                // Print string
                int addr = value;
                try {
                    while (true) {
                        byte b = mem.readByte(addr++);
                        if (b == 0) break;
                        System.out.print((char) b);
                    }
                    System.out.flush();
                } catch (Exception e) {
                    System.err.println("DEBUG PASSED INVALID PTR (" + Integer.toHexString(value) + ")");
                }
            }
            case 0x139 -> {
                System.out.print((char) value);
                System.out.flush();
            }
        }
    }
}
