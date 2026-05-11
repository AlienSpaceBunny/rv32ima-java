# Test Suite Expansion Plan

## Context

The goal is a test suite that catches regressions when new ISA extensions are added in the future.
Current baseline (as of the Javadoc commit): **46 unit tests + 1 integration test**.

Tests live in `core/src/test/java/com/alienspacebunny/emu/`:
- `CoreTest.java` — instruction execution and trap tests (35 tests)
- `MMIOBusTest.java` — MMIO bus contract tests (7 tests)
- `FFMMemoryBusEndianTest.java` — little-endian memory layout tests (4 tests)
- `cli/IntegrationTest.java` — packaged CLI smoke test (1 test)

---

## Existing Coverage (CoreTest.java)

| Category | What's Covered |
|---|---|
| Basic arithmetic | ADDI, ADD |
| Load/store | SW, LW |
| Memory fault | Load below RAM, store past RAM, MMIO without hook |
| Boundary | First/last byte read/write |
| CSR side effects | CSRRW rd=x0, CSRRS rs1=x0, CSRRSI imm=0, CSRRC rs1=x0, CSRRCI imm=0 |
| Trap/MRET | ECALL (machine mode), MRET, timer interrupt entry |
| Timer | mtime < mtimecmp, mtime == mtimecmp, mtime > mtimecmp |
| WFI | WFI wake on timer interrupt |
| LR/SC | Success, SC without LR at 0x80000000, address mismatch, SC clears reservation, store clears reservation |
| AMO | AMOMIN.W signed, AMOMAX.W signed, AMOMINU.W, AMOMAXU.W |
| Illegal instruction | Trap cause, mtval=ir, no rd commit; invalid funct7, invalid shift, M-lookalike, invalid funct3, invalid AMO op |
| Valid encodings | Neighboring valid instructions still execute (SLLI, SUB, MUL, AMOADD) |

---

## Layer 1 — Instruction Exhaustion (add to CoreTest.java)

Target: one test per instruction mnemonic not already covered, plus edge cases. These run in the
same `CoreTest.java` file using the existing hand-encoded instruction helpers.

**Status: IN PROGRESS**

### New helpers needed

```java
private static int luiInstruction(int rd, int imm20)
private static int auipcInstruction(int rd, int imm20)
private static int jalInstruction(int rd, int relImm)
private static int jalrInstruction(int rd, int rs1, int imm12)
private static int branchInstruction(int funct3, int rs1, int rs2, int relImm)
```

### RV32I — Upper immediate

- [x] `luiLoadsUpperImmediate` — LUI writes correct upper bits, low 12 are zero
- [x] `auipcAddsUpperImmediateToPC` — AUIPC result = pc + imm<<12

### RV32I — Jumps

- [x] `jalLinksAndJumpsForward` — forward jump, rd = return address
- [x] `jalLinksAndJumpsBackward` — backward jump
- [x] `jalrJumpsToRegisterPlusOffset` — JALR, rd = pc+4
- [x] `jalrClearsLowBitOfTarget` — JALR always clears bit 0 of target

### RV32I — Branches (all six conditions, taken and not-taken)

- [x] `beqTakenWhenEqual` / `beqNotTakenWhenNotEqual`
- [x] `bneTakenWhenNotEqual` / `bneNotTakenWhenEqual`
- [x] `bltTakenWhenLessThanSigned` — negative < positive
- [x] `bltNotTakenWhenGreaterSigned`
- [x] `bgeTakenWhenGreaterOrEqualSigned`
- [x] `bltuTakenWhenLessThanUnsigned` — 1 < 0xFFFFFFFF unsigned
- [x] `bgeuTakenWhenGreaterOrEqualUnsigned`

### RV32I — Loads (signed/unsigned sign-extension)

- [x] `lbSignExtendsNegativeByte` — LB 0xFF → -1 in rd
- [x] `lbuZeroExtendsByte` — LBU 0xFF → 0xFF in rd (not sign-extended)
- [x] `lhSignExtendsNegativeHalfword` — LH 0x8000 → negative in rd
- [x] `lhuZeroExtendsHalfword` — LHU 0x8000 → 0x8000 in rd

### RV32I — Stores

- [x] `sbWritesLowByte` — SB writes only low byte, leaves rest unchanged
- [x] `shWritesLowHalfword` — SH writes only low 16 bits

### RV32I — OP-IMM (all funct3 not yet covered)

