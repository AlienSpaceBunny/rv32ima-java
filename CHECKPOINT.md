# Session Checkpoint — 2026-10-12

## Current state

- **`main` is on `0.1.10-SNAPSHOT`, installed to `~/.m2`** (not tagged or published). It carries
  the whole timer/counter phase (T1–T7). `v0.1.8` is installed locally and tagged on `origin`.
- **V-32 trial:** [the handoff](docs/V32_TIMER_PHASE_HANDOFF.md) lists the breaking changes and
  V-32's migration sites, for Nate to relay. Wait for V-32's test report before archiving it.
- **Work directly on `main`** and push after each validated step, until API stabilization for
  `0.2.0` (Nate, 2026-10-12).
- **Waiting on Nate:** the [T7 bench tradeoff](docs/performance/2026-10-12/timer-counters/README.md)
  (`step(count=1)` about 1.12× slower natively, HotSpot uncached batched about 5% slower; batched
  native unchanged). Other blocked and deferred items are in [docs/BACKLOG.md](docs/BACKLOG.md).

## Resume here

**Step A1 of [the plan](docs/TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md): `stepAttempts` and the
uncached path**, implementing [the execution API design](docs/EXECUTION_API_DESIGN.md)
(revision 6). Not started. It includes the agreed `PostExecHook`/WFI rule (an executed WFI calls
the hook once, stalling or not). If Nate wants a mitigation for the bench tradeoff first, do that
before A1.

T7 landed as `d6948c8` (Javadoc and API.md fixes), `ebe694a` (timer design archived and trimmed),
`0c46c8f` (bench evidence) and the version-bump commit.

**Validation status:** the T7 tree passed `./mvnw clean verify` (699 core tests in each of three
cache configurations, plus CLI and bench), `./release.sh` with a freshly built baremetal binary,
and `./mvnw clean install` at `0.1.10-SNAPSHOT`. Symbolized native builds show `step()` and
`runCached()` still unswitched. `release.sh` rewrites `baremetal/baremetal.elf` and its debug
listing with debug-string noise only; restore them rather than committing.

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
