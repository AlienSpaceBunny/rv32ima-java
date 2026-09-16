# Documentation Index

## Active

| Document | Purpose |
|---|---|
| [API.md](API.md) | Public API contracts for embedders. Narrative summary; the Javadoc on the five core types is authoritative. |
| [FEATURE_REQUEST.md](FEATURE_REQUEST.md) | Original feature request: V-32 AP/IOP support (multi-hart, extra ISA extensions). |
| [FEATURE_REQUEST_PLAN.md](FEATURE_REQUEST_PLAN.md) | Implementation plan for the feature request. Staged P1–P3 lanes. At r6; conditionally signed off, implementation underway. |
| [TEST_PLAN.md](TEST_PLAN.md) | Test-suite expansion plan. Layers 1 & 2 done; Layer 3 (system-level scenarios) not started. |
| [RELEASING.md](RELEASING.md) | Local versioning/tagging/build procedure (`maven-release-plugin`). Central publishing is a separate, on-hold step — see `../RELEASE_TODO.md`. |
| [EMULATOR_REPO_NOTES.md](EMULATOR_REPO_NOTES.md) | Findings from directly reading the sibling emulator (V-32) repo's source — its architecture, threading model, and multi-hart `MemoryBus` gaps. A dated snapshot, not a live view; check before relying on specifics. |
| [RV64_FEASIBILITY_NOTES.md](RV64_FEASIBILITY_NOTES.md) | Speculative notes on whether/how this library could add RV64 support alongside RV32. No decision made, no work started or scheduled. |

Rolling status lives in [`../CHECKPOINT.md`](../CHECKPOINT.md). `../CLEANUP_TODO.md` is
closed out (see its status table); `../RELEASE_TODO.md` tracks the current release-readiness
work. `../CHANGELOG.md` tracks notable changes per version (Keep a Changelog format);
`../AGENTS.md` has the maintenance rule for it, plus other agent-facing project conventions.

## Archive

Superseded documents, kept for context. Do not treat as current.

| Document | Superseded by |
|---|---|
| [archive/IMPLEMENTATION_TODO.md](archive/IMPLEMENTATION_TODO.md) | P0–P7 all shipped; see git history and `CHECKPOINT.md`. |
| [archive/REMAINING_WORK.md](archive/REMAINING_WORK.md) | `CHECKPOINT.md` (release-readiness status) and `CLEANUP_TODO.md`. |
| [archive/PLAN_REVIEW_REQUEST.md](archive/PLAN_REVIEW_REQUEST.md) / [archive/PLAN_REVIEW_RESPONSE.md](archive/PLAN_REVIEW_RESPONSE.md) | Plan-review exchange with the originating LLM (r5 → r6 sign-off, B1–B7). Its outcomes are folded into `FEATURE_REQUEST_PLAN.md` r6, which is fully implemented. |
| [archive/MULTI_HART_BUS_NOTES.md](archive/MULTI_HART_BUS_NOTES.md) | 2026-09-13 multi-hart bus handoff to V-32, kept verbatim. Superseded by V-32's review below and by `archive/CPU_INTEGRATION_RESPONSE.md`. |
| [archive/CPU_INTEGRATION_REVIEW.md](archive/CPU_INTEGRATION_REVIEW.md) | V-32's 2026-09-15 review of `0.1.3-SNAPSHOT`, kept verbatim as the input to `archive/CPU_INTEGRATION_RESPONSE.md`. |
| [archive/CPU_INTEGRATION_FOLLOWUP.md](archive/CPU_INTEGRATION_FOLLOWUP.md) | V-32's 2026-09-16 follow-up review of `0.1.4-SNAPSHOT` / `CPU_INTEGRATION_RESPONSE.md`, kept verbatim as the input to `archive/CPU_INTEGRATION_RESPONSE_2.md`. |
| [archive/CPU_INTEGRATION_RESPONSE.md](archive/CPU_INTEGRATION_RESPONSE.md) | Round 1: response to V-32's 2026-09-15 review — per-finding fixes (`0664be7`), the `MemoryBus.checkAccess` API decision for failing-SC permission checks, alignment/reservation-lifecycle policy. Exchange closed by V-32's acceptance below; the contracts it introduced are now in `API.md` and the Javadoc. |
| [archive/CPU_INTEGRATION_RESPONSE_2.md](archive/CPU_INTEGRATION_RESPONSE_2.md) | Round 2: answers the follow-up — reservation cleanup before alignment faults, the inert-bus-entry lifecycle policy, in-batch interrupt reevaluation after CSR writes/MRET (`0.1.5-SNAPSHOT`). Closed by the acceptance below. |
| [archive/CPU_INTEGRATION_ACCEPTANCE.md](archive/CPU_INTEGRATION_ACCEPTANCE.md) | V-32's 2026-09-16 acceptance of `0.1.5-SNAPSHOT`, kept verbatim: both probes rerun and passing, its Gradle gate green (51 tests), no CPU-side requests outstanding, and a summary of its completed bus/privilege/mailbox integration. Its reproduce block uses V-32-repo paths (`review/*.java`, `V32_ARCHITECTURE.md`, i.e. `../emulator/`); the `0.1.15-SNAPSHOT` it mentions was a typo for `0.1.5`. |
| [archive/CoreFeatureProbe.java](archive/CoreFeatureProbe.java) | V-32's standalone probe from the round-1 review (outside the Maven source sets; run with `java -cp <core jar> CoreFeatureProbe.java`). Expected output against `0.1.4-SNAPSHOT` is in `archive/CPU_INTEGRATION_RESPONSE.md`. V-32's current copy (`../emulator/review/`, alongside its round-2 `CoreResponseProbe.java`) adds a `checkAccess` override and is not mirrored here. |
