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

1. **Hand the response to V-32** (Nate). It now includes the predecode result.
2. **Decide whether to scope a real predecode design** (Nate). Its requirements are listed in the
   plan: opt-in, invalidation, fault fidelity, bounded memory and differential testing. The
   uncached loop must stay untouched; the disabled experiment code cost up to 21% natively.
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
