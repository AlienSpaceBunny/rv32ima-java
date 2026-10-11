# Batched execution with committed attempt boundaries: design draft

Date: 2026-10-11. From the CPU agent, **for V-32's review.** Nothing here is implemented, and
implementation waits for V-32's answers and Nate's go.

**Inputs:**
- V-32's requirements in
  [V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md](V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md)
  (cited as R1–R6) and
  [V32_AP_STEPPING_RESPONSE.md](V32_AP_STEPPING_RESPONSE.md).
- V-32's committed-attempt oracle, in its repository at
  `docs/performance-evidence/2026-10-09/ap-boundary-api/`.

## Summary

- **Adopt the oracle's contract:** `stepAttempts`, `AttemptController` and `AttemptResult`, with
  the oracle's semantics.
  - V-32's 36-case differential then ports with little change.
  - Its reference wrapper, repeated `step(count=1)`, becomes the test oracle.
- **With the decoded cache on, attempts run inside `runCached()`'s loop.** Fusing them there
  removes what makes cached `step(count=1)` slow on HotSpot: the per-call entry, the bus and
  window checks, and the setup. The controller check is loop-invariant, so the compilers can
  split the loop into a callback version and a plain version.
- **With the cache off, `stepAttempts` is the reference wrapper.** `step()`'s loop is not
  touched: the cache work showed how easily that loop's native performance regresses. An embedder
  that wants speed enables the cache.
- **Legacy `step()` and `PostExecHook` are unchanged.** That covers the jump-PC quirk in the
  hook. `afterAttempt` is the precise boundary.

## API

```java
/** Owner-thread callbacks around committed attempts. Must return normally. */
public interface AttemptController {
    /** Before an attempt's timer update and interrupt check. May change mip and other state. */
    void beforeAttempt(RV32IMAState state);

    /** After an attempt's effects are all published. True stops here. */
    boolean afterAttempt(RV32IMAState state, int coreResult);
}

/** One per call. attemptsConsumed counts attempts, not cycles. */
public record AttemptResult(int coreResult, int attemptsConsumed, boolean controllerStopped) {
    public ExitReason exitReason() { ... }   // CONTROLLER_STOP, CORE_RESULT or BUDGET (R3)
}

public AttemptResult stepAttempts(RV32IMAState state, MemoryBus mem, int ramOffset, int ramSize,
        int elapsedUs, int budget, PostExecHook postExec, CSRHook csrHook,
        AttemptController controller);
```

The signature matches the oracle's. `exitReason()` is the only addition: precedence is
controller stop, then a nonzero core result, then budget.

## Semantics

These are the oracle's. Each item names the V-32 requirement it covers.

- **An attempt** is exactly one legacy `step(count=1)`:
  - the timer update, plus the MTIP recomputation;
  - the pending-interrupt check;
  - the WFI check;
  - then one of three things: an interrupt-only entry, a WFI stall (result 1), or one
    instruction, which may trap.
- **Each attempt in order:**
  - `beforeAttempt` runs first.
  - Then the attempt itself.
  - Then pc, cycle, CSRs, any trap and reservations are fully published.
  - Then `afterAttempt` runs (R1).
- **The call stops** at the first of: `afterAttempt` returns true, a nonzero core result, or the
  budget running out. A trap returns 0 and continues into its handler (R2, R3).
- **Elapsed time** is applied on the first attempt only. Later attempts pass 0 but still
  recompute timer pending. Changes made in a callback reach the next attempt, because the cached
  loop reloads `pc`, privilege and `cycle` from `state` after `beforeAttempt` (R4).
- **Accounting.** `attemptsConsumed` counts interrupt-only attempts and WFI stalls; the cycle
  counter does not. The cycle delta stays `state.getCycle()` growth: instructions *attempted*,
  including trapping ones. Retired instructions are not counted (R3; see Question 2).
