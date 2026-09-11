# Session Checkpoint — 2026-09-10

## Where We Are

All P0–P7 bugs fixed and tested. Release tooling (Spotless, Checkstyle, SpotBugs, Javadoc +
source jars) in place. Public API Javadoc **done** (commits `b8a1fa8`, `a6e5fa7`). Compliance
test layers 1 & 2 **done** (`3c9be5b`, `22c0e30`) — 259 core + 1 cli tests.

JUnit 6.1.3 upgrade, architectural review, and the C1–C7 cleanup pass are all done and
pushed. Release readiness is documented but deliberately paused (`RELEASE_TODO.md`) — no
Central publish until after multi-hart Phase 1–2. `docs/FEATURE_REQUEST_PLAN.md` r6 has
been reviewed and conditionally signed off by the originating LLM (`docs/PLAN_REVIEW_RESPONSE.md`);
the one condition (real MSIP/MEIP interrupt delivery, not just injection) is done (`5620de0`).
**Phase 1 (foundation) is done (`c92045e`)** — see below. **Phase 2 is done** (items 5, 6, 7,
8, 9, 10 all complete; final pieces `4aeec77`) — see below. **Phase 3 is done** (items 11, 12,
13 — Zba, Zbb, Zabha — `590a4c9`) — see below. Phase 4 (C extension) is next, pending Nate's
go-ahead.

---

## Done This Session

### JUnit 5.10.0 → 6.1.3
- Added `org.junit:junit-bom` import to parent `<dependencyManagement>`; removed hardcoded
  `5.10.0` from all 5 dependency declarations. Version now in one `<junit.version>` property.
- Pinned `maven-surefire-plugin` to `3.5.4` (`<surefire.plugin.version>`) — was on Maven's
  bundled default, which predates JUnit Platform 6's unified-versioning jump.
- `./mvnw clean verify` green: 260 tests (259 core + 1 cli, same as baseline — no silent
  zero-discovery), SpotBugs/Checkstyle clean, CLI smoke passes. `dependency:tree` confirms 6.1.3.
- **Committed** — the JUnit pom changes were bundled into commit `90f7997` (whose message reads
  "Added .java-version for jenv" but whose diff is the 3 pom files). ⚠️ `.java-version` itself
  is still **untracked** and was NOT in that commit — it needs its own `git add` + commit.

### Architectural review + coding-standards spot check
- Verdict: **architecture sound, no redesign**; gaps are doc drift + unenforced conventions.
  A comprehensive review is **not** the next step — a targeted mechanical cleanup is.
- Full findings → `CLEANUP_TODO.md` (C1–C7). Scratch notes at
  `/tmp/.private/nate/claude-1000/-home-nate-work-rv32ima-java/8727d846-5df2-49a8-a8ff-7af444ab4f07/scratchpad/arch-review-notes.md`.

---

## Cleanup Pass (`CLEANUP_TODO.md`) — DONE, committed and pushed

Commits (on `main`, pushed): `0943a48` docs (C1+C2) · `1d45c2b` checkstyle+renames (C3+C4) ·
`1c3248d` named constants (C5) · `f14def6` API tidy (C6+C7) · `55f5c54` `.java-version`.
The JUnit 6.1.3 bump is in `90f7997` (whose message misleadingly says ".java-version").

| Item | What | Status |
|---|---|---|
| C1 | Reconciled `docs/API.md` with shipped P5/P6/P7 behavior; added "Javadoc is authoritative" note | Done |
| C2 | `docs/` reorg: `archive/` for superseded docs, active plans moved into `docs/`, `docs/README.md` index, `FEATURE_REQUEST_PLAN.old.md` deleted, README pointer added | Done |
| C3 | Added 9 naming modules to `config/checkstyle.xml` (default patterns) | Done |
| C4 | Renamed snake_case locals in `RV32IMACore` (`ofs_pc`→`ofsPc`, `imm_se`→`immSext`, `is_reg`→`isReg`, `immm4`→`branchOffset`, `reladdy`→`jumpOffset`, `dowrite`→`doWrite`, `rs1imm`→`rs1Index`, `writeval`→`writeValue`); test `amoadd_addsAndReturnsOld`→`amoaddAddsAndReturnsOld` | Done |
| C5 | Named constants + `exceptionTrap()` helper in `RV32IMACore` for all trap causes, `INTERRUPT_FLAG`, `MIP_MTIP`, `EXTRAFLAG_WFI`/`_PRIV_MASK`, `PRIV_USER`/`_MACHINE`, `MSTATUS_MPP_SHIFT` | Done |
| C6 | Removed dead `MemoryBus.readIntSigned` default method | Done |
| C7 | Narrowed `catch (Exception)` → `catch (IndexOutOfBoundsException)` in `Main.dumpState` and `MiniRV32IMACSRHook` (CSR 0x138) | Done |

