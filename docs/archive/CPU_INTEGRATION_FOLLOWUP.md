# CPU response review — 2026-09-16

Decision: request two focused processor corrections before resuming V-32 bus
and mailbox integration. The original seven reproductions now produce the
requested results, and the additive `MemoryBus.checkAccess` API is suitable.
The response's reservation-lifecycle promise is not fully implemented, and
interrupt reevaluation still misses guest state changes within a batch.

This reviews [CPU_INTEGRATION_RESPONSE.md](CPU_INTEGRATION_RESPONSE.md), which
answers [CPU_INTEGRATION_REVIEW.md](CPU_INTEGRATION_REVIEW.md). The response is
preserved unchanged. No sibling-project or emulator runtime files were changed;
`build.gradle` still names `0.1.3-SNAPSHOT` pending acceptance.

## Evidence

- Tested the installed `com.alienspacebunny:rv32emu-core:0.1.4-SNAPSHOT` jar
  directly with Java 25.0.4.1. SHA-256 matches the response:
  `e4ce1982bae095b49dc32e6574b715229176d9d75173c234bf279e17f57c74f3`.
- Read the sibling core, bus contracts, MMIO forwarding, and relevant
  regressions at checkout HEAD `8e811d7`. Source references below are to
  `core/src/main/java/com/alienspacebunny/emu/RV32IMACore.java` there.
- Updated the original standalone probe with a side-effect-free `checkAccess`
  implementation, including full-width bounds validation. Its seven original
  observations match the response and its nine positive feature checks pass.
- Added [review/CoreResponseProbe.java](review/CoreResponseProbe.java), an
  independent asserting probe. It reports **12 passing assertions and 18
  failing assertions across the two remaining issues**, then exits nonzero.
  Failures include both immediate state and downstream-effect checks; they
  are not 18 distinct defects.
- No Gradle build/native build or upstream Maven build was run in this review.
  Changes are documentation and standalone probes outside Gradle source sets.
  This validates selected behavior of the published jar, not full emulator
  integration or ISA conformance. The response's 517-test upstream gate is
  reported evidence, not independently rerun here.

## Accepted changes

- U-mode MRET now traps, with either MPP=0 or MPP=3.
- MSIP and MEIP pending before WFI complete WFI without stalling and trap on
  the following step with the correct `mepc`.
- Misaligned LR/SC/AMO trap with the stated causes before data-bus access;
  nonzero-rs2 LR is rejected.
- A bus-throwing SC clears the local reservation, and an unreserved SC to a
  denied address calls `checkAccess` with the expected context and traps
  without modifying rd or invoking `tryScAndStore`.
- The context/atomic forwarding additions in `MMIOBus` and the corrected
  interrupt thread-ownership guidance address the earlier handoff concerns.
  Device-hook atomics and permission probes remain explicitly limited; V-32
  must enforce its own device policy rather than infer protection from them.

## Request 1: apply the promised reservation cleanup before alignment faults

The response says every LR/SC attempt drops the local reservation, explicitly
including misaligned SC. However, the alignment branch at lines 1879–1882 exits
before the clears at lines 1897 and 1907–1908. The existing alignment regression
starts with no reservation, so it cannot detect this.

Reproduction, with one hart and the default single-hart SC implementation:

1. Execute `lr.w x3,(x1)` at PC 0 with x1=`0x100`.
2. Change x1 to `0x101`; execute `sc.w x3,x2,(x1)` at PC 4, with x2=42.
3. Observe cause 6, `mtval=0x101`, **`reservationValid=true`**.
4. At `mtvec=0x80`, execute `addi x1,x0,0x100` and another SC, without a
   new LR. The SC returns **0** and writes **42** to `0x100`.

