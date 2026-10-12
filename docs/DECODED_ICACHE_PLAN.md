# Decoded instruction cache: agreed plan and next steps

Agreed 2026-10-10 by Nate, V-32 and the CPU agent. Status: **complete; merged to `main` as
`0.1.8-SNAPSHOT` on 2026-10-11.**
- **V-32 trial** ([handoff](archive/V32_DECODED_CACHE_TRIAL.md)): 4K AP slots cut headless
  boot time by 36–44% on JVM and 38–42% on native.
- **Decision:** V-32 recommended merging
  ([feedback](V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md)) and Nate approved.
- **Still with V-32:** production enablement.

The evidence is in
[performance/2026-10-10/decoded-cache](performance/2026-10-10/decoded-cache/README.md).

**Where the implementation departs from the design below, and why** (measured on native):

- **`step()` keeps its loop.** The first candidate (moving it into a private method) and any
  inner loop inlined into the interpreter loop stop Native Image from unswitching it, which cost
  the cache-off path 3–15%.
- **Delegation re-enters `step(count=1)` with a private mode that skips the entry checks.**
- **`FENCE.I` only sets a flag in `step()`'s loop; the flush runs outside both loops.**
- **F and A also run from the cache, through the shared `executeColdOpcode()`.** Delegating
  them made FP code slower than uncached.
- **Counters are one `DecodedCacheStats` record** (fills, bypasses, delegations, flushes,
  checks).

- [DECODED_ICACHE_PROTOCOL.md](DECODED_ICACHE_PROTOCOL.md) is the coherence contract, accepted
  by V-32.
- [CPU_REVIEW_FOLLOWUP_RESPONSE.md](archive/CPU_REVIEW_FOLLOWUP_RESPONSE.md) records the agreements,
  Nate's decisions and V-32's AP loop.
