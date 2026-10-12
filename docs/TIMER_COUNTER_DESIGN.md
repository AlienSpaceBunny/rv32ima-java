# Shared machine time, timer compare and Zicntr: design

Date: 2026-10-11. From the CPU agent. **Revision 3**, after V-32's review of the first draft (kept
below). Nothing here is implemented, and implementation waits for Nate's go. The changes are
listed at the end.

**Inputs:**
- V-32's "Timer and counter extension implementation requests" in
  [EXECUTION_API_DESIGN.md](EXECUTION_API_DESIGN.md#timer-and-counter-extension-implementation-requests).
- V-32's Packet 001 decision record, in its repository at
  `docs/decisions/001-clocks-timer-events.md` (C1 time source, C4 IOP timers, C5 lifecycle).
- V-32's review of the first draft, below.
- The [Zicntr](https://docs.riscv.org/reference/isa/v20260120/unpriv/counters.html),
  [Zicsr](https://docs.riscv.org/reference/isa/v20260120/unpriv/zicsr.html) and
  [machine-level](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)
  specifications.

This targets the API V-32 should move to, not how it calls the core today. It is independent of
the [execution API](EXECUTION_API_DESIGN.md), and either can land first.

## Summary

- **A shared time source, opt-in per core.** The embedder passes one `MachineTimeSource` to each
  hart's core. It supplies the machine's 64-bit microsecond timeline; V-32's production source is
  `(System.nanoTime() - epochNanos) / 1000`. The core samples it at documented points and never
  accumulates or rebases time.
- **Standard compare in source mode:** MTIP is `time >= mtimecmp`, unsigned, and zero is not
  special.
- **Full Zicntr behind an `IsaConfig` flag:** `cycle`, `time`, `instret` and their high halves,
  the machine aliases `mcycle` and `minstret`, and `mcounteren` gating for U-mode. Reads and
  writes follow Zicsr's ordering rules, and legality is checked before any side effect.
- **Work counts and architectural counters are separate.** `getCycle()` and a new `getInstret()`
  count attempted and retired instructions and are never changed by the guest. The guest-writable
  `mcycle` and `minstret` are views on them through per-hart offsets.
- **Legacy mode is unchanged.** A core without a source keeps `elapsedUs` accumulation and the
  zero-disables compare; with Zicntr off, CSR behavior is exactly today's. That is the historical
  oracle's contract.
- **The hot loops stay as they are.** Time is sampled once per call and on `time` reads, not per
  instruction. Retirement is counted where the cycle counter is already published.

## Baseline

What the core does today, as V-32's timer audit found and the code confirms:

- Each hart's `mtime` lives in `RV32IMAState` and grows by the caller's `elapsedUs`.
- MTIP is set when `mtimecmp != 0` and `mtime >= mtimecmp`; zero disables the timer. A due compare
  ends a WFI stall even with MTIE clear.
- Only the low half of `cycle` (`0xC00`) is built in. The cycle counter is incremented before the
  instruction executes, so a `cycle` read includes the reading instruction.
- There is no `cycleh`, `time`, `instret` or `mcounteren`. Other unknown CSRs go to the `CSRHook`
  or read 0.
- Write intent is already decoded correctly: CSRRW and CSRRWI always write, CSRRS and CSRRC write
  when the `rs1` index is nonzero, CSRRSI and CSRRCI when the immediate is nonzero. But nothing
  traps a write to a read-only CSR number, and the read (including a hook's read) happens before
  any check except privilege.
- Interrupt reevaluation after a CSR write or MRET does not recompute MTIP. If a handler moves the
  compare through MMIO and then executes MRET, the stale MTIP can re-enter the handler.

## API

```java
/** The machine's shared timeline. Thread-safe; must never decrease during a call into a core. */
@FunctionalInterface
public interface MachineTimeSource {
    /** Microseconds since the machine epoch, as an unsigned 64-bit value. */
    long nowMicros();
}

public RV32IMACore(IsaConfig isa, DecodedCacheConfig cache, MachineTimeSource time);

// IsaConfig gains a hasZicntr component.

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

- **Source mode** is a core built with a non-null source. One source instance serves both harts on
  the machine timeline. Legacy construction stays available, and source mode is always an
  explicit choice.
- **`elapsedUs` must be 0 in source mode.** `step` and `stepAttempts` throw
  `IllegalArgumentException` otherwise, before any callback or state change. That catches a
  half-migrated caller.
- **`state.getTimer()` is the hart's latest sample, not a live clock.** It goes stale while a hart
  is halted or between calls. Anything that needs current time (device workers, interval
  acknowledgement cadence, WFI sleep deadlines) reads the shared source directly. A write to it
  is overwritten by the next sample.
- **Zicntr and source mode are independent.** Without a source, `time` reads the legacy
  accumulated timer.

## Time sampling

In source mode the core samples the source at exactly these points:

1. **Once per public call,** at entry, after argument validation and the first `beforeAttempt`.
   That includes a call with a nonpositive budget. Internal exits do not sample again: the
   `FENCE.I` flush-and-resume and a delegated `step(count=1)` inside the decoded cache keep the
   call's sample.
2. **On every legal `time` or `timeh` read.** Each read samples afresh, so the RV32 high/low/high
   retry loop works and a waking or halted hart never reads stale time. A read that traps (U-mode
   with `mcounteren.TM` clear, or a write to the read-only CSR) does not sample.

Each sample becomes the hart's latest and **immediately recomputes MTIP**. If that makes a timer
interrupt deliverable, the read triggers the same in-instruction reevaluation as a write to
`mstatus`, `mie` or `mip`: the interrupt is taken after the reading instruction retires, in the
same attempt.

MTIP is also recomputed **from the latest sample, without sampling again:**
- at every attempt's entry in `stepAttempts` and at every `step` entry, so a callback's compare
  change is seen at the next attempt;
- at interrupt reevaluation after a CSR write or MRET. This is new; it removes the stale-MTIP
  re-entry above.

**Deferred deadlines.** A deadline crossed in the middle of a call is noticed at the next sample:
the next call or the next `time` read. This is not a latency guarantee. An instruction budget does
not bound wall time, because MMIO, callbacks and host stalls take time too. V-32 chooses and
validates its own call-duration policy before adopting source mode. Resampling every N attempts
remains a possible later option; nothing selects it now.

**Why not per attempt:** `System.nanoTime()` costs tens of nanoseconds, more than most cached
instructions.

## WFI: wakeup is not delivery

These are today's rules, kept in source mode:

- **Executing WFI** retires the instruction (pc and cycle advance). If an interrupt is already
  deliverable, it returns 0 and the interrupt is taken at the next entry. Otherwise the hart stalls
  and the call returns 1.
- **A stalled hart** checks at each call's entry, after the sample:
  - **A set MTIP ends the stall, even with MTIE clear.** The hart then continues after WFI without
    taking an interrupt; delivery is gated separately.
  - Any other pending interrupt ends the stall only if it is deliverable.
  - Otherwise the call returns 1 with pc and cycle unchanged.
- **Consequence:** a due compare with MTIE clear keeps waking the hart. Masking delivery does not
  cancel the timer, so an embedder cannot sleep a hart on a due, masked compare.
- **The core never sleeps.** The embedder decides how long to block, for example until the compare
  deadline read against its own source, or until another event.

## Compare semantics

- **In source mode, MTIP is set exactly when `time >= mtimecmp`, unsigned, and cleared
  otherwise.** Only the MTIP bit changes; MSIP, MEIP and other `mip` bits are untouched.
- **`TIMER_COMPARE_NEVER` is all ones.** It is a practically distant deadline (about 584,000 years
  at 1 MHz), not a hardware disable: a time of all ones would still be due. V-32's own cancellation
  also clears its expiration state and stops stale worker publication.
- **The embedder initializes the compare.** A new `RV32IMAState` holds 0, which in source mode is
  immediately due, so a hart with compare 0 cannot stay in WFI. Startup, reset and cancellation
  write `TIMER_COMPARE_NEVER`. No core helper is needed.
- **Generation and delivery stay separate.** MTIP reflects the compare; `mie.MTIE`, and in M-mode
  `mstatus.MIE`, gate delivery. Interrupt entry and return do not clear MTIP.

## Gating timer delivery

Packet 001's AP device mask must hold back delivery while keeping the request pending. The
mechanisms behave as follows:

- **Clearing MTIP in `beforeAttempt` does not work.** The attempt's recomputation restores it.
- **`mstatus.MIE` does not work.** It does not gate machine interrupts while the hart runs in
  U-mode, and WFI sets it unconditionally, which is one of the core's documented deviations.
- **The embedder clearing `mie.MTIE`** (a host-side state edit, not a guest CSR access) gates
  delivery. MTIP still follows the compare, so a due compare still wakes WFI.
- **Holding the compare at `TIMER_COMPARE_NEVER` while the device is masked** gates generation as
  well, so the hart can sleep. The deadline and any expiration stay in V-32's device state and are
  reinstalled when it unmasks.

**Recommendation:** hold the compare for Packet 001's mask, and clear `mie.MTIE` only where a
masked hart must still wake from WFI. The choice is V-32's (Question 2).

## Counters (Zicntr)

| CSR | Number | Value | U-mode gate |
| --- | --- | --- | --- |
| `cycle`, `cycleh` | `0xC00`, `0xC80` | `getMcycle()` | `mcounteren.CY` |
| `time`, `timeh` | `0xC01`, `0xC81` | a fresh sample (source mode) or the legacy timer | `mcounteren.TM` |
| `instret`, `instreth` | `0xC02`, `0xC82` | `getMinstret()` | `mcounteren.IR` |
| `mcycle`, `mcycleh` | `0xB00`, `0xB80` | `getMcycle()`, read/write | M-mode only |
| `minstret`, `minstreth` | `0xB02`, `0xB82` | `getMinstret()`, read/write | M-mode only |
| `mcounteren` | `0x306` | bits CY, TM and IR; others read 0; resets to 0 | M-mode only |

**Work counts.**
- **`getCycle()` keeps today's meaning:** instructions attempted, including trapping instructions
  and fetch faults, excluding interrupt-only entries and WFI stalls. It is not renamed or reused for
  retirement.
- **`getInstret()` counts retired instructions.** A synchronous exception does not retire,
  including ECALL, EBREAK and fetch faults. Interrupt-only entries and WFI stalls retire nothing.
  An executed WFI retires, even when it returns 1. An instruction followed by a same-attempt
  interrupt retires.
- **The guest never changes either.** Their deltas are work counts for the embedder, whatever the
  firmware writes to `mcycle` or `minstret`. The embedder may set them as part of its reset policy.

**Architectural counters.** `mcycle` is `getCycle()` plus a per-hart offset, and `minstret` is
`getInstret()` plus another. A guest write changes only the offset, so it is a cold-path state
write with no rebasing of the interpreter's local counts. Without Zicntr nothing writes the
offsets, so they stay 0.

**Read ordering (Zicntr on).** An explicit read returns the value before the reading instruction
executes, as Zicsr requires:
- a `cycle` or `mcycle` read excludes the reading instruction, unlike today's `0xC00`;
- an `instret` or `minstret` read counts the instructions retired before it;
- a `time` read is the fresh sample.
- With Zicntr off, `0xC00` keeps today's inclusive read.

**Writes.** A write replaces the counter's value after the writing instruction, and that
instruction's own increment is suppressed, as Zicsr's update precedence requires.
- A low-half write replaces the low 32 bits; a high-half write replaces the high 32 bits. Either
  way, the other half keeps its value from before the instruction.
- A write followed by a same-attempt interrupt keeps the written value.
- Callback edits through `setMcycle`/`setMinstret` (architectural) or `setCycle`/`setInstret`
  (work) are reloaded at the next attempt, like other callback edits.

**Legality before side effects.** For a built-in counter with Zicntr on, the checks run in this
order, and only then is the counter read (which for `time` takes a sample):
1. the existing privilege check from the CSR number, which keeps U-mode out of the machine aliases;
2. **write intent on a read-only CSR:** an illegal-instruction exception. Write intent is decided
   from the encoding, as today: CSRRS or CSRRC with `rs1` = x0 and CSRRSI or CSRRCI with a zero
   immediate are reads. A nonzero `rs1` index holding zero still writes, and so does CSRRW or CSRRWI
   with `rd` = x0;
3. **`mcounteren` in U-mode:** a clear bit raises an illegal-instruction exception. M-mode always
   reads.

Whether the read-only rule covers every CSR number with bits [11:10] = `11` (as the specification
requires, including hook-handled numbers such as `mvendorid` and `hpmcounter3`–`31`) or only the
built-in counters is Nate's decision (Question 3). Either way, a trapping access calls no hook.

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
- The retirement count is kept with Zicntr on or off; the bench gate below checks that this costs
  nothing measurable.

**Zicntr off.** CSR and hook behavior is exactly today's: only `0xC00` is built in (inclusive read),
every other number goes to the `CSRHook` or reads 0, and nothing traps for read-only numbers or
`mcounteren`. `getInstret()` still counts.

**Not included:** Zihpm and `mcountinhibit`. With `mcountinhibit` absent, counting behaves as if it
were 0. `0x320` still goes to the `CSRHook`, and a value the hook stores there does not inhibit the
built-in counters. With Zicntr on, the built-in CSRs in the table take precedence over the hook.

## Lifecycle and ownership

The core owns no epoch, reset policy or device. In V-32's terms:

- **Startup and whole-machine reset:** the embedder resets its epoch, writes `TIMER_COMPARE_NEVER`
  to both harts' compares, and applies its own counter reset policy. It resets the epoch only with
  both harts and its workers quiescent or coordinated, never during an in-flight call, because the
  source must not decrease within a call.
- **AP RESET** cancels the AP timer: the compare becomes `TIMER_COMPARE_NEVER`. Shared time
  continues.
- **HALT, RESUME, WFI and successful LAUNCH** leave the compare alone, so an IOP-configured request
  and its cadence survive. Today's LAUNCH reset of the timer and compare is V-32 code. In source
  mode, resetting the timer has no effect, and the compare must be left alone.
- **Who writes the compare:**
  - The compare is hart-owner state.
  - A cross-thread change, such as the IOP programming the AP's interval, is published by the
    embedder and applied in `beforeAttempt`. If the target hart is sleeping, the embedder must also
    wake it; publishing alone can strand an earlier deadline.
  - While a hart is halted it makes no calls, so its visible device state has to live outside the
    core and be reconciled before RESUME or LAUNCH.
  - An AP MMIO acknowledgement runs on the AP thread inside the store, and can write the compare
    directly. The next MTIP recomputation sees it, including the one at MRET.
- **IOP dual timers:** the interval device drives MEIP through the embedder's aggregation, and
  the register compare drives MTIP. The core recomputes only MTIP, so neither clears the other.
- **V-32's own races:** device generation and ownership for AP acknowledgements and IOP commands,
  wake signals, and the current IOP accumulation, zero-sentinel wait and `HartState` reset sites
  are V-32's migration work.

## Legacy mode and the stale MTIP

Source mode recomputes MTIP before CSR and MRET reevaluation. Legacy mode keeps the stale-MTIP
behavior, so it stays identical to the frozen oracle. Fixing it in legacy mode too would be a
separately identified behavior change: Nate's decision, with its expected oracle difference
documented and tested, and never described as historical equivalence.

## Validation

- **An independent reference.** A copy of V-32's frozen legacy interpreter, extended in test
  sources with only the selected changes: source sampling, standard compare and the Zicntr CSRs.
  It counts retirement per instruction rather than by the delta formula. It is kept separate from
  the unchanged frozen copy, which still checks legacy mode.
- **Deterministic cases with a manual source:**
  - high-word rollover and high/low/high reads of `time`, `cycle` and `instret`, including a
    low-word rollover exactly at the reading instruction;
  - pre-instruction reads of `cycle` and `instret` with Zicntr on, and the inclusive `0xC00` read
    with it off;
  - the retirement distinctions above, including several traps and handlers in one `stepAttempts`
    call;
  - writes to each half of `mcycle` and `minstret` mid-batch, immediate reads after them, a write
    followed by a same-attempt interrupt, and callback edits of work and architectural counters;
  - legality of all six Zicsr forms on the read-only counters, `mcounteren` gating in U-mode, and
    no hook call or time sample before a trap;
  - two harts on one source, and time advancing across WFI stalls and calls with no execution;
  - sample counts per call, including a nonpositive budget, the `FENCE.I` resume and delegated
    steps, and the MTIP each callback sees;
  - a `time` read that makes a timer interrupt deliverable, taken in the same attempt;
  - compare zero, `TIMER_COMPARE_NEVER` and equality at all ones, the threshold and re-arming;
  - WFI executed with an already-due compare, and a deadline becoming due during a stall, each with
    MTIE set and clear;
  - a compare changed in a callback and via MMIO before MRET, with no stale re-entry;
  - with Zicntr off, today's CSR and hook behavior, and a hook-stored `mcountinhibit` that inhibits
    nothing;
  - nonzero `elapsedUs` rejected in source mode before any callback.
- **Legacy mode against the unchanged historical oracle.** Source-mode differences are documented
  as expected differences, not reported as passing it.
- **The decoded cache:** the suite runs cached and uncached, as `TestCores` already does.
- **Bench:** legacy and source mode with Zicntr off must match `0.1.8`; source mode is measured
  separately.

V-32's device and integration tests (interval MMIO, masking, LAUNCH and reset races) stay in its
repository.

## Questions for V-32 (revision 3)

1. **Work and architectural counters.** `getCycle()` and `getInstret()` stay guest-proof work
   counts, and `mcycle`/`minstret` are offsets on them. Does that meet the accounting correction?
2. **Delivery gating.** Does holding the compare at `TIMER_COMPARE_NEVER` while masked, with the
   deadline kept in V-32's device state, suit Packet 001's AP mask?
3. **Read-only scope.** Do any of V-32's `CSRHook`s accept writes to numbers `0xC00`–`0xFFF`? That
   informs Nate's choice between the specification's general read-only rule and counters only.
4. **`time` reads.** Is refreshing MTIP on each `time` read, with same-attempt delivery, the
   ordering V-32 wants?

## V-32 review: answers and remaining design corrections

2026-10-11. **The shared-source direction fits Packet 001. Settle the sampling,
counter ordering and WFI details below before implementation.** This review is
based on the live V-32 AP/IOP loops, `HartState`, the retained attempt oracle and
the RISC-V specifications. It does not authorize implementation or adoption.
V-32 repository references below are relative to `/home/nate/work/emulator`.

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
