# Implementation TODO Before Public Release

This plan addresses the implementation issues found during review in priority order. Each item should be handled test-first where practical: add a failing test that demonstrates the bug or missing contract, confirm it fails for the expected reason, then fix the implementation and keep the test.

## P0: Prevent Host Crashes From Guest Memory Faults

- Add focused core tests for invalid guest data loads and stores:
  - `lw` from below RAM.
  - `sw` past the end of RAM.
  - invalid MMIO addresses when no hook claims the range.
- Assert that these cases enter the guest trap path instead of throwing `IndexOutOfBoundsException` or another Java exception.
- Fix `RV32IMACore` load/store handling so bus failures are converted into the correct load/store access-fault trap state.
- Add tests for valid boundary accesses at the first and last legal bytes of RAM to avoid overcorrecting the range checks.

## P1: Make the Published CLI Actually Runnable

- Add a packaging test that builds the CLI artifact and runs the documented command shape against the baremetal test binary.
- Confirm the current jar fails because it has no `Main-Class` manifest and does not package or otherwise resolve `rv32emu-core`.
- Fix CLI packaging with an executable jar strategy, either Maven Shade or an equivalent assembly setup.
- Update README commands and artifact names so they match the real project version and build output.
- Keep a release smoke test that runs the packaged CLI and asserts expected UART output or successful startup with a bounded instruction count.

## P2: Correct CSR Side Effects and Machine Trap State

- Add tests for CSR read/write side-effect rules:
  - `CSRRW rd=x0` must not read the CSR.
  - `CSRRS rs1=x0` and `CSRRC rs1=x0` must not write the CSR.
  - immediate CSR variants must follow the same no-write behavior when the immediate is zero for set/clear forms.
- Add a small custom `CSRHook` test double that records reads and writes so these side effects are observable.
- Add trap/MRET tests that validate `mepc`, `mcause`, `mtval`, privilege bits, and key `mstatus` bits across `ECALL`, timer interrupt entry, and `MRET`.
- Fix CSR execution and trap entry/return logic to preserve unrelated `mstatus` bits and match RV32 machine-mode semantics expected by the supported platform.

## P3: Reject Illegal Instruction Encodings

- Add instruction-decoding tests for illegal encodings that are currently accepted:
  - invalid OP/OP-IMM `funct7` combinations.
  - shift immediates with invalid high bits.
  - RV32M-looking instructions that only set bit 25 but do not have a valid M-extension encoding.
  - RV32A instructions with invalid `funct3` or unsupported operation fields.
- Assert that each case raises an illegal-instruction trap and does not commit a destination register write.
- Tighten decode validation before executing each instruction family.
- Add positive tests for the valid neighboring encodings so valid RV32I/M/A instructions continue to execute.

## P4: Clarify and Enforce MMIO Hook Semantics

- Add `MMIOBus` contract tests for hook matching, unsigned address ranges, overlapping ranges, read/write widths, and hook write return values.
- Decide and document whether `HardwareHook.handleWrite(...)=false` means fall through to RAM or means ignored after hook match.
- If fallthrough is the intended public API, fix `MMIOBus` so writes delegate to RAM when the hook returns `false`.
- If ignored writes are intended, update `HardwareHook` documentation and tests to remove the fallthrough contract.

## P5: Make Memory Endianness Explicit

- Add memory tests that write known bytes and read them back as `short` and `int`, and vice versa.
- Assert little-endian behavior independent of host platform.
- Update `FFMMemoryBus` to use explicit little-endian layouts or equivalent byte-order-safe access.
- Keep byte-level tests for signed and unsigned load behavior through the core.

## P6: Fix Timer Interrupt Boundary Behavior

- Add timer tests for `mtime < mtimecmp`, `mtime == mtimecmp`, and `mtime > mtimecmp`.
- Assert that MTIP is set when timer reaches the compare value, not one tick later.
- Fix the timer comparison logic and keep tests for WFI wakeup behavior.

## P7: Improve LR/SC and Atomic Coverage

- Add tests for `LR.W` and `SC.W` success, failure after address mismatch, and reservation clearing after store or `SC.W`.
- Add address-alias tests to prove high address bits do not collide in reservation tracking.
- Add tests for all supported AMO operations, including signed and unsigned min/max edge cases.
- Fix reservation tracking so it records the full RV32 address and follows the intended single-core memory model consistently.

## Testing Strategy Improvements, Prioritized

1. Add focused unit tests around every fixed issue above. These are the fastest feedback loop and should land with each implementation fix.
2. Add a release packaging smoke test for the CLI artifact. This directly protects the public getting-started path.
3. Add ISA compliance coverage using `riscv-tests` or `riscv-arch-test` for RV32I, RV32M, RV32A, CSR, trap, and privilege-relevant machine-mode cases.
4. Add differential tests against the original C `mini-rv32ima` or another trusted emulator. Compare PC, integer registers, CSRs, memory deltas, trap state, and UART output after bounded instruction counts.
5. Expand baremetal integration tests beyond the current hello-world binary to include timer interrupts, WFI, atomics, invalid memory access handling, and MMIO behavior.

## Release Readiness and Last Steps

- Add a code formatter after the functional fixes and coverage are in place.
- Apply the formatter once across the repository.
- Add a CI check that fails on unformatted Java code.
- Iteratively assess Checkstyle issues after the formatter run:
  - Fix issues that indicate real maintainability, API, documentation, or correctness problems.
  - Suppress only issues that are intentionally accepted, with narrow suppressions and a short reason.
- Add SpotBugs after formatter and Checkstyle cleanup:
  - Fix correctness, resource, concurrency, signedness, and API misuse findings.
  - Suppress only false positives or accepted risks, with narrow suppressions and a short reason.
- Add a release-process document that walks through releasing with GitHub Actions:
  - Required branch, tag, and versioning workflow.
  - CI gates that must pass before release.
  - Artifact build and publication steps.
  - GitHub release creation and generated/curated release notes.
  - Post-release validation using the published artifacts and documented CLI command.

## Definition of Done

- All P0-P7 implementation issues are covered by failing-first tests and fixed.
- Unit, integration, packaged CLI smoke, ISA compliance, and selected differential tests pass in CI.
- Public README commands match the packaged artifacts and are exercised by CI.
- Public API behavior is documented for memory faults, MMIO hooks, CSR hooks, timer behavior, and supported ISA scope.
- Release artifacts include usable source and binary jars with correct metadata, license, and attribution.
- Formatter, Checkstyle, and SpotBugs run in CI with no unsuppressed violations.
- GitHub Actions release workflow is documented, repeatable, and validated on a dry run or prerelease.
- Initial internal release path is supported before public publication, with the public Maven Central path documented as future-facing until signing and publication automation are implemented.
- Maven coordinates are finalized before public release. Sonatype namespace `com.alienspacebunny` is verified and matches the current project coordinates, so no group ID migration is needed.
- Maven Central build requirements are implemented before public publication, including source jars, Javadoc jars, artifact signing, staging/publishing credentials, and CI-safe secret handling.
- Supported Java version and OS expectations are documented, including Java 25 and any implications of the Foreign Function & Memory API.
- License and provenance are documented for the Java port, upstream `mini-rv32ima`, and bundled or generated baremetal artifacts.
- `baremetal.bin` is reproducibly generated from checked-in source, or the repository documents why the checked-in binary is trusted and how it was produced.
- A basic performance baseline exists for representative instruction execution and CLI baremetal execution, so future changes can detect unacceptable regressions.
