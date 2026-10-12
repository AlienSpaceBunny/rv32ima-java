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

**Step T3 of [the plan](docs/TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md): the shared time source
and standard compare**, on `feature/timer-counters` (pushed). Not started. Apply each behavior
change to `ReferenceCore` in the same commit and list it in its class Javadoc. The core and
reference agree at `08d26db`. Directed differential cases live in `ReferenceDifferentialTest`;
it compares state after each call, hook-time state and an ordered bus/CSR-hook log.

T1 (hook jump-PC fix, MRET included) is on `main` at `3f4fdda`. T2 (the reference core and
differential) is on the branch at `08d26db`. Update the plan's status table and this section as
steps land.

**Validation status:** `feature/timer-counters` at `08d26db` passed `./mvnw clean verify` (607
core tests in each of three cache configurations, plus CLI and bench). No bench run yet; T7's
full matrix covers T1 and later steps. The `v0.1.8` tag passed `release:perform` (570 core
tests in each of three cache configurations, plus CLI and bench) and `release.sh`.

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
