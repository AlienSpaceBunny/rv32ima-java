# Session Checkpoint — 2026-09-10

## Where We Are

All P0–P7 bugs fixed and tested. Release tooling (Spotless, Checkstyle, SpotBugs, Javadoc +
source jars) in place. Public API Javadoc **done** (commits `b8a1fa8`, `a6e5fa7`). Compliance
test layers 1 & 2 **done** (`3c9be5b`, `22c0e30`) — 259 core + 1 cli tests.

JUnit 6.1.3 upgrade, architectural review, and the C1–C7 cleanup pass are all done and
pushed. Release readiness is documented but deliberately paused (`RELEASE_TODO.md`) — no
Central publish until after multi-hart Phase 1–2. `docs/FEATURE_REQUEST_PLAN.md` r6 has
been reviewed and conditionally signed off by the originating LLM (`docs/PLAN_REVIEW_RESPONSE.md`);
the one condition (real MSIP/MEIP interrupt delivery, not just injection) is now done
(`5620de0`). Feature-plan implementation can proceed.

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
2. **Release readiness → `RELEASE_TODO.md`** (R1–R8). **Recommendation: hold** — Central
   artifacts are immutable and the public API is about to move under the feature-plan work
   below; no rush cost since namespace registration (`com.alienspacebunny`, also
   `us.n8l`) is already done. Revisit after Phase 1–2 land and an explicit API-freeze
   review happens. Decisions locked meanwhile: publish `rv32emu-core` only; CLI fat jar
   to GitHub Releases; JitPack covers any interim consumer. **Open: versioning scheme
   (R2)** — `0.1.0` is hardcoded, no SNAPSHOT, no tags.
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
   - Start with Phase 1 (`IsaConfig`, `hartId`, instruction-fetch-fault fix, `misa` from
     config) — independently committable, no multi-hart bus design needed yet.

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
- **Manual release process** — no automated semantic versioning.
- **License**: MIT forked from MIT; existing `LICENSE` is sufficient.
- **Commit each release-readiness / cleanup step separately** with a clear message.
- `step()`'s 8-arg signature and long body are a **deliberate interpreter idiom** — not a
  cleanup target.
