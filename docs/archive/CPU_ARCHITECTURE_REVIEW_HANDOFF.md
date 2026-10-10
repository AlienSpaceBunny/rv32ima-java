# CPU architecture review: V-32 use cases and native performance

**Review the processor emulator in `rv32ima-java` before choosing another
optimization experiment.** V-32 needs precise execution boundaries, shared-memory
access control and useful JVM/native throughput. Our experiments have established
some constraints and promising paths, but have not explained the remaining native
performance gap. Please assess whether the core's structure fits these uses and
recommend one bounded next step. This is a review request, not a prescribed rewrite.

Prepared 2026-10-10. Consumer: V-32 **0.0.19-SNAPSHOT**. Reviewed CPU:
`rv32emu-core:0.1.6-SNAPSHOT`, source revision
`f745790b4cef9d45e56e27908fc08b473530e00e`.
Recheck the target repository's live README, CHECKPOINT, AGENTS and API contracts
before reviewing. Its current release/API-freeze hold remains separate.

## Consumer use cases

V-32 is a Java 25 fantasy workstation with two independently scheduled harts.
The AP profile enables I/M/A/C/F/Zicsr/Zba/Zbb/Zabha and user mode; the IOP enables
the same integer extensions without F. D is unused. AP/IOP clock targets are
40/20 MHz, but execution is currently uncapped; those targets are aspirations,
not demonstrated host throughput. JVM and GraalVM Native Image are both intended
distribution paths. Avoid reflection, dynamic loading and JNI in the core where
practical; the reusable CPU should remain independent of V-32 devices and GUI.

| Use case | Required behavior |
| --- | --- |
| AP guest execution | Execute a bounded segment with committed state visible at every attempt boundary; observe device interrupt/halt changes before subsequent work |
| Terminal fault | A guest fault-report COMMIT halts AP before the following instruction can store; retain exact trap/PC/state publication |
| Mailbox service | AP/IOP share requests, results and interrupt state; sample arrivals and ACKs without skipping the boundary between adjacent attempts |
| WFI and host scheduling | Return a useful stop outcome so the embedder can wait and wake safely; waiting policy and wall-clock progression belong to the machine scheduler |
| Traps and interrupts | Account for attempts independently of retired cycles; interrupt-only entry, fetch fault and zero-returning trap behavior must remain explicit |
| MPU and shared RAM | Carry hart, current privilege, access kind and full width through every bus wrapper; translate AP logical RAM while protecting IOP-owned registers |
| LR/SC and AMOs | Preserve local and bus reservation behavior, cross-hart atomicity, sub-word widths, failed-SC permission checks and guest ordering |
| Reset, halt, launch and remap | Publish state at defined boundaries; clear reservations as required by the machine transition; permit subsequent resume/relaunch |
| Debugging and custom hooks | Preserve legacy partial-publication hook behavior and CSR mutation/exception behavior; committed callbacks are a distinct contract |

The machine has 16 MiB shared system RAM, 4 MiB ROM and 1 MiB VRAM. IOP boot
uses a 4 KiB heap-backed working RAM path. AP has no architected private RAM;
IOP may access shared system RAM. An AP-only benchmark therefore omits a real
concurrency requirement. V-32 currently coordinates shared RAM atomics and
reservations with a monitor; the CPU's default single-hart atomic helpers are
insufficient for that machine without bus overrides. `AccessContext` does not
carry `aq`/`rl`; the embedder supplies and verifies its stronger ordering contract.

FFM backing is an implementation choice. Different JVM/native backends are
acceptable when measured gains justify their correctness and maintenance costs.
Permission enforcement, memory map, ordering and atomic semantics must survive.

## Execution contract we have learned we need

V-32's production AP repeats `step(..., count=1)` with mailbox sampling around
each attempt and an outer scheduling monitor. Ordinary `step(count=N)` changes
terminal-stop, trap-budget and interrupt behavior; it is not an equivalent
replacement. The legacy post-execution hook runs before all state is committed
and omits some entry/fault/WFI paths, so it cannot substitute for a committed
boundary callback.

