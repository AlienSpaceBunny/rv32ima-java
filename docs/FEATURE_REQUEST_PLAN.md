# Feature Request Plan: V-32 AP/IOP Support

Revision: **r6**, 2026-09-10. See [PLAN_REVIEW_RESPONSE.md](archive/PLAN_REVIEW_RESPONSE.md)
for the application-context review of r5 and answers B1–B7.

**Implementation progress:** Phase 1 (items 1–4: `IsaConfig`, `misa` derivation, `hartId`,
instruction-fetch fault handling), Phase 2 (items 5–10: privilege/interrupt correctness fixes,
`AccessContext`, `atomicRmw`, `tryScAndStore`), Phase 3 (items 11–13: Zba, Zbb, Zabha), and Phase 4
(item 14: the C extension) are all done — see the inline "done" notes in the Existing Gap section,
Design Decisions §1–§5, the ISA Extension Implementation Details section, and the Staging Plan.
Phase 5 (item 15, the F extension) is split into 5a (register file, `fcsr`, and every
rounding-mode-independent instruction — done, `f50e0a8`) and 5b (the rounding-mode layer —
done, `814bde6`) — see Design Decision §8 and the F Extension section. Rolling status lives in
[`../CHECKPOINT.md`](../CHECKPOINT.md).

## Overall Verdict

The requested feature set is **fully implementable** within the current architecture. Nothing in the
request requires redesigning the core; it requires extending it in well-defined, layered increments.
The base `RV32IMA_Zicsr` use case is preserved throughout: new ISA features gate on a config
object, new bus callbacks have backward-compatible defaults, and existing embedder code remains
source-compatible. Correctness fixes to faults,
privilege checks, and interrupt delivery intentionally correct prior behavior.

The work is organized into three ordered priority lanes. Later lanes do not unblock earlier ones
and may be deferred, but each lane is internally ordered.

- **P1 — Safe multi-hart operation (top priority):** ISA config, hart ID, fetch-fault fix,
  privilege-mode semantics (U-mode CSR access fix), AccessContext bus metadata, interrupt
  injection and delivery, `atomicRmw`, `tryScAndStore` plus bus-internal reservation tracking.
  Unblocks the AP/IOP interrupt and synchronized MMIO mailbox path.
- **P2 — Non-F ISA extensions:** C (compressed), Zba, Zbb, Zabha. Valuable ISA
  coverage but not prerequisites for safe AP/IOP operation.
- **P3 — AP-only floating-point:** F extension. Largest effort, deferred to a separate phase.

Estimated relative effort (rough):

| Feature | Priority | Effort |
|---|---|---|
| ISA config + hart ID | P1 | XS |
| Instruction-fetch fault via bus | P1 | XS |
| U-mode CSR access privilege check | P1 | XS |
| Interrupt injection and MSIP/MEIP delivery | P1 | S — **done** (`5620de0`) |
| Bus access metadata (AccessContext) | P1 | S — **done** (`d9e93da`) |
| `atomicRmw` + `tryScAndStore` / bus-internal tracking | P1 | M — **done** (`4aeec77`) |
| Zba (3 instructions) | P2 | XS — **done** (`590a4c9`) |
| Zbb (18 instructions) | P2 | S — **done** (`590a4c9`) |
| Zabha sub-word AMOs | P2 | S — **done** (`590a4c9`) |
| C extension (fetch loop refactor) | P2 | M — **done** (`b224ac3`) |
| F extension (new register file + ~50 instrs) | P3 | L — 5a **done** (`f50e0a8`), 5b **done** (`814bde6`) |

---

## Confirmed Scope

B1 resolves the original “Zab” ambiguity as **Zabha byte/halfword AMOs**:
`AMO[ADD|AND|OR|XOR|SWAP|MIN[U]|MAX[U]].[B|H]`. Sub-word LR/SC
(`LR.B`, `LR.H`, `SC.B`, `SC.H`) is excluded. Word LR/SC remains available for
shared-memory synchronization. Zba and Zbb remain separately in scope.
See the [ratified Zabha specification](https://docs.riscv.org/reference/isa/v20260120/unpriv/zabha.html).

Zabha is independently configurable but is confirmed implementation scope, not
an unresolved option. Mailbox v1 requires none of C, Zba, Zbb, Zabha, or F.
The application's current guest build uses `rv32ima_zicsr` / `ilp32`, so F can
remain last and C can follow the integer extensions.

The review accepts the API boundaries and phasing. Phase 2 must include actual
software/external interrupt delivery, not just pending-bit injection and WFI wakeup.

---

## Existing Gap: Instruction-Fetch Fault Handling — done (`c92045e`)

The current `step()` loop does not wrap `mem.readInt(pc)` in a try/catch. An
`IndexOutOfBoundsException` from a fetch would propagate uncaught to the caller rather than
converting to an instruction access-fault trap (cause 1). This is a latent correctness issue
today and is directly blocking for the AP MPU model (IOP must be able to reject AP instruction
fetches with the correct guest trap). Fixing this is a prerequisite for P1.

**Fix:** Wrap the fetch call in try/catch, convert `IndexOutOfBoundsException` → `trap = 1 + 1`
(the codebase's `+1` internal encoding for instruction access-fault; `mcause` receives `1` after
the trap handler's `trap - 1` step), set `rval = pc`, break the instruction loop.

`ramOffset`/`ramSize` remains as a coarse DRAM-window precheck (existing behavior, unchanged).
The context-bearing bus overrides (Design Decision §3) provide fine-grained per-access
authorization — for example, AP MPU enforcement — on top. Both layers independently produce
instruction-access-fault traps through the same `trap = 1 + 1` path.

---

## Design Decisions

### 1. ISA Configuration Object — done (`c92045e`)

Add an immutable `IsaConfig` class passed to `RV32IMACore` at construction time.

```java
// Default — preserves all existing behavior exactly
RV32IMACore core = new RV32IMACore();

// Extended
RV32IMACore apCore = new RV32IMACore(IsaConfig.RV32IMFC_ZBA_ZBB_ZICSR);
RV32IMACore iopCore = new RV32IMACore(IsaConfig.RV32IMC_ZBB_ZICSR);
```

`IsaConfig` is an immutable value type with boolean fields for each extension:
`hasC`, `hasF`, `hasZba`, `hasZbb`, `hasZabha`. The `misa` CSR value is derived from
the config rather than hardcoded (current hardcoded value `0x40401101` represents RV32IMA only
and will misreport on extended cores).

When an instruction belonging to a disabled extension is decoded, the core raises an
illegal-instruction trap — the same path it uses today for reserved encodings. No behavior change
for base configs.

**Landed:** `IsaConfig` is a record (`hasC`, `hasF`, `hasZba`, `hasZbb`, `hasZabha`, `hasU`) with
`RV32IMA_ZICSR`/`RV32IMFC_ZBA_ZBB_ZICSR`/`RV32IMC_ZBB_ZICSR` presets and an `misa()` method;
`misa` (CSR `0x301`) is now derived from it.
- **U-mode bit — resolved (Nate).** The previously-hardcoded `misa` value (`0x40401101`) set bits
  {0, 8, 12, 22, 30}, not bit 20 (the standard "U" bit), even though this core implements U-mode.
  Nate's call: make it configurable rather than picking one. `IsaConfig.hasU` (default `false` via
  a 5-arg compatibility constructor) reproduces the exact original value including its
  non-standard bit 22 when `false`; `RV32IMFC_ZBA_ZBB_ZICSR`/`RV32IMC_ZBB_ZICSR` set it `true`
  (standard U bit, no legacy bit). `RV32IMA_ZICSR`'s `misa()` is unchanged (`0x40401101`).
- `RV32IMFC_ZBA_ZBB_ZICSR` sets the C and F `misa` bits even though those instructions aren't
  decoded until Phase 4/5 — a guest that probes `misa` on that config and trusts it will find
  F-extension instructions illegal-trap instead of executing. **Confirmed acceptable for now
  (Nate).**
- No decode-time gating exists yet because there is nothing to gate: none of Zba/Zbb/Zabha/C/F
  are decoded before their respective phases land.

### 2. Hart Identity — done (`c92045e`)

Add `int hartId` to `RV32IMAState` (defaults to 0). The embedder sets it before first use.
The core passes `hartId` through to access-context callbacks (see §3). No other core behavior
depends on the hart ID internally; the bus uses it as a reservation-owner identity.
Embedders must assign distinct, stable IDs to concurrently participating harts.

**Landed:** `RV32IMAState.hartId`, default `0`. Not yet read or written by the core itself — that
starts in Phase 2 once `AccessContext` plumbing exists (§3).

### 3. Bus Access Metadata — `AccessContext` via Default Method Overloads — done (`d9e93da`)

Rather than a separate observer interface (which would split enforcement between two objects),
add `AccessContext` as an optional second parameter via default method overloads on `MemoryBus`:

```java
// New enum/record added to the public API:
record AccessContext(int hartId, int privilege, AccessKind kind, int width, int atomicOp) {}
enum AccessKind { FETCH, LOAD, STORE, AMO }

// New overloads with backward-compatible defaults:
default byte   readByte (int addr, AccessContext ctx) { return readByte(addr);  }
default short  readShort(int addr, AccessContext ctx) { return readShort(addr); }
default int    readInt  (int addr, AccessContext ctx) { return readInt(addr);   }
default void   writeByte (int addr, byte  v, AccessContext ctx) { writeByte(addr, v);  }
default void   writeShort(int addr, short v, AccessContext ctx) { writeShort(addr, v); }
default void   writeInt  (int addr, int   v, AccessContext ctx) { writeInt(addr, v);   }
```

The core calls the context-bearing versions internally; all existing `MemoryBus` implementations
continue to work without modification because the defaults delegate to the no-context methods.
An embedder implementing the AP MPU supplies the legacy interface methods and overrides
the context-bearing versions for enforcement. Bus wrappers must preserve context and forward
atomic primitives, rather than accidentally falling back to split read/write defaults.

Access faults are still signalled by throwing `IndexOutOfBoundsException` from any version of the
method; the core converts it to the correct guest trap, including the new fetch catch path.
Preserve the guest logical fault address for `mtval`, even when the bus translates it.
LR faults are load access faults; SC and other AMO faults are store/AMO access faults.
`width` is the guest access width in bytes (1, 2, or 4); authorize the entire range.

The `privilege` field in `AccessContext` is the hart's current privilege level (from
`extraflags & 3`). The `atomicOp` field is the `funct5` encoding for AMOs, or 0 for
non-atomic accesses.

