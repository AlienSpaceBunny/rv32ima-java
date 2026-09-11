# Cleanup TODO — Post-P0–P7 Standards Pass

Driven by the architectural review + coding-standards spot check (2026-09-10). The review
verdict: **architecture is sound, no redesign needed**; the gaps are documentation drift and
unenforced coding conventions. This is a targeted mechanical cleanup, **not** a comprehensive
line-by-line review. Reassess whether a fuller pass is warranted only if this surfaces surprises.

Baseline: `./mvnw clean verify` green — 259 core + 1 cli tests, SpotBugs/Checkstyle clean,
CLI smoke passes, on JUnit 6.1.3.

Work items in priority order.

## Status — 2026-09-10

**C1–C7 all done, committed, and pushed to `main`; `./mvnw clean verify` green (259 core + 1
cli tests, SpotBugs/Checkstyle clean, CLI smoke passes).** Commits: `0943a48` (C1+C2),
`1d45c2b` (C3+C4), `1c3248d` (C5), `f14def6` (C6+C7).

| Item | Status | Notes |
|---|---|---|
| C1 | Done | `docs/API.md` rewritten; forward-refs removed; "Javadoc is authoritative" note added |
| C2 | Done | `docs/README.md` index; `docs/archive/` for `IMPLEMENTATION_TODO.md` + `REMAINING_WORK.md`; `FEATURE_REQUEST*.md` + `TEST_PLAN.md` moved to `docs/`; `FEATURE_REQUEST_PLAN.old.md` deleted; README pointer |
| C3 | Done | 9 naming modules added, Checkstyle default patterns |
| C4 | Done | All 8 identifiers renamed + 1 test method; pure rename |
| C5 | Done | `exceptionTrap()` + `EXC_*` / `INT_*` / `INTERRUPT_FLAG` / `MIP_MTIP` / `EXTRAFLAG_*` / `PRIV_*` / `MSTATUS_MPP_SHIFT` constants |
| C6 | Done | `MemoryBus.readIntSigned` removed |
| C7 | Done | Both `catch (Exception)` sites narrowed |

Nothing in the pass surfaced anything that warrants escalating to a comprehensive review.

---

## C1 — Reconcile `docs/API.md` with current behavior  *(High — release blocker)*

`docs/API.md` still describes the pre-P5/P6/P7 state and will misinform Maven Central users:

- ~L28: "Timer interrupt pending currently uses `mtime > mtimecmp`; P6 will change this to
  `>=`." — code already uses `>=` (`RV32IMACore.java:164`, `Long.compareUnsigned(...) >= 0`).
- ~L30: "P6 will change this to `mtime >= mtimecmp`" forward-reference — delete.
- ~L46: "Multi-byte endianness is intended to be little-endian. P5 will make this explicit in
  implementation and tests." — already explicit (`FFMMemoryBus.LE_SHORT`/`LE_INT`,
  `FFMMemoryBusEndianTest`).
- ~L89–91: "`extraflags` currently stores privilege, WFI state, and LR/SC reservation bits.
  P7 will replace the LR/SC reservation encoding with explicit reservation state." —
  reservations are now separate fields (`reservationAddr`, `reservationValid`); `extraflags`
  holds only privilege (bits 0–1) and WFI (bit 2).

Action: rewrite the affected sections to describe shipped behavior; remove all "Pn will…"
forward-references. Cross-check every claim against the class Javadoc (which is accurate).

---

## C2 — Consolidate / archive top-level planning docs  *(Med)*

8 tracked `.md` files at repo root, several stale or superseded:

| File | State |
|---|---|
| `CHECKPOINT.md` | rolling — keep, but currently points at already-shipped "Public API Javadoc" |
| `REMAINING_WORK.md` | P0–P7 all Done; release-readiness mostly Done — reads as active |
| `IMPLEMENTATION_TODO.md` | P0–P7 postmortem — fully superseded |
| `FEATURE_REQUEST.md` | original request — keep as archive |
| `FEATURE_REQUEST_PLAN.md` | current plan — keep |
| `FEATURE_REQUEST_PLAN.old.md` | committed `.old` file — delete (git history has it) |
| `TEST_PLAN.md` | status unverified — check against current test suite |
| `CLEANUP_TODO.md` | this file |

Action: create `docs/archive/`, move superseded docs there (or delete the `.old`), keep
`CHECKPOINT.md` + active TODOs at root. Add a one-line index to `README.md` or `docs/`.

