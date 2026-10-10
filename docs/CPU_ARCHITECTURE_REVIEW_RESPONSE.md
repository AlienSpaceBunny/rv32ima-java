# CPU architecture review: response to V-32

Response to [the 2026-10-10 handoff](archive/CPU_ARCHITECTURE_REVIEW_HANDOFF.md). Reviewed
`rv32emu-core` at `f745790` (`0.1.6-SNAPSHOT`). Changes landed as a result ship in
`0.1.7-SNAPSHOT`. Measurements, method and provenance are in
[performance/2026-10-10](performance/2026-10-10/README.md), produced with the new unpublished
[`bench`](../bench/README.md) module. The core stays memory-backend-agnostic; backend numbers
below are inputs to V-32's own storage decision.

## Summary

- **Native vs HotSpot, the core alone, neutral `byte[]` bus.** The original core was 2.6×
  slower under Native Image (GraalVM CE, O2). The cause is ahead-of-time compilation without
  runtime profiles, not Graal's code generator: on the JVM, Graal's JIT matches C2.
- **`0.1.7-SNAPSHOT` makes the core 18–39% faster natively and 28–34% faster on HotSpot** on
  CoreMark and integer workloads. CoreMark's C build gains a further 10–16% from table-driven
  compressed expansion. There are no API or behaviour changes. FP workloads are unchanged
  natively; an allocation-free rounding attempt had no effect.
- **V-32's production AP path is dominated by consumer-side costs, not the core:**
  - per-attempt and per-access locking, about 77 ns per attempt on both runtimes against ~5 ns
    for the core
  - native-only, the `MemorySegment` accessor: 4–7× in the core, 14× in host loops, plus
    72 B allocated per access
- **Compressed code cost 17–30% throughput** relative to the same program built without C.
  After C4 the penalty is gone on HotSpot and about 11% natively; the remainder is the
  halfword probe, which must stay.
- **Recommended next step:** adopt `0.1.7-SNAPSHOT`, build native images with `-O3`, and
  rerun V-32's existing AP controls. Then make one accessor decision for native builds. Details
  are at the end.

## What changed in `0.1.7-SNAPSHOT`, and what it asks of V-32

| Commit | Change | Consumer impact |
| --- | --- | --- |
| `0eff01b` | `MMIOBus` skips the hook scan outside the hooks' covered range, and scans an array, so no iterator is allocated | None. Hook dispatch still goes through the overridable no-context methods, which a new test pins for subclasses such as `MachineBus`. Registration and overlap rules are unchanged. |
| `a7d7b40` | Base RV32I ALU encodings are decoded before the M and Zba/Zbb tables | None. The encodings are disjoint, so no decode or legality outcome changes. |
| `9baaafd` | F and A/Zabha arms run in private helpers outside `step()`'s loop | None. Semantics are unchanged, including traps, `mtval`, reservations and register writes. |
| `ffb57c8` | Compressed instructions are expanded through a table precomputed from the existing decoder (256 KiB, built at class initialization) | None. Expansion is unchanged; the table depends only on encoding bits, so it needs no invalidation. Adds 256 KiB of heap per JVM or image. |
| `b5eecfd` | Tests pinning `AccessContext` privilege and hart identity across MRET, traps and calls | None (tests only). |
| `9bdccaf`, `4c22d8b` | Unpublished `bench` module with CoreMark and FP kernel images | None; not part of `rv32emu-core`. |

**Obligations.** There are no API, contract or behavioural changes. To adopt:

1. Bump the dependency to `0.1.7-SNAPSHOT`.
2. Rerun V-32's gate, including the frozen-oracle comparisons (330 attempt cases and 415
   bulk/hook/controller/CSR comparisons), because `step()`'s internals moved.
3. Rebase the private `stepAttempts`/shared-decoder prototype onto the new `step()`. It was
   written against `f745790`, and C3 rewrote the OP block and moved the F/A arms into
   `executeColdOpcode`, so it will conflict. The new structure should make it smaller.

**Reviewing the changes.** From the V-32 checkout:

```bash
git -C ../rv32ima-java log --oneline f745790..HEAD
git -C ../rv32ima-java diff f745790..HEAD -- core/
```

Each claim above can be checked against that diff and the evidence README. Every core change
was measured against its predecessor with identical bench classes, pinned to one P-core, with
full ranges. Every invocation was verified for instruction count, registers and memory, or
CoreMark's own CRCs and a Java FP reference.

## Assessment: where the time goes

| Layer | Owner | Native effect | HotSpot effect | Status |
| --- | --- | --- | --- | --- |
| Per-instruction decode work and code size in `step()`'s loop | Core | Large; AOT has no profile to discount cold arms | Moderate | Fixed by C3: −18% to −39% native, −28% to −34% HotSpot |
| Polymorphic bus call (one interface dispatch per access) | Core design | 19–27% of native core time | ≈0 (C2 inlines from profiles) | Remains. Not caused by allocation: C1, which removed all per-access allocation, was slower and was rejected. |
| Compressed fetch and expansion | Core | 17–30% | 17–30% | Expansion fixed by C4. ~11% native remains, from the required halfword probe. |
| FP arithmetic helpers | Core | Dominant in FP code: 33–41 vs 81–87 MIPS | — | Open. The per-operation rounding record was not the cost (C5 rejected); the remaining allocation source is unidentified. |
| `MemorySegment` scalar accessor | V-32 backend | 4–7× core slowdown; 72 B per access | ≈0 | V-32 decision |
| Per-attempt sampling and per-access monitors (`mailbox.updateInterrupt` ×2, `syscon`, `SystemRAMBus`) | V-32 | ~77 ns per attempt | the same | V-32 decision |
| No profile-guided optimization | Toolchain | Residual native gap | n/a | PGO exists only in Oracle GraalVM, a proprietary licence, and is excluded by project policy. CE `-O3` gives 12–25%; `-march` x86-64-v2/v3 give nothing measurable. |

