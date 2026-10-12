# Session Checkpoint — 2026-10-12

## Current state

- **`main` is on `0.1.10-SNAPSHOT`, installed to `~/.m2`** (not tagged or published). It carries
  the timer/counter phase (T1–T7) and, not yet installed, steps A1 and A2. `v0.1.8` is installed
  locally and tagged on `origin`. The version bump for phase A waits for A4.
- **V-32 adopted the trial** `0.1.10-attempts-SNAPSHOT` (V-32 `70237cf`): the AP's single
  mechanism is `stepAttempts` with a 4096-slot cache; the separate cached attempt loop is
  ratified; no core API blocker
  ([exchange, archived](docs/archive/V32_ATTEMPTS_RATIFICATION.md)).
- **Work directly on `main`** and push after each validated step, until API stabilization for
  `0.2.0` (Nate, 2026-10-12).
- **T7 bench tradeoff accepted** (Nate, 2026-10-12): `step(count=1)` about 1.12× slower natively
  and HotSpot uncached batched about 5% slower stay as they are; V-32 picks its execution mode
  (`step` or `stepAttempts`, cache on or off) once the core is stable
  ([evidence](docs/performance/2026-10-12/timer-counters/README.md)). Blocked and deferred items
  are in [docs/BACKLOG.md](docs/BACKLOG.md).

## Resume here

**A3 is done** ([evidence](docs/performance/2026-10-12/attempts/README.md)): V-32's cached
`stepAttempts` path is 1.21–1.76× faster than its former cached `step(count=1)` loop, batches are
unchanged, and the budget barely matters. Nate accepted the 2–7% `step(count=1)` regression
(2026-10-12). A follow-up question from Nate arrived truncated; get the rest before A4.

**Next: step A4 of [the plan](docs/TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md)**, the phase close
(docs, CHANGELOG review, `./release.sh`, version bump, install, push, archive the execution
design's review history). V-32's AP budget of 1,061 attempts per call is a legacy value V-32
will likely retune; nothing in the core should depend on it. Keep both cached loops'
independent-reference differential and mutation coverage (V-32's ratification condition).

**Trial build:** `0.1.10-attempts-SNAPSHOT` is installed in `~/.m2` from local tag
`trial/0.1.10-attempts`, which V-32 now consumes. `main`'s POMs stay on `0.1.10-SNAPSHOT`.

**Validation status:** the bench `--step-loop` tree (`79cf4f7`) passed `./mvnw clean verify`;
the core is unchanged since A2. The A2 tree passed `./mvnw clean verify` (801 core tests in each of three
cache configurations, plus CLI and bench). Twelve mutations of the new loop and driver each fail
tests. Not run for A2: `./release.sh` (required at A4). `release.sh` rewrites
`baremetal/baremetal.elf` and its debug listing with debug-string noise only; restore them rather
than committing.

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
