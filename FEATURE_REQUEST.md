# rv32emu-core Feature Request for V-32 AP/IOP Support

## Summary

The V-32 emulator is moving from a flat single-core prototype to an asymmetric
dual-core system:

- AP: application processor, user-mode guest code.
- IOP: I/O processor, machine-mode microkernel and device manager.

The emulator can model some of the crossbar and MPU behavior locally, but the
CPU core needs a few features so AP/IOP memory protection, traps, interrupts,
and IPC atomics behave correctly.

## Requested ISA Support

- AP target ISA: `RV32IMFC_Zba_Zbb`.
- IOP target ISA: `RV32IMC_Zbb`.
- Add the byte/halfword atomic support needed by the project if this maps to
  the intended `Zab` extension target.
- Keep and complete `Zicsr`; it is needed for trap/interrupt control and
  privilege state even though it is not the mailbox mechanism itself.

## Requested Core/Bus Interfaces

- Pass access metadata to memory hooks or a bus-access callback:
  - hart id,
  - privilege mode,
  - access kind: instruction fetch, data read, data write,
  - access width,
  - atomic operation kind when applicable.
- Let the embedding emulator reject accesses in a way the core converts into
  the correct guest trap for fetch/load/store faults.
- Support AP user mode and IOP machine mode well enough for:
  - AP execution exclusively in user mode,
  - IOP execution in machine mode,
  - `ECALL`, `MRET`, `mtvec`, `mepc`, `mcause`, `mtval`, `mstatus`, `mie`,
    and `mip` behavior used by a simple microkernel.
- Add external/software interrupt injection hooks so MMIO devices such as the
  IPC mailbox can interrupt the receiving processor.

## Requested Atomic Semantics

The mailbox can initially be emulated as synchronized MMIO in this repo, but
shared-memory IPC and lock-free queues need correct cross-hart atomics:

- LR/SC reservations must be invalidated by writes from another hart to the
  reserved address or reservation granule.
- AMOs must be atomic with respect to both harts.
- The bus or core should provide enough coordination that two core instances
  sharing one memory bus cannot interleave an AMO as separate read/write
  operations.

## Why Emulator-Side Checks Are Not Enough

The V-32 design has IOP-controlled base-and-bound memory protection:

`physical_address = ap_logical_address + iop_configured_base`

The emulator can implement that translation locally, but it needs the core to
distinguish instruction fetches, data accesses, privilege level, and atomics so
it can enforce AP-visible RAM/MMIO rules and report the right trap.

## Current Workaround

Until these features land, the emulator will:

- enforce AP RAM base/limit in its AP-facing memory bus where possible,
- keep IOP-only syscon/MPU registers hidden from AP,
- use Java-synchronized MMIO hooks for mailbox-style IPC,
- avoid relying on guest LR/SC or AMO correctness across AP and IOP.
