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
