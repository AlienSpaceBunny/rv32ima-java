# Core throughput: JVM vs Native Image decomposition (2026-10-10)

Evidence for [the CPU architecture review response](../../CPU_ARCHITECTURE_REVIEW_RESPONSE.md).
It was produced with the unpublished [`bench`](../../../bench/README.md) module. The core is
memory-backend-agnostic: bench buses other than `bytearray` only size backend costs for
consumers.

## Summary

- **Native vs JVM, original core, neutral `byte[]` bus:** native is **2.6×** slower in a
  polymorphic image and **2.1×** in a closed-world one. HotSpot uses C2; native is GraalVM CE
  25.3.4.1 at O2.
- **The cause is ahead-of-time compilation without profiles, not Graal's code generator.** On
  the JVM, Graal's JIT matches C2.
- **Landed:**

  | Change | Result |
  | --- | --- |
  | C2: `MMIOBus` lookup | −14% to −42% native and −17% to −55% HotSpot on `MMIOBus` buses |
  | C3: decode/loop restructuring | CoreMark and integer work 18–39% faster native, 28–34% HotSpot |
  | C4: compressed expansion table | CoreMark's C build a further 10–16% faster |

- **Rejected:** C1 (cached `AccessContext`s: removed all allocation but was slower) and C5
  (allocation-free FP rounding: no effect).
- **Compressed code was 17–30% slower than the same program without C.** After C4 the gap is
  gone on HotSpot and about 11% natively.
- **FP code is unchanged natively**, at about 33–41 MIPS against 81–87 on HotSpot. Its cost
  lies in the F helpers and a so-far unidentified allocation source.
- **FFM `MemorySegment` access is the largest native cost when it is used,** but it is
  downstream. It is 4–7× slower than `byte[]` in the core, 14× in host loops, and allocates
  72 B per access by itself; a direct `ByteBuffer` view matches `byte[]`.
- **Compiler flags:** `-O3` gives a free 12–25%. `-march` x86-64-v2/v3 make no measurable
  difference relative to `compatibility`.
- **Predecode upper bound (not landed):** caching decoded instructions per address makes
  CoreMark 1.46–2.04× faster natively (fetch only) and 1.84–2.51× with operand predecode,
  overtaking `HEAD` on HotSpot. It needs an invalidation design first; see the last section.
- **A consumer-style `final` router does not recover inlining.** A realistic bus with an
  MPU-style check is too large to inline into `step()` even when it is the image's only bus
  class.

## Method

- **Host:** Intel Core i9-13950HX. Every process is pinned with `taskset -c 2,3`, one P-core's
  two hyperthreads. Two CPUs keep HotSpot ergonomics on G1. No other builds or measurements
  ran concurrently.
- **Runtimes:**
  - JVM: Corretto 25.0.4.1 (25.0.4.1+8-LTS).
  - Native: GraalVM CE 25.3.4.1+1.1 `native-image` with `--no-fallback --parallelism=4
    -H:+UnlockExperimentalVMOptions -H:+SharedArenaSupport`, Serial GC (default). Each image's
    build log and the manifest record the level/target the compiler actually applied:
    "optimization level: 2, target machine: compatibility" for all O2 images, 3 for `poly-o3`,
    `x86-64-v2`/`-v3` for the `-march` images.
- **Images:** `poly` makes all nine bench buses reachable. Each `mono-*` image makes exactly
  one reachable, the closed-world control.
- **Repetition:** one process per (image, bus). Two forks; the second reverses both process
  order and workload order. Each process warms all workloads for ≥3 rounds and ≥10 s of timed
  execution, then takes 3 measured rounds, so each cell has **6 samples**.
- **Verification:** every invocation, including warmup and first runs, checks the analytical
  instruction count, final PC, absence of traps, and an independent Java reference model of
  registers and memory. `WorkloadTest` shows a perturbed expectation fails every workload.
