# V-32 AP/IOP plan review response

Date: 2026-09-10. Reviews r5 of [FEATURE_REQUEST_PLAN.md](FEATURE_REQUEST_PLAN.md)
and answers [PLAN_REVIEW_REQUEST.md](PLAN_REVIEW_REQUEST.md). The plan incorporates
these clarifications as r6; this response records the review of r5.

**B1 is resolved; the proposed scope and ordering fit V-32. Sign-off is conditional
on making actual software/external interrupt delivery part of Phase 2.** The
injection helper alone does not provide that.

## Application model

The application documentation was read before entering the processor repository.
V-32 is a graphics-oriented fantasy workstation: AP application code runs in
U-mode with direct access to permitted video/audio devices; the M-mode IOP owns
boot, storage, services, and AP base-and-bound protection. The documented
implementation has video, cartridge boot handoff, and AP bus protection.
Mailboxes, the fuller microkernel, and advanced devices remain future work. The
pitches describe aspirations; the checkpoint and feature request establish the
immediate integration needs.

## B1 — Byte/halfword atomics

**(a) Yes. (b) Yes.** Interpret the ambiguous “Zab” request as **Zabha
byte/halfword AMOs**. Exclude `LR.B/H` and `SC.B/H`; retain word LR/SC for
shared-memory synchronization. Zabha is useful planned coverage, not a
prerequisite for mailbox v1. This matches the
[ratified Zabha specification](https://docs.riscv.org/reference/isa/v20260120/unpriv/zabha.html).

Preserve byte/halfword access width through authorization and MMIO dispatch. A
containing-word RMW can be an internal RAM implementation technique, but must not
introduce neighboring MMIO accesses or bypass MPU boundaries.

## B2 — F extension deferral

**Yes.** F can remain last. The existing application guest Makefile already
targets `rv32ima_zicsr` with the `ilp32` ABI. Initial integration can continue
with F-free builds; F remains part of the eventual AP target.

## B3 — Bus-owned cross-hart coordination

**Yes.** Bus-owned coordination fits the architecture. However, the two harts
have different bus wrappers, and AP RAM accesses undergo translation.
Reservations and locking must converge on the **same backing-memory addresses**,
with metadata and atomic operations forwarded through those wrappers. Merely
sharing an underlying allocation is insufficient.

LR's read and reservation registration must also be indivisible against
competing stores. Retain the local SC address check and clear the local
reservation after unsuccessful as well as successful SC attempts. The
[A-extension specification](https://docs.riscv.org/reference/isa/v20260120/unpriv/a-st-ext.html)
requires SC to invalidate its reservation regardless of success.

## B4 — Plain-store / SC ordering

**Yes.** An overlapping competing store ordered **between the LR and SC** must
make that SC fail. Apply the contract to byte and halfword stores too, and to
future DMA/host writes into shared IPC memory. A store preceding the LR does not
require failure.

## B5 — Interrupt injection

**Yes to the API model; no synchronous callback is needed.** Injection and
pending-bit clearing can use the target hart's synchronization discipline,
shared with execution.

**Material concern:** the current interrupt dispatch in
[`RV32IMACore.step()`](../core/src/main/java/com/alienspacebunny/emu/RV32IMACore.java)
checks only MTIP. Phase 2 must implement MSIP/MEIP delivery and correct
privilege-dependent gating. Section 7's unconditional `mstatus.MIE` requirement
is incorrect when executing in U-mode: enabled, pending machine interrupts can
trap from U-mode regardless of that global bit. See the
[machine interrupt rules](https://docs.riscv.org/reference/isa/priv/machine.html).

## B6 — AccessContext and faults

**Yes for MPU enforcement and the exception channel.** Hart identity, privilege,
kind, width, and atomic operation provide the needed authorization inputs.
Faults should report the guest logical fault address, with LR classified as a
load fault and SC/AMO as store/AMO faults.

For future shared-memory queues, separately specify memory ordering: the record
omits `aq`/`rl`. That is acceptable if the implementation guarantees sufficient
stronger ordering; per-granule atomicity alone does not establish publication
ordering for queue payloads.

## B7 — Phasing

**Yes, subject to B5.** Mailbox v1 needs no C, Zbb, Zabha, or F instructions.
Phases 1–2 can supply its processor support, while the emulator supplies the
synchronized mailbox and interrupt acknowledgement.

Keep trap routing explicit: an AP exception enters M-mode **on the AP**, not
automatically on the IOP. The embedding runtime needs a deliberate mechanism for
notifying the IOP and returning application execution to U-mode. Initializing
AP privilege to U-mode alone does not provide that behavior.
