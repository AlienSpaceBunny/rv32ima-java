# Feature Request Plan: V-32 AP/IOP Support

## Overall Verdict

The requested feature set is **fully implementable** within the current architecture. Nothing in the
request requires redesigning the core; it requires extending it in well-defined, layered increments.
The base `RV32IMA_Zicsr` use case is preserved throughout: new ISA features gate on a config
object, new bus callbacks have backward-compatible defaults, and all existing embedder code
compiles and behaves identically without changes.

The work is organized into three ordered priority lanes. Later lanes do not unblock earlier ones
and may be deferred, but each lane is internally ordered.

- **P1 — Safe multi-hart operation (top priority):** ISA config, hart ID, fetch-fault fix,
  privilege-mode semantics (U-mode CSR access fix), AccessContext bus metadata, interrupt
  injection, `atomicRmw`, `tryScAndStore`/`ReservationTable`. Unblocks the AP/IOP interrupt and
  synchronized MMIO mailbox path.
- **P2 — Non-F ISA extensions:** C (compressed), Zba, Zbb, optional Zabha. Valuable ISA
  coverage but not prerequisites for safe AP/IOP operation.
- **P3 — AP-only floating-point:** F extension. Largest effort, deferred to a separate phase.

Estimated relative effort (rough):

| Feature | Priority | Effort |
|---|---|---|
| ISA config + hart ID | P1 | XS |
| Instruction-fetch fault via bus | P1 | XS |
| U-mode CSR access privilege check | P1 | XS |
| Interrupt injection API | P1 | XS |
| Bus access metadata (AccessContext) | P1 | S |
| `atomicRmw` + `tryScAndStore` / ReservationTable | P1 | M |
| Zba (3 instructions) | P2 | XS |
| Zbb (~18 instructions) | P2 | S |
| Zabha sub-word AMOs (if confirmed) | P2 | S |
| C extension (fetch loop refactor) | P2 | M |
| F extension (new register file + ~50 instrs) | P3 | L |

---

## Open Questions

### OQ-1: "Zab" naming

The feature request uses the name "Zab", which is not a standard RISC-V extension name.
Three likely intended meanings, with different scope and effort:

| Candidate | What it adds | Effort |
|---|---|---|
| **Zba** | Address generation: `SH1ADD`, `SH2ADD`, `SH3ADD` | XS (3 instrs) |
| **Zba + Zbb** | Both bit-manipulation extensions | S combined |
| **Zabha** | Byte/halfword AMOs: `AMOADD.B/H`, `AMOSWAP.B/H`, etc. | S (extends existing AMO decoder) |

"Zba" and "Zbb" are both ratified. "Zabha" (byte/halfword AMOs) was ratified in 2024.
Sub-word LR/SC (`LR.B`, `LR.H`, `SC.B`, `SC.H`) is **not** part of any ratified RISC-V spec
as of 2025 and is excluded from this plan unless explicitly requested.

**Action:** Confirm with the originating LLM whether all three are needed or only Zba+Zbb.
This plan designs for all three but treats Zabha as independently optional. The specific
questions for that review are in [`PLAN_REVIEW_REQUEST.md`](PLAN_REVIEW_REQUEST.md) (B1);
`Zba` and `Zbb` are named explicitly in the request, so only the byte/halfword-atomic scope
(Zabha, and whether unratified sub-word LR/SC is expected) is actually open.

---

## Existing Gap: Instruction-Fetch Fault Handling

The current `step()` loop does not wrap `mem.readInt(pc)` in a try/catch. An
`IndexOutOfBoundsException` from a fetch would propagate uncaught to the caller rather than
converting to an instruction access-fault trap (cause 1). This is a latent correctness issue
today and is directly blocking for the AP MPU model (IOP must be able to reject AP instruction
fetches with the correct guest trap). Fixing this is a prerequisite for Track A.

