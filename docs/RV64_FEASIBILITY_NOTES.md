# RV64 Feasibility Notes

> **Status: speculative. No decision has been made, no design has been signed off, and no
> implementation work has started or is scheduled.** This document records a conversational
> exploration of "could this library eventually support RV64 too" from 2026-09-13, so the
> reasoning doesn't have to be redone from scratch if the question comes up again. Treat
> every conclusion here as provisional and re-derive/re-verify against the code before
> acting on it — especially file:line references, which will drift.
>
> **Snapshot basis:** `rv32ima-java` at commit `1b3a29f` (2026-09-13). Motivation, per Nate:
> this would be about *generalizing the project for other potential users/consumers*, not a
> need of the current downstream consumer (the V-32 emulator repo, which is RV32-only and
> has no plans to change that). It is not urgent and not on any roadmap.

## The question

If we wanted an RV64 variant of this emulator, is it practical to have one library
implement both RV32 and RV64, or would it be better to fork the repo, or write a clean RV64
implementation from the spec?

## Survey: how RV32-specific is the current design?

(Read-only survey of the codebase as of `1b3a29f`, ~3363 lines across 10 files in
`core/src/main/java/.../emu/`, with `RV32IMACore.java` — the interpreter — at 1910 lines.)

- **Registers**: `RV32IMAState.regs` is `int[32]`. All ALU ops in `RV32IMACore.java` are
  native Java `int` arithmetic (e.g. `Integer.compareUnsigned`, `Integer.divideUnsigned` for
  unsigned variants). No width abstraction exists — `int` *is* the register type.
