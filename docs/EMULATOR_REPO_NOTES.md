# V-32 Emulator Repo Notes

**Purpose:** this repo (`rv32ima-java`) is a library consumed by a sibling application repo,
referred to throughout this project's docs as "the emulator repo" or "V-32" — a fantasy-console
emulator with two RISC-V harts (an Application Processor and an I/O Processor) built on
`RV32IMACore`. `docs/FEATURE_REQUEST.md` and `docs/FEATURE_REQUEST_PLAN.md` originated from
that repo's needs. This document summarizes what was learned by directly reading that repo's
source on 2026-09-13, so future work here doesn't need to re-read across repos just to answer
"does the emulator already do X" — check here first, and treat anything specific (file names,
line-level behavior) as a hint to verify with a targeted read, not as current truth, since that
repo evolves independently of this one.

**Snapshot basis:** emulator repo at commit `3c3bdd9` ("Bump rv32 core version"), local path
`../emulator` relative to this repo (i.e., sibling checkouts under the same parent directory).
**This will go stale.** Re-check with a direct read (or ask the emulator repo's own session)
before relying on specifics for anything consequential — this is a snapshot, not a live view.

## What the emulator repo is

A two-hart machine: an **Application Processor (AP)** running guest/game code, and an **I/O
Processor (IOP)** acting as a privileged supervisor — boot handoff, MPU-style AP RAM windowing,
syscon/control registers. Both harts are separate `RV32IMACore` instances with their own
`RV32IMAState`, driven by `ApplicationProcessor`/`IOProcessor` (both `Runnable`), started as
**real OS threads** from `Driver.java` (`new Thread(machine.applicationProcessor(), "ap")`,
`new Thread(machine.ioProcessor(), "iop")`, both `.start()`'d — plus a third `"video"` thread).
This is genuine concurrency today, not a future design point.

## Memory topology (why this matters for `rv32ima-java`'s multi-hart contract)

`EmulatorMachine`'s constructor wires one `SystemRAMBus` instance (`ramBus`, wrapping a single
off-heap `MemorySegment` via `java.lang.foreign`) that **both harts ultimately reach**:

- IOP: `mmioBus = new MMIOBus(ramBus)` → IOP's private `BootBus` (a `MemoryBus`-implementing
  inner class of `IOProcessor`) falls through to `mmioBus`/`ramBus` for any address outside its
  small private boot-code window.
- AP: `ApMemoryBus` wraps the *same* `ramBus` as its `systemRam` field, translating AP's logical
  RAM addresses through IOP-configured base/limit registers (`SystemControlHook`) before
  reaching it — a software MPU. Non-RAM AP accesses go through `sharedBus` (=`mmioBus`) with an
  allow-list (`ROM`/`VRAM`/video/input/palette/cartridge readable; `VRAM`/palette writable only).

So any RAM address either hart can reach resolves to the **same** `MemorySegment`, through two
different front-end wrappers.

## Concrete gaps found (as of the snapshot above)

- **Neither hart sets a distinct `hartId`.** `ApplicationProcessor` and `IOProcessor` both
  construct `new RV32IMAState()` (default `hartId == 0`) and `new RV32IMACore()` (base
  `IsaConfig.RV32IMA_ZICSR` — no `C`/`F`/`Zba`/`Zbb`/`Zabha`, but base RV32IMA already includes
  full-word AMO/LR/SC). A one-line fix in each constructor, and a prerequisite for anything
  hart-identity-keyed.
- **No context-bearing `MemoryBus` overrides anywhere in the chain.** `ApMemoryBus`,
  `SystemRAMBus`, and IOP's `BootBus` all implement only the six legacy no-`AccessContext`
  methods. This compiles and runs today because `MemoryBus`'s context-aware methods default to
  delegating to the no-context ones — but it means `AccessContext` (`hartId`/`AccessKind`/
  `atomicOp`) never reaches the bus layer, and `atomicRmw`/`tryScAndStore` both fall to their
  single-hart-only default implementations.
- **Plain (non-atomic) shared-RAM loads/stores have no Java Memory Model cross-thread
  guarantee either.** `SystemRAMBus` uses `MemorySegment.get/set` with `ValueLayout.
  JAVA_INT_UNALIGNED` etc. — no `volatile`, no `VarHandle` access mode, no lock. This is a
  strictly bigger gap than "AMOs aren't atomic": today, nothing guarantees one hart's plain
  write ever becomes visible to the other, in any order. By contrast, `SystemControlHook`
  (the existing AP/IOP control-plane state — `apRunning`, `debugStatus`, `apRamBase`,
  `apRamLimit`) already correctly uses `volatile` fields — so the team already reaches for the
  right tool for small control state, it just hasn't been applied to the bulk-RAM path.
- **This is anticipated, not accidental.** The emulator repo's own `FEATURE_REQUEST.md` (the
  document that became this repo's `docs/FEATURE_REQUEST_PLAN.md`) says the initial mailbox can
  be "synchronized MMIO," but explicitly that "shared-memory IPC and lock-free queues need
  correct cross-hart atomics" — i.e., today's gap is a known, planned-for next step, not a
  surprise.

## What's already right, and doesn't need re-deriving

- The `AccessContext`/`atomicRmw`/`tryScAndStore` *contract* on this repo's side is complete
  (Phase 1–5, `docs/FEATURE_REQUEST_PLAN.md`) — nothing further is needed here for the emulator
  to consume it. See `docs/API.md` for the current surface.
- `RV32IMACore.injectInterrupt(RV32IMAState, int bit)` already exists — this is the building block
  for AP-to-IOP trap notification. **Correction (2026-09-16, per V-32's `CPU_INTEGRATION_REVIEW.md`):
  it is *not* inherently thread-safe.** It does a plain read-modify-write of `mip` and
  `extraflags`, the same fields `step()` mutates, and its Javadoc requires the caller to hold
  whatever synchronization guards the target `RV32IMAState`. An earlier draft of this note said
  "safe to call across threads"; that was wrong. The safe pattern is for the sender to publish
  pending state through its own synchronized/volatile device state and for the *target hart's
  owner thread* to call `injectInterrupt` between its own `step` calls. Notification is a *separate* concern from the memory-bus work (a trap enters
  M-mode on whichever hart took it; the IOP needs its own device/convention to learn about an AP
  trap it cares about). Not a `MemoryBus` design question.
- A detailed, actionable design writeup for the multi-hart bus itself — recommending one new bus
  class, one coarse lock covering *all* shared-RAM traffic (not just atomics, which also
  sidesteps `AccessContext`'s documented `aq`/`rl` gap entirely), and a reservation table sized
  for exactly two known harts rather than a general N-hart structure — was handed off directly
  to the emulator repo as `MULTI_HART_BUS_NOTES.md` (2026-09-13, **not committed there** — it's
  a working note for whoever picks up that task, not this repo's concern to maintain). Don't
  re-derive that design from scratch here; if it's needed again, check whether that file (or
  whatever it turned into) still exists there first.

## What this doesn't cover

This note is scoped to what was read directly (`ApplicationProcessor.java`, `IOProcessor.java`,
`ApMemoryBus.java`, `SystemRAMBus.java`, `EmulatorMachine.java`, `Driver.java`,
`SystemControlHook.java`, `FEATURE_REQUEST.md`, `CHECKPOINT.md`, `git log`) for the specific
question of multi-hart memory-bus safety. It is not a general survey of the emulator repo (video
pipeline, cartridge format, input handling, etc. were not examined) and should not be treated as
one.
