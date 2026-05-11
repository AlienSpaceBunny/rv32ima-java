# Feature Request Plan: V-32 AP/IOP Support

## Overall Verdict

The requested feature set is **fully implementable** within the current architecture. Nothing in the
request requires redesigning the core; it requires extending it in well-defined, layered increments.
The base `RV32IMA_Zicsr` use case is preserved throughout: new ISA features gate on a config
object, new bus callbacks have backward-compatible defaults, and all existing embedder code
compiles and behaves identically without changes.

The work breaks into two independent tracks that can proceed in parallel:

- **Track A — Multi-hart infrastructure** (interrupt injection, bus metadata, cross-hart atomics):
  unblocks the AP/IOP mailbox and privilege separation. This is the stated top priority.
- **Track B — ISA extensions** (C, Zba, Zbb, Zabha, F): extends the instruction set. Each
  extension is self-contained and can land independently.

Estimated relative effort (rough):

| Feature | Track | Effort |
|---|---|---|
| ISA config + hart ID | A | XS |
| Interrupt injection API | A | XS |
| Bus access metadata (AccessContext) | A | S |
| Instruction-fetch fault via bus | A | XS |
| Cross-hart LR/SC + AMO atomicity | A | M |
| Zba (3 instructions) | B | XS |
| Zbb (~18 instructions) | B | S |
| Zabha sub-word AMOs (if confirmed) | B | S |
| C extension (fetch loop refactor) | B | M |
| F extension (new register file + ~50 instrs) | B | L |

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
This plan designs for all three but treats Zabha as independently optional.

---

## Existing Gap: Instruction-Fetch Fault Handling

The current `step()` loop does not wrap `mem.readInt(pc)` in a try/catch. An
`IndexOutOfBoundsException` from a fetch would propagate uncaught to the caller rather than
converting to an instruction access-fault trap (cause 1). This is a latent correctness issue
today and is directly blocking for the AP MPU model (IOP must be able to reject AP instruction
fetches with the correct guest trap). Fixing this is a prerequisite for Track A.

**Fix:** Wrap the fetch call in try/catch, convert `IndexOutOfBoundsException` → trap cause 2
(instruction access-fault), set `rval = pc`, break the instruction loop.

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
implementation overrides `atomicRmw` to hold a per-granule lock around the read-modify-write,
making AMOs correct across harts. The default preserves today's single-hart behavior.

### 5. Cross-Hart LR/SC Reservations

LR/SC reservations currently live in `RV32IMAState.reservationAddr/reservationValid`. For
cross-hart correctness, a store from Hart 1 must invalidate Hart 2's reservation. The approach:

- The reservation state remains in `RV32IMAState` for ownership simplicity.
- Add an optional `ReservationTable` — a shared mutable object that both harts hold a reference
  to — passed as a new optional parameter to `step()`.
- `LR.W`: registers `(hartId, addr)` in the shared table.
- `SC.W`: validates that the table still holds a reservation for this hart/addr. Clears it on
  success.
- Any `writeInt`, `writeByte`, `writeShort`: calls `table.invalidateOverlapping(addr, width)`,
  which clears any reservation whose granule overlaps the written address.
- The `ReservationTable` is thread-safe internally (uses `synchronized` or `AtomicReference`).
- Single-hart callers pass `null` for the table; behavior is identical to today.

