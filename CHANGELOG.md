# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/). See `docs/RELEASING.md`
for how this file is updated as part of cutting a release.

## [Unreleased]

### Added
- `IsaConfig`, an immutable RISC-V extension configuration (`hasC`, `hasF`, `hasZba`,
  `hasZbb`, `hasZabha`) accepted by a new `RV32IMACore(IsaConfig)` constructor. The
  `misa` CSR is now derived from it instead of a hardcoded constant; the zero-argument
  constructor is unaffected (`IsaConfig.RV32IMA_ZICSR`, identical `misa` value).
  Optional-extension instructions are not decoded yet — only `misa` reflects the
  configuration so far (multi-hart Phase 1 foundation work).
- `IsaConfig.hasU`: picks whether `misa()` reports the standard U-mode bit (20) or
  reproduces the previously-hardcoded value's non-standard bit 22. Defaults to `false`
  (bit 22, matching the original mini-rv32ima-derived value exactly) via a 5-argument
  compatibility constructor; the V-32 presets set it `true`.
- U-mode CSR access privilege check (multi-hart Phase 2): a CSR access now raises an
  illegal-instruction trap, instead of succeeding, when the hart's current privilege is
  below the CSR address's minimum-privilege field (bits 9-8 of the CSR number, the
  standard RISC-V encoding). Applies uniformly to hook-routed custom CSR numbers too —
  see `CSRHook`'s updated Javadoc if you have a custom CSR meant to be reachable from
  user-mode guest code.
- `RV32IMAState.hartId`, identifying a hart among others sharing a `MemoryBus`.
  Defaults to `0`; not yet read or written by the core itself.
- Instruction fetch now catches `IndexOutOfBoundsException` from the memory bus (not
  just the coarse `ramOffset`/`ramSize` window) and converts it to an instruction
  access-fault trap, matching how data loads/stores already behave.
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
