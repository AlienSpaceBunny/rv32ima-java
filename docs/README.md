# Documentation Index

## Active

| Document | Purpose |
|---|---|
| [API.md](API.md) | Public API contracts for embedders. Narrative summary; the Javadoc on the five core types is authoritative. |
| [FEATURE_REQUEST.md](FEATURE_REQUEST.md) | Original feature request: V-32 AP/IOP support (multi-hart, extra ISA extensions). |
| [FEATURE_REQUEST_PLAN.md](FEATURE_REQUEST_PLAN.md) | Implementation plan for the feature request. Staged P1–P3 lanes. At r6; conditionally signed off, implementation underway. |
| [PLAN_REVIEW_REQUEST.md](PLAN_REVIEW_REQUEST.md) | Questions sent to the originating LLM to sign off FEATURE_REQUEST_PLAN.md r5. |
| [PLAN_REVIEW_RESPONSE.md](PLAN_REVIEW_RESPONSE.md) | The originating LLM's answers (B1–B7); basis for r6 and the interrupt-gating fix. |
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
