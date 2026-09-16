# CPU integration review — 2026-09-15

Decision: request processor-project corrections before continuing the mailbox
slice. No emulator runtime changes were made. The sibling checkout was read
only; its README was readable without manual approval.

## Evidence and scope

- Read `README.md`, `CHECKPOINT.md`, `FEATURE_REQUEST.md`, and
  `MULTI_HART_BUS_NOTES.md`, then traced the local AP/IOP bus wiring.
- Inspected sibling API/plan/handoff documents, core/state/bus/config sources,
  and relevant test coverage at `rv32ima-java` HEAD `9bae09c`.
- Tested the installed `com.alienspacebunny:rv32emu-core:0.1.3-SNAPSHOT` jar,
  which is the dependency version named in `build.gradle`. The handoff's
  `0.1.2-SNAPSHOT` reference is stale.
- Jar SHA-256:
  `76200a7deffb855fb1e458283045aed6db13bcab5f2443664ad785fb0ebb671e`.
- Used Java 25.0.4.1 and an isolated FFM-backed bus with unaligned access
  support, making missing core validation observable without host exceptions.
- The user reports successful `gradlew build` and `gradlew nativeBuild`.
  Those builds were not repeated for this documentation/standalone-probe change.
  The upstream Maven build was not run, to keep that checkout read-only.

The new interfaces and extension implementations are present. Nine positive
smoke checks passed: U-mode machine-CSR rejection, U-mode ECALL with same-hart
M-mode entry, enabled MSIP delivery, LR access metadata, C.ADDI, SH1ADD, CLZ,
FADD.S, and AMOADD.B preserving neighboring bytes. This is a focused integration
review, not a full ISA or floating-point compliance certification. V-32 still
constructs both cores with the base ISA config; these additions are not enabled
in its runtime yet.

## Processor follow-up

Line numbers below refer to the inspected sibling `RV32IMACore.java`.

1. **U-mode MRET executes instead of trapping (line 1768).** The MRET branch
   does not check current privilege. With instruction `0x30200073`, U-mode,
   `mstatus.MPP=3`, and `mepc=0x40`, the installed jar jumps to `0x40` in M-mode
   with no trap. Even with MPP=0, executing MRET from U-mode must be rejected.
   Add privilege/encoding validation before the return-state mutations, with
   U-mode rejection and valid M-mode return regressions. This does not bypass
   our unconditional AP crossbar policy, but invalidates the intended CPU
   privilege boundary.

2. **Already-pending MSIP can leave WFI stalled indefinitely (lines 1097,
   1124, 1791).** Reproduction: M-mode, MSIE=1, global MIE=0, timer disabled;
   inject MSIP before executing WFI. WFI sets MIE and enters its wait state.
   The next step returns 1 before checking the pending enabled software
   interrupt. No second injection or timer event should be needed. Check
   pending locally enabled interrupts before the WFI early return, distinguish
   wakeup from trap delivery, and test MSIP/MEIP before and after WFI plus
   guest interrupt-enable changes within a batch. The legacy WFI-sets-MIE
   behavior is documented; this lost wakeup is an additional problem even
   under that behavior.

