# Session Checkpoint — 2026-09-16

## Latest: V-32 follow-up review answered (round 2, `0.1.5-SNAPSHOT`)

V-32 re-reviewed `0.1.4-SNAPSHOT` (`docs/archive/CPU_INTEGRATION_FOLLOWUP.md`): accepted all
round-1 changes and `checkAccess`, and raised two more, both real and both fixed
(`docs/CPU_INTEGRATION_RESPONSE_2.md`):

1. Misaligned `LR.W`/`SC.W` exited before the reservation clear → stale reservation reusable
   by a later SC without a new LR. Now cleared before the alignment check. Bus-side "inert
   stale entry, replaced by next LR.W" policy documented on `tryScAndStore`.
2. Interrupts were only evaluated at `step` entry / WFI → with `count > 1`, a `csrs
   mstatus/mie/mip` or `MRET` let the next instruction run before delivery. Now reevaluated
   after those instructions retire and delivered in the same call (`mepc` exact, incl.
   compressed targets; cycle counts only the retired instruction).

V-32's own probes rerun here against the `0.1.5` jar: `CoreFeatureProbe` exit 0,
`CoreResponseProbe` 30/30. **Resume:** V-32 bumps `build.gradle` to `0.1.5-SNAPSHOT` and
resumes its bus/privilege/mailbox integration.

## Latest: V-32 CPU integration review answered (`0664be7`, bump to `0.1.4-SNAPSHOT`)

V-32 reviewed `0.1.3-SNAPSHOT` (its `CPU_INTEGRATION_REVIEW.md`, 2026-09-15, copied into this
repo along with `MULTI_HART_BUS_NOTES.md` and `CoreFeatureProbe.java` in `0a22a97`, now
archived verbatim under `docs/archive/` as the review input) and found five processor defects. All fixed in `0664be7` with regressions; response for the V-32 side is
`docs/CPU_INTEGRATION_RESPONSE.md`:

1. U-mode `MRET` → illegal instruction (plus reserved rd/rs1 on SYSTEM funct3==0).
2. WFI lost wakeup: stall check now runs after the pending-interrupt computation; `WFI` itself
   doesn't stall when an interrupt is already deliverable (returns 0).
3. Atomic alignment traps (causes 4/6, `mtval` = guest address, no bus call); `LR.W` rs2≠0 illegal.
4. **API decision:** new `MemoryBus.checkAccess(address, ctx)` — side-effect-free permission
   probe, permit-all default, called on the locally-failing `SC.W` path so an MPU bus can fault
   it (cause 7). `FFMMemoryBus` bounds-checks; `tryScAndStore` overrides must permission-check
   before returning failure.
5. Every `LR.W`/`SC.W` attempt clears the local reservation before the bus is consulted.

Also: `MMIOBus` now forwards context-bearing overloads + `atomicRmw`/`tryScAndStore`/`checkAccess`
to its backing bus for non-hook addresses (was a context-dropping wrapper).
`docs/EMULATOR_REPO_NOTES.md`'s "injectInterrupt is safe across threads" claim was wrong and is
corrected (Javadoc requires caller synchronization). No compatibility flag added — see the
response doc's last section for why. Nothing in `../emulator` modified.

**Resume:** V-32 bumps `build.gradle` to `0.1.4-SNAPSHOT`, adds a one-line `checkAccess`
override to its probe bus, re-runs the probe (expected values in the response doc), then
resumes its bus/privilege integration. This repo waits on that; API stays unfrozen.

## Where We Are (as of 2026-09-13)

All P0–P7 bugs fixed and tested. Release tooling (Spotless, Checkstyle, SpotBugs, Javadoc +
source jars) in place. Public API Javadoc **done** (commits `b8a1fa8`, `a6e5fa7`). Compliance
test layers 1 & 2 **done** (`3c9be5b`, `22c0e30`) — 259 core + 1 cli tests.