The `atomicOp` field is also the signal a multi-hart bus uses to register LR.W reservations:
`ctx.kind == AMO && ctx.atomicOp == 2` identifies a load-reserve, allowing the bus override
of `readInt(addr, ctx)` to record `(ctx.hartId, addr)` in its private cross-hart table without
any additional parameter on the call.

The record is sufficient for the V-32 MPU but does not carry `aq`/`rl` ordering bits.
Before declaring shared-memory IPC safe, the core/bus implementation must document and verify
how it honors guest acquire/release and FENCE ordering for ordinary payload accesses as well
as atomic queue metadata. A sufficiently stronger ordering implementation permits keeping this
record shape. Per-granule exclusion alone is not a payload-publication guarantee; if stronger
ordering is not supplied, the ordering interface must be extended before that milestone.

**Landed (`d9e93da`):** `AccessContext` (record) and `AccessKind` (enum: `FETCH`, `LOAD`,
`STORE`, `AMO`) as specified. All 6 base methods plus `readByteSigned`/`readShortSigned` gained
context-bearing overloads (the signed pair wasn't in the original sketch — added so a bus
overriding the 1-arg signed defaults keeps working, matching the stated compatibility
guarantee). `RV32IMACore` builds the context once per instruction (`hartId`, `privilege` from
`extraflags & 3`) and passes it to fetch, every load/store width, and both the AMO read and
write. `AccessContextTest` covers all of it, including an explicit `ContextlessBus` regression
guard for the default-delegation compatibility promise. `MMIOBus` — the one bus implementation
in this repo — does not forward `AccessContext` to `HardwareHook`; flagged in its Javadoc,
since it's the bus a first AP implementation would most likely start from.
The AMO block now carries `AccessContext` (`kind=AMO`, `atomicOp=funct5`) on its `readInt`/
`writeInt` calls, but **is not yet atomic across harts** — it's still two separate bus
transactions, same as before. That's item 8 (`atomicRmw`).

### 4. AMO Atomicity — `atomicRmw` Bus Primitive — done (`4aeec77`)

The current AMO implementation in `RV32IMACore` (lines 529–579) calls `mem.readInt` then
`mem.writeInt` as two separate bus transactions. This is not atomic with respect to a concurrent
hart even if both calls individually acquire a lock. For the single-hart case this is acceptable,
but it breaks cross-hart AMO correctness.

Add an optional bus primitive:

```java
// On MemoryBus — word-operation sketch; atomic correctness is single-hart-only:
default int atomicRmw(int addr, int funct5, int operand, AccessContext ctx) {
    int old = readInt(addr, ctx);
    int result = computeAmo(funct5, old, operand); // extracted helper
    writeInt(addr, result, ctx);
    return old;
}
```

The core calls `mem.atomicRmw(...)` for all AMO instructions. A multi-hart-aware bus
implementation overrides `atomicRmw` to hold a per-granule lock around the read-modify-write
AND the invalidation of any overlapping LR/SC reservations in its internal reservation table
(see §5). The default preserves today's single-hart behavior; no `ReservationTable` parameter
is needed because cross-hart coordination is owned by the bus, not exposed through `step()`.
The default does not provide multi-hart atomic correctness. A multi-hart implementation must
coordinate all overlapping accesses, including ordinary reads/writes, with the atomic operation.
The sketch above is for words; Zabha requires width-aware dispatch as described below.

### 5. Cross-Hart LR/SC Reservations — `tryScAndStore` done (`4aeec77`); `ReservationTable` itself is bus-internal, doc-only (see item 10)

LR/SC reservations currently live in `RV32IMAState.reservationAddr/reservationValid`. For
cross-hart correctness, a store from Hart 1 must invalidate Hart 2's reservation. The approach:

The reservation state in `RV32IMAState` (`reservationAddr`/`reservationValid`) remains as the
per-hart architectural state. Cross-hart coordination is owned entirely by the bus implementation
and requires no new parameter on `step()`.

**How this works:**

