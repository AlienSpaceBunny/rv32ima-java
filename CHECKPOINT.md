# Session Checkpoint — 2026-10-12

## Current state

- **`main` is on `0.1.9-SNAPSHOT`** and carries T1–T6, including breaking API changes that are
  not yet installed to `~/.m2` (T7's phase bump does that). `v0.1.8` is installed locally and
  tagged on `origin`, not published.
- **Work directly on `main`** and push after each validated step, until API stabilization for
  `0.2.0` (Nate, 2026-10-12). `feature/timer-counters` is merged and may stay.
- **Next work is approved:** implement the settled timer/counter design, then the execution API
  design, following [the implementation plan](docs/TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md).
  Both designs are at revision 6 and confirmed by V-32.
- **Blocked and deferred items** are in [docs/BACKLOG.md](docs/BACKLOG.md). The `v0.1.8` tag is
  pushed; stale branches may stay.

## Resume here

**Step T7 of [the plan](docs/TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md): timer phase close**, on
`main`. Not started: review the Javadoc, `docs/API.md` and the CHANGELOG as a whole; run the full
bench matrix against `0.1.8` on JVM and native plus a symbolized native build checking that
`step()`'s and `runCached()`'s loops are still unswitched; `./release.sh`; bump with
`versions:set`, `./mvnw install`, push; then write the V-32 handoff note.

Landed on `main`: T1 (hook jump-PC fix) at `3f4fdda`, T2 (reference core and differential) at
`08d26db`, T3 (shared time source, standard compare) at `fdd68f0`, T4 (standard WFI,
implemented-only `mie`/`mip`) at `525c925`, T5 (read-only CSR write traps) at `5abce6e`, and T6
(Zicntr counters and `time` reads, tests in `CounterTest` and the differential's `counters` and
`timeReads` cases) at `23bbc03`.

**For T7's bench:** T6 keeps one more `long` local (`retiredBase`) live across `step()`'s loop
and `runCached()`'s loop, used only on the CSR path and at exits; check it costs nothing
measurable. No Linux image is available locally, so only the baremetal image covers the "images
still run" check.

T4's WFI/`PostExecHook` question is settled for A1 (V-32's recommendation, in the plan's A1
step). V-32's [packets 001–003 review](docs/V32_PACKET_REVIEW_2026-10-12.md) needs nothing from
the core; its core-side notes are appended.

**Validation status:** `23bbc03` passed `./mvnw clean verify` (699 core tests in each of three
cache configurations, plus CLI and bench) and a freshly built baremetal run. Four mutations of
the core's counter code were each caught by the differential. No bench run yet; T7's full matrix
covers T1 and later steps (T3 adds one source read and compare per `step` call; T4–T6 change only
entry, CSR, WFI and exit paths).

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