These expectations follow the [privileged specification's trap-return and WFI
rules](https://docs.riscv.org/reference/isa/v20260120/priv/machine.html).

3. **Atomic operand validation is incomplete (lines 1813–1840).** LR.W at
   `0x101` creates a reservation, and AMOADD.W there writes memory, with no
   trap. No misaligned atomicity PMA is configured by this test. LR.W with a
   nonzero rs2 encoding also executes. Validate alignment and LR encoding
   before bus side effects, covering SC and Zabha halfwords as well. For
   alignment, use load causes 4/5 or store causes 6/7 as appropriate to the
   chosen policy; `mtval` must retain the guest address. The published bus
   primitive contract currently promises naturally aligned addresses.

4. **Locally failing SC skips memory permission checks (lines 1850–1855).**
   SC.W with no reservation, aimed at unmapped `0x10000`, retires with rd=1
   and no trap. The bus is never called. The current fast-path contract needs
   a way to validate a failing SC without writing memory, while preserving
   compatibility and ensuring local failure can never become a successful
   default-bus store. Please propose the precise bus API adjustment before
   V-32 implements its concurrent bus. Do not simulate validation by an
   ordinary read or write to MMIO.

The [atomic specification](https://docs.riscv.org/reference/isa/v20260120/unpriv/a-st-ext.html)
requires LR/SC alignment and permission checks even for retiring failed SCs;
AMO alignment has an optional PMA exception. LR reservations cover all four
bytes of an RV32 word, so overlapping writes matter, not just equal starts.

5. **Clarify faulting-SC reservation cleanup.** When `tryScAndStore` throws,
   cause 7 and `mtval` are correct, but `state.reservationValid` remains true
   because cleanup follows the call. The new bus contract and local state
   need a consistent lifecycle on exceptions. Add a regression and document
   the chosen fault policy. This is an additional contract question, separate
   from the demonstrated permission-check bypass above.

## Corrections to the multi-hart plan owned by V-32

The core/bus ownership split is appropriate. One shared monitor and two stable
hart IDs are a reasonable first implementation; the following details must be
included before treating the handoff as implementable:

- Forward context **and atomic primitives** through every wrapper. The IOP
  path is `BootBus -> MMIOBus -> SystemRAMBus`; upstream MMIOBus explicitly
  discards context and inherits non-atomic defaults. Fixing only BootBus and
  ApMemoryBus is insufficient. V-32 can supply its own crossbar or route RAM
  directly while leaving ordinary device accesses with the existing router.
  Include signed LB/LH overloads, whose defaults also discard context.
- Check AP permissions and the full access width before translation; delegate
  the entire atomic operation once to shared physical RAM. Track reservations
  by translated physical address, and invalidate on overlapping byte,
  halfword, word, and AMO writes. Example: LR.W at physical P must be
  invalidated by SB at P+1. Exact starting-address equality is insufficient.
- Keep the LR read/registration, each RMW, and SC check/store/invalidation
  indivisible under the same monitor as plain accesses. The existing
  `MemoryBus.super.atomicRmw` can provide the operation arithmetic inside that
  monitor, avoiding local ISA reimplementation.
- Define reservation cleanup on reset, halt/reload, and MPU remapping.
  Route concurrent host/DMA writes through the same coordination, or require
  quiescence. Raw `ramSegment()` access and bulk copies bypass a bus lock.
- The monitor gives strong ordering for participating RAM accesses. It does
  not by itself establish all device/MMIO/VRAM publication semantics. Document
  the RAM and I/O ordering policies and verify mailbox payload publication.
- **injectInterrupt is not inherently thread-safe.** Both it and the core
  modify ordinary `mip`/`extraflags` fields with read-modify-write operations.
  The authoritative Javadoc requires caller synchronization, contrary to the
  handoff and sibling `docs/EMULATOR_REPO_NOTES.md`. Prefer publishing device
  pending state safely and having the target hart apply it between step calls;
  avoid a sender directly mutating a concurrently executing target state.
  Correct the upstream handoff documentation; a core concurrency API change
  is not required merely to support this owner-thread approach.
- Set IOP privilege explicitly to M-mode during local integration. A freshly
  allocated state has zero extraflags, so the current IOP actually starts in
  U-mode despite the architectural intention. AP trap entry remains on the
  AP; IOP notification/recovery needs a separate device/firmware convention.
- The current boot payload is published through volatile `apRunning` after
  IOP RAM stores, and the AP reads that flag before executing. Thus the notes
  overstate the lack of *any* existing visibility guarantee. Arbitrary live
  shared-memory traffic still needs the proposed synchronization.
- The assertion that a coarse lock cannot bottleneck a 40 MHz target is
  unsupported. Use it as a correctness baseline, then measure with existing
  benchmarks. No performance claim is established by these probes.

Required later tests include deterministic barrier-controlled store-between-LR
and-SC cases for every width and translated alias, denied access fault causes
and logical `mtval`, end-to-end AP/IOP context and atomic forwarding, payload
publication, and concurrent AMO totals. Repeated stress alone is insufficient.

## Reproduction

From this repository with Java 25:

```sh
java -cp /home/nate/.m2/repository/com/alienspacebunny/rv32emu-core/0.1.3-SNAPSHOT/rv32emu-core-0.1.3-SNAPSHOT.jar review/CoreFeatureProbe.java
```

The probe is outside Gradle source sets and changes no emulator behavior.
It prints the questionable cases and asserts the positive smoke checks; exit
zero means the probe ran, **not** that the processor passed conformance.
`mcause=-1` is a sentinel indicating that no trap changed it.

Observed results: U-mode MRET ended at PC=0x40/privilege=3/cause=-1; pending
MSIP/WFI remained at PC=4 with the second step returning 1; misaligned LR
reserved 0x101; misaligned AMOADD wrote 1 there; nonzero-rs2 LR succeeded;
faulting SC retained its local reservation; unreserved SC to an unmapped
address returned rd=1/cause=-1. All nine positive checks printed PASS.

## Resume condition

Send the processor findings to its implementing model, obtain fixes and
regressions plus the SC validation API decision, install the updated artifact,
and rerun these probes. Then resume local bus/privilege integration and the
synchronized mailbox slice. ROM access removal and compression remain later
tasks. No branch, commit, or cross-project modification was made by this review.
