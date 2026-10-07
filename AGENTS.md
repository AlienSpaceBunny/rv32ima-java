# Agent Instructions: rv32emu

## Validation and changelog

- Run `./mvnw spotless:apply` before committing. For code changes, run
  `./mvnw clean verify` unless instructed to skip it; this covers formatting,
  Checkstyle, SpotBugs, tests, and the packaged-CLI smoke test. Shared tooling
  versions belong in `../alienspacebunny-build`.
- `./release.sh` adds validation with a freshly compiled baremetal binary
  (requires clang/lld and make). It does not version, tag, or publish.
- Public API, behavior, dependency/tooling changes and removals need a
  `CHANGELOG.md` entry under `[Unreleased]` in the same commit. Use Keep a
  Changelog categories; docs-only housekeeping and CI-only tweaks need no entry
  (add one if unsure). Create release sections only during the release procedure.

## Finishing a task

Complete these steps without another request when the task is complete and its
checks pass. If work is partial, tests fail, or a task question remains unresolved,
report that instead.

1. For substantive code/public-API changes, bump once per task using
   `./mvnw versions:set -DnewVersion=X.Y.Z-SNAPSHOT -DgenerateBackupPoms=false`
   and update the current-version line in `docs/RELEASING.md`. Docs-only work and
   housekeeping need no bump. Never hand-edit POM versions or create a release
   version this way.
2. After a bump, run `./mvnw install` for the sibling emulator's local dependency.
   Use `-DskipTests` only after the full gate passed on the same tree. No deploy.
3. Move superseded docs, completed review/handoff exchanges, and supplied temporary
   probes to `docs/archive/` with `git mv`; index them in `docs/README.md` and fix
   links. Keep current guidance in active docs.
4. Update `CHECKPOINT.md`, commit in logical units, and push to `origin` after
   validation and the applicable steps above.

## Architecture

- `core` must not depend on `cli`. The CLI depends on core and is distributed as
  a fat jar through GitHub Releases, not as a Central artifact.
- `RV32IMACore.step()`'s long body and eight-parameter signature are deliberate
  interpreter idioms. Trap dispatch uses cause-plus-one (`trap == 0` means none);
  read its class Javadoc and `exceptionTrap()` before changing trap logic.
- Javadoc on `MemoryBus`, `HardwareHook`, `CSRHook`, `RV32IMACore`, and
  `RV32IMAState` is authoritative; `docs/API.md` is a narrative summary.

## Releases and documentation

- `main` stays on `-SNAPSHOT`. Tagged releases use `maven-release-plugin` and
  `docs/RELEASING.md`. Publishing remains on hold (`RELEASE_TODO.md`); never run
  the manual release workflow without Nate's explicit release request.
  Push/PR CI is deliberately deferred.
- Maintain `docs/README.md` as the documentation index and `CHECKPOINT.md` as the
  current resume note. Preserve `docs/FEATURE_REQUEST_PLAN.md`'s design text and
  record completed items in place with their landing commits; it remains a spec.

## Tools and file access

- Prefer `rg` and dedicated search tools; check `gh` availability and authentication
  before use. For inspection use `rg --no-config` with supported flags and `--`,
  `sed --sandbox -n -- 'START,ENDp' FILE`, or `cat`/`head`/`tail`; use `jq` for JSON.
- Try operations in the sandbox first and prefer `apply_patch` for edits. Do not
  bypass restrictions with wrappers or broader approvals. For Maven logs, prefer
  `--log-file` to shell redirection.
