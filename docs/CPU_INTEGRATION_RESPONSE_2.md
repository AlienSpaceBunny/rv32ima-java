# Response to the V-32 follow-up review — 2026-09-16 (round 2)

Answers V-32's `CPU_INTEGRATION_FOLLOWUP.md` (archived verbatim here as
`archive/CPU_INTEGRATION_FOLLOWUP.md`), which reviewed `0.1.4-SNAPSHOT` and
`docs/CPU_INTEGRATION_RESPONSE.md`. Both requested corrections are made.
`../emulator` was read only (its `review/` probes were run, not modified).

- Fix commit: `63d5fbc`. Version bump: `ee27809` → **`0.1.5-SNAPSHOT`**.
- Installed artifact: `~/.m2/repository/com/alienspacebunny/rv32emu-core/0.1.5-SNAPSHOT/rv32emu-core-0.1.5-SNAPSHOT.jar`,
  SHA-256 `623683e2530db715c95eda36e19c581fcf63616a4687c831bad5f43c4405a399`.
  **V-32 action:** `build.gradle` → `0.1.5-SNAPSHOT` (it still names
  `0.1.3-SNAPSHOT`; skip `0.1.4`).
- Gate: `./mvnw clean verify` green — 528 core tests (11 new), Spotless,
  Checkstyle, SpotBugs, cli smoke.
- **Both V-32 probes rerun here against the `0.1.5` jar:**
  `review/CoreFeatureProbe.java` (the `checkAccess`-updated version in
  `../emulator/review/`) exits 0 with the seven corrected observations and
  nine `PASS` lines; `review/CoreResponseProbe.java` prints
  **`30 passed; 0 failed`** and exits 0 (was `12 passed; 18 failed`).

## Request 1 — reservation cleanup before the alignment fault: fixed

The follow-up was right: the alignment branch exited before the clears, so
the round-1 promise ("every LR/SC attempt clears the reservation") was not
met for misaligned attempts, and the reproduction (valid `LR.W` → misaligned
`SC.W` → aligned `SC.W` with no new `LR.W` succeeding and writing 42) was real.

**Change.** In the RV32A block, immediately after the encoding checks and
*before* the alignment check, the core now (a) computes the SC's local
validity decision (`irmid == 3 && reservationValid && reservationAddr == rs1`)
and then (b) unconditionally clears `reservationValid` for `irmid` 2 or 3.
Every subsequent exit — alignment trap, bus fault, local failure, bus
failure, success — starts from a cleared reservation; only a successful
`LR.W` read sets it again. The pre-attempt validity decision is preserved for
the aligned-SC path exactly as before. Illegal-encoding traps (`LR.W` with
`rs2 != 0`) still happen earlier and do not touch the reservation, since the
instruction never executes. No data-bus side effect was added: a misaligned
atomic still never reaches the bus.