- `LR.W`: the core reads through the bus with `ctx.kind == AMO && ctx.atomicOp == 2` (LR's
  funct5). The core records the reservation locally in `state`. A multi-hart bus override also
  records `(ctx.hartId, addr)` in its own private `ReservationTable` on this same call, keyed
  by the context it already receives. Reading the value and registering the reservation must
  occur in one critical section ordered against competing stores.
- `SC.W`: the core checks `state.reservationValid` and the reserved address as a local pre-check.
  If either check fails, it returns failure immediately (no bus call). If both pass, it calls
  `mem.tryScAndStore(hartId, addr, value, ctx)` — the bus makes the final atomic decision,
  checking its private table under a granule lock. The bus may reject even if the local state
  says valid (cross-hart invalidation happened between the LR and SC). After every SC attempt,
  successful or unsuccessful, the core clears `state.reservationValid`; a bus-side SC attempt
  also consumes its tracked reservation regardless of success.
- `AMO`: core calls `mem.atomicRmw(...)`. The bus override holds the granule lock, performs the
  RMW, and invalidates any overlapping reservation entries in its private table atomically.
- **Plain stores**: the bus's `writeByte`, `writeShort`, and `writeInt` overrides invalidate
  overlapping reservations. The write and invalidation occur under the same per-granule lock
  used by `tryScAndStore`. A competing overlapping store ordered between LR and SC must cause
  that SC to fail; a store before LR does not require failure. Future DMA/host writes to shared
  IPC memory must participate in this protocol too.
- Single-hart use: `tryScAndStore` default just writes; bus does not override; behavior is
  identical to today.

```java
// On MemoryBus — default provides single-hart correctness:
default int tryScAndStore(int hartId, int addr, int value, AccessContext ctx) {
    writeInt(addr, value, ctx);
    return 0; // success; local reservation check already done by core before this call
}
// Multi-hart override holds a granule lock covering: private table check + conditional
// write + reservation invalidation — all as one critical section.
```

`step()` gains no new parameter for this mechanism. Cross-hart reservation management is
entirely encapsulated in the bus implementation.

**Amended 2026-09-16 (`0664be7`, from V-32's `CPU_INTEGRATION_REVIEW.md` (now `docs/archive/`) findings 3–5; see
`docs/CPU_INTEGRATION_RESPONSE.md`):**
- *Locally failing SC is still permission-checked.* The A extension says no `SC.W` may retire
  without passing memory permission checks. The "no bus call at all" fast path above was
  therefore incomplete for a bus with access control. New default method
  `MemoryBus.checkAccess(address, ctx)` — a side-effect-free probe, permit-all by default — is
  called on that path instead of a write; it throws `IndexOutOfBoundsException` to fault the
  SC with cause 7. `tryScAndStore` overrides must likewise permission-check before returning a
  failure code. The store-side fast path (no `tryScAndStore` call when the local check fails)
  is unchanged.
- *Alignment.* The core validates natural alignment for `LR.W`/`SC.W`/every AMO width before
  any bus call (causes 4/6, `mtval` = guest address), so the "naturally aligned" promise in
  `atomicRmw`'s contract is now enforced rather than assumed. `LR.W` with `rs2 != 0` is illegal.
- *Reservation lifecycle on a fault.* Every `LR.W`/`SC.W` attempt clears
  `state.reservationValid` before the bus is consulted; only a successful `LR.W` sets it. A bus
  override must consume its own entry before throwing so both views agree after the trap.
- *Wrappers.* `MMIOBus` now forwards context-bearing overloads and all three atomic primitives
  to its backing bus for non-hook addresses; the "bus wrappers must preserve context and forward
  atomic primitives" sentence in §3 was previously unmet by the one wrapper in this repo.

V-32 has distinct AP/IOP bus wrappers, not identical address views. AP logical RAM addresses
are translated before coordination. Both paths must converge on one reservation/locking domain
keyed by backing-memory identity and translated physical granule. Sharing an allocation alone
is insufficient. MPU remapping/reset must not allow stale reservations to authorize an SC in a
new mapping; the embedder must invalidate affected reservations as part of that transition.

### 6. Interrupt Injection API

`mip` is already a public field on `RV32IMAState`. To inject an interrupt, an embedder can
write a bit into `mip` directly. However, if the target hart is in WFI (`extraflags & 4`),
it will not check `mip` until the WFI flag is cleared.

Add a static helper method (or a convenience method on `RV32IMACore`):

```java
public static void injectInterrupt(RV32IMAState state, int interruptBit) {
    state.mip |= (1 << interruptBit);
    state.extraflags &= ~4; // wake from WFI
}
```

Document standard interrupt bit assignments:
- Bit 3: software interrupt (MSIP)
- Bit 7: timer interrupt (MTIP — already managed by core)
- Bit 11: external interrupt (MEIP)

For a multi-threaded host where Hart 0 (IOP) injects an interrupt into Hart 1 (AP), this method
must be called while holding whatever synchronization guards the AP's `RV32IMAState`,
including execution through `step()`. Pending-bit clearing/acknowledgement uses the same
synchronization. No callback or synchronous trap delivery inside the helper is required.
(Reaffirmed 2026-09-16: the helper is a plain read-modify-write of `mip`/`extraflags` and is
*not* thread-safe on its own; the recommended pattern is for the target hart's owner thread to
apply it between `step` calls from device state the sender published safely. A stalled hart
now also re-checks `mip & mie` at the top of `step`, so a pending bit set directly on `mip`
without this helper's WFI clear is not lost — `0664be7`.)

**Required Phase 2 delivery work — done (`5620de0`).** MSIP/MEIP dispatch (constants
`MIP_MSIP`/`MIP_MEIP`, causes `INT_MACHINE_SOFTWARE`/`INT_MACHINE_EXTERNAL`) and
`RV32IMACore.injectInterrupt(state, bit)` landed ahead of the rest of Phase 2, since it was a
defect in already-shipped behavior (see §7) rather than new multi-hart surface. Priority is
external > software > timer, gated by the corrected rule in §7. `CoreTest` covers delivery,
non-delivery when disabled, U-vs-M-mode gating, three-way priority, and the injection helper's
pending-bit-set + WFI-wake behavior. Pending-bit acknowledgement is exercised only via manual
`mip` clears in tests, not yet against a real mailbox device — that remains Phase 2 integration
work.

### 7. Privilege-Mode Semantics

This section specifies the intended behavior for AP (U-mode) and IOP (M-mode) privilege
handling. Items marked **existing** are already implemented correctly; only items marked
**new fix** require code changes.

**U-mode CSR access restriction — done (`d9298a3`).** The current CSR decode path in
`RV32IMACore` (line 437) has no privilege check — any privilege level can read or write any CSR.
The RISC-V spec encodes the minimum required privilege in CSR bits[9:8]: M-mode CSRs have `0b11`,
and U-mode-accessible CSRs (cycle/time/instret, `0xC00`–`0xCFF`) have `0b00`. Add before the CSR
dispatch:

```java
if (((csrno >> 8) & 3) > (state.extraflags & 3)) {
    trap = (2 + 1); // illegal instruction
    break;
}
```

This raises an illegal-instruction trap when U-mode code attempts to access any M-mode CSR,
and passes U-mode reads of `0xC00` (cycle) since their privilege field is `0b00`.

**Landed as-specified**, named constants substituted for the literals (`CSR_PRIVILEGE_SHIFT`,
`CSR_PRIVILEGE_FIELD_MASK`, `EXTRAFLAG_PRIV_MASK`, `exceptionTrap(EXC_ILLEGAL_INSTRUCTION)`).
**Applies uniformly to hook-routed CSR numbers too** — see `CSRHook`'s updated Javadoc: a custom
CSR address that doesn't follow the standard convention on purpose (for example, mini-rv32ima's
`0x136`–`0x140` console CSRs, whose bits 9–8 happen to be `1`) is gated by that coincidental
field just like a real CSR. A custom CSR meant to be reachable from user-mode guest code needs an
address whose bits 9–8 are `0b00`. Worth checking before wiring V-32's IOP console/debug CSRs to
an AP-visible address.

