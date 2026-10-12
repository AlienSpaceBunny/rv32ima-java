# Timer/counters and execution API: implementation plan

Date: 2026-10-12. **Approved to start** (Nate, 2026-10-12). This plan orders the work for the two
settled designs:

1. [TIMER_COUNTER_DESIGN.md](TIMER_COUNTER_DESIGN.md) (revision 6), which lands first;
2. [EXECUTION_API_DESIGN.md](EXECUTION_API_DESIGN.md) (revision 6), which builds on it.

The designs are the specification; this file only sequences them. If a step's work reveals a design
gap, stop and raise it with Nate instead of improvising. Record each finished step's landing
commit in the status table, as in [FEATURE_REQUEST_PLAN.md](FEATURE_REQUEST_PLAN.md).

## Status

| Step | Scope | Status | Landing commit |
| --- | --- | --- | --- |
| T1 | `PostExecHook` jump-PC fix (MRET included) | Done, on `main` | `3f4fdda` |
| T2 | Independent reference core and differential harness | Done, on `feature/timer-counters` | `08d26db` |
| T3 | Shared time source, standard compare, `elapsedUs` removal | Done, on `feature/timer-counters` | `fdd68f0` |
| T4 | Standard WFI, WARL `mie`/`mip` | Done, on `feature/timer-counters` | `525c925` |
| T5 | Read-only CSR write traps | Not started | |
| T6 | Zicntr counters and `time` reads | Not started | |
| T7 | Timer phase close: docs, bench, release check, V-32 handoff | Not started | |
| A1 | `stepAttempts` API and uncached path | Not started | |
| A2 | Fused cached path | Not started | |
| A3 | Bench `--attempts` measurement | Not started | |
| A4 | Execution phase close | Not started | |

## Rules for every step

- **Direction:** follow V-32's needs and, where cheap to maintain, the RISC-V specification. Do
  not add a legacy mode or keep an old shortcut for its own sake; update existing tests to the new
  behavior.
- **Branches:** phase T on `feature/timer-counters`, phase A on `feature/attempts`, branched from
  `main` after T merges. Push branches to `origin`; merge to `main` at each phase close.
- **One step, one or more logical commits**, each passing `./mvnw spotless:apply` and
  `./mvnw clean verify`. CHANGELOG entries under `[Unreleased]` go in the same commit as the
  change. The core suite already runs uncached and cached at 4 and 4096 slots (`TestCores`).
- **The reference changes with the core.** Every behavior change in T3–T6 is applied to the
  reference core (T2) in the same commit, and added to its list of intentional differences. The
  reference never shares sampling or counter code with the optimized core.
- **Stop on an unexplained mismatch** between the core and the reference, and report it.
- **Hot path:** no inner loops inside interpreter loops (they stop Native Image unswitching); keep
  cold work (CSR, counters, time) out of the fast handlers. Measure with [`bench/`](../bench/README.md)
  pinned to `taskset -c 2,3` with nothing else running; outputs in `bench/out/`.
- **Version:** bump once per phase at its close, with `versions:set` per [AGENTS.md](../AGENTS.md),
  then `./mvnw install`.
- **CHECKPOINT.md** names the current step and its state at the end of each session.

## Phase T: timer and counters

### T1. `PostExecHook` jump-PC fix

For a taken jump or branch, the hook reports the instruction's own PC instead of the target minus
the instruction length, compressed forms included, on the uncached and cached paths (the cache
copied the quirk in `f720b7c`).

- **Tests:** taken and untaken BEQ-family branches, JAL, JALR, `C.J`, `C.JAL`, `C.JR`, `C.JALR`,
  `C.BEQZ`/`C.BNEZ`, uncached and cached.
- **Docs:** `PostExecHook` Javadoc, `docs/API.md`, CHANGELOG (Fixed).
- Independent of the rest; can land on `main` first.

### T2. Independent reference core

Copy `RV32IMACore` from `f745790`, the baseline of V-32's attempt oracle (V-32 repository:
`docs/performance-evidence/2026-10-09/ap-boundary-api/`), into the core's test sources as a
reference core. Change it only to compile against today's types and to carry the T1 fix. Later
commits to `main` are performance refactors and the opt-in decoded cache, so the reference and
the core must agree exactly at this step.