- **Ranges:** full ranges are recorded in `summary.csv`. Native max/min spread is 1–5% in
  nearly all cells; JVM spread is 5–50%. Ranges describe noise, not confidence intervals.
- **Unpinned screen:** an earlier unpinned run had bimodal ~2× native ranges from P-/E-core
  placement. It is superseded. Its printed summary survives in
  [`unpinned-screen.txt`](unpinned-screen.txt); its raw files were lost to `mvn clean`.

### Provenance

| Item | Baseline |
| --- | --- |
| Core source | `f745790` (`HEAD`); the jar's core sources were verified byte-identical to `HEAD`. The manifest's `git_dirty` reflects then-uncommitted C1 edits in the tree, not the jar. |
| Bench jar SHA-256 | `9e5ae522eb9d03ef80beb257bf8785eee2f8655aa6cb745b9b0f2532ae8b33c0` |
| Run | 2026-10-10 00:43–00:58 PDT, 56 processes, all exit 0 |

Image hashes, argv, exit codes and per-process durations are in each run's `manifest.json`;
raw CSVs are in each run's `raw/`. Manifest paths refer to the git-ignored `bench/out/` tree
where the runs were made. Every run directory here was copied verbatim from there.

## Baseline results (unchanged core)

Median ms over 6 samples. Work per invocation: 5,000,006 instructions for `reg-*`;
11.0M for `copy*`; 3,400,008 for `mix`; 3.1M or 1.6M words for host loops.

| Cell | reg-ima | reg-ap | regc-ap | reg-ap-step1 | copy6m-ap | mix-ap | host-read6m |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| jvm/bytearray | 25.5 | 25.1 | 35.0 | 42.9 | 57.7 | 19.2 | 1.8 |
| jvm/directbuffer | 25.6 | 25.2 | 34.3 | 43.4 | 56.3 | 20.0 | 0.7 |
| jvm/ffm | 25.7 | 25.8 | 36.0 | 45.9 | 59.7 | 21.1 | 1.3 |
| jvm/router | 27.2 | 26.5 | 35.4 | 43.4 | 60.2 | 20.9 | 2.1 |
| jvm/mmio | 35.2 | 40.3 | 45.4 | 57.8 | 95.8 | 34.8 | 4.5 |
| mono/bytearray | 53.5 | 58.9 | 65.1 | 83.3 | 131.5 | 40.8 | 7.8 |
| mono/directbuffer | 57.1 | 57.7 | 65.3 | 79.3 | 131.4 | 38.5 | 5.7 |
| mono/router | 69.6 | 82.3 | 79.3 | 101.3 | 191.6 | 61.9 | 12.3 |
| mono/ffm | 221.9 | 387.4 | 261.7 | 452.1 | 967.0 | 334.5 | 107.0 |
| poly/bytearray | 66.2 | 72.0 | 73.3 | 92.9 | 166.0 | 53.2 | 9.8 |
| poly/directbuffer | 70.0 | 80.1 | 77.5 | 102.1 | 184.7 | 58.8 | 11.0 |
| poly/heapbuffer | 70.4 | 80.8 | 78.7 | 104.3 | 186.4 | 59.7 | 11.0 |
| poly/router | 69.2 | 85.0 | 81.0 | 104.7 | 200.8 | 66.0 | 12.8 |
| poly/mmio | 83.4 | 112.2 | 97.2 | 133.0 | 268.0 | 90.5 | 21.4 |
| poly/ffm | 247.3 | 473.9 | 300.9 | 513.5 | 1164.4 | 416.3 | 116.8 |
| poly/heapsegment | 238.7 | 453.6 | 288.2 | 488.6 | 1119.0 | 400.2 | 112.2 |
| poly-o3/bytearray | 58.1 | 62.1 | 66.7 | 87.9 | 137.3 | 42.8 | 5.9 |
| poly-v2/bytearray | 65.8 | 72.8 | 74.6 | 92.0 | 166.8 | 53.2 | 9.8 |
| poly-v3/bytearray | 66.3 | 74.1 | 75.0 | 95.7 | 171.7 | 55.0 | 9.9 |
| poly-o3/ffm | 185.0 | 331.4 | 238.1 | 361.6 | 847.5 | 300.7 | 84.6 |