**ECALL cause by privilege (existing).** `ECALL` from M-mode sets `mcause = 11`; from U-mode
sets `mcause = 8`. Already correct at line 489.

**MRET (existing — corrected 2026-09-16, `0664be7`).** Restores privilege from `mstatus.MPP`,
restores `MIE` from `MPIE`, clears `MPP` to U-mode. The return-state mechanics were correct, but
the "already correct" claim missed that nothing gated the instruction on privilege: V-32's
`CPU_INTEGRATION_REVIEW.md` demonstrated U-mode `MRET` performing a machine-mode return. Now an
`MRET` below M-mode traps illegal-instruction, and non-zero `rd`/`rs1` on any funct3 == 0 SYSTEM
instruction is illegal. See `docs/CPU_INTEGRATION_RESPONSE.md` finding 1.

**Trap entry (existing).** All traps and interrupts enter M-mode unconditionally (`extraflags |= 3`),
saving the prior privilege in `mstatus.MPP`. Already correct at line 621.

**Interrupt gating — done (`5620de0`).** For this U/M-only core, a pending machine interrupt is
eligible when its bit is set in both `mip` and `mie`, and either execution is in U-mode or
execution is in M-mode with `mstatus.MIE = 1`. `mstatus.MIE` is not required while running
U-mode. The existing timer model's `mtimecmp != 0` condition concerns generation of MTIP only;
it does not gate software/external interrupts. See the
[machine interrupt rules](https://docs.riscv.org/reference/isa/priv/machine.html). This was a
latent defect in already-shipped code (nothing previously ran below M-mode to trigger it), not
new behavior — see `RV32IMACore.java` and `CoreTest` for the fix and its regression coverage.

**AP / IOP privilege assignment.** The embedder sets `state.extraflags & 3` before first use:
`0` for AP (U-mode), `3` for IOP (M-mode). The core does not configure this — it is embedder
responsibility. AP exceptions and interrupts enter M-mode on the AP hart; they do not
transfer execution or trap state to the IOP. The emulator must provide a deliberate notification
and recovery path (for example, a protected AP trap handler that signals the IOP) and return
application execution to U-mode. AP initialization alone does not keep application execution
confined to U-mode across traps. Trap handling must remain within the AP's bus protection policy.

### 8. F/D Forward Compatibility (baked in ahead of Phase 5)

Before starting Phase 5 (F extension), Nate asked whether implementing F and D (double-precision)
together now would be cheaper than F now, D later. Estimate (not measured): D's non-RNE rounding
arithmetic doesn't inherit F's "compute in `double`, round once" shortcut (there's no wider
primitive to hide behind for double-precision — directed/RMM rounding needs real correctly-rounded
arithmetic, e.g. `Math.fma`-based error-free transformations), so the two extensions' hardest work
doesn't share. Rough bands: F alone is the plan's existing **L**, at its low end given the
float-via-double shortcut; D added later is ~0.7–0.9× of F; both together now is ~1.7–1.8× of F
alone (shares `fcsr`/decode skeleton/register file/test harness, not the rounding work). Conclusion:
**not meaningfully cheaper together — implement F now, defer D** — but three shape decisions are
cheap to make now and avoid an API-breaking retrofit tax later (which would otherwise land on
published API, not just effort, since `RV32IMAState.regs` is a public field and the precedent here
is the same):

1. **FP register file stored as `long[] fregs = new long[32]`, not `float[]`,** even though F alone
   only ever needs the low 32 bits. Every FP-producing instruction NaN-boxes on write (sets the
   upper 32 bits to all-ones, per the standard RISC-V convention for a register wider than the
   value it holds), matching what a real `FLEN=64` implementation does; reads simply take the low
   32 bits, since nothing under F-only decode can write an improperly-boxed value. `RV32IMAState`'s
   existing style (`public final int[] regs`) means a later `float[]` → `long[]` migration would be
   a breaking change for downstream consumers (V-32) rather than an internal refactor — this is
   free to avoid now.
2. **`IsaConfig.hasD` added now as a misa-only flag** (bit 3, the "D" letter), following exactly the
   pattern `hasF` used from Phase 1 through Phase 4: it only changes the `misa()` value the guest
   observes, and does not unlock any instruction decode. The record's compact constructor validates
   `hasD ⇒ hasF` (D implies F per the RISC-V spec — there is no D-without-F configuration). Avoids
   adding a third compatibility-constructor layer to `IsaConfig` when D is eventually decoded.
3. **`MemoryBus.readLong`/`writeLong` (8-byte access, needed by `FLD`/`FSD`) is explicitly NOT
   added now.** Unlike the two decisions above, this one has no consumer under F alone — `FLW`/`FSW`
   are word-width and use the existing `readInt`/`writeInt` context-bearing overloads. Adding an
   unused width to a public interface ahead of any caller is speculative surface, not free
   forward-compatibility; it's deferred to whenever D is actually implemented.

---

## ISA Extension Implementation Details

### Zba (Address Generation) — done (`590a4c9`)

Three new OP-class instructions in the decode of opcode `0x33`, funct7 `0x10`:

| funct3 | Instruction | Computation |
|---|---|---|
| 2 | SH1ADD | `rd = (rs1 << 1) + rs2` |
| 4 | SH2ADD | `rd = (rs1 << 2) + rs2` |
| 6 | SH3ADD | `rd = (rs1 << 3) + rs2` |

The existing `legalEncoding` check in the OP/OP-IMM decode block must be extended to admit
funct7 `0x10` when `IsaConfig.hasZba` is true. Low risk; no fetch or state changes.

### Zbb (Basic Bit Manipulation) — done (`590a4c9`)

18 new instructions. Encodings below are taken from the authoritative
[riscv-opcodes](https://github.com/riscv/riscv-opcodes) machine-readable tables
(`extensions/rv_zbb`, `rv32_zbb`, `rv_zba`), not hand-derived — an earlier revision of this
table had three funct7 values wrong (CLZ/CTZ/CPOP/SEXT.B/SEXT.H's group was listed as `0x04`
instead of `0x30`; ORC.B as `0x18` instead of `0x14`; REV8 as `0x68` instead of `0x34`) and
omitted ZEXT.H entirely. Verify against that source, not this table, if the two ever disagree
again.

| funct7 | funct3 | rs2 (OP-IMM only) | Instructions |
|---|---|---|---|
| 0x30 | 1 | 0/1/2/4/5 | CLZ, CTZ, CPOP, SEXT.B, SEXT.H (OP-IMM) |
| 0x30 | 5 | any | RORI (OP-IMM; rs2 field is the shift amount) |
| 0x14 | 5 | 0x07 | ORC.B (OP-IMM) |
| 0x34 | 5 | 0x18 | REV8 (OP-IMM, RV32 form) |
| 0x04 | 4 | 0 (rs2 = x0) | ZEXT.H (OP) |
| 0x05 | 4–7 | — | MIN, MINU, MAX, MAXU (OP) |
| 0x20 | 4 | — | XNOR (OP) — same funct7 as SUB/SRA; discriminate by funct3 |
| 0x20 | 6, 7 | — | ORN, ANDN (OP) |
| 0x30 | 1, 5 | — | ROL, ROR (OP) |

Java standard library provides efficient hardware-backed implementations for the heavy operations:
`Integer.numberOfLeadingZeros()` (CLZ), `Integer.numberOfTrailingZeros()` (CTZ),
`Integer.bitCount()` (CPOP), `Integer.reverseBytes()` (REV8), `Integer.rotateLeft()`/
`Integer.rotateRight()` (ROL/ROR/RORI).

The `legalEncoding` guard must be relaxed for these funct7 values when `IsaConfig.hasZbb`.

### Zabha (Byte/Halfword AMOs — confirmed scope) — done (`590a4c9`)

Extend the existing AMO decoder at opcode `0x2F`. Currently funct3 must be 2 (word). Zabha
adds funct3 0 (byte) and 1 (halfword) with the same funct5 operations. Byte/halfword AMOs
require width-aware `atomicRmw` handling:

- Preserve byte/halfword width through authorization and MMIO dispatch.
- A containing-word RMW with masking is allowed only as an internal RAM technique that
  preserves neighboring bytes, synchronization, and guest access/fault semantics. Do not widen
  MMIO accesses or bypass MPU boundaries.
- Use the low operand bits, perform signed/unsigned comparisons at the selected width, and
  sign-extend the old byte/halfword returned in `rd`. Enforce natural alignment.
- The bus primitive handles synchronization with overlapping word LR/SC and all store widths.
  No byte/halfword LR/SC is introduced.

Guard with `IsaConfig.hasZabha`. No fetch or state changes.

**Landed as-specified**, with one implementation refinement worth recording: `RV32IMACore`
sign-extends the loaded value and truncates `operand` to the AMO's width (both via a plain Java
narrowing cast, e.g. `(byte) value`) *before* calling `MemoryBus.computeAmo` — which is otherwise
unchanged and still operates on plain `int`s. This works because sign-extending two values to the
same width and comparing them with `Integer.compareUnsigned` preserves the same relative order as
comparing the original narrower values unsigned (sign-extension maps the "large" half of the
narrower range to the "large" half of the wider unsigned range, contiguously and monotonically).
So the existing signed (`Math.min`/`Math.max`) and unsigned (`compareUnsigned`) branches in
`computeAmo` did not need separate sub-word variants. See `MemoryBus.atomicRmw`'s updated Javadoc
and `ZabhaTest`.

### C Extension (Compressed Instructions) — done (`b224ac3`)

This is the most structurally significant ISA change. It affects the instruction-fetch loop in
`step()` rather than just the decode switch.

**Changes required:**

1. **Alignment check:** `(ofs_pc & 3) != 0` → `(ofs_pc & 1) != 0` (halfword alignment).
2. **Fetch:** Before reading a full word, check if the next halfword's bits[1:0] are not `11`.
   If they are `11`, the instruction is 32-bit (existing path). If they are `00`, `01`, or `10`,
   the instruction is 16-bit compressed (read only 2 bytes).
3. **Decode:** Add a `decodeCompressed(short cinstr)` method that expands 16-bit encodings to
   equivalent 32-bit execution. RVC has three quadrants (Q0, Q1, Q2) covering ~36 instruction
   forms; all map to existing RV32I operations on a restricted register set (x8–x15 for
   register-limited forms, also known as "prime" registers: x8=s0 through x15=a5).
4. **PC increment:** After the decode, advance by 2 for compressed or 4 for 32-bit.
5. **PostExecHook:** `pc` remains the instruction start address; `ir` holds the 16-bit
   encoding zero-extended to 32 bits for compressed instructions. Document this change.

The `decodeCompressed` method should be a self-contained expand-then-dispatch path: after
expanding a 16-bit instruction to a representative 32-bit form, re-enter the existing opcode
switch. This minimizes new code surface. The few RVC instructions without a direct 32-bit
equivalent (e.g., `C.ADDI4SPN` which aliases ADDI with specific register constraints) are
handled directly in `decodeCompressed` before falling through to the switch.

Guard the 16-bit fetch path with `IsaConfig.hasC`. Without C, the alignment and fetch logic
are unchanged.

**Landed, with two refinements over this sketch:**

- **`instrLen`, not implied by this section but required by it.** Every place in `step()` that
  computes "the PC after this instruction" (JAL/JALR/branch targets, `MRET`, `WFI`, the loop's PC
  advance, and the pending-interrupt PC correction) previously used a literal `4`. All of them now
  use a local `instrLen` variable (4 normally, 2 for a compressed instruction) instead, so a
  compressed jump's link value and a compressed branch's fall-through PC are correct structurally
  rather than by the coincidence of no compressed instruction ever reaching those specific literals
  (`MRET`/`WFI` in particular: there is no `C.MRET`/`C.WFI`, but converting them anyway removes a
  correctness argument that depended on that absence rather than on the code itself).
- **§5's `ir` design not implemented as sketched.** Nothing in this repo's `PostExecHook` contract
  or its tests actually depends on `ir` holding the original 16 bits for a compressed instruction
  (checked before implementing, not assumed) — `ir` holds `RV32IMACore`'s internal 32-bit
  expansion instead, which is the simpler of two viable designs and avoids threading a second
  "original fetched bits" variable through the entire opcode switch. `mtval` on an
  illegal-instruction trap from a bad compressed encoding is the same value. See
  `PostExecHook`'s Javadoc.

`decodeCompressed` takes an `int` (the 16-bit encoding zero-extended), not a `short` as sketched
above — plain `int` bit arithmetic throughout the method, no sign-extension surprises from a
`short` parameter. It expands every base RV32C (Zca) instruction, `C.ADDI4SPN` included, into a
standard 32-bit RV32I/M word via direct field placement for register/immediate forms and
inverse-encoded scrambled immediates for `JAL`/`JALR`/branches, always re-entering the existing
opcode switch — no RVC instruction needed a direct-compute exception. Every bit-shuffle formula
was taken from the reference simulator's decoder (`riscv-isa-sim`, not hand-derived) and
cross-checked against the authoritative `riscv-opcodes` tables; a reserved 16-bit pattern returns
an opcode with no case in the switch, reusing its existing illegal-instruction `default` arm.
`C.FLW`/`C.FSW` (quadrant 0, funct3 3/7) stayed illegal at this point since `F` wasn't decoded
yet — Phase 5's scope, not an oversight — and were wired up once Phase 5b landed (see below).
New `CompressedInstructionTest` (34 tests) covers every instruction differentially against its
hand-assembled 32-bit equivalent, reserved/illegal patterns in both directions, jump link values,
a compressed breakpoint trap's `mtval`, RAM-window-edge fetch faulting instead of overrunning the
backing store, and mixed compressed/32-bit instruction streams in both orderings (the actual point
of the extension, and the one place `mem.readInt` at a half-word- but not word-aligned address is
exercised). 400 core + 1 cli tests.

**Follow-up, done alongside Phase 5b (`3a42dbf`):** with `F` now fully decoded,
`decodeCompressed` gained the four remaining F-extension slots this section originally deferred —
`C.FLW`/`C.FSW` (quadrant 0, funct3 3/7) and `C.FLWSP`/`C.FSWSP` (quadrant 2, funct3 3/7) — each
expanding into the equivalent `FLW`/`FSW` using the same immediate-decode helpers as the integer
`C.LW`/`C.SW`/`C.LWSP`/`C.SWSP` forms they're structurally identical to. `decodeCompressed` itself
stays `IsaConfig`-agnostic: the resulting 32-bit `FLW`/`FSW` re-enters the ordinary opcode switch,
whose own `hasF` check traps illegal-instruction if `F` isn't enabled even when `hasC` is — so
`HAS_C`-only configs see no behavior change. One real difference from `C.LWSP`: `C.FLWSP`'s `rd`
field does *not* reserve `0`, since `f0` is an ordinary FP register, not hardwired zero, unlike
`x0`/`C.LWSP`. Quadrant 0/2 funct3 1/5 (`C.FLD`/`C.FSD`/`C.FLDSP`/`C.FSDSP`) remain reserved — they
require `D`, which this core still doesn't decode (only `IsaConfig.hasD`'s `misa` bit exists); the
existing test comments describing them as "not part of RV32 at all" were wrong (they're valid
RV32DC encodings) and have been corrected. 6 new/renamed tests. 498 core + 1 cli tests.