- **Differential harness:** seeded random programs plus directed cases, run in lockstep. Compare
  every committed boundary (registers, FP registers, pc, CSRs, privilege, `extraflags`, counters),
  memory and device effects, and both reservation layers (the hart's fields and a recording bus).
- **Intentional differences:** the reference's class Javadoc lists them; empty at this step.
- No production change, so no CHANGELOG entry.

### T3. Shared time source and standard compare

The design's [API](TIMER_COUNTER_DESIGN.md#api) and [time sampling](TIMER_COUNTER_DESIGN.md#time-sampling)
sections, except `time` CSR reads (T6).

- **Core:**
  - `MachineTimeSource` and `ManualTimeSource`.
  - Constructors require a source; constructors without one are removed.
  - `step()` loses `elapsedUs`; `count <= 0` throws `IllegalArgumentException` before any sample
    or state change.
  - One sample per public call; `RV32IMAState.setTimer` is removed and `getTimer()` is the last
    sample.
  - Standard unsigned compare with no zero special case. New states start at
    `TIMER_COMPARE_NEVER`.
  - MTIP is recomputed at CSR-write and MRET reevaluation.
- **Migration:**
  - Core tests get a helper for the common construct-and-step pattern first, so most of
    `CoreTest`'s 120 `step` calls change mechanically.
  - The CLI's `Main` builds a source from `System.nanoTime()` and its divisor, or from the cycle
    count in fixed-update mode. `CLINTHook` keeps writing the compare.
  - Bench `Workloads` uses a `ManualTimeSource`.
- **Tests:** the scripted source that advances on every read; sample counts per call (including
  delegated steps and the `FENCE.I` resume on the cached path); compare zero, `TIMER_COMPARE_NEVER`
  and equality at all ones; a compare moved via MMIO before MRET with no stale re-entry;
  `count <= 0` rejected without side effects; two harts on one source.
- **CHANGELOG:** Changed (breaking: constructors, `step`, `setTimer`, compare), Fixed (stale MTIP).

### T4. Standard WFI and WARL `mie`/`mip`

The design's [WFI](TIMER_COUNTER_DESIGN.md#wfi) section.

- **Core:** WFI no longer sets `mstatus.MIE`. The wake condition is `mip & mie & (MSIP | MTIP |
  MEIP)`, independent of the global enable; delivery keeps the privilege rules. Guest writes keep
  only MSIE, MTIE and MEIE in `mie` and change nothing in `mip`. Guest reads, the wake condition
  and the delivery check all mask with the implemented bits. Remove the class Javadoc's two
  "intentional deviations".
- **Tests:** the design's WFI list, keeping in mind that an interrupt already deliverable at
  entry is taken before WFI executes: WFI retiring without sleeping (M-mode, `mstatus.MIE`
  clear); entry traps (U-mode, or M-mode with MIE set); stalls ended by a source injected after
  the stall, for MSIP, MTIP and MEIP separately; a masked, due compare that does not wake; and
  reserved bits set by the host that read as 0 and neither wake nor deliver.
- **Check:** the baremetal image (`IntegrationTest` and `release.sh`) still runs to completion.

### T5. Read-only CSR write traps

The design's legality rules under [Counters](TIMER_COUNTER_DESIGN.md#counters-zicntr).

- **Core:** write intent to any CSR number with bits [11:10] = `11` raises an illegal-instruction
  exception after the privilege check and before any read, hook call or sample. CSRRW and CSRRWI
  with `rd` = x0 keep skipping the read.
- **Tests:** all six Zicsr forms on read-only numbers, including a nonzero `rs1` index holding
  zero and CSRRW with `rd` = x0; a writable hook CSR with separate read and write counters for
  read suppression; no hook call before a trap.

### T6. Zicntr counters and `time` reads

The rest of the design's [Counters](TIMER_COUNTER_DESIGN.md#counters-zicntr) section, and `time`
reads from [time sampling](TIMER_COUNTER_DESIGN.md#time-sampling).

- **Core:**
  - `getInstret()`/`setInstret()` (retired-work count). Architectural offsets behind
    `getMcycle()`/`setMcycle()` and `getMinstret()`/`setMinstret()`.
  - `mcounteren`. The CSRs `cycle`, `time` and `instret`, their high halves, and the machine
    aliases.
  - Pre-instruction reads. Writes that replace the value and suppress the instruction's own
    increment.
  - Retirement counted per segment, and published wherever `cycle` is.
  - A `time` read samples, refreshes MTIP and triggers same-instruction reevaluation.
- **Tests:** the design's counter list: high-word rollover, including at the reading instruction;
  high/low/high reads; retirement distinctions; mid-batch writes to each half; a write followed
  by a same-attempt interrupt; setter semantics; `mcounteren` gating; a hook-stored `mcountinhibit`
  that inhibits nothing; a `time` read that crosses a deadline, including one in a delegated step,
  with every later attempt keeping its sample across a `FENCE.I` resume.
- **Hot path:** retirement accounting adds no per-instruction work to `step()`'s loop or
  `runCached()`.

### T7. Timer phase close

- **Docs:** `docs/API.md` and the authoritative Javadoc (`RV32IMACore`, `RV32IMAState`,
  `CSRHook`, `PostExecHook`); the CHANGELOG reviewed as a whole; landing commits recorded in the
  status table above and in the timer design.
- **Bench:** the full matrix against `0.1.8` on JVM and native, plus a symbolized native build
  confirming `step()`'s and `runCached()`'s loops are still unswitched. A measured regression goes
  to Nate as a tradeoff decision. Record results under `docs/performance/<date>/timer-counters/`.
- **Release check:** `./release.sh` (fresh baremetal build).
- **Version and merge:** bump, `./mvnw install`, merge to `main`, push.
- **V-32 handoff:** a short note for Nate to relay, listing the breaking changes and the migration
  sites the design names: `ApplicationProcessor.stepCycles`'s nonpositive branch and
  `TerminalFaultIntegrationTest`, `IOProcessor.run()`'s wait calculation, the LAUNCH timer
  reset, `HartState` reset, the shared-source wiring, and device-level masking.

## Phase A: execution API

Starts from `main` after T7.

### A1. `stepAttempts` and the uncached path

- **Core:** `AttemptController`, `AttemptResult` with `exitReason()`, and `stepAttempts`.
  Arguments are validated before any callback; a nonpositive budget throws. The uncached path
  uses a new internal single-attempt entry: every entry check, no time sample. It is distinct
  from `DELEGATING`. The Javadoc states the contract directly and lists its differences from
  V-32's oracle.
- **Reference:** an attempt driver over the reference core with its own call-level sample
  lifetime.
- **Tests:** V-32's oracle ported from its evidence (`candidate/AttemptBoundaryTest.java`): 36
  cases and 330 budget comparisons, with changed expectations listed. The oracle's hook
  observations move to `step()`'s hook tests. Add the design's new cases, including callback
  timing in both phases and nonpositive budgets.

### A2. Fused cached path

- **Core:** `runCached()` in attempt mode, selected by an explicit flag. `FENCE.I` flushes before
  `afterAttempt` and resumes with no new sample and no second `beforeAttempt`. A delegated `time`
  read's sample is retained. V-32's disposable fused-attempt candidate (V-32 repository:
  `docs/performance-evidence/2026-10-09/ap-entry-optimization/`) is prior art, not a source to copy.
- **Tests:** a seeded lockstep differential against repeated `step(count=1)` with a fixed source.
  Advancing-source comparisons against the reference across cached and uncached paths, null
  controllers, `time` reads, delegation and `FENCE.I`, at 4 and 4096 slots and in check mode.
- **Check:** a symbolized native build showing the batched cached loop still unswitched.

### A3. Measurement

A bench `--attempts N` mode with an AP-like controller. It compares `step(count=1)` loops against
`stepAttempts`, cache off and on, at a budget of 1,061 plus one diagnostic larger budget, with
allocation recorded. The baseline is the timer version, with `0.1.8` as a historical comparison;
JVM and native. Record under `docs/performance/<date>/attempts/`. A regression goes to Nate.

### A4. Execution phase close

Docs, CHANGELOG, landing commits, bump, install, merge, push. A trial snapshot for V-32 needs
Nate's separate authorization.

## Out of scope

- V-32's adapter and device work (masking, LAUNCH, acknowledgements, wake signals, IOP adoption).
- Batch size, cache enablement and scheduling, which are V-32's.
- Other old shortcuts in the core; see [BACKLOG.md](BACKLOG.md).
