# Test Coverage and Remaining Candidates

Reviewed against the test sources on **2026-10-07**. The original instruction
expansion (Layer 1) and compliance-style vector suite (Layer 2) are complete.
Later ISA and integration fixes added substantial coverage beyond that plan.
The [original plan](archive/TEST_PLAN_PRE_CLEANUP_2026-10-07.md) is archived.

## Current coverage

Core tests live in
[`core/src/test/java/com/alienspacebunny/emu/`](../core/src/test/java/com/alienspacebunny/emu/).

| Suite | Coverage |
|---|---|
| `CoreTest` | Integer execution, CSR access and privilege checks, trap state, timer boundaries, interrupt priority, WFI wakeups, LR/SC, invalid encodings, and in-batch interrupt reevaluation. |
| `RV32IComplianceTest` | Parameterized integer and multiplication/division vectors, plus Zba and Zbb instruction tests. |
| `CompressedInstructionTest` | Compressed/32-bit differential execution, mixed streams, extension gating, alignment, and fetch-window edges; includes compressed F loads/stores. |
| `FExtensionTest` / `FExtensionRoundingTest` | FP register/CSR behavior, special values, rounding modes and flags; independent arithmetic differential checks. |
| `ZabhaTest` | Sub-word AMOs, neighboring-byte preservation, signed/unsigned comparison and extension gating. |
| `AtomicPrimitivesTest` | Bus routing, bus-side SC rejection, permission faults, atomic alignment and reservation lifecycle. |
| `AccessContextTest` / `MMIOBusTest` | Context propagation, legacy compatibility, hook ownership/ranges/widths, and forwarding atomic primitives. |
| `IsaConfigTest` / `FFMMemoryBusEndianTest` | Configuration/misa behavior and little-endian memory layout. |

The CLI's [IntegrationTest](../cli/src/test/java/com/alienspacebunny/cli/IntegrationTest.java)
executes the baremetal binary with UART, CLINT and syscon hooks. `clean verify`
also runs the packaged CLI jar against that binary. V-32's separate multi-hart
acceptance checks are recorded in
[CPU_INTEGRATION_ACCEPTANCE.md](archive/CPU_INTEGRATION_ACCEPTANCE.md); they are
consumer-side tests, not part of this Maven suite.

## Original Layer 3 audit

Related tests do not necessarily exercise the complete proposed guest flow.

| Proposed scenario | Current coverage | Remaining value |
|---|---|---|
| Timer interrupt → handler → MRET | Entry and MRET have separate state tests; pending interrupts after MRET are covered. | One complete guest round trip that acknowledges/rearms the timer and resumes without immediate retrapping. |
| WFI → elapsed-time timer wake | Timer wake is tested from a manually stalled state; other tests execute WFI with pending interrupts. | Execute WFI, observe a stalled call, advance `elapsedUs` across the match, and verify wake/trap state. |
| User-mode ECALL | Machine-mode ECALL and U-mode CSR/MRET restrictions are tested. | A direct U-mode ECALL test asserting cause 8, saved PC/status and entry into M-mode. |
| CLINT register access | Baremetal smoke tests include CLINT; no dedicated register assertions. | Verify the supported low/high timer-word reads and compare-word writes through `MMIOBus`. |
| PC outside the fetch window | Bus-rejected fetches and a compressed word overrun are covered. | Direct precheck tests below the window and at its end, including proof that no bus access or post-exec callback occurs. |
| Misaligned PC | `misalignedTwoByteFetchTrapsOnlyWithoutHasC` covers the 4-byte versus 2-byte rule. | Strengthen trap-state assertions and add an odd PC with C enabled; avoid duplicating the existing alignment test. |

## Candidates worth implementing

These concern existing behavior and need no additional ISA work. They are
proposals, not scheduled implementation:

1. **User-mode ECALL** — small, directly relevant to the public privilege contract.
2. **WFI with elapsed-time timer wake** and **timer-handler/MRET round trip** —
   exercise transitions currently tested mostly in isolation.
3. **CLINT register contract** — put this in `cli`, where `CLINTHook` lives, so
   `core` remains independent of the runner. The current hook recognizes exact
   word-register addresses; the old plan's blanket promise of byte-accurate
   partial-register access was not an implemented contract. Define any desired
   sub-word behavior separately before testing it.
4. **Fetch prechecks and odd-PC trap state** — target the uncovered branches and
   observable trap/callback behavior.

## Longer-term validation

Integer compliance-style and compressed/FP differential testing already exist.
A Java-versus-C execution comparison would add a separate oracle for their shared
ISA subset, accounting for intentional semantic differences. A repeatable
performance baseline would also be useful before setting any regression threshold;
neither is currently implemented or scheduled.
