# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/). See `docs/RELEASING.md`
for how this file is updated as part of cutting a release.

## [Unreleased]

### Added
- `RV32IMACore.injectInterrupt(state, bit)` and machine software/external interrupt
  (MSIP/MEIP) dispatch, alongside the existing core-managed timer interrupt (MTIP).
- `maven-release-plugin`, wired for local versioning and git tagging (`vX.Y.Z`); see
  `docs/RELEASING.md`.
- Standard POM project metadata (`name`, `description`, `url`, `licenses`, `developers`,
  `scm`) on all three modules.
- `docs/RELEASING.md`, `RELEASE_TODO.md`, `PLAN_REVIEW_REQUEST.md` /
  `PLAN_REVIEW_RESPONSE.md`, and `docs/README.md` documentation index.

### Changed
- Upgraded JUnit from 5.10.0 to 6.1.3 via the `junit-bom`.
- Fixed a latent interrupt-gating defect: `mstatus.MIE` no longer masks machine
  interrupts while executing in user mode (it should only mask them while already in
  machine mode, per the RISC-V privileged spec). Dormant until now — nothing previously
  ran the core below machine mode.
- Reorganized `docs/`: active planning docs consolidated under `docs/`, superseded docs
  moved to `docs/archive/`.
- Added Checkstyle naming-convention rules; normalized `RV32IMACore`'s mixed
  snake_case/camelCase locals to camelCase; named trap-cause and status-bit constants
  that were previously bare integer literals.
- `main` now carries a `-SNAPSHOT` version (`0.1.1-SNAPSHOT`); previously every commit
  claimed a bare `0.1.0` with no tags or release history.

### Removed
- Dead `MemoryBus.readIntSigned` default method (never called; `LW` uses `readInt`
  directly).

## [0.1.0] - unreleased

Not tagged in this repository. Referenced informally by the downstream V-32 project
before this changelog existed; the first tagged release from this repo will be `0.1.1`.
