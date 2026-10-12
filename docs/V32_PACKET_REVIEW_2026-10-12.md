# Packets 001–003: CPU design and hardware-profile review

Date: 2026-10-12. Documentation review only. Packet 001 policy remains accepted;
002/003 author replies remain blank. Resume interactive review at **002/D1**.
The prospective packets below collect questions without selecting features or
moving the XIP/C → Rust R1 → bounded-heap R2 implementation sequence.

## Inputs and current status

- CPU [execution design](../../../rv32ima-java/docs/EXECUTION_API_DESIGN.md) and
  [timer/counter design](../../../rv32ima-java/docs/TIMER_COUNTER_DESIGN.md),
  revision 6, dated 2026-10-12: confirmed from V-32's side; implementation waits
  for Nate's go. Timer/counter work precedes the execution API. These are design
  agreements, not published-core behavior, dependency adoption or validation.
- [Historical hardware profile](../HISTORICAL_HARDWARE_PROFILE.md): use its
  programming examples and cost distinctions, not historical feature counts or
  bus fidelity as requirements. This pass adds no historical research claims.
- [001](001-clocks-timer-events.md), [002](002-async-device-dma.md) and
  [003](003-storage-lifecycle.md): explicit author replies govern. CPU interface
  agreement does not answer device authority, buffer lifetime or storage policy.

## Packet 001: retain policy, reconcile mechanisms

The AP timer has **retained device state** (request, deadline, cadence, expiration)
and an **effective core compare**. Masking sets the latter to
`TIMER_COMPARE_NEVER`; it neither consumes an expiration nor advances cadence.
Readable device pending follows retained state, not masked core MTIP. This
clarifies the earlier phrase “timer pending reflects its compare.” It supplies
the accepted mask semantics without reserving guest `mie.MTIE` for the host.

Unmasking an overdue retained deadline must take effect at the next eligible
execution boundary. The revision-6 design ends the CPU call after that MMIO
store and starts a new call before another instruction, obtaining fresh time.
A scheduling stop must retain its own reason; it is not a terminal halt and
must not trigger terminal reservation cleanup. Production budget-1 stepping
already returns each attempt; future batching needs the explicit stop path.
No batch size or API adoption is selected here.

The shared source is sampled once per call and on legal `time/timeh` reads.
`getTimer()` is only a last sample. Device pending reads, ACK cadence and WFI
deadlines use current shared time. A fresh legal time read can refresh MTIP and
cause a same-attempt interrupt after retirement. Mid-call deadlines otherwise
wait for another sample; instruction counts do not bound host latency.

Core WFI now wakes for pending locally enabled implemented interrupts, without
setting global MIE. Wake and trap delivery differ. Migrate the IOP's old masked
due-compare wake and zero-sentinel waits; do not spin on a masked overdue timer.
Zero is a valid compare, not cancellation. Cross-thread commands publish retained
device state and wake the owner; workers do not edit hart state directly.
Epoch reset requires coordinated/quiescent harts and workers.

**Questions worth preserving if policy is needed:**

- What usable timer/control response target should constrain future call duration?
  Start with measured interactive behavior; 1 µs representability promises no
  corresponding host latency. Exact budgets remain implementation choices.
- Does an ACK consume everything due at its serialized current-time observation?
  Suggested interpretation: one coalesced expiration, next future original-cadence
  boundary; a concurrent replacement/cancel wins according to device ordering.
  This refines accepted cadence, not a proposal to replay missed ticks.
- What `mcounteren` grant does fresh AP startup receive? The proposed core starts
  it at zero while LAUNCH enters U-mode. Full Zicntr support alone does not select
  initial U-mode permission. Also define reset of work counts and architectural
  offsets explicitly; elapsed time remains independent.

Invalid RTC low/high sequences remain register-design work. Whole-machine reset
must discard staging. Do not add coherent RTC reads, a pause clock, direct AP
timer programming or extra persistent state without an explicit new decision.

### Follow-up reply slots for Packet 001

**R1 — Startup counter access:** should AP U-mode initially read all three Zicntr
counters, only time, or receive grants through privileged startup/service policy?
Suggested default for discussion: expose all three for simple timing/profiling,
with any privilege restrictions stated explicitly. Current LAUNCH enters U-mode
with `mie` zero; identify the privileged path that prepares interrupt enables and
counter grants before AP opts in. No U-mode machine-CSR write is assumed.

**Author reply (R1):**

**R2 — ACK observation boundary:** should ACK consume all expiration through its
serialized current-time observation, moving a repeater to the next future boundary
on original cadence? This is the suggested interpretation above. If no new policy
is needed, record it as register/concurrency design rather than reopening C1–C6.