- **Memory bus**: `MemoryBus` is hardcoded to `int address` on every method
  (`readByte(int)`, `readInt(int)`, `writeInt(int, int)`, …), documented as "unsigned 32-bit
  guest physical addresses represented as Java `int`." No 64-bit accessors exist.
  `CHECKPOINT.md` records this as a deliberate choice ("`MemoryBus` 64-bit access explicitly
  deferred, since FLW/FSW don't need it").
- **Shifts**: shift amounts are masked `& 0x1F` (5-bit) at each shift site. RV64 needs 6-bit
  shamt (`& 0x3F`) plus the shamt[5]/imm[25] encoding bit — mechanical, but touches every
  shift case individually.
- **W-suffix ops** (ADDIW/ADDW/SLLIW/etc., RV64-only): don't exist yet. They'd land on
  currently-unused major opcodes (`0x1B`/`0x3B`) — purely additive to the decode table, no
  conflict with existing RV32 opcodes.
- **Load/store widths**: the load/store switch only handles byte/half/word; LWU/LD/SD funct3
  slots (3, 6, 7) currently fall through to illegal-instruction.
- **Compressed decode**: already has an explicit marker for this —
  `RV32IMACore.java` (quadrant-1 decode): `yield 0; // reserved on RV32 (RV64 C.SUBW/C.ADDW
  live here)`. `FEATURE_REQUEST_PLAN.md` similarly documents that the RV64 FCVT long forms
  (`rs2` field 2/3) explicitly trap illegal-instruction today.
- **CSRs**: `mstatus`, `mepc`, `mtval`, `mcause`, `mie`, `mip`, `mtvec`, `mscratch` are all
  plain `int` fields on `RV32IMAState`. `IsaConfig.misa()` hardcodes `MISA_MXL32`; no
  RV64 `mstatus` fields (SXL/UXL, `mstatush`) exist or are anticipated. The CLINT's 64-bit
  registers (cycle/timer/timercmp) are already split into `*l`/`*h` int-pair halves — a
  deliberate 32-bit-register-pair workaround, not a native 64-bit CSR.
- **F extension / future D**: FP registers are already `long[32]`, NaN-boxed, explicitly
  sized "so a later D extension does not need a breaking storage-width change." This part
  needs **no** rework for RV64 — D is XLEN-orthogonal, and the F-extension rounding-mode
  arithmetic (`roundToFloat`, `exactSum`/`exactMul`/`exactDiv`/`exactSqrt`/`exactFma`,
  `fAddSubS`, etc.) operates on `float`/`double`/NaN-boxed bits, never on GPR width.
- **`IsaConfig`**: a pure feature-flag `record` (`hasC`, `hasF`, `hasD`, `hasZba`, `hasZbb`,
  `hasZabha`, `hasU`) with a computed `misa()`. No XLEN/MXL axis exists — every config
  implicitly assumes XLEN=32.
- **Docs**: every existing RV64 mention in the repo (`CHECKPOINT.md`,
  `FEATURE_REQUEST_PLAN.md`, test comments) is a "this is reserved for/traps as illegal on
  RV64, not implemented" note. There's no half-finished RV64 groundwork anywhere, and no
  prior design doc laying out a migration plan.

## Three clarifications that shaped the answer

Nate's follow-up added three constraints not assumed in the initial survey:

1. **Support both RV32 and RV64, not "upgrade to RV64."** RV32 must remain exactly as
   capable and as cheap as it is today, indefinitely — this isn't a migration.
2. **No requirement to mix RV32 and RV64 harts in one running system.** RV32-only systems
   never need 64-bit anything.
3. **Don't force 32-bit-only consumers to pay for a 64-bit bus.** The existing `int`-addressed
   `MemoryBus` and its consumer (the emulator repo) must not be made more expensive or
   have its API changed on RV64's account.
4. **Motivation is generalizing the library for potential future consumers**, not a need of
   the current one — reinforces #3 (added cost must land only on people who opt into RV64)
   and means this is discretionary, not time-pressured.

### Why these simplify the design question rather than complicate it

The open question after the initial survey was "parameterize `MemoryBus`/the core on word
width, or split into separate implementations?" These four points resolve it in favor of
**split**:

- Constraint 2 removes the only reason a *unified, runtime-polymorphic* core/bus would be
  needed (heterogeneous mixed-width systems). Without that, XLEN can be fixed at
  construction time per instance rather than dispatched on per access.
- Constraint 3 rules out the alternative unification strategy — widening `MemoryBus` to
  `long` addresses everywhere and having RV32 mask/truncate. That was the design implied by
  the original survey answer; it's now explicitly off the table.
- Java has no reified generics over primitives, so a single generic `Core<Word>` /
  `MemoryBus<Addr>` was never going to be free: it either boxes `int`/`long` in the
  interpreter's hot loop (a tax on *everyone*, including RV32) or forces `long` storage on
  RV32 too (which constraint 3 forbids). With constraint 2 removing the only benefit such a
  design would buy (mixed-width interop), there's no remaining case for it in Java
  specifically — a split implementation isn't a compromise here, it's the only good option
  once these constraints are in place.

### Resulting shape, if this were ever pursued

Purely illustrative — not a plan, not sized, not scheduled:

- `MemoryBus` (current, `int`-addressed) is untouched. Zero API impact on the existing
  RV32 consumer; this is the concrete win from constraint 3.
- A new, separate 64-bit-addressed bus interface for an RV64 build, unrelated at the type
  level to the RV32 one (not a supertype/subtype or generic instantiation of it).
- Two concrete core/state class families: `RV32IMACore`/`RV32IMAState` (unchanged) and a new
  `RV64...` pair doing native `long` arithmetic, no masking overhead.
- A shared, width-independent slice extracted for the pieces that already don't care about
  XLEN: the F-extension arithmetic, exception-cause constants, and the `IsaConfig`
  feature-flag pattern (likely gaining an `xlen`/`mxl`-style field). This is the part worth
  protecting from duplication — it's also the part that took real effort to get right
  ([[#Survey|see F extension notes above]]).
- ALU/decode bodies (the actual opcode `switch` logic) would be written twice, once per
  width. That duplication is treated as acceptable rather than a problem to engineer away,
  since Java offers no zero-cost way to share it without imposing a cost on RV32.

### What would make this worth revisiting

No specific trigger is currently planned. This would become concrete if a real second
consumer wanted RV64 specifically, or if "generalizing for other users" solidified from an
aside into an actual goal. Until then, this document is the extent of the investigation —
do not treat any of the above as committed design.