JUnit 6.1.3 upgrade, architectural review, and the C1–C7 cleanup pass are all done and
pushed. Release readiness is documented but deliberately paused (`RELEASE_TODO.md`) — no
Central publish until after multi-hart Phase 1–2. `docs/FEATURE_REQUEST_PLAN.md` r6 has
been reviewed and conditionally signed off by the originating LLM (`docs/archive/PLAN_REVIEW_RESPONSE.md`);
the one condition (real MSIP/MEIP interrupt delivery, not just injection) is done (`5620de0`).
**Phase 1 (foundation) is done (`c92045e`)** — see below. **Phase 2 is done** (items 5, 6, 7,
8, 9, 10 all complete; final pieces `4aeec77`) — see below. **Phase 3 is done** (items 11, 12,
13 — Zba, Zbb, Zabha — `590a4c9`) — see below. **Phase 4 is done** (item 14 — the C extension —
`b224ac3`) — see below. **Phase 5a is done** (item 15's rounding-mode-independent subset — register file, `fcsr`, FLW/FSW, moves, sign injection, classify, comparisons, min/max — `f50e0a8`) — see below. **Phase 5b is done** (item 15's rounding-mode layer — FADD/FSUB/FMUL/FDIV/FSQRT.S, the FMADD family, FCVT conversions, full `fflags` — `814bde6`) — see below. **The F extension (item 15) is now fully decoded; Phase 5, and the whole `docs/FEATURE_REQUEST_PLAN.md` staging plan, is complete.** A follow-up gap Phase 4 had deliberately left open — `C.FLW`/`C.FSW`/`C.FLWSP`/`C.FSWSP`, pending F's decode — is now closed too (`3a42dbf`); see below.

**Release hold extended past this repo's own staging plan (Nate, 2026-09-13, `RELEASE_TODO.md`).**
Finishing the staging plan does **not** trigger the API-freeze review. The API stays
deliberately unfrozen — everything through whatever `-SNAPSHOT` `main` is currently on is
unstable — until the **emulator repo's** side of the multi-hart integration is done and has
exercised the API in practice; only then does a real release (**`0.2.0`**, not the earlier
`0.1.1` target — see `RELEASE_TODO.md`'s update) make sense. `main` is `0.1.2-SNAPSHOT` as of
this session (plain bump, no tag — `8f0b6cf`); expect more plain bumps before any real release.

**Multi-hart `MemoryBus` design notes handed off to the emulator repo (2026-09-13, not
committed here — see below for where).** Two of the three items previously flagged as
"belonging to the emulator repo" (cross-hart LR/SC reservations keyed on translated addresses,
`aq`/`rl` memory-ordering semantics, both needing a real multi-hart-aware `MemoryBus`
implementation that doesn't exist anywhere yet) got a detailed design writeup for whoever
implements that bus, wherever it ends up living. AP-to-IOP trap notification remains flagged
but undesigned — it needs a device the emulator repo owns, not a `MemoryBus` concern.

**What's plausibly next in this repo specifically** (no go-ahead yet on any of these —
flagging, not starting): D (double-precision) extension work, deliberately deferred out of
Phase 5 (`hasD` is misa-only today, see Design Decision §8); anything the emulator-repo
integration work surfaces as needed back here once it's underway.

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
   signed off** by the originating LLM (`docs/archive/PLAN_REVIEW_REQUEST.md` →
   `docs/archive/PLAN_REVIEW_RESPONSE.md`, B1–B7). The one condition (real MSIP/MEIP delivery)
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

## Phase 4 — C extension decode (`b224ac3`) — done

Item 14. The most structurally significant ISA change so far: it touches `step()`'s
instruction-fetch and PC-arithmetic logic, not just the decode switch.

- **Fetch and alignment**, gated by `IsaConfig.hasC`: unchanged (word-only fetch, 4-byte
  alignment) when `hasC` is false. When `hasC` is true, alignment drops to 2 bytes, and fetch
  first calls `mem.readShort(pc)`; if its low two bits are `11` it's a 32-bit instruction (a
  further `mem.readInt(pc)` call, possibly at a non-word-aligned address if the previous
  instruction was compressed — `FFMMemoryBus` uses `JAVA_INT_UNALIGNED`, verified by a new
  mixed-stream test), otherwise the 16 bits already read are the whole instruction.