Replacing step 2 with a misaligned LR produces cause 4 and the same stale
reservation reuse. This contradicts the explicitly chosen fault policy;
the request does not depend on treating every possible trapping LR policy
as architecturally forbidden. The general SC invalidation and LR/SC pairing
rules are in the [atomic specification, §13.2](https://docs.riscv.org/reference/isa/v20240411/unpriv/a-st-ext.html).

Please:

- Preserve the pre-attempt local-validity decision needed for an aligned SC,
  but clear the local reservation before a decoded LR/SC alignment fault can
  exit. Retain the no-data-bus-side-effects guarantee for misaligned atomics.
- Add valid-LR → misaligned-SC and valid-LR → misaligned-LR regressions,
  checking both cleanup and a subsequent SC without a new LR. Keep the
  successful aligned LR/SC and bus-access-fault tests.
- Clarify bus-side lifecycle when no atomic bus call occurs. A misaligned
  attempt never reaches the bus, and `checkAccess` is expressly forbidden to
  mutate reservation tracking. Thus the contract cannot promise immediate
  bus/core table equality on every fault or locally failing SC. Either
  document that a stale bus entry is inert behind the core's false local
  flag, must be replaced/cleared on the next LR attempt, and is cleared by
  embedder reset/remap handling; or specify a separate cleanup mechanism.
  Do not turn `checkAccess` into a stateful cleanup operation. The present
  API can remain sufficient with the inert-entry policy made explicit.

## Request 2: reevaluate interrupts after guest CSR writes and MRET

`pendingEnabledInterrupts` is evaluated at step entry (line 1133) and in WFI
(line 1825), but not after interrupt-affecting CSR writes (line 1783) or MRET
(lines 1800–1807). The new `csrs mie; wfi` test only proves the WFI special
case. Other guest instructions continue within the same batch after a
pending interrupt becomes deliverable.

Minimal MSIP reproduction:

```text
initial: M-mode, mip.MSIP=1, mie.MSIE=1, mstatus.MIE=0, x1=8
PC 0:  csrs mstatus,x1     (0x3000a073)
PC 4:  addi x4,x0,1        (0x00100213)
step(count=2)
actual:   pc=8, x4=1, mcause=-1 (sentinel: no interrupt trap)
required: x4=0; interrupt entry has mcause=0x80000003, mepc=4
```

The same result occurs when enabling `mie` with MIE already set, and when
setting the supported writable pending bit in `mip` with both enables set.
The probe exercises each CSR with MSIP and MEIP. Separately, an M-mode MRET
to U-mode with MSIP pending and MSIE set executes the instruction at the
return target (`0x40`) before trapping; that target instruction must wait
until the interrupt is handled, even when MIE remains zero in U-mode.

Interrupt conditions must be reevaluated immediately after xRET and writes
to the CSRs on which interrupt delivery depends; this is stronger than the
ordinary bounded-time rule for a pending bit changing. See the
[privileged specification, §3.1.9](https://docs.riscv.org/reference/isa/v20240411/_attachments/riscv-privileged.pdf).
This matters to V-32 because its AP normally calls `step` with 1,061
instructions per scanline, rather than one instruction per call.

Please reevaluate before executing another guest instruction after these
boundaries, preserving interrupt priority, trap PC, privilege, and cycle
accounting. Delivering within the current call or ending the batch and
delivering at the next call is acceptable provided no intervening guest
instruction executes. Add CSR cases without WFI and MRET cases for M-mode
interrupt reenable and return to U-mode. Cover compressed return targets if
enabled; do not assume the next instruction is four bytes long when forming
`mepc`. No cross-thread state mutation is needed for these reproductions.

## Reproduction and resume condition

From the emulator repository with Java 25:

```sh
java -cp "$HOME/.m2/repository/com/alienspacebunny/rv32emu-core/0.1.4-SNAPSHOT/rv32emu-core-0.1.4-SNAPSHOT.jar" review/CoreFeatureProbe.java
java -cp "$HOME/.m2/repository/com/alienspacebunny/rv32emu-core/0.1.4-SNAPSHOT/rv32emu-core-0.1.4-SNAPSHOT.jar" review/CoreResponseProbe.java
```

The first command exits zero and prints the corrected original observations
plus nine PASS lines. The second currently exits one after `12 passed; 18
failed`; a corrected jar should pass all 30 assertions. It allows early batch
termination at the CSR/MRET boundary before delivery on the following call.

Publish the two fixes and regressions, clarify the no-bus-call reservation
lifecycle, and rerun these probes. Then update V-32's dependency explicitly,
implement the shared-RAM/context/privilege integration and synchronized mailbox
slice using the original review's ownership corrections, and run
`./gradlew build`. Do not restore reliance on direct cross-thread
`injectInterrupt`, default multi-hart atomics, or permit-all failed-SC checks.
