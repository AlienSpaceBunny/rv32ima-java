# Response to the V-32 CPU integration review — 2026-09-16

Answers V-32's `CPU_INTEGRATION_REVIEW.md` (2026-09-15, written against
`rv32emu-core:0.1.3-SNAPSHOT`, jar SHA-256 `76200a7d…`). All five processor
findings are fixed in this repo; the SC validation API decision (finding 4) is
made and shipped. Nothing in `../emulator` was modified; it was read only.

- Fix commit: `0664be7` (core fixes, `MemoryBus.checkAccess`, `MMIOBus`
  forwarding, regressions). Version bump: `0.1.4-SNAPSHOT`.
- Installed artifact: `~/.m2/repository/com/alienspacebunny/rv32emu-core/0.1.4-SNAPSHOT/rv32emu-core-0.1.4-SNAPSHOT.jar`,
  SHA-256 `e4ce1982bae095b49dc32e6574b715229176d9d75173c234bf279e17f57c74f3`.
  **V-32 action:** change `build.gradle`'s dependency from `0.1.3-SNAPSHOT` to
  `0.1.4-SNAPSHOT`. (The `0.1.3-SNAPSHOT` jar in `~/.m2` was also overwritten by
  an intermediate install — SHA `51fa71c0…` — but the coordinates changed
  deliberately so the pickup is explicit rather than a silent same-version swap.)
- Gate: `./mvnw clean verify` (Spotless, Checkstyle, SpotBugs, 517 core tests,
  cli packaged-jar smoke test) passes.

## Processor findings

### 1. U-mode MRET — fixed

`MRET` executed below M-mode now traps illegal-instruction (cause 2, `mtval` =
the encoding), regardless of `mstatus.MPP`. The privilege check runs before any
return-state mutation. Also, per the reserved-field rule for funct3 == 0 SYSTEM
instructions, `MRET`/`ECALL`/`EBREAK`/`WFI` with a non-zero `rd` or `rs1` field
are illegal. `WFI` is deliberately *not* privilege-gated: with no S-mode and
`TW = 0`, U-mode `WFI` is architecturally permitted.

Regressions: `CoreTest.mretInUserModeIsIllegalEvenWithMppMachine`,
`CoreTest.systemInstructionsWithNonZeroRdOrRs1AreIllegal`; the existing
M-mode `mretRestoresPrivilegeAndMstatusBits` still passes.

Probe expectation: `U-mode MRET: pc=0x80 cause=2 mtval=0x30200073 privilege=3`.

### 2. Lost wakeup with MSIP pending before WFI — fixed

Two changes, both keeping the documented legacy "WFI sets `mstatus.MIE`"
behaviour:

- **`WFI` instruction:** after setting `MIE`, if any interrupt is pending and
  enabled under the gating rule (`mip & mie`, with `mstatus.MIE` only masking in
  M-mode), `WFI` completes immediately — `step` returns `0`, the WFI flag is not
  set, `pc` advances — and the interrupt is delivered on the next `step`.
