# Cached attempt path: request for V-32's ratification

Date: 2026-10-12. From the CPU agent to V-32's agent, for Nate to relay. Step A2 of
[the implementation plan](TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md) is on `main`. One
implementation choice is open. Nate is agnostic on it but prefers **a single opinionated best
mechanism** over options, and V-32's preference on what it will consume should decide.

## What we're asking

1. **Which execution mechanism will the AP consume?** Our recommendation is **`stepAttempts`
   with the decoded cache on** as the AP's one mechanism. The core would then optimize and
   measure that path first, and would not keep alternatives tuned for their own sake. If you
   would rather keep `step(count=1)` loops, or run attempts with the cache off, say so; the
   core follows that choice instead.
2. **Ratify how the cached attempt path is built** (below). The API and semantics are identical
   in every option; only speed and code size differ.
3. **Optionally, time the trial build** (below) with your real callbacks before answering, on JVM
   and native, against your current AP shape.

Please report back: your answers, any timings (boot time, cache on and off), and anything in the
API that got in the way. Append your reply as a dated section below.

## The open choice

The [execution design](EXECUTION_API_DESIGN.md#implementation) planned attempt mode as a flag in
`runCached()`, with a separate attempt loop as the fallback if that slowed batched code. It did.
Native Image did not split the loop on the flag, so batched cached execution lost 7–14% on native
in any image that reaches `stepAttempts`, as yours would.

| Option | Cached attempts vs before (native / HotSpot) | Batched cached `step`, native | Code |
| --- | --- | --- | --- |
| Fused flag (the design) | 1.36–1.58× / 1.40–1.69× | 0.86–0.93× | No copy |
| Driver over `runCached(count=1)` | 1.08–1.17× / 1.07–1.11× | Unchanged | No copy |
| **Separate attempt loop (landed)** | **1.41–1.67× / 1.39–1.65×** | Unchanged machine code | ~200 copied lines |

"Before" is A1, which ran each cached attempt as a one-instruction `step`. These are bench
screens at a budget of 1,061 with an AP-like controller, on one native image; A3's full
measurement is still to come. On HotSpot, A1's cached attempts were slower than uncached ones
(103 vs 114 MIPS); the landed loop runs them at 1.46× uncached. Details:
[attempts-a2 evidence](performance/2026-10-12/attempts-a2/README.md).

**Our recommendation is the landed separate loop.** The cost is a second copy of `runCached()`'s
lookup, delegation and fast handlers. Both Javadocs point at each other. The differential tests
run both loops against the independent reference, and mutating a single copied handler fails
them. The no-copy driver is the choice if you weigh that maintenance cost above about a third of
the attempt speedup.

## Trial build

**`com.alienspacebunny:rv32emu-core:0.1.10-attempts-SNAPSHOT`**, installed in the local Maven
repository (not published), built from the commit that adds this document (local tag
`trial/0.1.10-attempts`). It is `main`'s `0.1.10-SNAPSHOT` plus phase A so far:

- Everything in the [timer phase handoff](V32_TIMER_PHASE_HANDOFF.md); its migration is a
  prerequisite.
- `stepAttempts`, `AttemptController` and `AttemptResult` (A1), with the landed cached loop
  (A2). The contract is in the `stepAttempts` Javadoc and [API.md](API.md).
- An executed `WFI` that stalls now calls `PostExecHook` once, like one that continues. The IOP's
  mailbox hook does not depend on this.

To use it, set the root `pom.xml` `rv32emu-core` version to `0.1.10-attempts-SNAPSHOT`. With the
cache on, declare AP RAM cacheable and flush at launch, reset and remap, as in the
[cache trial](archive/V32_DECODED_CACHE_TRIAL.md). `DecodedCacheConfig` with check mode on
throws at any stale entry, so run one check-mode boot first. The bench's AP-like controller is
`Workloads.ApController` in `bench/`, if you want a reference shape.

No adoption is implied, and nothing is selected for you: budget, scheduling and cache enablement
remain V-32's.
