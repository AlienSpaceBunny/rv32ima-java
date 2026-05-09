# RV32IMA Core API Contracts

This document records the public contracts that embedders can rely on when
using `rv32emu-core`. The core is intended to stay platform-neutral; console,
board, and SoC behavior should be composed around the core through `MemoryBus`,
`HardwareHook`, `CSRHook`, and scheduler code.

## Execution Core

`RV32IMACore.step(...)` executes up to `count` guest instructions against the
provided mutable `RV32IMAState` and `MemoryBus`.

- `state.pc` is the guest program counter at entry and is updated before return.
- `ramOffset` and `ramSize` define the legal instruction-fetch window.
- Data loads and stores are delegated to `MemoryBus`; bus range failures should
  throw `IndexOutOfBoundsException`, which the core converts into guest load or
  store access-fault traps.
- `elapsedUs` advances the machine timer before instruction execution.
- Return value `0` means normal execution or trap handling completed.
- Return value `1` means the CPU is waiting for interrupt and no instruction was
  executed.
- `postExec`, when provided, is called after instruction execution or before
  trap handling for the instruction that caused a trap.

Known timer behavior inherited from the current implementation:

- Timer interrupt pending is set only when `mtimecmp != 0`.
- Timer interrupt pending currently uses `mtime > mtimecmp`; P6 will change this
  to `mtime >= mtimecmp`.
- `WFI` sets `mstatus.MIE` before entering wait state so timer interrupts can
  wake the CPU.

## MemoryBus

`MemoryBus` is the address-space abstraction for guest data access and
instruction fetch after the core's instruction-fetch window check.

- Addresses are unsigned 32-bit guest addresses represented as Java `int`.
- Implementations provide byte, halfword, and word operations.
- Implementations should throw `IndexOutOfBoundsException` for unmapped or
  disallowed addresses so the core can raise guest access-fault traps.
- `readByte()` and `readShort()` return raw Java byte/short values; callers use
  the signed default helpers or unsigned masking depending on instruction
  semantics.
- Multi-byte endianness is intended to be little-endian. P5 will make this
  explicit in implementation and tests.

## MMIOBus and HardwareHook

`MMIOBus` composes a backing RAM bus with registered `HardwareHook` ranges.

- Hook ranges are start-inclusive and end-exclusive.
- Hook matching uses unsigned 32-bit address ordering.
- Invalid, wrapping, null, zero-size, and overlapping registrations are rejected.
- Once an address matches a hook range, that range owns the access. Reads and
  writes do not fall through to backing RAM.
- `HardwareHook.handleWrite(...)` receives the guest address, an unsigned value
  normalized to the requested width, and width in bytes.
- `HardwareHook.handleRead(...)` receives the guest address and width in bytes;
  the returned value is narrowed by `MMIOBus` for byte and halfword reads.
- Unrecognized offsets inside a registered device range should be ignored or
  read according to that device's own contract.

For richer machines, such as a fantasy console with VRAM, MPU translation, and
multiple processors, prefer a dedicated `MemoryBus` crossbar that delegates to
RAM, VRAM, and hooks. Keep `MMIOBus` for simple range-routed devices.

## CSRHook

`CSRHook` handles custom CSRs that are not implemented directly by
`RV32IMACore`.

- Known machine CSRs are handled by the core first.
- Unknown CSR reads call `CSRHook.handleRead(...)` when a hook is present, or
  return `0` when no hook is present.
- Unknown CSR writes call `CSRHook.handleWrite(...)` when a hook is present, or
  are ignored when no hook is present.
- CSR instruction side-effect rules are enforced by the core before invoking
  the hook.

## RV32IMAState

`RV32IMAState` is mutable execution state.

- Integer registers, PC, key machine CSRs, cycle counter, and timer registers
  are public fields for simple embedding and checkpointing.
- `getCycle()/setCycle()`, `getTimer()/setTimer()`, and
  `getTimerMatch()/setTimerMatch()` expose the 64-bit split registers.
- `extraflags` currently stores privilege, WFI state, and LR/SC reservation bits.
  P7 will replace the LR/SC reservation encoding with explicit reservation
  state.
