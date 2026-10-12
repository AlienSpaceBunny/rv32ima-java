# Step A3: attempt-mode measurement (2026-10-12)

Evidence for step A3 of [the plan](../../../TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md):
`stepAttempts` against V-32's former AP loop of `step(count=1)` calls, cache off and on, on
HotSpot and Native Image. The baseline is the timer version (`5245585`), with `v0.1.8` as a
historical comparison. Tables: [`comparison.md`](comparison.md).

**One decision is Nate's:** the `step(count=1)` paths V-32 no longer uses on the AP are 2–7%
slower than in the timer version (below). Everything else is unchanged or faster.

## Summary

- **V-32's chosen path is much faster than its former AP loop.** Cached `stepAttempts` at
  V-32's current budget of 1,061 against the timer version's `step(count=1)` loop with the same
  callbacks:

  | | vs cached loop | vs uncached loop |
  | --- | --- | --- |
  | Native (`mono`, `poly`) | 1.21–1.76× | 1.04–2.64× |
  | HotSpot | 1.29–1.74× | 0.94–1.68× |

  The low ends are the synthetic cache-stress workloads (`spread-ap`, `collide-ap`) and the FP
  kernels. On HotSpot the cache-stress pair runs cached attempts at 0.91–0.94× of uncached ones,
  a cache cost, not an attempt cost. CoreMark gains 1.62–1.71× over the cached loop and
  1.52–2.15× over the uncached one.
- **The budget barely matters.** Against 1,061, a budget of 64 costs 0–8% (cached 0.92–1.00×),
  and 65,536 gains nothing (cached 0.95–1.01×; uncached HotSpot 0.89–1.02×, whose low cells are
  the processes where C2 failed to scalar-replace, below). Per-call cost is small at any budget V-32 is likely to
  pick, so retuning the legacy 1,061 is free to follow V-32's scheduling needs.
- **Uncached `stepAttempts`** is 0.98–1.05× the timer version's uncached loop, and HEAD's own
  uncached loop runs at 0.90–1.02× of uncached attempts.
- **Allocation.** On native each `stepAttempts` call allocates one 24-byte `AttemptResult`
  (0.38 bytes per instruction at budget 64, 0.02 at 1,061); nothing else differs from the timer
  version. HotSpot scalar-replaces it: the attempt cells allocate nothing except on CoreMark and
  `mix-ap` (0.08–16 bytes per instruction), and the `step(count=1)` loop allocates the same way
  in both versions. The variation between processes follows C2's inlining, not the mode. The
  design's one immutable result per call stands.
- **Batched execution is unchanged.** Against the timer version, cached batches are 0.98–1.03×,
  apart from `reg-ap-step1`, below. Uncached batches are 0.96–1.08×. The low end is `poly`'s
  register loops (0.96–0.98×), consistent with the code-placement effect seen in
  [A2](../attempts-a2/README.md). Against `0.1.8`, native cached batches are 1.00–1.07× and
  uncached 0.95–1.02×, `reg-ap-step1` aside. HotSpot uncached batches are 0.86–0.95×, the timer phase's accepted cost.
- **Regression for Nate: `step(count=1)` paths.** Against the timer version:
  - the `step(count=1)` loop with callbacks: 0.96–1.02× on native, cached or not, mostly
    0.97–0.98× with tight ranges; 0.93–1.04× on HotSpot;
  - a bare cached `step(count=1)` loop (`reg-ap-step1` in cached batched mode): 0.93× `mono`,
    0.95× `poly`, 0.92× HotSpot (wide range). Against `0.1.8` that path is now 0.81–0.82× on
    native, including the timer phase's accepted 1.12×.

  **Probable cause:** A1 added an `attemptEntry` field check to `step()`'s entry, so that an
  uncached attempt reuses its call's time sample. `step(count=1)` pays it per instruction,
  about 0.3–0.5 ns against 9–13 ns of work. Not confirmed by a variant. Batched calls amortize it.

  V-32's AP no longer uses these paths. The IOP uses uncached 1,024-instruction batches, which are
  unchanged. Under the no-legacy direction, the proposal is to **accept it**. The alternative is
  a variant that moves the attempt entry out of `step()` (for example, a separate entry
  method), to measure whether that recovers the 2–7%.

## Method

- **Host and toolchain:** as in [the timer evidence](../timer-counters/README.md#method):
  i9-13950HX, `taskset -c 2,3`, Corretto 25.0.4.1, GraalVM CE 25.3.4.1, O2,
  `-march=compatibility`. Nothing else ran during measurement.
- **Images and bus:** `jvm`, `mono-bytearray` and `poly` (every bus reachable, polymorphic call
  sites), all on the `bytearray` bus. The `-o3`/`-v2`/`-v3` images and the other buses were
  left out; the timer phase showed them tracking these.
- **Modes:** `step` batches (`reg-ap-step1` is a bare `step(count=1)` loop in this mode);
  `--attempts N` at 64, 1,061 and 65,536; and `--attempts 1061 --step-loop`, V-32's former AP
  loop (`step(count=1)` between the same callbacks, in units of 1,061). Each mode ran uncached
  and with `--decoded-cache 4096`. The controller is the bench's AP shape: a volatile mailbox
  read into `mip` before and after each attempt and a volatile stop check.
- **Builds:** bench jars from `git archive` exports, with `./mvnw -pl bench -am package
  -DskipTests`.

  | Version | Source | Jar SHA-256 |
  | --- | --- | --- |
  | HEAD | `01e6976` (code as `79cf4f7`) | `572213108ad64954de1229bb9e7689d28fb6edd2f845a8ec44480251ac7ca401` |
  | Timer | `5245585` plus `79cf4f7`'s bench `Harness`/`Workloads` and [`timer-bench-backport.patch`](timer-bench-backport.patch) (no `stepAttempts`) | `7f10165aecbbb5231a8538c0f575922ced67e73aeb95ff1f08729dc0c49818ce` |
  | `v0.1.8` | T7's jar and images, reused | `2be3ab05535f8db39f531f030149655ca227637b855e8eca8f0f157ab16de51e` |

  Native image hashes are in each run's `manifest.json`.
- **Matrix:** `run_matrix.py --no-flags --buses bytearray --decoded-cache 4096`, plus
  `--attempts 64,1061,65536 --step-loop 1061` for HEAD and `--step-loop 1061` for the timer
  version. Two forks of 3 measured rounds after at least 10 s of warmup, 6 samples per cell, every
  invocation verified. HEAD ran 15:44–15:59 PDT, the timer version 15:59–16:05, and `v0.1.8`
  16:05–16:08.
- **Tables:** [`comparison.md`](comparison.md), generated by [`a3compare.py`](a3compare.py)
  from the run directories [`head/`](head/), [`timer/`](timer/) and [`v018/`](v018/), copied
  verbatim from the git-ignored `bench/out/results/`.