---

## C3 — Add naming + style rules to Checkstyle  *(Med)*

`config/checkstyle.xml` has 5 modules (AvoidStarImport, EqualsHashCode, StringLiteralEquality,
SimplifyBoolean{Expression,Return}). No naming rule, no import-order rule — nothing enforces
the identifier convention, which is why C4 exists.

Action: add `LocalVariableName`, `ParameterName`, `MemberName`, `MethodName`, `TypeName`,
`ConstantName` (standard camelCase / UPPER_SNAKE patterns). Consider `CustomImportOrder` or
`ImportOrder`. Run `./mvnw verify`, fix violations (see C4), keep the build green.

---

## C4 — Normalize identifiers in `RV32IMACore`  *(Low — do with C3)*

C-carryover snake_case mixed with camelCase in the same method:

- snake_case: `ofs_pc`, `imm_se`, `is_reg`, `immm4`, `reladdy`, `dowrite`, `rs1imm`
- camelCase: `legalEncoding`, `accessFaultTrap`, `validAtomicOperation`, `writeval`

Action: rename to camelCase (`ofsPc`/`instrOffset`, `immSext`, `isReg`, `branchOffset`,
`jumpOffset`, `doWrite`, `rs1Index`, `writeValue`). Pure rename, no logic change; the test
suite (259 core tests) is the safety net. Do in the same commit as C3 so the rule and the
fix land together.

---

## C5 — Named constants for trap causes and status bits  *(Low)*

`MSTATUS_MIE/MPIE/MPP` constants exist and are used in some spots, but:

- Trap causes are bare literals throughout: `(1 + 1)`, `(2 + 1)`, `(3 + 1)`, `(5 + 1)`,
  `(7 + 1)`, `(8 + 1)`, `(11 + 1)`, plus interrupt `0x80000007`. The `+1` internal encoding
  (mcause = literal − 1) already confused a reviewer — see `FEATURE_REQUEST_PLAN.md` r2 notes.
- WFI flag: bare `& 4` / `|= 4` / `&= ~4`.
- Privilege: bare `& 3` / `|= 3`.
- MTIP: `1 << 7` in three places.

Action: introduce named constants (or a small `TrapCause` enum) — e.g.
`TRAP_INSTR_MISALIGNED`, `TRAP_INSTR_ACCESS_FAULT`, `TRAP_ILLEGAL_INSTR`, `TRAP_BREAKPOINT`,
`TRAP_LOAD_ACCESS_FAULT`, `TRAP_STORE_ACCESS_FAULT`, `TRAP_ECALL_U`, `TRAP_ECALL_M`,
`INT_MACHINE_TIMER`, `EXTRAFLAG_PRIV_MASK`, `EXTRAFLAG_WFI`, `MIP_MTIP`. Document the `+1`
encoding at the constant definitions. Behavior-preserving.

---

## C6 — Drop dead public API: `MemoryBus.readIntSigned`  *(Low — do before 1.0)*

`MemoryBus.readIntSigned` (`MemoryBus.java:95`) is documented "for symmetry", never called —
LW uses `readInt` directly. On a published interface this is a permanent binary-compat
commitment for no caller.

Action: remove the default method and its Javadoc; fix the doc reference at `MemoryBus.java:21`
if needed. (`readByteSigned`/`readShortSigned` stay — used by LB/LH.)

---

## C7 — Narrow over-broad catch clauses  *(Low)*

The `MemoryBus` contract specifies `IndexOutOfBoundsException` for faults, but two sites catch
`Exception`:

- `Main.dumpState:148` — around `ram.readInt(state.pc)`.
- `MiniRV32IMACSRHook.java:57` — CSR 0x138 string-print loop.

Action: narrow both to `catch (IndexOutOfBoundsException e)`. CLI-only, low risk.

---

## Not in scope (already known / tracked)

- Uncaught instruction-fetch `IndexOutOfBoundsException` in `step()` — documented as the
  "Existing Gap" in `FEATURE_REQUEST_PLAN.md`, scheduled for multi-hart Phase 1. Leave it there.
- `step()` 8-arg signature / ~450-line body — deliberate interpreter idiom, not a defect.
- CLI hooks' hardcoded MMIO addresses — acceptable for a reference runner.
- P0–P7 fixes — not re-audited; out of scope for this pass.
