# Step A2: the cached attempt path (2026-10-12)

Evidence for step A2 of [the plan](../../../TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md). It
records why the landed cached path is a separate attempt loop, the
[design](../../../archive/EXECUTION_API_DESIGN_REV6_WITH_REVIEWS_2026-10-12.md#implementation)'s named fallback, and not a mode of
`runCached()`. **One decision is Nate's:** the separate loop copies `runCached()`'s fast
handlers. Variant B below avoids the copy and gives up most of the attempt speedup.

These are screens, not A3's measurement: one image (`mono-bytearray`), nine workloads, two forks.

## Summary

Four cores, each with the bench's new `--attempts` mode, so `stepAttempts` is reachable:

| Variant | Cached `stepAttempts` vs A1, native | HotSpot | Batched cached `step` vs A1, native |
| --- | --- | --- | --- |
| A1: each attempt a cached one-instruction `step` (`83308f3`) | 1 | 1 | 1 |
| **Fused:** attempt mode as a flag in `runCached()` (the design) | 1.36–1.58× | 1.40–1.69× | **0.86–0.93×** |
| B: a driver calling the batched `runCached(count=1)` per attempt | 1.08–1.17× | 1.07–1.11× | 1.00–1.02× |
| **C: a separate attempt loop (landed)** | **1.41–1.67×** | 1.39–1.65× | 0.96–1.04× (placement; see below) |

Medians at budget 1,061, pinned to `taskset -c 2,3`. Full tables:
[`comparison-native.txt`](comparison-native.txt), [`comparison-jvm.txt`](comparison-jvm.txt).

- **The fused loop slows batches on native.** Native Image does not unswitch `runCached()` on the
  loop-invariant flag, so every batched iteration also carries the attempt-mode code. In the
  symbolized image, `sampleTime` and `pendingInterruptTrap` have two call sites each, like every
  other call in the duplicated loop, so both copies carry them. Batched integer and CoreMark workloads lose 7–14%, and cached `step(count=1)` 3%.
- **Before the bench reached `stepAttempts`, the cost was hidden.** With `stepAttempts`
  unreachable, Native Image folded attempt mode away; the batched loop matched T7 within 2%.
  V-32's images reach both methods, as every bench image now does.
- **C keeps the batched loop's code.** Its `runCached()` is the same instruction stream as A1's,
  except for two field offsets moved by the new fields ([`unswitching.txt`](unswitching.txt)). Its
  2–5% batched dip on the integer and CoreMark workloads therefore comes from code placement (the function
  starts at a different 64-byte alignment), not from code. C is also the fastest attempt path on
  native: it carries no batched code.
- **On HotSpot** C and the fused loop match on attempts, and batched differences sit inside the
  JVM's usual spread. A process uses one mode here; V-32 runs both in one process, which may
  change C2's profiles for the fused loop (not measured).
- **Uncached `stepAttempts`** is unchanged in every variant (`att-off` rows). The exceptions are
  B's 1–4% on HotSpot, inside the spread, and native `mix-ap` at 1.06× in every variant, which is
  placement.
- **A1's cached attempts were slower than uncached on HotSpot** (103 vs 114 MIPS on `reg-ap`), as
  the cache work found for cached `step(count=1)`. C runs cached attempts at 1.46× uncached there.

## The decision for Nate

C copies `runCached()`'s lookup, delegation and fast handlers into `runCachedAttempts()`, about
200 of its 250 lines. Both Javadocs point at the other. The differential tests run both loops
against the reference, and mutating one copied handler (`SLTU`, `BGEU`) fails them. The
alternatives:

- **B:** no copy, batched code untouched, but only 1.08–1.17× on cached attempts
  ([`variant-b.patch`](variant-b.patch), against A1's `RV32IMACore.java`).
- **The fused loop:** no copy, the fastest HotSpot attempts, but a 7–14% native batch regression
  ([`variant-fused.patch`](variant-fused.patch)).
- A shared fast-handler method that both loops call was not tried. Native Image would have to
  inline a method of that size into both loops. The decoded-cache work found that moving the batch
  loop into a separate method cost 3–4%.

## Correctness evidence

All variants pass the same tests: the full gate for the fused loop and C, and the attempt and
differential suites for B, each uncached and cached at 4 and 4096 slots.

- New in `ReferenceDifferentialTest`:
  - `stepAttempts` against the reference's attempt driver. Random programs (eight seeds, five ISA
    configurations, half in check mode) and the nine directed programs run with random budgets,
    including 1,061, V-32's current (legacy) budget. The source advances on every read.
  - The seeded lockstep against repeated `step(count=1)` on a second core, with a source fixed
    during each call.
  - Random controllers record every boundary and edit the machine. Edits include `mip` injection
    and acknowledgement, a direct `MTIP` edit, registers, privilege, `mstatus`, `mie`, the
    compare, all four counters, pc, the WFI flag, both reservation layers and a cache flush. They
    stop at random.
- Twelve mutations of C's loop and driver fail between 1 and 49 tests each: sampling every attempt
  or on the `FENCE.I` resume; dropping the pc, cycle or retired-count reload after callbacks; a
  trap or fetch fault ending the call; no `MTIP` recomputation; `afterAttempt` before the flush;
  a second `afterAttempt` on resume; and the two handler mutations above.
- The delegated `time` read retained across a `FENCE.I` resume, with MTIP checked, is
  `StepAttemptsTest.timeReadSampleIsKeptByLaterAttempts`, unchanged since A1.

## Method

- **Host and toolchain:** as in [the timer evidence](../timer-counters/README.md#method):
  i9-13950HX, `taskset -c 2,3`, Corretto 25.0.4.1, GraalVM CE 25.3.4.1, O2,
  `-march=compatibility`. Nothing else ran during the screens.
- **Images:** `mono-bytearray` with `-H:-DeleteLocalSymbols`, one per variant, each from a bench
  jar with the `--attempts` mode. Built sequentially before the screens.
- **Screens:** default warmup (at least 10 s and 3 rounds), 3 measured rounds, two forks (the
  second with `--reverse`), so 6 samples per cell. Workloads: `reg-ap`, `regc-ap`,
  `reg-ap-step1`, `mix-ap`, `copy4k-ap`, `fp-ap`, `coremark-c`, `coremark-noc` (native only) and
  `host-read6m` (a core-free control). Modes: `--decoded-cache 4096` (batched), plus
  `--attempts 1061` with and without the cache. Screen 2 reran A1 and the fused loop alongside C.
- **Code shape:** `nm -S` sizes and `ByteArrayBus.readInt` call sites per method, plus call
  targets per method from `objdump`.
- **Jars** (SHA-256): A1 `8c5396c6…`, B `2e122ca1…`, C `6415c0b3…`, fused `90868358…`, all on
  `0.1.10-SNAPSHOT`. The landed C differs from the measured jar only in comments.

Raw CSVs are in the git-ignored `bench/out/a2-variants/`.
