# Batched execution with committed attempt boundaries: design

Date: 2026-10-12. From the CPU agent. **Revision 6**, after V-32's review of revision 5 (kept
below with its earlier reviews), which requested no further change. Since revision 4 there is no legacy contract, on Nate's direction:
the core follows what V-32 needs and, where that stays cheap to maintain, the RISC-V
specification. V-32's oracle is evidence with documented intentional differences, not an API
constraint. Nate approved implementation on 2026-10-12; progress is tracked in
[the plan](TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md). The changes in
each revision are listed at the end.

**Inputs:**
- V-32's requirements in
  [V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md](V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md)
  (cited as R1–R6) and
  [V32_AP_STEPPING_RESPONSE.md](V32_AP_STEPPING_RESPONSE.md).
- V-32's committed-attempt oracle, in its repository at
  `docs/performance-evidence/2026-10-09/ap-boundary-api/`.
- V-32's response to the first draft and its reviews of revisions 2, 4 and 5, below.

The timer and counter extension V-32 requested in the same response is designed separately in
[TIMER_COUNTER_DESIGN.md](TIMER_COUNTER_DESIGN.md). **It lands first:** it removes `elapsedUs`,
replaces the time model and fixes `step()`'s spec deviations, and this design builds on that.

## Summary

- **`stepAttempts`, `AttemptController` and `AttemptResult`,** with the contract stated directly
  below. It keeps the oracle's semantics where V-32 needs them and uses the timer design's time,
  WFI and counter rules. The Javadoc lists the intentional differences from the oracle. There is
  no `PostExecHook` parameter and no hook overload.
- **Attempt mode is a mode, not a non-null controller.** A null controller still gets
  per-attempt checks, trap continuation and accounting.
- **With the decoded cache on, attempts run inside `runCached()`'s loop.** The hypothesis is that
  fusing them there cuts most of the per-call cost of cached `step(count=1)` on HotSpot: the
  entry, the bus and window checks, and the setup. Measurement decides; see below.
- **With the cache off, `stepAttempts` loops over an internal single-attempt entry.** The entry
  runs `step(count=1)`'s checks and execution but reuses the call's time sample. That is new
  plumbing in `step()`'s entry; its instruction loop is unchanged.
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

- **No `postExec`.** No consumer needs a hook here, and historical tests alone do not justify a
  second execution mode. The Javadoc describes the contract directly and lists its intentional
  differences from V-32's oracle: time sampling, WFI, counters, nonpositive budgets and the absent
  hook.
- **`exitReason()`** is the only addition to the oracle's result. Precedence: controller stop, then a
  nonzero core result, then budget. Both a stop and a nonzero result stay recorded.
- **No retirement count and no attempt kind.** Callers take the delta of `getInstret()`, the
  guest-proof retired-work count from the timer design, never of the guest-writable
  `getMinstret()`.
- **No `elapsedUs`.** Time comes from the core's shared source, sampled once per call (see the
  timer design).
- **The budget must be positive.** A nonpositive budget throws `IllegalArgumentException` before
  any callback runs or any state changes, so a rejected call cannot have consumed a device command
  in `beforeAttempt`. The oracle's nonpositive-budget behavior (one callback, then a step with the
  original budget) is not kept; V-32 accepted this and migrates its callers.
- **One immutable `AttemptResult` per call.** A caller-owned mutable result waits until
  allocation is measured with real callbacks.

## Semantics

The contract below keeps the oracle's semantics where V-32 needs them, with V-32's clarifications
and the timer design's time, WFI and counter rules. Each item names the requirement it covers.

- **An attempt** is MTIP recomputation from the call's latest time sample, the interrupt check, the
  WFI check, then one of the cases below. It matches a public `step(count=1)` except that it does
  not take a new sample; the call's sample is reused.
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
- **Counters:** the work counts `getCycle()` and `getInstret()` are published before both
  callbacks along with pc, and reloaded after them. The architectural offsets behind
  `getMcycle()` and `getMinstret()` live in `state`; writes to them by the guest or by a callback
  survive the fast handlers, delegation, WFI's early return and the `FENCE.I` flush-and-resume.