- **`decodeCompressed(int)`** expands every base RV32C (Zca) instruction into an equivalent
  standard 32-bit RV32I/M word — direct field placement for register/immediate forms
  (`ADDI`/`LI`/`LUI`/`ANDI`/`SUB`/`XOR`/`OR`/`AND`/`LW`/`SW`/`SLLI`/`SRLI`/`SRAI`/`MV`/`ADD`),
  inverse-encoded scrambled immediates for `JAL`/`JALR`/branches — so the existing opcode switch
  runs it unmodified; no RVC instruction needed a direct-compute exception, contrary to the
  plan's expectation for `C.ADDI4SPN`. A reserved 16-bit pattern returns an opcode with no case
  in the switch, reusing its existing illegal-instruction `default` arm — no new trap-dispatch
  code needed. All bit-shuffle formulas came from the reference simulator's decoder
  (`riscv-isa-sim`), cross-checked against `riscv-opcodes`, not hand-derived.
- **`instrLen`** (4 or 2) replaces every literal instruction-length constant used for PC-target
  arithmetic: `JAL`/`JALR`/branch targets, `MRET`, `WFI`, the loop's PC advance, and the
  pending-interrupt PC correction. `MRET`/`WFI` are converted too even though there's no
  `C.MRET`/`C.WFI` — the point is that the code no longer depends on that absence to be correct.
- **`PostExecHook.ir`** now holds the internal 32-bit expansion for a compressed instruction, not
  the original 16 bits (checked first: nothing in this repo's tests or Javadoc depended on the
  original bits) — same value used for `mtval` on an illegal-instruction trap from a bad
  compressed encoding. Documented on `PostExecHook`'s Javadoc, `docs/API.md`, and `CHANGELOG.md`
  as the one user-visible behavioral note in this phase.
- New `CompressedInstructionTest` (34 tests): every instruction checked *differentially*
  against its hand-assembled 32-bit equivalent (deliberately not by re-deriving expected values
  from the same formulas `decodeCompressed` uses) — caught three real bugs in the test's own
  compressed-encoder helpers (missing quadrant bits on `C.BEQZ`/`C.BNEZ`/`C.LWSP`, and a missing
  shift-amount-overflow bit on `C.SRLI`/`C.SRAI`) before they could hide behind a shared mistake.
  Also covers reserved/illegal patterns in both directions, jump link values (`pc+2`, not
  `pc+4`), a compressed breakpoint's `mtval`, RAM-window-edge fetch faulting instead of
  overrunning the backing store, and mixed compressed/32-bit streams in both orderings. 400
  core + 1 cli tests.

---

## Phase 5a — F extension, rounding-mode-independent subset (`f50e0a8`) — done