### F Extension (Single-Precision Floating-Point — lowest priority, AP only)

Effort: **L**. Split into two sub-phases (see Design Decision §8's estimate discussion for why F
is cheaper than this section originally implied): **5a is rounding-mode-independent and done
(`f50e0a8`)**; **5b is the rounding-mode layer and done** (item 15 complete).

**State additions (`RV32IMAState`) — done:** see Design Decision §8 for why the register file is
`long`-backed even though only the low 32 bits are used under F alone.
- `long[] fregs = new long[32]` — FP register file, NaN-boxed on every write (upper 32 bits set to
  all-ones); reads take the low 32 bits.
- `int fcsr` — FP control and status (bits 7–5: rounding mode `frm`; bits 4–0: accrued
  exception flags `fflags`).

**New CSRs handled in `readCsr`/`writeCsr` — done:** `0x001` (fflags), `0x002` (frm), `0x003`
(fcsr). Gated on `IsaConfig.hasF`, but only as a guard around these three cases — there is no
"CSR doesn't exist" trap, matching this core's existing behavior for every other unimplemented CSR
number (silent no-op / `CSRHook` passthrough, never illegal-instruction).

**5a opcodes — done, zero rounding-mode dependence:**
- `0x07` — FLW; `0x27` — FSW (word-width loads/stores, no rounding).
- `0x53` (OP-FP), by `funct7`: `0x10` FSGNJ/FSGNJN/FSGNJX.S (pure bit manipulation, no exceptions
  per spec); `0x14` FMIN/FMAX.S (hand-rolled — not `Math.min`/`Math.max`, which mishandle NaN
  propagation and don't need to distinguish ±0); `0x50` FEQ/FLT/FLE.S (FEQ is the quiet comparison,
  only a signaling NaN sets `NV`; FLT/FLE are signaling — any NaN operand sets `NV`); `0x70`
  FMV.X.W/FCLASS.S; `0x78` FMV.W.X. `funct7`'s bits 26:25 are the `fmt` field (S/D/H/Q); an exact
  `funct7` match already excludes the D/Q-format encodings of these same operations without a
  separate check.

