# Release Readiness

Publishing remains **on hold**. The public API is unfrozen; the first tagged release
target is **`0.2.0`**. Development versions remain `-SNAPSHOT`.

## Remaining before release

1. Confirm that V-32's [2026-09-16 acceptance](docs/archive/CPU_INTEGRATION_ACCEPTANCE.md)
   satisfies the integration condition set on 2026-09-13. V-32 reports completed
   AP/IOP integration, passing probes and integration tests, and no outstanding
   CPU-project requests. The decision to lift the hold remains with Nate.
2. Review the public API against that real consumer before freezing it. Completion
   of the CPU feature plan alone does not authorize a freeze or release.
3. Obtain Nate's explicit release request, then follow
   [RELEASING.md](docs/RELEASING.md). The manual workflow is ready but has not been
   exercised end to end for rv32emu. The shared parent build has already used the
   same Central profile successfully.
4. After publication, resolve `rv32emu-core` from Central using an empty local
   Maven repository and run a consumer smoke test. A local `install` does not
   verify the published artifact.

## Preparation status

The original R1–R8 identifiers are retained for references in earlier documents.

| Item | Status | Evidence / current behavior |
|---|---|---|
| R1 — Portal account and namespace | Done | `com.alienspacebunny` registered; Portal credentials configured as organization secrets. |
| R2 — Signing key | Done | Release signing key and passphrase configured as organization secrets. |
| R3 — POM metadata | Done | Parent has project URL, license, developer and SCM metadata; both modules have names and descriptions. |
| R4 — Central deployment wiring | Implemented; rv32emu validation pending | Inherited `central-release` profile; CLI excluded from the Central bundle. |
| R5 — Versioning | Done | `maven-release-plugin` prepares `vX.Y.Z` tags and the next snapshot. Local prepare does not push. |
| R6 — Release-build validation | Done | `release.sh` runs `clean verify` and a freshly compiled baremetal binary; it does not publish. |
| R7 — Release workflow | Done | Manual workflow prepares, uploads to Central, and creates a draft GitHub Release. Push/PR CI is deliberately deferred. |
| R8 — Release procedure | Documented; published-artifact check pending | Local and Central procedures are in `docs/RELEASING.md`; the post-publication smoke test remains open. |

## Distribution decisions

- Maven coordinates use `com.alienspacebunny`. Central receives `rv32emu-core`
  and its parent POM; the CLI fat jar is distributed through GitHub Releases.
- Interim consumers can build and install snapshots locally.
- `0.1.0` was an informal downstream reference, not a tagged release here.
  `0.1.1` was an earlier release target, superseded by `0.2.0`.
- `CHANGELOG.md` uses Keep a Changelog categories. Finalize the release section
  before running the release procedure.
- `LICENSE` retains the upstream `Copyright (c) 2022 CNLohr` attribution. Adding
  a separate attribution for the Java port remains an optional maintainer decision.

The [previous checklist](docs/archive/RELEASE_TODO_PRE_CLEANUP_2026-10-07.md)
preserves completed steps and superseded recommendations.
