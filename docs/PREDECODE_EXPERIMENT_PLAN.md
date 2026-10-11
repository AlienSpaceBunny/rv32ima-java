# Predecode upper-bound experiment (complete)

Drafted 2026-10-10. It answers one question before the V-32 handoff: **how much could
decode-once execution gain over `HEAD`'s interpreter, at best?** That number decides whether
predecode becomes a separately scoped design or is parked. This is an experiment, not a
candidate change: nothing from it lands on `main`.

## Why now

The 2026-10-10 evidence ([performance/2026-10-10](performance/2026-10-10/README.md)):

- **The residual native gap comes from AOT without profiles, not from Graal's code
  generation.** Graal's JIT matches C2.
- **Per-instruction decode work and loop size were worth 22–42% native** (C3).
- **Compressed code runs 17–30% slower than the same program built without C**, because
  expansion and the halfword probe repeat on every execution.
- **FP code is dominated by per-operation work.**

Predecode attacks all of these uniformly. It also subsumes a prefetch buffer or fetch window,
since a hit needs no fetch at all, so those are not explored separately.

**Status: complete (2026-10-10).** Both tiers were measured against fresh `HEAD` references
and every workload check passed. The decision rule's top band is met: CoreMark's C build is
1.46–2.04× faster natively with tier 1 and 1.84–2.51× with tier 2, so a real predecode design
is to be scoped as a separate decision. The code lives only on the local branch
`exp/predecode` (`04fdf54`, `077c90b`) and as patches beside the
[evidence](performance/2026-10-10/README.md#predecode-upper-bound-experiment-not-landed-decision-rule-met).
Each tier took well under its time budget.

## Design of the experiment

**Where it lives.** A throwaway branch `exp/predecode` off `claude-review`,
modifying `RV32IMACore` behind a constructor flag that only the bench sets. It is never
merged. The bench gets one entry point that enables the flag (`PredecodeMain`, plus a
`mono-predecode` native image).

**No invalidation; static code only.** Every measured workload must leave its code bytes
unchanged. The harness checks this after each invocation by comparing the image region with
the loaded program, so the cache can never serve stale code. The cache covers
`[ramOffset, ramOffset + ramSize)`, is keyed per core instance, and is never flushed.

**Two tiers, each measured once:**

1. **Decoded-word cache (fetch elimination).** Per halfword slot, store the 32-bit word the
   switch acts on (the precomputed expansion for C) and the instruction length. A hit skips
   both bus fetch calls and the expansion. This bounds what a fetch window or prefetch buffer
   could buy, and the C-specific penalty. Expected effort: 1–2 hours including the matrix.
2. **Operand predecode (full decode-once).** Per slot, also store a handler index and
   pre-extracted fields: `rd`, `rs1`, `rs2`, and the sign-extended immediate or branch/jump
   offset. Dispatch on the handler index, with per-handler arms for the hot instructions
   (ADD/ADDI/LW/SW/BNE/BEQ/JAL/LUI and so on); rare ones fall back to the existing switch.
   This bounds full predecode. Expected effort: 3–4 hours including the matrix.

**Matrix (one run per tier, the existing runner, about 15 minutes each):**

- **Images:** `jvm`, `mono`, `poly` and `poly-o3`, with the `bytearray` and `router` buses.
- **Workloads:** `coremark-c`, `coremark-noc`, `fpkernels-c`, `fpkernels-noc`, `reg-ap`,
  `regc-ap`, `copy6m-ap`, `mix-ap` and `reg-ap-step1`.
- **Reference:** a fresh `HEAD` run in the same session with identical bench classes.

All existing verification stays on: instruction counts, CoreMark CRCs, the FP reference model,
and the register/memory models. Any mismatch voids the tier.

## Decision rule

| CoreMark (C build), native mono/poly, best tier vs `HEAD` | Outcome |
| --- | --- |
| ≥ 1.4× faster | Scope a real predecode design as a separate decision (requirements below) |
| 1.15–1.4× | Report to V-32 as a moderate, conditional option; no design work now |
| < 1.15× | Park predecode and fetch windows; say so in the review response |

Report the HotSpot effect alongside: predecode may help the JVM less, or not at all. Tier 1's
result also states directly how much of the compressed penalty is fetch and expansion.

**Stop conditions:**

- Either tier breaks a workload check: fix it within its budget, or record it as
  inconclusive.
- Tier 1 exceeds 2 hours, or tier 2 exceeds 4: stop and report what was measured.

## Requirements a real design would have to meet

These are listed so the result is read against the true cost; none of them is built in this
experiment.

- **Opt-in.** The `MemoryBus` contract implies every fetch reaches the bus, which buses doing
  MPU checks, MMIO or counting rely on. Predecode must be explicitly enabled by the embedder.
- **Invalidation:**
  - the core's own stores and AMOs into cached code (checkable in the core)
  - other harts' and host writes (shared RAM loads, payload replacement), which need an
    embedder notification API or a bus-side write hook
  - reset, remap and MPU window changes
- **Fault fidelity.** Fetch permission is evaluated per privilege and window, so cache entries
  must be keyed or validated on them. A fetch fault must still occur at exactly the right
  instruction, and C-boundary cases (a 32-bit instruction straddling a region end) must behave
  identically.
- **Memory.** Bounded per executable window. V-32's AP window is up to 16 MiB of shared RAM,
  so use a lazily filled, page-granular structure.
- **Equivalence testing.** Run the full core suite plus a differential mode that executes
  cached and uncached in lockstep.
