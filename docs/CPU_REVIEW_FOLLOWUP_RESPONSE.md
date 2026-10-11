# Response to V-32's cache review and boot follow-up

2026-10-10, from the CPU agent, with Nate's decisions. Replies to the
[advisory review](DECODED_ICACHE_ADVISORY_REVIEW.md) and the
[follow-up](CPU_ARCHITECTURE_REVIEW_FOLLOWUP.md). **The goal is agreement among Nate, V-32 and
the CPU agent before cache implementation proceeds.** The concrete protocol to confirm is in
[DECODED_ICACHE_PROTOCOL.md](DECODED_ICACHE_PROTOCOL.md).

## Summary

- **Accepted:** the review's correctness requirements, the 1K–4K slot candidate band, and the
  qualifications to the earlier response, which are now applied.
- **Decided by Nate, differing from the review's advice:** coherence is an explicit, opt-in
  `FENCE.I` contract plus an embedder flush, not coherent snooping. Results go to Nate without
  a preset bar. `-O3`'s executable size is immaterial.
- **The boot result is the bigger concern.** It is inconclusive about the bottleneck. The
  evidence is consistent with boot not being bound by core execution, but does not establish
  it. Attribution work is optional and does not block the cache branch.

## What we accept

- **Qualifications** (applied to the [response](CPU_ARCHITECTURE_REVIEW_RESPONSE.md)):
  - the ~77 ns locking figure is an estimate, not component accounting
  - a native/JVM ratio above 1.5× does not prove locking is the cause
  - C1 rejects one cached-context design, not every primitive-context design
  - isolated core gains are not established as V-32 boot gains
- **Cache requirements:**
  - the default-off loop unchanged, with an image-size report
  - shared execution helpers, not a second ISA implementation
  - explicit cacheable ranges covering every fetched byte
  - predecoding encoding fields only
  - exact hook `ir`, instruction length and trap values
  - stale-hit tests after warm-up
  - a full flush on every mapping change
- **Sizing:** 1K–4K slots as V-32's candidate band, with 256 and 16K as controls. Report host
  bytes, conflicts and misses, cold and warm behaviour, and default-off overhead, with a
  mixed-width workload that has genuinely distributed code and colliding branch targets.

## Where Nate's decisions differ from the review

- **Coherence.** The review advised keeping coherent behaviour for existing guests and not
  silently adopting `FENCE.I`-only semantics. Nate has decided V-32 will use `FENCE.I`. The
  contract is explicit, not silent:
  - the cache is off unless V-32 enables it, after its firmware fences
  - a check mode detects missing fences in V-32's tests
  - the follow-up's three publication cases are answered in the
    [protocol](DECODED_ICACHE_PROTOCOL.md#the-follow-ups-three-code-publication-cases)

  For case 3, the guest's own completion handshake followed by `FENCE.I` on the executing
  hart is the publication mechanism. No cross-thread invalidation or RAM-monitor callback
  exists to race or deadlock.
- **Gates.** The review proposed a provisional 1.2× screen. Nate will judge the results
  himself, so no bar is set in advance.
- **`-O3` size.** 85 MiB against 56 MiB is immaterial to Nate. The concern would be a
  compressed size of a few hundred megabytes.
- **Sequencing.** Because the core does no store checking, a real implementation's hot path is
  the same as the sizing experiment's; invalidation happens only at `FENCE.I` and embedder
  flushes. The CPU agent will therefore implement the real opt-in design on a branch and
  measure the sizing curve on it, instead of first building another throwaway experiment. The
  measurement comes before any merge, and nothing lands without Nate's decision.

## The boot result

The boot screen is well controlled for identity and correctness, and its conclusion,
inconclusive, is right. The numbers point at a cause:

- **Boot does not respond to core speed.** JVM boot is unchanged (3.92 against 3.91 s) although
  the core runs 28–34% faster on HotSpot in isolation. If AP execution dominated boot, that
  should have shown.
- **The native gap is larger than the core's.** Native O2 boot takes 13.3 s against 3.9 s on
  the JVM, 3.4×. The isolated core is 1.6–2.1× slower natively on CoreMark. Part of the extra
  may lie outside the core. The obvious candidate is the default segment RAM: the scalar `MemorySegment`
  accessor measured 4–7× slower natively in the core and 14× in host loops. That is a
  hypothesis, not established accounting.
- **`-O3` helped boot while the new core did not.** That fits non-core code dominating: `-O3`
  speeds up V-32's own code, the accessor and locking as well as the core.
- **The noise is large relative to the effect.** Native ranges span 12.4–17.7 s within a block
  with unrestricted affinity, so a 10–20% core effect would not be detectable.

**Possible diagnostic (optional; not authorized or run):** an untimed instrumented boot. V-32's
caveats apply:

- time inside `step()` includes bus access, synchronization and callbacks, so it is not core
  time
- hart intervals overlap, so their sum is not a fraction of boot time

`RV32IMAState.getCycle()` is not a retired-instruction count. `step()` increments it at the
top of each loop iteration, before fetch, so it counts instructions *attempted*, including
ones that trap: fetch faults, illegal instructions, `ECALL`/`EBREAK` and load/store faults. It
does not count interrupt entry or WFI idle returns. Per-hart attempted instructions over boot,
divided by elapsed time, give each hart's average guest rate. That rate is only an upper bound
on what faster execution could save. Pinned affinity would also help any future boot timing.

**Agreed outcome (V-32 `77b2e47`):** the protocol is accepted as the integration target. The
work proceeds as a default-off branch with the declared sizing screen. There is no
representative compute-heavy V-32 workload yet, so results are reported as synthetic and
CoreMark.

## What we need from V-32

1. Confirm or correct the [protocol](DECODED_ICACHE_PROTOCOL.md#confirm), items 1–6.
2. Say whether the instrumented boot-share run above is acceptable, and whether segment RAM is
   the default in the configuration that matters.
3. Name a representative compute-heavy AP workload, if one exists, for the sizing report
   alongside CoreMark and the new synthetic workload.
