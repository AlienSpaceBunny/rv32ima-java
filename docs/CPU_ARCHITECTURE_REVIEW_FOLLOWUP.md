# CPU review follow-up: boot evidence and code publication

> Copied from V-32 (`../emulator`) `docs/CPU_ARCHITECTURE_REVIEW_FOLLOWUP.md` at `805d76d` on 2026-10-10. Text is
> unchanged; links are adjusted for this repository, and links into V-32's evidence point at
> the sibling checkout. Recheck the original for later edits.

2026-10-10. **The improved core's boot-speed benefit is inconclusive at O2.
O3 is promising but grows the executable by 52.7%. The bounded cache sizing
experiment remains worthwhile; no cache implementation or production adoption
decision follows from this boot screen.**

Read this alongside the [initial advisory review](DECODED_ICACHE_ADVISORY_REVIEW.md).
The CPU agent has already copied that review into its own repository at `f1678af`.
This follow-up and its [full boot evidence](../../emulator/docs/performance-evidence/2026-10-10/core-boot/README.md)
are in the emulator repository and can be read directly from this shared workspace.

## What the boot comparison adds

Thirty fresh headless V-32 boots all passed full image/palette and clean-exit
checks. Both CPU versions used the current IOP wait fix, default segment RAM,
identical guest assets, matching non-CPU classes and the same toolchain. Three
samples per cell in each of two reversed-order blocks compare old/new CPU on
JVM and native O2, plus new-core native O3.

| Comparison | Block 1 readiness change | Block 2 readiness change |
| --- | ---: | ---: |
| New CPU vs old, JVM | 3.8% higher | 2.9% lower |
| New CPU vs old, native O2 | 15.6% lower | 19.1% higher |
| New-core O3 vs new-core O2 | 15.4% lower | 30.1% lower |

CPU-only rankings reverse, ranges overlap, and pooled medians differ by less
than 0.5%. Do not present the core's isolated throughput gains as established
V-32 boot gains. The checked dependency behaved correctly, but production remains
on `0.1.6-SNAPSHOT`; adoption is a separate increment.

O3 lowers both block medians, with overlap in one block. Its executable is
85.13 MiB versus 55.75 MiB for O2, with the same debug configuration. This is
file size, not measured runtime RAM. Qualify the recommendation to adopt O3:
there is a substantial size tradeoff in the complete production graph. Do not
describe it as free or infer locking from the residual native/JVM timing ratio.

No wider RAM matrix, profiling, retries or cache measurements were added.
The screen ended at its planned 30 processes. It supports keeping the sizing
experiment bounded and later requiring a consumer measurement with real
invalidation costs, rather than transferring the replica's speedup prediction.

## Author clarification: code loading survives XIP

Nate expects ROM_COPY to be replaced under XIP, but plans loading from an external
emulated filesystem and may keep compressed code in ROM that is decompressed
into RAM. These are intended use cases, not implemented filesystem or compressed-code
loader features. Removing ROM_COPY therefore does not remove external code writes.

Distinguish three cases in the cache contract:

1. **Immutable XIP ROM:** explicitly cacheable populated ranges, retaining hart
   permissions and invalidation on image replacement or mapping changes.
2. **Load/decompress before execution:** keep the target hart quiescent, complete
   writes, invalidate affected instruction state, then publish the launch/resume.
   Initially cold entries are easy; relaunch and previously executed RAM still
   require a flush. A bulk range invalidation at this boundary can avoid a
   cross-thread notification per decompressed byte if the integration explicitly
   guarantees quiescence and ordering.
3. **Overwrite code while execution continues:** require a defined publication
   handshake or coherent mechanism, covering aliases and writes racing a fill.
   Invalidation alone does not make partially loaded/decompressed code safe to
   execute. A flag consumed only at the next bulk call is not a sufficient general
   visibility contract. Avoid callbacks acquiring CPU/syscon locks under RAM locks.

The exact filesystem/loader API, whether live code replacement is permitted, and
any guest `FENCE.I` obligations remain author decisions. Initial V-32 advice stays:
preserve coherent behavior for existing guests, support an explicit quiescent
load/flush/start path, and do not silently change the guest contract to
`FENCE.I`-only. Modelled cache timing remains separate.

## Advice to the CPU agent

Proceed with the one declared sizing screen after folding in the initial review.
Keep 1K–4K decoded slots as V-32's advisory candidate band, with smaller/larger
controls and real conflict coverage. Before production implementation, settle
cache identity, explicit cacheability, mapping/permission invalidation, physical
aliases, write coverage, and concurrent visibility. The no-invalidation sizing
screen can establish capacity potential only.

The boot result does not authorize larger caches, a general backend rewrite, or
another synthetic entry-timing screen. Preserve the existing correctness evidence
and keep default-off execution unaffected.
