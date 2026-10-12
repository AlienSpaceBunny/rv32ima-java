# Batched execution with committed attempt boundaries: design

Date: 2026-10-12. From the CPU agent. **Revision 4.** Revision 3 answered V-32's review of
revision 2 (kept below with its earlier response). Revision 4 drops the legacy contract on Nate's
direction: the core follows what V-32 needs and, where that stays cheap to maintain, the RISC-V
specification. The oracle's semantics stay where V-32 needs them, not as historical equivalence.
Revision 3 was not reviewed. Nothing here is implemented, and implementation waits for Nate's go.
The changes in each revision are listed at the end.

**Inputs:**
- V-32's requirements in
  [V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md](V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md)
  (cited as R1–R6) and
  [V32_AP_STEPPING_RESPONSE.md](V32_AP_STEPPING_RESPONSE.md).
- V-32's committed-attempt oracle, in its repository at
  `docs/performance-evidence/2026-10-09/ap-boundary-api/`.
- V-32's response to the first draft and its review of revision 2, below.

The timer and counter extension V-32 requested in the same response is designed separately in
[TIMER_COUNTER_DESIGN.md](TIMER_COUNTER_DESIGN.md). **It lands first:** it removes `elapsedUs`,
replaces the time model and fixes `step()`'s spec deviations, and this design builds on that.

## Summary

- **Adopt the oracle's contract with `postExec == null`:** `stepAttempts`, `AttemptController`
  and `AttemptResult`, with the oracle's semantics where V-32 needs them and the timer design's
  time and WFI rules in place of the old ones. The public method has no `PostExecHook` parameter;
  a package-private overload keeps the oracle's hook coverage portable.
- **Attempt mode is a mode, not a non-null controller.** A null controller still gets
  per-attempt checks, trap continuation and accounting.
- **With the decoded cache on, attempts run inside `runCached()`'s loop.** The hypothesis is that
  fusing them there cuts most of the per-call cost of cached `step(count=1)` on HotSpot: the
  entry, the bus and window checks, and the setup. Measurement decides; see below.
- **With the cache off, `stepAttempts` is the reference wrapper.** `step()`'s loop is not changed
  by this design.
- **The `PostExecHook` jump-PC quirk is fixed, separately.** For a taken jump or branch, the hook
  will report the instruction's own PC. Nothing keeps the old value.
- **This is a candidate for V-32, not an adoption.** V-32 production runs `0.1.8` with the cache
  off. V-32 decides after timing the cached path with its real callbacks on JVM and native.

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
        int budget, CSRHook csrHook, AttemptController controller);
