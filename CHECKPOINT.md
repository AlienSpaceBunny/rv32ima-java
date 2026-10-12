# Session Checkpoint — 2026-10-11

## Current state

- **Release:** `v0.1.8` is the first local tagged release, cut on 2026-10-11.
  - It is installed in `~/.m2` (`rv32emu-core` `0.1.8`) and validated with `release.sh`,
    including the baremetal run.
  - It is not published, and its CLI jar is in `target/checkout/`.
  - `main` is on **`0.1.9-SNAPSHOT`**.
- **Tag push pending Nate's OK:** `main` is on `origin`, but the `v0.1.8` tag exists only
  locally. Push it with `git push origin v0.1.8`.
- **What the release contains:** the CPU architecture review work and the opt-in decoded
  instruction cache. See `CHANGELOG.md` `[0.1.8]`.
- **API and publishing:** the public API is unfrozen and unstable. V-32 is its only consumer.
  Publishing stays on hold until `0.2.0`. Never start the GitHub release workflow without Nate's
  explicit request.
- **Working with V-32:** Codex works on V-32 (`../emulator`); Claude works on this repo. Nate
  relays between them. V-32's current calls into the core are not the target; the exchanges
  define how V-32 should call it.
  - Exchanges are files in this repo's `docs/`. Each side appends dated sections, and either
    agent can read the other repo directly.
  - When a reply settles, replace absolute or `../../emulator` links with plain V-32 repository
    paths, commit it, and index it in `docs/README.md`.

## Resume / pending

1. **Execution API and timer/counter designs: both at revision 5, awaiting V-32's review.**
   - **Direction (Nate, 2026-10-12):** design only for V-32's needs and, where cheap, the RISC-V
     spec. No legacy mode; old shortcuts are dropped. Bench regressions go to Nate as tradeoffs.
     V-32 has Nate's direction verbatim and accepts temporary breakage during migration.
   - [TIMER_COUNTER_DESIGN.md](docs/TIMER_COUNTER_DESIGN.md) lands first: one shared time source
     replaces `elapsedUs`; standard compare and WFI; WARL `mie`/`mip`; Zicntr always on; read-only
     CSR numbers trap on write; work counts separate from guest-writable counters;
     `step(count <= 0)` rejected. Two questions (device-level timer mask, firmware `mip` writes).
   - [EXECUTION_API_DESIGN.md](docs/EXECUTION_API_DESIGN.md) builds on it: one sample per call on
     every path via an internal single-attempt entry; no hook overload; timer version as the
     fusion baseline. One question (uncached sampling).
   - Nate relays both to Codex. V-32's reply is design only; it does not authorize
     implementation.
   - **After V-32 answers:** settle each design and get Nate's go. Then:
     - Execution API: implement on a branch, port the oracle with V-32's frozen legacy
       interpreter as the independent reference, run bench `--attempts`, and cut
       `0.1.9-attempts-SNAPSHOT` for V-32 to time with its real callbacks.
     - Timer/counters: implement behind source mode and an `IsaConfig` Zicntr flag, with
       legacy mode unchanged.
   - **Native Image rule:** keep inner loops (array fills, flushes) out of interpreter loops;
     they stop loop unswitching. Verify with a symbolized build, as in
     [the cache evidence](docs/performance/2026-10-10/decoded-cache/README.md).
2. **Decoded instruction cache: done and released in `0.1.8`.**
   - [Plan](docs/DECODED_ICACHE_PLAN.md) and [protocol](docs/DECODED_ICACHE_PROTOCOL.md).
   - V-32's trial: 4K AP slots cut boot time by 36–44% on JVM and 38–42% on native.
   - Production enablement is V-32's change.
3. **`PostExecHook` jump-PC quirk:** fix it as a separate change (report the instruction's own PC,
   compressed forms included). V-32 found no dependency on it.
4. **Housekeeping:**
   - Local branches that can be deleted: `feature/decoded-icache` (already gone from `origin`)
     and `claude-review`.
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
