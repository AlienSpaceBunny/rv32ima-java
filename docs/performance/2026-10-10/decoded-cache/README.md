# Decoded instruction cache: measurements (2026-10-10)

Evidence for step 4 of [DECODED_ICACHE_PLAN.md](../../../DECODED_ICACHE_PLAN.md), on branch
`feature/decoded-icache`. **Nate decides on merging; there is no preset bar.** All workloads are
CoreMark, FP kernels or synthetic. V-32 has no representative workload yet.

## Summary

- **Default-off cost is gone, except for a small `step(count=1)` cost on closed-world images.**
  Branch `d8e55aa` with the cache off, against `main` (`fa29ddc`) over the full matrix:
  - Every per-workload median is 0.98–1.01× on native and on HotSpot.
  - `reg-ap-step1`, V-32's AP shape, is 1.04–1.09× on `mono` and 1.05× on `poly-v2`. It is
    0.97–1.03× on the other native images.
  - HotSpot outliers (up to 1.09×) sit inside the JVM's usual 5–50% sample spread.
- **Speedups with the cache on (4K slots, vs `main`).** These reach 80–94% of the archived
  whole-window tier-2 replica (the ceiling) on integer code, 82–92% on CoreMark and 96–106% on
  FP:

  | Workload | Native (`mono`/`poly`/`poly-o3`) | HotSpot |
  | --- | --- | --- |
  | CoreMark (C build) | 1.65–2.17× | 1.47–1.50× |
  | CoreMark (no C) | 1.50–1.83× | 1.50–1.51× |
  | Integer loops (`reg`, `copy`, `mix`) | 1.64–3.29× | 1.45–1.71× |
  | FP (`fp-ap`, `fpkernels`) | 1.07–1.82× | 1.12–1.23× |
  | `reg-ap-step1` | 1.26–1.77× | **0.82–0.83×** (slower) |

- **Size barely matters for CoreMark.** 256 slots (4 KiB of host memory) gets 91–95% of the 4K
  speedup, and 1K slots gets 97–100%. CoreMark's hit rate is 96.8% at 256 slots and 99.9% at 4K.
- **Misses cost more than no cache.** A miss fetches, predecodes and fills.
  - `collide-ap` (the adversarial case) runs at 0.63–0.91×.
  - `spread-ap` (64 KiB of hot code) runs at 0.69–0.98× until 16K slots cover it, then
    0.91–1.51×.
- **Cold cost is small.** Each invocation flushes, as the protocol requires on program load.
  CoreMark refills 1,825 entries per 2.58 M instructions at 4K (0.07%).
- **Native image size:** `poly` +128 KiB (+0.96%), `mono-bytearray` +64 KiB (+0.56%).
- **Host memory:** 16 B per slot: 4 KiB, 16 KiB, 64 KiB and 256 KiB for 256 to 16K slots.

## What changed along the way, and why

The first build (`bc37ec5`) followed the plan's first candidate: the loop and trap tail moved
into a private `runBatch()`.
- **That cost the cache-off path on native:** +3–4% median and up to +15% on `step(count=1)`;
  see [`round1/`](round1).
- **The cached path was slower on FP:** 0.71–0.93×, because F/A instructions were delegated and
  refetched.

Symbolized native builds ([`unswitching.txt`](unswitching.txt)) found the cause. On `main`,
Native Image unswitches `step()`'s loop: every bus call site appears twice. Any inner loop
inlined into the interpreter loop stops that. Here the inner loop was the array fill of
`flushDecodedCache()`, inlined from the FENCE arm or from the cached loop.

The landed shape (`e0249b6`, `d8e55aa`):

- **`step()` keeps its loop,** as on `main`. The FENCE arm only sets a flag.
- **The cached loop `runCached()` contains no inner loop.** After a delegated `FENCE.I` it returns,
  and `continueAfterFenceI()` flushes outside it and resumes the batch.
- **Delegation re-enters `step(count=1)`.** A private mode field makes it skip the entry checks,
  so the cache-off path pays one field test per call.
- **F and A run from the cache through the shared `executeColdOpcode()`.** No second
  implementation is needed for them.

[`screens/`](screens) holds the pinned two-image screens of each variant against `main`.

## Method

- **Host and toolchain:** as in [the CPU review evidence](../README.md#method): i9-13950HX,
  `taskset -c 2,3`, Corretto 25.0.4.1, GraalVM CE 25.3.4.1 at O2 unless labelled `-o3`.
- **Matrix runs:** two forks of 3 measured rounds after ≥10 s of warmup, so 6 samples per cell.
  Every invocation is verified. Nothing else ran during measurement.
- **Default-off:** `main` and the branch, cache off, over the full matrix on the original 14
  workloads. Branch builds run `--workloads` limited to those 14, so timed code is identical.
- **Sizing:** `jvm`, `mono`, `poly` and `poly-o3` on `bytearray` and `router`. Each image runs
  uncached and at 256, 1K, 4K and 16K slots (`--decoded-cache N`), over all guest workloads
  including the two new ones. The speedup columns compare against the same binary uncached.
  "vs main" compares against this session's `main` run.
- **Ceiling:** the tier-2 images from `exp/predecode` (`077c90b`), rerun this session. The images
  are those of the predecode experiment. The JVM jar was rebuilt from the same commit (SHA-256
  `5b75ddbb…`), since the original jar was lost.
- **Counters:** one JVM process per size (`--cache-stats`, first, 1 warmup and 1 measured round).
  Hits are instructions minus fills minus bypasses.

| Run | Jar SHA-256 | When (PDT) |
| --- | --- | --- |
| [`main`](main) | `f4b92fa8…` (`fa29ddc`) | 20:53–21:09 |
| [`off`](off), [`sizing`](sizing), [`stats`](stats) | `68347d7b…` (`d8e55aa`) | 21:09–21:43 |
| [`ceiling`](ceiling) | `5b75ddbb…` (`077c90b`) | 20:18–20:21 |

[`comparison.md`](comparison.md) holds the full tables, generated by
[`dccompare.py`](dccompare.py) from the copied summaries.

## Correctness evidence

- The core suite runs three times: uncached, and with every core cached at 4 and 4096 slots,
  with the whole address space cacheable.
- `DecodedCacheTest`:
  - A seeded lockstep differential against the uncached core, eight configurations with C,
    F, A, Zba/Zbb, CSRs, traps, interrupts and WFI. After every step it compares state,
    memory and `PostExecHook` events.
  - A stale-entry case for each flush rule.
- `WorkloadTest`:
  - Every bench workload verified with 4- and 4096-slot caches.
  - The four CoreMark and FP images run in lockstep against the uncached core.

## Findings to note

- **HotSpot `step(count=1)` with the cache is 0.82× of uncached.** Delegation and per-call setup
  outweigh the saved fetch at one instruction per call. Native is 1.26–1.77×. V-32's AP calls
  `step(count=1)`, so the runtime it ships on decides whether the cache helps there.
- **Existing quirk, unchanged:** for a taken jump or branch, `PostExecHook` receives the target
  minus the instruction length, not the instruction's own PC as its Javadoc says. The cached
  path matches it exactly. Fixing it is a separate behaviour change.
- **On a miss, an instruction without a fast handler is fetched twice:** once to fill and once
  by the interpreter. CSR, system, fences, division and bit-manipulation fall in this group.
  This is harmless because cacheable ranges must be free of side effects. Two access-history
  tests stay on plain cores for this reason.
