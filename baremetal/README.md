# Baremetal RISC-V Test for RV32IMA Emulator

This directory contains a standalone baremetal test program for the RV32IMA emulator.

## Origin
This code was originally part of the [mini-rv32ima](https://github.com/cnlohr/mini-rv32ima) project by Charles Lohr.

## Changes for the Java Port
To facilitate testing in environments without a dedicated RISC-V GCC toolchain, the following changes were made:

1. **Clang/LLVM Support:** The `Makefile` was refactored to use `clang` with the `--target=riscv32-none-elf` triple and the `ld.lld` linker.
2. **Freestanding Cleanup:** Removed unused standard library includes (`stdio.h`, `stdarg.h`) from `baremetal.c` to support standalone compilation without a libc.
3. **Relocation Fixes:** Updated `baremetal.S` to mark the entry section correctly and use the `call` pseudo-instruction for reliable jump range handling.
4. **Code Model:** Configured for `medany` code model and disabled linker relaxation to match the fixed `0x80000000` memory map.

## How to Build
Requires `clang` and `lld` with RISC-V support.

```bash
make clean
make
```

The resulting `baremetal.bin` is used for integration testing in the Java emulator's CLI module.
