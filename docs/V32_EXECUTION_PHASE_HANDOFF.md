# Execution phase handoff to V-32

Date: 2026-10-12. From the CPU agent, for Nate to relay. Phase A (`stepAttempts`, steps A1–A4 of
[the implementation plan](TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md)) is complete on `main` and
installed locally as **`com.alienspacebunny:rv32emu-core:0.1.11-SNAPSHOT`** (not published, no
tag). It implements revision 6 of [the execution design](EXECUTION_API_DESIGN.md), as V-32
ratified it.

Nothing is requested. This note records the closed phase and, at Nate's request, lists the test
guests and test code V-32 may want to borrow.

## What changed since the trial

**Nothing in the core.** `0.1.11-SNAPSHOT`'s core and CLI sources are identical to the trial
`0.1.10-attempts-SNAPSHOT` (`trial/0.1.10-attempts`, `196a730`) that V-32 `70237cf` consumes.
Moving to it is a version change only. Since the trial, this repository added only the bench
measurement, documentation and the version bump.

## Measurement (A3)

[Evidence](performance/2026-10-12/attempts/README.md): bench guests on HotSpot and Native Image,
with the bench's AP-shaped controller (a volatile mailbox read into `mip` before and after each
attempt, and a volatile stop check).

- **Cached `stepAttempts`**, V-32's chosen path, runs 1.21–1.76× faster than the timer version's
  cached `step(count=1)` loop with the same callbacks on native, and 1.29–1.74× on HotSpot.
  CoreMark gains 1.62–1.71×. The synthetic cache-stress guests gain least, and on HotSpot they
  run cached attempts slower than uncached (0.91–0.94×).
- **The budget barely matters.** Against 1,061 attempts per call, 64 costs 0–8% and 65,536
  gains nothing. V-32 can retune its budget for scheduling reasons without a throughput penalty
  in that range.
- **Allocation:** on Native Image, each call allocates its 24-byte `AttemptResult`; HotSpot
  usually scalar-replaces it.
- **Unchanged:** `step` batches, including the IOP's shape. `step(count=1)` is 2–7% slower than
  in the timer version; Nate accepted that, since the AP no longer uses it.

This is the bench, not V-32's machine; V-32's boot timings remain its own evidence.

## Test guests and code to borrow

All of it is MIT-licensed like the rest of this repository, except CoreMark (below). Paths are
relative to this repository.

### Guest programs

| What | Where | Notes |
| --- | --- | --- |
| **CoreMark** port | `bench/guest/coremark-port/`, `crt0.S`, `support.c`, `guest.lds`, `Makefile` | EEMBC sources are Apache-2.0 and fetched at a pinned commit by `fetch-coremark.sh`, not vendored; only the port layer is ours. Built with and without C for `rv32imafc_zicsr_zba_zbb` (V-32's AP profile). Self-checking: CoreMark's own CRCs plus a pinned final CRC. Not a compliant CoreMark score. |
| **FP kernels** | `bench/guest/fpkernels.c` | 48×48 sgemm, 8-body n-body (`sqrtf`, division), degree-8 Horner (`fmaf`). Checked against `Workloads.FpKernelModel`, a Java model of the same operations. A ready F-extension soak test. |
| Prebuilt images | `bench/src/main/resources/com/alienspacebunny/bench/guest/` | The four `.bin` images, with instruction counts and CRCs pinned in `expected.properties`. Usable without a cross-compiler. |
| Guest runtime conventions | `bench/guest/README.md` | Image at 0, stack top `0xFF0000`, a result block at `0xFF8000` (completion magic, `main`'s return, address of the final `WFI`, results). Ends in `WFI`. Easy to map into any RAM window. |
| **baremetal** hello-world | `baremetal/` | From upstream mini-rv32ima, built with clang/lld. Prints through mini-rv32ima's debug CSRs (`0x136`–`0x138`, a `CSRHook` in the CLI) and reads `cycle`. A smoke test for C-plus-assembly startup and CSR hooks. |

### Synthetic workloads (Java)

- **`bench/.../Asm.java`:** a minimal RV32 assembler in Java (the encodings the workloads use,
  backward branches, compressed forms). Useful for generating directed guest code inside JUnit
  tests without a toolchain.
- **`bench/.../Workloads.java`:** self-verifying guest programs built with `Asm`, each with an
  analytical instruction count and a Java reference model of registers and memory. Register
  loops (32-bit and compressed), word copy with checksum at 4 KiB and 6 MiB, a load/store/AMO
  and LR/SC mix, FP arithmetic, and two decoded-cache stress programs (`spread-ap`: 256 functions
  over 64 KiB; `collide-ap`: eight functions that collide in a direct-mapped cache).
  `Workloads.ApController` is the AP-shaped controller the measurement used.
- **`bench/.../Harness.java`, `run_matrix.py`:** a warmup-then-measure harness with
  allocation and GC counters, forked runs in reversed order, and CSV summaries. The `Mono*Main`
  and `PolyMain` entry points control Native Image's devirtualization.

### Correctness test code

In `core/src/test/java/com/alienspacebunny/emu/`:

- **`ReferenceCore`:** an independent interpreter, copied from the core at the baseline of
  V-32's attempt oracle (`f745790`), with the timer design's changes and an attempt driver
  applied. It never shares sampling or counter code with the core; its Javadoc lists every
  change made to the baseline.
- **`ReferenceDifferentialTest`:** seeded random program generation plus directed programs (LR/SC,
  user mode, WFI and interrupts, faults, CSR hooks, counters), run in lockstep against the
  reference. It includes a `RecordingBus` that logs every data, device and reservation access, a
  `RecordingCsrHook`, an `AdvancingSource` time source, and a `RandomController` that edits the
  machine at attempt boundaries. The recording bus and random controller are the most portable
  pieces for testing V-32's own adapters.
- **`AttemptBoundaryTest`:** V-32's oracle cases (36 cases, 330 budget comparisons), ported to
  the current contract.
- **`RV32IComplianceTest`:** riscv-tests-derived vectors for every RV32IMA encoding, with
  sign-boundary cases.
- **`FExtensionRoundingTest`:** F arithmetic in every rounding mode, checked against
  `BigDecimal`-exact and `Math.fma` oracles.
- **`TimerTest`, `CounterTest`, `CsrTest`:** directed coverage of the shared time source, compare,
  WFI, Zicntr and CSR legality.

The bench and the test sources are not published artifacts. Copy what helps; nothing here is an
API commitment.
