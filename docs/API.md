# RV32IMA Core API Contracts

This document is a narrative summary of the public contracts that embedders can
rely on when using `rv32emu-core`. The Javadoc on `MemoryBus`, `HardwareHook`,
`CSRHook`, `RV32IMACore`, and `RV32IMAState` is the authoritative specification;
where this document and the Javadoc disagree, the Javadoc wins.

The core is intended to stay platform-neutral; console, board, and SoC behavior
should be composed around the core through `MemoryBus`, `HardwareHook`,
`CSRHook`, and scheduler code.

## Execution Core

`RV32IMACore()` configures the base RV32IMA_Zicsr ISA. `RV32IMACore(IsaConfig)`
accepts an `IsaConfig` for the additional extensions being layered on for the
V-32 multi-hart feature work (`RV32IMFC_ZBA_ZBB_ZICSR`, `RV32IMC_ZBB_ZICSR`, or
a custom combination). As of Phase 5b, `hasZba`, `hasZbb`, `hasZabha`, `hasC`,
and `hasF` are fully decoded — enabling one unlocks the corresponding
instructions, and the un-gated encodings still raise an illegal-instruction
trap even when the config would otherwise support them (see the
`IsaConfig`/`RV32IMACore` class Javadoc for the exact instruction list per
flag). `hasF` unlocks all of RV32F: `FLW`/`FSW`, the FP moves
(`FMV.X.W`/`FMV.W.X`), sign injection (`FSGNJ[N|X].S`), `FCLASS.S`,
comparisons (`FEQ`/`FLT`/`FLE.S`), `FMIN`/`FMAX.S` (all rounding-mode
independent, Phase 5a), and `FADD`/`FSUB`/`FMUL`/`FDIV`/`FSQRT.S`, the FMADD
family, and `FCVT.{W,WU}.S`/`FCVT.S.{W,WU}` (all rounding-mode dependent,
Phase 5b). An instruction's `rm` field selects one of the five IEEE 754
rounding modes statically, or `frm` (CSR `0x002`) dynamically when `rm` is 7;
a reserved encoding (`rm` 5 or 6, or a dynamic selector when `frm` itself
holds a reserved value) traps illegal-instruction before touching any
register or flag. When both `hasC` and `hasF` are set, the compressed forms
`C.FLW`/`C.FSW`/`C.FLWSP`/`C.FSWSP` are decoded too, expanding into the
equivalent `FLW`/`FSW`; with `hasC` but not `hasF` they still trap
illegal-instruction, via the same `hasF` check the 32-bit `FLW`/`FSW` opcodes
go through. `hasD` is not decoded at all: it
only changes the `misa` CSR value the guest reads back. `misa` is derived
from the config (`IsaConfig.misa()`); `Zba`/`Zbb`/`Zabha` have no bit of
their own in `misa` and don't affect it regardless of decode support; `C`,
`F`, and `D` do have `misa` bits and their flags set them independently of
decode support. `hasU` picks which of two mutually exclusive bits `misa`
reports: `false` (the default, `RV32IMA_ZICSR`) reproduces the exact value
this core hardcoded before `IsaConfig` existed, including a non-standard bit
22 with no architected meaning; `true` (both V-32 presets) reports the
standard U-mode bit (20) instead. Use `true` for any config whose guest code
actually runs in user mode.

`RV32IMAState.fregs` (the FP register file, `IsaConfig.hasF`) is `long[32]`,
not `float[32]`, even though only the low 32 bits are used while D remains
undecoded — see `docs/FEATURE_REQUEST_PLAN.md` Design Decision §8. Every
FP-producing instruction NaN-boxes its write (sets the upper 32 bits to
all-ones); read the low 32 bits directly, or via `Float.intBitsToFloat((int)
state.fregs[i])`. `state.fcsr` holds the rounding mode (bits 7–5, `frm`) and
accrued exception flags (bits 4–0, `fflags`); also addressable piecewise as
CSRs `0x001` and `0x002`. All five `fflags` bits (`NV`/`DZ`/`OF`/`UF`/`NX`)
are accrued now: `NV` from a signaling-NaN operand (or, for FCVT, any NaN
input, or an out-of-range input) to any FP-consuming instruction; `DZ` from a
finite nonzero dividend divided by zero (not `0/0`, which is `NV`); `OF`/`UF`
from a rounded result that overflows to infinity/saturates at the largest
finite magnitude, or underflows to a subnormal or zero; `NX` whenever the
mathematically exact result isn't exactly representable in the destination
type. `fflags`/`frm`/`fcsr` are never cleared by the core itself — the guest
CSR-writes them directly (typically before a sequence it wants to check
afterward).

`RV32IMACore.step(...)` executes up to `count` guest instructions against the
provided mutable `RV32IMAState` and `MemoryBus`.