- History: the [scope draft](archive/DECODED_ICACHE_SCOPE_DRAFT_2026-10-10.md),
  [V-32's review](archive/DECODED_ICACHE_ADVISORY_REVIEW.md) and its
  [follow-up](archive/CPU_ARCHITECTURE_REVIEW_FOLLOWUP.md).

## What is being built

- **An opt-in, per-core, direct-mapped cache of decoded instructions.** The capacity is set by
  the embedder: 0 (off, the default) or a power of two. It is an instruction cache only, with no
  data cache, and it is invisible to the guest: no modelled timing.
- **Coherence is `FENCE.I` plus an embedder flush** (protocol Rules 1–4). There is no store
  snooping and no cross-thread invalidation. Every mapping change flushes fully.
- **Why it is bounded:** the [upper-bound experiment](PREDECODE_EXPERIMENT_PLAN.md)'s
  whole-window replica cost 6× the window per core. Here memory is fixed per hart: 16 B per slot,
  so 4K slots take 64 KiB.
- **No preset performance bar.** Nate judges the results before any merge, and V-32 judges its
  own adoption.

## Design

**API (names provisional):**

- **Construction:** `new RV32IMACore(IsaConfig, DecodedCacheConfig)`, where
  `DecodedCacheConfig(int slots, boolean checkMode)` validates `slots` as 0 or a power of two.
  The existing constructors keep the cache off. There is no resizing setter.
- **Cacheable ranges:** a call valid only between `step()` calls declares them, and it
  flushes. A fetch is cached only if the whole instruction lies in one declared range *and*
  in `step()`'s window. With no range declared, nothing is cached.
- **Flush:** `flushDecodedCache()`, valid only between `step()` calls on the core's thread, or
  while the hart is quiescent under the embedder's own happens-before. Its Javadoc states the
  threading rule from the protocol.
- **Counters for instrumented runs:** fills, flushes and check-mode comparisons, as plain
  `long` fields touched only off the hit path. Hits are attempts minus fills.

**Structure:**

- **Arrays:** parallel `int[] tags`, `int[] words` and `long[] ops`, indexed by
  `(pc >>> 1) & mask`, or `pc >>> 2` without C.
  - The tag is the PC with bit 0 set for M-mode; bit 0 is free because PCs are even.
  - `ops` holds the tier-2 encoding: handler, `rd`/`rs1`/`rs2`, compressed flag and immediate.
- **At `step()` entry (Rule 3):** compare the bus instance, `RV32IMAState` instance, hart ID,
  `ramOffset` and `ramSize` with the previous call, and flush on any difference. These are a
  few compares, which matters because V-32 calls `step(count=1)` for every AP instruction.
- **The uncached loop stays exactly as today.** `step()` chooses at entry between it and the
  cached path. The cached path must not become a second ISA implementation. **Candidate to try
  first:**
  1. Move today's loop and its trap tail unchanged into a private method that `step()` calls
     after its entry checks.
  2. The cached loop executes the hot fast handlers itself (tier 2's RV32I subset plus MUL, and
     `FENCE.I`, which flushes).
  3. For every other instruction it delegates exactly one instruction to that private loop,
     then ends the batch wherever today's loop would: on a trap, WFI or interrupt
     re-evaluation.

  Measure that moving the loop into a method leaves the default-off path unchanged on both
  runtimes. If it does not, try the alternatives and record why.
- **Fill only on an executed, successful fetch.** Faults and reserved encodings are never
  cached.
- **Fast handlers decode encoding fields only,** never conclusions that depend on privilege or
  CSR/FP state. Hook `ir`, instruction length, `mtval` and trap values must match the uncached
  path exactly, including the expanded word for compressed instructions.
- **Check mode:** every hit also fetches through the bus, exactly as the uncached path would,
  and compares the word. A mismatch throws `IllegalStateException` naming the PC and both
  words. It is for embedder tests that find missing `FENCE.I`s, not for timing.
- **`FENCE.I`:** flushes in the cached path. The uncached path keeps treating it as a no-op
  `FENCE`.

## Validation

- **The existing suite unchanged with the cache off,** plus a parameterized rerun with the
  cache on at 4 slots, to force conflicts, and at 4K.
- **A lockstep differential test:** cached and uncached cores on cloned state, comparing
  registers, PC, CSRs, `fregs` and memory after every instruction. It runs over the bench
  images and a seeded random instruction stream, including C, F, A, Zba/Zbb, CSR, traps and
  interrupts.
- **Stale-hit tests,** each warming an entry first:
  - revoke fetch permission, then fetch again
  - shrink or remap the window
  - change bus, state or hart
  - privilege change from M to U
  - write code without `FENCE.I`: the stale result is allowed, and check mode must throw
  - write code then `FENCE.I`: the new code runs
  - write through an alias, then `flushDecodedCache()`
  - an instruction straddling a range end is never cached
- **Hook parity:** a `PostExecHook` sees an identical (`pc`, `ir`, `trap`) sequence cached and
  uncached.
- **Gate:** `./mvnw clean verify`, 0 Checkstyle and 0 SpotBugs findings, and the packaged-CLI
  smoke test.

## Measurement

Use the existing bench discipline: pinned `taskset -c 2,3`, nothing else running, outputs
under `bench/out/`, and the reference built from `main` in the same session.

- **Default-off overhead:** branch against `main` on all existing cells, JVM and native.
- **Sizing:** 256, 1K, 4K and 16K slots, against `main` and against the archived whole-window
  replica (`exp/predecode`, tier 2) as the ceiling. Images: `jvm`, `mono`, `poly` and
  `poly-o3`, on `bytearray` and `router`.
- **Workloads:**
  - the existing nine
  - **new:** `spread-ap`, mixed 16/32-bit code distributed over 64 KiB and called in a seeded
    order
  - **new:** `collide-ap`, hot branch targets spaced at multiples of the cache span

  Label results as CoreMark and synthetic; V-32 has no representative workload yet.
- **Report:** speedup, cold against warm runs, fill and miss rates from a separate instrumented
  run, host bytes per configuration, and native image size with the cache code compiled in.

## Next steps, in order

1. **Branch** `feature/decoded-icache` from `main`. Never push `exp/predecode`.
2. **Implement** the API, the cached path, check mode and the counters, with Javadoc stating
   the protocol. Add `CHANGELOG.md` entries and update `API.md`.
3. **Validate** with the tests above and the full gate.
4. **Measure** default-off overhead and the sizing matrix, and add the evidence under
   `docs/performance/<date>/`.
5. **Report to Nate.** He decides on merging and gives no preset bar.
6. **Cut a V-32 trial build** once Nate is satisfied with the branch:
   1. On the branch only, set a distinct version so V-32's normal `0.1.7-SNAPSHOT` is not
      replaced:
      `./mvnw versions:set -DnewVersion=0.1.8-icache-SNAPSHOT -DgenerateBackupPoms=false`.
   2. Run `./mvnw install`.
   3. Write a short trial handoff for V-32. It should cover enabling the cache on the AP
      core, declaring AP RAM as the cacheable range, and flushing at launch, reset and remap.
      Run one check-mode boot first, then matched boot timing against `0.1.7-SNAPSHOT`.
   4. Expect a small boot effect: V-32's AP calls `step(count=1)` per instruction under the
      `syscon` monitor, and spins on WFI (see the
      [addendum](archive/CPU_REVIEW_FOLLOWUP_RESPONSE.md#addendum-the-aps-wfi-and-attempt-loop-read-2026-10-10)).
      A cache cannot remove that overhead.
7. **After Nate's decision:** merge to `main` with the version bump per `AGENTS.md`. V-32 then
   adopts on its own schedule, after wiring its flush boundaries and firmware `FENCE.I`.
