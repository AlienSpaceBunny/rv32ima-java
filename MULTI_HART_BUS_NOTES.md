# Multi-hart `MemoryBus` — design notes for this repo

> Review status (2026-09-15): retain this as the original handoff, not an
> approved implementation plan. [CPU_INTEGRATION_REVIEW.md](CPU_INTEGRATION_REVIEW.md)
> records verified processor defects and corrections to context forwarding,
> overlapping-write invalidation, interrupt thread safety, and ordering claims.
> The local dependency is now 0.1.3-SNAPSHOT. Processor follow-up is pending.

Written 2026-09-13, from the `rv32ima-java` side, after finishing that repo's Phase 1–5
staging plan (`docs/FEATURE_REQUEST_PLAN.md` there) and reading this repo's current
`ApplicationProcessor`/`IOProcessor`/`ApMemoryBus`/`SystemRAMBus`/`EmulatorMachine` to ground
this in what's actually here rather than in the abstract. **Not committed** — this is a
handoff note, drop it or fold it into `FEATURE_REQUEST_PLAN.md`/a new checkpoint as you see fit.

## TL;DR

`rv32ima-java` now fully supplies the *contract* (`IsaConfig`, `hartId`, `AccessContext`,
`atomicRmw`/`tryScAndStore` default methods, `injectInterrupt`) but deliberately ships no
concrete multi-hart-safe `MemoryBus`. This repo doesn't have one yet either — `ApMemoryBus`
and `SystemRAMBus` only implement the legacy no-context `MemoryBus` methods, both harts default
to `hartId == 0`, and `SystemRAMBus`'s backing `MemorySegment` accesses have no cross-thread
memory-model guarantee. **This isn't a future concern: `Driver.java` already starts `ap` and
`iop` as two real OS threads that both reach the same `SystemRAMBus` instance, and both cores
run the base RV32IMA config, which already includes AMO/LR/SC** — so this is live infrastructure
risk today, independent of whether any current guest program actually exercises it.

## What `rv32ima-java` gives you (as of `0.1.2-SNAPSHOT`, unreleased/unstable)

- `IsaConfig` is a record: `IsaConfig(hasC, hasF, hasZba, hasZbb, hasZabha, hasD, hasU)`, plus a
  5-arg compatibility constructor defaulting `hasD`/`hasU` to `false`. `RV32IMACore()` (what
  both `ApplicationProcessor` and `IOProcessor` currently call) is `IsaConfig.RV32IMA_ZICSR`.
- `RV32IMAState.hartId` (`int`, default `0`). Not read internally by the core except as the
  value threaded into `AccessContext`; it's purely an identity tag for the bus/embedder.
- `AccessContext(int hartId, int privilege, AccessKind kind, int width, int atomicOp)`, passed
  to context-bearing overloads of every `MemoryBus` read/write method. `AccessKind` is
  `FETCH`/`LOAD`/`STORE`/`AMO`. `atomicOp` is the `funct5` encoding when `kind == AMO` (LR.W is
  `2`), `0` otherwise. **`AccessContext`'s own Javadoc explicitly disclaims `aq`/`rl` — it says a
  bus relying on it for ordering "must independently document and verify" that ordering.** See
  the locking recommendation below for how to sidestep that gap entirely rather than solve it.
- `MemoryBus.atomicRmw(address, funct5, operand, ctx)`: default implementation does a plain
  read-compute-write as two separate calls — explicitly documented as **not atomic against a
  concurrent hart**, even at the given width (Zabha byte/halfword AMOs use `ctx.width()`).
- `MemoryBus.tryScAndStore(hartId, address, value, ctx)`: called only after `RV32IMACore`'s own
  local fast-path check (`state.reservationValid` + address match) passes; the bus gets the
  final say. Default implementation unconditionally writes and reports success — correct only
  because there's implicitly one hart.
- `RV32IMACore.injectInterrupt(state, bit)`: sets a `mip` bit on a *different* hart's
  `RV32IMAState` and clears its WFI stall — this is how one hart wakes another for MSIP/MEIP.
  Not a `MemoryBus` concern, but relevant to AP-to-IOP trap notification (see the end of this
  note).
