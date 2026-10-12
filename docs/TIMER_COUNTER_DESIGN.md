# Shared machine time, timer compare and Zicntr: design

Date: 2026-10-12. From the CPU agent. **Revision 6, implemented** in steps T1–T7 of
[the implementation plan](TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md); see [Status](#status). V-32
confirmed this revision. Since revision 4 there is no legacy mode, on Nate's direction: the core
follows what V-32 needs and, where that stays cheap to maintain, the RISC-V specification. It
keeps no old shortcuts for their own sake. V-32's reviews of every revision and the per-revision
change lists are archived in
[TIMER_COUNTER_DESIGN_REV6_WITH_REVIEWS_2026-10-12.md](archive/TIMER_COUNTER_DESIGN_REV6_WITH_REVIEWS_2026-10-12.md).

**Inputs:**
- V-32's "Timer and counter extension implementation requests" in
  [the archived execution design](archive/EXECUTION_API_DESIGN_REV6_WITH_REVIEWS_2026-10-12.md#timer-and-counter-extension-implementation-requests).
- V-32's Packet 001 decision record, in its repository at
  `docs/decisions/001-clocks-timer-events.md` (C1 time source, C4 IOP timers, C5 lifecycle).
- V-32's reviews of the first draft and of revisions 4 and 5, in the archived copy.
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

## What changed from 0.1.8

The core's behavior in `0.1.8`, as V-32's timer audit found and the code confirmed, and what
replaced it:

| `0.1.8` | Replaced by |
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
- **The baremetal image** used by `release.sh` and the CLI's `IntegrationTest` must still run.
  This repository has no Linux image; mini-rv32ima's was never carried over.
- **Bench:** compare against `0.1.8` on JVM and native. A measured regression is reported to Nate
  as a tradeoff decision; it is not a reason to keep a known shortcut. This version becomes the
  baseline for the execution API's attempt-fusion cost.

V-32's device and integration tests stay in its repository: interval MMIO, masked pending reads,
an acknowledgement while masked, LAUNCH with an overdue retained request, the advancing-clock
unmask example above, and reset races.

## Status

Implemented on `main` for `0.1.10-SNAPSHOT`, in the plan's steps:

| Step | Scope | Commit |
| --- | --- | --- |
| T1 | `PostExecHook` jump-PC fix | `3f4fdda` |
| T2 | Independent reference core and differential | `08d26db` |
| T3 | Shared time source, standard compare | `fdd68f0` |
| T4 | Standard WFI, implemented-only `mie`/`mip` | `525c925` |
| T5 | Read-only CSR write traps | `5abce6e` |
| T6 | Zicntr counters and `time` reads | `23bbc03` |
| T7 | Phase close: docs, bench, release check | see the plan |

The `stepAttempts` parts of this design (sampling after the first `beforeAttempt`, per-attempt
retirement, callback-visible MTIP) land with the [execution API](EXECUTION_API_DESIGN.md) in
phase A.
