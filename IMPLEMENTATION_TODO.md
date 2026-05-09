# Implementation TODO Before Public Release

This plan addresses the implementation issues found during review in priority order. Each item should be handled test-first where practical: add a failing test that demonstrates the bug or missing contract, confirm it fails for the expected reason, then fix the implementation and keep the test.

## P0: Prevent Host Crashes From Guest Memory Faults

- Add focused core tests for invalid guest data loads and stores:
  - `lw` from below RAM.
  - `sw` past the end of RAM.
  - invalid MMIO addresses when no hook claims the range.
- Assert that these cases enter the guest trap path instead of throwing `IndexOutOfBoundsException` or another Java exception.
- Fix `RV32IMACore` load/store handling so bus failures are converted into the correct load/store access-fault trap state.
  - Load access fault (cause 5) and store/AMO access fault (cause 7) require `mtval` to hold the faulting *address*, not the loaded value or PC. The current expression `(trap > 5 && trap <= 8) ? rval : pc` targets the right trap range but uses the wrong variable; update it to pass the faulting address through from the load/store decode paths when P0 traps are added.
- Add tests for valid boundary accesses at the first and last legal bytes of RAM to avoid overcorrecting the range checks.

## P1: Make the Published CLI Actually Runnable

- Add a packaging test that builds the CLI artifact and runs the documented command shape against the baremetal test binary.
- Confirm the current jar fails because it has no `Main-Class` manifest and does not package or otherwise resolve `rv32emu-core`.
- Fix CLI packaging with an executable jar strategy, either Maven Shade or an equivalent assembly setup.
- Update README commands and artifact names so they match the real project version and build output.
- Keep a release smoke test that runs the packaged CLI and asserts expected UART output or successful startup with a bounded instruction count.

## P2: Correct CSR Side Effects, Machine Trap State, and mtval Encoding

- Add tests for CSR read/write side-effect rules:
  - `CSRRW rd=x0` must not read the CSR.
  - `CSRRS rs1=x0` and `CSRRC rs1=x0` must not write the CSR.
  - immediate CSR variants must follow the same no-write behavior when the immediate is zero for set/clear forms.
- Add a small custom `CSRHook` test double that records reads and writes so these side effects are observable.
- Add trap/MRET tests that validate `mepc`, `mcause`, `mtval`, privilege bits, and key `mstatus` bits across `ECALL`, timer interrupt entry, and `MRET`.
- Fix CSR execution and trap entry/return logic to preserve unrelated `mstatus` bits and match RV32 machine-mode semantics expected by the supported platform.
- Fix `mtval` for illegal instruction traps (cause 2): RISC-V allows `mtval` to be zero for illegal instructions, but if populated it should hold the faulting instruction encoding (`ir`), not the PC. This project should populate it with `ir` for diagnostics and compliance coverage. The current trap handler sets `mtval = pc` for all non-load/store traps.
  - Note: load/store access fault `mtval` correctness is tracked under P0, since those traps are not yet reachable.

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
  - Discussion needed: `MMIOBus` currently ignores the boolean return value entirely — any write whose address falls in a hook's registered range goes to the hook and never reaches RAM, regardless of what the hook returns. However, `CLINTHook.handleWrite()` returns `false` for unrecognized addresses within the CLINT range (e.g., software interrupt register), implying the author expected fallthrough or at least some caller-visible signal. Before fixing, agree on the intended contract: (a) hooks own their entire registered range and `false` is purely informational, or (b) `false` means the hook declined and the write should fall through to RAM. The choice affects the public `HardwareHook` API and all existing hook implementations.
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
- Fix the timer comparison logic from `>` to `>=` while preserving the intentional `timerMatch != 0` startup guard, and keep tests for WFI wakeup behavior.

## P7: Fix LR/SC Reservation Tracking and Expand Atomic Coverage

