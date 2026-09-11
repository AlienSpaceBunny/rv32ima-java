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
- `AccessContext` and `AccessKind` (multi-hart Phase 2): every `MemoryBus` read/write
  method gained a context-bearing overload (hart id, privilege, access kind, width,
  atomic op), which `RV32IMACore` now calls for every fetch, load, store, and AMO.
  Existing `MemoryBus` implementations are unaffected — the context-bearing overloads
  default to delegating to the no-context ones. `MMIOBus` does not forward
  `AccessContext` to `HardwareHook`; see its updated Javadoc.
- `RV32IMAState.hartId`, identifying a hart among others sharing a `MemoryBus`.
  Defaults to `0`; not yet read or written by the core itself.
- `MemoryBus.atomicRmw` and `MemoryBus.tryScAndStore` (multi-hart Phase 2):
  `RV32IMACore`'s AMO block now routes every RV32A atomic through these two default
  methods instead of computing results inline. `LR.W` continues to route through the
  existing `readInt(address, ctx)` overload. The default implementations are correct
  only for a single hart; a multi-hart-aware bus overrides them to hold a per-granule
  lock and to have the final say on whether an `SC.W` the core's local reservation
  check believed would succeed actually does. See `MemoryBus`'s and `docs/API.md`'s
  updated Javadoc, and `AtomicPrimitivesTest`.
- Zba, Zbb, and Zabha instruction decode (multi-hart Phase 3): `IsaConfig.hasZba` unlocks
  `SH1ADD`/`SH2ADD`/`SH3ADD`; `IsaConfig.hasZbb` unlocks all 18 basic bit-manipulation
  instructions (`CLZ`, `CTZ`, `CPOP`, `SEXT.B`, `SEXT.H`, `ZEXT.H`, `MIN`, `MINU`, `MAX`,
  `MAXU`, `ANDN`, `ORN`, `XNOR`, `ROL`, `ROR`, `RORI`, `ORC.B`, `REV8`); `IsaConfig.hasZabha`
  unlocks byte/halfword AMOs (the RV32A opcode's `funct3` field admitting `0`/`1` in addition
  to `2` for the nine read-modify-write ops — `LR.W`/`SC.W` remain word-only). `misa` is
  unaffected either way; these are `Z`-prefixed sub-extensions with no bit of their own.
  `MemoryBus.atomicRmw`'s default implementation is now width-aware via `ctx.width()`,
  sign-extending the loaded value and truncating the operand to the AMO's width before
  delegating to the unchanged, still-`int`-based `computeAmo`. See `docs/API.md`, the
  `IsaConfig`/`MemoryBus`/`RV32IMACore` Javadoc, and the new `RV32IComplianceTest`
  Zba/Zbb sections and `ZabhaTest`.
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