`./mvnw clean verify` green after the full pass: 259 core + 1 cli tests, SpotBugs/Checkstyle
clean, CLI smoke passes. Spotless applied (no manual reformat needed).

Committed one-per-unit and pushed to `origin/main` (`0943a48`, `1d45c2b`, `1c3248d`,
`f14def6`, `55f5c54`, `0d004dc`). Review verdict stands: no comprehensive review warranted.

---

## After the Cleanup Pass

1. ~~Decide whether anything warrants a comprehensive review.~~ Done — no.
2. **Release readiness → `RELEASE_TODO.md`** (R1–R8). **Central publish still on
   hold** — immutable, and the public API is about to move under the feature-plan
   work below; no rush cost since namespace registration (`com.alienspacebunny`,
   also `us.n8l`) is already done. Revisit after Phase 1–2 land and an explicit
   API-freeze review happens. **Versioning + local release mechanics are done**
   (R5/R6, this session): `main` is `0.1.1-SNAPSHOT` (Nate: skip `0.1.0`, it's
   already referenced by the downstream V-32 project — first release will be
   `0.1.1`), `maven-release-plugin` is wired (tags `vX.Y.Z`, commits/tags locally
   only), `release.sh` fixed. Procedure in `docs/RELEASING.md`. `CHANGELOG.md`
   started (Keep a Changelog format; maintenance rule in `AGENTS.md`). No release
   has actually been cut yet.
3. Feature work per `docs/FEATURE_REQUEST_PLAN.md` r6. **Reviewed and conditionally
   signed off** by the originating LLM (`docs/PLAN_REVIEW_REQUEST.md` →
   `docs/PLAN_REVIEW_RESPONSE.md`, B1–B7). The one condition (real MSIP/MEIP delivery)
   is done. Remaining before/during implementation:
   - AP-to-IOP trap notification (B7): the emulator repo, not this one, needs a
     deliberate mechanism — AP traps enter M-mode *on the AP hart* and do not
     auto-notify the IOP. Nothing to do here; flagged for Nate.
   - Cross-hart LR/SC reservations must key off *translated* backing addresses (B3),
     since AP/IOP use different bus wrappers — a real design point for Phase 2, not yet
     coded.
   - `aq`/`rl` memory-ordering semantics are explicitly out of `AccessContext`'s scope
     (B6) — acceptable only if the eventual bus implementation supplies stronger
     ordering; flag before declaring shared-memory IPC safe.
   - ~~Start with Phase 1...~~ Done, see below. Phase 2 is next.

---

## Phase 1 Foundation (`c92045e`) — done

Implements `docs/FEATURE_REQUEST_PLAN.md` Staging Plan items 1–4. No behavior change for
existing callers — `RV32IMACore()`'s zero-arg constructor is untouched in effect.

- `IsaConfig` record (`hasC`/`hasF`/`hasZba`/`hasZbb`/`hasZabha`) + 3 presets +
  `misa()`; `RV32IMACore(IsaConfig)` constructor added alongside the zero-arg one;
  `misa` (CSR `0x301`) now derived from it instead of a hardcoded literal.
- `RV32IMAState.hartId` (default `0`), not yet read by the core.
- Instruction fetch (`mem.readInt(pc)`) now catches `IndexOutOfBoundsException` within
  the `ramOffset`/`ramSize` window and converts it to an instruction access-fault trap,
  matching data load/store behavior.
- Two things flagged for Nate; both resolved (see `docs/FEATURE_REQUEST_PLAN.md` §1):
  - **`misa` U-bit — resolved, `IsaConfig.hasU` added.** Nate: make it configurable. `false`
    (default, via a 5-arg compatibility constructor) reproduces the exact original hardcoded
    value including its non-standard bit 22; `true` (both V-32 presets) reports the standard
    U bit (20) instead. `RV32IMA_ZICSR.misa()` unchanged (`0x40401101`).
  - **F advertised before decoded on `RV32IMFC_ZBA_ZBB_ZICSR` — confirmed acceptable for now
    (Nate).** No change needed.