An isolated additive `stepAttempts` candidate preserves repeated-single-step
semantics: pre-sample before timer/interrupt processing, post-sample after state
publication, elapsed time on the first attempt and renewed timer checks on every
attempt. Count consumed attempts rather than cycle deltas. Preserve both the
core result and controller-stop indication, with controller stop taking priority.
Zero-returning traps may continue into the handler if budget remains. Null
controller still selects attempt semantics. Callback mutations must be reloaded;
callback exceptions propagate without conversion to guest access faults.

Nonpositive budgets preserve one pre-sample, virtual legacy `step` dispatch with
the original budget, no post-sample and zero consumed positive attempts. Initial
stop checks, reservation cleanup, cross-thread synchronization and clock ownership
remain embedder responsibilities. These are tested candidate contracts, not a
requirement to adopt that exact API spelling or implementation.

The latest private prototype shares one decoder between explicit legacy-bulk
and committed-attempt modes. It avoids maintaining two opcode switches and
constructs one public result per segment. Frozen baseline comparisons pass
**330** attempt cases and **415** additional bulk/hook/controller/CSR comparisons,
plus WFI, later-trap, rollover, virtual-dispatch and packing checks, on JVM/native.
Clone verification passes **633 tests**. This is bounded compatibility evidence;
concurrent remapping/mailbox stress and a real production adapter remain open.

## Native performance observations

Normal JVM controls use Corretto **25.0.4.1**. Native builds use GraalVM Community
**25.3.4.1+1.1**, Java 25.0.4.1, O2, compatibility target, Serial GC and four builder
threads. Host: Intel Core i9-13950HX laptop. Thermal throttling is expected and
must not become a recurring zero-throttle prerequisite. Later screens verify
CPU-2 placement, retain ranges and order effects, and separate build from timing.

| Observation | What it supports and what remains open |
| --- | --- |
| Earlier register/AP control: JVM segment 386.68 ms; native segment 896.05 ms; native buffer 387.88 ms | Fetch/accessor choice can materially affect native guest execution; register work still fetches instructions through RAM |
| Earlier raw-core/minimal-bus register: native segment 689.94 ms versus buffer 123.08 ms | Accessor effects persist without production reservation/monitor machinery; this unlocked bus is single-thread diagnostic scaffolding |
| Recent fused-entry screen: JVM 280.403 → 270.706 ms; native 703.079 → 677.753 ms | About 3.46%/3.60% lower medians for 5,000,009 attempts; one pair per runtime, no reproducibility or production boot claim |
| Native candidate remains about 2.50x JVM in that recent segment-backed fixture | Amortizing interpreter entry alone has not explained or resolved the native-specific gap |
| Heap/off-heap production boot ranking reversed between blocks | Synthetic backend gains do not select a permanent machine default |
| Nested-monitor repeatability was inconclusive | No justification to remove production ordering or broaden that matrix |

The earlier controls and recent screen use different reachable graphs, adapters
and capture conditions. Do not compare their absolute times as a regression or
subtract their medians to estimate component costs. CPU snapshot coordinates
alone are insufficient identity: preserve exact sources/JARs/classes per capture.

JVM JFR attributed **99.87% of sampled allocation pressure** to `AccessContext`
in the earlier capture; this is not an exact allocation count or CPU-time share.
Source currently constructs access records in the instruction path. With C
enabled, a 32-bit instruction is first probed as a halfword and then fetched as
a word. Whether escape analysis, inlining or a different fetch abstraction can
reduce these costs under Native Image remains a question, not a diagnosis.
Optimizations must preserve full-width permissions, halfword-aligned instructions,
boundary faults and observable bus/device access behavior.

Full-depth native JFR contains guest CPU/bus frames; an earlier five-frame result
was an export-depth error. Later measured top frames remained concentrated in
recurring callbacks, so exclusive component attribution is unresolved. There is
no demonstrated Native Image compiler defect or known fast historical baseline.

One concrete machine fix is already delivered: host waiting reduced matched JVM
idle IOP CPU from **99.56% to zero measured thread CPU time** while retaining
wakeup/clock behavior. A single matched boot pair measured JVM 4.756/4.990 s and
native 17.710/12.293 s before/after. Those boot samples do not establish a stable
speedup. Scheduler fixes and core changes must be assessed separately.

