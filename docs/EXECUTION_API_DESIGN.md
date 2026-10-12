# Batched execution with committed attempt boundaries: design

Date: 2026-10-12. From the CPU agent. **Revision 6, implemented** in steps A1–A4 of
[the implementation plan](TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md); see [Status](#status). V-32
confirmed this revision and adopted it. Since revision 4 there is no legacy contract, on Nate's
direction: the core follows what V-32 needs and, where that stays cheap to maintain, the RISC-V
specification. V-32's oracle is evidence with documented intentional differences, not an API
constraint. V-32's response and reviews of every revision, and the per-revision change lists, are
archived in
[EXECUTION_API_DESIGN_REV6_WITH_REVIEWS_2026-10-12.md](archive/EXECUTION_API_DESIGN_REV6_WITH_REVIEWS_2026-10-12.md).

**Inputs:**
- V-32's requirements in
  [V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md](V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md)
  (cited as R1–R6) and
  [V32_AP_STEPPING_RESPONSE.md](V32_AP_STEPPING_RESPONSE.md).
- V-32's committed-attempt oracle, in its repository at
  `docs/performance-evidence/2026-10-09/ap-boundary-api/`.
- V-32's response to the first draft and its reviews of revisions 2, 4 and 5, in the archived
  copy.

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
- **With the decoded cache on, attempts run in a cached loop of their own,**
  `runCachedAttempts()`, which copies `runCached()`'s lookup, delegation and fast handlers. The
  design's first choice, a mode of `runCached()`, slowed batched execution on Native Image; see
  [Implementation](#implementation).
- **With the cache off, `stepAttempts` loops over an internal single-attempt entry.** The entry
  runs `step(count=1)`'s checks and execution but reuses the call's time sample. That is new
  plumbing in `step()`'s entry; its instruction loop is unchanged.
- **The `PostExecHook` jump-PC quirk is fixed, separately.** For a taken jump or branch, the hook
  will report the instruction's own PC. Nothing keeps the old value.
- **V-32 adopted it** (V-32 `70237cf`, on the trial build `0.1.10-attempts-SNAPSHOT`): the AP's
  single execution mechanism is `stepAttempts` with a 4096-slot cache. The IOP keeps `step`
  batches and its `PostExecHook`.

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
- **One immutable `AttemptResult` per call.** Native Image allocates it (24 bytes per call);
  HotSpot usually scalar-replaces it. A caller-owned mutable result is not needed at the budgets
  measured.

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

**Cache on.** `stepAttempts` runs `runCachedAttempts()`, a separate loop, including for a null
controller. Each iteration:

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

The batched loop keeps its semantics: a trap or a newly deliverable interrupt ends the batch.

**Why a separate loop.** The design first made attempt mode a loop-invariant flag in
`runCached()`. Native Image did not unswitch on it, so batched cached execution lost 7–14% in
any image that reaches `stepAttempts`. The design's fallback landed instead:
`runCachedAttempts()` copies `runCached()`'s lookup, delegation and fast handlers (about 200
lines), and `runCached()`'s machine code is unchanged. Both Javadocs point at each other; change
both together. V-32 ratified the copy on the condition that both loops keep their
independent-reference differential and mutation coverage. Evidence:
[attempts-a2](performance/2026-10-12/attempts-a2/README.md).

**Cache off.** `stepAttempts` takes the call's sample after the first `beforeAttempt`, then loops
over an internal single-attempt entry and calls the callbacks. That entry is a new internal mode
of `step()`'s entry: it runs every check (MTIP from the retained sample, the interrupt and WFI
checks) and executes one instruction, but does not sample. It is distinct from `DELEGATING`, which
skips the checks because the cached loop already ran them. `step()`'s instruction loop is
unchanged. There is no speedup here, only correct per-call sampling. The entry check costs
`step(count=1)` 2–7% against the timer version; Nate accepted that (2026-10-12).

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

Step A3 measured `stepAttempts` against V-32's former AP loop of `step(count=1)` calls with the
same AP-like callbacks, cache off and on, on HotSpot and Native Image
([evidence](performance/2026-10-12/attempts/README.md)). The timer version was the baseline and
`0.1.8` a historical comparison.

- Cached `stepAttempts` runs 1.21–1.76× faster than the cached `step(count=1)` loop on native
  and 1.29–1.74× on HotSpot. Uncached `stepAttempts` matches the uncached loop.
- The budget barely matters: 64 costs 0–8% against V-32's current (legacy) 1,061, and 65,536
  gains nothing. Other budgets were diagnostics; none is selected here.
- Batched execution is unchanged.

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

Implemented on `main` for `0.1.11-SNAPSHOT`, in the plan's steps:

| Step | Scope | Commit |
| --- | --- | --- |
| A1 | `stepAttempts` API and uncached path; `PostExecHook` for a stalling WFI | `5c1a58c`, `83308f3` |
| A2 | Cached path as a separate loop | `f9e5acb` (bench), `4c37ae0` |
| A3 | Bench measurement | `79cf4f7` (bench), `583c3dc` |
| A4 | Phase close: docs, release check, version | see the plan |

**Out of scope:**
- the scheduler, pacing, lock layout and batch size, which are V-32's;
- the IOP, which can adopt the same API later.