- 283 core + 1 cli tests (`./mvnw clean verify` green), up from 267 + 1.

---

## Phase 2 Progress — items 5 and 6 done

**Item 5 (`d9298a3`) — U-mode CSR access privilege check.** A CSR access now traps
illegal-instruction if the hart's privilege is below the CSR address's minimum-privilege
field (bits 9–8). Applies uniformly to hook-routed custom CSRs too — flagged in `CSRHook`'s
Javadoc, since mini-rv32ima's `0x136`–`0x140` console CSRs happen to fall in a
privilege-restricted range by coincidence of address, not intent. 287 core + 1 cli tests.

**Item 6 (`d9e93da`) — `AccessContext`/`AccessKind`, plumbed through every access.** New
context-bearing overloads on `MemoryBus` (all defaulting to delegate to the no-context
version — existing buses unaffected); `RV32IMACore` now passes context to fetch, every
load/store width, and both AMO read+write. Added `readByteSigned`/`readShortSigned`
context overloads beyond the plan's sketch, for full compatibility fidelity. **`MMIOBus`
doesn't forward context to `HardwareHook`** — flagged in its Javadoc, since it's the bus a
first AP implementation would most likely start from; a context-aware bus (AP MPU) should
implement `MemoryBus` directly instead. AMO accesses carry context now but the AMO block is
**still not cross-hart atomic** (two separate bus calls) — that's item 8. 301 core + 1 cli
tests.

**Items 8, 9, 10 (`4aeec77`) — done. Phase 2 is complete.** `RV32IMACore`'s AMO block
now routes through two new `MemoryBus` default methods instead of computing results
inline: `atomicRmw(address, funct5, operand, ctx)` handles the nine RMW AMOs (the old
inline switch moved into a private static `MemoryBus.computeAmo` helper), and
`tryScAndStore(hartId, address, value, ctx)` makes the bus's final atomic decision for
`SC.W` — called only if the core's local reservation fast-path check passes; the bus can
still reject a store the core's local state believed would succeed. `LR.W` is unchanged
(already routed through `readInt(address, ctx)` per item 6). Both default implementations
are single-hart-correct only; a multi-hart bus must hold a per-granule lock, per their
Javadoc. Item 10 (`ReservationTable`) is doc-only as the plan specifies — no class exists
or is needed in this repo; `docs/FEATURE_REQUEST_PLAN.md` §5 and the new methods' Javadoc
already state the full contract, and `step()`'s signature is unchanged. New
`AtomicPrimitivesTest` (8 tests) proves the routing contract with a recording bus,
including that the bus's rejection overrides the core's local belief, and that a fault
thrown from either new primitive becomes a standard store/AMO access fault (cause 7).
309 core + 1 cli tests.

**Phase 2 landing is not the same as cross-hart IPC being safe.** A conforming
multi-hart `MemoryBus` implementation doesn't exist yet in this repo, and `aq`/`rl`
memory-ordering semantics remain explicitly out of `AccessContext`'s scope pending that
bus's design (§3, §5) — flag this before anyone relies on shared-memory IPC between AP
and IOP.

---

## Phase 3 — Zba/Zbb/Zabha decode (`590a4c9`) — done