Item 15, split in two: before starting, Nate asked whether doing F and D (double-precision)
together now would be cheaper than F now, D later. Estimate (not measured, see Design Decision
§8): D's non-RNE rounding doesn't inherit F's "compute in `double`, round once" shortcut, so the
hard parts of F and D don't share; not meaningfully cheaper together, so F proceeds alone. Three
forward-compatibility decisions were baked in anyway since they're free now and a breaking-API
retrofit later otherwise (`IsaConfig.hasD` misa-only flag now; `long`-backed NaN-boxed FP register
file; `MemoryBus` 64-bit access explicitly deferred, since FLW/FSW don't need it).

- **`IsaConfig.hasD`** added as a 7th record component (misa-only, bit 3), with `hasD ⇒ hasF`
  validated in the compact constructor. Two compatibility constructors (5-arg, 6-arg) preserve
  every existing call site unchanged.
- **`RV32IMAState.fregs`** (`long[32]`, not `float[32]`) and **`fcsr`** (`int`) added. Every FP
  write NaN-boxes (upper 32 bits set to all-ones); reads take the low 32 bits. f0 is an ordinary
  register, unlike `x0`.
- **`fflags`/`frm`/`fcsr` CSRs** (`0x001`/`0x002`/`0x003`) added to `readCsr`/`writeCsr`, gated on
  `hasF` — but only as a guard around these three cases, not a "CSR doesn't exist" trap; matches
  this core's existing behavior for every other unimplemented CSR (silent no-op/hook passthrough).
- **Decoded:** FLW/FSW (opcodes `0x07`/`0x27`); on `0x53` (OP-FP) — FSGNJ/FSGNJN/FSGNJX.S,
  FMIN/FMAX.S, FEQ/FLT/FLE.S, FMV.X.W/FCLASS.S, FMV.W.X. All rounding-mode-independent. FMIN/FMAX
  are hand-rolled (not `Math.min`/`Math.max`, which get NaN propagation wrong for RISC-V's
  semantics). `NV` is the only `fflags` bit that can arise here (signaling-NaN operands to a
  comparison or min/max); `DZ`/`OF`/`UF`/`NX` are all rounding-related and wait for 5b.
- **Not yet decoded at this point (Phase 5b, below):** FADD/FSUB/FMUL/FDIV/FSQRT.S, the FMADD
  family, FCVT.{W,WU}.S/FCVT.S.{W,WU}. The numerical approach sketched at the time (a native
  `double` intermediate rounded to `float` once) turned out to be unsafe for directed rounding
  modes and was corrected during Phase 5b's implementation — see below.
- New `FExtensionTest` (39 tests) plus 6 new `IsaConfigTest` cases for `hasD`. 446 core + 1 cli
  tests.

---

## Phase 5b — F extension, rounding-mode layer (`814bde6`) — done

Item 15's remaining half: FADD/FSUB/FMUL/FDIV/FSQRT.S, the FMADD/FMSUB/FNMSUB/FNMADD.S family
(their own top-level opcodes, not `OP-FP` `funct7` cases — the R4 format repurposes that field as
`{rs3, fmt}`), and FCVT.{W,WU}.S/FCVT.S.{W,WU}. **The F extension, and the whole Phase 1–5 staging
plan in `docs/FEATURE_REQUEST_PLAN.md`, is now complete.**

- **The Phase 5a numerical sketch was wrong, caught by advisor review before implementation.**
  Rounding the native `double` result of a Java double-precision operation to `float` a second
  time is *not* safe for directed rounding modes: the `double` result is itself already a rounded
  approximation of the true infinite-precision result, not the true value. Counterexample:
  `1.0f + (-2^-149f)` — the `double` sum is exactly `1.0`, but the true value
  `1.0 - 2^-149` is strictly below `1.0`, so round-toward-negative must produce
  `Math.nextDown(1.0f)`, not `1.0f`. A naive "cast the double result once" implementation would
  have silently gotten every directed-mode boundary case wrong.
- **The fix:** every arithmetic op computes a `(double approx, int residualSign)` pair — `approx`
  is the same correctly-rounded `double` as before, `residualSign` is the exact sign of the true
  result minus `approx`. This is exact and cheap per op: `FMUL` needs no computation at all (a
  float product needs at most 48 significant bits, well inside `double`'s 53); `FADD`/`FSUB` use
  Knuth's TwoSum on the two (losslessly widened) `double` operands; `FDIV`/`FSQRT` use a
  `Math.fma`-computed exact residual against the numerator. `roundToFloat` then derives the two
  candidate floats bracketing the true result from that pair and picks the one each of the five
  modes calls for — RTZ/RDN/RUP/RNE directly, RMM via one additional exact-midpoint tie check
  (valid because a float midpoint is always exactly representable in `double`). Overflow and
  subnormal boundaries fall out for free (`Math.nextUp(Float.MAX_VALUE)` is `+infinity`).
- **The FMA family needed no special-casing at all, contrary to the Phase 5a sketch's
  expectation.** The multiply term `a*b` is exact in `double` (as for `FMUL`), so folding the
  addend `c` in via the same TwoSum used for `FADD`/`FSUB` gives one correctly-rounded
  approximation of the whole fused expression directly — there's no double-rounding-unsafe
  intermediate step to avoid, because this implementation never rounds the product before adding
  `c`. `FMADD`/`FMSUB`/`FNMSUB`/`FNMADD.S` share one `fmaS` implementation, parameterized by which
  of `a`/`c` gets negated.
- **`FCVT.{W,WU}.S` rounds to an integer per `rm` first, then range-checks the rounded value** —
  not the pre-rounded one. This ordering is observable at boundaries: `FCVT.WU.S(-0.5)` is in
  range under RTZ (rounds to `-0`, `NX` set, `NV` clear) but out of range under RDN (rounds to
  `-1`, saturates to `0`, `NV` set, `NX` clear).
- Full `fflags` accrual: `NV`/`DZ` per-operation for special values (NaN, infinities, zero — e.g.
  `0/0` and `inf/inf` are invalid, not divide-by-zero; opposite-signed-infinity addition is
  invalid), `OF`/`UF`/`NX` generically from `roundToFloat`'s rounded magnitude and exactness.
  Exact-cancellation zero sign (`+0` in every mode except round-toward-negative, where it's `-0`)
  is handled explicitly for `FADD`/`FSUB`/`FMADD`-family, matching IEEE 754 rather than whatever
  sign Java's own default-rounding `double` zero arithmetic happens to produce.
- A reserved `rm` encoding (5, 6, or a dynamic selector when `frm` itself holds a reserved value)
  traps illegal-instruction before touching any register or flag; this check applies only to
  instructions with an actual `rm` field (the five arithmetic ops, the FMA family, all four FCVT
  forms) — Phase 5a's FSGNJ/FMIN/FMAX/FEQ/FCLASS/FMV use `funct3` as an opcode selector, not `rm`,
  and are untouched.
- New `FExtensionRoundingTest` (48 tests): basic wiring and NaN/infinity/zero special cases per
  op; the `1.0f + (-2^-149f)` RDN/RNE divergence from the advisor's counterexample; overflow and
  underflow flag/saturation behavior across rounding modes; the FCVT round-then-range-check
  ordering; reserved and dynamic rounding-mode traps; and a randomized differential suite for
  FADD/FSUB/FMUL/FDIV/FSQRT against `BigDecimal`-exact arithmetic, plus an FMA differential
  suite against `Math.fma` — both deliberately independent of this class's own TwoSum/`Math.fma`-
  residual formulas, per Phase 4's differential-testing discipline. One SpotBugs suppression
  added (`config/spotbugs-exclude.xml`) for the RMM tie-detection equality check, which is exact
  by construction. 494 core + 1 cli tests.

---

## C.FLW/C.FSW/C.FLWSP/C.FSWSP decode (`3a42dbf`) — done

A follow-up Phase 4 explicitly deferred ("would be legal once F is decoded, but F is not
implemented by this core yet — Phase 5's scope, not an oversight") and that got missed when
Phase 5b wrapped up last session. `decodeCompressed` now expands all four remaining
F-extension compressed slots into `FLW`/`FSW`, using the same immediate-decode helpers as the
structurally identical `C.LW`/`C.SW`/`C.LWSP`/`C.SWSP` integer forms.

- `decodeCompressed` itself stays `IsaConfig`-agnostic (as it already was for every other
  instruction): the generated 32-bit `FLW`/`FSW` re-enters the ordinary opcode switch, whose own
  `hasF` check traps illegal-instruction if `F` isn't enabled even when `C` is. No new gating
  logic needed.
- `C.FLWSP`'s `rd` field does **not** reserve `0`, unlike `C.LWSP` — `f0` is an ordinary FP
  register, not hardwired zero (a distinction already baked into Phase 5a's register-file
  design). Verified explicitly in the new test (`cFlwspMatchesFlwAndAllowsRdZero`).
- Corrected two existing test comments that turned out to be wrong: `C.FLD`/`C.FSD`/
  `C.FLDSP`/`C.FSDSP` (the D-extension quadrant-0/2 funct3 1/5 slots) are valid RV32DC
  encodings, not "not part of RV32 at all" as previously stated — they stay illegal because
  this core doesn't decode `D` at all (only `IsaConfig.hasD`'s `misa` bit exists), not because
  of an RV32-vs-RV64 distinction.
- New differential tests (`CompressedInstructionTest`) compare `fregs` (loads) or raw memory
  content (stores) between the compressed encoding and its hand-assembled 32-bit `FLW`/`FSW`
  equivalent, plus a gating test confirming `HAS_C`-only configs are unaffected. 498 core + 1
  cli tests.

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