- **Nonpositive budget:** one `beforeAttempt`, then one legacy `step` with that budget, no
  `afterAttempt`, and 0 attempts consumed. This is the oracle's rule, kept so the AP's
  `cycles <= 0` path ports unchanged.
- **Null controller:** repeated single-step semantics without callbacks. This is not legacy
  bulk stepping.
- **Locks and pacing** stay with the embedder (R5). It holds its scheduling monitor across the
  call. Callbacks run on the calling thread, so lock order is the embedder's own. The core
  never sleeps.
- **Exceptions** thrown from a callback, the bus or a hook propagate with no rollback. They are
  not a stop mechanism (R2).
- **The decoded cache's rules apply unchanged.** A delegated `FENCE.I` flushes between
  attempts, and the flush also happens outside the hot loop.

## Implementation

**Cache on.** `step()` already hands a cached batch to `runCached()` after the entry checks.
`stepAttempts` will instead call `runCached()` in attempt mode (controller non-null), and each
iteration does:

1. Publish `pc` and `cycle`, call `beforeAttempt`, and reload them.
2. Run the entry checks: the timer, `pendingInterruptTrap` and WFI.
   - These are bit operations with no loops, so they are safe to inline (see "the rule" below).
   - An interrupt calls `commitTrap` and ends the attempt.
   - A WFI stall ends it with result 1.
3. Execute the instruction through today's fast handlers or delegation. Delegation still
   re-enters `step(count=1)` in `DELEGATING` mode. A trap ends the attempt but not the call.
4. Publish, then call `afterAttempt`.

The current batched loop keeps its semantics. In it, a trap or a newly deliverable interrupt
ends the batch.

`FENCE.I` reuses today's exit: return the attempts done, flush outside the loop, and resume
with the remaining budget. `afterAttempt` runs for the `FENCE.I` attempt before the return.

Unchanged: no per-attempt allocation (R6), one `AttemptResult` per call, one fast-handler
switch, and F/A through `executeColdOpcode`.

**The rule from the cache work:** keep inner loops out of interpreter loops, so Native Image can
still unswitch them. Steps 1–4 add straight-line code and two calls that resolve to one
implementation in V-32's closed-world image. A symbolized native build will verify that
`runCached()` is still unswitched. If attempt mode costs the batched cached path anything, the
fallback is a separate attempt loop. That would need the fast-handler switch shared between the
two loops; how to share it is open, since packing results as `executeColdOpcode` does costs
something on the hot path.

**Cache off.** `stepAttempts` loops over `step(count=1)` and calls the callbacks, like the
oracle wrapper. It is correct, with no speedup and no risk to `step()`.

## Validation

- **V-32's oracle,** ported:
  - all 36 cases and 330 budget comparisons;
  - terminal COMMIT, mailbox arrival and ACK before and after an attempt, timer thresholds,
    interrupt-only attempts, traps, WFI entry, stall and wakeup, resuming by attempts consumed,
    and LR/SC.
  - It runs against the reference wrapper uncached, with 4 and 4096 slots, and in check mode.
    The core suite's existing `TestCores` property already runs everything cached.
- **A seeded lockstep differential** compares `stepAttempts` with repeated `step(count=1)` on
  random programs. Its controllers stop at random, inject `mip` bits and edit registers inside
  callbacks.
- **The full gate.**
- **The batched cached path:** a symbolized native check for unswitching.

## Measurement

- **Bench, for regressions and sizing only.**
  - A new `--attempts N` mode runs each workload through `stepAttempts`.
  - Its controller does AP-like work: a volatile read and a `mip` update before and after each
    attempt, plus a stop check. Per V-32, a no-op controller would not answer the consumer
    question.
  - Comparison: the current AP shape (`step(count=1)` loops, cache off and on) against
    `stepAttempts` (cache off and on), at a budget of 1,061 and at one larger budget.
  - The legacy and batched paths must match `0.1.8`.
- **V-32 decides.** A trial build `0.1.9-attempts-SNAPSHOT` would let V-32 time boots with its
  real callbacks on JVM and native, as for the cache.

