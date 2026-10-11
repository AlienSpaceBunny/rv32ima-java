# Session Checkpoint — 2026-10-10

## Current state

`main` is on **`0.1.7-SNAPSHOT`** and carries the CPU architecture review work, fast-forwarded
from local branch `claude-review`, which can now be deleted. The public API remains unfrozen
and publishing is on hold. The first real release target is **`0.2.0`**; no release is
authorized. CPU feature Phases 1–5 are complete; see
[FEATURE_REQUEST_PLAN.md](docs/FEATURE_REQUEST_PLAN.md) and the
[checkpoint archive](docs/archive/CHECKPOINT_PRE_COMPACTION_2026-10-06.md).

**V-32's CPU architecture review is answered.** The response is
[CPU_ARCHITECTURE_REVIEW_RESPONSE.md](docs/CPU_ARCHITECTURE_REVIEW_RESPONSE.md); the evidence
is in [performance/2026-10-10](docs/performance/2026-10-10/README.md). It stays active until
V-32 acknowledges it; then archive it. Measurements use the unpublished
[`bench`](bench/README.md) module, pinned with `taskset -c 2,3`, with outputs in the git-ignored
`bench/out/`.

**Predecode experiment: complete.** Following the [plan](docs/PREDECODE_EXPERIMENT_PLAN.md),
the decision rule's top band was met. CoreMark's C build ran 1.46–2.04× faster natively with
cached fetched words (tier 1) and 1.84–2.51× with operand predecode (tier 2), and 1.30–1.50×
on HotSpot. The code is on the local-only branch `exp/predecode` (`04fdf54`, `077c90b`), never
pushed, with patches beside the evidence.

## Resume / pending

1. **V-32 advisory review of [DECODED_ICACHE_PLAN.md](docs/DECODED_ICACHE_PLAN.md)** (Nate is
   arranging it). It replaces the whole-window replica, which Nate declined to ratify, with an
   optional, bounded, embedder-sized decoded instruction cache. **Do not build or measure until
   the review is settled.** V-32's initial review is in
   [DECODED_ICACHE_ADVISORY_REVIEW.md](docs/DECODED_ICACHE_ADVISORY_REVIEW.md), copied from
   `../emulator` at `351dbc4`, and is not yet folded into the plan. V-32 is also running a
   performance test of its own; check `../emulator` for later edits or results. The response to
   V-32 now includes the predecode result and points to the plan.
2. **After the review:** the plan's sizing experiment on `exp/predecode`, then decide on a real
   implementation.
3. **Unchanged, carried over:**
   - The release-hold decision and API-freeze review ([RELEASE_TODO.md](RELEASE_TODO.md)).
   - The Central smoke test.
   - Never start the release workflow without Nate's explicit request. D stays deferred.

**Validation status:** `./mvnw clean verify` is green at the `0.1.7-SNAPSHOT` bump (535 core,
1 CLI and 4 bench tests; 0 Checkstyle and 0 SpotBugs findings), and `./mvnw install` has run.

## Constraints for future work

- Follow [AGENTS.md](AGENTS.md) for gates, changelog, version bumps, local install,
  archiving, and commit/push requirements. Shared formatter/tooling versions belong
  in `../alienspacebunny-build`; this repo consumes its published parent.
- Core stays independent of CLI and of any memory backend. `step()`'s long body and
  eight-argument signature are deliberate; cold F/A arms live in `executeColdOpcode`.
  Measure hot-path changes with `bench/` on JVM and native. Trap dispatch uses an
  internal cause-plus-one encoding.
- [API.md](docs/API.md) summarizes contracts; Javadoc is authoritative. Multi-hart
  buses own translated-address reservation coordination, atomicity, sub-word lock
  granularity, and ordering stronger than the single-hart defaults. `aq`/`rl` is
  outside `AccessContext`'s scope. Cross-thread interrupt injection requires caller
  synchronization.
- F directed rounding needs the exact residual alongside the double approximation;
  a double result alone loses boundary information. Preserve independent
  differential tests when changing that arithmetic.