Items 11, 12, 13. `RV32IMACore`'s OP/OP-IMM decode block gained a `computeBitmanip` helper
(mirroring `MemoryBus.computeAmo`'s style) reached via a new `isBitmanip` branch computed
alongside `legalEncoding`, so an unsupported combination still falls through to the base
`legalEncoding` check and traps illegal-instruction exactly as before.

- **Zba** (`IsaConfig.hasZba`): `SH1ADD`/`SH2ADD`/`SH3ADD` (OP, funct7 `0x10`).
- **Zbb** (`IsaConfig.hasZbb`): all 18 instructions — `CLZ`, `CTZ`, `CPOP`, `SEXT.B`,
  `SEXT.H` (OP-IMM, funct7 `0x30`, discriminated by the `rs2` field); `ZEXT.H` (OP, funct7
  `0x04`, `rs2` fixed to `x0`); `MIN`/`MINU`/`MAX`/`MAXU` (OP, funct7 `0x05`); `ANDN`/`ORN`/
  `XNOR` (OP, funct7 `0x20`, same funct7 as `SUB`/`SRA` but disjoint funct3 values); `ROL`/
  `ROR` (OP, funct7 `0x30`); `RORI` (OP-IMM, funct7 `0x30`, shift amount in the `rs2` field);
  `ORC.B` (OP-IMM, funct7 `0x14`); `REV8` (OP-IMM, funct7 `0x34`, RV32 form). All encodings
  verified against the authoritative
  [riscv-opcodes](https://github.com/riscv/riscv-opcodes) machine-readable tables — an
  earlier draft of `docs/FEATURE_REQUEST_PLAN.md`'s encoding table had three funct7 values
  wrong and omitted `ZEXT.H` entirely; fixed in the same commit as the doc update.
- **Zabha** (`IsaConfig.hasZabha`): byte/halfword AMOs. The RV32A decoder's `funct3` field
  now also admits `0`/`1` for the nine RMW ops (still `2`-only for `LR.W`/`SC.W` — Zabha
  defines no sub-word `LR`/`SC`, and the core traps that combination illegal even with
  `hasZabha` set). `MemoryBus.atomicRmw`'s default implementation became width-aware via
  `ctx.width()`: it reads/writes at that width, sign-extends the loaded value and truncates
  `operand` to it before calling the unchanged, still-`int`-based `computeAmo`, and writes
  back only the low `width` bytes. This works without a sub-word variant of `computeAmo`
  because sign-extending both operands to the same width preserves their relative order
  under `Integer.compareUnsigned` — documented on `atomicRmw`'s Javadoc and locked in by
  `ZabhaTest`.
- New `RV32IComplianceTest` Zba/Zbb sections and `ZabhaTest` (8 tests): computation,
  edge/boundary values, neighboring-byte preservation for sub-word AMOs, signed-vs-unsigned
  comparison at reduced width, `rs2` bits above the operand width being ignored per the
  Zabha spec, and `IsaConfig` gating in both directions (extension present but the specific
  encoding not covered; extension absent entirely). 362 core + 1 cli tests.

**Phase 3 is ISA decode only** — it doesn't change the multi-hart bus contract. A
multi-hart-aware `MemoryBus`'s `atomicRmw` override still owns sub-word lock granularity for
Zabha, the same way it already owns word-width AMO/LR/SC coordination from Phase 2.

---

## Interrupt Gating Fix (`5620de0`) — done

While triaging the plan review, verified two real (if latent) defects directly in
`RV32IMACore.step()`, not hypothetical future ones: only `MTIP` was ever dispatched, and
`mstatus.MIE` unconditionally gated it — wrong per the RISC-V priv spec, which says
`mstatus.MIE` masks machine interrupts only while executing in machine mode. Dormant until
now because `Main.java` always runs the CLI in machine mode.

- Added `MIP_MSIP`/`MIP_MEIP`, `INT_MACHINE_SOFTWARE`/`INT_MACHINE_EXTERNAL`, and
  `RV32IMACore.injectInterrupt(state, bit)`.
- Corrected gating rule + external > software > timer priority.
- 8 new `CoreTest` cases (267 total, was 259). `./mvnw clean verify` green.
- `docs/FEATURE_REQUEST_PLAN.md` and `docs/API.md` updated with "done" markers.

---

## Key Decisions to Remember

- **Wildcard imports banned** by Checkstyle `AvoidStarImport`. Do not re-introduce.
- **Formatter**: Palantir Java Format 2.90.0. Run `./mvnw spotless:apply` before committing new
  source.
- **JUnit version** lives only in `<junit.version>` (parent pom) via the BOM. Bump there.
- **Surefire** pinned — do not remove the pin; the bundled default lags the Platform.
- **Big-endian JVM** explicitly out of scope (documented in `FFMMemoryBus` Javadoc).
- **Versioning is `maven-release-plugin`-driven** (superseded the earlier "no automated
  semantic versioning" note) — see `docs/RELEASING.md`. Don't hand-edit pom versions.
- **Changelog**: add a `CHANGELOG.md` entry under `[Unreleased]` in the same commit as any
  user-facing change — see `AGENTS.md`.
- **License**: MIT forked from MIT; existing `LICENSE` is sufficient.
- **Commit each release-readiness / cleanup step separately** with a clear message.
- `step()`'s 8-arg signature and long body are a **deliberate interpreter idiom** — not a
  cleanup target.
