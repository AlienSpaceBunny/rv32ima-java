# Documentation Index

## Reference and development

| Document | Purpose |
|---|---|
| [API.md](API.md) | Public API contracts for embedders. Narrative summary; the Javadoc on the five core types is authoritative. |
| [TEST_PLAN.md](TEST_PLAN.md) | Current test coverage, audit of the original system scenarios, and remaining test candidates. |
| [RELEASING.md](RELEASING.md) | Local release and manual Central-publishing procedures. |
| [../RELEASE_TODO.md](../RELEASE_TODO.md) | Release hold, remaining decisions and preparation status. |
| [../CHANGELOG.md](../CHANGELOG.md) | Public API, behavior and tooling changes. |
| [FEATURE_REQUEST_PLAN.md](FEATURE_REQUEST_PLAN.md) | Completed V-32 CPU feature design/specification, with landing commits recorded in place. |
| [RV64_FEASIBILITY_NOTES.md](RV64_FEASIBILITY_NOTES.md) | Speculative notes on whether/how this library could add RV64 support alongside RV32. No decision made, no work started or scheduled. |

## Archive

Superseded documents, kept for context. Do not treat as current. Review text is
preserved; link targets are adjusted for the archive layout or the source repository.

| Document | Superseded by |
|---|---|
| [archive/CLEANUP_TODO.md](archive/CLEANUP_TODO.md) | Completed C1–C7 standards pass; retained as implementation history. |
| [archive/FEATURE_REQUEST.md](archive/FEATURE_REQUEST.md) | Original V-32 request; implemented by `FEATURE_REQUEST_PLAN.md`. |
| [archive/RELEASE_TODO_PRE_CLEANUP_2026-10-07.md](archive/RELEASE_TODO_PRE_CLEANUP_2026-10-07.md) | [Current release checklist](../RELEASE_TODO.md); preserves completed steps and superseded recommendations. |
| [archive/TEST_PLAN_PRE_CLEANUP_2026-10-07.md](archive/TEST_PLAN_PRE_CLEANUP_2026-10-07.md) | [Current coverage and test candidates](TEST_PLAN.md). |
| [archive/CHECKPOINT_PRE_COMPACTION_2026-10-06.md](archive/CHECKPOINT_PRE_COMPACTION_2026-10-06.md) | Historical checkpoint preserved during the 2026-10-06 compaction: completed phases, reviews, cleanup steps, and superseded resume instructions. Current state and pending decisions live in [../CHECKPOINT.md](../CHECKPOINT.md). |
| [archive/IMPLEMENTATION_TODO.md](archive/IMPLEMENTATION_TODO.md) | P0–P7 all shipped; see git history and `CHECKPOINT.md`. |
| [archive/REMAINING_WORK.md](archive/REMAINING_WORK.md) | Current release checklist and completed `archive/CLEANUP_TODO.md`. |
| [archive/PLAN_REVIEW_REQUEST.md](archive/PLAN_REVIEW_REQUEST.md) / [archive/PLAN_REVIEW_RESPONSE.md](archive/PLAN_REVIEW_RESPONSE.md) | Design review (r5 → r6 sign-off, B1–B7). Its outcomes are folded into the completed `FEATURE_REQUEST_PLAN.md`. |
| [archive/CPU_INTEGRATION_REVIEW.md](archive/CPU_INTEGRATION_REVIEW.md) | V-32's 2026-09-15 review of `0.1.3-SNAPSHOT`, kept verbatim as the input to `archive/CPU_INTEGRATION_RESPONSE.md`. |
| [archive/CPU_INTEGRATION_FOLLOWUP.md](archive/CPU_INTEGRATION_FOLLOWUP.md) | V-32's 2026-09-16 follow-up review of `0.1.4-SNAPSHOT` / `CPU_INTEGRATION_RESPONSE.md`, input to `archive/CPU_INTEGRATION_RESPONSE_2.md`. Its probe link points to the source repository. |
| [archive/CPU_INTEGRATION_RESPONSE.md](archive/CPU_INTEGRATION_RESPONSE.md) | Round 1: response to V-32's 2026-09-15 review — per-finding fixes (`0664be7`), the `MemoryBus.checkAccess` API decision for failing-SC permission checks, alignment/reservation-lifecycle policy. Exchange closed by V-32's acceptance below; the contracts it introduced are now in `API.md` and the Javadoc. |
| [archive/CPU_INTEGRATION_RESPONSE_2.md](archive/CPU_INTEGRATION_RESPONSE_2.md) | Round 2: answers the follow-up — reservation cleanup before alignment faults, the inert-bus-entry lifecycle policy, in-batch interrupt reevaluation after CSR writes/MRET (`0.1.5-SNAPSHOT`). Closed by the acceptance below. |
| [archive/CPU_INTEGRATION_ACCEPTANCE.md](archive/CPU_INTEGRATION_ACCEPTANCE.md) | V-32's 2026-09-16 acceptance of `0.1.5-SNAPSHOT`: both probes passed, its Gradle gate green (51 tests), no CPU-side requests outstanding, and completed bus/privilege/mailbox integration. Reproduction commands use V-32-repo paths; `0.1.15-SNAPSHOT` was a typo for `0.1.5`. |
| [archive/CoreFeatureProbe.java](archive/CoreFeatureProbe.java) | V-32's standalone probe from the round-1 review (outside the Maven source sets; run with `java -cp <core jar> CoreFeatureProbe.java`). Expected output against `0.1.4-SNAPSHOT` is in `archive/CPU_INTEGRATION_RESPONSE.md`. V-32's current copy (`../emulator/review/`, alongside its round-2 `CoreResponseProbe.java`) adds a `checkAccess` override and is not mirrored here. |

## Historical emulator documents

Superseded application-side notes were moved to the
[V-32 emulator repository](https://github.com/AlienSpaceBunny/v32-emulator)'s
`docs/archive/`, with distinct filenames to preserve its existing copies:

- [RV32EMU_EMULATOR_REPO_NOTES_2026-09-13.md](https://github.com/AlienSpaceBunny/v32-emulator/blob/371af30de51b3ce736c882a83fffdafa512b0c3d/docs/archive/RV32EMU_EMULATOR_REPO_NOTES_2026-09-13.md) — old application architecture snapshot.
- [RV32EMU_MULTI_HART_BUS_NOTES_2026-09-13.md](https://github.com/AlienSpaceBunny/v32-emulator/blob/371af30de51b3ce736c882a83fffdafa512b0c3d/docs/archive/RV32EMU_MULTI_HART_BUS_NOTES_2026-09-13.md) — original shared-RAM bus handoff.

The CPU review responses and acceptance remain here as API and release evidence.