**Regressions** (`AtomicPrimitivesTest`):
`validLrThenMisalignedScClearsReservationAndLaterScFails` and
`validLrThenMisalignedLrClearsReservationAndLaterScFails` — each runs the
follow-up's sequence step by step and asserts cause 6/4, `reservationValid ==
false`, then that the aligned `SC.W` at `mtvec` returns 1 with zero
`tryScAndStore` calls and memory untouched. The existing aligned LR/SC
success tests, bus-access-fault tests, and round-1 lifecycle tests all still
pass.

**Bus-side lifecycle when no atomic bus call occurs — the inert-entry
policy, now explicit** (Javadoc on `tryScAndStore`, `docs/API.md`):

- A multi-hart bus may hold an entry for a hart whose local flag is already
  false (misaligned attempt never reached the bus; locally failing SC only
  called `checkAccess`, which must not mutate tracking). That entry is
  **inert**: the core never calls `tryScAndStore` unless its local
  reservation is valid, and the local flag can only become valid again via a
  new `LR.W` read, at which point the bus records the new `(hartId, address)`
  and replaces the stale entry.
- Contract: at most one entry per hart, replaced on every `LR.W` read for
  that hart; the bus is not told about core-local clears and does not need
  to be; the embedder clears entries on reset, halt/reload, and MPU remap.
- `checkAccess` stays stateless. No separate cleanup call is added; the
  present API is sufficient with this policy stated.

## Request 2 — reevaluate interrupts after CSR writes and MRET: fixed

Agreed on the spec point (xRET and interrupt-CSR writes require immediate
reevaluation, stronger than the bounded-delay rule for a pending bit), and on
why it matters with 1,061-instruction batches.

**Change.** Delivery happens **within the same `step` call**, not by ending
the batch — so V-32's scanline batch loses no instruction budget. After an
instruction retires that either wrote `mstatus` (`0x300`), `mie` (`0x304`),
or `mip` (`0x344`) — any CSR op form, including immediates and set/clear —
or executed `MRET`, the core reevaluates deliverability once that
instruction's register write, `postExec`, and PC advance are complete. If an
interrupt is deliverable it is taken immediately: priority is the ordinary
external > software > timer via the same helper the pre-loop dispatch uses;
`mepc` is the next instruction's address (the `MRET` target, or the
instruction after the CSR write) — computed from the retired instruction's
own length, so a compressed or halfword-aligned target is exact; privilege
and `mstatus` follow the normal trap-entry path; the cycle count includes
only the retired instruction. `WFI`'s own check from round 1 is unchanged.
Nothing else in the batch loop changed: a CSR write that leaves nothing
deliverable, or an `MRET` with nothing pending, continues the batch as before
(regression-tested both ways).

**Regressions** (`CoreTest`, all with `count = 2`, all asserting the next
instruction's `x4` write did not happen):
`csrWriteEnablingMstatusMieDeliversPendingInterruptBeforeNextInstruction`
(the follow-up's minimal repro: `x4 == 0`, `mcause = 0x80000003`, `mepc = 4`,
`cycle = 1`), `csrWriteEnablingMieBitWithMstatusMieAlreadySetDeliversBeforeNextInstruction`
(MEIP), `csrWriteSettingPendingBitInMipDeliversBeforeNextInstruction`,
`csrWriteReevaluationPreservesInterruptPriority` (MSIP+MEIP → `0x8000000b`),
`csrWriteWithNothingDeliverableDoesNotEndTheBatch`,
`mretToUserModeDeliversPendingInterruptBeforeTargetInstruction` (MIE = 0,
MSIE set, MPP = U: taken in U-mode; `MPP` then records U),
`mretReenablingMachineInterruptsDeliversBeforeTargetInstruction` (MPIE = 1,
MPP = M), `mretToCompressedTargetDeliversWithExactTargetMepc` (`hasC`,
target `c.addi` at a halfword-aligned `mepc`), `mretWithNothingDeliverableContinuesAtTarget`.

## Unchanged from round 1

`checkAccess`'s contract and default, the alignment causes (4/6), MRET
privilege gating, the WFI changes, MMIOBus forwarding, and the
`injectInterrupt` ownership guidance. Device-hook atomics and permission
probes remain explicitly limited to what `HardwareHook` can express; V-32
enforces its own device policy. Nothing here restores reliance on
cross-thread `injectInterrupt`, default multi-hart atomics, or permit-all
failed-SC checks — the follow-up's closing constraints hold.

## Re-run

```sh
J=~/.m2/repository/com/alienspacebunny/rv32emu-core/0.1.5-SNAPSHOT/rv32emu-core-0.1.5-SNAPSHOT.jar
java -cp "$J" review/CoreFeatureProbe.java    # exit 0, corrected observations + 9 PASS
java -cp "$J" review/CoreResponseProbe.java   # 30 passed; 0 failed, exit 0
```

Then bump `build.gradle` to `0.1.5-SNAPSHOT` and resume the shared-RAM /
context / privilege integration and the synchronized mailbox slice per the
original review's ownership corrections.