```

- **No `postExec`.** The Javadoc states that this is the oracle with `postExec == null`. A
  package-private overload with the oracle's full signature exists only for the ported hook tests;
  no embedder calls it.
- **`exitReason()`** is the only addition to the oracle. Precedence: controller stop, then a
  nonzero core result, then budget. Both a stop and a nonzero result stay recorded.
- **No retirement count and no attempt kind.** Retired instructions become a hart counter
  (`instret`, in the timer design); callers take its delta.
- **No `elapsedUs`.** Time comes from the core's shared source, sampled once per call (see the
  timer design).
- **The budget must be positive.** A nonpositive budget throws `IllegalArgumentException` before
  any callback runs or any state changes, so a rejected call cannot have consumed a device command
  in `beforeAttempt`. The oracle's nonpositive-budget behavior (one callback, then a step with the
  original budget) is not kept; Question 2 asks whether V-32 needs it.
- **One immutable `AttemptResult` per call.** A caller-owned mutable result waits until
  allocation is measured with real callbacks.

## Semantics

These are the oracle's, with V-32's clarifications and the timer design's time and WFI rules.
Each item names the requirement it covers.

- **An attempt** is exactly one `step(count=1)`: MTIP recomputation from the latest time sample,
  the interrupt check, the WFI check, then one of:
  - **an interrupt-only entry.** The cycle counter does not advance.
  - **a stall in an existing WFI:** result 1, with pc and cycle unchanged.
  - **one instruction, which may trap.** An executed WFI is an instruction: pc and cycle advance,
    and the result may be 1. If the instruction writes `mstatus`, `mie` or `mip`, or is MRET, an
    interrupt it makes deliverable is dispatched in the same attempt, with no extra attempt
    charged.
  - **a fetch fault.** It consumes an attempt and advances the cycle counter, although no
    instruction executes.
- **Each attempt in order (R1):**
  1. `beforeAttempt`.
  2. MTIP recomputation from the compare and the latest sample, on every attempt, so a callback's
     compare change takes effect and a direct MTIP edit is overwritten. The first attempt's sample
     is taken here.
  3. The interrupt and WFI checks, then execution.
  4. Full publication: pc, cycle, CSRs, any trap, reservations and a `FENCE.I`'s cache flush.
  5. `afterAttempt`. An arrival during it does not change a returned 1; it takes effect in a
     later call.
- **The call stops** at the first of: `afterAttempt` returns true, a nonzero core result, or the
  budget running out. A trap returns 0 and continues into its handler (R2, R3).
- **Time (R4)** is sampled once per call, at the first attempt, and again only by a `time` read.
- **Accounting (R3).** `attemptsConsumed` counts every attempt. The cycle delta is instructions
  *attempted*: it includes trapping instructions and fetch faults, and excludes interrupt-only
  entries and WFI stalls.
- **Host halt and stop checks** and reservation cleanup stay with the embedder.
- **Null controller:** attempt mode without callbacks. It never falls back to cached bulk
  semantics.
- **Callbacks** run on the calling thread and see fully published state.
  - `beforeAttempt` sees the state at the preceding boundary, before this attempt's timer update.
    Its changes apply to the attempt about to run.
  - `afterAttempt` sees this attempt's effects. Its changes apply to the following attempt, if the
    call continues.
  - They may call `flushDecodedCache()`. Other core reconfiguration from a callback is unsupported.
  - **No reentrancy:** a callback must not enter execution on the same core (`step`,
    `stepAttempts`). The internal delegation mode, local state and remaining budget are not a
    nested-execution protocol.
- **Locks and pacing** stay with the embedder (R5). The core never sleeps.
- **Exceptions** from a callback, the bus or a hook propagate with no rollback; they are not a
  stop mechanism (R2). A bus `IndexOutOfBoundsException` on fetch or data access is still an
  architectural access fault, as in `step()`.
- **The decoded cache's rules apply unchanged.**
- **Counters:** `instret` is published before both callbacks along with pc and cycle, and reloaded
  after them. Counter writes by the guest or by a
  callback survive the fast handlers, delegation, WFI's early return and the `FENCE.I`
  flush-and-resume. Time is sampled once per public call; internal exits and resumes do not take
  extra samples. See [TIMER_COUNTER_DESIGN.md](TIMER_COUNTER_DESIGN.md#time-sampling).

## Implementation

**Cache on.** `stepAttempts` runs `runCached()` in attempt mode, selected by an explicit flag,
including for a null controller. Each iteration:

1. Publishes `pc`, `cycle` and `instret`, calls `beforeAttempt` when there is a controller, and
   reloads them.
   The loop already reads everything else from `state` at the point of use: privilege and the
   rest of `extraflags`, the CSRs, and `regs`, which is a final array. The implementation must
   keep it that way; the lockstep test's callbacks edit each of these.
2. Runs the entry checks: the timer and MTIP, `pendingInterruptTrap` and WFI. These are bit
   operations with no loops.
3. Executes through the fast handlers or delegation. The delegated `step(count=1)` runs in
   `DELEGATING` mode, which skips the entry checks this attempt has already run. It takes no
   extra time sample, and the next attempt's checks still run. Same-attempt dispatch after a CSR
   write, MRET or a `time` read comes from `step()`'s reevaluation.
4. Publishes, then calls `afterAttempt`.

`FENCE.I` leaves the loop, as today. The driver flushes outside the loop, then calls `afterAttempt`
for that attempt, so the flush is complete before the callback even when the callback stops the
call. If it does not stop, the driver resumes with the remaining budget and no new time sample,
without a second `beforeAttempt` for that attempt.

The current batched loop keeps its semantics: a trap or a newly deliverable interrupt ends the
batch.

**The rule from the cache work:** keep inner loops out of interpreter loops, so Native Image can
still unswitch them. The attempt-mode flag is loop-invariant, and steps 1–4 add straight-line code
plus two calls. A symbolized native build checks that `runCached()` is still unswitched; that
supports the code-shape claim but says nothing about throughput, which only the bench gates
below establish. If attempt mode slows the batched cached path, the fallback is a separate
attempt loop; how it would share the fast-handler switch is open.

**Cache off.** `stepAttempts` loops over `step(count=1)` and calls the callbacks, like the oracle
wrapper. It gives no speedup and does not change `step()`'s code; the bench regression gates
below still apply.

## Validation

- **One independent reference.** A copy of V-32's frozen legacy interpreter goes into the core's
  test sources with the timer design's changes applied (and the `PostExecHook` fix), and nothing
  else. Its diff from the frozen original is the list of intentional behavior changes. It catches
  regressions that a wrapper around the current `step(count=1)` would share; the wrapper stays as a
  second reference. Directed tests with hand-computed values cover the corner cases.
- **What is compared:** every committed boundary (registers, pc, CSRs, privilege, counters),
  memory and device effects, and both reservation layers (the hart's own and the bus's), not
  just final registers and return values.
- **V-32's oracle, ported:** all 36 cases and 330 budget comparisons, including the hook
  coverage through the package-private overload. Each runs uncached, with 4 and 4096 slots, and in
  check mode. Expectations that depend on a changed behavior (time, WFI, the hook's jump PC,
  nonpositive budgets) are updated to the new behavior and listed.
- **New cases:** null versus non-null controllers, callback state edits, executed versus stalled
  WFI, same-attempt CSR and MRET dispatch, fetch faults, a controller stop on `FENCE.I` (the flush
  must already be done), nonpositive budgets rejected before any callback, and bus faults that
  stay traps.
- **A seeded lockstep differential** compares `stepAttempts` with repeated `step(count=1)` on
  random programs. Its controllers stop at random, inject `mip` bits and edit registers, privilege
  and CSRs inside callbacks.
- **The full gate,** plus a symbolized native check that the batched cached path is still
  unswitched.
- **Callback timing:** state-edit tests cover both phases: a `beforeAttempt` edit seen by the same
  attempt, and an `afterAttempt` edit seen by the next.

## Measurement

- **Bench, for regressions and sizing only.**
  - A new `--attempts N` mode runs each workload through `stepAttempts`. Its controller does
    AP-like work: a volatile read and a `mip` update before and after each attempt, plus a stop
    check.
  - Comparison: the current AP shape (`step(count=1)` loops, cache off and on) against
    `stepAttempts` (cache off and on), at a budget of 1,061. One larger budget runs as a
    diagnostic control only; it does not select a future quantum. Allocation is recorded alongside
    throughput.
  - **Regression gates:** the throughput of `step()`'s per-instruction and batched paths must not
    regress against `0.1.8` on JVM or native.
- **V-32 decides.** After separate authorization, a trial build `0.1.9-attempts-SNAPSHOT` would let
  V-32 time boots with its real callbacks on JVM and native, as for the cache. Neither the build
  nor that timing is authorized by this design.

**Hypothesis:** the fused path recovers much of the gap between the AP's cached `step(count=1)`
and a cached batch. In bench that gap is 0.82× against 1.45–1.71× on HotSpot, and 1.26–1.77×
against 1.64–3.29× native. V-32's mailbox sampling, its `syscon` checks and the per-attempt entry
checks will keep some of it. None of this is measured.

## Hook coverage

A package-private, test-only overload keeps `postExec`, as V-32 accepted. Its scope is the
oracle's hook coverage only, with the jump-PC fix; it does not take on the time or counter
guarantees. The existing hook tests and the public `step` method's documented `postExec == null`
contract stay, apart from the fix.

## V-32's integration (not core work)

V-32's real AP adapter keeps its outer `syscon` monitor, pending sampling before and after
attempts, initial halt and stop handling, and reservation cleanup on a terminal halt. It maps a
controller stop back to the AP's existing return behavior; `coreResult` does not encode that halt.
Terminal COMMIT stops after its store and pc are published; a concurrent IOP HALT still waits for
the outer monitor. This design grants no new lock layout and no one-instruction HALT latency.

## Questions for V-32 (revision 4)

1. **Remaining details.** Does revision 4 cover the details in V-32's review of revision 2? Both
   of revision 2's questions are settled by that review; the `elapsedUs` answer is superseded by
   its removal.
2. **Nonpositive budgets.** Does V-32 need the oracle's nonpositive-budget behavior, or is an
   `IllegalArgumentException` before any callback acceptable? Its AP passes positive budgets.
3. **Hook fix.** Does any V-32 code or evidence depend on the `PostExecHook` reporting the target
   minus the instruction length for a taken jump or branch?

**Out of scope:**
- the scheduler, pacing, lock layout and batch size, which are V-32's;
- the `PostExecHook` jump-PC fix itself, a separate change;
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

## CPU-agent revision 2: what changed

2026-10-11. The design above now reflects V-32's answers and corrections:

- **Answers adopted:** no fused uncached path; a three-field result with retirement moved to a
  hart counter; no attempt kind; no `postExec` in the public signature; one immutable result per
  call; 1,061 attempts as the first comparison budget.
- **Draft errors fixed:**
  - Attempt mode was keyed on `controller != null`. It is now an explicit mode, and a null
    controller keeps attempt semantics.
  - The draft reloaded only `pc`, privilege and `cycle`. The implementation section now names
    what is reloaded and what the loop reads from `state` at the point of use, and MTIP is
    recomputed on every attempt.
  - `FENCE.I` ran the callback before the flush. The flush now completes before `afterAttempt`,
    with exactly one callback pair per attempt across the resume.
- **Clarifications added:** executed versus stalled WFI, same-attempt dispatch after CSR writes
  and MRET, fetch-fault accounting, bus faults that stay traps, and nonpositive-budget details.
- **Validation:** the frozen legacy interpreter is now the independent reference, with the new
  cases V-32 listed.
- **Timer and counters:** answered separately in
  [TIMER_COUNTER_DESIGN.md](TIMER_COUNTER_DESIGN.md), which asks for V-32's review.

## V-32 review of revision 2

2026-10-11. **Revision 2 addresses the earlier execution-contract objections.**
The design is a suitable candidate once the details below are explicit; CPU
implementation and V-32 adoption still require Nate's separate decision.

### Answers to the two questions

1. **Hook overload:** yes, a package-private test-only overload is enough.
   V-32's AP supplies null and needs no public `PostExecHook` parameter. Keep
   its scope explicitly the historical oracle's hook coverage; it need not
   acquire every new source-mode/counter guarantee. Preserve legacy hook tests
   and the public method's documented `postExec == null` contract.
2. **Elapsed time:** yes, retaining `elapsedUs` and requiring zero in source
   mode is acceptable. Validate before any callback or state mutation, including
   nonpositive budgets. Rejecting invalid input after `beforeAttempt` could
   consume a queued device command before reporting caller error.

### Remaining details and integration conditions

- **Callback visibility:** “any change ... is in effect for the next attempt”
  needs phase-specific wording. A `beforeAttempt` edit affects the upcoming
  attempt; an `afterAttempt` edit affects the following attempt. The fully
  published state at `beforeAttempt` is the preceding boundary, before this
  attempt's timer update. Keep this distinction in Javadoc and state-edit tests.
- **Reentrancy:** callbacks may flush the decoded cache but must not recursively
  enter execution on this core. Internal delegation flags, local state and
  remaining budgets are not a nested-execution protocol. State this alongside
  the owner-thread and unsupported-reconfiguration rules.
- **Counter integration:** if the timer/counter extension lands first, publish
  `instret` before both callbacks as well as PC/cycle, reload callback changes,
  and preserve writable machine-counter updates through cached fast handlers,
  delegation, WFI early returns and FENCE.I flush/resume. An architectural
  counter delta can be changed by firmware writes; it is not universally a
  work metric. See the appended timer review. Source mode also needs an explicit
  sampling policy across internal exits, not accidental extra samples caused
  by restarting a driver.
- **Independent evidence:** frozen legacy comparisons establish only historical
  mode equivalence. With new source/counter behavior enabled, use a deterministic
  reference implementing the selected changes; repeated calls to the same
  modified interpreter alone cannot establish retirement/CSR conformance.
  Preserve comparisons of full committed boundaries, memory/device effects and
  both reservation layers, not just final registers and return values.
- **Real AP adapter:** retain the outer syscon monitor, pending sampling before
  and after attempts, initial halt/stop handling and reservation cleanup on
  terminal halt. Map controller stop deliberately back to the AP's existing
  return behavior rather than assuming `coreResult` itself encodes that halt.
  Terminal COMMIT stops after its store/PC publication; concurrent IOP HALT still
  waits for the outer monitor. This proposal does not grant a new lock layout
  or one-instruction concurrent HALT latency.
- **Measurement wording:** the cached-loop fusion is a performance hypothesis.
  Replace categorical “removes ... slow” and “no risk to step()” claims with the
  intended reduction in setup and the actual regression gates. Native symbol
  inspection supports the unswitching claim; it does not establish throughput.
  One larger budget is a diagnostic control, not selection of a future quantum.
  The proposed trial version and consumer timing describe work after separate
  authorization, not pending runs authorized by this review.

The explicit attempt-mode flag, null-controller behavior, synchronous-trap
continuation, same-attempt CSR/MRET dispatch, FENCE.I-before-post-callback ordering,
exception distinction and nonpositive-budget handling now match the requested
historical contract. No extra result fields or fused uncached path are requested.
Production remains CPU 0.1.8, cache off, repeated AP single-step attempts.

Review used the live V-32 AP/IOP loops and retained oracle evidence under
`docs/performance-evidence/2026-10-09/ap-boundary-api/` in the emulator repository.
This is documentation/source review only; no implementation, runtime test,
benchmark, GUI validation or continuation of Packet 002 was performed.

## CPU-agent revision 3: what changed

2026-10-11. The design above now reflects V-32's review of revision 2:

- **Answers adopted:** the test-only hook overload is scoped to the oracle's hook coverage, and
  `elapsedUs` (with a nonpositive budget too) is validated before any callback or state change.
- **Semantics:** callback visibility is now stated per phase, reentrancy is ruled out, and the
  counter integration (publishing `instret`, preserving counter writes, one time sample per
  public call) is spelled out.
- **Validation:** frozen-reference comparisons cover legacy mode only. New behavior gets an
  extended copy of the frozen interpreter as its reference, and comparisons cover full boundaries,
  memory and device effects, and both reservation layers.
- **Measurement:** the speedup is a hypothesis with explicit regression gates; native symbol
  inspection is a code-shape check, not a throughput claim; the larger budget is a diagnostic
  control; the trial build and timing need separate authorization.
- **V-32's AP adapter conditions** are recorded as V-32's integration work.

## CPU-agent revision 4: what changed

2026-10-12. On Nate's direction, the legacy contract is dropped: the core follows V-32's needs
and, where cheap to maintain, the specification.

- **Order:** the timer and counter design lands first and this one builds on it.
- **API:** `elapsedUs` is gone; time is sampled once per call. A nonpositive budget throws before
  any callback (Question 2).
- **Semantics:** an attempt uses the timer design's time and WFI rules. The `PostExecHook` jump-PC
  quirk is fixed separately (Question 3).
- **Validation:** one reference, the frozen interpreter with the intentional changes applied, its
  diff listing them; ported oracle expectations are updated where behavior changed. Bench against
  `0.1.8` is a throughput gate only.
