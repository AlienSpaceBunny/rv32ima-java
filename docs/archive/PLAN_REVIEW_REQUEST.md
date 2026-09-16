# Review request — FEATURE_REQUEST_PLAN.md (r5) → sign-off for implementation

**For:** the LLM that authored `FEATURE_REQUEST.md` (the V-32 AP/IOP request).
**Accompanies:** `docs/FEATURE_REQUEST_PLAN.md` — read it alongside this note; you
have no repo access, so section references (§1–§7, "Phase N") point into that file.

You have reviewed this plan before (revisions r2, r3, r4, r5 in its Revision
History). This is a final consistency + intent check before `rv32emu-core`
implementation starts. **The revision you are reviewing is r5.** Note the Revision
History block in the file is printed out of order (r5, then r4, then r3) — that is
editorial only; r5 is newest.

---

## A. Known doc-consistency fixes — listed so you don't re-report them

The plan's own "Combined Review Notes" flag six items. We will apply these as **r6
regardless of your answer**; no need to raise them:

1. Backward Compatibility section still says `step()` gains a `ReservationTable`
   parameter — contradicts r5 (bus-owned reservations, no new `step()` param). Will
   be rewritten to "`step()` remains source-compatible."
2. Phase 2 verification still describes two states "sharing a `ReservationTable`" —
   will become two states sharing a multi-hart-aware `MemoryBus`.
3. Plain-store vs. `SC.W` ordering contract will be stated explicitly (see B4 — we
   want your confirmation on the *contract*, not the wording).
4. `atomicRmw` / `tryScAndStore` defaults will be labelled single-hart-only for
   atomic correctness.
5. Summary priority wording ("`tryScAndStore`/`ReservationTable`") will be
   clarified to "`tryScAndStore` plus bus-internal reservation tracking."
6. Revision History reordered.

## B. Questions we need you to answer

### B1. Byte/halfword atomics — what exactly? *(this is the blocker)*

`FEATURE_REQUEST.md` says: "Add the byte/halfword atomic support needed by the
project if this maps to the intended `Zab` extension target." We read that as
**Zabha** (ratified 2024): `AMOADD.B/H`, `AMOSWAP.B/H`, `AMOAND/OR/XOR.B/H`,
`AMOMIN/MAX[U].B/H`. Confirm:

- **(a)** Zabha (byte/halfword *AMOs*) is what you need — yes/no.
- **(b)** You do **not** also need sub-word LR/SC (`LR.B`, `LR.H`, `SC.B`, `SC.H`).
  These are not in any ratified RISC-V spec and the plan excludes them. Confirm
  exclusion is acceptable — yes/no.

`Zba` (`SH1ADD/SH2ADD/SH3ADD`) and `Zbb` (~18 bit-manip instrs) are named
explicitly in your request, so they are in scope regardless; no question there.

### B2. F extension deferral

Your AP ISA is `RV32IMFC_Zba_Zbb` — includes F. The plan makes F the **last**
phase (Phase 5, P3, "AP only"), after everything else. Confirm AP guest code can
run without hardware F initially (soft-float or F-free builds) so this ordering
does not block your integration — yes/no. If F is actually on the critical path,
say so.

### B3. Cross-hart LR/SC and AMO coordination lives in the bus

r5's design: the **shared `MemoryBus` implementation** owns cross-hart reservation
tracking and AMO atomicity (§4, §5). The core keeps only per-hart
`reservationValid` as a local fast-path. `step()` gets no reservation parameter.
The bus detects `LR.W` via `ctx.kind == AMO && ctx.atomicOp == 2` on the `readInt`
override; `SC.W` routes through `mem.tryScAndStore(...)`; all AMOs route through
`mem.atomicRmw(...)`; plain stores invalidate overlapping reservations in the bus's
private table.

Your request said "The bus **or core** should provide enough coordination…". Does
putting this entirely in the bus meet your needs, given your V-32 harness already
shares one bus between the two core instances? — yes/no, concerns if any.

### B4. Plain-store / `SC.W` ordering contract

Proposed contract for a multi-hart bus implementation (we will write this into r6;
confirm it is what you need):

> A plain store's `writeInt(addr, v, ctx)` — its write **and** its invalidation of
> overlapping reservations — must be ordered against `tryScAndStore` under the same
> per-granule lock. Otherwise: Hart A holds a valid reservation; Hart B does a plain
> store but has not yet invalidated; Hart A's `SC.W` succeeds; Hart B then
> invalidates. The store precedes the `SC.W` in real time, so the `SC.W` should
> have failed.

Is "plain store globally ordered before a concurrent `SC.W` to the same granule
must fail that `SC.W`" the semantics your IPC queues rely on? — yes/no.

### B5. Interrupt injection API

Proposed (§6): embedder sets `state.mip |= (1 << bit)` directly, plus a helper
`injectInterrupt(RV32IMAState, int bit)` that also clears the WFI flag so a halted
hart wakes. Bit assignments: 3 = MSIP (software), 7 = MTIP (timer, core-managed),
11 = MEIP (external). Caller holds whatever lock guards the target hart's state.

Does a direct-field-write + helper model satisfy "external/software interrupt
injection hooks", or do you need a registered callback / a core method that also
re-evaluates trap delivery synchronously? — confirm or specify.

### B6. `AccessContext` shape

Proposed (§3): `record AccessContext(int hartId, int privilege, AccessKind kind,
int width, int atomicOp)` with `enum AccessKind { FETCH, LOAD, STORE, AMO }`.
`privilege` = `extraflags & 3`; `atomicOp` = AMO `funct5`, else 0. Fault signalling
stays as: the bus throws `IndexOutOfBoundsException`, the core converts it to the
correct guest trap (fetch → instruction access-fault, load/store → the matching
access-fault). Base-and-bound translation stays in your bus override; the core
does no translation and keeps `ramOffset`/`ramSize` only as a coarse precheck.

Confirm this record shape carries everything your IOP MPU model needs, and that
`IndexOutOfBoundsException` is an acceptable fault channel — yes/no.

### B7. Phasing vs. your integration timeline

Plan order: **Phase 1** (`IsaConfig`, `hartId`, instruction-fetch-fault fix,
`misa` from config) → **Phase 2** (U-mode CSR privilege check, `AccessContext`
plumbing, `injectInterrupt`, `atomicRmw`, `tryScAndStore`) → **Phase 3** Zba/Zbb →
**Phase 4** Zabha → **Phase 5** C → **Phase 6** F. *(numbering will be tidied in
r6.)*

The plan claims **mailbox v1 (synchronized MMIO + interrupt) needs only Phases
1–2**. Confirm that matches your rollout — i.e. you do not need any ISA extension
(C, Zbb, …) to stand up the first working AP/IOP mailbox. — yes/no.

---

## C. What we are NOT asking

- Formatting / prose style of the plan.
- The already-settled architectural-boundary decision (r5 removed
  `ReservationTable` from `step()`); that is final unless B3 surfaces a real
  blocker.
- Anything about the Java port's release process, build, or test tooling.
