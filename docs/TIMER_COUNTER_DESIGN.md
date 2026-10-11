# Shared machine time, timer compare and Zicntr: design draft

Date: 2026-10-11. From the CPU agent, **for V-32's review.** Nothing here is implemented, and
implementation waits for V-32's answers and Nate's go.

**Inputs:**
- V-32's "Timer and counter extension implementation requests" in
  [EXECUTION_API_DESIGN.md](EXECUTION_API_DESIGN.md#timer-and-counter-extension-implementation-requests).
- V-32's Packet 001 decision record, in its repository at
  `docs/decisions/001-clocks-timer-events.md` (C1 time source, C4 IOP timers, C5 lifecycle).
- The [Zicntr](https://docs.riscv.org/reference/isa/v20260120/unpriv/counters.html) and
  [machine timer](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html)
  specifications.

This targets the API V-32 should move to, not how it calls the core today. It is independent of
the [execution API](EXECUTION_API_DESIGN.md), and either can land first.

## Summary

- **A shared time source, opt-in per core.** The embedder passes one `MachineTimeSource` to each
  hart's core. It supplies the machine's 64-bit microsecond timeline; V-32's production source is
  `(System.nanoTime() - epochNanos) / 1000`, with the epoch reset only on startup and whole-machine
  reset. The core reads it at documented points and never accumulates or rebases time.
- **Standard compare in source mode:** MTIP is `time >= mtimecmp`, unsigned, and zero is not
  special. "Never" is all ones.
- **Full Zicntr behind an `IsaConfig` flag:** `cycle`, `time`, `instret` and their high halves,
  `mcounteren` gating for U-mode, and a new per-hart 64-bit retirement counter.
- **Legacy mode is unchanged.** A core without a source keeps `elapsedUs` accumulation and the
  zero-disables compare. That is the historical oracle's contract.
- **The hot loops stay as they are.** Time is sampled at call entry and on `time` reads, not per
  instruction, and retirement is counted where the cycle counter is published.

## Baseline

What the core does today, as V-32's timer audit found:

- Each hart's `mtime` lives in `RV32IMAState` and grows by the caller's `elapsedUs`.
- MTIP is set when `mtimecmp != 0` and `mtime >= mtimecmp`; zero disables the timer.
- Only the low half of `cycle` (`0xC00`) is built in. There is no `cycleh`, `time`, `instret` or
  `mcounteren`. Other unknown CSRs go to the `CSRHook` or read 0, and writes to read-only CSR
  numbers are not trapped.
- Interrupt reevaluation after a CSR write or MRET does not recompute MTIP. If a handler moves the
  compare through MMIO and then executes MRET, the stale MTIP can re-enter the handler.

## API

```java
/** The machine's shared timeline. Thread-safe; must never decrease between resets. */
@FunctionalInterface
public interface MachineTimeSource {
    /** Microseconds since the machine epoch, as an unsigned 64-bit value. */
    long nowMicros();
}

public RV32IMACore(IsaConfig isa, DecodedCacheConfig cache, MachineTimeSource time);

// IsaConfig gains a hasZicntr component.

// RV32IMAState gains:
public static final long TIMER_COMPARE_NEVER = -1L;   // all ones
public long getInstret();
public void setInstret(long instret);
public int mcounteren;
```

- **Source mode** is a core built with a non-null source. One source instance serves every hart
  on the machine timeline.
- **`elapsedUs` must be 0 in source mode.** `step` and `stepAttempts` throw
  `IllegalArgumentException` otherwise, which catches a half-migrated caller.
- **`state.getTimer()` becomes the hart's latest sample.** It is read-only in effect: the next
  sample overwrites a write.
- **Zicntr and source mode are independent.** Without a source, `time` reads the legacy
  accumulated timer.

## Time sampling

In source mode the core samples the source at two points:

1. **At call entry,** once per `step` or `stepAttempts` call, after the first `beforeAttempt`.
2. **On every `time` or `timeh` read.** Each read samples afresh, so the RV32 high/low/high retry
   loop works and a waking or halted hart never sees stale time.

Each sample is stored as the hart's latest. MTIP is recomputed from the latest sample and the
current compare:

- at every attempt's entry in `stepAttempts` and at every `step` entry, even with no new sample,
  so a callback's compare change is seen at the next attempt;
- at interrupt reevaluation after a CSR write or MRET. This is new; it removes the stale-MTIP
  re-entry above.

**Why not per attempt:** `System.nanoTime()` costs tens of nanoseconds, more than most cached
instructions. The cost is that a deadline crossed in the middle of a call is noticed at the next
call, or at the next `time` read. That latency is bounded by one call's duration, which V-32
controls through its budget. Question 1 asks whether that is enough.

**WFI.** A stalled hart samples on each call and returns 1 until MTIP or another enabled interrupt
is pending. The embedder decides how long to block, for example until `getTimerMatch()` on its own
source, or until another event. The core never sleeps.

## Compare semantics

- **In source mode, MTIP is set exactly when `time >= mtimecmp`, unsigned, and cleared
  otherwise.** Only the MTIP bit changes; MSIP, MEIP and other `mip` bits are untouched.
- **"Never" is `TIMER_COMPARE_NEVER`.** At 1 MHz, all ones is about 584,000 years away.
- **The embedder initializes the compare.** A new `RV32IMAState` holds 0, which in source mode is
  immediately due. Because WFI wakes on MTIP even with MTIE masked (V-32 asked to keep that), a
  hart with compare 0 can never stall in WFI. Startup, reset and cancellation write
  `TIMER_COMPARE_NEVER`.
- **Generation and delivery stay separate.** MTIP reflects the compare; `mie.MTIE` and
  `mstatus.MIE` gate delivery. Interrupt entry and return do not clear MTIP. V-32's device masks
  and enables are its own state on top.

## Counters (Zicntr)

| CSR | Number | Source | U-mode gate |
| --- | --- | --- | --- |
| `cycle`, `cycleh` | `0xC00`, `0xC80` | the hart's cycle counter | `mcounteren.CY` |
| `time`, `timeh` | `0xC01`, `0xC81` | a fresh sample (source mode) or the legacy timer | `mcounteren.TM` |
| `instret`, `instreth` | `0xC02`, `0xC82` | the new retirement counter | `mcounteren.IR` |

- **Read-only.** A write to any of these raises an illegal-instruction exception.
- **`mcounteren` (`0x306`)** implements bits CY, TM and IR; the others read 0. It resets to 0, so
  firmware enables the counters it wants AP applications to read. A U-mode read with the bit clear
  raises an illegal-instruction exception. M-mode always reads.
- **`cycle` keeps today's accounting:** instructions attempted, including trapping instructions
  and fetch faults, excluding interrupt-only entries and WFI stalls. It is not renamed or reused
  for retirement.
- **`instret` counts retired instructions.** A synchronous exception does not retire, and that
  includes ECALL, EBREAK and fetch faults. Interrupt-only entries and WFI stalls retire nothing. An
  executed WFI retires, even when it returns 1. An instruction followed by a same-attempt interrupt
  retires.
- **Read ordering:** a `cycle` read includes the reading instruction, as today. An `instret` read
  counts instructions retired before it.
- **No per-instruction cost.** A batch ends at its first synchronous exception, so retirement is
  the cycle delta minus one if the batch ended in an exception. The core adds that where it already
  publishes `cycle`, and an `instret` read mid-batch derives the value from the local cycle count.
  A differential test checks this against a per-instruction counter.
- **Machine counters.** `mcycle`, `minstret` and their high halves (`0xB00`, `0xB02`, `0xB80`,
  `0xB82`) are M-mode read/write aliases of the same counters (Question 3).
- **Not included:** Zihpm, `mcountinhibit` and `hpmcounter3`–`31`. Those numbers keep going to the
  `CSRHook`. With Zicntr enabled, the built-in CSRs above take precedence over the hook.

## Lifecycle and ownership

The core owns no epoch, reset policy or device. In V-32's terms:

- **Startup and whole-machine reset:** the embedder resets its epoch, writes `TIMER_COMPARE_NEVER`
  to both harts' compares, and applies its own cycle and `instret` reset policy.
- **AP RESET** cancels the AP timer: the compare becomes `TIMER_COMPARE_NEVER`. Shared time
  continues.
- **HALT, RESUME, WFI and successful LAUNCH** leave the compare alone, so an IOP-configured
  request and its cadence survive. Today's LAUNCH reset of the timer and compare is V-32 code; in
  source mode, resetting the timer has no effect, and the compare must be left alone.
- **Who writes the compare:**
  - The compare is hart-owner state.
  - A cross-thread change, such as the IOP programming the AP's interval, is published by the
    embedder and applied in `beforeAttempt`.
  - An AP MMIO acknowledgement runs on the AP thread inside the store, and can write the compare
    directly. The next MTIP recomputation sees it, including the one at MRET.
- **IOP dual timers:** the interval device drives MEIP through the embedder's aggregation, and
  the register compare drives MTIP. The core recomputes only MTIP, so neither clears the other.

## Validation

- **A deterministic manual source** in tests. Cases:
  - high-word rollover and high/low/high reads of `time`, `cycle` and `instret`;
  - the retirement distinctions listed above;
  - two harts on one source;
  - time advancing across WFI stalls and calls with no execution;
  - compare zero, `TIMER_COMPARE_NEVER`, the threshold and re-arming;
  - a compare changed in a callback and via MMIO before MRET, with no stale re-entry;
  - `mcounteren` gating and read-only traps;
  - nonzero `elapsedUs` rejected in source mode.
- **Legacy mode against the unchanged historical oracle.** Source-mode differences are documented
  as expected differences, not reported as passing it.
- **The decoded cache:** the suite runs cached and uncached, as `TestCores` already does.
- **Bench:** legacy and source mode with Zicntr off must match `0.1.8`; source mode is measured
  separately.

V-32's device and integration tests (interval MMIO, masking, LAUNCH and reset races) stay in its
repository.

## Questions for V-32

1. **Sampling.** Is a sample per call, plus one per `time` read, enough? The alternative is
   re-sampling every N attempts within a call, at roughly one `nanoTime()` per N attempts.
2. **Construction.** Is a third constructor argument acceptable, or should the core move to a
   builder now that it takes three configuration objects? The API is unfrozen, so either is cheap.
3. **Machine counters.** Should `mcycle` and `minstret` be included? They are cheap, and the
   privileged specification expects them in M-mode.
4. **Stale MTIP in legacy mode.** The MTIP recomputation at reevaluation is proposed for source
   mode only, which keeps legacy behavior identical. Should legacy mode get the fix too?
5. **Compare initialization.** Should the core offer a helper that resets a hart's timer state
   for source mode, or is writing `TIMER_COMPARE_NEVER` simple enough to leave to the embedder?
