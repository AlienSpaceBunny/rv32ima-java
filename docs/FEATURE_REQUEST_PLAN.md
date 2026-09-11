# Feature Request Plan: V-32 AP/IOP Support

Revision: **r6**, 2026-09-10. See [PLAN_REVIEW_RESPONSE.md](PLAN_REVIEW_RESPONSE.md)
for the application-context review of r5 and answers B1–B7.

**Implementation progress:** Phase 1 (items 1–4: `IsaConfig`, `misa` derivation, `hartId`,
instruction-fetch fault handling) is done, plus Phase 2 item 7 (interrupt gating fix, MSIP/MEIP
dispatch, `injectInterrupt`) landed early — see the inline "done" notes in the Existing Gap
section, Design Decisions §1/§2/§6/§7, and the Staging Plan. Rolling status lives in
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
| Bus access metadata (AccessContext) | P1 | S |
| `atomicRmw` + `tryScAndStore` / bus-internal tracking | P1 | M |
| Zba (3 instructions) | P2 | XS |
| Zbb (~18 instructions) | P2 | S |
| Zabha sub-word AMOs | P2 | S |
| C extension (fetch loop refactor) | P2 | M |
| F extension (new register file + ~50 instrs) | P3 | L |

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

### 3. Bus Access Metadata — `AccessContext` via Default Method Overloads

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

### 4. AMO Atomicity — `atomicRmw` Bus Primitive

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

### 5. Cross-Hart LR/SC Reservations

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

**U-mode CSR access restriction — done (`<pending>`).** The current CSR decode path in
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

**MRET (existing).** Restores privilege from `mstatus.MPP`, restores `MIE` from `MPIE`, clears
`MPP` to U-mode. Already correct at lines 481–485.

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

---

## ISA Extension Implementation Details

### Zba (Address Generation)

Three new OP-class instructions in the decode of opcode `0x33`, funct7 `0x10`:

| funct3 | Instruction | Computation |
|---|---|---|
| 2 | SH1ADD | `rd = (rs1 << 1) + rs2` |
| 4 | SH2ADD | `rd = (rs1 << 2) + rs2` |
| 6 | SH3ADD | `rd = (rs1 << 3) + rs2` |

The existing `legalEncoding` check in the OP/OP-IMM decode block must be extended to admit
funct7 `0x10` when `IsaConfig.hasZba` is true. Low risk; no fetch or state changes.

### Zbb (Basic Bit Manipulation)

~18 new instructions. Key funct7 assignments in OP (0x33) and OP-IMM (0x13):

| funct7 | funct3 | Instructions |
|---|---|---|
| 0x04 | various | CLZ, CTZ, CPOP (OP-IMM, rs2=0/1/2), SEXT.B, SEXT.H |
| 0x05 | 4–7 | MIN, MAX, MINU, MAXU (OP) |
| 0x20 | 6,7 | ANDN, ORN (funct3 6,7) — same funct7 as SUB/SRA; discriminate by funct3 |
| 0x20 | 4 | XNOR |
| 0x30 | 1,5 | ROL, ROR (OP); RORI (OP-IMM) |
| 0x18 | 5 | ORC.B (OP-IMM) |
| 0x68 | 5 | REV8 (OP-IMM) |

Java standard library provides efficient hardware-backed implementations for the heavy operations:
`Integer.numberOfLeadingZeros()` (CLZ), `Integer.numberOfTrailingZeros()` (CTZ),
`Integer.bitCount()` (CPOP), `Integer.reverse()` (used in REV8).

The `legalEncoding` guard must be relaxed for these funct7 values when `IsaConfig.hasZbb`.

### Zabha (Byte/Halfword AMOs — confirmed scope)

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

### C Extension (Compressed Instructions)

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

### F Extension (Single-Precision Floating-Point — lowest priority, AP only)

Effort: **L**. Planned here at interface depth only; full decode design is deferred.

**State additions (`RV32IMAState`):**
- `float[] fregs = new float[32]` — FP register file.
- `int fcsr` — FP control and status (bits 7–5: rounding mode `frm`; bits 4–0: accrued
  exception flags `fflags`).

