# Timer phase handoff to V-32

Date: 2026-10-12. From the CPU agent, for Nate to relay. The timer/counter phase (T1–T7 of
[the implementation plan](../TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md)) is complete on `main` and
installed locally as **`com.alienspacebunny:rv32emu-core:0.1.10-SNAPSHOT`** (not published, no
tag). It implements revision 6 of [the timer/counter design](../TIMER_COUNTER_DESIGN.md). The
execution API (`stepAttempts`, phase A) is not in this version.

This is a trial snapshot for running V-32's tests against the new API; V-32 owns the adoption
decision. The full list of changes is the `[Unreleased]` section of
[CHANGELOG.md](../../CHANGELOG.md); [API.md](../API.md) and the Javadoc state the contracts.

## Breaking changes

- **Constructors take a `MachineTimeSource`:** `RV32IMACore(MachineTimeSource)` and
  `RV32IMACore(IsaConfig, DecodedCacheConfig, MachineTimeSource)`. The constructors without one
  are gone, including `RV32IMACore(IsaConfig)`; pass `DecodedCacheConfig.OFF` for no cache. One
  source instance serves both harts.
- **`step` loses `elapsedUs`:** `step(state, mem, ramOffset, ramSize, count, postExec, csrHook)`.
  Each call samples the source once at entry, and a legal `time`/`timeh` read samples again. The
  core never accumulates time. `RV32IMAState.setTimer` is gone; `getTimer()` is the latest sample.
- **`count < 1` throws `IllegalArgumentException`** before any sample or state change.
- **Standard compare:** MTIP is `time >= mtimecmp`, unsigned; zero is an ordinary, immediately
  due deadline. Use `RV32IMAState.TIMER_COMPARE_NEVER` (all ones), which a new state holds.
- **Standard WFI:** it never sets `mstatus.MIE`. It wakes on `mip & mie & (MSIP | MTIP | MEIP)`,
  whatever the global enable, and traps only if deliverable. A due compare masked in `mie` no
  longer wakes a stalled hart. `injectInterrupt` no longer clears the WFI flag; the next `step`
  decides.
- **`mie`/`mip` implement only MSIE/MTIE/MEIE and their pending bits.** A guest write to `mip`
  changes nothing.
- **Read-only CSR numbers** (bits 11–10 = `11`) trap on write intent before any read or hook call.
- **Zicntr is built in:** `cycle`, `time`, `instret`, their high halves, `mcycle`/`minstret`
  and their high halves, and `mcounteren`. These never reach the `CSRHook`. Counter reads return
  the value before the reading instruction; user-mode reads need their `mcounteren` bit, which
  starts at 0.
- **New state accessors:** `getInstret()`/`setInstret()` (retired work count, guest-proof), and
  `getMcycle()`/`setMcycle()`, `getMinstret()`/`setMinstret()` (architectural values; setters
  change only an offset). `setCycle`/`setInstret` move the architectural view with them.
- **Fixed:** `PostExecHook` now reports a taken jump, branch or `MRET` at its own PC.

## V-32 migration sites

From the design and V-32's current sources (`../emulator` at `9349ac0`, on core `0.1.6-SNAPSHOT`):

- **`ApplicationProcessor.stepCycles`:** its `cycles <= 0` branch calls `step(count=1,
  elapsedUs=cycles)`, which now has no equivalent; drop or redefine it, and update
  `TerminalFaultIntegrationTest`, which uses it. The loop's `instruction == 0 ? 1 : 0` elapsed
  argument goes away.
- **`IOProcessor.run()`:** `advanceClock()` and its `setTimer` go; time comes from the shared
  source. The wait calculation's `match != 0` sentinel and its comment ("the core wakes WFI on a
  due compare even when MTIE is masked") no longer hold: use `TIMER_COMPARE_NEVER` for no
  deadline, and do not spin on a due compare masked in `mie`. `step(cycles, elapsedUs)` becomes
  `step(cycles)`.
- **`HartState.reset`:** `setTimer(0)` goes; `setTimerMatch(0)` would now make the timer due at
  once, so write `TIMER_COMPARE_NEVER`. Decide the counter reset policy (`setCycle`, `setInstret`,
  `setMcycle`, `setMinstret`) and the `mcounteren` grant, which Packet 001/R1 leaves open.
- **The LAUNCH timer reset** goes with `setTimer`; LAUNCH sets the AP's delivery mask (the
  effective compare becomes `TIMER_COMPARE_NEVER`) and keeps the retained request.
- **Shared-source wiring:** build one source, typically `(System.nanoTime() - epochNanos) / 1000`,
  and pass it to both cores; reset its epoch only while both harts and their workers are
  quiescent.
- **Device-level masking:** the AP interval device masks through the effective compare, as in the
  design's "Gating timer delivery" section; unmasking an overdue deadline needs a fresh call.
- **Tests that relied on old behavior:** WFI setting MIE, zero-disables compare, injection
  clearing the WFI flag, the inclusive `cycle` read, and any hook handling of `0xC00`–`0xC82`.

The A1 rule that an executed WFI calls `PostExecHook` once is **not** in this snapshot; today a
stalling WFI returns without the call.

## Validation on the CPU side

- `./mvnw clean verify`: 699 core tests in each of three cache configurations (uncached, 4 and
  4096 slots), the CLI's baremetal `IntegrationTest` and packaged-jar smoke run, and the bench's
  `WorkloadTest`. `./release.sh` passed with a freshly built baremetal binary. There is no
  Linux image in this repository.
- An independent reference core (`ReferenceCore`, from `f745790` with the design's changes
  applied) runs in lockstep with the core in `ReferenceDifferentialTest`.
- **Performance** ([evidence](../performance/2026-10-12/timer-counters/README.md)): batched native
  work is unchanged against `0.1.8`. `step(count=1)`, V-32's current AP shape, is about 1.12×
  slower natively from the per-call time sample and entry work; HotSpot uncached batched work
  is about 5% slower. Nate accepted both (2026-10-12): V-32 chooses its execution mode once the
  core is stable, and phase A's `stepAttempts` samples once per call rather than once per
  attempt.

## Using the snapshot

Point V-32's core dependency at `com.alienspacebunny:rv32emu-core:0.1.10-SNAPSHOT` from the
local Maven repository (`mavenLocal()`). V-32 reports what fails or needs a core change; this
note is not an exchange document to edit in place.
