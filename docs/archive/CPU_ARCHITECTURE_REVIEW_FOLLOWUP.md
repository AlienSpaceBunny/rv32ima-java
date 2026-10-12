# CPU review follow-up: boot evidence and code publication

> Copied from V-32 (`../emulator`) `docs/CPU_ARCHITECTURE_REVIEW_FOLLOWUP.md` at `77b2e47` on 2026-10-10. Text is
> unchanged; links are adjusted for this repository, and links into V-32's evidence point at
> the sibling checkout. Recheck the original for later edits.

2026-10-10. **The improved core's boot-speed benefit is inconclusive at O2.
O3 is promising but grows the executable by 52.7%. The bounded cache sizing
experiment remains worthwhile. Proceed with a default-off implementation on a
branch and measure before any merge or V-32 adoption. This recommendation follows
the subsequent protocol discussion, not a demonstrated boot-speed benefit.**

Read this alongside the [initial advisory review](DECODED_ICACHE_ADVISORY_REVIEW.md).
The CPU agent has already copied that review into its own repository at `f1678af`.
This follow-up and its [full boot evidence](https://github.com/AlienSpaceBunny/v32-emulator/blob/77b2e47b2f8a74b6aeac62862541c17158e7dbab/docs/performance-evidence/2026-10-10/core-boot/README.md)
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
Nate subsequently confirmed that this executable size is acceptable; the concern
would be a compressed distribution of a few hundred megabytes.

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
   require a flush. A full cache flush at this boundary is sufficient when the
   integration guarantees quiescence and ordering.
3. **Overwrite code while execution continues:** require a defined publication
   handshake that establishes write visibility, followed by `FENCE.I` on the
   executing hart before it enters the newly published code.
   Invalidation alone does not make partially loaded/decompressed code safe to
   execute. A flag consumed only at the next bulk call is not a sufficient general
   visibility contract. Avoid callbacks acquiring CPU/syscon locks under RAM locks.

Nate has now selected an explicit, opt-in `FENCE.I` plus embedder-flush contract
and wants to discourage self-modifying code. Loading and decompression remain
supported use cases. This supersedes the initial recommendation for automatic
coherence; that mechanism is not required under the chosen publication contract.
The cache stays off until firmware and host integration meet the contract.

## Response to the updated CPU protocol

The rules and ownership model in the CPU repository's
`docs/DECODED_ICACHE_PROTOCOL.md` are acceptable as the integration target.
AP RAM is the initial cacheable range; XIP comes later. Firmware fences and
toolchain support are acceptable adoption requirements. No conflict with the
completed boot screen is identified.

Guest base/limit writes while the AP is running return `BUSY` without changing
the mapping. HALT and AP execution share the syscon monitor, so HALT completes
outside `core.step()`. Raw Java mapping setters intentionally remain trusted
quiescent fixture APIs: they serialize with AP batches but do not enforce the
halted state. Tests use this distinction, including reservation invalidation
after a remap between batches. Leave these APIs unchanged for now; audit their
use and enforce safe mapping/flush boundaries at cache adoption. Keep the guest
`BUSY` ABI rather than adding a production IOP trap.

Protocol confirmation item 2 is an adoption obligation, not a claim that every
host load/reset/patch path has already been audited and wired. The case table
covers the known intended loading paths; it is not a blanket promise about
future monitors or JITs. Loading new code must not expose partially written
instructions, and publication must establish visibility before `FENCE.I`.

The boot screen does not establish that costs outside the core dominate boot.
An instrumented diagnostic could help, but time inside `step()` includes bus
access, synchronization and callbacks. Hart intervals may overlap, so their
sum is not a fraction of boot elapsed time. Verify what `getCycle()` counts
before labeling it retired instructions. No additional instrumentation run has
been authorized or executed here; attribution work need not block the branch.
No representative compute-heavy V-32 AP workload has been established by this
review, so report synthetic and CoreMark results as such until one is selected.

## Advice to the CPU agent

Proceed with the default-off implementation branch and declared sizing screen.
Keep 1K–4K decoded slots as V-32's advisory candidate band, with smaller/larger
controls and real conflict coverage. Preserve cache identity, explicit
cacheability, mapping/permission checks, semantic parity and stale-hit tests.
Use the chosen `FENCE.I` and host-flush contract rather than store snooping or
cross-thread invalidation. There is no preset performance acceptance threshold;
Nate will judge the results before any merge or adoption.

The boot result does not authorize larger caches, a general backend rewrite, or
another synthetic entry-timing screen. Preserve the existing correctness evidence
and keep default-off execution unaffected.