- `state.pc` is the guest program counter at entry and is updated before return.
- `ramOffset` and `ramSize` define the legal instruction-fetch window as a
  coarse precheck. Without `IsaConfig.hasC`, a PC not 4-byte aligned within
  that window still traps misaligned, and `mem.readInt(pc)` is called exactly
  as before — nothing here changes for a non-`C` config. With `hasC`, the
  alignment requirement drops to 2 bytes, and fetch first calls
  `mem.readShort(pc)`: if its low two bits are `11`, `mem.readInt(pc)` is
  called for the full 32-bit instruction (possibly at a non-word-aligned
  address, if the previous instruction was compressed); otherwise the 16 bits
  already read are the whole instruction, expanded internally into an
  equivalent 32-bit RV32I/M operation (see `RV32IMACore`'s `decodeCompressed`
  Javadoc). Either way, an `IndexOutOfBoundsException` from any of these calls
  (for example, a bus enforcing finer-grained access control, or a 32-bit
  fetch whose last bytes fall past the window) is converted into the same
  instruction access-fault trap as a PC outside the window, with `mtval` set
  to the faulting PC.
- Data loads and stores are delegated to `MemoryBus`; bus range failures should
  throw `IndexOutOfBoundsException`, which the core converts into guest load or
  store access-fault traps.
- `elapsedUs` advances the machine timer before instruction execution.
- Return value `0` means normal execution or trap handling completed.
- Return value `1` means the CPU is waiting for interrupt and no instruction was
  executed.
- `postExec`, when provided, is called once per instruction cycle: after a
  non-trapping instruction commits its result but before the PC advances (by 2
  or 4 bytes, depending on whether the instruction was compressed), or before a
  trapping instruction's trap state is committed. It is not called when
  instruction fetch itself fails — PC outside the window, misaligned, or the bus
  rejecting the fetch as described above. For a compressed instruction, the
  `ir` value passed to `postExec` (and used for `mtval` on an
  illegal-instruction trap raised from a bad compressed encoding) is
  `RV32IMACore`'s internal 32-bit expansion of the 16-bit encoding, not the
  original 16 bits.

Timer behavior (see the `RV32IMACore` class Javadoc for the rationale behind
the two intentional spec deviations):

- `MTIP` in `mip` is raised only when `mtimecmp != 0` and `mtime >= mtimecmp`;
  it is cleared otherwise. The `mtimecmp != 0` guard suppresses a spurious
  interrupt in the reset state before the guest configures `mtimecmp`.
