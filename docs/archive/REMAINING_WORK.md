> **ARCHIVED (2026-09-10).** P0–P7 are done; release tooling and Public API Javadoc are done.
> Superseded by `../../CHECKPOINT.md` and `../../CLEANUP_TODO.md`. The "Deferred / Lower
> Priority" list at the bottom is still a useful backlog reference.

# Remaining Work Before Public Release

## Status Summary

| Priority | Description | Status |
|---|---|---|
| P0 | Prevent host crashes from guest memory faults | **Done** |
| P1 | Make the CLI runnable | **Done** — packaged-jar smoke test in `cli/pom.xml` (antrun) |
| P2 | CSR side effects, trap state, mtval | **Done** |
| P3 | Reject illegal instruction encodings | **Done** |
| P4 | Clarify and enforce MMIO hook semantics | **Done** |
| P5 | Make memory endianness explicit | **Done** |
| P6 | Fix timer interrupt boundary behavior | **Done** |
| P7 | Fix LR/SC reservation tracking | **Done** |
| — | Release readiness (docs, tooling, CI) | In progress |

---

## Release Readiness

### Done
- **Spotless formatter** — Palantir Java Format 2.90.0 via `spotless-maven-plugin` 2.44.5.
  `mvn spotless:apply` reformats; `mvn verify` fails if formatting is dirty (bound to `verify`
  phase). **CI:** GitHub Actions should run `mvn verify` — Spotless check runs automatically.
- **Packaged-jar CLI smoke test** — Already in `cli/pom.xml` via `maven-antrun-plugin`;
  builds the shade jar, runs it against `baremetal.bin`, asserts UART output.
- **baremetal.bin reproducibility** — Documented in `baremetal/README.md`; built from source
  via `make clean && make` with clang/lld.

### Next: Checkstyle
- Replace the existing `google_checks.xml` placeholder in `pom.xml` with a project-specific
  Checkstyle config.
- **Must include `AvoidStarImport`** to ban wildcard imports (`import foo.*`). This is the
  chosen enforcement point for the wildcard-import preference (Spotless formats but does not
  ban them).
- Fix any real issues; suppress only with narrow, justified `@SuppressWarnings` or inline
  suppression comments.
- Bind `checkstyle:check` to `verify` alongside Spotless.

### SpotBugs
- Add `spotbugs-maven-plugin` after Checkstyle is clean.
- Fix correctness, resource, concurrency, and signedness findings.
- Suppress only false positives or accepted risks, with a reason comment.

### Public API Javadoc
- `MemoryBus` — byte/short/int semantics, signed vs. unsigned returns, endianness, fault
  contract, thread-safety expectations.
- `HardwareHook` — address range ownership, unhandled-read sentinel, thread-safety.
- `CSRHook` — when `handleRead`/`handleWrite` are called (after P2 side-effect fixes),
  unrecognised CSR behaviour, read-only/write-only CSR handling.
- `RV32IMACore.step()` — `elapsedUs` and timer semantics, `count` at trap boundaries,
  return-value contract, post-exec hook ordering.
- `RV32IMAState` — stable public API vs. internal fields; reservation fields; `extraflags`
  privilege bits.
- Document intentional spec deviations in `RV32IMACore` Javadoc:
  - WFI sets `mstatus.MIE = 1` before suspending (ensures timer interrupt can wake CPU).
  - Timer interrupt gated by `timerMatch != 0` at startup (prevents spurious interrupts).

### Version Bumping and Release Process (Manual)
- Document the manual release steps: branch, version bump in pom.xml, tag, CI gates, artifact
  publication, GitHub release creation, post-release validation.
- Add Maven source and Javadoc jar generation (required for Maven Central).
- Add artifact signing with GPG (required for Maven Central).
- Document Sonatype namespace (`com.alienspacebunny`) and staging/publishing steps.

### Deferred / Lower Priority
- ISA compliance tests (riscv-tests or riscv-arch-test for RV32I/M/A/CSR)
- Differential tests against C mini-rv32ima
- Baremetal integration tests beyond hello-world (timer, WFI, atomics, invalid access)
- Performance baseline
- License: project is MIT, forked from MIT (mini-rv32ima). No additional action required
  beyond confirming the existing LICENSE file covers the port and bundled baremetal artifacts.