**5b opcodes — done, all consult `frm`:**
- `0x43`/`0x47`/`0x4B`/`0x4F` — FMADD/FMSUB/FNMSUB/FNMADD.S (fused multiply-add family; these are
  their own top-level opcodes, not `0x53` `funct7` cases — the R4 instruction format repurposes
  that field as `{rs3, fmt}`).
- `0x53`, `funct7` `0x00`/`0x04`/`0x08`/`0x0C`/`0x2C` — FADD/FSUB/FMUL/FDIV/FSQRT.S.
- `0x53`, `funct7` `0x60`/`0x68` — FCVT.{W,WU}.S / FCVT.S.{W,WU} (int↔float conversions; also
  consult `frm`, and int-producing conversions saturate rather than wrap on NaN/out-of-range input
  — NaN → `INT_MAX`/`UINT_MAX`, too-large → max, too-negative → min, `NV` set). `rs2` field 2/3
  (the RV64 long forms) trap illegal-instruction.

**5b numerical approach — done, corrected from this section's original sketch during
implementation (advisor-reviewed):** the original idea above — treat the native `double` result
`d` of a Java double-precision operation as *the* true result and round it to `float` once — is
**not** safe for FADD/FSUB/FDIV/FSQRT.S under directed rounding modes. `d` is itself already a
*rounded* (correctly-rounded-to-double) approximation of the true infinite-precision result `X`,
not `X` itself, and `d` can equal a representable `float` exactly while `X` does not (e.g.
`1.0f + (-2^-149f)`: `d == 1.0` exactly, but `X = 1.0 - 2^-149 < 1.0`, so round-toward-negative
must produce `nextDown(1.0f)`, not `1.0f`). Directed rounding needs to bracket `X`, not `d`.

The implemented fix: every arithmetic op returns a `(approx, residualSign)` pair — `approx` is the
same correctly-rounded `double` as before, but paired with the *sign* of the exact residual
`X - approx`, computed exactly wherever the op allows:
- **FMUL** — exact; a float product needs at most 48 significant bits, well inside `double`'s 53.
- **FADD/FSUB** — Knuth's TwoSum on the two (already-exact, promoted-from-`float`) `double`
  operands gives an exact residual.
