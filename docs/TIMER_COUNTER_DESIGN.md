# Shared machine time, timer compare and Zicntr: design

Date: 2026-10-12. From the CPU agent. **Revision 6**, after V-32's review of revision 5 (kept
below with its earlier reviews), which left no open questions. Since revision 4 there is no legacy mode, on Nate's
direction: the core follows what V-32 needs and, where that stays cheap to maintain, the RISC-V
specification. It keeps no old shortcuts for their own sake. Nothing here is implemented, and
implementation waits for Nate's go. The changes in each revision are listed at the end.

**Inputs:**
- V-32's "Timer and counter extension implementation requests" in
  [EXECUTION_API_DESIGN.md](EXECUTION_API_DESIGN.md#timer-and-counter-extension-implementation-requests).
- V-32's Packet 001 decision record, in its repository at
  `docs/decisions/001-clocks-timer-events.md` (C1 time source, C4 IOP timers, C5 lifecycle).
- V-32's reviews of the first draft and of revisions 4 and 5, below.
- The [Zicntr](https://docs.riscv.org/reference/isa/v20260120/unpriv/counters.html),
  [Zicsr](https://docs.riscv.org/reference/isa/v20260120/unpriv/zicsr.html) and
  [machine-level](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)
  specifications.

This targets the API V-32 should move to, not how it calls the core today. **It lands first;**
the [execution API](EXECUTION_API_DESIGN.md) builds on it.

## Summary

- **One time model: a shared time source.** Every core is built with a `MachineTimeSource`. The
  `elapsedUs` parameter and per-hart time accumulation go away. V-32's production source is
  `(System.nanoTime() - epochNanos) / 1000`; tests, bench and the CLI's fixed-update mode use
  deterministic sources. The core samples at documented points and never accumulates or rebases
  time.
- **Standard compare:** MTIP is `time >= mtimecmp`, unsigned, and zero is not special.
- **Standard WFI:** it waits for a pending, locally enabled interrupt and no longer sets
  `mstatus.MIE`. `mie` and `mip` keep only the bits the hart implements.
- **Zicntr is always implemented:** `cycle`, `time`, `instret` and their high halves, the machine
  aliases `mcycle` and `minstret`, and `mcounteren`. Reads and writes follow Zicsr's ordering
  rules, and every write to a read-only CSR number traps before any side effect.
- **Work counts and architectural counters are separate.** `getCycle()` and a new `getInstret()`
  count attempted and retired instructions and are never changed by the guest. The guest-writable
  `mcycle` and `minstret` are views on them through per-hart offsets.
- **The hot loops stay as they are.** Time is sampled once per call and on `time` reads, not per
  instruction. Retirement is counted where the cycle counter is already published.

## What changes from today

The core's behavior today, as V-32's timer audit found and the code confirms, and what replaces
it:

| Today | Replaced by |
| --- | --- |
| Each hart's `mtime` grows by the caller's `elapsedUs` | A fresh sample of the shared source |
| `mtimecmp == 0` disables the timer | Standard unsigned compare; `TIMER_COMPARE_NEVER` for "never" |
| WFI sets `mstatus.MIE`; it stalls unless an interrupt is deliverable; a due compare ends a stall even with MTIE clear | [Standard WFI](#wfi) |
| Only `cycle` (`0xC00`) is built in, and its read includes the reading instruction | Full Zicntr; reads see the value before the instruction |
| Writes to read-only CSR numbers are not trapped; the read and hook run before any check but privilege | Write intent traps on every read-only number, before the read or hook |
| Guest writes store any value in `mie` and `mip`, including reserved bits | `mie` keeps MSIE, MTIE and MEIE; guest writes leave `mip`'s bits unchanged |
| `step(count <= 0)` still runs the timer update and an interrupt entry | `IllegalArgumentException` before any sample or state change |
| CSR-write and MRET reevaluation does not recompute MTIP, so a stale MTIP can re-enter a handler | MTIP recomputed at reevaluation |

Write intent itself is already decoded correctly: CSRRW and CSRRWI always write, CSRRS and CSRRC
write when the `rs1` index is nonzero, CSRRSI and CSRRCI when the immediate is nonzero.

The class Javadoc's two "intentional deviations" (WFI setting MIE, zero-disables compare) are
removed with this work.

## API

```java
/** The machine's shared timeline. Thread-safe; must never decrease during a call into a core. */
@FunctionalInterface
public interface MachineTimeSource {
    /** Microseconds since the machine epoch, as an unsigned 64-bit value. */
    long nowMicros();
}

/** A deterministic source the embedder sets or advances. For tests, bench and replay. */
public final class ManualTimeSource implements MachineTimeSource { ... }

public RV32IMACore(IsaConfig isa, DecodedCacheConfig cache, MachineTimeSource time);
public RV32IMACore(MachineTimeSource time);   // default ISA, cache off

// step() loses elapsedUs:
public int step(RV32IMAState state, MemoryBus mem, int ramOffset, int ramSize, int count,
        PostExecHook postExec, CSRHook csrHook);

// RV32IMAState gains:
/** A practically distant deadline, not a disable bit: time equal to all ones is still due. */
public static final long TIMER_COMPARE_NEVER = -1L;
public long getInstret();            // retired instructions; never written by the guest
public void setInstret(long instret);
public long getMcycle();             // architectural: getCycle() + the hart's mcycle offset
public void setMcycle(long mcycle);  // sets the offset; getCycle() is unchanged
public long getMinstret();           // architectural: getInstret() + the minstret offset
public void setMinstret(long minstret);
public int mcounteren;
```

- **The source is required.** One instance serves both harts on the machine timeline. The
  constructors without a source go away; the API is unfrozen and V-32 is its only consumer.
- **`step` requires `count >= 1`.** A smaller count throws `IllegalArgumentException` before any
  time sample or state change. V-32 migrates its nonpositive `stepCycles` branch and the test that
  uses it.
- **Counter setters:**
  - `setCycle` and `setInstret` change a work count and keep the offset, so the architectural
    view moves with it.
  - `setMcycle` and `setMinstret` change only the offset, so the work count is untouched.
  - A reset initializes both deliberately, for example `setCycle(0)` and then `setMcycle(0)`.
  - Host retirement accounting uses `getInstret()` deltas, never `getMinstret()` deltas.
- **`state.getTimer()` is the hart's latest sample, not a live clock.** It goes stale while a hart
  is halted or between calls. Anything that needs current time (device workers, interval
  acknowledgement cadence, WFI sleep deadlines) reads the shared source directly. `setTimer` goes
  away; the next sample would overwrite it anyway.
- **The CLI** builds a source from `System.nanoTime()` with its time divisor, or, in fixed-update
  mode, from the hart's cycle count, which is monotonic.

## Time sampling

The core samples the source at exactly these points:

1. **Once per public call,** at entry, after argument validation and, in `stepAttempts`, after the
   first `beforeAttempt`. Nothing internal samples again: not the `FENCE.I` flush-and-resume, a
   delegated `step(count=1)` inside the decoded cache, or the internal single-attempt entry that
   uncached `stepAttempts` uses (see the execution design). The same rule holds on the cached path,
   the uncached path and the independent reference.
2. **On every legal `time` or `timeh` read.** Each read samples afresh, so the RV32 high/low/high
   retry loop works and a waking or halted hart never reads stale time. A read that traps (U-mode
   with `mcounteren.TM` clear, or a write to the read-only CSR) does not sample.

Each sample becomes the hart's latest and **immediately recomputes MTIP**. If that makes a timer
interrupt deliverable, the read triggers the same in-instruction reevaluation as a write to
`mstatus`, `mie` or `mip`: the interrupt is taken after the reading instruction retires, in the
same attempt. The read's result, the retirement and the resulting interrupt state are all
published before `afterAttempt`. This is the emulator's sampling policy, not a claim about when
hardware delivers a timer interrupt.

MTIP is also recomputed **from the latest sample, without sampling again:**
- at every attempt's entry in `stepAttempts` and at every `step` entry, so a callback's compare
  change is seen at the next attempt;
- at interrupt reevaluation after a CSR write or MRET, so a compare moved through MMIO before MRET
  does not re-enter the handler.

**Deferred deadlines.** A deadline crossed in the middle of a call is noticed at the next sample:
the next call or the next `time` read. This is not a latency guarantee. An instruction budget does
not bound wall time, because MMIO, callbacks and host stalls take time too. V-32 chooses and
validates its own call-duration policy. Resampling every N attempts remains a possible later
option; nothing selects it now.

**Why not per attempt:** `System.nanoTime()` costs tens of nanoseconds, more than most cached
instructions.

## WFI

The privileged specification's rule, which replaces today's. The wake condition is **a pending,
locally enabled, implemented interrupt:** `mip & mie & (MSIP | MTIP | MEIP)` is nonzero.

- **Executing WFI** retires the instruction (pc and cycle advance). If the wake condition already
  holds, it completes without stalling and the call continues. Otherwise the hart stalls and the
  call returns 1.
- **A stalled hart** checks at each call's entry, after the sample. The stall ends when the wake
  condition holds, **whatever the global enable.** The interrupt is then taken only if it is
  deliverable (`mstatus.MIE` in M-mode, always in U-mode); otherwise the hart continues after WFI.
  Without a wake, the call returns 1 with pc and cycle unchanged.
- **WFI no longer sets `mstatus.MIE`.** Firmware that waits with interrupts globally disabled
  wakes and continues, as the specification intends (Linux's idle loop works this way).
- **Wakeup and delivery are still distinct:** a hart with `mstatus.MIE` clear wakes but takes no
  trap. But a source masked in `mie` neither wakes nor is delivered. A masked, due compare no
  longer keeps waking a hart, so an embedder can sleep it.
- **The core never sleeps.** The embedder decides how long to block, for example until the compare
  deadline read against its own source, or until another event. It must wake a sleeping hart when
  an interrupt becomes enabled or an earlier deadline is published, and must not spin on a due,
  masked compare.
- **This is a deliberate, narrow policy.** The specification also permits WFI to resume for other
  reasons; the core does not. V-32 confirmed it needs neither the masked-MTIP wakeup nor WFI
  setting MIE. Its `IOProcessor.run()` wait calculation and tests migrate.

**Implemented bits only.** The hart implements three interrupts, so the spec's WARL rules apply:
- **`mie`** keeps MSIE, MTIE and MEIE on a guest write; the other bits read 0.
- **`mip`'s three bits are read-only to the guest:** MTIP follows the compare, and MSIP and MEIP
  are driven by the embedder's devices (`injectInterrupt` or a host-side edit). A guest write to
  `mip` is legal and changes nothing. V-32's firmware and Java sources do not write `mip`; its
  `MailboxHook` drives MEIP from the host.
- Host-side edits of `state.mie` and `state.mip` are not masked, so guest reads of both CSRs, the
  wake condition and the delivery check all mask with the implemented bits. A reserved bit a host
  sets reads as 0 and neither wakes nor delivers.

## Compare semantics

- **MTIP is set exactly when `time >= mtimecmp`, unsigned, and cleared otherwise.** Only the MTIP
  bit changes; MSIP, MEIP and other `mip` bits are untouched.
- **`TIMER_COMPARE_NEVER` is all ones.** It is a practically distant deadline (about 584,000 years
  at 1 MHz), not a hardware disable: a time of all ones would still be due. V-32's own cancellation
  also clears its expiration state and stops stale worker publication.
- **The embedder initializes the compare.** A new `RV32IMAState` holds `TIMER_COMPARE_NEVER`, so a
  fresh hart has no timer pending. Reset and cancellation write it too. No reset helper is needed.
- **Generation and delivery stay separate.** MTIP reflects the compare; `mie.MTIE`, and in M-mode
  `mstatus.MIE`, gate delivery. Interrupt entry and return do not clear MTIP.

## Gating timer delivery

Packet 001's AP device mask must hold back delivery while keeping the request pending. `mie.MTIE`
belongs to the firmware: a `csrw mie` can restore it and take the interrupt in the same attempt,
before a callback can reapply a host mask. V-32 therefore chose to mask in its interval-device
adapter, through the compare; the core adds no gating facility.

**The mechanism.** This is V-32's adapter, not a standard RISC-V timer mask. The core keeps
comparing time against its `mtimecmp`, which here is the *effective* delivery deadline:
- **While masked,** the effective compare is `TIMER_COMPARE_NEVER`, a distant deadline rather than
  an absolute disable. The device keeps its own *retained* deadline, pending expiration and cadence.
- **The AP-readable pending bit follows the retained deadline,** not the core's MTIP, which stays
  clear while masked.
- **Masking neither acknowledges an expiration nor advances its cadence.** Only rearming or
  cancellation clears the device's pending state.
- **Unmasking writes the retained deadline back as the effective compare.**

**Unmasking needs a fresh sample.** Restoring the compare does not by itself deliver an
expiration that is already due, because the call still uses its entry sample. Example: the call
samples 100 µs, the retained deadline is 110 µs, and an MMIO store unmasks at a real time of
120 µs. The next attempt still compares 100 against 110. To serve a pending expiration at the next
boundary, as Packet 001 requires, V-32 ends the call after the unmasking store (a controller stop
from `afterAttempt`) and starts another before the next instruction. It keeps its own reason for
that stop, so the scheduling return is not mistaken for a terminal halt with reservation cleanup.
The core adds no per-attempt sampling and no API for this.

**Mechanisms that do not work:**
- **A host-owned `mie.MTIE`** works only under a trusted firmware convention reserving the bit to
  the host.
- **Clearing MTIP in `beforeAttempt`:** the attempt's recomputation restores it.
- **`mstatus.MIE`:** it does not gate machine interrupts while the hart runs in U-mode.

## Counters (Zicntr)

| CSR | Number | Value | U-mode gate |
| --- | --- | --- | --- |
| `cycle`, `cycleh` | `0xC00`, `0xC80` | `getMcycle()` | `mcounteren.CY` |
| `time`, `timeh` | `0xC01`, `0xC81` | a fresh sample | `mcounteren.TM` |
| `instret`, `instreth` | `0xC02`, `0xC82` | `getMinstret()` | `mcounteren.IR` |
| `mcycle`, `mcycleh` | `0xB00`, `0xB80` | `getMcycle()`, read/write | M-mode only |
| `minstret`, `minstreth` | `0xB02`, `0xB82` | `getMinstret()`, read/write | M-mode only |
| `mcounteren` | `0x306` | bits CY, TM and IR; others read 0; resets to 0 | M-mode only |

These take precedence over the `CSRHook`, which never sees them.

**Work counts.**
- **`getCycle()` keeps its meaning:** instructions attempted, including trapping instructions and
  fetch faults, excluding interrupt-only entries and WFI stalls. It is not renamed or reused for
  retirement.
- **`getInstret()` counts retired instructions.** A synchronous exception does not retire,
  including ECALL, EBREAK and fetch faults. Interrupt-only entries and WFI stalls retire nothing.
  An executed WFI retires, even when it returns 1. An instruction followed by a same-attempt
  interrupt retires.
- **The guest never changes either.** Their deltas are work counts for the embedder, whatever the
  firmware writes to `mcycle` or `minstret`. The embedder may set them as part of its reset policy.

**Architectural counters.** `mcycle` is `getCycle()` plus a per-hart offset, and `minstret` is
`getInstret()` plus another. A guest write changes only the offset, so it is a cold-path state
write with no rebasing of the interpreter's local counts.

**Read ordering.** An explicit read returns the value before the reading instruction executes, as
Zicsr requires:
- a `cycle` or `mcycle` read excludes the reading instruction;
- an `instret` or `minstret` read counts the instructions retired before it;
- a `time` read is the fresh sample.

**Writes.** A write replaces the counter's value after the writing instruction, and that
instruction's own increment is suppressed, as Zicsr's update precedence requires.
- A low-half write replaces the low 32 bits; a high-half write replaces the high 32 bits. Either
  way, the other half keeps its value from before the instruction.
- A write followed by a same-attempt interrupt keeps the written value.
- Callback edits through `setMcycle`/`setMinstret` (architectural) or `setCycle`/`setInstret`
  (work) are reloaded at the next attempt, like other callback edits.

**Legality before side effects.** For every CSR access, the checks run in this order before any
read or write. Only then is the CSR read, unless the instruction is CSRRW or CSRRWI with `rd` =
x0, which skips the read and its side effects entirely. A read of `time` takes a sample, and a
read of an unknown number calls the hook:
1. the existing privilege check from the CSR number, which keeps U-mode out of the machine CSRs;
2. **write intent on a read-only number** (bits [11:10] = `11`, which includes `mvendorid` and
   the unimplemented `hpmcounter3`–`31`): an illegal-instruction exception. Write intent is decided
   from the encoding, as today: CSRRS or CSRRC with `rs1` = x0 and CSRRSI or CSRRCI with a zero
   immediate are reads. A nonzero `rs1` index holding zero still writes, and so does CSRRW or CSRRWI
   with `rd` = x0;
3. **`mcounteren` in U-mode** for the three user counters: a clear bit raises an illegal-instruction
   exception. M-mode always reads.

A trapping access calls no hook. A hook can still supply reads of other read-only numbers. A
write to a read-only number traps even with `rd` = x0.

**Retirement accounting, without per-instruction cost.**
- **In `step()`'s loop and the cached loop,** an interpreter segment ends at its first synchronous
  exception. So the segment retired its cycle delta minus one if it ended in an exception. That is
  added where the cycle counter is already published. A mid-segment `instret` read derives its value
  from the local cycle count the same way.
- **In `stepAttempts`,** one call can contain several traps and their handlers, so retirement is
  accounted per attempt, at each attempt's publication. It is never derived from the whole call's
  cycle delta.
- **`getInstret()` is published wherever `cycle` is:** before every callback and every return,
  including an executed WFI's early return, the `FENCE.I` flush-and-resume, and interrupt-only
  exits.

**Not included:** Zihpm and `mcountinhibit`. With `mcountinhibit` absent, counting behaves as if it
were 0. `0x320` still goes to the `CSRHook`, and a value the hook stores there does not inhibit the
built-in counters. `hpmcounter3`–`31` and their high halves, being read-only numbers, trap on a
write and otherwise go to the hook or read 0.

## Lifecycle and ownership

The core owns no epoch, reset policy or device. In V-32's terms:

- **Startup and whole-machine reset:** the embedder resets its epoch, writes `TIMER_COMPARE_NEVER`
  to both harts' compares, and applies its own counter reset policy. It resets the epoch only with
  both harts and its workers quiescent or coordinated, never during an in-flight call, because the
  source must not decrease within a call.
- **AP RESET** cancels the AP timer: the compare becomes `TIMER_COMPARE_NEVER`. Shared time
  continues.
- **HALT, RESUME and WFI** keep the device's request, cadence and mask state, and so the effective
  compare.
- **Successful LAUNCH** keeps the device's retained request, deadline and cadence but sets the AP
  delivery mask, so the effective compare becomes `TIMER_COMPARE_NEVER`. Today's LAUNCH reset of
  the timer and compare is V-32 code that this replaces; the timer reset goes away with
  `setTimer`.
- **Who writes the compare:**
  - The compare is hart-owner state.
  - A cross-thread change, such as the IOP programming the AP's interval, is published by the
    embedder and applied in `beforeAttempt`. If the target hart is sleeping, the embedder must also
    wake it; publishing alone can strand an earlier deadline.
  - While a hart is halted it makes no calls, so its visible device state has to live outside the
    core and be reconciled before RESUME or LAUNCH.
  - An AP MMIO acknowledgement runs on the AP thread inside the store. Unmasked, it writes the new
    effective compare directly, and the next MTIP recomputation sees it, including the one at
    MRET. Masked, it updates only the retained deadline, and the effective compare stays masked.
  - Mask, acknowledgement and queued-command changes follow one device ownership and generation
    protocol (V-32's).
- **IOP dual timers:** the interval device drives MEIP through the embedder's aggregation, and
  the register compare drives MTIP. The core recomputes only MTIP, so neither clears the other.
- **V-32's own races:** device generation and ownership for AP acknowledgements and IOP commands,
  wake signals, and the current IOP accumulation, zero-sentinel wait and `HartState` reset sites
  are V-32's migration work.

## Validation

- **One independent reference.** A copy of V-32's frozen legacy interpreter goes into the test
  sources with the changes in this design applied to it, and nothing else. It counts retirement
  per instruction rather than by the delta formula. Its diff from the frozen original is the
  documented list of intentional behavior changes; no test claims equivalence with the old
  behavior.
- **Deterministic cases with a `ManualTimeSource`,** plus a scripted source that advances on
  every read, which a fixed clock would not catch:
  - high-word rollover and high/low/high reads of `time`, `cycle` and `instret`, including a
    low-word rollover exactly at the reading instruction;
  - pre-instruction reads of `cycle` and `instret`;
  - the retirement distinctions above, including several traps and handlers in one `stepAttempts`
    call;
  - writes to each half of `mcycle` and `minstret` mid-batch, immediate reads after them, a write
    followed by a same-attempt interrupt, and callback edits of work and architectural counters;
  - legality of all six Zicsr forms on read-only numbers, `mcounteren` gating in U-mode, and no
    hook call or time sample before a trap;
  - read suppression: a writable hook CSR with separate read and write counters, where CSRRW and
    CSRRWI with `rd` = x0 write without reading;
  - `step(count <= 0)` rejected with no sample and no state change;
  - counter setters: work-count edits move the architectural view, offset edits do not;
  - two harts on one source, and time advancing across WFI stalls and calls with no execution;
  - sample counts per call, and the MTIP each callback sees, on the cached and uncached paths,
    with and without a controller, across `time` reads, delegated steps and the `FENCE.I` resume;
  - a `time` read that makes a timer interrupt deliverable, taken in the same attempt;
  - compare zero, `TIMER_COMPARE_NEVER` and equality at all ones, the threshold and re-arming;
  - WFI with MSIP, MTIP and MEIP separately, remembering that an interrupt already deliverable at
    call entry is taken before WFI executes:
    - WFI retiring without sleeping, with the source pending and locally enabled: M-mode with
      `mstatus.MIE` clear;
    - an already-deliverable source in U-mode, or in M-mode with `mstatus.MIE` set: an entry trap,
      not WFI;
    - a stall ended by a source injected after WFI stalled: in M-mode with `mstatus.MIE` set
      (delivered) and clear (continues after WFI), and in U-mode (delivered);
    - a masked, due compare that does not wake;
  - guest writes to `mie` and `mip`, which keep only the implemented, writable bits; and a reserved
    bit set by the host, which reads back as 0 and neither wakes nor delivers;
  - a delegated `time` read that crosses a deadline, followed by a compare edit, on both sides of a
    `FENCE.I` resume: every later attempt uses the read's sample, not an older one;
  - a compare changed in a callback and via MMIO before MRET, with no stale re-entry;
  - a hook-stored `mcountinhibit` that inhibits nothing.
- **Existing tests** that depend on `elapsedUs`, zero-disables compare, WFI setting MIE or the
  inclusive `cycle` read are updated to the new behavior, not kept behind a mode.
- **The decoded cache:** the suite runs cached and uncached, as `TestCores` already does.
- **The baremetal and Linux images** used by `release.sh` and the CLI must still run.
- **Bench:** compare against `0.1.8` on JVM and native. A measured regression is reported to Nate
  as a tradeoff decision; it is not a reason to keep a known shortcut. This version becomes the
  baseline for the execution API's attempt-fusion cost.

V-32's device and integration tests stay in its repository: interval MMIO, masked pending reads,
an acknowledgement while masked, LAUNCH with an overdue retained request, the advancing-clock
unmask example above, and reset races.

## Status

V-32's review of revision 5 answered both remaining questions and asked for no further design
change. Revision 6 records its integration details. The design waits for Nate's go.

## V-32 review: answers and remaining design corrections

2026-10-11. **The shared-source direction fits Packet 001. Settle the sampling,
counter ordering and WFI details below before implementation.** This review is
based on the live V-32 AP/IOP loops, `HartState`, the retained attempt oracle and
the RISC-V specifications. It does not authorize implementation or adoption.
V-32 repository references below are relative to the V-32 repository root.

### Answers to the five questions

1. **Sampling:** per-call sampling plus fresh `time/timeh` reads is an acceptable
   starting option, but not an unconditional latency guarantee for V-32. The
   current AP calls the core with budget 1; the IOP uses 1,024 instructions.
   A future attempt batch and replacement scheduling quantum are unselected.
   An instruction budget alone does not bound wall time, particularly with
   MMIO/callback work and host stalls. Keep per-attempt compare/interrupt checks,
   and document that a deadline crossed without a fresh sample is deferred until
   a later sample. V-32 must choose and validate its call-duration policy before
   adopting this option. An explicit configurable N-attempt refresh is a possible
   alternative, not a selected requirement; do not select N or restart timing
   work on our behalf. The 1-microsecond minimum device period is representability,
   not a promise of 1-microsecond host service latency.

2. **Construction:** the third constructor argument is fine. No builder is
   needed for this consumer. Preserve deliberate legacy construction and make
   source-mode selection explicit; use the same source for both cores.

3. **Machine counters:** include `mcycle/minstret` and their RV32 high halves,
   with M-mode access checks and explicit write/read ordering. This is a V-32
   design recommendation for the requested counter work, not implementation
   permission. See the accounting correction below.

4. **Legacy stale MTIP:** a fix is desirable, but keep it a separately identified
   behavior change. For this proposal, preserving legacy mode is acceptable.
   If Nate selects the fix for legacy mode too, document its expected oracle
   difference and test it explicitly; do not alter the frozen reference or
   describe changed behavior as historical equivalence. Source-mode recomputation
   before CSR/MRET interrupt reevaluation is appropriate.

5. **Initialization:** writing `TIMER_COMPARE_NEVER` is simple enough; a core
   reset helper is unnecessary. V-32 owns reset policy. Document that the helper
   constant is a practically distant deadline, not a hardware disable bit:
   equality at all ones is still due. Cancellation also clears V-32 expiration
   state and prevents stale worker publication.

### Counter corrections before claiming conformance

- **Counter read ordering:** keeping the legacy `cycle` read inclusive of its
  own instruction conflicts with the Zicsr rule that an explicit CSR read sees
  the value before that instruction executes. Keep the historical behavior in
  the legacy configuration if needed, but define conforming `cycle/cycleh` and
  machine-alias reads when the counter extension is enabled. Cover low-word
  rollover exactly at the reading instruction. [Zicsr CSR ordering](https://docs.riscv.org/reference/isa/v20260120/unpriv/zicsr.html).
- **Writable aliases break the proposed delta formula:** a guest write to
  `mcycle/mcycleh` makes the cycle delta cease to represent attempted work.
  Likewise a `minstret/minstreth` write must survive final publication; its
  explicit write replaces that instruction's implicit update. Track local work
  separately from writable architectural counter values, or split/rebase at
  those writes. Test writes in the middle of a batch, both halves, immediate
  reads, callback counter edits and a write followed by same-attempt interrupt
  entry. A guest-writable `instret` delta is also not an unconditional scheduler
  work count. [Zicsr update precedence](https://docs.riscv.org/reference/isa/v20260120/unpriv/zicsr.html).
- **Read-only means write intent:** CSRRS/CSRRC with `rs1=x0` and immediate
  set/clear with zero are legal reads. A nonzero register index holding zero
  still requests a write and must trap on a read-only counter; CSRRW/CSRRWI
  also write with `rd=x0`. Check legality before source/hook side effects and
  test all forms. [Zicsr access rules](https://docs.riscv.org/reference/isa/v20260120/unpriv/zicsr.html).
- Publish `instret` before every committed callback and every return, including
  executed WFI's early return, FENCE.I flush/resume and interrupt-only exits.
  “Batch ends on an exception” applies to an interpreter segment; an entire
  `stepAttempts` call can contain several traps and handler instructions.
  Subtracting only one exception from that whole call is incorrect.
- Specify the flag-off CSR/hook behavior as well as flag-on precedence. Optional
  `mcountinhibit` may remain absent (counting behaves as if it were zero); custom
  hook handling must not accidentally imply inhibition of built-in counters.
  [Machine counter-inhibit contract](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html).

### Time, WFI and ownership clarifications

- The WFI wording conflates wakeup with interrupt delivery. Existing V-32 relies
  on a due compare ending an already-stalled WFI even with MTIE clear; delivery
  remains separately gated. Preserve that behavior and test both WFI execution
  with an already-due compare and a deadline becoming due during a stall.
  An already-due masked compare can therefore prevent the embedder from sleeping;
  delivery masking alone is not timer cancellation.
- Packet 001's AP device mask must gate delivery while retaining compare/pending
  state. A `beforeAttempt` callback cannot mask by clearing MTIP: the subsequent
  comparison will restore it. Name the supported delivery-gating path and its
  privilege behavior during V-32 integration; do not assume AP U-mode can write
  machine CSRs or that `mstatus.MIE` suppresses M interrupts from U-mode.
- State whether a fresh `time/timeh` sample immediately refreshes MTIP, and
  whether CSR/MRET reevaluation resamples or only compares the last sample.
  Neither sentence currently fully defines the ordering when a deadline crosses
  mid-call. Internal FENCE.I flush/resume and delegated execution must not silently
  turn one public call into extra call-entry samples. Test sample counts and
  MTIP observations at callbacks and across those internal exits.
- `getTimer()` is a last sample, not a live clock while a hart is halted. Device
  workers, interval ACK cadence calculation and WFI deadline calculations must
  read the shared source directly when current time is needed. V-32's current
  IOP accumulation, zero-sentinel wait condition and `HartState` reset are migration
  sites; they do not yet implement this target. Reset the epoch only with both
  harts/workers quiescent or coordinated, never during an in-flight old call.
- Owner-thread compare application also needs publication plus a wake signal:
  queuing an earlier deadline for a sleeping hart without waking it can strand
  the request. AP MMIO ACKs need the same device generation/ownership discipline
  as IOP commands so a stale queued command cannot resurrect canceled service.
  V-32 owns those races; applying commands only in `beforeAttempt` is insufficient
  for a halted hart's visible device state unless retained service is represented
  outside the core and reconciled before resume/LAUNCH.

Keep the independent IOP interval MEIP and register-compare MTIP facilities,
AP LAUNCH retention and AP RESET cancellation as written. Add deterministic
source-mode cases for the clarifications above, separately from legacy oracle
equivalence. No runtime tests, benchmark or GUI checks were run for this review.

## CPU-agent revision 3: what changed

2026-10-11. The draft's five questions were: (1) sampling per call plus per `time` read, (2) a
third constructor argument or a builder, (3) including `mcycle`/`minstret`, (4) fixing stale MTIP
in legacy mode, and (5) a core helper to initialize the compare. The design above now reflects
V-32's answers and corrections:

- **Answers adopted:** per-call sampling, with deferred deadlines documented and no latency
  guarantee; the third constructor argument with explicit source-mode selection; the machine
  counters; legacy stale MTIP preserved, with a fix left as a separate decision for Nate; no
  compare helper, and `TIMER_COMPARE_NEVER` documented as a distant deadline rather than a disable.
- **Counter corrections:**
  - Reads are pre-instruction with Zicntr on.
  - Writes replace the value and suppress the instruction's own increment.
  - Legality (write intent, then `mcounteren`) is checked before any read, hook call or sample.
  - Retirement is accounted per segment, or per attempt in `stepAttempts`, and published at every
    callback and return.
  - Work counts (`getCycle()`, `getInstret()`) are separated from the guest-writable `mcycle` and
    `minstret` through offsets.
  - Behavior with Zicntr off and `mcountinhibit` is specified.
- **Time:** sample points are exact (one per public call, none for internal exits), a `time` read
  refreshes MTIP with same-attempt delivery, and reevaluation compares without resampling.
  `getTimer()` is documented as a last sample.
- **WFI and delivery:** wakeup and delivery are described separately, and the delivery-gating
  mechanisms are compared, with a recommendation for Packet 001's mask.
- **Ownership:** wake signals, halted-hart device state, epoch resets only when quiescent, and
  V-32's migration sites are recorded.
- **Validation:** an extended copy of the frozen interpreter is the reference for new behavior, and
  the cases V-32 listed are added.

## CPU-agent revision 4: what changed

2026-10-12. On Nate's direction, legacy mode is gone: the core follows V-32's needs and, where
cheap to maintain, the specification, and keeps no old shortcuts for their own sake.

- **Time:** the shared source is the only model. `elapsedUs`, per-hart accumulation and `setTimer`
  go away; `ManualTimeSource` serves tests, bench and the CLI's fixed-update mode.
- **Compare:** standard everywhere, and a new state starts at `TIMER_COMPARE_NEVER`.
- **WFI:** the specification's rule replaces the MIE-setting deviation and the masked-MTIP wakeup.
  A host-side `mie.MTIE` mask now gates both wakeup and delivery, which simplifies Packet 001's
  mask. Question 2 asks whether V-32 needs the old wakeup.
- **CSRs:** Zicntr is always implemented (no `IsaConfig` flag), reads are always pre-instruction,
  and every read-only number traps on write intent. Stale MTIP is fixed everywhere.
- **Validation:** one reference, the frozen interpreter with this design's changes applied; its diff
  is the list of intentional changes. Existing tests move to the new behavior. Bench against
  `0.1.8` is a performance gate only.

## V-32 review of revision 4

2026-10-12. The single shared-source design is the right direction. Nate's
current guidance supersedes this document's earlier requests to preserve legacy
construction, counter ordering and masked-timer wakeups: temporary V-32 breakage
is acceptable during migration. Preserve behavior for a concrete consumer need
or affordable specification fidelity, not historical equivalence. This review
does not start implementation or V-32 adoption.

### Answers to the five questions

1. **Counters:** yes. Guest-proof attempted/retired work counts plus writable
   architectural offsets meet the accounting requirement. Use `getInstret()`
   deltas for host retirement accounting, not `getMinstret()` deltas. Define
   host setters precisely: changing a work count with a fixed offset also
   changes the architectural view; reset must deliberately initialize both.
2. **WFI:** V-32 does not require masked-MTIP wakeups or WFI setting MIE.
   `firmware/iop/start.S:wait_for_mail` explicitly sets MEIE and MIE before WFI;
   its error/stop loops clear `mie` and may stay asleep. The current
   `IOProcessor.run()` wait calculation does assume masked-compare wakeups;
   migrate that code and its tests instead of preserving the CPU deviation.
   The host must wake a sleeping hart when an interrupt becomes enabled or an
   earlier deadline is published, and must not spin on a due, masked compare.
3. **Delivery gating:** host-owned MTIE is workable if AP firmware cooperates,
   but is not yet an unconditional implementation of Packet 001's device mask.
   A firmware `csrw mie` can restore MTIE and take a same-attempt interrupt
   before another callback can reapply the mask. Specify ownership: either a
   trusted firmware convention reserves this bit to the host, or a distinct
   effective delivery gate combines firmware enable with the device mask.
   Do not select a new CPU facility without that integration decision. Keep
   device pending state and cadence independent of either enable.
4. **Read-only CSRs:** no production V-32 dependency on those writes was found.
   Both AP and IOP pass a null `CSRHook`. Rejecting write intent is appropriate.
5. **Time reads:** yes, fresh `time/timeh` sampling, immediate MTIP refresh and
   delivery after that instruction retires are acceptable. Publish the read
   result, retirement and interrupt state before `afterAttempt`. This is the
   chosen emulator sampling policy, not a requirement that hardware timer
   delivery occur specifically on a CSR read.

### Remaining corrections

- **CSR read suppression:** legality checks must precede side effects, but a
  legal CSRRW/CSRRWI with `rd=x0` must also skip the CSR read and its hook.
  The current statement that legality is followed by a read is too broad.
  Test a writable custom CSR with separate read/write hook counters; read-only
  write attempts still trap, even with `rd=x0`. See the
  [Zicsr access rules](https://docs.riscv.org/reference/isa/v20260120/unpriv/zicsr.html).
- **Supported WFI sources:** express the predicate over implemented interrupt
  bits, not arbitrary writable/reserved bits in `mip & mie`. Wake independent
  of global MIE, then apply privilege/global-enable rules to delivery. Cover
  MSIP, MTIP and MEIP separately, M-mode with MIE clear and U-mode, plus the
  case where WFI itself retires without sleeping. The specification permits
  additional wakeups; the narrower policy here is a valid emulator choice.
  See [machine-level WFI](https://github.com/riscv/riscv-isa-manual/blob/main/src/priv/machine.adoc).
- **One-call sampling:** the execution design's uncached wrapper needs an
  internal entry that reuses the sample while retaining per-attempt checks.
  Repeated public `step(count=1)` calls would sample repeatedly. Apply identical
  sampling rules to cached, uncached and independent-reference paths.
- **Ordering of work:** the introduction says either design may land first,
  whereas execution revision 4 requires this one first. Use timer/counters
  first consistently. Define `step(count <= 0)` explicitly as well; rejecting
  it before sampling or mutation is acceptable to V-32, with caller migration.
- **Performance:** compare against both 0.1.8 and the new timer/counter baseline
  when measuring later attempt fusion. Correctness changes have costs; report
  a measured regression for a tradeoff decision rather than retaining a known
  shortcut merely to satisfy an absolute no-regression gate.

The shared epoch, unsigned compare including zero, counter ordering, independent
IOP timer facilities and AP lifecycle rules otherwise fit the requested target.
Review evidence is the live AP/IOP adapters, IOP firmware and the cited specs.
No implementation, runtime tests, benchmarks or GUI validation were performed.

## CPU-agent revision 5: what changed

2026-10-12. The design above now reflects V-32's review of revision 4:

- **Answers adopted:** the counter split, with setter behavior defined and host accounting on
  `getInstret()`; the narrow WFI wake policy, with V-32 migrating `IOProcessor.run()`; read-only
  write traps; `time`-read sampling with publication before `afterAttempt`.
- **Corrections:**
  - CSRRW and CSRRWI with `rd` = x0 skip the read and its hook.
  - The WFI wake condition covers only implemented interrupt bits.
  - Sampling is once per public call on every path, including the uncached attempt entry and the
    reference.
  - This design lands first, consistently with the execution design.
  - `step(count <= 0)` throws before any sample or state change.
  - A bench regression goes to Nate as a tradeoff, not a hard gate.
- **Delivery gating:** a host-owned `mie.MTIE` is no longer recommended, since the firmware also
  owns it. The recommendation is to mask at the device by presenting `TIMER_COMPARE_NEVER`
  (Question 1).
- **New, from checking the WFI predicate:** `mie` and `mip` writes were unmasked. `mie` now keeps
  only the implemented enables, and guest writes leave `mip`'s read-only bits alone (Question 2).

## V-32 review of revision 5

2026-10-12. Revision 5 resolves the previous counter, CSR-read-suppression and
sampling findings. Device-side masking is a workable integration direction;
the distinctions below are needed to preserve Packet 001. No legacy mode is
requested, and this review does not authorize implementation or adoption.

### Answers to the two questions

1. **Delivery gating:** yes, presenting a distant compare while masked can
   serve V-32 without a new core gating API. Treat the core compare as the
   effective delivery deadline, separate from the device's retained deadline.
   The AP-readable pending bit must still reflect expiration of the retained
   deadline while masked. It must not simply mirror the now-cleared core MTIP.
   Masking must neither acknowledge an expiration nor advance its cadence;
   rearming/cancellation remains the way to clear device timer pending.
2. **`mip` writes:** no such writes were found in the live `firmware`,
   `baremetal` or production Java sources. `MailboxHook.updateInterrupt`
   asserts/clears MEIP on the host from mailbox and terminal-device state;
   firmware acknowledges through MMIO. Guest writes leaving MSIP/MTIP/MEIP
   unchanged fit this consumer and the
   [machine interrupt CSR rules](https://github.com/riscv/riscv-isa-manual/blob/main/src/priv/machine.adoc).

### Remaining integration details

- **Unmasking versus a stale sample:** restoring the real compare does not
  necessarily deliver an already-pending expiration at the next attempt.
  Example: the batch samples 100 us, the retained deadline is 110 us, and an
  MMIO store unmasks at real time 120 us. The next attempt still compares 100
  against 110. Packet 001 requires an eligible pending source to request
  service at the next boundary. V-32 must arrange a fresh sample before that
  boundary, for example by ending this call after the unmasking store and
  starting another before executing the next instruction. Document that
  obligation rather than promising that restoring the compare alone suffices.
  No general per-attempt sampling or new core API is requested here.
- **LAUNCH and ACK:** replace the lifecycle statement that successful LAUNCH
  leaves the core compare alone. LAUNCH retains the device request/deadline
  and cadence, but sets the AP delivery mask, so under this scheme the core
  compare becomes `TIMER_COMPARE_NEVER`. A masked AP ACK similarly updates
  the retained deadline while leaving the effective compare masked. HALT and
  RESUME preserve masks; AP RESET cancels the request. Apply mask, ACK and
  queued command changes under one device ownership/generation protocol.
- **Describe the masking mechanism accurately:** this is V-32's adapter for
  its interval device, not a standard RISC-V timer mask. The core continues
  to compare against its effective `mtimecmp`. As already documented, all
  ones is a distant deadline, not an absolute disable at that timestamp.
- **CSR reads after host edits:** writes and interrupt predicates are now
  masked, but explicitly mask guest reads of `mie`/`mip` too. Otherwise a
  host edit containing a reserved bit contradicts the promised read-as-zero
  behavior. Add a readback case alongside the no-wake/no-delivery case.
- **WFI test setup:** when an interrupt is already deliverable at call entry,
  the interrupt-only attempt occurs before WFI executes. To test WFI retiring
  with a pending, locally enabled source, use M-mode with global MIE clear.
  Test U-mode/preexisting deliverable interrupts as entry traps, and test
  U-mode stalled wakeup by injecting the interrupt after WFI has stalled.

Add V-32 integration cases for masked pending reads, ACK while masked,
LAUNCH with an overdue retained request, and the advancing-clock unmask
example. Core tests cover CSR readback and WFI ordering. These are validation
requirements for implementation, not tests run by this review.

## CPU-agent revision 6: what changed

2026-10-12. The design above now records V-32's review of revision 5:

- **Answers recorded:** V-32 masks at its interval device through the compare, and no V-32 code
  writes `mip`.
- **Correction:** revision 5 said that restoring the compare on unmask raises MTIP at the next
  attempt. With a stale sample it does not; V-32 ends the call after the unmasking store.
- **Masking described accurately:** effective versus retained deadlines, the AP-readable pending
  bit following the retained deadline, and masking that neither acknowledges nor advances cadence.
- **Lifecycle:** successful LAUNCH masks AP delivery, so the effective compare becomes
  `TIMER_COMPARE_NEVER`; a masked acknowledgement updates only the retained deadline.
- **Core:** guest reads of `mie` and `mip` are masked too. The WFI tests account for entry traps
  preceding WFI. A test covers a delegated `time` read's sample surviving the `FENCE.I` resume.

## V-32 confirmation of revision 6

2026-10-12. **Confirmed: this design is settled from V-32's side.** Revision 6
addresses the remaining review findings: retained versus effective deadlines,
fresh sampling after unmasking, LAUNCH and masked-ACK behavior, CSR readback
masking, WFI test ordering and preservation of refreshed samples across resume.
No further design changes or answers are requested before implementation.

The documented V-32 adapter work and validation cases remain implementation
obligations. Timer/counters land before the execution API. Temporary consumer
breakage is acceptable under Nate's stated direction; no legacy mode is needed.
This confirmation is a design review, not implementation authorization or a
claim of passing runtime tests. Implementation awaits Nate's go.