- Add tests for `LR.W` and `SC.W` success, failure after address mismatch, and reservation clearing after store or `SC.W`.
- Add address-range tests covering the primary RAM base (0x80000000), high-bit aliases, and `SC.W` without a preceding `LR.W` to expose the current reservation encoding bugs before fixing them.
- Add tests for all supported AMO operations, including signed and unsigned min/max edge cases.
- Fix reservation tracking: the current implementation packs only the low 29 address bits into `extraflags` with `rs1 << 3`, so addresses with different high bits alias and there is no explicit valid/invalid reservation state. At 0x80000000 (the standard RAM base), the stored reservation bits are zero, so `SC.W` can incorrectly succeed without a preceding matching `LR.W`. The fix requires a dedicated `reservationAddr int` field plus a validity flag or sentinel value on `RV32IMAState`; the bit-packing approach cannot represent a full 32-bit RV32 address alongside the existing privilege and WFI bits.

## Testing Strategy Improvements, Prioritized

1. Add focused unit tests around every fixed issue above. These are the fastest feedback loop and should land with each implementation fix.
2. Add a release packaging smoke test for the CLI artifact. This directly protects the public getting-started path.
3. Add ISA compliance coverage using `riscv-tests` or `riscv-arch-test` for RV32I, RV32M, RV32A, CSR, trap, and privilege-relevant machine-mode cases.
4. Add differential tests against the original C `mini-rv32ima` or another trusted emulator. Compare PC, integer registers, CSRs, memory deltas, trap state, and UART output after bounded instruction counts.
5. Expand baremetal integration tests beyond the current hello-world binary to include timer interrupts, WFI, atomics, invalid memory access handling, and MMIO behavior.

## Release Readiness and Last Steps

- Document intentional deviations from the RISC-V spec that are carried over from the upstream mini-rv32ima C implementation:
  - WFI sets `mstatus.MIE = 1` before suspending. The spec treats WFI as a hint and does not require privilege-state changes; the emulator does this to ensure a timer interrupt can wake a waiting CPU. Document this in `RV32IMACore` so contributors do not "fix" it and break WFI wakeup behavior.
  - The timer interrupt is gated by `timerMatch != 0`. Setting `mtimecmp = 0` does not fire an interrupt immediately, contrary to the spec (`mtime >= mtimecmp` with both at zero). This prevents spurious interrupts at startup before the guest configures the timer. Document the behavior and its rationale.
- Write public API documentation for every contract that a system-level emulator consumer or hardware hook implementor must rely on:
  - `MemoryBus`: semantics of byte/short/int reads and writes; signed vs. unsigned return conventions; what happens on access to an unregistered MMIO address; endianness guarantee (once P5 is fixed).
  - `HardwareHook`: what address range the hook is responsible for; meaning of `handleWrite` return value (once P4 is resolved); whether reads for unhandled addresses within the registered range should return 0 or some other sentinel; thread-safety expectations.
  - `CSRHook`: when `handleRead` and `handleWrite` are called (after P2 fixes, only when side-effect rules allow); what `handleRead` should return for unrecognized CSR numbers; whether the hook is invoked for read-only or write-only CSRs.
  - `RV32IMACore.step()`: the meaning of `elapsedUs` and how it drives the timer; behavior of the `count` parameter at trap boundaries; what return values mean; ordering guarantees between trap handling and the post-exec hook.
  - `RV32IMAState`: which fields are stable public API vs. internal implementation details; LR/SC reservation semantics (once P7 is fixed); how `extraflags` privilege bits interact with machine-mode trap entry and MRET.
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
- Public API behavior is documented for memory faults, MMIO hooks, CSR hooks, timer behavior, and supported ISA scope. Documentation is sufficient for a downstream integrator to implement a correct `HardwareHook`, `CSRHook`, or alternative `MemoryBus` without reading the emulator source. Known intentional spec deviations (WFI/MIE, timer-at-zero) are explicitly called out with rationale.
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