- **FDIV**/**FSQRT** — `Math.fma` computes an exact residual against the numerator (`a - q*b` /
  `a - s*s`) in one fused operation.
- **FMADD family** — the multiply term is exact (as for FMUL), so folding in the addend via the
  same TwoSum used for FADD/FSUB gives one correctly-rounded approximation of the *whole* fused
  expression. This means **the FMA family needs no special-casing at all** beyond sign handling
  for the four opcodes' `±(a*b) ± c` combinations — contrary to this section's original
  expectation that a double-intermediate expression "double-rounds at the `+c` step" for FMA;
  that concern applies to computing `a*b` then rounding *before* adding `c`, which this
  implementation never does.

Given `(approx, residualSign)`, rounding to `float` derives the two candidate floats bracketing
the true result (`lower`/`upper`, via `Math.nextUp`/`Math.nextDown` off the RNE-rounded `float`)
and picks the one each mode calls for — this also handles overflow and subnormal boundaries for
free, since `Math.nextUp(Float.MAX_VALUE)` is `+infinity` and `nextUp`/`nextDown` walk the
subnormal grid correctly. RMM (ties away from zero) needs one additional exact-midpoint check,
valid because a float midpoint is always exactly representable in `double`. `NX` is set whenever
the true result isn't exactly representable as the rounded `float`; `OF`/`UF` follow from the
rounded magnitude. `FCVT.{W,WU}.S` rounds the input to an integer per `rm` *first*, then
range-checks the rounded value (not the pre-rounded one) before saturating — this ordering matters
at the boundary (e.g. `FCVT.WU.S(-0.5)` is in range under RTZ, which rounds to `-0`, but out of
range under RDN, which rounds to `-1`).

`NV`/`DZ` for special values (NaN, infinities, zero) are handled per-operation before any of the
above, matching the standard IEEE 754 rules (e.g. `0/0` and `inf/inf` are invalid, not
divide-by-zero; `finite/0` is divide-by-zero; opposite-signed-infinity addition is invalid).
Full `fflags` accrual (`NV`/`DZ`/`OF`/`UF`/`NX`) is complete; 5a set only `NV`, and only where it
could arise from sign-agnostic bit inspection (comparisons, min/max) rather than rounding.

Guard with `IsaConfig.hasF`. All new opcodes with F disabled → illegal instruction trap.
`IsaConfig.hasD` exists (misa-only, see Design Decision §8) but decodes nothing — D itself remains
out of scope for this phase.

---

## Staging Plan

The following order is recommended. Each step is independently committable and testable.

### Phase 1 — Foundation (prerequisite for everything) — done (`c92045e`)

1. ~~Add `IsaConfig` (immutable value type with extension flags; derive `misa` from it).~~ Done.
2. ~~Add `hartId` to `RV32IMAState`.~~ Done.
3. ~~Fix the instruction-fetch exception gap (wrap `mem.readInt(pc)` in try/catch).~~ Done.
4. ~~Derive `misa` from `IsaConfig` instead of hardcoded constant.~~ Done (folded into item 1).

283 core + 1 cli tests green (`./mvnw clean verify`). `IsaConfig.hasU` added afterward to resolve
the `misa` U-bit question (Nate: make it configurable, default to the original legacy value,
V-32 presets opt into the standard bit) — see Design Decision §1 above. The F-before-it's-decoded
behavior on `RV32IMFC_ZBA_ZBB_ZICSR` is confirmed acceptable for now (Nate).

### Phase 2 — Multi-Hart Infrastructure (P1 continued)

5. ~~Add U-mode CSR access privilege check (Design Decision §7): `((csrno >> 8) & 3) > privilege`
   → illegal-instruction trap.~~ **Done (`d9298a3`)**.
6. ~~Add `AccessContext` record and `AccessKind` enum; add default-method overloads to
   `MemoryBus`; plumb context through all core load/store/fetch calls.~~ **Done
   (`d9e93da`)**. Also added context-bearing `readByteSigned`/`readShortSigned` overloads
   (not in the original sketch) so LB/LH preserve the compatibility guarantee for any bus
   that overrode the 1-arg signed defaults. `MMIOBus` (the one in-repo bus) does not forward
   `AccessContext` to `HardwareHook` — flagged in its Javadoc; a context-aware bus should
   implement `MemoryBus` directly. AMO reads/writes carry `AccessContext` now but still go
   through separate `readInt`/`writeInt` calls, **not yet atomic across harts** — that's item 8.
7. ~~Add `injectInterrupt`, MSIP/MEIP delivery, and the privilege-dependent interrupt gating
   fix (§6–§7).~~ **Done (`5620de0`)**, landed ahead of the rest of this phase as a standalone
   correctness fix. Acknowledgement against a real mailbox device is still open (§6).
8. ~~Add `atomicRmw` default method to `MemoryBus`; route all AMO instructions through it.~~
   **Done (`4aeec77`)**. `RV32IMACore`'s AMO block now calls `mem.atomicRmw(rs1, irmid, rs2,
   ctx)` for the nine RMW ops instead of computing the result inline; the old inline switch
   moved into `MemoryBus.computeAmo`, a private static helper shared only by `atomicRmw`'s
   default. `LR.W` is unchanged (already routed through `readInt(rs1, ctx)` per item 6).
9. ~~Add `tryScAndStore` default method to `MemoryBus`; route SC.W through it (core retains
   local fast-path reservation check in state; bus makes final atomic decision for
   multi-hart).~~ **Done (`4aeec77`)**. If the core's local check fails, the bus is never
   called at all (destination register gets `1` directly); if it passes, `mem.tryScAndStore
   (state.hartId, rs1, rs2, ctx)` makes the final call and its result — success or a
   bus-side rejection the core's local state couldn't see — becomes the destination
   register's value verbatim. `AtomicPrimitivesTest` proves both the routing (each new
   method called with the right args, in the right cases, and not otherwise) and a fault
   thrown from either primitive correctly becomes a store/AMO access fault (cause 7).
10. ~~Define `ReservationTable` as a bus-internal type; document how a multi-hart bus override
    manages it privately via `readInt` (LR detection), `tryScAndStore`, `atomicRmw`, and
    all store-width overrides. Cover translated aliases, indivisible LR registration, and
    SC reservation consumption. Specify the memory-ordering contract in §3.
    `step()` gains no new parameter.~~ **Done, doc-only as specified** — no `ReservationTable`
    class exists or is needed in this repo; §5 above already specifies the full contract
    (what a multi-hart bus's private table must track and when), and the `atomicRmw`/
    `tryScAndStore` Javadoc restates the locking obligations at each call site. `step()`'s
    signature is unchanged, confirming no core-side parameter was needed.

**Phase 2 is complete** as scoped in this repo (items 5, 6, 7, 8, 9, 10 all done). At the end
of Phase 2, the core supplies the processor support for interrupt-capable synchronized MMIO
mailboxes. The emulator supplies the mailbox device, synchronization, acknowledgement, and AP
trap/IOP notification path. No later ISA phase is required for mailbox v1. Cross-hart LR/SC and
AMO correctness requires a conforming multi-hart bus implementation and verification of the
ordering contract; adding default methods alone does not establish it — that bus does not yet
exist in this repo, and `aq`/`rl` memory-ordering semantics remain explicitly out of
`AccessContext`'s scope (§3, §5) pending that bus's design.

### Phase 3 — Integer ISA Extensions (P2) — done (`590a4c9`)

11. ~~Zba (3 instructions in OP decode).~~ **Done.** `SH1ADD`/`SH2ADD`/`SH3ADD`, gated by
    `IsaConfig.hasZba`.
12. ~~Zbb (~18 instructions; extend OP/OP-IMM decode, update `legalEncoding` guard).~~ **Done.**
    All 18 instructions, gated by `IsaConfig.hasZbb`: `CLZ`, `CTZ`, `CPOP`, `SEXT.B`, `SEXT.H`,
    `ZEXT.H`, `MIN`, `MINU`, `MAX`, `MAXU`, `ANDN`, `ORN`, `XNOR`, `ROL`, `ROR`, `RORI`, `ORC.B`,
    `REV8`. See the corrected encoding table above.
13. ~~Zabha byte/halfword AMOs (confirmed; extend AMO decoder and width-aware bus handling).~~
    **Done.** `MemoryBus.atomicRmw`'s default is now width-aware via `ctx.width()`; gated by
    `IsaConfig.hasZabha`. Byte/halfword `LR`/`SC` remain illegal-instruction, per Zabha's own
    scope. New `RV32IComplianceTest` vectors (Zba/Zbb) and `ZabhaTest` (Zabha) cover routing,
    edge cases, signed-vs-unsigned comparison at reduced width, neighboring-byte preservation, and
    gating by `IsaConfig` in both directions (present but disabled → illegal instruction; absent
    combination → illegal instruction regardless of config). 362 core + 1 cli tests.

**Phase 3 delivers ISA decode only.** A multi-hart-aware `MemoryBus`'s `atomicRmw` override
still owns sub-word RMW/lock granularity, exactly as for the word-width AMOs in Phase 2 — nothing
here changes that division of responsibility or narrows it to word-only.

### Phase 4 — Compressed Instructions (P2) — done (`b224ac3`)

14. ~~C extension: halfword alignment, 16-bit fetch path, `decodeCompressed`, PC advance.~~
    **Done.** See the "Landed, with two refinements" note under the C Extension design section
    above for what changed versus this staging note's sketch (`instrLen` threading, and
    `PostExecHook.ir`'s actual behavior for a compressed instruction).

### Phase 5 — Floating-Point (P3 — AP only, separate effort)

15. F extension: register file, fcsr, opcode decode for FLW/FSW/arithmetic/conversion.
    **Split into 5a/5b, both done** (see the F Extension design section below): **5a
    (`f50e0a8`)** — register file, `fcsr`, and every rounding-mode-independent instruction.
    **5b (`814bde6`)** — the rounding-mode layer: FADD/FSUB/FMUL/FDIV/FSQRT.S, the
    FMADD family, and FCVT conversions, all five IEEE 754 rounding modes, and full `fflags`
    accrual. **Follow-up (`3a42dbf`)** — closed the loop Phase 4 left open (item 14's
    note above): `decodeCompressed` now decodes `C.FLW`/`C.FSW`/`C.FLWSP`/`C.FSWSP`, since `F` is
    fully decoded. See the C Extension design section's follow-up note.

---

## Backward Compatibility

All changes are additive with respect to the existing public API:

- `RV32IMACore()` zero-arg constructor preserved; internally uses `IsaConfig.RV32IMA_ZICSR`
  as default, preserving the base ISA configuration while applying the correctness fixes above.
- `MemoryBus` implementations are unaffected; new context-bearing methods have defaults.
- `RV32IMAState` gains new fields (`hartId`, `fregs`, `fcsr`) at zero/null defaults.
- `step()` remains source-compatible with no new reservation parameter. Cross-hart
  reservation coordination is implemented by bus overrides.
- `misa` CSR value will change for non-base configs; for the default config it stays
  `0x40401101`.

The existing CLI integration test (`IntegrationTest.java`) and all unit tests continue to pass
without modification after each phase.

---

## Verification

Each phase should be verified before the next begins:

- **Phase 1:** `mvn test` passes; `misa` value reflects config for a constructed extended core.
- **Phase 2:** Two states share a multi-hart-aware bus with private reservation tracking,
  including AP/IOP wrappers that alias the same translated physical RAM. Verify LR detection
  via `ctx.kind == AMO && ctx.atomicOp == 2`, indivisible read/reservation registration,
  `atomicRmw`, `tryScAndStore`, and byte/halfword/word store-vs-SC races. Verify local SC address
  checking and reservation consumption on success and failure. Verify ordinary payload
  publication under the documented acquire/release/FENCE ordering contract.
  Interrupt tests must check actual MSIP/MEIP trap delivery and causes, not just WFI clearing:
  cover pending masked interrupts, acknowledgement, U-mode with `mstatus.MIE = 0`, M-mode with
  MIE both clear/set, and coexistence with timer interrupts.
  AccessContext tests assert hart ID, privilege, kind, width, and atomic operation across wrappers;
  denied fetch/load/store/LR/SC/AMO accesses must produce the correct cause and logical `mtval`.
  Privilege tests cover U-mode CSR rejection, U-mode ECALL cause 8, MRET restoration, and
  same-hart M-mode trap entry. Emulator integration validates AP trap notification to the IOP
  and return to U-mode without bypassing AP bus protection.
- **Phases 3–4:** Extend `RV32IComplianceTest` with new instruction vectors (style already
  established in the existing compliance suite). Zabha tests cover width, sign extension,
  alignment, neighboring-byte preservation, MPU boundaries, and absence of widened MMIO accesses.
- **Phase 5:** Dedicated FP compliance vectors; NaN canonicalization and rounding mode tests
  required before declaring F complete.

---

## Revision History

- **r6** — Application-context review resolves B1 as Zabha AMOs with sub-word LR/SC excluded;
  confirms F deferral and mailbox-first phasing. Applies the six r5 consistency fixes. Adds
  MSIP/MEIP delivery and privilege-dependent interrupt gating to Phase 2; clarifies translated
  bus coordination, all-width store ordering, LR/SC lifecycle, guest fault addresses, sub-word
  MMIO behavior, payload memory ordering, and AP-to-IOP trap notification responsibility.
- **r5** — Architectural boundary review: removed `ReservationTable` from `step()` and from
  `atomicRmw`/`tryScAndStore` signatures. Cross-hart LR/SC coordination is now entirely
  bus-internal; the bus detects LR.W via `ctx.kind==AMO && ctx.atomicOp==2` in its `readInt`
  override and manages its own private reservation table. The core retains its per-hart
  `state.reservationValid` as a fast-path pre-check for SC.W. `ramOffset`/`ramSize` noted as
  a legacy wart but retained for backward compatibility. All other proposed APIs confirmed as
  correctly scoped to the processor layer.
- **r4** — Third review: tightened `tryScAndStore` default contract to be explicit that it is
  valid only for single-hart/null-table use and that multi-hart correctness requires the bus
  override; added `ReservationTable` parameter to `atomicRmw` so AMO reservation invalidation
  happens under the same granule lock; fixed prose/code-block signature mismatch for
  `tryScAndStore`; fixed staging number repeat (Phase 3 was re-using 9–10); corrected F
  rounding mode name `RDNMM` → `RMM`.
- **r3** — Second review from originating LLM: restructured summary into P1/P2/P3 priority lanes;
  added Design Decision §7 (Privilege-Mode Semantics) including the U-mode CSR access privilege
  check, which is a real code gap in the current implementation; replaced `tryScAndClear + writeInt`
  with `tryScAndStore` as the correct atomic SC.W primitive (the prior design had a TOCTOU window
  between reservation check and memory write); corrected the F-extension rounding note (Java
  primitive float uses RNE, matching RISC-V's default; the risk is the four non-default modes).
- **r2** — Incorporated review from originating LLM: clarified internal trap encoding (`trap = 1 + 1`)
  to prevent spec-number confusion; added two-layer fetch-authorization note; tightened
  `tryScAndClear` atomicity requirement; softened Phase 2 mailbox claim; added privilege-mode
  verification items to Phase 2. Correction 1 of the review (trap cause numbering) identified an
  ambiguity in wording rather than a semantic error — the codebase's `+1` internal encoding was
  correct throughout.
- **r1** — Initial plan.

---

## Review Status

The r5 review and B1–B7 answers are preserved in
[PLAN_REVIEW_RESPONSE.md](archive/PLAN_REVIEW_RESPONSE.md). The six consistency fixes listed in
section A of the review request are incorporated in r6. The original review request remains
an r5 historical document; the five phases in this plan are the authoritative r6 numbering.

The requested scope and API boundaries are accepted for implementation with the contracts
above. This is plan-level acceptance, not a claim that the features are implemented or verified.
In particular, Phase 2 completion requires actual software/external interrupt delivery, a
conforming bus for cross-hart atomics, and verification of memory ordering. The emulator owns
the mailbox device and AP trap-to-IOP notification/recovery path.
