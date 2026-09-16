# CPU integration acceptance — 2026-09-16

Accepted `rv32emu-core:0.1.5-SNAPSHOT` after reviewing
[CPU_INTEGRATION_RESPONSE_2.md](CPU_INTEGRATION_RESPONSE_2.md) and independently
rerunning both V-32 probes. No further CPU-project requests are outstanding
from these two review rounds. The sibling repository was not modified.

The user referred to `0.1.15-SNAPSHOT`; that coordinate is absent locally.
The response and installed artifact both identify `0.1.5-SNAPSHOT`, which is
the version tested and now selected in `build.gradle`.

## Verification

- Java 25.0.4.1; local jar SHA-256 matches the response:
  `623683e2530db715c95eda36e19c581fcf63616a4687c831bad5f43c4405a399`.
- `review/CoreFeatureProbe.java`: corrected original observations and all
  nine positive feature checks pass.
- `review/CoreResponseProbe.java`: **30 passed; 0 failed**, exit zero.
  Both misaligned reservation cleanup and interrupt reevaluation after CSR
  writes/MRET now meet the acceptance assertions.
- `./gradlew build --offline`: **51 tests passed**, zero failures/errors/skips,
  including 32 new integration cases. ROM and cartridge artifacts built.
  `git diff --check` is clean.
- The response's inert-entry policy is adequate: a stale bus reservation
  cannot authorize SC when the core-local flag is false; a new LR replaces
  the hart's entry. `checkAccess` remains side-effect-free. V-32 clears its
  bus reservations on reset, halt, payload replacement, and AP remapping.
- The upstream Maven gate was not rerun. These are focused integration
  checks, not complete ISA or floating-point conformance certification.

Reproduce against the accepted artifact:

```sh
J="$HOME/.m2/repository/com/alienspacebunny/rv32emu-core/0.1.5-SNAPSHOT/rv32emu-core-0.1.5-SNAPSHOT.jar"
java -cp "$J" review/CoreFeatureProbe.java
java -cp "$J" review/CoreResponseProbe.java
./gradlew build --offline
```

## Completed V-32 integration

- One shared-RAM monitor covers scalar accesses, LR read/registration,
  atomic RMW, SC check/store, and overlapping-write invalidation. Arithmetic
  stays in the CPU library. Host payload replacement uses the same monitor.
- AP translation and BootBus forward context, signed loads, atomic
  primitives, and `checkAccess`; the library's MMIO forwarding is retained.
  Atomic device accesses are rejected before reaching hardware hooks.
- AP hart 0 resets in U-mode; IOP hart 1 resets explicitly in M-mode.
  Reset clears CPU state including WFI and the local reservation.
- AP batches and AP crossbar operations coordinate with syscon remapping
  and halt. Bus entries are invalidated even if a halt/release or remap/back
  occurs between two AP batches without the AP observing the intermediate
  configuration. IOP RAM accesses can run concurrently with AP execution.
- A synchronized mailbox at `0xFF120000` provides one inbox per hart,
  explicit acknowledgement, and MEIP delivery. Each hart samples device
  pending state on its own execution thread, including after an ACK before
  a same-batch MRET. No sender mutates another hart's CPU state.
- AP traps enter M-mode on the AP; firmware may report the cause through
  the IOP inbox. A guest test exercises ECALL → AP handler → mailbox → IOP
  interrupt. No automatic trap transfer or ROM-access removal was added.

The Gradle gate covers deterministic cross-thread LR/store/SC tests for all
store widths and AMOs, translated aliases in both hart directions, successful
LR/SC with aligned and unaligned physical windows, concurrent guest AMO totals,
permission faults and logical `mtval`, signed-load context forwarding,
reservation lifecycle, mailbox payload publication, and guest wake/ACK/MRET.
See [V32_ARCHITECTURE.md](V32_ARCHITECTURE.md) for the mailbox ABI, ordering,
and host quiescence requirements. Native-image build and performance benchmarks
were not rerun; no throughput claim is made for the correctness-first locks.