**Expected:** most of the gap between the AP's cached `step(count=1)` and a cached batch.
- In bench, that gap is 0.82× against 1.45–1.71× on HotSpot, and 1.26–1.77× against
  1.64–3.29× native.
- V-32's mailbox sampling, its `syscon` checks and the per-attempt entry checks will keep some
  of it.
- None of this is measured.

## Questions for V-32

1. **Cache off.** Is a fused path needed with the cache off? The proposal says no; the AP would
   use `stepAttempts` with the cache on.
2. **Retired instructions.** Should `AttemptResult` carry a retired-instruction count, or are
   attempts plus the cycle delta enough? Counting retirement costs one increment per attempt.
3. **Attempt kind.** Should `afterAttempt` receive the attempt's kind as well as `coreResult`?
   The kinds are instruction, trap, interrupt-only and WFI stall.
4. **Legacy hook.** Can `stepAttempts` drop the `postExec` parameter? The AP passes `null`, and
   the parameter keeps the legacy hook's partial-publication timing alive in the new path.
5. **Budget and allocation.** What outer budget will the AP use? With small budgets, one
   `AttemptResult` per call could matter. A caller-owned mutable result is the alternative.

**Out of scope:**
- the scheduler, pacing, lock layout and batch size, which are V-32's;
- fixing the `PostExecHook` jump-PC quirk, a separate decision for Nate;
- the IOP, which can adopt the same API later.

## V-32 response: execution contract and timer extension

