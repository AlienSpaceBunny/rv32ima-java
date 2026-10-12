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
| [CPU_ARCHITECTURE_REVIEW_RESPONSE.md](CPU_ARCHITECTURE_REVIEW_RESPONSE.md) | Response to V-32's 2026-10-10 CPU architecture/native-performance review: assessment, ranked directions, recommended next step. Active until V-32 acknowledges it. |
| [performance/2026-10-10/README.md](performance/2026-10-10/README.md) | JVM vs Native Image measurements behind the review: method, provenance, baseline decomposition, landed and rejected candidates. |
| [PREDECODE_EXPERIMENT_PLAN.md](PREDECODE_EXPERIMENT_PLAN.md) | Complete: bounded upper-bound experiment for decode-once execution. Its decision rule's top band was met (1.46–2.51× native on CoreMark); it lists the requirements a real design would face. |
| [DECODED_ICACHE_PLAN.md](DECODED_ICACHE_PLAN.md) | Complete: merged as `0.1.8-SNAPSHOT`. Agreed design and ordered next steps for the opt-in decoded instruction cache: API, structure, validation, measurement, V-32 trial build. |
| [performance/2026-10-10/decoded-cache/README.md](performance/2026-10-10/decoded-cache/README.md) | Decoded-cache measurements: default-off cost, sizing at 256–16K slots against `main` and the tier-2 ceiling, counters, and the Native Image unswitching finding. |
| [TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md](TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md) | **Current work.** Ordered steps (T1–T7, then A1–A4) implementing the two designs below, with a status table recording landing commits. |
| [TIMER_COUNTER_DESIGN.md](TIMER_COUNTER_DESIGN.md) | **Implemented spec** (phase T, revision 6, confirmed by V-32): one shared time source replacing `elapsedUs`, standard compare and WFI, WARL `mie`/`mip`, always-on Zicntr with separate work and architectural counters, device-level timer masking on V-32's side. Landing commits in its Status section; its review history is archived. |
| [EXECUTION_API_DESIGN.md](EXECUTION_API_DESIGN.md) | **Implemented spec** (phase A, revision 6, confirmed and adopted by V-32): `stepAttempts` with committed per-attempt callbacks and one time sample per call; with the cache on, a separate cached attempt loop. Landing commits and A3's results in the file; its review history is archived. |
| [performance/2026-10-12/timer-counters/README.md](performance/2026-10-12/timer-counters/README.md) | Timer phase against `0.1.8` on JVM and native: unchanged batched native work, slower `step(count=1)` and HotSpot uncached, unswitching check. |
| [performance/2026-10-12/attempts-a2/README.md](performance/2026-10-12/attempts-a2/README.md) | Step A2's cached attempt path: why it is a separate loop (a fused mode slowed native batches 7–14%), with the variants screened. |
| [performance/2026-10-12/attempts/README.md](performance/2026-10-12/attempts/README.md) | Step A3: `stepAttempts` against V-32's former `step(count=1)` loop, cache off and on, budgets 64–65,536, allocation; against the timer version and `0.1.8` on JVM and native. |
| [V32_PACKET_REVIEW_2026-10-12.md](V32_PACKET_REVIEW_2026-10-12.md) | V-32's review of its device packets 001–003 against the two CPU designs, with core-side notes appended. Context only: no core work requested. Its links are relative to V-32's `docs/packets/`. |
| [BACKLOG.md](BACKLOG.md) | Blocked, waiting and deferred work: items waiting on Nate or V-32, the remaining-shortcuts audit, D, archiving. |
| [V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md](V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md) | **Current.** V-32's trial result and merge recommendation, plus its requirements for a batched execution API with per-attempt boundaries. Input to the execution API design. |
| [V32_AP_STEPPING_RESPONSE.md](V32_AP_STEPPING_RESPONSE.md) | V-32's answer on AP stepping: `step(count=1)` is its current path (cache off there on HotSpot is an acceptable fallback); committed per-instruction boundaries could live inside a batched call; pacing belongs to the embedder; the core API is unstable and changes are coordinated between the repos. |
| [DECODED_ICACHE_PROTOCOL.md](DECODED_ICACHE_PROTOCOL.md) | Cache coherence protocol (`FENCE.I` plus embedder flush) and V-32 case mapping, accepted by V-32. |
| [../bench/README.md](../bench/README.md) | Unpublished throughput harness: workloads, bench-only buses, native images, how to run the matrix. |
| [RV64_FEASIBILITY_NOTES.md](RV64_FEASIBILITY_NOTES.md) | Speculative notes on whether/how this library could add RV64 support alongside RV32. No decision made, no work started or scheduled. |

## Archive

Superseded documents, kept for context. Do not treat as current. Review text is
preserved; link targets are adjusted for the archive layout or the source repository.

