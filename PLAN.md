# Plan for Converting RV32IMA Emulator from C to Java

## 1. Core Emulator Conversion Strategy

### 1.1 State Management
- Create `RV32IMAState.java` class mirroring `struct MiniRV32IMAState`
- Fields: `int[] regs`, `int pc`, `int mstatus`, `int cyclel`, `int cycleh`, etc.
- Use Java primitives (int) matching 32-bit widths from C
- Add helper methods for CSR access if needed

### 1.2 Memory System
- Maintain byte[] RAM array (equivalent to `uint8_t * ram_image`)
- Implement memory access methods matching C macros:
  - `load1`, `load2`, `load4` (signed/unsigned variants)
  - `store1`, `store2`, `store4`
- Handle memory-mapped I/O ranges (0x10000000-0x12000000)

### 1.3 Instruction Execution
- Create `RV32IMACore.java` with `step()` method equivalent to `MiniRV32IMAStep`
- Implement full RV32IMA instruction set:
  - Base ISA: LUI, AUIPC, JAL, JALR, Branches, Loads, Stores, OPIM, OP
  - Extensions: M (mul/div), A (atomic), Zicsr (CSR), Zifencei
- Maintain cycle counting and timer logic
- Handle traps/interrupts same as C version

### 1.4 CSR and Exception Handling
- Implement all CSR operations (mstatus, mie, mip, mepc, mcause, etc.)
- Handle environment calls (ECALL), breakpoints (EBREAK)
- Implement MRET and WFI instructions
- Trap handling with same priority/behavior as C version

### 1.5 Platform I/O
- Create platform abstraction layer for:
  - UART (0x10000000): Console I/O
  - CLINT Timer (0x11000000-0x1100bfff): Timer and timecmp
  - Syscon (0x11100000): Reboot/poweroff
  - Custom CSRs (0x136-0x139, 0x140): Debug/console features
- Implement keyboard input handling similar to C version
- Maintain same memory-mapped I/O behavior

## 2. Java Project Structure

### 2.1 Main Package Structure
```
src/main/java/
  └── com/alienspacebunny/
      ├── emulator/
      │   ├── RV32IMAState.java
      │   ├── RV32IMACore.java
      │   ├── Memory.java
      │   └── PlatformIO.java
      ├── Main.java (updated to use emulator classes)
      └── utils/
          ├── NumberParser.java (SimpleReadNumberInt equivalent)
          └── TimeUtils.java (GetTimeMicroseconds equivalent)
```

### 2.2 Key Implementation Details
- Use `int` for all 32-bit values (matching C `uint32_t` behavior)
- Handle sign extension properly in load/store operations
- Implement instruction decode using switch statements similar to C
- Maintain precise timing behavior for interrupts
- Preserve exact CSR behavior and bit layouts

## 3. Test Case Utilization

### 3.1 Useful Test Directories
- **baremetal**: 
  - Contains assembly (.S) and C test programs
  - Can be compiled to RISC-V binaries for Java emulator testing
  - Includes linker script demonstrating memory layout
  - **Action**: Compile these to create test binaries for JVM testing

- **hello_linux**:
  - Simple "hello world" Linux test program
  - Can be compiled to test Linux boot capability
  - **Action**: Compile and use as boot test binary

- **configs**:
  - Device tree sources (.dts) showing hardware configuration
  - **Action**: Use for understanding memory map, not direct Java conversion

- **cachetest**: 
  - GPU-focused and incomplete
  - **Action**: Not useful for CPU emulator testing

- **attic**:
  - Historical scripts
  - **Action**: Not useful

### 3.2 Java Testing Approach
- Create JUnit test class for CPU core
- Test individual instructions with known inputs/outputs
- Test CSR operations and exception handling
- Test memory access and I/O behavior
- Create integration tests using compiled test binaries
- Use baremetal/hello_linux binaries as integration test targets

## 4. Migration Steps

### 4.1 Phase 1: Core Infrastructure
1. Create state and memory classes
2. Implement basic memory access
3. Create skeleton core class

### 4.2 Phase 2: Instruction Set
1. Implement integer instructions (LUI, AUIPC, OPIM, OP)
2. Implement control flow (JAL, JALR, Branches)
3. Implement load/store instructions
4. Add M extension (mul/div)

### 4.3 Phase 3: System Operations
1. Implement CSR instructions
2. Add exception/trap handling
3. Implement timer and interrupts
4. Add platform I/O (UART, syscon)

### 4.4 Phase 4: Integration and Testing
1. Create main execution loop
2. Add command-line argument handling
3. Integrate with test binaries from baremetal/hello_linux
4. Verify behavior matches C implementation

## 5. Considerations and Challenges

### 5.1 Behavioral Matching
- CSR side effects must be identical
- Memory access fault behavior must be preserved
- Interrupt timing critical for proper operation

### 5.2 Performance
- Java may be slower than optimized C
- Focus on correctness first, then optimize
- Consider using arrays instead of objects for performance-critical paths

### 5.3 Testing Strategy
- Compare register dumps between C and Java versions
- Use same test binaries for both implementations
- Validate against known-good outputs from C version
- Test edge cases: misaligned accesses, illegal instructions, etc.

This plan provides a comprehensive approach to converting the C emulator to Java while maintaining behavioral compatibility and leveraging existing test infrastructure.
