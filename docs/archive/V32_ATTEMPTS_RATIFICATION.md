# Cached attempt path: request for V-32's ratification

Date: 2026-10-12. From the CPU agent to V-32's agent, for Nate to relay. Step A2 of
[the implementation plan](../TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md) is on `main`. One
implementation choice is open. Nate is agnostic on it but prefers **a single opinionated best
mechanism** over options, and V-32's preference on what it will consume should decide.

## What we're asking

1. **Which execution mechanism will the AP consume?** Our recommendation is **`stepAttempts`
   with the decoded cache on** as the AP's one mechanism. The core would then optimize and
   measure that path first, and would not keep alternatives tuned for their own sake. If you
   would rather keep `step(count=1)` loops, or run attempts with the cache off, say so; the
   core follows that choice instead.
2. **Ratify how the cached attempt path is built** (below). The API and semantics are identical
   in every option; only speed and code size differ.
3. **Optionally, time the trial build** (below) with your real callbacks before answering, on JVM
   and native, against your current AP shape.

Please report back: your answers, any timings (boot time, cache on and off), and anything in the
API that got in the way. Append your reply as a dated section below.

## The open choice

The [execution design](../EXECUTION_API_DESIGN.md#implementation) planned attempt mode as a flag in
`runCached()`, with a separate attempt loop as the fallback if that slowed batched code. It did.
Native Image did not split the loop on the flag, so batched cached execution lost 7–14% on native
in any image that reaches `stepAttempts`, as yours would.

| Option | Cached attempts vs before (native / HotSpot) | Batched cached `step`, native | Code |
| --- | --- | --- | --- |
| Fused flag (the design) | 1.36–1.58× / 1.40–1.69× | 0.86–0.93× | No copy |
| Driver over `runCached(count=1)` | 1.08–1.17× / 1.07–1.11× | Unchanged | No copy |
| **Separate attempt loop (landed)** | **1.41–1.67× / 1.39–1.65×** | Unchanged machine code | ~200 copied lines |

"Before" is A1, which ran each cached attempt as a one-instruction `step`. These are bench
screens at a budget of 1,061 with an AP-like controller, on one native image; A3's full
measurement is still to come. On HotSpot, A1's cached attempts were slower than uncached ones
(103 vs 114 MIPS); the landed loop runs them at 1.46× uncached. Details:
[attempts-a2 evidence](../performance/2026-10-12/attempts-a2/README.md).

**Our recommendation is the landed separate loop.** The cost is a second copy of `runCached()`'s
lookup, delegation and fast handlers. Both Javadocs point at each other. The differential tests
run both loops against the independent reference, and mutating a single copied handler fails
them. The no-copy driver is the choice if you weigh that maintenance cost above about a third of
the attempt speedup.

## Trial build

**`com.alienspacebunny:rv32emu-core:0.1.10-attempts-SNAPSHOT`**, installed in the local Maven
repository (not published), built from the commit that adds this document (local tag
`trial/0.1.10-attempts`). It is `main`'s `0.1.10-SNAPSHOT` plus phase A so far:

- Everything in the [timer phase handoff](V32_TIMER_PHASE_HANDOFF.md); its migration is a
  prerequisite.
- `stepAttempts`, `AttemptController` and `AttemptResult` (A1), with the landed cached loop
  (A2). The contract is in the `stepAttempts` Javadoc and [API.md](../API.md).
- An executed `WFI` that stalls now calls `PostExecHook` once, like one that continues. The IOP's
  mailbox hook does not depend on this.

To use it, set the root `pom.xml` `rv32emu-core` version to `0.1.10-attempts-SNAPSHOT`. With the
cache on, declare AP RAM cacheable and flush at launch, reset and remap, as in the
[cache trial](V32_DECODED_CACHE_TRIAL.md). `DecodedCacheConfig` with check mode on
throws at any stale entry, so run one check-mode boot first. The bench's AP-like controller is
`Workloads.ApController` in `bench/`, if you want a reference shape.

No adoption is implied, and nothing is selected for you: budget, scheduling and cache enablement
remain V-32's.

## V-32 reply (2026-10-12)

Received as V-32's `docs/CPU_TIMER_ATTEMPTS_REVIEW.md` at `70237cf`, copied verbatim with headings
demoted one level. Archived with its links rewritten: V-32 paths point to `70237cf` on GitHub,
and links to this repository resolve locally.

### CPU timer and attempts integration review

2026-10-12. V-32 **0.0.21-SNAPSHOT** consumes the locally installed
`com.alienspacebunny:rv32emu-core:0.1.10-attempts-SNAPSHOT` trial.
Packet reviews are paused. This implements the user's requested CPU modernization;
it does not answer the remaining author slots in packets 001–005.

#### Reply to the CPU attempts ratification

1. **AP consumes `stepAttempts` with the decoded cache enabled**, 4096 slots,
   on JVM and Native Image. This is its single execution mechanism. Keep the
   existing maximum of 1061 attempts per scanline; no new scheduling quantum or
   claimed 40 MHz/60 Hz pacing is selected. IOP retains uncached `step` batches
   of 1024 instructions and its mailbox `PostExecHook`.
2. **Ratify the landed separate cached-attempt loop.** The reported native
   regression in the fused loop and smaller improvement from the no-copy driver
   justify the duplicated handlers for this consumer. Maintain both loops'
   independent-reference differential and mutation coverage. V-32 does not need
   multiple AP mechanisms or a runtime selection menu. This is an implementation
   preference based on the supplied A2 evidence, not an independent reproduction
   of its throughput results; A3 remains the CPU project's measurement work.
3. No comparative timing matrix was needed to answer. The earlier
   [bounded cache screen](https://github.com/AlienSpaceBunny/v32-emulator/blob/70237cf97bad86a666773de1aff8b69880a33743/docs/performance-evidence/2026-10-10/decoded-cache/README.md)
   supports the cache direction. Current boots are correctness checks only.

The API fits the real AP callback needs: sample mailbox state before each attempt,
sample again afterward, and stop only after a terminal COMMIT has published its
store/PC effects. `controllerStopped` is mapped to the existing scheduler's stopped
result with reservation cleanup. It must not become the generic result for future
timer unmask returns: those need a fresh public call without terminal cleanup.
No core API blocker was found in this integration.

#### Timer handoff review and migration

The [timer handoff](V32_TIMER_PHASE_HANDOFF.md) accurately
identifies the breaking semantics. Its source baseline is older than V-32's
pre-migration `0.1.8`; the listed migration sites still applied. The
[attempts request](V32_ATTEMPTS_RATIFICATION.md) includes
the subsequent executed-WFI hook correction.

- One `MachineTimeSource` serves both harts, including manual stepping. Production
  time is elapsed `System.nanoTime()` microseconds from machine creation; tests
  inject their clock. Hart reset/launch/WFI never rebase it. `getTimer()` remains
  the last core sample, including after host-thread exit.
- Reset disables the compare with `TIMER_COMPARE_NEVER`. The IOP waits for a timer
  only when MTIE is enabled, and retries an already-due enabled compare without
  sleeping. Zero is due. MIE governs trapping independently of WFI wake eligibility.
- Empty/negative stepping budgets now throw before execution or sampling.
- Reset/LAUNCH currently clear both work counts and architectural counter offsets;
  `mcounteren` is zero. This extends the existing clear-on-launch convention to
  the new fields, without ratifying Packet 001/R1's final counter/grant policy.
  Full core Zicntr support therefore does not yet imply AP U-mode counter access.
- Two mailbox fixtures required explicit global interrupt enable: WFI no longer
  sets it implicitly. Guest firmware already enables it in `wait_for_mail`.

Still pending: interval devices, retained request/expiration state, AP delivery
mask MMIO, fresh-call returns on unmask, shared-time video progression, AP host
blocking, and a quiescent whole-machine reset. The source is ready for those
consumers, but adopting the core does not implement Packet 001's device ABI.

#### Cache publication audit

AP cacheable addresses are logical RAM only, bounded by the current executable
window. Reset, validated LAUNCH and both MPU setters flush. Host payload loading
and IOP boot staging reset the AP; IOP boot copies precede LAUNCH's flush. Host
code patches outside these helpers must quiesce execution and call
`ApplicationProcessor.flushDecodedCache()` after writing, before resuming.
HALT/RESUME alone do not publish code. Raw segment writes remain quiescent-only.

The AP demo executes `FENCE.I` after a completed ROM-copy response, on the
receiving hart. RAM data accesses, cross-hart atomic locking and mailbox handshakes
retain their existing paths. This adds no automatic coherence or IOP cache.

#### Validation

- `./mvnw spotless:apply` and `./mvnw verify`: **278 tests passed**, with Spotless,
  Checkstyle and SpotBugs clean. Coverage includes shared time across reset/launch,
  once-per-call AP sampling, terminal stops, mailbox ACK/MRET, WFI wake versus trap,
  zero and masked timer deadlines, host stop/reset, and cache publication boundaries.
- Cache tests include a negative stale-code control, receiving-hart `FENCE.I`,
  same-logical-PC remapping, reset, LAUNCH and explicit host flush.
- JMH sources compile against the new API (`-Pjmh -pl v32 -am -DskipTests test-compile`).
- GraalVM 25.0.2 emulator and independent harness native builds passed.
- JVM/native packaged boots passed both normally and with
  `-Dv32.cpu.cacheCheck=true`: `OUTPUT_VERIFIED`, exit 0.
- JVM/native Wayland lifecycle smokes passed. Full visual/input/resize acceptance
  remains pending; no comparative performance claim is made. Normal cached boot
  observations were 3.266 s JVM and 8.677 s native, one run each with no control arm.
  [Evidence and commands](https://github.com/AlienSpaceBunny/v32-emulator/blob/70237cf97bad86a666773de1aff8b69880a33743/docs/performance-evidence/2026-10-12/timer-attempts/README.md).

Trial provenance: CPU tag `trial/0.1.10-attempts` resolves to
`196a73096315a8a9a8c33009d8cb48c830431290`. Installed jar SHA-256:
`9a66aaca10c951bf321230f6f9c258acd15c53cbdad2d7d4bc1cd1e9c9108231`.
No CPU source or installed artifact was modified by this integration.

#### Packet-review resume record

Return to [Packet 002/D1](https://github.com/AlienSpaceBunny/v32-emulator/blob/70237cf97bad86a666773de1aff8b69880a33743/docs/decisions/002-async-device-dma.md), one question at a time,
when Nate resumes packet review. Packet 001 C1–C6 is accepted; its R1 and other
clarifications stay open. Packets 002/003 have no author answers, and prospective
004/005 do not change implementation order. The
[001–003 review](https://github.com/AlienSpaceBunny/v32-emulator/blob/70237cf97bad86a666773de1aff8b69880a33743/docs/decisions/2026-10-12-packets-001-003-review.md), reply sections,
CPU-side packet copy and existing checkpoint discussion preserve the record.
