# Session Checkpoint — 2026-10-12

## Current state

- **`main` is on `0.1.10-SNAPSHOT`, installed to `~/.m2`** (not tagged or published). It carries
  the timer/counter phase (T1–T7) and, not yet installed, step A1. `v0.1.8` is installed locally
  and tagged on `origin`. The version bump for phase A waits for A4.
- **V-32 trial:** [the handoff](docs/V32_TIMER_PHASE_HANDOFF.md) lists the breaking changes and
  V-32's migration sites, for Nate to relay. Wait for V-32's test report before archiving it.
- **Work directly on `main`** and push after each validated step, until API stabilization for
  `0.2.0` (Nate, 2026-10-12).
- **T7 bench tradeoff accepted** (Nate, 2026-10-12): `step(count=1)` about 1.12× slower natively
  and HotSpot uncached batched about 5% slower stay as they are; V-32 picks its execution mode
  (`step` or `stepAttempts`, cache on or off) once the core is stable
  ([evidence](docs/performance/2026-10-12/timer-counters/README.md)). Blocked and deferred items
  are in [docs/BACKLOG.md](docs/BACKLOG.md).

## Resume here

**Step A2 of [the plan](docs/TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md): the fused cached path**,
per [the execution API design](docs/EXECUTION_API_DESIGN.md) (revision 6). Not started. Today a
cached core's `stepAttempts` runs each attempt as a cached single-instruction batch through the
same internal entry as the uncached path; A2 moves attempts into `runCached()`'s loop behind an
explicit flag. A2's tests include the seeded lockstep against repeated `step(count=1)` with a
fixed source, and the delegated `time` read retained across a `FENCE.I` resume.

A1 landed as `5c1a58c` (an executed, stalling `WFI` calls the `PostExecHook`) and `83308f3`
(`stepAttempts`, `AttemptController`, `AttemptResult`; the reference core's attempt driver; V-32's
oracle ported as `AttemptBoundaryTest`; new cases in `StepAttemptsTest`). The internal entry is an
`attemptEntry` flag that `step()` tests once per call; it adds no work to the instruction loop.

**Validation status:** the A1 tree passed `./mvnw clean verify` (788 core tests in each of three
cache configurations, plus CLI and bench). A mutation that makes the attempt entry sample fails 38
of the new tests. Not run for A1: `./release.sh` and bench (neither required by the plan until
A3/A4). `release.sh` rewrites `baremetal/baremetal.elf` and its debug listing with debug-string
noise only; restore them rather than committing.

## Rules for this work

- **Direction (Nate, 2026-10-12):** design for V-32's needs and, where cheap to maintain, the
  RISC-V spec. No legacy mode and no old shortcuts kept for their own sake; temporary V-32
  breakage during migration is accepted. A bench regression goes to Nate as a tradeoff decision.
- **Gates and finishing:** follow [AGENTS.md](AGENTS.md) (spotless, `clean verify`, CHANGELOG,
  version bump per phase with `versions:set`, `install`, commit and push). Shared tooling
  versions belong in `../alienspacebunny-build`.
- **Benchmarks:** [`bench/`](bench/README.md) on JVM and native, pinned to `taskset -c 2,3`, with
  nothing else running; outputs in the git-ignored `bench/out/`.
- **Native Image:** keep inner loops (array fills, flushes) out of interpreter loops; they stop
  loop unswitching. Verify with a symbolized build, as in
  [the cache evidence](docs/performance/2026-10-10/decoded-cache/README.md).
- **Core design:** the core stays independent of the CLI and any memory backend. `step()`'s long
  body is deliberate; cold F/A arms live in `executeColdOpcode`; trap dispatch uses an internal
  cause-plus-one encoding. The Javadoc is authoritative; [API.md](docs/API.md) summarizes it.
- **F directed rounding** needs the exact residual alongside the double approximation; keep the
  independent differential tests when changing that arithmetic.
- **Working with V-32:** Codex works on V-32 (`../emulator`), Claude on this repo, and Nate relays.
  Exchanges are files in `docs/` with dated sections appended by each side. Commit a received
  reply before editing. When an exchange settles, replace local links with V-32 repository paths.
  V-32's current calls are not the target.
- **Releases:** publishing is on hold until `0.2.0` ([RELEASE_TODO.md](RELEASE_TODO.md)). Never
  start the GitHub release workflow without Nate's explicit request. `exp/predecode` stays local
  and is never pushed.
- **Documentation index:** [docs/README.md](docs/README.md), which also lists completed plans and
  the [checkpoint archive](docs/archive/CHECKPOINT_PRE_COMPACTION_2026-10-06.md).