**New CSRs handled in `readCsr`/`writeCsr`:** `0x001` (fflags), `0x002` (frm), `0x003` (fcsr).

**New opcodes:**
- `0x07` — FLW (FP load word)
- `0x27` — FSW (FP store word)
- `0x43`–`0x4B` — FMADD/FMSUB/FNMSUB/FNMADD (fused multiply-add family)
- `0x53` — all other FP arithmetic, comparisons, conversions, and moves

**Java implementation notes:** Java `float` is IEEE 754 single-precision. `Float.NaN` must be
canonicalized to the canonical quiet NaN (`0x7FC00000`) on write to a register. Denormal
arithmetic, infinity, and signed zero are handled correctly by the JVM on most hosts. Java
primitive `float` operations follow IEEE 754 round-to-nearest-even (RNE) by default, which
matches RISC-V's default rounding mode (`frm = 0b000`). The correctness risk lies in the four
**non-default** RISC-V rounding modes (RTZ/0b001, RDN/0b010, RUP/0b011, RMM/0b100) and the
accrued exception flag bits in `fflags` — these require explicit handling beyond primitive float
arithmetic and are the primary source of F-extension test failures. Dedicated test vectors for
each rounding mode and each `fflags` bit are required before declaring F complete.

Guard with `IsaConfig.hasF`. All new opcodes with F disabled → illegal instruction trap.

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
   → illegal-instruction trap.~~ **Done (`<pending>`)**.
6. Add `AccessContext` record and `AccessKind` enum; add default-method overloads to
   `MemoryBus`; plumb context through all core load/store/fetch calls.
7. ~~Add `injectInterrupt`, MSIP/MEIP delivery, and the privilege-dependent interrupt gating
   fix (§6–§7).~~ **Done (`5620de0`)**, landed ahead of the rest of this phase as a standalone
   correctness fix. Acknowledgement against a real mailbox device is still open (§6).
8. Add `atomicRmw` default method to `MemoryBus`; route all AMO instructions through it.
9. Add `tryScAndStore` default method to `MemoryBus`; route SC.W through it (core retains
   local fast-path reservation check in state; bus makes final atomic decision for multi-hart).
10. Define `ReservationTable` as a bus-internal type; document how a multi-hart bus override
    manages it privately via `readInt` (LR detection), `tryScAndStore`, `atomicRmw`, and
    all store-width overrides. Cover translated aliases, indivisible LR registration, and
    SC reservation consumption. Specify the memory-ordering contract in §3.
    `step()` gains no new parameter.

At the end of Phase 2, the core supplies the processor support for interrupt-capable
synchronized MMIO mailboxes. The emulator supplies the mailbox device, synchronization,
acknowledgement, and AP trap/IOP notification path. No later ISA phase is required for mailbox v1.
Cross-hart LR/SC and AMO correctness requires a conforming multi-hart bus implementation and
verification of the ordering contract; adding default methods alone does not establish it.

### Phase 3 — Integer ISA Extensions (P2)

11. Zba (3 instructions in OP decode).
12. Zbb (~18 instructions; extend OP/OP-IMM decode, update `legalEncoding` guard).
13. Zabha byte/halfword AMOs (confirmed; extend AMO decoder and width-aware bus handling).

### Phase 4 — Compressed Instructions (P2)

14. C extension: halfword alignment, 16-bit fetch path, `decodeCompressed`, PC advance.

### Phase 5 — Floating-Point (P3 — AP only, separate effort)

15. F extension: register file, fcsr, opcode decode for FLW/FSW/arithmetic/conversion.

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
[PLAN_REVIEW_RESPONSE.md](PLAN_REVIEW_RESPONSE.md). The six consistency fixes listed in
section A of the review request are incorporated in r6. The original review request remains
an r5 historical document; the five phases in this plan are the authoritative r6 numbering.

The requested scope and API boundaries are accepted for implementation with the contracts
above. This is plan-level acceptance, not a claim that the features are implemented or verified.
In particular, Phase 2 completion requires actual software/external interrupt delivery, a
conforming bus for cross-hart atomics, and verification of memory ordering. The emulator owns
the mailbox device and AP trap-to-IOP notification/recovery path.