**Author reply (R2):**

**R3 — Response target, when batching is considered:** what timer/control delay
would be noticeably unacceptable for interactive use? Keep measurements and the
host-stall qualification with any eventual target; do not promise a hard bound
that an instruction quantum cannot supply. No answer is needed to draft packets.

**Author reply (R3):**

## Packet 002: authority and ownership before opcodes

D1 still chooses mediated versus direct blit authority. Historical command engines
suggest useful operations, but do not resolve V-32's MPU/lifetime checks. D2's
control snapshot is distinct from bulk payload ownership: pinning, staging and
explicit live mutation remain alternatives. LIVE presentation does not select
live mutable DMA inputs. Audio naturally transfers spans continuously; a blit
owns a finite operation. They need a common lifetime rule, not identical buffers.

D3/D4 must budget accepted commands **and unconsumed completions**. One executing
request does not bound history if another can start before its result is consumed.
Choose a retained slot that stays busy, reserved bounded completion capacity, or
an explicit consume-before-reuse rule. Completion visibility and notification
are separate: retain ready state through a full mailbox or masked interrupt,
define reassert/ACK behavior, and preserve unrelated MEIP sources. No source
number or transport is selected. Destination publication needs a specified
guest observation path; renderer visibility remains LIVE.

D5 needs a result for a partially completed rectangle, not merely a byte count
whose geometry is unknowable. Cancellation must preserve the chosen overlap
semantics. D6 should decide supported memory classes, pixel widths, pitches and
overlap before adding keyed/masked operations. General cross-row overlap may
require staging or rejection; reversing individual rows is insufficient.

Audio needs a separate clock discussion: shared machine time keeps running
through host stalls, while the host output clock may drift or stop. Choose sample
position meaning, underrun catch-up/discard, bounded buffering and any drift
correction. CPU timer sampling is not a per-sample scheduler. Choose sample format,
rate/channels and synthesis/PCM mixing order; historical eight-bit PCM is a lead,
not proof of an adequate final format. AP loss must not leave audio reading reused
RAM. HALT may retain an active stream; application teardown must settle it.

## Packet 003: storage authority and failure boundaries

L1's simplified block controller remains a proposal. Separate SD identity,
controller vocabulary, host backing and filesystem owner. Writable source/save
work needs L3/L4 even if initial bring-up is read-only. L2 remains after R1/R2
budget evidence; historical service processors do not establish SRAM sufficiency.

L3 should specify ordinary host-completed writes versus backing synchronization,
barrier ordering, error retention, close semantics and interruption-loss windows.
An atomic editor save is a separate filesystem/service guarantee. L4 must cover
host-resource identity as well as guest generations: an old writer can still
alter the same backing file after its guest result has been invalidated. Retain
or quarantine the image handle and prevent conflicting reopen/remount until safe.
A timeout neither undoes media writes nor proves settlement.

L5 must make parent return conditional on both safe RAM reuse and volume/cache
handoff. Child writes can invalidate a parent's cache even when parent RAM is
intact. Hardware LAUNCH retention of timers/devices does not grant the new child
ownership of an old application's requests. Firmware/service teardown supplies
that boundary under D5/L4; no automatic hardware cleanup is inferred.

L6 should first ask **whether any additional persistent SRAM is needed**. Accepted
CMOS/RTC plus SD do not imply another device. Only if selected should capacity,
restart backing and corruption handling be designed. Pseudo-LPT log ownership,
bounded output and slow-host teardown remain distinct from guest filesystem flush.

## Prospective questions and next review

The pass included one read-only GPT-6 Astra/high consultation through
consult-advisor. Its timer/lifecycle, direct-command revocation, extra-persistence
and audio-clock findings are incorporated above. Its suggestion for a separate
audio packet is kept under D6 to avoid competing scopes; prospective packet
numbers follow this repository's index. Advisor recommendations are not approval.

[004: Display publication and graphics scope](004-display-publication.md) collects
sprite, origin/scroll and optional raster questions; [005: Input and service
discovery](005-input-service-discovery.md) collects editor input and capability
questions. They are future discussion drafts, not prerequisites for basic video
separation. Audio stays under D6 rather than acquiring a competing packet.

Resume **002/D1**, one question at a time, using this review as context. Revisit
001 only if a clarification above requires an author policy answer. Resolve
002/003 before scoping their runtime integration; do not restart CPU performance
screens or treat confirmed CPU designs as adoption authorization.