- No `ReservationTable` class ships — it's a documented contract (what a multi-hart bus's
  private tracking must do), not a shipped type. Building it is squarely this repo's job.

## What this repo has today (as of `3c3bdd9`, "Bump rv32 core version")

Traced through `EmulatorMachine`'s constructor, `ApplicationProcessor`, `IOProcessor`,
`ApMemoryBus`, `SystemRAMBus`, and `Driver`:

- **Both harts share one `SystemRAMBus` instance.** `EmulatorMachine` builds one
  `ramBus = new SystemRAMBus(ramSegment)`, then passes it to `ApMemoryBus` (as `systemRam`, AP's
  translated-window target) *and* wraps it into `mmioBus = new MMIOBus(ramBus)`, which
  `IOProcessor`'s private `BootBus` inner class falls through to for any address outside the
  IOP's small boot window. Any RAM address either hart can reach ultimately resolves to the same
  `MemorySegment`.
- **Both harts already run on real, already-started OS threads.** `Driver.java`:
  `new Thread(machine.applicationProcessor(), "ap")` and `new Thread(machine.ioProcessor(),
  "iop")`, both started. This is genuine concurrency today, not a future design point.
- **Both harts default to `hartId == 0`.** Neither `ApplicationProcessor` nor `IOProcessor` sets
  `state.hartId` after `new RV32IMAState()`. Any bus logic keyed by hart identity (reservation
  ownership, diagnostics, future per-hart locking) is broken until this is fixed — it's a
  one-line change in each constructor, but it's a prerequisite for everything else here.
- **`ApMemoryBus` and `SystemRAMBus` only implement the six legacy no-`AccessContext` methods.**
  Neither overrides the context-bearing overloads, `atomicRmw`, or `tryScAndStore`. Since
  `MemoryBus`'s context-aware defaults just delegate to the no-context versions, this compiles
  and runs fine today — but it means `hartId`/`AccessKind`/`atomicOp` metadata never reaches the
  bus layer, and any guest `AMO`/`LR.W`/`SC.W` from either hart falls through to the single-hart
  defaults described above, racing against the other hart's plain loads/stores or its own AMOs
  on the same address.