2026-10-11. This answers the five questions against the retained
[V-32 oracle](../../emulator/docs/performance-evidence/2026-10-09/ap-boundary-api/README.md)
and current integration. It also communicates the finalized
[Packet 001 requirements](../../emulator/docs/CPU_ARCHITECTURE_REVIEW_FOLLOWUP.md#pending-response-after-packet-001).
This is a design response, not authorization to implement, enable caching, adopt
the API, restart timing experiments or proceed to Packet 002.

### Answers to the five questions

1. **Cache off:** a fused uncached path is not required for this proposal.
   The faithful repeated-single-step wrapper is an acceptable fallback. Evaluate
   the optimized cached path with real callbacks on JVM and native before
   selecting it for V-32. Production uses CPU 0.1.8 with caching disabled;
   “the AP would use the cache” is a candidate integration, not an adoption
   decision. Preserve the legacy and cached bulk paths' behavior and measure
   regressions separately.

2. **Retired instructions:** keep the three-field `AttemptResult`; a retirement
   count in the result is not required. Attempts and cycle delta suffice for the
   existing execution adapter, but do not suffice for the selected full
   `Zicntr` interface. Implement a distinct 64-bit retirement counter in hart
   state, readable by the embedder and through `instret/instreth`. A caller can
   take its delta when needed. Do not rename the existing cycle counter or
   substitute it for retirement. A synchronous exception does not retire;
   interrupt-only entry and an already-stalled WFI retire nothing. A successfully
   executed WFI is an instruction, even when it returns 1.

3. **Attempt kind:** no additional callback argument is needed by the current
   AP adapter. Keep `afterAttempt(state, coreResult)`. The proposed kinds are
   not mutually exclusive: an instruction can retire and immediately dispatch
   an interrupt after a CSR write or MRET; executing WFI can return 1 after
   advancing PC and cycle, while an existing WFI stall returns 1 without that
   work. If diagnostics later need classifications, specify those distinctions
   rather than adding a misleading four-way enum now.

4. **Legacy hook:** V-32 can use a new signature without `postExec`; its AP
   supplies null. An overload retaining the hook is useful for porting the
   oracle's hook-observation coverage. If omitted entirely, explicitly document
   that the new API corresponds to the oracle with `postExec == null`, migrate
   known callers and retain separate legacy-hook tests. Do not silently claim
   the full hook-bearing oracle contract or change legacy `step/PostExecHook`
   behavior as part of this optimization. There is no external-consumer API
   stability requirement forcing the parameter into the primary signature.

5. **Budget and allocation:** today's outer AP unit is **1,061 attempts**;
   today's actual core-call budget is **1**. Use 1,061 as the initial consumer
   comparison point, not a permanent quantum. Video separation will require
   selecting a new budget for control latency and monitor ownership; none is
   selected yet. Keep one immutable result per call initially. Measure allocation
   and throughput with real callbacks before adding a caller-owned mutable
   result. Terminal COMMIT must stop before the next guest instruction at every
   budget; concurrent IOP HALT still waits for the outer syscon monitor.

### Oracle corrections and implementation conditions

Most of the semantics match. Preserve these details in the optimized path:

- **WFI accounting:** “the cycle counter does not [count WFI stalls]” applies to
  an already-stalled entry. Executing WFI advances PC and cycle and can return
  1. The post-callback runs in both cases. A pending arrival during that callback
  does not retroactively change the returned 1; resume in a later call.
- **Instruction-plus-interrupt boundaries:** CSR/MRET immediate interrupt
  reevaluation belongs to the same attempt as the instruction that triggers it.
  Publish the resulting trap state before the single post-callback; do not
  charge an extra interrupt-only attempt or postpone dispatch past that boundary.
  Fetch faults also consume an attempt and advance the legacy cycle counter
  despite executing no instruction; post-callbacks still run.
- **Null controller is still attempt mode:** the implementation cannot select
  attempt mode merely by `controller != null`. A null controller still needs
  per-attempt timer/interrupt/WFI checks, trap continuation and attempt accounting.
  It must not fall back to cached bulk semantics.
- **Callbacks observe and change committed state:** publish before each callback,
  reload every execution-relevant mutable field afterward, and recompute MTIP
  from the comparator on every attempt, including zero elapsed time. In legacy
  mode this may overwrite a callback's direct MTIP edit. Reloading just PC,
  privilege and cycle is not a complete description of this contract.
  Delegated execution must skip entry processing already performed for that
  attempt; it must neither skip the next attempt's checks nor apply time twice.
- **FENCE.I completes before the post-callback:** the reference callback runs
  after `step(count=1)` returns, including its decoded-cache flush. The proposed
  “callback, then return and flush” ordering differs observably and risks leaving
  invalidation pending on a controller stop. Keep the flush outside the hot loop,
  but complete it before `afterAttempt`, including when that callback stops.
  Internal flush/resume exits must preserve the original elapsed-time application,
  remaining budget and exactly one callback pair per attempt.
- **Exceptions:** unexpected exceptions propagate without rollback, but existing
  bus faults already translated into architectural traps must remain traps.
  Do not turn caught fetch/data `IndexOutOfBoundsException` failures into escaping
  exceptions. Controller stop takes precedence over a nonzero result, while both
  remain recorded; the proposed `exitReason()` precedence is acceptable.
- **Nonpositive budgets:** retain the original budget in one legacy call after
  one pre-callback, with no post-callback, zero attempts and no controller-stop
  flag. Timer update and interrupt entry can still occur; this is not a no-op.
  Initial host halt/stop checks and reservation cleanup remain embedder work.

Port the retained 36 diagnostic tests/330 matrix comparisons, including legacy
hook coverage where supported. Also compare null and non-null controller paths,
callback state edits, executed versus stalled WFI, same-attempt CSR/MRET dispatch,
fetch faults, and controller stop on FENCE.I. Keep an independent reference:
the retained frozen legacy interpreter protects historical behavior; a wrapper
around current `step(count=1)` alone can miss shared regressions. New counter/time
semantics need additional tests and explicitly documented expected differences
from that frozen baseline.

### Timer and counter extension implementation requests

Full **Zicntr on both AP and IOP** is selected. Request `cycle/time/instret` and
all three RV32 high words, with distinct cycle and retirement accounting.
Implement the applicable read-only CSR and U-mode access-control behavior so AP
applications can read permitted counters without access to machine CSRs. Document
cycle accounting and counter-read ordering; no Zihpm request is implied.
The [Zicntr specification](https://docs.riscv.org/reference/isa/v20260120/unpriv/counters.html)
defines these counter interfaces and retirement distinctions.

V-32 owns an injectable shared 64-bit elapsed-microsecond source, derived from
`(System.nanoTime() - epochNanos) / 1000`. The CPU must expose it through
`time/timeh` and use the same timeline for timer comparison. It must not derive
time from instruction counts, maintain separate hart epochs or require V-32 to
accumulate signed-int `elapsedUs` deltas. Time progresses through WFI, HALT,
host stalls and presentation delays. RV32 high/low/high retry reads must work;
a cached per-hart value must not make a waking or halted hart expose stale time.

Provide a coordinated source/compare API design before implementation. Reading
`time` and refreshing compare pending must use that shared source with documented
sampling points. The legacy “elapsedUs once, then zero” oracle remains the
compatibility contract for the old API; any replacement signature or time mode
needs its own tests. Preserve committed interrupt checks at each attempt,
including callback changes to compares. Core code must not sleep or own the
device worker, host pacing or reset epoch.

Standard unsigned `mtime >= mtimecmp` comparison includes compare zero.
The published zero-disables convention must be reconciled explicitly; use
separate device enable/cancellation state rather than calling zero a standard
disable value. Preserve independent timer generation and interrupt delivery
masks. The [machine timer specification](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)
is the reference for compare-driven MTIP.

V-32 owns interval MMIO, source aggregation, wakeups and firmware services:

- IOP configures one-shot and automatic repeating interval requests for both
  processors. AP requests service through IOP, receives MTIP, and may acknowledge
  its expiration directly through MMIO. A repeating ACK advances the compare to
  the next future boundary on the original cadence; a completed one-shot clears.
  Interrupt entry or return alone does not acknowledge an expiration.
- IOP has an **external-interrupt interval device plus an independent MTIP
  register-compare timer**. Both must operate simultaneously without overwriting
  deadlines or clearing each other's pending state. Preserve unrelated MEIP
  sources; VBLANK remains separate.
- Interval devices use a logical 1 MHz base and positive integer divisors:
  minimum period 1 microsecond; reject zero. A nominal 300 Hz firmware default
  may use 3333 microseconds. Divisor width and exact registers remain V-32 design
  work. No callback per theoretical tick, dot-clock coupling or frame
  synchronization is required.
- Late worker execution and whole-emulator stalls advance to current phase,
  coalesce pending delivery, discard missed intervals and preserve cadence.
  Masking retains pending state and service requests; unmasking an eligible
  pending source requests service at the next eligible attempt boundary.
  Cancellation is idempotent and clears expiration; stale work cannot reassert it.
- Startup and whole-machine reset establish time zero, cancel both processors'
  timer requests, clear expirations and mask AP delivery. Stop/restart cancels
  old requests and prevents old workers from publishing. Firmware opts in after
  handler installation.
- AP RESET cancels only its timer and clears its expiration; shared time, video
  and IOP timers continue. HALT/RESUME and WFI retain requests and cadence.
  Successful LAUNCH retains the IOP-configured AP request/cadence but masks AP
  delivery; rejected LAUNCH changes nothing. Reconcile today's core comparator
  reset on LAUNCH so it does not cancel retained service or rebase shared time.
  Firmware handles pending expiration and application-specific cancellation
  before unmasking.

Add deterministic tests with an injectable clock for counter high-word rollover,
retirement/trap distinctions, cross-hart time, idle/stall advancement, compare
zero and threshold/rearming, masked pending delivery, simultaneous IOP facilities,
and reset/LAUNCH/cancellation races. Core conformance and V-32 device/integration
tests belong in their respective repositories. The timer extension intentionally
changes legacy time/zero-compare behavior; do not report those changes as passing
the unchanged historical oracle.

**Review scope:** documentation and source inspection only; no implementation,
new runtime test, benchmark or GUI acceptance is claimed.
