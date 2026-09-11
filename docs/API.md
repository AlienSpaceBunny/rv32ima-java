# RV32IMA Core API Contracts

This document is a narrative summary of the public contracts that embedders can
rely on when using `rv32emu-core`. The Javadoc on `MemoryBus`, `HardwareHook`,
`CSRHook`, `RV32IMACore`, and `RV32IMAState` is the authoritative specification;
where this document and the Javadoc disagree, the Javadoc wins.

The core is intended to stay platform-neutral; console, board, and SoC behavior
should be composed around the core through `MemoryBus`, `HardwareHook`,
`CSRHook`, and scheduler code.

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
- `postExec`, when provided, is called once per instruction cycle: after a
  non-trapping instruction commits its result but before the PC advances, or
  before a trapping instruction's trap state is committed. It is not called when
  instruction fetch itself fails (PC outside the window, or misaligned).

Timer behavior (see the `RV32IMACore` class Javadoc for the rationale behind
the two intentional spec deviations):

- `MTIP` in `mip` is raised only when `mtimecmp != 0` and `mtime >= mtimecmp`;
  it is cleared otherwise. The `mtimecmp != 0` guard suppresses a spurious
  interrupt in the reset state before the guest configures `mtimecmp`.
- `WFI` unconditionally sets `mstatus.MIE` before entering the wait state so a
  pending timer interrupt can wake the hart even if the guest had not enabled
  interrupts.

Interrupt gating and injection:

- Three machine interrupts are modeled: `MTIP` (bit 7, timer, core-managed from
  `mtimecmp` as above), `MSIP` (bit 3, software), and `MEIP` (bit 11, external).
  An embedder sets `MSIP`/`MEIP` directly on `state.mip`/`state.mie`, or via the
  static helper `RV32IMACore.injectInterrupt(state, bit)`, which also clears the
  hart's WFI flag so a stalled hart wakes.
- All three are gated the same way: the bit must be set in both `mip` and `mie`,
  and either the hart is currently in user mode, or `mstatus.MIE` is set. This
  core models only machine and user privilege, so `mstatus.MIE` only masks
  interrupts while executing in machine mode — a machine interrupt that is
  individually enabled is always taken while the hart is running in user mode,
  per the RISC-V privileged spec.
- If more than one is simultaneously pending and enabled, priority is external >
  software > timer.
- Injecting an interrupt into another hart's `RV32IMAState` from a different
  thread (for example, one hart signaling another) is the caller's
  responsibility to synchronize; `RV32IMAState` itself provides no locking.

## MemoryBus

`MemoryBus` is the address-space abstraction for guest data access and
instruction fetch after the core's instruction-fetch window check.

- Addresses are unsigned 32-bit guest addresses represented as Java `int`.
- Implementations provide byte, halfword, and word operations.
- Implementations should throw `IndexOutOfBoundsException` for unmapped or
  disallowed addresses so the core can raise guest access-fault traps.
- `readByte()` and `readShort()` return raw Java byte/short values; callers use
  the signed default helpers (`readByteSigned`, `readShortSigned`) or unsigned
  masking depending on instruction semantics.
- Multi-byte accesses (`short`, `int`) are little-endian. `FFMMemoryBus`
  enforces this explicitly and `FFMMemoryBusEndianTest` covers it. Big-endian
  hosts are not supported.

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
  `getTimerMatch()/setTimerMatch()` expose the 64-bit split registers; prefer
  them over the raw half-word fields except in a CLINT MMIO hook.
- `extraflags` holds the privilege level (bits 0–1: machine = `3`, user = `0`)
  and the WFI flag (bit 2).
- LR/SC reservation state is held separately in `reservationAddr` and
  `reservationValid`.