**Fix:** Wrap the fetch call in try/catch, convert `IndexOutOfBoundsException` → `trap = 1 + 1`
(the codebase's `+1` internal encoding for instruction access-fault; `mcause` receives `1` after
the trap handler's `trap - 1` step), set `rval = pc`, break the instruction loop.

`ramOffset`/`ramSize` remains as a coarse DRAM-window precheck (existing behavior, unchanged).
The context-bearing bus overrides (Design Decision §3) provide fine-grained per-access
authorization — for example, AP MPU enforcement — on top. Both layers independently produce
instruction-access-fault traps through the same `trap = 1 + 1` path.

---

## Design Decisions

### 1. ISA Configuration Object

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

### 2. Hart Identity

Add `int hartId` to `RV32IMAState` (defaults to 0). The embedder sets it before first use.
The core passes `hartId` through to access-context callbacks (see §3). No other core behavior
depends on the hart ID; it is purely an observable label.

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
An embedder implementing the AP MPU overrides the context-bearing versions only.

Access faults are still signalled by throwing `IndexOutOfBoundsException` from any version of the
method; the core's existing catch paths convert this to the correct trap.

The `privilege` field in `AccessContext` is the hart's current privilege level (from
`extraflags & 3`). The `atomicOp` field is the `funct5` encoding for AMOs, or 0 for
non-atomic accesses.

The `atomicOp` field is also the signal a multi-hart bus uses to register LR.W reservations:
`ctx.kind == AMO && ctx.atomicOp == 2` identifies a load-reserve, allowing the bus override
of `readInt(addr, ctx)` to record `(ctx.hartId, addr)` in its private cross-hart table without
any additional parameter on the call.

### 4. AMO Atomicity — `atomicRmw` Bus Primitive

The current AMO implementation in `RV32IMACore` (lines 529–579) calls `mem.readInt` then
`mem.writeInt` as two separate bus transactions. This is not atomic with respect to a concurrent
hart even if both calls individually acquire a lock. For the single-hart case this is acceptable,
but it breaks cross-hart AMO correctness.

Add an optional bus primitive:

```java
// On MemoryBus — default is non-atomic, existing implementations unaffected:
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
  by the context it already receives.
- `SC.W`: the core checks `state.reservationValid` as a fast-path local pre-check. If false,
  returns failure immediately (no bus call). If true, calls
  `mem.tryScAndStore(hartId, addr, value, ctx)` — the bus makes the final atomic decision,
  checking its private table under a granule lock. The bus may reject even if the local state
  says valid (cross-hart invalidation happened between the LR and SC). After a successful store,
  the core clears `state.reservationValid`.
- `AMO`: core calls `mem.atomicRmw(...)`. The bus override holds the granule lock, performs the
  RMW, and invalidates any overlapping reservation entries in its private table atomically.
- **Plain stores**: the bus's `writeInt(addr, value, ctx)` override (with `ctx.kind == STORE`)
  automatically invalidates matching entries in its private table. The core requires no explicit
  invalidation call for plain stores.
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
must be called while holding whatever synchronization guards the AP's `RV32IMAState`.

### 7. Privilege-Mode Semantics

This section specifies the intended behavior for AP (U-mode) and IOP (M-mode) privilege
handling. Items marked **existing** are already implemented correctly; only items marked
**new fix** require code changes.

**U-mode CSR access restriction (new fix).** The current CSR decode path in `RV32IMACore`
(line 437) has no privilege check — any privilege level can read or write any CSR. The RISC-V
spec encodes the minimum required privilege in CSR bits[9:8]: M-mode CSRs have `0b11`, and
U-mode-accessible CSRs (cycle/time/instret, `0xC00`–`0xCFF`) have `0b00`. Add before the CSR
dispatch:

```java
if (((csrno >> 8) & 3) > (state.extraflags & 3)) {
    trap = (2 + 1); // illegal instruction
    break;
}
```

This raises an illegal-instruction trap when U-mode code attempts to access any M-mode CSR,
and passes U-mode reads of `0xC00` (cycle) since their privilege field is `0b00`.

**ECALL cause by privilege (existing).** `ECALL` from M-mode sets `mcause = 11`; from U-mode
sets `mcause = 8`. Already correct at line 489.

**MRET (existing).** Restores privilege from `mstatus.MPP`, restores `MIE` from `MPIE`, clears
`MPP` to U-mode. Already correct at lines 481–485.

**Trap entry (existing).** All traps and interrupts enter M-mode unconditionally (`extraflags |= 3`),
saving the prior privilege in `mstatus.MPP`. Already correct at line 621.

**Interrupt gating (existing).** Interrupts are only delivered when `mstatus.MIE = 1` and the
corresponding bit in `mie` is set. Timer interrupt additionally requires `mtimecmp != 0`. All
correct.

**AP / IOP privilege assignment.** The embedder sets `state.extraflags & 3` before first use:
`0` for AP (U-mode), `3` for IOP (M-mode). The core does not configure this — it is embedder
responsibility.

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

### Zabha (Byte/Halfword AMOs — conditional on OQ-1)

Extend the existing AMO decoder at opcode `0x2F`. Currently funct3 must be 2 (word). Zabha
adds funct3 0 (byte) and 1 (halfword) with the same funct5 operations. Byte/halfword AMOs
require read-modify-write with sub-word masking:
- Read full word, mask the target byte/halfword, apply operation, write back.
- The `atomicRmw` bus primitive handles synchronization.

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

### Phase 1 — Foundation (prerequisite for everything)

1. Add `IsaConfig` (immutable value type with extension flags; derive `misa` from it).
2. Add `hartId` to `RV32IMAState`.
3. Fix the instruction-fetch exception gap (wrap `mem.readInt(pc)` in try/catch).
4. Derive `misa` from `IsaConfig` instead of hardcoded constant.

### Phase 2 — Multi-Hart Infrastructure (P1 continued)

5. Add U-mode CSR access privilege check (Design Decision §7): `((csrno >> 8) & 3) > privilege`
   → illegal-instruction trap.
6. Add `AccessContext` record and `AccessKind` enum; add default-method overloads to
   `MemoryBus`; plumb context through all core load/store/fetch calls.
7. Add `injectInterrupt` helper; document MSIP/MEIP bit assignments.
8. Add `atomicRmw` default method to `MemoryBus`; route all AMO instructions through it.
9. Add `tryScAndStore` default method to `MemoryBus`; route SC.W through it (core retains
   local fast-path reservation check in state; bus makes final atomic decision for multi-hart).
10. Define `ReservationTable` as a bus-internal type; document how a multi-hart bus override
    manages it privately via `readInt` (LR detection), `tryScAndStore`, `atomicRmw`, and
    `writeInt` overrides. `step()` gains no new parameter.

At the end of Phase 2, interrupt-capable synchronized MMIO mailboxes are fully supported
(covering the mailbox v1 path from the feature request's "Current Workaround"). Cross-hart
LR/SC and AMO correctness are also complete, providing the foundation for future shared-memory
IPC queues; mailbox v1 does not depend on guest atomics.

### Phase 3 — Integer ISA Extensions (P2)

11. Zba (3 instructions in OP decode).
12. Zbb (~18 instructions; extend OP/OP-IMM decode, update `legalEncoding` guard).
13. Zabha byte/halfword AMOs (conditional on OQ-1 answer; extend AMO decoder).

### Phase 4 — Compressed Instructions (P2)

14. C extension: halfword alignment, 16-bit fetch path, `decodeCompressed`, PC advance.

### Phase 5 — Floating-Point (P3 — AP only, separate effort)

15. F extension: register file, fcsr, opcode decode for FLW/FSW/arithmetic/conversion.

---

## Backward Compatibility

All changes are additive with respect to the existing public API:

- `RV32IMACore()` zero-arg constructor preserved; internally uses `IsaConfig.RV32IMA_ZICSR`
  as default, identical to current behavior.
- `MemoryBus` implementations are unaffected; new context-bearing methods have defaults.
- `RV32IMAState` gains new fields (`hartId`, `fregs`, `fcsr`) at zero/null defaults.
- `step()` signature gains one new optional parameter (`ReservationTable`); existing callers
  use an overload that passes `null`.
- `misa` CSR value will change for non-base configs; for the default config it stays
  `0x40401101`.

The existing CLI integration test (`IntegrationTest.java`) and all unit tests continue to pass
without modification after each phase.

---

## Verification

Each phase should be verified before the next begins:

- **Phase 1:** `mvn test` passes; `misa` value reflects config for a constructed extended core.
- **Phase 2:** Unit test: two `RV32IMAState` instances sharing a `ReservationTable` and an
  `atomicRmw`-aware `MemoryBus`; verify LR/SC cross-hart invalidation and AMO ordering.
  Interrupt injection test: inject MEIP while core is in WFI; verify WFI flag clears.
  AccessContext test: custom `MemoryBus` asserts `hartId`, `privilege`, `kind` per access.
  Privilege tests: U-mode CSR access raises illegal-instruction trap; `ECALL` from U-mode sets
  `mcause = 8`; `MRET` correctly restores prior privilege level and `MIE`; `mie`/`mip`/
  `mstatus.MIE` gating blocks and delivers interrupts as expected.
- **Phases 3–4:** Extend `RV32IComplianceTest` with new instruction vectors (style already
  established in the existing compliance suite).
- **Phase 5:** Dedicated FP compliance vectors; NaN canonicalization and rounding mode tests
  required before declaring F complete.

---

## Revision History

- **r1** — Initial plan.
- **r2** — Incorporated review from originating LLM: clarified internal trap encoding (`trap = 1 + 1`)
  to prevent spec-number confusion; added two-layer fetch-authorization note; tightened
  `tryScAndClear` atomicity requirement; softened Phase 2 mailbox claim; added privilege-mode
  verification items to Phase 2. Correction 1 of the review (trap cause numbering) identified an
  ambiguity in wording rather than a semantic error — the codebase's `+1` internal encoding was
  correct throughout.
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

---

## Combined Review Notes

v5 fixes the major architectural-boundary issue from the prior plan: `ReservationTable` should not
be threaded through `step()` or exposed as a core-level coordination parameter. Cross-hart LR/SC
coordination belongs in the shared bus implementation, using `AccessContext` to identify hart,
access kind, and atomic operation. The plan now correctly makes the core responsible for local
per-hart reservation state and makes the bus responsible for final cross-hart arbitration.

The `ramOffset`/`ramSize` fetch window remains a borderline system-level concern, but keeping it as
a legacy coarse compatibility precheck is acceptable. Fine-grained fetch/load/store authorization
still belongs in the context-aware bus path.

Before treating v5 as implementation-ready, address these remaining items:

1. **Remove stale `step()`/`ReservationTable` compatibility text.** The Backward Compatibility
   section still says `step()` gains a `ReservationTable` parameter and existing callers use an
   overload passing `null`. That contradicts v5's core design, which says `step()` gains no new
   reservation parameter. Replace it with: `step()` remains source-compatible; cross-hart
   reservation coordination is implemented by bus overrides.
2. **Update verification to match bus-owned reservations.** The Phase 2 test still describes two
   states "sharing a `ReservationTable`." That should become two states sharing a multi-hart-aware
   `MemoryBus` with private reservation tracking. The test should explicitly cover
   `atomicRmw`, `tryScAndStore`, LR.W detection via `ctx.kind == AMO && ctx.atomicOp == 2`, and a
   plain-store-vs-`SC.W` race.
3. **Make plain-store ordering explicit.** v5 says a bus `writeInt(..., ctx)` override with
   `ctx.kind == STORE` automatically invalidates matching reservations. It should also state that
   multi-hart bus implementations must order plain store write + reservation invalidation against
   `tryScAndStore` using the same reservation/granule lock. Otherwise this invalid interleaving is
   still possible: Hart A has a valid LR reservation; Hart B performs a plain store but has not yet
   invalidated; Hart A executes `SC.W` and succeeds; Hart B invalidates. The store happened before
   the `SC.W`, so the `SC.W` should have failed.
4. **Clarify default methods are single-hart only for atomic correctness.** The `atomicRmw` and
   `tryScAndStore` defaults preserve current single-hart behavior, but they must not be presented as
   multi-hart safe. Multi-hart correctness requires bus overrides that lock RMW/check/store/
   invalidation as one critical section for the relevant granule.
5. **Clean up stale priority wording if desired.** The summary still says P1 includes
   `tryScAndStore`/`ReservationTable`; this is acceptable if `ReservationTable` is understood as a
   bus-internal implementation detail, but clearer wording would be "`tryScAndStore` plus
   bus-internal reservation tracking."
6. **Clean up revision ordering.** The revision history lists `r5`, then `r4`, then `r3`; this is
   editorial only.

Net assessment: v5 is the best shape so far. The public API boundary is now right, and the remaining
work is mostly consistency cleanup plus making the plain-store/SC ordering contract unambiguous.