- **Plain (non-atomic) loads/stores also have no cross-thread visibility guarantee.**
  `SystemRAMBus` uses `MemorySegment.get/set` with `ValueLayout.JAVA_INT_UNALIGNED` etc. — no
  `VarHandle` access mode, no `volatile`, no lock. This is a strictly bigger gap than "AMOs
  aren't atomic": even two ordinary, non-conflicting-looking stores from different harts have no
  Java Memory Model ordering between them today. (For contrast: `SystemControlHook` already gets
  this right — `apRunning`/`debugStatus`/`apRamBase`/`apRamLimit` are all `volatile`. The bulk-RAM
  path just hasn't had the same treatment applied yet.)
- **This matters for the stated goal, not just hypothetically.** `FEATURE_REQUEST.md` (this
  repo's own file, the one that became `rv32ima-java`'s `FEATURE_REQUEST_PLAN.md`) says the
  interim mailbox can be "synchronized MMIO," but that "shared-memory IPC and lock-free queues
  need correct cross-hart atomics" — i.e., this is explicitly the intended next capability, not
  a maybe.

## Recommendation: one new class, one coarse lock, sized for exactly two harts

You don't need the general N-hart machinery `rv32ima-java`'s docs describe in the abstract —
this system has exactly two harts (AP, IOP), a known fixed topology, and (per `Application-
Processor`'s own comment) an *emulated* 40 MHz AP clock running through a Java interpreter loop
— several orders of magnitude below any rate where a single coarse lock would be a bottleneck.
Don't build a striped/per-page lock table; it's solving a scaling problem you don't have.

**Shape**: a new class — call it `ConcurrentSystemRAMBus` or similar — that replaces
`SystemRAMBus` as what `ApMemoryBus` and the IOP's `BootBus` both ultimately route RAM accesses
through. It:

1. Implements the **context-bearing** `MemoryBus` overloads (`readInt(addr, ctx)` etc.), not
   just the legacy ones — `ApMemoryBus` and `BootBus` need to start forwarding `AccessContext`
   through instead of silently dropping to the no-context defaults. (`ApMemoryBus.readInt(int)`
   etc. would need a context-bearing sibling that actually gets called — right now
   `RV32IMACore` calls the context-aware methods on whatever `MemoryBus` it's holding, so once
   `ApMemoryBus` overrides those instead of only the legacy six, the context flows through
   automatically. No `RV32IMACore`-side change needed.)
2. Guards **every** access — plain loads/stores as well as `atomicRmw`/`tryScAndStore` — with
   one `synchronized` block (or one `ReentrantLock`) over the whole segment. This is the
   simplifying move: a single lock covering *all* shared-RAM traffic gives you ordinary Java
   monitor happens-before semantics for everything, which is strictly stronger than "AMOs are
   atomic" — it sidesteps `AccessContext`'s documented `aq`/`rl` gap entirely, because you're not
   relying on per-access acquire/release bits at all, just total ordering through the one lock.
   Trying to be clever with `VarHandle` volatile/acquire-release access modes for the *plain*
   load/store path while reserving a separate lock only for AMOs would technically also work,
   but mixing two synchronization strategies over the same memory is exactly the kind of thing
   that's easy to get subtly wrong; start with the one coarse lock and only split it if a
   profiler says so.
3. Owns a tiny reservation table sized for exactly two harts — e.g. two `long` fields (or a
   2-element array) holding `(hartId, reservedAddress, valid)`, mutated only inside the same
   lock as everything else. `LR.W` (`readInt` override, `ctx.kind() == AMO && ctx.atomicOp() ==
   2`) records `(ctx.hartId(), address)`; `tryScAndStore` checks the entry for `hartId`, performs
   the write and clears it on success, clears it unconditionally otherwise; **any** plain store
   or `atomicRmw` to an address matching *either* hart's outstanding reservation invalidates it,
   even if that hart isn't the one doing the writing. (Granule size: matching exact address is
   fine to start — RISC-V permits a coarser reservation granule but never requires one finer than
   the access itself, and there is no byte/halfword `LR`/`SC` to worry about aligning here.)

**Before this bus work matters at all**: fix `ApplicationProcessor`'s and `IOProcessor`'s
`RV32IMAState` construction to assign distinct `hartId`s (e.g. AP `0`, IOP `1`, or whatever
convention you'd like — `rv32ima-java` doesn't care which numbers, only that they're distinct
and stable). Nothing above works without that.

## Testing this will actually need concurrency tests, not just unit tests

`rv32ima-java`'s own `AtomicPrimitivesTest` proves the *core-side routing contract*
(`RV32IMACore` calls `atomicRmw`/`tryScAndStore` with the right arguments in the right cases)
against a single-threaded recording mock — it cannot and does not prove real concurrent
correctness, because the default bus implementations it's testing against are, by definition,
only correct for one hart. Whatever you build here needs its own tests that actually race two
threads against the same bus instance: e.g., N iterations of `AMOADD` from each of two threads
on the same address, asserting the final value is exactly `initial + N*delta*2` (a classic
race-detecting test — a single missing lock will make this test flaky, not reliably fail, so run
it with a healthy iteration count and consider running it a few times in CI); an LR/SC
producer/consumer pair proving a `SC.W` correctly fails when the other hart's plain store or AMO
landed between this hart's `LR.W` and `SC.W`; and an invalidation-across-harts test (hart A's
`LR.W`, hart B writes the same address, hart A's `SC.W` must report failure).

## Out of scope for these notes: AP-to-IOP trap notification

Flagged in `rv32ima-java`'s docs as belonging entirely to this repo, and it's not a
`MemoryBus`/bus-locking concern at all — it's about a trap on the AP hart (which enters M-mode
*on the AP*, per the RISC-V spec) needing to somehow signal the IOP thread. The building block
that exists for this is `RV32IMACore.injectInterrupt(RV32IMAState, int bit)` (settable across
threads, since it just sets a `mip` bit and clears `EXTRAFLAG_WFI` — no bus involved), but
*deciding* when an AP trap should raise an IOP-visible interrupt, and building whatever device
or convention carries that signal, is a separate design task from what's in this note.
