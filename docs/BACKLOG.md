# Backlog

Work that is blocked, waiting on someone, or deferred. Current work and its next step live in
[../CHECKPOINT.md](../CHECKPOINT.md); release items live in [../RELEASE_TODO.md](../RELEASE_TODO.md).
Remove an item when it is done, and note where its result went.

## Waiting on Nate

- **Dependabot branches:** two on `origin` propose Maven 3.10.0 and Maven Wrapper 3.3.4. Shared
  tooling versions belong in `../alienspacebunny-build`, so decide there. (Stale merged branches
  may stay; Nate, 2026-10-12. The `v0.1.8` tag was pushed the same day.)
- **API freeze and publishing:** see [RELEASE_TODO.md](../RELEASE_TODO.md). The timer/counter and
  execution API work changes the public API first.

## Waiting on V-32

- **Archive [CPU_ARCHITECTURE_REVIEW_RESPONSE.md](CPU_ARCHITECTURE_REVIEW_RESPONSE.md)** once V-32
  acknowledges it.
- **Decoded cache in production** is V-32's change. Its trial: 4K AP slots cut boot time by
  36–44% on JVM and 38–42% on native.
- **Boot attribution** is V-32's call.

## Deferred

- **Remaining old shortcuts.** Following Nate's 2026-10-12 direction (V-32's needs plus affordable
  spec fidelity, no legacy shortcuts), audit what the timer/counter design does not cover:
  - `IsaConfig.hasU = false` reproduces the non-standard `misa` bit 22, with compatibility
    constructors to keep it;
  - `mvendorid` returns `0xff0ff0ff`;
  - `mstatus` and other CSRs accept any written value instead of their WARL fields. One visible
    effect, seen in T2's differential: MRET with `mstatus.MPP` = 1 or 2 runs the hart at that
    unmodeled privilege instead of a legal one.

  Check each for a V-32 dependency first. Start after the execution API phase.
- **D extension:** only the `misa` flag exists; no decode.
- **Linux compatibility (after the V-32 project):** reassess whether to support booting a Linux
  image, as upstream mini-rv32ima does. This repository has never had one (Nate, 2026-10-12).
- **Archive completed cache-era exchanges:**
  - [DECODED_ICACHE_ADVISORY_REVIEW.md](DECODED_ICACHE_ADVISORY_REVIEW.md);
  - [CPU_ARCHITECTURE_REVIEW_FOLLOWUP.md](CPU_ARCHITECTURE_REVIEW_FOLLOWUP.md);
  - [CPU_REVIEW_FOLLOWUP_RESPONSE.md](CPU_REVIEW_FOLLOWUP_RESPONSE.md).

  Fix links from the decoded-cache plan when moving them.
