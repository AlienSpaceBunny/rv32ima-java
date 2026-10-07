# RV32IMA Java Emulator

A RISC-V emulator library for Java 25, ported from
[mini-rv32ima](https://github.com/cnlohr/mini-rv32ima). The core separates CPU
execution from memory and devices, with mutable state for each hart and an
off-heap memory bus built on Java's Foreign Function & Memory API.

## Features

- RV32I, M, A and Zicsr, with configurable C, F, Zba, Zbb and Zabha extensions.
- Machine/user privilege, traps, timer/software/external interrupts and WFI.
- Replaceable `MemoryBus`, MMIO device hooks and custom CSR hooks.
- Access metadata and atomic bus primitives for embedders implementing shared-memory
  multi-hart systems. The default bus primitives support a single hart; concurrent
  harts require bus-side coordination.
- Separate core library and reference CLI runner; core has no runtime dependencies.

The D extension is not implemented. See [API contracts](docs/API.md) for
configuration, concurrency requirements and intentional architectural deviations.

## MMIO Hooks

`MMIOBus` routes accesses by registered address range. Hook ranges are device-owned:
once an address matches a hook, the read or write is handled by that hook and
does not fall through to the backing RAM bus. Unrecognized offsets inside a
device range should be ignored or read according to that device's own contract.

See [Core API Contracts](docs/API.md) for the public integration contracts.

## Project Structure

- `core`: The platform-agnostic emulator library.
- `cli`: A command-line runner for testing and executing RISC-V binaries.
- `C/`: Original C source and test programs (for reference and validation).
- `baremetal/`: Baremetal test binary and its build sources.
- `docs/`: [API reference and development documentation](docs/README.md).

## Getting Started

### Prerequisites

- Java 25 or higher
- The included Maven wrapper (`./mvnw`); no separate Maven installation is needed.

### Building

```bash
./mvnw clean verify
```

This builds the jars and runs formatting, static analysis, tests and the packaged
CLI smoke test. Use `./mvnw install` to make the library available to other local
Maven projects. Development artifacts are snapshots; Central publication is on hold.

### Running the CLI Emulator

```bash
java -jar cli/target/rv32emu-cli-<version>.jar -f path/to/image.bin [parameters]
```

Use the version from `pom.xml` for `<version>`. The runner loads a flat binary
at guest address `0x80000000` and uses the base RV32IMA_Zicsr configuration.

| Option | Meaning |
|---|---|
| `-f <file>` | Flat RISC-V binary image (required). |
| `-m <bytes>` | RAM size; defaults to 64 MiB. |
| `-c <count>` | Instruction budget, checked between batches; unlimited by default. |
| `-s` | Execute one instruction per batch and dump register state. |
| `-t <divisor>` | Positive timer divisor; defaults to 1. |
| `-l` | Derive elapsed time from the cycle counter. |
| `-p` | Disable sleeping while waiting for an interrupt. |

## Library Usage

```java
import com.alienspacebunny.emu.FFMMemoryBus;
import com.alienspacebunny.emu.RV32IMACore;
import com.alienspacebunny.emu.RV32IMAState;

int ramSize = 64 * 1024;
int ramOffset = 0x80000000;
try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, ramOffset)) {
    ram.writeInt(ramOffset, 0x00000013); // ADDI x0, x0, 0 (NOP)
    RV32IMAState state = new RV32IMAState();
    state.pc = ramOffset;
    state.extraflags = 3; // Machine mode

    RV32IMACore core = new RV32IMACore();
    core.step(state, ram, ramOffset, ramSize, 0, 1, null, null);
}
```

This executes one NOP. For a complete machine, load guest code into RAM, provide
devices through a `MemoryBus`, and call `step` from the hart's scheduler.

## Attribution & License

Based on `mini-rv32ima` by Charles Lohr. This Java port uses the MIT license;
see [LICENSE](LICENSE).