This keeps the cross-hart mechanism entirely in the library without requiring the embedder to
implement it, while making it opt-in.

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
arithmetic, infinity, and signed zero are handled correctly by the JVM on most hosts, but the
rounding mode bits in `fcsr.frm` require explicit `RoundingMode` injection into each operation
(Java's default is `HALF_UP`; the RISC-V default is `RNE` / round-to-nearest-even). This is
the main correctness risk for F and warrants dedicated test vectors.

Guard with `IsaConfig.hasF`. All new opcodes with F disabled → illegal instruction trap.

---

## Staging Plan

The following order is recommended. Each step is independently committable and testable.

### Phase 1 — Foundation (prerequisite for everything)

1. Add `IsaConfig` (immutable value type with extension flags; derive `misa` from it).
2. Add `hartId` to `RV32IMAState`.
3. Fix the instruction-fetch exception gap (wrap `mem.readInt(pc)` in try/catch).
4. Derive `misa` from `IsaConfig` instead of hardcoded constant.

### Phase 2 — Multi-Hart Infrastructure (Track A)

5. Add `AccessContext` record and `AccessKind` enum; add default-method overloads to
   `MemoryBus`; plumb context through all core load/store/fetch calls.
6. Add `injectInterrupt` helper; document MSIP/MEIP bit assignments.
7. Add `atomicRmw` default method to `MemoryBus`; route all AMO instructions through it.
8. Add `ReservationTable`; pass as optional parameter to `step()`; implement cross-hart
   LR/SC invalidation on store.

At the end of Phase 2, the AP/IOP mailbox use case is fully unblocked.

### Phase 3 — Integer ISA Extensions (Track B, part 1)

9. Zba (3 instructions in OP decode).
10. Zbb (~18 instructions; extend OP/OP-IMM decode, update `legalEncoding` guard).
11. Zabha byte/halfword AMOs (conditional on OQ-1 answer; extend AMO decoder).

### Phase 4 — Compressed Instructions (Track B, part 2)

12. C extension: halfword alignment, 16-bit fetch path, `decodeCompressed`, PC advance.

### Phase 5 — Floating-Point (Track B, part 3 — AP only, separate effort)

13. F extension: register file, fcsr, opcode decode for FLW/FSW/arithmetic/conversion.

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
- **Phases 3–4:** Extend `RV32IComplianceTest` with new instruction vectors (style already
  established in the existing compliance suite).
- **Phase 5:** Dedicated FP compliance vectors; NaN canonicalization and rounding mode tests
  required before declaring F complete.

---

## Plan Adequacy Review

This plan is broadly adequate for the requested `rv32emu-core` work, but it needs a few
corrections before it is implementation-ready.

### Required Corrections

1. **Fix the instruction-fetch trap cause.** The "Existing Gap" section correctly identifies an
   instruction access fault as cause 1, but its proposed fix says to convert fetch
   `IndexOutOfBoundsException` to trap cause 2. Cause 2 is illegal instruction. Fetch access
   rejection should raise instruction access fault, cause 1.
2. **Add explicit privilege and `Zicsr` coverage.** The feature request calls out AP user mode,
   IOP machine mode, `ECALL`, `MRET`, `mtvec`, `mepc`, `mcause`, `mtval`, `mstatus`, `mie`, and
   `mip`. The plan should specify the intended privilege behavior and tests: AP runs in U-mode,
   IOP runs in M-mode, U-mode CSR access traps correctly, `ECALL` cause depends on privilege,
   `MRET` restores privilege/interrupt state, and `mie`/`mip`/`mstatus.MIE` gating works.
3. **Clarify fetch authorization through the bus.** The existing API uses `ramOffset`/`ramSize`
   as a coarse instruction-fetch window before fetching through `MemoryBus`. The plan should say
   whether this remains a compatibility precheck or is relaxed so AP-facing bus metadata can own
   instruction-fetch authorization.
4. **Soften the mailbox claim.** Phase 2 should not claim that guest shared-memory mailbox usage
   is fully unblocked. The project still plans mailbox v1 as synchronized MMIO until cross-hart
   LR/SC/AMO behavior is proven. Better wording: Phase 2 unblocks interrupt-capable synchronized
   MMIO mailboxes and provides the foundation for later shared-memory queues.
5. **Tighten LR/SC atomicity.** The reservation-table design should explicitly require `SC.W`
   validation and the conditional store to be atomic with respect to reservation invalidation.

### Priority Assessment

The plan mostly reflects the right priorities, but the headings and staging should make them more
explicit:

1. **Safe multi-hart operation first.** This includes hart identity, access metadata, correct
   fetch/load/store traps, interrupt injection, AP U-mode/IOP M-mode privilege behavior, and a
   synchronized-MMIO mailbox path. Cross-hart LR/SC and AMO correctness belong here as the
   foundation for future shared-memory IPC, but mailbox v1 should not depend on guest atomics.
2. **Compressed and integer extensions second.** `C`, `Zba`, `Zbb`, and optional byte/halfword
   AMOs are valuable ISA-coverage work, but they are not prerequisites for safe AP/IOP operation.
   This stage should explicitly exclude `F`.
3. **Floating point last, AP only.** `F` is a separate AP-only effort and should remain deferred
   until multi-hart behavior and non-F ISA coverage are stable.