| Document | Superseded by |
|---|---|
| [archive/EXECUTION_API_DESIGN_REV6_WITH_REVIEWS_2026-10-12.md](archive/EXECUTION_API_DESIGN_REV6_WITH_REVIEWS_2026-10-12.md) | The [implemented execution design](EXECUTION_API_DESIGN.md); keeps V-32's response and reviews of every revision and the per-revision change lists. |
| [archive/CPU_REVIEW_FOLLOWUP_RESPONSE.md](archive/CPU_REVIEW_FOLLOWUP_RESPONSE.md) | Completed: CPU agent's reply to V-32's cache review and boot follow-up (agreements, Nate's decisions, boot-result analysis, an addendum on V-32's former AP loop). Its outcomes are the [cache plan](DECODED_ICACHE_PLAN.md) and [protocol](DECODED_ICACHE_PROTOCOL.md). |
| [archive/DECODED_ICACHE_ADVISORY_REVIEW.md](archive/DECODED_ICACHE_ADVISORY_REVIEW.md) | V-32's advisory review of the cache scope (copied at `77b2e47`), answered by the reply above. |
| [archive/CPU_ARCHITECTURE_REVIEW_FOLLOWUP.md](archive/CPU_ARCHITECTURE_REVIEW_FOLLOWUP.md) | V-32's follow-up (copied at `77b2e47`): matched boot results and code-publication cases, answered by the reply above. |
| [archive/V32_ATTEMPTS_RATIFICATION.md](archive/V32_ATTEMPTS_RATIFICATION.md) | Completed exchange: the CPU agent asked which execution mechanism the AP will use, with trial build `0.1.10-attempts-SNAPSHOT`. V-32's reply, appended verbatim: `stepAttempts` with a 4096-slot cache as the AP's only mechanism, the separate cached attempt loop ratified, and the timer migration done (278 V-32 tests passing). Outcome recorded in the [implementation plan](TIMER_AND_ATTEMPTS_IMPLEMENTATION_PLAN.md). |
| [archive/V32_TIMER_PHASE_HANDOFF.md](archive/V32_TIMER_PHASE_HANDOFF.md) | Completed handoff of the timer phase (`0.1.10-SNAPSHOT`): breaking changes and V-32 migration sites. V-32 migrated; its report is in the ratification reply above. |
| [archive/TIMER_COUNTER_DESIGN_REV6_WITH_REVIEWS_2026-10-12.md](archive/TIMER_COUNTER_DESIGN_REV6_WITH_REVIEWS_2026-10-12.md) | The [implemented timer/counter design](TIMER_COUNTER_DESIGN.md); keeps V-32's reviews of every revision and the per-revision change lists. |
| [archive/V32_DECODED_CACHE_TRIAL.md](archive/V32_DECODED_CACHE_TRIAL.md) | Completed trial handoff for `0.1.8-icache-SNAPSHOT`; V-32's result is in [V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md](V32_CACHE_MERGE_AND_EXECUTION_API_FEEDBACK.md). |
| [archive/DECODED_ICACHE_SCOPE_DRAFT_2026-10-10.md](archive/DECODED_ICACHE_SCOPE_DRAFT_2026-10-10.md) | [Agreed plan](DECODED_ICACHE_PLAN.md) and [protocol](DECODED_ICACHE_PROTOCOL.md); keeps the nine questions V-32 answered. |
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
| [archive/CPU_ARCHITECTURE_REVIEW_HANDOFF.md](archive/CPU_ARCHITECTURE_REVIEW_HANDOFF.md) | V-32's 2026-10-10 CPU architecture and native-performance review request, kept verbatim as the input to `CPU_ARCHITECTURE_REVIEW_RESPONSE.md`. Its evidence paths refer to the V-32 repository. |
| [archive/CoreFeatureProbe.java](archive/CoreFeatureProbe.java) | V-32's standalone probe from the round-1 review (outside the Maven source sets; run with `java -cp <core jar> CoreFeatureProbe.java`). Expected output against `0.1.4-SNAPSHOT` is in `archive/CPU_INTEGRATION_RESPONSE.md`. V-32's current copy (`../emulator/review/`, alongside its round-2 `CoreResponseProbe.java`) adds a `checkAccess` override and is not mirrored here. |

## Historical emulator documents

Superseded application-side notes were moved to the
[V-32 emulator repository](https://github.com/AlienSpaceBunny/v32-emulator)'s
`docs/archive/`, with distinct filenames to preserve its existing copies:

- [RV32EMU_EMULATOR_REPO_NOTES_2026-09-13.md](https://github.com/AlienSpaceBunny/v32-emulator/blob/371af30de51b3ce736c882a83fffdafa512b0c3d/docs/archive/RV32EMU_EMULATOR_REPO_NOTES_2026-09-13.md) — old application architecture snapshot.
- [RV32EMU_MULTI_HART_BUS_NOTES_2026-09-13.md](https://github.com/AlienSpaceBunny/v32-emulator/blob/371af30de51b3ce736c882a83fffdafa512b0c3d/docs/archive/RV32EMU_MULTI_HART_BUS_NOTES_2026-09-13.md) — original shared-RAM bus handoff.

The CPU review responses and acceptance remain here as API and release evidence.