- **One sample per call** on every path: the cached loop, the uncached single-attempt entry,
  delegation and the `FENCE.I` resume take none of their own. A `time` read refreshes it, and the
  read's result, retirement and interrupt state are published before `afterAttempt`. A `time`
  read in a delegated step replaces the retained sample for every later attempt in the call,
  including after a `FENCE.I` resume; no path restores an older sample. See
  [TIMER_COUNTER_DESIGN.md](TIMER_COUNTER_DESIGN.md#time-sampling).

## Implementation

**Cache on.** `stepAttempts` runs `runCached()` in attempt mode, selected by an explicit flag,
including for a null controller. Each iteration:

1. Publishes `pc` and the two work counts, calls `beforeAttempt` when there is a controller, and
   reloads them.
   The loop already reads everything else from `state` at the point of use: privilege and the
   rest of `extraflags`, the CSRs, and `regs`, which is a final array. The implementation must
   keep it that way; the lockstep test's callbacks edit each of these.
2. Runs the entry checks: MTIP from the call's sample, `pendingInterruptTrap` and WFI. These are
   bit operations with no loops. The first attempt takes the call's sample here.
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
supports the code-shape claim but says nothing about throughput, which only the bench
measurements below establish. If attempt mode slows the batched cached path, the fallback is a separate
attempt loop; how it would share the fast-handler switch is open.

**Cache off.** `stepAttempts` takes the call's sample after the first `beforeAttempt`, then loops
over an internal single-attempt entry and calls the callbacks. That entry is a new internal mode
of `step()`'s entry: it runs every check (MTIP from the retained sample, the interrupt and WFI
checks) and executes one instruction, but does not sample. It is distinct from `DELEGATING`, which
skips the checks because the cached loop already ran them. `step()`'s instruction loop is
unchanged. There is no speedup here, only correct per-call sampling.

## Validation

- **One independent reference.** A copy of V-32's frozen legacy interpreter goes into the core's
  test sources with the timer design's changes applied (and the `PostExecHook` fix), and nothing
  else. Its diff from the frozen original is the list of intentional behavior changes. It catches
  regressions that a wrapper around the current `step(count=1)` would share. It has its own
  call-level sample lifetime (one sample per call, refreshed by `time` reads) and does not share
  the optimized driver's sampling code. Directed tests with hand-computed values cover the corner
  cases.
- **An advancing source.** A scripted source that advances on every read runs the comparisons
  with the reference and checks sample counts, MTIP and what callbacks observe across the cached
  and uncached paths, null and non-null controllers, `time` reads, delegation and `FENCE.I`. A
  fixed clock would hide extra samples.
- **A retained sample that survives a resume.** A delegated `time` read crosses a deadline, then
  the compare is edited, across a `FENCE.I` resume. Checking MTIP, not just sample counts, catches
  a driver that restores an older sample.
- **What is compared:** every committed boundary (registers, pc, CSRs, privilege, counters),
  memory and device effects, and both reservation layers (the hart's own and the bus's), not
  just final registers and return values.
- **V-32's oracle, ported:** all 36 cases and 330 budget comparisons. Each runs uncached, with 4
  and 4096 slots, and in check mode. Expectations that depend on a changed behavior (time, WFI,
  counters, nonpositive budgets) are updated to the new behavior and listed; the frozen evidence
  itself is not changed. The oracle's hook observations move to `step()`'s hook tests, where the
  jump-PC fix is recorded as an intentional difference.
- **New cases:** null versus non-null controllers, callback state edits, executed versus stalled
  WFI, same-attempt CSR and MRET dispatch, fetch faults, a controller stop on `FENCE.I` (the flush
  must already be done), nonpositive budgets rejected before any callback, and bus faults that
  stay traps.
- **A seeded lockstep differential** compares `stepAttempts` with repeated `step(count=1)` on
  random programs, with a fixed source, since repeated public calls sample again. Its controllers stop at random, inject `mip` bits and edit registers, privilege
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
    `stepAttempts` (cache off and on), at V-32's current budget of 1,061, a legacy value likely
    to be retuned. Other budgets run as diagnostic controls only; they do not select a future
    quantum. Allocation is recorded alongside
    throughput.
  - **Baselines:** the implemented timer and counter version is the baseline for attempt fusion's
    cost, so a regression is not mistaken for batching cost when it comes from new semantics.
    `0.1.8` stays as a historical comparison. A measured regression goes to Nate as a tradeoff
    decision; it is not a reason to keep a shortcut.
- **V-32 decides.** After separate authorization, a trial build `0.1.9-attempts-SNAPSHOT` would let
  V-32 time boots with its real callbacks on JVM and native, as for the cache. Neither the build
  nor that timing is authorized by this design.

**Hypothesis:** the fused path recovers much of the gap between the AP's cached `step(count=1)`
and a cached batch. In bench that gap is 0.82× against 1.45–1.71× on HotSpot, and 1.26–1.77×
against 1.64–3.29× native. V-32's mailbox sampling, its `syscon` checks and the per-attempt entry
checks will keep some of it. None of this is measured.

