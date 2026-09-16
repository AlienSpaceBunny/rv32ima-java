# Agent Instructions: rv32emu (RV32IMA Java Emulator)

This file uses the general `AGENTS.md` name so any coding agent working in this
repository finds the same project guidance.

## Changelog

- **Every user-facing change gets an entry under `## [Unreleased]` in
  `CHANGELOG.md`**, in the same commit as the change — not as an afterthought.
  User-facing means: new or changed public API, behavior changes (including bug
  fixes), dependency/tooling version bumps, removed functionality. Use the
  [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) categories (`Added`,
  `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`).
- Purely internal housekeeping (doc rewording with no behavior change, comment
  fixes, CI-only tweaks) doesn't need an entry — use judgment, but default to
  adding one if unsure.
- At release time, `[Unreleased]` is renamed to the version being released (see
  `docs/RELEASING.md` step 2) and a fresh empty `[Unreleased]` is added above it.
  Don't pre-emptively create a version section yourself; that's the release
  procedure's job.

## Finishing a Task (ways of working)

When a task is done and committed cleanly (gate green, changelog entry in place), do all
of the following without waiting to be asked. Skip the whole list if the task ended with
failing tests, an unresolved question, or partial work — report that instead.

1. **Bump the version if the change was substantive** (code or public-API change, not
   docs-only or housekeeping): a plain `-SNAPSHOT` bump via
   `./mvnw versions:set -DnewVersion=X.Y.Z-SNAPSHOT -DgenerateBackupPoms=false`, plus the
   "currently `…-SNAPSHOT`" line in `docs/RELEASING.md`. Never produce a release version
   this way; release cuts stay with `maven-release-plugin` (`docs/RELEASING.md`) and remain
   on hold (`RELEASE_TODO.md`). One bump per task, not per commit.
2. **Install locally after the bump**: `./mvnw install` (add `-DskipTests` only when the
   full gate already passed on the same tree). The sibling emulator consumes the jar from
   `~/.m2`, so an unbumped or uninstalled change is invisible to it. No remote deploy.
3. **Archive what the task made obsolete**: move superseded docs, finished back-and-forth
   review/handoff documents, and temporary test or probe files that were supplied as task
   input (for example a standalone `*.java` probe outside the Maven source sets) to
   `docs/archive/` with `git mv`, add a row to the Archive table in `docs/README.md`, and fix
   any links. Keep in `docs/` only what a fresh session still needs to act on.
4. **Commit and push**: commit in logical units as usual, then `git push` to `origin`.
   Pushing is part of finishing cleanly, not a separate ask — but only when the gate is
   green and steps 1–3 are done.

## Build / Validation Gate

- Run `./mvnw clean verify` before finishing code changes, unless told to skip
  it. It runs Spotless, Checkstyle, SpotBugs, all tests, and (in `cli`) a
  packaged-jar smoke test against a baremetal RISC-V binary — all gates a
  human reviewer would otherwise have to run by hand.
- Run `./mvnw spotless:apply` to auto-fix formatting before committing; don't
  hand-format to match Palantir Java Format.
- `./release.sh` runs the same gates plus a final validation run against a
  freshly compiled baremetal test binary (requires `clang`/`make` in
  `baremetal/`). It does not version, tag, or publish anything.

## Architecture

- `core` (`rv32emu-core`): platform-agnostic emulator library. Must not depend
  on `cli`. Keep it that way — embedders (consoles, fantasy-console runtimes,
  test harnesses) depend on `core` alone.
- `cli` (`rv32emu-cli`): reference command-line runner, depends on `core`.
  Distributed as a fat jar (GitHub Releases), not published to Maven Central —
  see `RELEASE_TODO.md`.
- `RV32IMACore.step()`'s 8-parameter signature and long body are a **deliberate
  interpreter idiom**, not a cleanup target.
- Trap dispatch uses an internal "+1" encoding on the local `trap` variable
  (`trap == 0` means no trap). See the class-level Javadoc on `RV32IMACore` and
  `exceptionTrap()` before touching trap-cause logic.
- `docs/API.md` is a narrative summary for embedders; the Javadoc on
  `MemoryBus`, `HardwareHook`, `CSRHook`, `RV32IMACore`, and `RV32IMAState` is
  the authoritative spec where the two disagree.

## Release / Versioning

- `main` always carries a `-SNAPSHOT` version. Cutting a release is driven by
  `maven-release-plugin` — see `docs/RELEASING.md` for the full
  prepare/perform/rollback procedure. Don't hand-edit the version in the poms
  outside that procedure.
- Maven Central publishing is deliberately on hold — see `RELEASE_TODO.md`
  (R1–R4) for why and what's still open.

## Docs

- `docs/README.md` is the documentation index — add new docs there, and mark
  superseded ones as archived rather than deleting them outright.
- `CHECKPOINT.md` is the rolling status/resume note. Keep it current at the
  end of a work session so a fresh session (or a different agent) can resume
  without re-deriving context.
- `docs/FEATURE_REQUEST_PLAN.md` is the multi-hart (V-32 AP/IOP) feature plan,
  reviewed and conditionally signed off by the originating LLM
  (`docs/archive/PLAN_REVIEW_RESPONSE.md`). Mark items done in place (inline "done"
  notes with the landing commit) rather than deleting the original design text
  — it's also the spec.

## Tool Usage

- Prefer `rg` and dedicated search tools over ad hoc `grep`/`find` pipelines.
- `gh` may not be installed or authenticated in every environment; check
  before assuming it's available for PR/issue operations.
