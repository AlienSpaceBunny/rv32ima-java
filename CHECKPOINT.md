# Session Checkpoint — 2026-10-12

## Current state

- **`main` is on `0.1.9-SNAPSHOT`.** Since the local `v0.1.8` release (installed in `~/.m2`, not
  published) its only code change is T1's hook fix; the rest is design documentation.
- **Next work is approved:** implement the settled timer/counter design, then the execution API
  design, following [the implementation plan](docs/TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md).
  Both designs are at revision 6 and confirmed by V-32.
- **Blocked and deferred items** are in [docs/BACKLOG.md](docs/BACKLOG.md), including the
  unpushed `v0.1.8` tag and branch cleanup that wait on Nate.

## Resume here

**Step T2 of [the plan](docs/TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md): the independent
reference core and differential harness**, on a new `feature/timer-counters` branch from `main`.
Not started. T1 (the `PostExecHook` jump-PC fix) landed on `main` in `3f4fdda`; it also fixes
MRET, which had the same quirk, so the T2 reference carries that too. Update the plan's status
table and this section as steps land.

**Validation status:** `main` at `3f4fdda` passed `./mvnw clean verify` (594 core tests in each
of three cache configurations, plus CLI and bench). No bench run for T1; T7's full matrix covers
it. The `v0.1.8` tag passed `release:perform` (570 core tests in each of three cache configurations, plus CLI and bench) and `release.sh`.

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