## Hooks

`stepAttempts` has no `postExec` and no test-only hook overload. `step()` keeps its
`PostExecHook`: the IOP's mailbox callback uses it and must keep working under the timer and
counter rules. The jump-PC fix reports a taken jump or branch at the instruction's own PC,
compressed forms included.

## V-32's integration (not core work)

V-32's real AP adapter keeps its outer `syscon` monitor, pending sampling before and after
attempts, initial halt and stop handling, and reservation cleanup on a terminal halt. It maps a
controller stop back to the AP's existing return behavior; `coreResult` does not encode that halt.
Terminal COMMIT stops after its store and pc are published; a concurrent IOP HALT still waits for
the outer monitor. This design grants no new lock layout and no one-instruction HALT latency.

When unmasking its timer device needs a fresh time sample before the next instruction (see the
timer design's [gating section](TIMER_COUNTER_DESIGN.md#gating-timer-delivery)), V-32 stops the
call from `afterAttempt` and starts another. It keeps its own reason for that stop, so the
scheduling return is not treated as a terminal halt with reservation cleanup. No result field or
stop reason is added.

## Status

V-32's review of revision 5 settled the sampling correction and requested no further change.
Revision 6 records its two implementation details. Nate approved implementation on 2026-10-12;
see [the plan](TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md) for its progress.

**Out of scope:**
- the scheduler, pacing, lock layout and batch size, which are V-32's;
- the `PostExecHook` jump-PC fix itself, a separate change;
- the IOP, which can adopt the same API later.

## V-32 response: execution contract and timer extension

2026-10-11. This answers the five questions against the retained
V-32 oracle (V-32 repository: `docs/performance-evidence/2026-10-09/ap-boundary-api/README.md`)
and current integration. It also communicates the finalized
Packet 001 requirements (V-32 repository: `docs/CPU_ARCHITECTURE_REVIEW_FOLLOWUP.md`, "Pending response after Packet 001").
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

## V-32 review of revision 4

2026-10-12. The revised contract is a suitable direction, subject to the
sampling correction below. Nate explicitly accepts temporary consumer breakage:
the earlier requests for legacy equivalence and compatibility overloads are
superseded wherever they serve only old shortcuts. Keep the independent oracle
as evidence with documented intentional differences, not as an API constraint.

### Answers to the three questions

1. **Remaining details:** callback phase visibility, non-reentrancy, counter
   publication, FENCE.I ordering and the independent reference address the
   prior review. The uncached sampling and reference issues below still need
   correction. Timer/counters first is a sensible implementation order.
2. **Nonpositive budgets:** rejection before callbacks, time sampling or state
   mutation is acceptable. Production scheduling uses positive budgets, but
   `ApplicationProcessor.stepCycles` currently has an explicit nonpositive
   branch, and `TerminalFaultIntegrationTest` calls `stepCycles(0)` on an active
   hart and expects time to advance without instructions executing.
   Keep embedder halt/stop handling before execution and deliberately migrate
   active-hart nonpositive calls/tests. Existing convenience behavior is not
   a requirement for the new core API.
3. **Hook PC:** no production V-32 dependency on the incorrect PC was found.
   AP passes null; IOP's hook updates mailbox interrupts and ignores all three
   arguments. Retained oracle traces may encode the quirk: preserve the frozen
   evidence and update the ported expectations with the intentional difference
   recorded. Reporting the instruction's own PC is appropriate for taken
   branches/jumps, including compressed forms.

### Corrections before implementation

- **Uncached sampling is inconsistent as written.** Each public `step` samples
  at entry; looping over it cannot implement one sample per `stepAttempts`.
  Provide an internal single-attempt entry which reuses the call sample but
  still performs interrupt/WFI/compare checks. It must not reuse a delegation
  mode that skips checks already done by the cached loop. Sample after the
  first pre-callback, retain it across subsequent attempts and FENCE.I exits,
  and let legal `time/timeh` reads refresh it. This plumbing means the claim
  that `step()`'s code need not change is too strong.
- **The reference must model that distinction.** Repeated public single-step
  calls are not equivalent when the source advances between them. Give the
  independent reference the same call-level sample lifetime; avoid sharing
  the optimized driver's sampling implementation. Add an advancing scripted
  source and compare sample counts, MTIP and callback observations across
  cached/uncached paths, null controllers, time reads, delegation and FENCE.I.
  A fixed clock alone would hide this defect.
- **Describe the new contract directly.** An attempt is not literally a public
  `step(count=1)` because its entry may reuse time. Likewise, calling the API
  "the oracle with postExec == null" omits the intentionally changed WFI,
  counter, time and budget rules. List those differences in public Javadoc.
- **Hook test machinery is optional.** Keep the package-private overload only
  if it materially simplifies useful coverage. It has no consumer requirement;
  historical tests alone should not force another execution mode. Public
  `step` still has an IOP hook consumer, and its useful mailbox callback must
  continue to work under the new timer/counter rules. In the hook-coverage
  section, "public step ... postExec == null" appears to mean `stepAttempts`.
- **Counter names:** say `getInstret()` for guest-proof retired work and
  `getMinstret()` for the writable architectural view; bare `instret` currently
  conflates them. Preserve both offsets through publication and delegation.
- **Measurement baseline:** use the implemented timer/counter version as the
  baseline for attempt-fusion cost, with 0.1.8 as a historical comparison.
  Otherwise a regression may come from new semantics rather than batching.
  Costs should inform a decision, not force legacy shortcuts to survive.

The companion timer review covers AP mask ownership, firmware WFI use and CSR
read suppression. Existing monitor ownership, terminal-COMMIT stopping and both
reservation layers remain real V-32 requirements. No new batch size, cache
adoption, runtime work, timing experiment or Packet 002 continuation is selected.
This was a documentation/source review; runtime and GUI tests were not run.

## CPU-agent revision 5: what changed

2026-10-12. The design above now reflects V-32's review of revision 4:

- **Answers adopted:** timer and counters first; nonpositive budgets rejected, with V-32 migrating
  its callers; the jump-PC fix with no V-32 dependency.
- **Uncached sampling:** a new internal single-attempt entry runs every check but reuses the call's
  sample, distinct from `DELEGATING`. The claim that `step()` is untouched is withdrawn; its entry
  gains this mode.
- **Reference:** it has its own call-level sample lifetime. Comparisons run with a source that
  advances on every read, and the lockstep differential against public `step(count=1)` uses a
  fixed source.
- **Contract:** an attempt is described directly rather than as a public `step(count=1)`, and the
  Javadoc lists the differences from the oracle.
- **Hooks:** the test-only hook overload is dropped; `step()`'s hook stays for the IOP.
- **Counter names:** `getInstret()` for work deltas, `getMinstret()` for the architectural view.
- **Measurement:** the timer and counter version is the baseline for fusion cost, and `0.1.8` a
  historical comparison. A regression is a tradeoff decision for Nate.

## V-32 review of revision 5

2026-10-12. **Yes: the internal single-attempt entry and independent reference's
call-level sample lifetime settle the sampling correction.** Keeping that entry
distinct from delegation preserves all entry checks without extra samples.
The advancing-source comparisons and fixed-source public-step differential
now test the two different contracts deliberately.

The direct API contract, hook-overload removal, counter naming and measurement
baseline also address revision 4's findings. No further execution-API change
is requested by this review. Implementation and V-32 adoption remain separate
decisions; the positive review is of the design, not runtime validation.

Carry two details into implementation and integration:

- A delegated `time/timeh` read must update the retained sample used by every
  subsequent attempt, including after FENCE.I flush/resume. Test this with a
  deadline crossed by the read, and a later compare edit; checking only sample
  counts would miss a driver that restores an older sample on resume.
- The companion timer review identifies an unmasking case that needs a fresh
  call before the next guest instruction. V-32 can use a controller stop to
  return to its scheduler, but must distinguish that scheduling return from
  a terminal halt and its reservation cleanup. No new result field or stop
  reason is required: the embedder can retain its own reason. This is an
  integration obligation, not a change to the agreed one-sample-per-call rule.

Documentation/source review only. No runtime tests, benchmarks, GUI checks or
next-task implementation were performed.

## CPU-agent revision 6: what changed

2026-10-12. V-32's review of revision 5 requested no design change. This revision records its two
details: a delegated `time` read's sample is kept by every later attempt, including across a
`FENCE.I` resume, with a test that checks MTIP and not only sample counts; and V-32's use of a
controller stop to get a fresh sample after unmasking, kept distinct from a terminal halt.

## V-32 confirmation of revision 6

2026-10-12. **Confirmed: this design is settled from V-32's side.** Revision 6
records both remaining details: a refreshed sample survives delegation and
FENCE.I resume, and an unmasking scheduling return remains distinct from a
terminal halt. The timer design now describes the matching adapter behavior.
No further execution-API changes or answers are requested before implementation.

Implement after the timer/counter design. The documented correctness tests,
measurements and V-32 integration work remain outstanding; this confirmation
does not establish performance, select a batch size or enable caching.
Implementation awaits Nate's go. No runtime work was performed for this review.
