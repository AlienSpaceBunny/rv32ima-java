# Session Checkpoint — 2026-10-12

## Current state

- **`main` is on `0.1.11-SNAPSHOT`, installed to `~/.m2`** (not tagged or published). It carries
  the complete timer/counter and execution API phases
  ([plan](docs/TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md), T1–T7 and A1–A4). Its core source is
  identical to the trial `0.1.10-attempts-SNAPSHOT` (local tag `trial/0.1.10-attempts`) that V-32
  `70237cf` consumes. `v0.1.8` is installed locally and tagged on `origin`.
- **V-32 adopted `stepAttempts`:** the AP's single mechanism is cached `stepAttempts` (4096
  slots); the separate cached attempt loop is ratified on condition that both cached loops keep
  their independent-reference differential and mutation coverage.
- **Accepted tradeoffs** (Nate, 2026-10-12): T7's `step(count=1)` and HotSpot uncached batched
  costs, and A3's further 2–7% on `step(count=1)`. V-32's AP budget of 1,061 is a legacy value
  it will likely retune; nothing in the core depends on it.
- **Work directly on `main`** and push after each validated step, until API stabilization for
  `0.2.0` (Nate, 2026-10-12).

## Resume here

**Phase A handoff relayed** (2026-10-12, [archived](docs/archive/V32_EXECUTION_PHASE_HANDOFF.md)):
nothing is pending from V-32.

**Next (Nate, 2026-10-12): two audits, then a proposal to Nate before any implementation.**

1. **Remaining old shortcuts.** The items in [docs/BACKLOG.md](docs/BACKLOG.md#deferred): the
   non-standard `misa` bit 22 with `hasU = false` and its compatibility constructors, `mvendorid`
   `0xff0ff0ff`, and CSRs (`mstatus` and others) that accept any value instead of their WARL
   fields, such as MRET into an unmodeled privilege. Find any others in the code too. Check each
   for a V-32 dependency (`../emulator`) first. Under the no-legacy direction, propose fixing,
   not preserving.
2. **Extensions discussed but deferred.** Read the active docs, `docs/archive/` and the V-32
   exchanges (including V-32's `docs/`) for extensions or features that were raised as possible
   work and then deferred. List each with where it came up, why it was deferred, and whether
   V-32 needs it. **D and RV64 stay deferred** ([RV64 notes](docs/RV64_FEASIBILITY_NOTES.md)); do
   not reopen them.

Report both as one short findings document for Nate to pick from. The API-freeze review toward
`0.2.0` ([RELEASE_TODO.md](RELEASE_TODO.md)) follows.

**Validation status:** the `0.1.11-SNAPSHOT` tree passed `./release.sh` (`./mvnw clean verify`:
801 core tests in each of three cache configurations, CLI, bench; then a fresh baremetal build
and run). `release.sh` rewrites `baremetal/baremetal.elf` and its debug listing with debug-string
noise only; restore them rather than committing.

## Rules for this work

- **Direction (Nate, 2026-10-12):** design for V-32's needs and, where cheap to maintain, the
  RISC-V spec. No legacy mode and no old shortcuts kept for their own sake; temporary V-32
  breakage during migration is accepted. A bench regression goes to Nate as a tradeoff decision.
- **Gates and finishing:** follow [AGENTS.md](AGENTS.md) (spotless, `clean verify`, CHANGELOG,
  version bump per phase with `versions:set`, `install`, commit and push). Shared tooling
  versions belong in `../alienspacebunny-build`.
- **Benchmarks:** [`bench/`](bench/README.md) on JVM and native, pinned to `taskset -c 2,3`, with
  nothing else running; outputs in the git-ignored `bench/out/`. `step(count=1)` cells
  (`reg-ap-step1` batched, `--step-loop`) are diagnostic only: report them, but only a massive
  regression justifies optimization work (Nate, 2026-10-12). Its correctness tests stay.
- **Native Image:** keep inner loops (array fills, flushes) out of interpreter loops; they stop
  loop unswitching. Verify with a symbolized build, as in
  [the cache evidence](docs/performance/2026-10-10/decoded-cache/README.md).
- **Core design:** the core stays independent of the CLI and any memory backend. `step()`'s long
  body is deliberate; cold F/A arms live in `executeColdOpcode`; `runCachedAttempts` copies
  `runCached`'s fast handlers, so change both; trap dispatch uses an internal cause-plus-one
  encoding. The Javadoc is authoritative; [API.md](docs/API.md) summarizes it.
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
