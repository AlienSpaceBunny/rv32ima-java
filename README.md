# RV32IMA Java Emulator

A platform-agnostic, high-performance RISC-V RV32IMA emulator library for Java 25.

This is a Java port of the open-source [mini-rv32ima](https://github.com/cnlohr/mini-rv32ima) emulator. It is designed to be extensible, using the modern Java Foreign Function & Memory API (FFM) for efficient off-heap memory management.

## Features

- **RV32IMA Support:** Full implementation of the base integer ISA plus Multiplication (M) and Atomic (A) extensions.
- **FFM Memory:** High-performance off-heap memory access using `java.lang.foreign`.
- **Extensible Memory Bus:** Injectable hardware hooks via `MMIOBus` and `HardwareHook`.
- **Custom CSR Hooks:** Support for custom Control and Status Registers.
- **Multi-Module Maven:** Clean separation between the core emulator library and the CLI runner.
- **Platform Agnostic:** The core library is stateless and can be integrated into any Java project (e.g., fantasy consoles, simulators).

## Project Structure

- `core`: The platform-agnostic emulator library.
- `cli`: A command-line runner for testing and executing RISC-V binaries.
- `C/`: Original C source and test programs (for reference and validation).

## Getting Started

### Prerequisites

- Java 25 or higher
- Maven

### Building

```bash
./mvnw clean install
```

### Running the CLI Emulator

```bash
java -jar cli/target/rv32emu-cli-1.0-SNAPSHOT.jar -f path/to/image.bin [parameters]
```

#### Parameters:
- `-m [ram amount]`: Set the RAM size (default: 64MB).
- `-f [image file]`: Path to the RISC-V binary image to execute.
- `-c [count]`: Number of instructions to execute before stopping.
- `-s`: Enable single-step mode with register dumps.
- `-t [divisor]`: Time division base for the internal timer.

## Library Usage

```java
try (FFMMemoryBus ram = new FFMMemoryBus(ramSize, ramOffset)) {
    RV32IMAState state = new RV32IMAState();
    state.pc = ramOffset;
    
    MMIOBus bus = new MMIOBus(ram);
    // Register your hardware hooks here
    bus.registerHook(0x10000000, 0x100, new MyCustomHardware());

    RV32IMACore core = new RV32IMACore();
    core.step(state, bus, ramOffset, ramSize, elapsedUs, 1024, null, null);
}
```

## Attribution & License

This project is based on `mini-rv32ima` by Charles Lohr. 
The original C code is licensed under BSD, MIT, or CC0. 
This Java port maintains the MIT license. See [LICENSE](LICENSE) for details.
