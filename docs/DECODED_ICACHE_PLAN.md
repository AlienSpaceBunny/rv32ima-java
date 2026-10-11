# Decoded instruction cache: scope (draft for V-32 advisory review)

Drafted 2026-10-10. Status: **scoping only; nothing built or measured beyond the
[predecode upper-bound experiment](PREDECODE_EXPERIMENT_PLAN.md).** Nate has asked V-32 to
review this as an advisor before work proceeds. The questions for V-32 are at the
[end](#questions-for-v-32). **V-32's initial review has arrived**
([copy](DECODED_ICACHE_ADVISORY_REVIEW.md)) and is not yet folded into this draft.

## Summary

- **Proposal:** an optional, per-core, bounded cache of *decoded* instructions. It is
  direct-mapped and sized by the embedder, with a default of off. It is an instruction cache
  only; there is no data cache and no modelled cache timing.
- **Why bounded:** the upper-bound experiment kept a decoded copy of the whole executable
  window, 6× the window's size, which is 96 MiB per core for a 16 MiB window. That is tolerable
  for small machines but cannot be assumed for every consumer. A bounded cache costs a fixed
  amount per hart, whatever the RAM size.
- **Expected benefit (estimated, not measured):** the replica made CoreMark's C build
  1.84–2.51× faster natively and 1.30–1.50× on HotSpot. At hit rate *h*, a cache should give
  roughly 1 / (0.45·h + 1.05·(1 − h)) natively: about 2.1× at 95% and 1.95× at 90%.
- **Next step, after review:** one bounded experiment sweeping cache sizes, including a
  workload whose hot code exceeds the smaller sizes, followed by a decision whether to build it
  properly. The obligations a real implementation must meet are listed below.

## Background

The [evidence](performance/2026-10-10/README.md#predecode-upper-bound-experiment-not-landed-decision-rule-met)
from the upper-bound experiment, which had no invalidation:

| CoreMark, C build, vs `HEAD` | Native mono / poly / poly `-O3` (`byte[]`) | HotSpot |
| --- | --- | --- |
| Tier 1: cached fetched words | 1.46× / 1.78× / 1.58× | 1.03× |
| Tier 2: plus pre-extracted operands | 1.84× / 2.19× / 1.97× | 1.30× |

- Fetch was the largest native cost: the bus calls, the compressed halfword probe, and
  expansion. Compressed code's penalty disappeared entirely with tier 1.
- Tier 2 is what helps HotSpot. FP code gains little, because its helpers dominate.
- The disabled experiment code slowed the uncached path by up to 21% natively. A real design
  must leave the uncached loop unchanged.

## Proposed design

### Scope

- **Decoded instructions only.** The gain comes from decoding each instruction once and
  skipping the fetch calls. A data cache would remove no decode work. It would also duplicate
  memory the bus owns and impose coherence obligations on every load and store. Data-side cost
  is the bus call itself, which is a backend matter outside the core.
- **Invisible to the guest.** "Era-appropriate" refers to capacity only. Hits and misses have
  no timing or cycle effects. Modelled cache timing would be a separate feature.

### Configuration

- **A per-core cache size in slots: 0 (off, the default) or a power of two.** Off is exactly
  today's behaviour: every fetch reaches the bus.
- **The size is chosen by the embedder.** Era-appropriate consumers such as V-32 pick a small
  size; throughput-oriented ones pick a large one. A size covering the whole window
  approximates the measured replica.
- **API shape is open.** It cannot go in `IsaConfig`, which describes the ISA, not the
  implementation. The candidates are a constructor overload taking a small options record, or
  a setter valid only between `step()` calls.

### Structure

- **Direct-mapped, one slot per halfword of guest PC** with C; per word without C. The index is
  PC bits above the slot granularity. The tag is the slot's full PC plus the fetch privilege.
- **Entry contents, 16 B per slot:**
  - tag (4 B)
  - raw or expanded instruction word (4 B), needed for `PostExecHook` and illegal-instruction
    `mtval`
  - handler index, `rd`/`rs1`/`rs2`, a compressed flag and the immediate (8 B)

  That is 8 B of host memory per byte of compressed guest code covered, or 4 B without C. For
  example, 1K slots is 16 KiB covering 2 KiB of C code, and 4K slots is 64 KiB covering 8 KiB.
  Dropping the stored word, which is reconstructible for the fast handlers, would save 25% at
  the cost of complexity.
- **Fill on an executed, successful fetch only.** It never fills a line or fetches ahead,
  since a speculative fetch could touch MMIO or fault before the instruction executes. Faults
  and reserved encodings are never cached.
- **Hit path:** a tag compare, then the tier-2 fast handlers (hot RV32I and MUL); anything else
  falls back to the ordinary switch with the cached word.
- **The uncached loop stays untouched.** `step()` selects at entry between today's loop and a
  separate cached loop. Native Image then compiles two loop bodies; measure the image size.

### Bus contract and fault fidelity

- **Hits do not reach the bus.** This relaxes the `MemoryBus` fetch contract for enabled
  cores; the relaxation must be documented in Javadoc and `API.md` as opt-in.
- **Cacheable windows.** Only fetches inside embedder-declared cacheable ranges are cached, so
  MMIO, counting buses and fetch side effects keep reaching the bus. By default this could be
  `step()`'s `[ramOffset, ramOffset + ramSize)`, or an explicit range.
- **Privilege in the tag.** Fetch permission can depend on privilege, so an entry filled in
  M-mode does not hit in U-mode. That avoids flushing on every trap and `MRET`.
- **Exact faults.** A fetch fault still occurs at the faulting instruction because faults are
  never cached. A 32-bit instruction straddling a window end behaves as today, because only a
  fetch that succeeded is ever cached.

### Invalidation

| Source | Mechanism | Cost |
| --- | --- | --- |
| The core's own stores, AMOs and SC | Check the slots each write overlaps, plus the slot 2 bytes before a write, in case a 32-bit instruction straddles it; clear on a tag match | One index and compare per store, enabled cores only |
| `FENCE.I` | Flush the whole cache. Zifencei semantics: today the core treats `FENCE.I` as a plain `FENCE` | Linear in cache size; cheap for small caches |
| Other harts, the host and DMA writing code | An embedder API: `invalidateInstructionCache()` and possibly a ranged variant. Whether it is mandatory depends on the coherence mode below | Embedder-defined |
| Reset, remap, MPU window changes, and a different `ramOffset`/`ramSize` | Flush, through the embedder API or automatically on a window change | Rare |

**Coherence mode is an open question.** There are two choices:

1. **Coherent:** the embedder must invalidate on every external write to cacheable memory, for
   example through a write hook in its RAM bus.
2. **`FENCE.I`-only:** cross-hart and host code writes become visible after the guest executes
   `FENCE.I`, or after the embedder flushes, as the RISC-V spec permits.

Mode 2 is cheaper and architecturally legal, but it breaks guests and loaders that write code
without `FENCE.I`.

**Threading.** The cache is hart-local. An invalidation from another thread must either happen
between that hart's `step()` calls, or set a volatile "flush requested" flag that `step()`
checks at entry. In the second case, visibility is delayed to the next `step()` call, which is
bounded by the batch size.

### Not changed

LR/SC reservations, AMO semantics, `AccessContext` contents for loads and stores, trap
encoding, interrupt timing, and the legacy partial-publication hook behaviour. With the cache
off, everything is unchanged and the uncached loop is not modified.

## Validation plan (for the real implementation)

- **The full core suite with the cache enabled,** at a small size to force conflicts and at a
  large size, alongside the existing default-off run.
- **A lockstep differential mode:** cached and uncached cores on cloned state over the bench
  workloads and a randomized instruction stream. Registers, PC, CSRs and memory are compared
  every instruction.
- **Targeted tests:**
  - self-modifying code, with and without `FENCE.I`
  - stores straddling slot boundaries and into the second half of a 32-bit instruction
  - aliasing PCs that map to the same slot
  - privilege-dependent fetch permission
  - an MMIO or uncacheable fetch reaching the bus every time
  - a fetch fault at a window end
  - external invalidation from the embedder API
  - `PostExecHook` receiving the same `ir` cached and uncached
- **Performance gates,** with the existing bench discipline:
  - cache off: within ±3% of `HEAD` on both runtimes
  - the agreed size: a gain worth the complexity, the threshold to be set after the sizing
    experiment

## Sizing experiment (proposed, not run)

The experiment runs on the throwaway branch `exp/predecode`, not on `main`.

- **Build:** tier 2 made direct-mapped, with sizes of 256, 1K, 4K and 16K slots plus the
  full-window replica as the ceiling. There is no invalidation, and the static-code check stays
  on.
- **Workloads:** the existing nine, plus a new synthetic one whose hot code is larger than
  2–8 KiB, to measure misses and thrashing. CoreMark's hot code is small and would fit almost
  any size, so on its own it overstates hit rates.
- **Reporting:** the hit rate per size, measured with an instrumented run separate from the
  timed ones, and speedup versus `HEAD` on JVM and native.
- **Budget:** one matrix, about half a day including the new workload.

## Phasing

1. V-32 advisory review of this scope (now).
2. Sizing experiment; report and decide.
3. Real implementation behind the opt-in API, with the validation plan above, the
   `CHANGELOG.md` entry, and Javadoc and `API.md` updates.
4. V-32 adoption at its chosen size and coherence mode.

## Questions for V-32

1. **Code writes:**
   - Do V-32's guests and loaders execute `FENCE.I` after writing code?
   - Where does the host write guest code: payload loading, launch, relaunch?
   - Could V-32 notify the core on those writes, for example from `SystemRAMBus`?
2. **Cross-hart code:** does the IOP ever write code the AP executes, or vice versa, outside a
   launch or remap boundary where a flush would be natural?
3. **Uncacheable fetch:** does either hart execute from anything with fetch side effects,
   counting or per-access policy that must see every fetch, such as MMIO, or ROM or VRAM under a
   remap?
4. **MPU:** does fetch permission depend on hart or privilege beyond M/U, and how often do MPU
   windows change at run time?
5. **Coherence mode:** is a `FENCE.I`-only mode acceptable for the AP and the IOP, or must the
   core be coherent with external writes?
6. **Sizing:** what capacity does V-32 consider era-appropriate per hart, and does it need the
   cache to be invisible to the guest (no timing), as proposed?
7. **Execution API:** does the cache interact with the proposed committed-attempt entry point
   or the `step(count=1)` attempt loop, given that cache state persists across calls?
8. **Native image:** is a second compiled loop body acceptable in image size?
9. **API shape:** do you prefer a constructor options record or a between-calls setter?