All other cells, including `varhandle`, `wrapper` and `copy4k`/`host-copy4k`, are in
[`baseline/summary.csv`](baseline/summary.csv).

Sampled heap allocation in bytes per instruction (per word for host loops). One
`AccessContext` is 32 B.

| Cell | reg-ima | reg-ap | copy6m-ap | host-read6m |
| --- | ---: | ---: | ---: | ---: |
| jvm/* (except mmio) | 0 | 0 | 0–2.3 | 0 |
| mono/bytearray, mono/directbuffer | 0 | 0 | 0 | 0 |
| mono/router, poly/{bytearray,router,buffers,wrapper} | 32 | 64 | 73 | 0 |
| poly/mmio | 64 | 128 | 146 | 32 |
| mono/ffm | 72 | 144 | 170 | 88 |
| poly/{ffm,heapsegment} | 104 | 208 | 243 | 88 |

`poly/mmio`'s extra 32 B per access is `MMIOBus.getHook`'s `ArrayList` iterator, which escapes
when the lookup isn't inlined. FFM's 72 B per access comes from segment access itself in Native
Image, independent of the core.

### Reading the baseline

| Question | Cells | Finding |
| --- | --- | --- |
| Neutral-bus native gap | poly vs jvm `bytearray` | 2.6× (`reg-ima`), 2.9× (`copy6m`) |
| Dispatch/allocation share | poly vs mono `bytearray` | 19% (`reg-ima`) to 21% (`copy6m`) of poly time; allocation 0 in mono |
| Residual interpreter gap | mono `bytearray` vs jvm | 2.1× with zero allocation |
| C-enabled double fetch | `reg-ima` → `reg-ap` | native +9–10%; JVM ≈ 0 |
| Per-call entry (`count=1`) | `reg-ap` → `reg-ap-step1` | JVM 3.6 ns/attempt, native mono 4.9 ns/attempt: not native-specific |
| Backend accessor (downstream) | `ffm`/`heapsegment` vs `bytearray`/`directbuffer` | native 4–7× in the core, 14× host-only; JVM ≈ equal |
| Consumer router | mono vs poly `router` | no gain (69.6 vs 69.2); still allocates: a bus with real policy isn't inlined into `step()` |
| Compiler flags | poly O2 vs O3 vs v2/v3 | O3 −12% (`reg-ima`) to −17% (`copy6m`), −25% FFM; v2/v3 within ±3% |

An unpinned-screen reading that suggested `MemoryBus` default methods doubled dispatch
(`wrapper` faster than `bytearray`) did not reproduce when pinned (65.5 vs 66.2 ms). It is
retracted.

## Candidates

Acceptance gate for a candidate core change:

- `clean verify` passes.
- The targeted native cell improves by ≥10%, with non-overlapping ranges.
- No JVM cell is worse by more than 3%.

### C1: cached immutable `AccessContext` instances. Rejected.

**What it was.** A per-hart table of precomputed contexts, keyed by privilege, kind, width and
AMO op, held on `RV32IMAState` and rebuilt when `hartId` changes. `step()` looked contexts up
instead of constructing them. Results are in [`c1/summary.csv`](c1/summary.csv); the bench jar
SHA-256 is `2afe72be…11aa8`.

**Allocation result.** It removed core-side allocation completely. Native poly and
`mono/router` fell from 32–64 B per instruction to 0; only the FFM backend's own 72 B per
access remained.

**Timing result.** It was slower everywhere it mattered:

| Cell | Change vs baseline | Range |
| --- | --- | --- |
| `mono/bytearray` | +6% to +17% | above baseline in every workload |
| `poly/bytearray` | +1% to +5% | |
| `poly/router` | +3% to +8% | above baseline |
| JVM | +10% to +24% | above baseline in most C-enabled workloads |

**Why.** Where the record was already scalar-replaced (C2, and closed-world native), the
lookup adds loads that did not exist before. Where it was not, the allocation removed was
nearly free: a TLAB bump plus a few cheap young collections of dead objects.

**Conclusion.** Allocation was a symptom, not a cost. The baseline's 19–27% poly-vs-mono
difference is the non-inlined interface call itself. Any context scheme that adds a load
cannot beat scalar replacement where inlining happens. A primitive (packed `int`) context
would avoid both the load and the allocation, but its upside is bounded by the allocation
cost, which this result shows is small. It does not justify an API change.

C1's new tests that pin existing semantics were kept: privilege after MRET/trap mid-batch,
`hartId` changes between calls, and sub-word AMO width/op contexts.

### C2: `MMIOBus` covering-range lookup. Landed.

**What it does.** Addresses outside `[lowest hook start, highest hook end)` skip the hook scan,
and the scan uses an array rather than an `ArrayList`. Hook dispatch still goes through the
overridable no-context methods, which V-32's `MachineBus` subclass relies on. Results are in
[`c2-graaldiag/summary.csv`](c2-graaldiag/summary.csv).

| Cell | reg-ima | reg-ap | copy6m-ap | mix-ap | host-read6m |
| --- | ---: | ---: | ---: | ---: | ---: |
| poly/mmio, baseline → C2 (ms) | 83.4 → 71.7 | 112.2 → 86.5 | 268.0 → 205.5 | 90.5 → 65.8 | 21.4 → 12.4 |
| jvm/mmio, baseline → C2 (ms) | 35.2 → 28.6 | 40.3 → 28.4 | 95.8 → 65.1 | 34.8 → 22.6 | 4.5 → 2.0 |

Every candidate range is entirely below the baseline range. Native allocation for `mmio`
dropped by 32 B per access, the iterator: 64 → 32 B per instruction on `reg-ima` and 32 → 0
on the host loop. The control cells (`bytearray`, `router`) are unchanged within noise.

## Diagnostic: is the residual gap Graal code generation or missing profiles?

The same C2 jar was run on GraalVM CE 25.3.4.1's JDK, once with its default Graal JIT and once
with `-XX:-UseJVMCICompiler` (C2):

| Cell | reg-ima | reg-ap | regc-ap | copy6m-ap | mix-ap | host-read6m |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Corretto C2, bytearray | 25.8 | 26.4 | 35.2 | 59.3 | 20.5 | 1.7 |
| GraalVM JDK, C2, bytearray | 25.5 | 25.1 | 35.2 | 56.1 | 19.7 | 1.7 |
| GraalVM JDK, Graal JIT, bytearray | 24.9 | 29.9 | 33.8 | 62.6 | 17.8 | 1.6 |
| Native poly, bytearray | 66.5 | 73.3 | 74.0 | 166.5 | 53.3 | 9.9 |

Graal as a JIT, with runtime profiles and speculative deoptimization, matches C2 to within
noise. The native gap is therefore ahead-of-time compilation without profiles, not Graal's
code generator. Profile-guided optimization would be the textbook remedy, but it is only in
Oracle GraalVM, a proprietary licence, and is excluded by project policy. CE-only remedies
are `-O3` and changing the interpreter's structure.

### C3: hot-loop restructuring. Landed in two commits.

The diagnostic above shows Native Image lacks the profile that tells C2 which arms are hot. C3
removes per-instruction work and cold code from `step()`'s loop instead. Both parts were
measured against a fresh C2-core reference with identical bench classes
([`c2ref-fp`](c2ref-fp/summary.csv), [`c3a-fp`](c3a-fp/summary.csv),
[`c3ab-fp`](c3ab-fp/summary.csv)):

- **C3a: decode base RV32I OP/OP-IMM encodings before the M and Zba/Zbb tables.** These are
  disjoint, so no outcome changes, but the 10-flag bitmanip classification previously ran for
  every ALU instruction.
- **C3b: move the F and A arms into private helpers returning a packed `long`.** This keeps
  them out of the loop body.

| Change (vs C2 reference) | Native mono/poly, `byte[]` and router | HotSpot | FP loop (`fp-ap`) |
| --- | --- | --- | --- |
| C3a | −13% to −37% | −12% to −27% | −3% to −7% |
| C3a + C3b | −10% to −42% | −21% to −33% | −1% to +1% |
| C3b increment alone | −2% to −9% | −2% to −18% | neutral; AMO-heavy `mix` up to +3% in one cell |

Nearly all ranges sit entirely below the reference. C3b alone misses the 10% native gate. It
was landed by explicit decision because its gains are consistent on both runtimes and FP is
unaffected.

## Before/after on real code (original core vs `HEAD`)

`final-base` is the original core (`f745790`) and `final` is `HEAD` (C2 + C3a + C3b). They
share identical bench classes and images. Every cell is 6 samples, pinned. See
[`final-base`](final-base/summary.csv) and [`final`](final/summary.csv).

Median ms, original → final:

| Cell | coremark-c | coremark-noc | fpkernels-c | fpkernels-noc | reg-ap | copy6m-ap | reg-ap-step1 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| jvm/bytearray | 21.8 → 15.7 (−28%) | 18.1 → 13.1 (−28%) | 26.4 → 22.6 (−14%) | 25.0 → 22.3 (−11%) | 35.3 → 24.8 (−30%) | 79.6 → 53.8 (−32%) | 58.0 → 40.2 (−31%) |
| mono/bytearray | 31.6 → 24.0 (−24%) | 27.5 → 18.4 (−33%) | 52.8 → 52.0 (−1%) | 49.4 → 48.5 (−2%) | 59.9 → 42.2 (−30%) | 132.2 → 94.6 (−28%) | 81.9 → 62.0 (−24%) |
| mono/directbuffer | 30.7 → 22.3 (−27%) | 27.2 → 16.5 (−39%) | 51.9 → 50.3 (−3%) | 48.6 → 46.9 (−3%) | 57.3 → 36.7 (−36%) | 130.4 → 83.4 (−36%) | 81.6 → 57.2 (−30%) |
| mono/router | 44.6 → 35.4 (−21%) | 35.7 → 25.6 (−28%) | 63.1 → 63.0 (−0%) | 55.1 → 54.8 (−1%) | 85.2 → 64.1 (−25%) | 203.2 → 152.3 (−25%) | 103.8 → 82.8 (−20%) |
| poly/bytearray | 38.2 → 30.4 (−20%) | 32.8 → 22.5 (−32%) | 57.6 → 57.9 (+1%) | 52.7 → 52.2 (−1%) | 74.1 → 54.0 (−27%) | 168.6 → 126.8 (−25%) | 94.0 → 73.1 (−22%) |
| poly/router | 44.5 → 36.4 (−18%) | 36.8 → 27.2 (−26%) | 63.3 → 63.3 (−0%) | 55.7 → 55.4 (−0%) | 95.4 → 66.3 (−31%) | 199.7 → 156.9 (−21%) | 106.0 → 85.9 (−19%) |
| poly-o3/bytearray | 32.8 → 25.7 (−22%) | 27.3 → 19.4 (−29%) | 51.6 → 51.3 (−1%) | 47.7 → 47.5 (−0%) | 62.1 → 43.6 (−30%) | 137.8 → 105.1 (−24%) | 88.9 → 63.6 (−28%) |

All integer and CoreMark changes have candidate ranges entirely below the original. In
millions of guest instructions per second at the final medians, CoreMark (C build) runs at
164 on HotSpot, 107 in native mono, 85 in native poly and 100 with poly `-O3`.

### Findings from compiled code

- **Compressed code is slower than uncompressed code with the same instruction count.**
  CoreMark's C build is 56% compressed instructions statically, yet it runs 17–30% slower
  than the no-C build on every runtime: HotSpot 164 vs 197 MIPS, native mono 107 vs 140. Two
  costs explain it:
  - each execution re-expands the compressed encoding
  - every 32-bit instruction on a C-enabled core pays a halfword probe plus a word read

  This matters for V-32, whose AP code will be C-heavy. See C4.
- **FP code did not benefit natively.** The FP kernels run at 33–41 MIPS native against
  81–87 on HotSpot. The integer work around them improved, but the FP arithmetic helpers
  dominate. In the closed-world image they allocate ≈46 B per instruction where integer code
  allocates nothing: the per-operation rounding record (two for FMA) plus cold-path contexts.
  See C5.
- **Correctness on real code.** CoreMark's list/matrix/state CRCs and its final CRC agree
  between C and no-C builds on every runtime. The FP kernels match the Java reference model
  bit for bit, which also validates the F implementation and the compressed decoder on
  compiler-generated code.

### C4: table-driven compressed expansion. Landed.

**What it does.** `decodeCompressed` is a pure function of the 16-bit encoding, so its result
for all 65,536 values is precomputed at class initialization (256 KiB). Each compressed
instruction then costs one array load. The table needs no invalidation. Results are in
[`c4/summary.csv`](c4/summary.csv), measured against `final`.

| Cell | coremark-c | regc-ap | coremark-noc (control) | reg-ap (control) |
| --- | ---: | ---: | ---: | ---: |
| jvm/bytearray | −16% | −30% | +1% | 0% |
| mono/bytearray | −14% | −29% | 0% | −3% |
| mono/router | −10% | −24% | +3% | +1% |
| poly/bytearray | −12% | −27% | +2% | 0% |
| poly/router | −10% | −25% | 0% | −1% |
| poly-o3/bytearray | −14% | −31% | 0% | −1% |

All targeted ranges are below the reference. The control cells execute no compressed
instruction and stay within ±4%, which is session drift. After C4 the C build of CoreMark
matches the no-C build on HotSpot (13.2 vs 13.2 ms). The native penalty fell from ~30% to
~11%; the remainder is the halfword probe, which must stay because reading a word first could
fault or touch MMIO for a 16-bit instruction at a region end.

### C5: allocation-free FP rounding. Rejected.

**What it was.** The per-operation `ExactApprox` record (approximation plus residual sign) was
replaced by an inline approximation plus residual-sign helpers, with identical arithmetic. All
48 directed-rounding tests and the bit-exact FP kernels passed. Results are in
[`c5/summary.csv`](c5/summary.csv).

**Result.** FP cells were unchanged within ±4% on every runtime, and native FP allocation
barely moved (46.1 → 45.9 B per instruction in `mono`). The record was already scalar-replaced
because its helpers inline. The remaining FP allocation comes from elsewhere and has not been
identified. Reverted.

### Predecode upper-bound experiment. Not landed; decision rule met.

**What it was.** Per [the plan](../../PREDECODE_EXPERIMENT_PLAN.md), two throwaway tiers on
the local-only branch `exp/predecode`, behind an opt-in core flag that only the bench sets.
Neither tier has invalidation; every invocation checks that the guest left its code bytes
unchanged. Both patches are in [`predecode/patches/`](predecode/patches/).

- **Tier 1 (`04fdf54`), decoded-word cache:** per halfword slot, the fetched word (the C
  expansion for compressed code) and its length. A hit skips both bus fetch calls, the
  halfword probe and the expansion.
- **Tier 2 (`077c90b`), operand predecode:** tier 1 plus a handler index, `rd`/`rs1`/`rs2` and
  the pre-extracted immediate. Hot base RV32I instructions and MUL run in a fast path; the rest
  falls back to the ordinary switch.

**Matrix.** The `jvm`, `mono`, `poly` and `poly-o3` images on the `bytearray` and `router`
buses, nine workloads. Each tier ran against a fresh `HEAD` (`ae5aba2`) reference in the same
session. The `-pd` cells are the same experiment binaries with the flag set; the plain cells
show what the disabled code costs. The manifests' `git_rev` is the checkout the runner ran
from, so identify the builds by jar hash: `HEAD` `0649e8ec…`, tier 1 `b65813d6…`, tier 2
`ac2c3d57…`. The two `HEAD` runs agree within −1% to +7%. Full tables are in
[`tier1-comparison.txt`](predecode/tier1-comparison.txt) and
[`tier2-comparison.txt`](predecode/tier2-comparison.txt), produced by
[`pdcompare.py`](predecode/pdcompare.py) from `predecode/{head,t1,head2,t2}/summary.csv`.

**Result, speedup of `-pd` over `HEAD`** (every native range is entirely below its reference):

| Cell | coremark-c T1 | coremark-c T2 | coremark-noc T2 | fpkernels-c T2 | reg-ap T2 | copy6m-ap T2 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| jvm/bytearray | 1.03× | 1.30× | 1.41× | 1.14× | 1.43× | 1.33× |
| jvm/router | 1.02× | 1.50× | 1.47× | 1.23× | 1.65× | 1.53× |
| mono/bytearray | 1.46× | 1.84× | 1.62× | 1.11× | 2.34× | 2.07× |
| mono/router | 2.04× | 2.47× | 2.04× | 1.28× | 3.51× | 2.89× |
| poly/bytearray | 1.78× | 2.19× | 1.83× | 1.20× | 3.00× | 2.56× |
| poly/router | 2.04× | 2.51× | 2.08× | 1.29× | 3.65× | 2.94× |
| poly-o3/bytearray | 1.58× | 1.97× | 1.66× | 1.12× | 2.48× | 2.31× |

**Findings:**

- **The decision rule's top band is met by tier 1 alone:** CoreMark (C build) is 1.46–2.04×
  faster natively. Tier 2 reaches 1.84–2.51×. In MIPS on `bytearray`, tier 2 runs CoreMark-C
  at 233 in native `mono` and 212 in `poly`, above `HEAD`'s HotSpot figure of 206. With
  predecode, the AOT gap on integer code is closed.
- **Fetch was far costlier natively than estimated.** The earlier bound for a fetch window,
  10–25% native, came from the bus-call share alone. Tier 1 removes the probe, the second
  call and the expansion as well, and gains 46–104%. On HotSpot it gains at most 6%.
- **The compressed penalty is entirely fetch.** With tier 1, CoreMark's C and no-C builds run
  at the same speed natively (for example `mono`: 13.97 vs 14.13 ms), against an 11% penalty
  at `HEAD`.
- **Tier 2 is what helps HotSpot** (1.30–1.50× on CoreMark-C). Natively it adds a further
  18–26% over tier 1 on CoreMark.
- **FP is bounded by its helpers.** FP kernels gain 1.06–1.34× from tier 1 and nothing further
  natively from tier 2, which does not predecode F instructions.
- **The disabled code is not free.** With the flag off, the experiment builds run at
  0.89–0.98× (tier 1) and 0.79–0.94× (tier 2) of `HEAD`'s speed natively, and down to 0.91×
  on HotSpot. A real
  design must keep the uncached path's loop unchanged, for example with a separate loop or
  entry point.
- **Native `poly` allocates in the fast path:** 8.7 B per instruction on CoreMark, from
  `AccessContext`s passed to non-inlined bus calls. `mono/bytearray` allocates nothing.
- **Correctness:** every invocation verified instruction counts, CoreMark CRCs, the FP
  reference model and the register/memory models. The core suite (535 tests) passes on both
  experiment commits with the flag off; the flag was smoke-tested on five buses at test scale.