- [x] `addiNegativeImmediate` — negative imm sign-extends correctly
- [x] `sltiSignedComparison` — SLTI: rd=1 when rs1 < imm (signed)
- [x] `sltiuUnsignedComparison` — SLTIU: rd=1 when rs1 < imm (unsigned)
- [x] `xoriFlipsBits`
- [x] `oriSetsBits`
- [x] `andiClearsBits`
- [x] `slliShiftsLeft`
- [x] `srliShiftsRightLogical` — no sign extension
- [x] `sraiShiftsRightArithmetic` — preserves sign bit

### RV32I — OP (all funct3 not yet covered)

- [x] `subSubtracts`
- [x] `sllShiftsLeftByRegister`
- [x] `sltSignedComparison`
- [x] `sltuUnsignedComparison`
- [x] `xorXorsBits`
- [x] `srlShiftsRightLogical`
- [x] `sraShiftsRightArithmetic`
- [x] `orOrsBits`
- [x] `andAndsBits`

### RV32I — Misc

- [x] `writeToX0IsDiscarded` — result of any instruction targeting x0 is not committed
- [x] `fenceIsNoOp` — FENCE advances PC and does not trap
- [x] `ebreakRaisesBreakpointTrap` — cause 3, mepc = PC of ebreak

### RV32M — Edge cases

- [x] `mulhSignedUpperHalf`
- [x] `mulhsuMixedSignUpperHalf`
- [x] `mulhuUnsignedUpperHalf`
- [x] `divByZeroReturnsMinusOne`
- [x] `divuByZeroReturnsMaxUnsigned`
- [x] `divSignedOverflow` — MIN_VALUE / -1 = MIN_VALUE (no trap)
- [x] `remByZeroReturnsDividend`
- [x] `remuByZeroReturnsDividend`
- [x] `remSignedOverflow` — MIN_VALUE % -1 = 0

### RV32A — Basic AMO correctness (all ops not yet covered)

- [x] `amoswapWritesNewValueAndReturnsOld`
- [x] `amoadd_addsAndReturnsOld` (AMOADD basic; valid encoding test only hit it as a side effect)
- [x] `amoxorXorsAndReturnsOld`
- [x] `amoandAndsAndReturnsOld`
- [x] `amoorOrsAndReturnsOld`

---

## Layer 2 — Compliance-Style Per-Instruction Tests

**Status: NOT STARTED — begin after Layer 1 is committed**

A new test class `RV32IComplianceTest.java` modeled on riscv-tests but written entirely in Java
(no toolchain dependency). The approach:

- One test method per defined encoding variant in RV32I/M/A.
- A private helper `runOne(int instruction, int[] regInit, int[] memInit, int expectedRd,
  int... expectedMem)` sets up a minimal state, runs one instruction, and asserts.
- Alternatively, a parameterized JUnit 5 test with `@MethodSource` feeding `(label, ir, rs1, rs2,
  expectedRd)` tuples for pure ALU instructions.

Target: ~100 tests covering every defined instruction encoding with at least one representative
value set and one edge case (boundary values, sign boundaries, zero operands).

The riscv-tests reference in `/home/nate/work/riscv-tests` can be consulted for canonical test
vectors; the Java tests will not build or link the C/assembler suite — only use it as a reference
for expected results.

---

## Layer 3 — System-Level Scenario Tests

**Status: NOT STARTED — begin after Layer 2 is committed**

New test class or methods in `CoreTest.java` covering full system flows:

- `timerInterruptFireAndMretCycle` — interrupt entry + MRET round-trips privilege and mstatus
- `wfiSuspendsAndTimerWakesIt` — WFI stalls, elapsedUs crosses timer match, step returns 0
- `ecallFromUserModeRaisesUserEcall` — cause 8 (not 11) when privilege = 0
- `nestedCLINTRegisterAccess` — byte-accurate reads/writes of mtime/mtimecmp halves via CLINTHook
- `pcOutOfRangeRaisesInstructionAccessFault` — cause 1 (instruction access fault)
- `pcMisalignedRaisesInstructionAddressMisaligned` — cause 0

---

## After Layer 3

- **ISA compliance tests**: modeled on riscv-tests for RV32I/M/A/CSR. Hand-encoded in Java.
- **Differential tests**: run both this emulator and C mini-rv32ima against same binary; compare
  register state after N instructions.
- **Performance baseline**: run hello-world binary, record instructions/sec, gate on regression.
