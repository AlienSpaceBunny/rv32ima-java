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

1. **Decoded instruction cache: steps 1–4 are done on branch `feature/decoded-icache`
   (`d8e55aa` plus docs, pushed); step 5, Nate's review, is next.** Follow
   [DECODED_ICACHE_PLAN.md](docs/DECODED_ICACHE_PLAN.md), whose status block lists where the build
   departs from the design. Evidence:
   [performance/2026-10-10/decoded-cache](docs/performance/2026-10-10/decoded-cache/README.md).
   - **Cache off vs `main`:** 0.98–1.01× per-workload median on both runtimes;
     `step(count=1)` is +4–9% on closed-world `mono` images.
   - **Cache on (4K slots) vs `main`:** CoreMark 1.65–2.17× native and 1.47–1.50× HotSpot.
     `step(count=1)` is 1.26–1.77× native but **0.82× on HotSpot**. Misses cost more than no
     cache (`collide-ap` 0.63–0.91×).
   - **For Nate's decision:** merge or not; whether HotSpot `step(count=1)` matters for V-32;
     the existing `PostExecHook` quirk (a taken jump reports target − length, against its
     Javadoc), which the cache reproduces and which is not fixed.
   - **After Nate is satisfied:** plan step 6, the V-32 trial build `0.1.8-icache-SNAPSHOT`,
     on the branch only.
   - **Native Image rule learned:** keep inner loops (array fills, flushes) out of interpreter
     loops; they stop loop unswitching. `bench/out/symdiag/` has the symbolized builds.
   - **Housekeeping:** `exp/predecode` stays local. Local branch `claude-review` can be deleted.
2. **Optional, not blocking:** boot attribution, which is V-32's call. `getCycle()` counts
   instructions *attempted*, including trapping ones, not retired instructions.
3. **Unchanged, carried over:**
   - The release-hold decision and API-freeze review ([RELEASE_TODO.md](RELEASE_TODO.md)).
   - The Central smoke test.
   - Never start the release workflow without Nate's explicit request. D stays deferred.

**Validation status:** on `feature/decoded-icache`, `./mvnw clean verify` is green: the core
suite runs three times (uncached, and every core cached at 4 and 4096 slots), 570 tests each,
plus 1 CLI and 6 bench tests, with 0 Checkstyle and 0 SpotBugs findings. `main` is unchanged at
`0.1.7-SNAPSHOT`; no version bump until the merge.

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
