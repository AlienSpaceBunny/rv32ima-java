# Decoded instruction cache: V-32 advisory review

> Copied from V-32 (`../emulator`) `docs/DECODED_ICACHE_ADVISORY_REVIEW.md` at `805d76d` on 2026-10-10. Text is
> unchanged; links are adjusted for this repository, and links into V-32's evidence point at
> the sibling checkout. Recheck the original for later edits.

Reviewed 2026-10-10. Recommendation: **proceed with one bounded sizing experiment;
revise the correctness contract before production implementation.** This is advice,
not a selected V-32 capacity, coherence-policy change, or integration approval.

Follow-up: [matched boot results and the author's filesystem/decompression
clarification](CPU_ARCHITECTURE_REVIEW_FOLLOWUP.md).

Sources: the live emulator checkpoint, the CPU
[review response](CPU_ARCHITECTURE_REVIEW_RESPONSE.md),
[cache draft](DECODED_ICACHE_PLAN.md), and
[predecode evidence](performance/2026-10-10/README.md#predecode-upper-bound-experiment-not-landed-decision-rule-met),
plus the source paths cited below. No new measurements or runtime tests were run
for this review. Both checkouts were clean at the start.

## Recommendation and present position

The cache is worth investigating. The upper-bound gains are substantial even
after the hot-loop and compressed-expansion improvements. They justify testing
whether a small working set retains enough benefit; they do not justify a
whole-window replica or promise a comparable V-32 production speedup.

V-32 still depends on `0.1.6-SNAPSHOT`. Its earlier RAM and nested-monitor
experiments were inconclusive, with production defaults unchanged. The IOP
host-wait fix is complete. The final synthetic shared-decoder timing screen is
unexecuted; I agree with parking that screen. Preserve the committed-attempt
contract and correctness evidence for a separately scoped API decision.

Adopting the already improved core and testing `-O3` is the more immediate V-32
increment. The CPU cache experiment can proceed independently, without installing
its experimental artifacts into V-32's normal dependency path.

Bounded host storage fits the intended scale. This proposal models neither guest
cache latency nor a real hardware cache's byte layout, however. Describe capacity
both as decoded slots and actual host bytes, rather than treating 64 KiB of
decoded host storage as a 64 KiB guest instruction cache.

## Answers to the nine questions

1. **Code writes.** The checked firmware and baremetal sources use `fence iorw,
   iorw`, not `FENCE.I`. There is no established instruction-fence protocol.
   `EmulatorMachine.loadPayload` halts the AP, replaces RAM, resets and releases
   it. `IOProcessor.loadProgram` replaces private boot RAM and resets the IOP
   while quiescent. Firmware copies the AP payload before LAUNCH. These are
   natural flush boundaries. A notification in `SystemRAMBus` is feasible, but
   must cover byte/halfword/word stores, successful SC/AMO writes and
   `replaceContents`; raw `ramSegment()` writes remain quiescent-only and need an
   explicit flush before execution resumes.

2. **Cross-hart code.** The IOP copies AP code at boot. Its runtime `ROM_COPY`
   service can also write any valid destination in the AP window while the AP
   runs; it does not distinguish executable bytes from data. Current demo
   requests transfer assets, so this is a permitted code-write path, not evidence
   that the demo currently patches code. The AP cannot access private IOP boot
   RAM, but the shared-memory contract must also support future execution from
   shared RAM. Do not assume all cross-hart code writes occur at launch/remap.

3. **Uncacheable fetch.** Current AP execution is RAM-only, through `ApMemoryBus`;
   current IOP execution is confined by `step()` to its private 4 KiB boot window.
   There is no current production MMIO instruction-fetch requirement in those
   windows. Fetch permission checks still matter. Future XIP ROM is a promising
   cache target: `XipMemory` owns immutable images but enforces hart permissions
   and populated extents. It is not yet the production execution path. Require
   explicit cacheable declarations, covering every fetched byte, rather than
   assuming the entire coarse `step()` window is safe for every bus.

4. **MPU.** Permissions depend on hart and mapping, beyond M/U. AP traps into
   M-mode do not grant IOP access. AP logical RAM translates through an
   IOP-controlled base and limit. Guest mapping changes are rejected while the
   AP runs; accepted changes and trusted host changes occur between batches.
   There is no measured change frequency. Flush on every accepted mapping change,
   including a base change that leaves the executable limit unchanged. A
   privilege tag alone cannot identify the backing memory or permission state.

5. **Coherence mode.** Recommend preserving coherent behavior for initial V-32
   adoption. `FENCE.I`-only behavior could be an explicit future machine/guest
   contract, but should not be silently introduced as an optimization. The
   [RISC-V Zifencei specification](https://docs.riscv.org/reference/isa/unpriv/zifencei.html)
   makes instruction fencing local to the executing hart; remote instruction
   visibility requires synchronization and a fence on each executing hart.
   An IOP's ordinary `FENCE` does not flush the AP's instruction cache.

6. **Sizing and timing.** Recommend evaluating 1K–4K slots as the initial V-32
   candidate band, keeping 256 and 16K as experimental controls. This is a
   proposed budget, not an author decision. With the draft's 16 B entries:

   | Slots per hart | Entry payload per hart | Nominal C address span |
   | ---: | ---: | ---: |
   | 256 | 4 KiB | 512 B |
   | 1K | 16 KiB | 2 KiB |
   | 4K | 64 KiB | 8 KiB |
   | 16K | 256 KiB | 32 KiB |

   Conflicts can reduce useful coverage. Report array/object and coherence
   metadata overhead as well; avoid per-entry objects. Two 4K-slot harts would
   consume 128 KiB of entry payload, plus overhead and the existing shared
   compressed-expansion table. Keep guest cycles and timers unchanged; modelling
   cache timing requires a separate decision.

7. **Execution API.** Cache state should persist across `step(count=1)` calls.
   It must not change attempt counting, timer advancement, interrupt sampling,
   terminal-stop precedence, hooks or trap publication. The cache is independent
   of adopting `stepAttempts`. Checking pending invalidation only at call entry
   happens to fit the current AP loop; it is insufficient as a general coherent
   contract for long bulk calls or a future fused-attempt loop.

8. **Native image.** A second compiled loop is acceptable in principle if it
   protects the default-off path. Measure code area and total image size with
   matched production-relevant graphs. Avoid two independently maintained ISA
   implementations; reuse execution helpers and audit semantic parity. The
   disposable prototype's disabled-path regression makes this a real gate.

9. **API shape.** Prefer constructor options for capacity and coherence policy,
   with size zero retaining current behavior. Provide a separate invalidation
   API with explicit ownership/threading rules. Cacheability belongs to the
   execution-memory contract. Avoid a resizing setter initially; reconfiguration
   adds allocation and lifecycle states without an identified consumer need.

Source basis: `firmware/iop/main.c`, `firmware/include/v32.h`, and
`v32/src/main/java/com/alienspacebunny/emulator/{EmulatorMachine,IOProcessor,ApplicationProcessor,ApMemoryBus,SystemControlHook}.java`,
plus `memory/{SystemRAMBus,XipMemory}.java` under that package.

## Contract changes needed before implementation

- **Permission and identity changes:** a successful fetch proves permissions
  only at that instant. A hit must still obey current coarse bounds and alignment,
  and any finer-grained permission/mapping change must invalidate it. Specify
  ownership of cached state when a core is called with a different state, bus or
  hart identity; the existing API passes these into each call.
- **Concurrent invalidation:** a volatile flag checked at the next `step()` call
  provides deferred visibility, not general coherence. Define the ordering
  point relative to a completed write and subsequent fetch. Handle a write racing
  cache fill, avoid losing a request while clearing a flag, and prevent a callback
  under the RAM monitor from acquiring CPU/syscon locks in reverse order. A
  generation protocol or execution-boundary handshake is a candidate, not an
  already approved solution.
- **Aliases and write coverage:** invalidation must relate physical writes to AP
  logical PCs, including nonzero MPU bases and aliases supported by other buses.
  Invalidating the writer's same numerical PC is insufficient. Cover every
  instruction start whose bytes overlap a write, including odd-address byte
  writes and the second half of a 32-bit instruction. Include FP stores and all
  successful atomic stores. The cost is not universally one index/compare.
- **Dynamic execution checks:** predecode encoding fields, not conclusions that
  depend on current privilege, CSR/FP state or other mutable execution state.
  Preserve today's exact hook instruction word, instruction length and trap
  values, including compressed instructions. Cacheability must cover the full
  fetched instruction when it crosses a range boundary.

Add tests that first warm an entry, then revoke access, shrink/remap the window,
switch bus/state identity, modify code through a physical alias, and invalidate
during a bulk call or fill. Cold-fetch fault tests alone cannot detect these
stale-hit failures. Preserve full instruction-by-instruction differential checks.

## Bound the experiment and interpret it conservatively

Keep direct mapping for the first screen. Add a mixed 16/32-bit workload with
genuine distributed execution, plus deliberately colliding branch targets;
merely padding a binary does not enlarge its hot working set. Report cold and
warm behavior, conflicts, misses, host bytes, default-off overhead and image size.
Retain separate instrumented hit-rate runs and reversed measurement order.

The hit-rate speedup formula is illustrative, not a prediction. Bounded lookup,
miss fill, instruction mix, store invalidation and interference change its costs.
The no-invalidation screen establishes capacity potential only. A later real
implementation must repeat performance gates with its chosen coherence mechanism
and representative writes. Consider a provisional worthwhile-benefit screen of
at least 1.2x native speedup on representative integer code at no more than 4K
slots, reproducible across reversed blocks, and no material JVM regression;
agree the actual gate before running it. Stop after the declared matrix. Poor
small-cache results do not automatically authorize associativity or larger caches.

Finally, qualify the CPU response's consumer attribution. Its accessor and router
results are useful controls, but the roughly 77 ns production-locking claim is
not established component accounting. A remaining native/JVM ratio above 1.5x
does not prove locking is responsible. Likewise, a rejected cached-context
implementation does not prove every primitive-context design would fail. These
are reasonable priorities for investigation, not exhaustive causal conclusions.
Use matched current-core V-32 controls rather than old wall times as an adoption
gate; past production rankings reversed. Keep GUI acceptance and production
throughput claims separate from this source/design review.
