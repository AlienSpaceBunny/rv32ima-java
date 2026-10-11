# Session Checkpoint — 2026-10-11

## Current state

`main` is on **`0.1.8-SNAPSHOT`**. It carries the CPU architecture review work and the opt-in
decoded instruction cache. The public API remains unfrozen, and publishing is on hold. The
first real release target is **`0.2.0`**; no release is authorized.

CPU feature Phases 1–5 are complete; see [FEATURE_REQUEST_PLAN.md](docs/FEATURE_REQUEST_PLAN.md)
and the [checkpoint archive](docs/archive/CHECKPOINT_PRE_COMPACTION_2026-10-06.md).

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

1. **Batched execution API: draft written,
   [EXECUTION_API_DESIGN.md](docs/EXECUTION_API_DESIGN.md); awaiting V-32's review** through Nate,
   with five open questions. Implement only after V-32's answers and Nate's go.
   - **What it is:** one core call that runs many instructions while keeping each one's
     commit, interrupt and stop points.
   - **Requirements:**
     [V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md](docs/V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md),
     with background in [V32_AP_STEPPING_RESPONSE.md](docs/V32_AP_STEPPING_RESPONSE.md). They
     are:
     - owner-thread callbacks before and after each attempt;
     - a committed stop with no exceptions;
     - attempt and exit accounting;
     - defined timer semantics;
     - pacing and locks owned by the embedder;
     - no per-attempt allocation, and execution code shared by the cached and uncached paths.
   - **Starting contract:** V-32's committed-attempt oracle. Port its cases with the cache
     off, on and in check mode.
   - **Constraints:**
     - The core API is unstable and has no external consumers. Coordinate changes and V-32's
       migration directly.
     - Build it on `runCached()`. Keep inner loops out of interpreter loops; they stop Native
       Image's loop unswitching.
     - Evaluate it with V-32's real callbacks on JVM and native.
   - **Related:** the existing `PostExecHook` quirk, where a taken jump reports target minus
     instruction length. It is not fixed, and the new API should not inherit it.
2. **Decoded instruction cache: merged as `0.1.8-SNAPSHOT` (2026-10-11).**
   - [Plan](docs/DECODED_ICACHE_PLAN.md) and
     [measurements](docs/performance/2026-10-10/decoded-cache/README.md).
   - V-32's trial: 4K AP slots cut boot time 36–44% on JVM and 38–42% on native.
   - Production enablement is V-32's change.
   - Housekeeping: `exp/predecode` stays local. Branch `feature/decoded-icache` (also on
     `origin`) and local `claude-review` can be deleted.
3. **Optional, not blocking:** boot attribution, which is V-32's call. `getCycle()` counts
   instructions *attempted*, including trapping ones, not retired instructions.
4. **Unchanged, carried over:**
   - The release-hold decision and API-freeze review ([RELEASE_TODO.md](RELEASE_TODO.md)).
   - The Central smoke test.
   - Never start the release workflow without Nate's explicit request. D stays deferred.

**Validation status:** `main` at `0.1.8-SNAPSHOT`: `./mvnw clean install` is green. The
core suite runs three times (uncached, and every core cached at 4 and 4096 slots), 570 tests
each, plus 1 CLI and 6 bench tests, with 0 Checkstyle and 0 SpotBugs findings.

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