- `WFI` unconditionally sets `mstatus.MIE` before entering the wait state so a
  pending timer interrupt can wake the hart even if the guest had not enabled
  interrupts. If an enabled interrupt is already pending once `MIE` is set,
  `WFI` completes without stalling (`step` returns `0`) and the interrupt is
  delivered on the next `step` call. A stalled hart also re-checks for a
  deliverable interrupt at the top of every `step`, so a pending bit set on
  `mip` directly (without `injectInterrupt`'s WFI clear) still wakes it.
- `MRET` is machine-mode only: executed from user mode it traps
  illegal-instruction regardless of `mstatus.MPP`. `MRET`/`ECALL`/`EBREAK`/
  `WFI` with a non-zero `rd` or `rs1` field are illegal (reserved encodings).

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

Access context (multi-hart Phase 2): every read/write method has a
context-bearing overload taking an `AccessContext` (`hartId`, `privilege`,
`AccessKind` — `FETCH`/`LOAD`/`STORE`/`AMO`, `width` in bytes, and `atomicOp`,
the AMO/LR/SC `funct5` or `0`). `RV32IMACore` calls only the context-bearing
overloads. Each has a default that delegates to the no-context version, so
implementing just the six original methods (plus the two signed-load defaults)
continues to work unmodified — `ContextlessBus` in `AccessContextTest` is a
regression guard for this. A bus needing the metadata (per-hart MPU
enforcement, cross-hart LR/SC/AMO coordination) overrides the context-bearing
methods instead. `MMIOBus` does **not** forward `AccessContext` to
`HardwareHook` — a context-aware bus should implement `MemoryBus` directly
rather than wrap or extend `MMIOBus`. `AccessContext` does not carry `aq`/`rl`
ordering bits; per-access exclusion alone is not a payload-publication
guarantee for shared-memory IPC — see `AccessContext`'s Javadoc.

Atomics (multi-hart Phase 2): `RV32IMACore`'s AMO block (RV32A) routes
through three `MemoryBus` methods instead of computing AMO results itself.

- `atomicRmw(address, funct5, operand, ctx)` handles every RV32A AMO except
  `LR.W`/`SC.W` (the nine read-modify-write ops: `AMOSWAP`, `AMOADD`,
  `AMOXOR`, `AMOAND`, `AMOOR`, `AMOMIN[U]`, `AMOMAX[U]`). The default
  implementation is read-compute-write as two separate calls at the width
  given by `ctx.width()` — `readInt(address, ctx)`/`writeInt(address, value,
  ctx)` for a word (`width == 4`), or the byte/halfword overloads for a Zabha
  sub-word AMO (`width == 1`/`2`) — correct for a single hart, but **not
  atomic with respect to a concurrent hart** sharing the bus. A
  multi-hart-aware override must hold one lock over the granule for the read,
  compute, write, and invalidation of any overlapping LR/SC reservation in its
  own tracking, for the whole operation, at whatever width the AMO uses.
- `LR.W` routes through the existing `readInt(address, ctx)` overload — a
  multi-hart bus records `(ctx.hartId(), address)` in its own reservation
  tracking there, keyed off `ctx.kind() == AccessKind.AMO && ctx.atomicOp()
  == 2`.
- `tryScAndStore(hartId, address, value, ctx)` makes the bus's final
  atomic decision for `SC.W`. `RV32IMACore` calls this only after its own
  local fast-path check passes (`state.reservationValid` and
  `state.reservationAddr` match) — if that check fails, the bus is never
  called for the store; instead the core calls `checkAccess(address, ctx)`
  (below) and, if that returns normally, the destination register gets `1`
  (failure) with nothing written. The bus is still free to reject a store
  the core's local state believed would succeed, if it observed a cross-hart
  invalidation the core's purely local state cannot see; the default
  implementation always succeeds, correct only for a single hart. Either way
  the reservation is consumed. An override that returns a failure code must
  have permission-checked the store first (a failed `SC.W` may not retire
  without passing memory permission checks), and must consume its own
  reservation entry before throwing.
- `checkAccess(address, ctx)` is a side-effect-free permission probe: throw
  `IndexOutOfBoundsException` iff the real access described by `ctx` would
  be denied, without reading or writing anything. The default permits
  everything (so a legacy six-method bus keeps its old behavior); a bus
  with access control overrides it, and a wrapper forwards it. Today the
  core calls it only on the locally-failing `SC.W` path (`kind == AMO`,
  `width == 4`, `atomicOp == 3`); `FFMMemoryBus` bounds-checks.

Every atomic address the core hands to the bus is naturally aligned to
`ctx.width()`: a misaligned `LR.W` traps load address-misaligned (cause 4),
a misaligned `SC.W` or AMO — Zabha halfwords included — traps store/AMO
address-misaligned (cause 6), with `mtval` = the guest address, before any
bus call. (Ordinary `LW`/`SW` etc. remain misaligned-tolerant, inherited
from mini-rv32ima; only atomics enforce alignment.) `LR.W` with a non-zero
`rs2` field is an illegal instruction.

A fault (`IndexOutOfBoundsException`) thrown from `atomicRmw`,
`tryScAndStore`, or `checkAccess` becomes a standard store/AMO access fault
(cause 7, `mtval` = the AMO address), exactly like an ordinary faulting
store — see `AtomicPrimitivesTest`. Reservation lifecycle: every
`LR.W`/`SC.W` attempt clears the hart's local reservation before the bus is
consulted, so a faulting `SC.W` (or `LR.W`) never leaves a stale
`reservationValid`; only a successful `LR.W` establishes one.

Bus wrappers (address routers, MPU views, boot overlays) must forward the
context-bearing overloads *and* `atomicRmw`/`tryScAndStore`/`checkAccess`
to the wrapped bus; otherwise the defaults drop the context and split
every AMO into a read/write pair at the wrapper. `MMIOBus` does this for
addresses no hook claims (hook addresses still take the no-context
`HardwareHook` path, since that interface carries no context).

Zabha (byte/halfword AMOs, Phase 3): with `IsaConfig.hasZabha`, the RV32A
opcode's `funct3` field also admits `0` (byte) and `1` (halfword) for the nine
read-modify-write AMOs above — `LR.W`/`SC.W` remain word-only; Zabha does not
define a sub-word `LR`/`SC`. `atomicRmw`'s default sign-extends the loaded
value and truncates `operand` to the AMO's width before computing, and writes
back only the low `width` bytes — see `atomicRmw`'s Javadoc for why this
default is correct for `AMOMIN[U]`/`AMOMAX[U]`'s signed and unsigned
comparisons without a separate sub-word code path. `RV32IMACore` never
widens a sub-word AMO into a word-width bus access. See `ZabhaTest`.

Zba/Zbb (Phase 3): `RV32IMACore` decodes `SH1ADD`/`SH2ADD`/`SH3ADD` when
`IsaConfig.hasZba`, and all 18 Zbb basic bit-manipulation instructions when
`IsaConfig.hasZbb` (`CLZ`, `CTZ`, `CPOP`, `SEXT.B`, `SEXT.H`, `ZEXT.H`, `MIN`,
`MINU`, `MAX`, `MAXU`, `ANDN`, `ORN`, `XNOR`, `ROL`, `ROR`, `RORI`, `ORC.B`,
`REV8`). These are pure register/immediate operations with no `MemoryBus`
involvement. See `RV32IComplianceTest`'s Zba/Zbb sections for the full
instruction-by-instruction coverage, including `IsaConfig` gating in both
directions.

F extension, rounding-mode-independent subset (Phase 5a): with
`IsaConfig.hasF`, `FLW`/`FSW` route through the existing
`readInt(address, ctx)`/`writeInt(address, value, ctx)` overloads exactly
like `LW`/`SW`, and `FSW` invalidates any held LR/SC reservation like any
other store. `FMIN.S`/`FMAX.S` are not `Math.min`/`Math.max` — RISC-V
returns the non-NaN operand when exactly one operand is NaN (the canonical
NaN only when both are), and `-0.0` compares below `+0.0`; both were
hand-rolled rather than reusing the JDK methods. `FEQ.S` is a quiet
comparison (only a signaling NaN operand sets `fcsr`'s `NV` bit); `FLT.S`/
`FLE.S` are signaling (any NaN operand, quiet or not, sets `NV`). See
`FExtensionTest` for the full instruction-by-instruction coverage, including
the NaN-boxing, `IsaConfig` gating, and `fcsr`/`fflags`/`frm` CSR behavior.

F extension, rounding-mode layer (Phase 5b): `FADD`/`FSUB`/`FMUL`/`FDIV`/
`FSQRT.S`, the FMADD/FMSUB/FNMSUB/FNMADD.S family, and
`FCVT.{W,WU}.S`/`FCVT.S.{W,WU}` are all computed by pairing a
correctly-rounded `double`-precision approximation of the true result with
the exact sign of its residual (via Knuth's TwoSum for FADD/FSUB and the FMA
family's addend step, or a `Math.fma`-computed exact residual for FDIV and
FSQRT), then rounding that pair to `float` in whichever of the five IEEE 754
modes the instruction's `rm` field (or dynamic `frm`) selects — this is
deliberately *not* "round the native `double` result once," which is unsafe
for directed rounding modes (see `docs/FEATURE_REQUEST_PLAN.md`'s F Extension
design section for the specific counterexample this caught in review). The
FMA family fuses its multiply and add into that single rounding rather than
computing `a*b` as a separately-rounded `float` first. `FCVT.{W,WU}.S` rounds
its input to an integer per `rm`, then range-checks the *rounded* value
before saturating — not the other way around, which matters at boundaries
like `FCVT.WU.S(-0.5)` (in range under RTZ, which rounds to `-0`; out of
range under RDN, which rounds to `-1`). See `FExtensionRoundingTest`,
including its randomized differential coverage against independent
`BigDecimal`-exact and `Math.fma` oracles (deliberately not the same
TwoSum/`Math.fma`-residual formulas this class's implementation uses).

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
- **Privilege gating** (Phase 2): before any of the above, the core checks the
  CSR number's minimum-privilege field (bits 9–8 of the 12-bit CSR number, the
  standard RISC-V CSR address convention) against the hart's current privilege
  and raises an illegal-instruction trap — without calling the hook — if the
  hart's privilege is lower. This applies to hook-routed CSR numbers too, even
  ones that don't follow the convention on purpose: a custom CSR meant to be
  reachable from user-mode guest code needs an address whose bits 9–8 are
  `0b00`.

## RV32IMAState

`RV32IMAState` is mutable execution state.

- Integer registers, PC, key machine CSRs, cycle counter, and timer registers
  are public fields for simple embedding and checkpointing.
- `hartId` identifies this hart among others sharing a `MemoryBus`. Defaults to
  `0`. The core does not read or write it as of the Phase 1 foundation work; a
  multi-hart-aware `MemoryBus` is expected to key per-hart state by it once bus
  access metadata lands. Embedders with more than one concurrently participating
  hart must assign distinct, stable IDs.
- `getCycle()/setCycle()`, `getTimer()/setTimer()`, and
  `getTimerMatch()/setTimerMatch()` expose the 64-bit split registers; prefer
  them over the raw half-word fields except in a CLINT MMIO hook.
- `extraflags` holds the privilege level (bits 0–1: machine = `3`, user = `0`)
  and the WFI flag (bit 2).
- LR/SC reservation state is held separately in `reservationAddr` and
  `reservationValid`.