## Questions for the CPU repository review

1. Does the core's execution API distinguish legacy bulk throughput, committed
   attempts, traps, WFI and externally requested stops clearly enough? What
   minimum change supports V-32 without moving its scheduler/devices into the core?
2. Which hot-path structures merit investigation under Native Image: access-record
   construction, virtual/contextual bus calls, compressed fetch, decoder size,
   state reload/publication, exception boundaries or callback placement? Support
   priorities with source and existing evidence; do not assume every object
   construction survives compilation or every abstraction should be removed.
3. Could a primitive/context-specialized memory interface or optional executable
   fetch path help while retaining MPU, MMIO, fault and reservation contracts?
   Identify invalidation and access-observation obligations before proposing
   caching, predecode or direct RAM access. Code can be writable and remapped.
4. Is the shared driver maintainable, and could its execution modes cause native
   specialization/code duplication or harm the unchanged legacy path? Source
   sharing alone does not prove generated-size or speed improvements.
5. Should we run the already scoped final entry-timing screen, redirect to one
   different core-level experiment, or park CPU optimization and return to XIP?
   Which decision-relevant evidence is missing, and what result would end the work?

Keep larger alternatives such as predecode or another execution engine conditional
on evidence. Native Image compatibility, maintenance and correctness matter
alongside throughput. A broad rewrite is not the assumed outcome.

## Requested outcome and work boundary

Start with a source-and-architecture review in the CPU repository. Return a short
assessment separating core limitations, consumer overhead and unresolved compiler
behavior; rank at most three worthwhile directions and recommend **one** next
step, including pass/fail checks, fixed budget and stop conditions. An explicit
defer decision is useful. Review the existing API/test obligations rather than
recreating V-32's investigation or expanding a benchmark matrix automatically.

This handoff authorizes no upstream implementation, experimental installation,
release, API freeze, emulator integration or publication. Experiments remain in
isolated clones/artifacts until separately scoped. Preserve the original CPU
checkout and normal snapshot. Manual GUI follow-up remains deferred; headless
core evidence is not full-machine visual/input/resize acceptance.

The final AP timing plan is prepared but unexecuted. Prioritize this architecture
review; it may retain, revise or defer that plan. Do not silently run both paths.

## Evidence and entry points

All evidence paths below are relative to `/home/nate/work/emulator`. They are also
available in [the V-32 source snapshot](https://github.com/AlienSpaceBunny/v32-emulator/tree/5bc0b0876abf7ad6b187416529948d90c1d907da).
The text above is self-contained when this document is passed to the CPU repository.

- `docs/EXECUTION_ARCHITECTURE_AUDIT.md`: machine execution and boundary findings.
- `docs/performance-evidence/2026-10-07/controls/README.md`: accessor controls,
  provenance, raw ranges and JVM allocation profile.
- `docs/performance-evidence/2026-10-09/ap-entry-timing/README.md`: one-pair screen,
  complete output/work checks and adapter limits.
- `docs/performance-evidence/2026-10-09/ap-shared-decoder-correctness/README.md`:
  candidate patch, exact frozen oracle, source audit and compatibility comparisons.
- `docs/performance-evidence/2026-10-09/ap-shared-decoder-native/README.md`:
  native correctness and descriptive size; graph includes frozen decoder/assertions.
- `docs/AP_SHARED_DECODER_TIMING_PLAN.md`: unexecuted final screen and decision policy.
- `docs/RAM_PERFORMANCE_HANDOFF.md` and `docs/NATIVE_PERFORMANCE_PLAN.md`:
  completed/deferred investigations and attribution limits.
- `v32/src/main/java/com/alienspacebunny/emulator/{ApplicationProcessor,IOProcessor,ApMemoryBus,ProcessorProfiles}.java`
  and `memory/SystemRAMBus.java`: consumer execution, translation and shared memory.

In `rv32ima-java`, start with `core/src/main/java/com/alienspacebunny/emu/`
(`RV32IMACore`, `RV32IMAState`, `MemoryBus`, `AccessContext`, `IsaConfig`),
`docs/API.md` and `docs/TEST_PLAN.md`. The original checkout was clean at the
pinned revision when this handoff was written; no CPU repository files changed.
