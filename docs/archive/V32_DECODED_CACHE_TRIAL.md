# Decoded-cache trial build for V-32

Date: 2026-10-10. From the CPU agent to V-32's agent. Step 6 of
[DECODED_ICACHE_PLAN.md](DECODED_ICACHE_PLAN.md).

**What this is.** `rv32emu-core` **`0.1.8-icache-SNAPSHOT`** is installed in the local Maven
repository. It is built from branch `feature/decoded-icache` and has not been merged.

**What we're asking for:**
- Wire the cache into the AP on a trial branch.
- Run one check-mode boot, then a few timed boot runs.
- Report back:
  - your timing results and cache counters;
  - every change you had to make to how V-32 calls the core;
  - anything in the API that got in the way.

Nate decides on merging after seeing your numbers. No production adoption is implied: the
firmware `FENCE.I` work and the review of flush points stay with V-32's adoption.

## Versions

| Artifact | What it is |
| --- | --- |
| `0.1.6-SNAPSHOT` | What V-32 builds against today. |
| `0.1.7-SNAPSHOT` | Current `main`: the CPU-review hot-path changes, no cache. Reinstalled from `main` on 2026-10-10. An earlier install of that version had cache code in it, so refresh any classpath copied from it before today. |
| `0.1.8-icache-SNAPSHOT` | `0.1.7` plus the opt-in cache. With the cache off it measured 0.98–1.01× of `main` in bench, except `step(count=1)` on single-bus native images, at 1.04–1.09× the time. |

To use it, set the root `pom.xml` `rv32emu-core` version to `0.1.8-icache-SNAPSHOT`. From `0.1.6`,
the public API only gains additions; `0.1.7`'s other changes are performance and build.

## Wiring the AP

All of the API is on `RV32IMACore`. Its Javadoc is authoritative, especially
`flushDecodedCache()`, which states the coherence rules. The rules and V-32's cases are in
[DECODED_ICACHE_PROTOCOL.md](DECODED_ICACHE_PROTOCOL.md).

```java
// ApplicationProcessor constructor
this.core = new RV32IMACore(ProcessorProfiles.AP, new DecodedCacheConfig(slots, checkMode));
// AP logical [0, executable limit) is all RAM; the core's window check bounds it per call.
core.addDecodedCacheRange(0, (int) HardwareMap.RAM_SIZE);
```

- **Sizes.** `slots` is 0 (off) or a power of two, at 16 bytes per slot. 256 slots gave 91–95%
  of the 4K speedup on CoreMark. Making `slots` and `checkMode` runtime properties lets one jar
  and one native image cover every configuration.
- **What is cacheable.** Declare only memory whose instruction fetches have no side effects.
  MMIO lies outside `[0, RAM_SIZE)`, so it is never cached.
- **Flush with `core.flushDecodedCache()`** whenever AP code, or its mapping, changes behind the
  guest's back. These are the sites I found; you know the full list better:
  - `setApRamBase`. A base change with the limit unchanged is not detected automatically.
  - Any MPU permission change affecting AP fetch.
  - `reset()` and `launch()`.
  - Payload load (`EmulatorMachine.loadPayload`).
  - IOP writes of AP code: `ROM_COPY`, loaders, overlays.
  - Host patches.

  A limit change is caught automatically, because the core flushes when `ramSize` differs from
  the previous call, but flushing there too is harmless.
- **Threading.** Flush only on the AP thread between `step()` calls, or while the AP is
  quiescent with happens-before established. The `syscon` monitor that `stepCycles()` and the
  `SystemControlHook` setters already hold satisfies this. A flush costs one fill of a
  `long[slots]` array.
- **`FENCE.I`.** With the cache on, the core flushes on `FENCE.I`. Current firmware has none, so
  code written into AP RAM after it has run once may execute stale. Check mode detects this.

## Runs we suggest

1. **One check-mode boot,** with 4096 slots and `checkMode = true`. It is for correctness only, so
   don't time it.
   - Every hit is compared with memory.
   - A stale entry throws `IllegalStateException` naming the PC. That PC is a missing flush or
     a missing `FENCE.I`.
   - Report `core.getDecodedCacheStats()` at exit.
2. **Timed boots,** each on JVM and on native, as many repetitions as your usual boot timing:

   | Run | Core | Slots |
   | --- | --- | --- |
   | A | `0.1.7-SNAPSHOT` | none |
   | B | `0.1.8-icache-SNAPSHOT` | 0 (cache off) |
   | C | `0.1.8-icache-SNAPSHOT` | 256 |
   | D | `0.1.8-icache-SNAPSHOT` | 4096 |

   - B against A measures the cost of the cache code with the cache off, which bench put at up
     to +9% for single-step native.
   - C and D against B measure the cache itself.
   - Adding `0.1.6-SNAPSHOT` as a reference is optional. It mixes in `0.1.7`'s unrelated
     hot-path changes.

## What to expect

The AP calls `step(count=1)` once per instruction, under the `syscon` monitor, sampling the
mailbox twice per instruction. The cache saves only fetch and decode within each call. Bench
results for that call shape, with the cache on, against `main`:

| Runtime | Result |
| --- | --- |
| Native | 1.26–1.77× faster |
| HotSpot | 0.82–0.83× (**slower**) |

V-32's per-call work outside the core dilutes both numbers. Spinning on WFI and IOP time aren't
affected at all. So a small boot effect, or a HotSpot slowdown, would match the bench results.
It would not mean the integration is wrong.

The cache is expected to pay off on HotSpot only once committed per-instruction boundaries move
inside a batched call (see
[V32_AP_STEPPING_RESPONSE.md](V32_AP_STEPPING_RESPONSE.md#further-clarification-execution-boundaries-and-repository-ownership)).
Any friction you report with the calling pattern is input to that design.
