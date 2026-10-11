# Session Checkpoint — 2026-10-11

## Current state

- **Release:** `v0.1.8` is the first local tagged release, cut on 2026-10-11.
  - It is installed in `~/.m2` (`rv32emu-core` `0.1.8`) and validated with `release.sh`,
    including the baremetal run.
  - It is not published, and its CLI jar is in `target/checkout/`.
  - `main` is on **`0.1.9-SNAPSHOT`**.
- **Push pending Nate's OK:** the changelog commit, both release-plugin commits, this checkpoint
  and the `v0.1.8` tag exist only locally. Push with `git push origin main --follow-tags`.
- **What the release contains:** the CPU architecture review work and the opt-in decoded
  instruction cache. See `CHANGELOG.md` `[0.1.8]`.
- **API and publishing:** the public API is unfrozen and unstable. V-32 is its only consumer.
  Publishing stays on hold until `0.2.0`. Never start the GitHub release workflow without Nate's
  explicit request.
- **Working with V-32:** Codex works on V-32 (`../emulator`); Claude works on this repo. Nate
  relays between them.
  - Exchanges are files in this repo's `docs/`. Each side appends dated sections, and either
    agent can read the other repo directly.
  - When a reply settles, replace absolute or `../../emulator` links with plain V-32 repository
    paths, commit it, and index it in `docs/README.md`.

## Resume / pending

1. **Batched execution API: the draft is waiting for V-32's review.**
   - The draft is [EXECUTION_API_DESIGN.md](docs/EXECUTION_API_DESIGN.md). Nate is to send it
     to Codex, which answers its five questions by appending to the same file.
   - **After V-32 answers:**
     - Revise the design.
     - Get Nate's go.
     - Implement it on a branch.
     - Port V-32's committed-attempt oracle: 36 cases with the cache off, on and in check mode.
     - Run the bench `--attempts` mode.
     - Cut a trial build for V-32 to time with its real callbacks.
   - **Requirements:**
     [V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md](docs/V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md)
     (R1–R6), with background in [V32_AP_STEPPING_RESPONSE.md](docs/V32_AP_STEPPING_RESPONSE.md).
   - **Proposed approach:**
     - Keep the oracle's `stepAttempts`, `AttemptController` and `AttemptResult`.
     - With the cache on, fuse the attempts into `runCached()`'s loop.
     - With the cache off, use the reference wrapper, and leave `step()`'s loop untouched.
   - **Native Image rule:** keep inner loops (array fills, flushes) out of interpreter loops;
     they stop loop unswitching. Verify with a symbolized build, as in
     [the cache evidence](docs/performance/2026-10-10/decoded-cache/README.md).
2. **Decoded instruction cache: done and released in `0.1.8`.**
   - [Plan](docs/DECODED_ICACHE_PLAN.md) and [protocol](docs/DECODED_ICACHE_PROTOCOL.md).
   - V-32's trial: 4K AP slots cut boot time by 36–44% on JVM and 38–42% on native.
   - Production enablement is V-32's change.
3. **For Nate's decision, not blocking:** the existing `PostExecHook` quirk. For a taken jump
   or branch, the hook reports the target minus the instruction length, not the instruction's
   PC. It is not fixed, and the new API should not inherit it.
4. **Housekeeping:**
   - Branches that can be deleted: `feature/decoded-icache`, both local and on `origin`, and
     local `claude-review`.
   - `exp/predecode` stays local and is never pushed.
   - Archive [CPU_ARCHITECTURE_REVIEW_RESPONSE.md](docs/CPU_ARCHITECTURE_REVIEW_RESPONSE.md)
     once V-32 acknowledges it.
5. **Carried over:**
   - The API-freeze review and the Central smoke test ([RELEASE_TODO.md](RELEASE_TODO.md)).
   - Boot attribution is V-32's call. `getCycle()` counts instructions *attempted*, not
     retired.
   - D stays deferred.

**Validation status:**
- The `v0.1.8` tag passed `release:perform`: the core suite three times (uncached, and cached
  at 4 and 4096 slots), 570 tests each, plus 1 CLI and 6 bench tests.
- `release.sh` passed on that tag.
- `main` at `0.1.9-SNAPSHOT`: `./mvnw install` with the full gate is green, and the build is
  installed.

## Constraints for future work

- Follow [AGENTS.md](AGENTS.md) for gates, the changelog, version bumps, local install,
  archiving and commit/push.
  - Shared tooling versions belong in `../alienspacebunny-build`.
  - Local releases follow [RELEASING.md](docs/RELEASING.md): changelog commit, dry run, then
    `release:prepare` and `release:perform`, then `release.sh` in `target/checkout`.
- **Benchmarks:** measure hot-path changes with [`bench/`](bench/README.md) on JVM and native.
  - Pin to `taskset -c 2,3`.
  - Run nothing else at the same time.
  - Outputs go in the git-ignored `bench/out/`.
- **Core design:** the core stays independent of the CLI and of any memory backend.
  - `step()`'s long body and eight-argument signature are deliberate.
  - Cold F/A arms live in `executeColdOpcode`.
  - Trap dispatch uses an internal cause-plus-one encoding.
- **Docs:** [API.md](docs/API.md) summarizes the contracts; the Javadoc is authoritative.
  - Multi-hart buses own reservation coordination, atomicity and ordering beyond the
    single-hart defaults.
  - Cross-thread interrupt injection requires caller synchronization.
- **F directed rounding** needs the exact residual alongside the double approximation. Keep the
  independent differential tests when changing that arithmetic.
- **Prior work:**
  - [Feature plan](docs/FEATURE_REQUEST_PLAN.md) (Phases 1–5 complete).
  - [Predecode experiment](docs/PREDECODE_EXPERIMENT_PLAN.md) (complete; code on local
    `exp/predecode`).
  - [Checkpoint archive](docs/archive/CHECKPOINT_PRE_COMPACTION_2026-10-06.md).