## Answers to the review questions

**1. Does the execution API separate bulk throughput, committed attempts, traps, WFI and
external stops clearly enough?** Not for V-32's needs. `step(count=N)` is a bulk primitive, and
its trap budget and in-batch interrupt timing differ from repeated `count=1`. The minimum core
change is an additive committed-attempt entry point. It would own:

- pre- and post-sample callback placement
- attempt counting
- stop-result precedence, as the handoff specifies

V-32's scheduler and devices stay outside. The case is contract clarity, not speed: entry
costs ~3.6 ns per attempt on HotSpot and ~4.9 ns native, which is why the fused-entry screen
moved only ~3.5%. Not implemented here; it awaits a separately scoped API decision.

**2. Which hot-path structures matter under Native Image?**

- **Measured, significant:**
  - decode work and cold code inside the loop (fixed by C3)
  - the non-inlined polymorphic bus call (19–27%)
  - compressed expansion (fixed by C4)
  - FP helper cost (open)
- **Measured, not significant:**
  - `AccessContext` allocation by itself: removing it entirely made things slower, so it was a
    symptom of the non-inlined call, not a cost
  - per-call state reload and publication
  - exception boundaries
- **Consumer router check:** restructuring V-32 so the core sees a single `final` bus class
  does not recover inlining. A bus with real policy, like an MPU check, is too large to inline
  into `step()` anyway. Measured: no gain.

**3. Could a primitive/context-specialized interface or optional fetch path help?**

- **Primitive context:** no. The cost is the call, not its argument (see C1).
- **Optional executable fetch window:** the remaining structural lever for fetch. Its upper
  bound is the fetch share of a non-inlined call, roughly 10–25% native and ≈0 on HotSpot. It
  must preserve:
  - store and remap invalidation, including other harts' stores
  - MPU windows
  - exact-instruction fault timing
  - MMIO fetch side effects

  Not recommended ahead of the items below.
- **Emulated cache or prefetch buffer:** no evidence of bus contention, and a data cache would
  be a weaker form of direct RAM access with the same coherence obligations. Defer both.
- **Predecode (decode once per address):** the one structural option that could close most of
  the remaining AOT gap uniformly, for integer, FP and compressed code alike. It carries the
  same invalidation obligations as a fetch window. A bounded upper-bound experiment on
  CoreMark-class code ([plan](PREDECODE_EXPERIMENT_PLAN.md)) decides whether it becomes a
  separately scoped design. *Result pending.*

**4. Is the shared driver maintainable; could its modes cause native duplication?** Sharing
one decoder between bulk and committed-attempt modes is the right source structure. C3 makes
it easier: the hot loop is now smaller and the cold arms are already factored into
`executeColdOpcode`. A single image compiles one copy of `step()` unless a mode is specialized
by inlining into separate callers. Measure generated size if the entry point is adopted. An
additive entry point calling the same body does not harm the legacy path.

**5. Run the final entry-timing screen, redirect, or park?** Park it. Entry is ~4–5 ns per
attempt, so no outcome would change a decision. Redirect to the recommended next step.

## Ranked directions

1. **Adopt `0.1.7-SNAPSHOT` and build with `-O3`.** Core-side, free: −18% to −39% native and
   −28% to −34% HotSpot, a further 10–16% for C-heavy code, plus 12–25% from `-O3`. Keep `-march=compatibility` or `x86-64-v2`
   for portability; v3 gave nothing measurable.
2. **V-32 accessor, native builds only.** Replace scalar `MemorySegment.get/set` in hot RAM
   paths with a `ByteBuffer` view (direct or heap) or a `byte[]`, keeping the same memory and
   contracts. Direct `ByteBuffer` matched `byte[]` in every native cell; the segment accessor
   cost 4–7×.
3. **V-32 per-attempt locking.** Make boundary sampling cheap when nothing changed, for
   example a volatile mailbox-pending read before taking the mailbox monitor. Avoid re-entering
   `syscon` for every AP bus access while `stepCycles` already holds it. This affects both
   runtimes and likely outweighs every remaining core item. Preserve and re-verify the ordering
   contracts with V-32's mailbox and terminal-fault tests.

## Recommended next step

**One bounded V-32 measurement increment:**

1. Adopt `0.1.7-SNAPSHOT`.
2. Add `-O3` to the native build.
3. Rerun the existing 2026-10-07 AP guest controls on JVM and native, segment and buffer.

- **Pass:** the V-32 gate passes; the native AP buffer cells improve versus 2026-10-07's
  387–2,042 ms; and the JVM AP cells do not regress.
- **Budget:** one matrix, in the existing format.
- **Stop condition:** if native AP with buffer access is still above 1.5× JVM, the remaining
  cost is V-32's locking (direction 3), not the core. Scope that next instead of further core
  work.

The XIP/C plan can resume in parallel; nothing here blocks it.