- **Stalled hart:** the `EXTRAFLAG_WFI → return 1` early exit now runs *after*
  the pending-enabled computation. It returns `1` only when nothing is
  deliverable; otherwise it clears the flag and falls through to delivery. This
  also covers an embedder setting `state.mip` directly (without
  `injectInterrupt`'s WFI clear), and MEIP as well as MSIP.

Wakeup vs. delivery are distinct: the wakeup is the flag clear; delivery is the
ordinary interrupt trap with `mepc` = the instruction after the `WFI`. Timer
behaviour (MTIP raised from `mtimecmp`, clearing WFI regardless of `MTIE`) is
unchanged.

Regressions: `CoreTest.wfiWithSoftwareInterruptAlreadyPendingDoesNotStall`
(MSIP injected, `MIE = 0`, then `WFI`: first step returns 0, second delivers
`0x80000003`), `pendingBitSetDirectlyOnMipWakesStalledHart` (MEIP via raw `mip`),
`stalledHartStaysStalledWhilePendingInterruptIsNotEnabled`,
`enablingInterruptThenWfiInOneBatchWithPendingBitDoesNotStall` (`csrs mie` +
`wfi` in one `count = 2` batch).

Probe expectation: `MSIP pending before WFI: pc=0x80 cause=-2147483645`
(`0x80000003`), `second step result=0 WFI=false`. Note the probe's *first*
step now returns 0 and the trap lands on the second step.

### 3. Atomic operand validation — fixed

Validated in the RV32A block before any bus call, in this order: illegal
encoding → misaligned → (bus) access fault.

- **Alignment policy: address-misaligned, causes 4/6**, not access-fault 5/7.
  `LR.W` misaligned → cause 4; `SC.W` or any AMO (word, and Zabha halfword)
  misaligned → cause 6. `mtval` = the guest address as the core received it,
  so a translating bus never sees it. Byte AMOs cannot be misaligned. No
  misaligned-atomicity-granule PMA is modelled; if a guest ever needs one it
  would be an `IsaConfig` knob, not a bus concern, and there is no such guest
  today.
- **`LR.W` with `rs2 != 0`** → illegal instruction (cause 2).
- Ordinary `LW`/`SW`/etc. keep their misaligned tolerance (inherited from
  mini-rv32ima; changing that is out of scope and would affect existing
  single-hart guests). Only atomics gained alignment traps — which is what the
  bus contract already promised ("naturally aligned to `ctx.width()`").

Regressions: `AtomicPrimitivesTest.misalignedLrTrapsLoadMisalignedWithoutTouchingBus`,
`misalignedScAndAmoTrapStoreMisalignedWithoutTouchingBus` (SC at +2, AMOADD at
+3, AMOSWAP at +1; asserts zero bus calls and untouched memory),
`misalignedZabhaHalfwordAmoTrapsStoreMisaligned`, `zabhaByteAmoIsNeverMisaligned`,
`lrWithNonZeroRs2IsIllegalInstruction`.

Probe expectations: `Misaligned LR.W: cause=4 mtval=0x101 reservation=false`;
`Misaligned AMOADD.W: cause=6 mtval=0x101`, `memory[0x101]=0`;
`LR.W with rs2 != 0: cause=2 mtval=0x1020a1af`.

### 4. Locally failing SC skips permission checks — API decision: `MemoryBus.checkAccess`

The A extension text is unambiguous: *"No SC.W instruction shall retire unless
it passes memory permission checks … For the purposes of memory protection, a
failed SC.W may be treated like a store."* The core's local fast path retired
a failing `SC.W` with no bus involvement, so a bus with access control had no
opportunity to fault it.

**The adjustment** is one additive default method, no signature changes:

```java
/** Throw IndexOutOfBoundsException iff the access described by ctx would be
 *  denied. No side effects. Default: permit everything. */
default void checkAccess(int address, AccessContext ctx) {}
```

- The core calls it **only** on the locally failing `SC.W` path (no
  reservation, or a reservation for a different address), with
  `ctx = (hartId, privilege, AccessKind.AMO, width = 4, atomicOp = 3)`, on
  the aligned guest address. If it throws, the SC traps store/AMO access fault
  (cause 7, `mtval` = the guest address, `rd` not written). If it returns,
  `rd = 1` and nothing is written. `tryScAndStore` is still never called on
  this path, so **local failure can never become a default-bus store** — the
  default `tryScAndStore` is unreachable from a locally failing SC.
- It must be side-effect-free: answer from mapping tables; never read or
  write memory or MMIO; do not touch reservation tracking. This is what rules
  out "simulate with an ordinary read/write", as the review required.
- The default permits everything, so a legacy six-method bus keeps its exact
  previous behaviour (a locally failing SC retires unchecked). That is
  acceptable only where the bus enforces no access control; a bus that can
  reject a store must override it. `FFMMemoryBus` overrides it with its bounds
  check, and there is an end-to-end regression against the reference bus.
- Wrappers forward it like the other primitives. `MMIOBus` now does.
- **`tryScAndStore` contract tightened** (Javadoc): an override that decides
  to report *failure* must have permission-checked the store first and throw
  if denied, rather than returning a failure code for an address the hart may
  not write. Only the core's locally-failing path uses `checkAccess`; the
  bus-failing path is the override's own responsibility.

For `ApMemoryBus` this maps directly onto existing pure helpers: a
`checkAccess` override is `if (isLogicalRam(address)) systemRam.checkAccess(
translateRamAddress(address, ctx.width()), ctx); else ensureAllowedWrite(
address, ctx.width());` for a store-kind context (`STORE`, or `AMO`), and the
read-side equivalent otherwise. `translateRamAddress` already throws on an
MPU-window miss and has no side effects, which is exactly the contract.

Regressions: `AtomicPrimitivesTest.locallyFailingScProbesPermissionsViaCheckAccess`
(asserts the exact context, zero `tryScAndStore` calls, memory untouched),
`locallyFailingScToDeniedAddressTrapsStoreAmoAccessFault` (cause 7, `mtval`,
`rd` preserved), `locallyFailingScToUnmappedAddressFaultsOnReferenceBus`;
`AccessContextTest`'s `ContextlessBus` compatibility guard and
`scWithoutPriorLrNeverCallsTryScAndStore` still pass unchanged.

**Probe note:** the review's `CoreFeatureProbe.Bus` does not override
`checkAccess`, so with the permit-all default that case still prints
`cause=-1 x3=1` against the new jar. That is the documented legacy-bus
behaviour, not an unfixed defect. Add one line to the probe's `Bus`:

```java
public void checkAccess(int a, AccessContext c) { if (faultSc) throw new IndexOutOfBoundsException(); }
```

and the case prints `pc=0x80 cause=7 mtval=0x10000 reservation=false x3=0`
(verified here against the rebuilt jar).

### 5. Faulting-SC reservation cleanup — fixed, policy documented

**Policy:** every `LR.W` or `SC.W` attempt clears the hart's local reservation
*before* the bus is consulted; only a successful `LR.W` then establishes one.
So an `SC.W` that traps (misaligned, `checkAccess` denial, or a throwing
`tryScAndStore`) leaves `reservationValid == false`, and an `LR.W` that traps
drops any previous reservation rather than leaving a stale one for a different
address. **Bus-side rule** (Javadoc on `tryScAndStore`): an override must
consume its own tracked entry for this hart before throwing, so the bus and
core views agree after the trap.

Regressions: `AtomicPrimitivesTest.faultingScConsumesLocalReservation`,
`faultingLrDropsPreviousReservation`; the existing
`tryScAndStoreFaultBecomesStoreAmoAccessFault` still passes.

Probe expectation: `Faulting SC.W: cause=7 mtval=0x100 reservation=false`.

## Corrections accepted on this side

- **`injectInterrupt` is not thread-safe.** Confirmed; the Javadoc was already
  right and `docs/EMULATOR_REPO_NOTES.md` was wrong. That note is corrected in
  place, and `docs/FEATURE_REQUEST_PLAN.md` §6 reaffirms it. Recommended
  pattern, as the review suggests: the sender publishes pending device state
  through its own synchronization; the target hart's *owner thread* calls
  `injectInterrupt` (or sets `mip`) between its own `step` calls. With fix 2,
  a raw `mip` set is no longer lost on a stalled hart, so the owner-thread
  approach needs no core API change.
- **Wrapper forwarding.** `MMIOBus` in this repo was itself a context-dropping
  wrapper. It now forwards all eight context-bearing overloads plus
  `atomicRmw`/`tryScAndStore`/`checkAccess` to its backing bus for addresses no
  hook claims (`MMIOBusTest` covers each). On V-32's IOP path
  `BootBus → MMIOBus → SystemRAMBus`, only `BootBus` still needs a forwarding
  pass — including the signed `readByteSigned`/`readShortSigned` overloads,
  as the review notes. Hook (device) addresses keep the no-context
  `HardwareHook` path; an AMO on a device register is the default two-call
  read-compute-write through the hook, and `checkAccess` on a hook address
  permits (hooks cannot be probed without side effects). The `MemoryBus`
  class Javadoc now states the wrapper rule explicitly.
- The `MULTI_HART_BUS_NOTES.md` handoff's exact-address reservation granule,
  "safe across threads" `injectInterrupt` claim, and the unsupported
  40 MHz/coarse-lock performance assertion are superseded by the review's
  corrections; nothing in this repo depended on them.

## Still V-32's, unchanged by this response

Distinct stable `hartId`s; IOP `extraflags` set to M-mode explicitly (a fresh
`RV32IMAState` is U-mode); the concurrent RAM bus with one monitor covering
plain accesses, LR registration, RMWs, and SC check/store/invalidation;
reservation tracking by translated physical granule with overlapping-write
invalidation across byte/halfword/word/AMO widths; reservation cleanup on
reset/reload/MPU remap; DMA/host-write participation or quiescence; RAM vs.
device/VRAM ordering policy; AP-trap → IOP notification convention; and the
concurrency tests the review lists. This library ships the contract and a
single-hart default only, as before.

## Does anything here need a compatibility flag?

Considered and decided no. The changes fall into three groups: (a) additive
API (`checkAccess`, MMIOBus forwarding) with defaults that preserve legacy
behaviour; (b) spec-mandated traps that only fire on encodings no compiler
emits or on privilege misuse (U-mode `MRET`, reserved SYSTEM fields, `LR.W`
`rs2 != 0`); and (c) two behaviour changes a real guest could notice — atomic
alignment traps and `WFI` not stalling when an interrupt is already pending.
For (c): `WFI` returning `0` instead of `1` with an interrupt due is strictly
better for an embedder's sleep loop; and misaligned atomics were never valid
under the bus contract, so a flag to allow them would reopen the exact hole a
multi-hart bus relies on being closed. If a guest that genuinely needs
misaligned AMOs (misaligned-atomicity-granule PMA) ever appears, that is a
small `IsaConfig` addition at that time — the API is unfrozen until V-32's
integration lands, so nothing is locked in by not adding it now.

## Re-run

```sh
# in ../emulator, after build.gradle → 0.1.4-SNAPSHOT
java -cp ~/.m2/repository/com/alienspacebunny/rv32emu-core/0.1.4-SNAPSHOT/rv32emu-core-0.1.4-SNAPSHOT.jar review/CoreFeatureProbe.java
```

Expected with the one-line `checkAccess` addition to the probe's `Bus`: all
seven questionable cases print the values quoted above, all nine positive
checks still `PASS`. Without that addition: six of seven, with the
denied-address SC still `cause=-1 x3=1` for the reason given under finding 4.
