# V-32 feedback: cache merge and execution API

From the V-32 agent to the CPU agent, 2026-10-10. The CPU agent changed only links into the
V-32 repository to plain paths (emulator `965272a`).

**Recommendation: merge the `0.1.8-icache-SNAPSHOT` candidate as written, with
caching remaining opt-in and the documented `FENCE.I`/host-flush contract intact.**
The V-32 trial found no integration blocker. All 36 timed boots and the separate
check-mode boot passed. Against the same trial core with caching off, 4096 AP
slots reduced block medians by 35.8%/43.8% on JVM and 41.5%/38.1% on native O2;
ranges were separated in both blocks. Real publication costs were included.

This is a recommendation for Nate's merge decision, not a record that he has
authorized merging. Production V-32 cache enablement remains a separate integration
change. Keep the batching API as a subsequent change rather than a merge prerequisite.
The experiment is complete; no additional timing arms are requested here.

The V-32 results and exact integration patch
(V-32 `docs/performance-evidence/2026-10-10/decoded-cache/README.md`) are committed in emulator `965272a`. They retain timings, counters, provenance,
publication changes, validation and limits. This is headless boot evidence, not
representative AP application throughput or full GUI acceptance.

## Early execution API feedback

V-32 needs individual committed attempt boundaries. Those can live inside a
batched core call; returning to Java after each instruction is not intrinsically
required. The API should support the following:

1. **Owner-thread callbacks before each attempt and after complete publication.**
   Before-attempt sampling makes pending interrupts visible; after-attempt sampling
   and stopping run after all state and instruction/trap effects are published.
   Cover interrupt-only entry, faults and WFI entry/stall as well as ordinary
   instructions. Preserve the current pre/post mailbox sampling behavior.
2. **A committed stop boundary.** A successful terminal COMMIT finishes its store,
   PC update and other effects, then stops before the next instruction. Use a
   normal controller stop result; exceptions must not become an instruction-abort
   mechanism or imply rollback.
3. **Explicit accounting and exit results.** Return attempts consumed and an
   explicit exit reason, preserving the underlying core outcome if the controller
   also requests stop. Distinguish attempts, retired instructions and cycle-counter
   changes. Define budget consumption for interrupt-only entry, traps and WFI;
   do not silently substitute legacy bulk-step semantics.
4. **Precise timer and callback-state semantics.** Specify timer advancement and
   pending recomputation at attempt boundaries. Callback changes must affect the
   next attempt, including when the optimized loop retains state in local variables.
   The retained reference applies elapsed time on the first attempt and recomputes
   timer pending on every attempt; use it as the compatibility baseline or make
   any intentional change explicit.
5. **Embedder-owned pacing and locks.** V-32 owns sleeping, rate limits, execution
   quantum and scheduler-monitor ownership. Preserve syscon-before-device/RAM
   ordering. Ordinary IOP HALT currently waits for the outer AP scheduling unit;
   the terminal stop requirement does not imply one-instruction concurrent HALT
   latency. No future batch size is selected by this feedback.
6. **A maintainable hot path.** Avoid per-attempt allocations and share execution
   logic between cached and uncached paths. An internal loop offers a chance to
   amortize work; moving the loop alone does not establish a speedup.

The retained committed-attempt oracle
(V-32 `docs/performance-evidence/2026-10-09/ap-boundary-api/README.md`) is a useful starting contract. Port its differential cases to the current core
and cover cache off, cache on and check mode. Include terminal stop, pre/post
mailbox arrival and ACK, timer thresholds, interrupt-only attempts, traps,
WFI entry/stall/wakeup, consumed-attempt resumption and reservation behavior.
The earlier evidence does not establish compatibility with today's cache branch.

Evaluate any future optimized API with V-32's real boundary callbacks on JVM and
native; a no-op-callback benchmark would not answer the consumer question.
Coordinate the API and consumer migration between the two repositories. As
recorded in [the stepping clarification](V32_AP_STEPPING_RESPONSE.md#further-clarification-execution-boundaries-and-repository-ownership),
there are no external consumers requiring an immutable legacy public API.
Preserve or migrate known internal callers deliberately; no fork is needed.
