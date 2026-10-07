# Session Checkpoint — 2026-10-07

## Current state

`main` is **`0.1.6-SNAPSHOT`**. The public API remains unfrozen and publishing remains
on hold. The first real release target is **`0.2.0`**; no release is authorized.

The CPU feature plan's **Phases 1–5 are complete**, including the compressed F
instructions. P0–P7 fixes, public API Javadoc, compliance-test Layers 1–2, and the
C1–C7 cleanup pass are also complete. Landing commits and design details remain in
[FEATURE_REQUEST_PLAN.md](docs/FEATURE_REQUEST_PLAN.md); completed session notes and
superseded handoffs are preserved in the
[checkpoint archive](docs/archive/CHECKPOINT_PRE_COMPACTION_2026-10-06.md).

**V-32 integration exchange closed:** V-32 accepted `0.1.5-SNAPSHOT` on 2026-09-16,
with both probes passing (`CoreResponseProbe` 30/30), its Gradle gate green (51
tests), and no outstanding CPU-project requests. Its acceptance documents completed
shared-RAM LR/SC, AP permission forwarding, two-hart reset/privilege, mailbox/MEIP,
and guest ECALL integration. See the
[acceptance](docs/archive/CPU_INTEGRATION_ACCEPTANCE.md); its `0.1.15-SNAPSHOT`
reference is a typo for `0.1.5`. That acceptance did not rerun this repo's Maven gate
or native-image/performance checks.

Subsequent build work bumped this repo to `0.1.6-SNAPSHOT` and moved shared build
configuration to the published **`alienspacebunny-parent:0.1.1`**. Release credentials,
signing, inherited Central deployment configuration, and the manual release workflow
are wired. They do not lift the release hold; there is deliberately no push/PR CI.

## Resume / pending decisions

1. **Nate must decide whether V-32's acceptance satisfies the integration condition
   for lifting the hold.** If agreed, the next step is an explicit API-freeze review
   against the real consumer before any release. See
   [RELEASE_TODO.md](RELEASE_TODO.md) for the remaining decisions and
   [RELEASING.md](docs/RELEASING.md) for current mechanics.
2. Central deployment is still untested for rv32emu; a smoke test of the published
   artifact remains open. Never start the manual release workflow without Nate's
   explicit release request.
3. No further feature work is authorized. D remains deferred (`hasD` only advertises
   the misa bit; no D decode). [TEST_PLAN.md](docs/TEST_PLAN.md) now audits existing
   coverage and proposes the remaining system scenarios. No new tests were added.
   [RV64_FEASIBILITY_NOTES.md](docs/RV64_FEASIBILITY_NOTES.md)
   is speculative, with no decision or scheduled implementation.

## Constraints for future work

- Follow [AGENTS.md](AGENTS.md) for gates, changelog, version bumps, local install,
  archiving, and commit/push requirements. Shared formatter/tooling versions belong
  in `../alienspacebunny-build`; this repo consumes its published parent.
- Core stays independent of CLI. `step()`'s long body and eight-argument signature
  are deliberate; trap dispatch uses an internal cause-plus-one encoding.
- [API.md](docs/API.md) summarizes contracts; Javadoc is authoritative. Multi-hart
  buses own translated-address reservation coordination, atomicity, sub-word lock
  granularity, and ordering stronger than the single-hart defaults. `aq`/`rl` is
  outside `AccessContext`'s scope. Cross-thread interrupt injection requires caller
  synchronization. AP-to-IOP trap notification belongs to the emulator's devices.
- F directed rounding needs the exact residual alongside the double approximation;
  a double result alone loses boundary information. Preserve independent
  differential tests when changing that arithmetic.

The 2026-10-07 cleanup refreshed README/API/release guidance, condensed AGENTS.md,
and archived completed plans. Superseded application architecture and bus-handoff
notes moved to `../emulator/docs/archive/` with `RV32EMU_`-prefixed filenames;
the documentation index records their locations (V-32 commit `371af30`). Public
Javadoc's compressed-F description was corrected. `clean verify` passed; local
Markdown link targets were checked. Runtime behavior and versions are unchanged.
