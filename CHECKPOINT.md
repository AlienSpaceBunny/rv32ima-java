# Session Checkpoint — 2026-10-10

## Current state

Work is on local branch **`claude-review`**, not pushed. It was branched from `main` at
`f745790` on 2026-10-10 at 00:07, before the first commit; `main` and `origin/main` are
untouched. The version is still **`0.1.6-SNAPSHOT`**. The public API remains
unfrozen and publishing is on hold. The first real release target is **`0.2.0`**; no
release is authorized. CPU feature Phases 1–5 are complete; see
[FEATURE_REQUEST_PLAN.md](docs/FEATURE_REQUEST_PLAN.md) and the
[checkpoint archive](docs/archive/CHECKPOINT_PRE_COMPACTION_2026-10-06.md).

**In progress: V-32's CPU architecture / native-performance review**
([handoff](docs/archive/CPU_ARCHITECTURE_REVIEW_HANDOFF.md)). The response is
[CPU_ARCHITECTURE_REVIEW_RESPONSE.md](docs/CPU_ARCHITECTURE_REVIEW_RESPONSE.md); the evidence
is in [performance/2026-10-10](docs/performance/2026-10-10/README.md). Measurements use the
new unpublished [`bench`](bench/README.md) module: pinned `taskset -c 2,3`, outputs in the
git-ignored `bench/out/`.

Landed locally, all with green gates:

| Commit | Change |
| --- | --- |
| `9bdccaf` | bench module |
| `0eff01b` | `MMIOBus` covering-range lookup |
| `b5eecfd` | context semantics tests |
| `a7d7b40` | C3a: base-first ALU decode |
| `9baaafd` | C3b: cold F/A arms out of `step()`'s loop |
| `4c22d8b` | `fp-ap`, CoreMark and FP-kernel images |
| `ffb57c8` | C4: compressed expansion table |

Rejected and reverted: C1 (cached `AccessContext`s) and C5 (allocation-free FP rounding).
Result: CoreMark and integer work is 18–39% faster native and 28–34% faster on HotSpot, with
a further 10–16% for C-heavy code. FP is unchanged natively.

Decisions this session (Nate):

- C3b landed despite missing the 10% gate on its own.
- PGO and Oracle GraalVM are excluded; proprietary licence risk.
- Native targets: `-march=native` dropped; `x86-64-v2`/`v3` measured and gave no gain. The
  oldest test machines are a Ryzen 2700X and an i7-8650U, both v3.
- The core stays backend-agnostic; FFM is not the preferred V-32 backend.

## Resume / pending

1. **Predecode upper-bound experiment, before handing back to V-32.** Follow the
   [plan](docs/PREDECODE_EXPERIMENT_PLAN.md). Tier-1 code is drafted on branch
   `exp/predecode` at `04fdf54`: correctness-smoked, not measured. The plan's status note gives the
   run commands. Run tier 1, then tier 2, each with
   one matrix against a fresh `HEAD` reference. Fold the result into
   [the response](docs/CPU_ARCHITECTURE_REVIEW_RESPONSE.md) (its predecode bullet says
   "Result pending") and the evidence README.
2. **Finish the task:**
   - bump to `0.1.7-SNAPSHOT`
     (`./mvnw versions:set -DnewVersion=0.1.7-SNAPSHOT -DgenerateBackupPoms=false`) and update
     `docs/RELEASING.md`
   - run `./mvnw clean verify`, then `./mvnw install -DskipTests`
   - bring `claude-review` into `main` (fast-forward) and push; never push `exp/predecode`
   - hand the response to V-32, which keeps it active until acknowledged
3. **Unchanged, carried over:**
   - The release-hold decision and API-freeze review ([RELEASE_TODO.md](RELEASE_TODO.md)).
   - The Central smoke test.
   - Never start the release workflow without Nate's explicit request. D stays deferred.

**Validation status:** `./mvnw clean verify` is green at `ffb57c8` (535 core, 1 CLI and
4 bench tests; 0 Checkstyle and 0 SpotBugs findings). Bench evidence is copied into
`docs/performance/2026-10-10/`. Working outputs remain in the git-ignored `bench/out/`.

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
