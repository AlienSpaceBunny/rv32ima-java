# Decoded instruction cache: coherence protocol (for V-32 confirmation)

Drafted 2026-10-10 after V-32's [advisory review](DECODED_ICACHE_ADVISORY_REVIEW.md) and Nate's
decisions. **V-32: please confirm or correct each item under [Confirm](#confirm).** The overall
design is in [DECODED_ICACHE_PLAN.md](DECODED_ICACHE_PLAN.md). Implementation proceeds on a
branch in parallel, because nothing here changes the default-off behaviour.

## Decisions (Nate)

- **Coherence is `FENCE.I` plus an embedder flush.** The core does not check its own stores
  against the cache, and there is no cross-thread invalidation protocol. V-32 will adopt
  `FENCE.I` where code is written.
- **Every MMU mapping change flushes fully.** Mapping changes are IOP-initiated, happen with
  the AP halted, and are rare: expected at OS-driven process startup.
- **There is no preset performance bar.** The sizing results go to Nate, who decides.

## The protocol

**Rule 1: a guest that writes code executes `FENCE.I` on the hart that will run it,** before
running it. This applies whether the code was written by the same hart, another hart, the host
on the guest's behalf, or DMA. It follows RISC-V Zifencei: `FENCE.I` is local to the executing
hart, so the IOP's `FENCE.I` does not affect the AP's cache. Until the executing hart fences,
whether it runs old or new code is unspecified, as on hardware.

**Rule 2: the embedder flushes a hart's cache, while that hart is not inside `step()`, whenever
code or code mapping changes behind the guest's back.** The core cannot see host writes or
V-32's MMU. For V-32, that means:

- an AP mapping change, including a base change that leaves the limit unchanged
- payload load, reset and relaunch (`EmulatorMachine.loadPayload`, `IOProcessor.loadProgram`)
- host-side patches such as debugger breakpoints
- an XIP image replacement or remap, when XIP becomes an execution path

**Rule 3: the core flushes on its own when it cannot prove an entry is still valid.** It does so
on `FENCE.I`, and at `step()` entry when the bus instance, the `[ramOffset, ramSize)` window,
the cacheable ranges or the hart identity differ from the previous call. This is a safety net,
not a V-32 obligation.

**Rule 4: a hit never weakens fetch checks the core performs today.** The coarse window bound
and alignment are still checked on every instruction. The tag includes privilege, so an entry
filled in M-mode never hits in U-mode. Faulting fetches are never cached. Only fetches lying
entirely inside a declared cacheable range are cached, so MMIO and other side-effecting fetches
reach the bus every time. Finer-grained permission changes are covered by Rule 2.

**Threading.** The cache belongs to one core and is touched only on that core's calling thread.
The flush API is called from that thread between `step()` calls, or while the hart is halted
and the embedder has established happens-before with its thread, for example through the
monitor V-32 already uses to halt and release the AP. Calling it concurrently with `step()` is
outside the contract. This removes the write-versus-fill and lock-ordering concerns in the
review.

## How V-32's cases map onto the protocol

| Case | Writer | Visibility to the executing hart |
| --- | --- | --- |
| Payload load or relaunch | Host, AP halted | Rule 2 at reset |
| AP system call: IOP loads code into AP RAM | IOP | Rule 1: the AP's return path executes `FENCE.I` before jumping |
| AP decompresses code from ROM or disk | AP | Rule 1 after decompression |
| IOP decompresses or copies code into AP RAM (`ROM_COPY` and similar) | IOP | Rule 1 on the AP |
| DMA into RAM, if ever added | Device | Rule 1 after completion |
| AP mapping change at process startup | IOP, AP halted | Rule 2 |
| XIP ROM | Never written | Rule 2 only on remap or replacement |
| Host debugger patch | Host | Rule 2 |
| IOP boot RAM (`loadProgram`) and IOP overlays | Host or IOP | Rule 2 at load; Rule 1 for IOP overlays |
| Process switch without a mapping change | — | Nothing to do: logical PCs keep their backing memory |
| Core called with a different bus, window or hart | — | Rule 3 |

## The follow-up's three code-publication cases

The [follow-up](CPU_ARCHITECTURE_REVIEW_FOLLOWUP.md) separates three cases.

1. **Immutable XIP ROM:** explicitly cacheable populated ranges. Hart permissions are kept by
   Rule 4, and image replacement or remap flushes by Rule 2.
2. **Load or decompress before execution:** the writer completes its writes while the target
   hart is quiescent, the embedder flushes (Rule 2), and then publishes the launch. A whole-cache
   flush is cheap at the proposed sizes, so no ranged flush or per-byte notification is needed.
3. **Overwriting code while execution continues:** agreed that invalidation alone does not
   make partially written code safe. The publication handshake is the guest's own: the system
   call return, a mailbox ACK or a completion flag tells the executing hart the writes are
   done. That hart then executes `FENCE.I` (Rule 1) before jumping. A guest needs this
   handshake whether or not a cache exists, because it must not run half-loaded code anyway.
   `FENCE.I` adds only the invalidation, on the executing hart's own thread, after
   publication. So nothing races a fill, and no lock is taken under the RAM monitor.

**Why this is explicit, not silent.** The cache is off by default, and with it off nothing
changes for existing guests. V-32 opts in only after its firmware fences. To catch a missing
`FENCE.I` before then, the implementation will offer a **check mode**: every hit also fetches
through the bus and compares, and a mismatch is reported as a stale-code error with the PC.
It is meant for V-32's tests, not for production timing.

## Review concerns this resolves, and those it does not

**Resolved by the protocol:**

- **Concurrent invalidation, write-versus-fill races and lock ordering:** there is no
  cross-thread invalidation.
- **Aliases and store coverage:** the core no longer infers code changes from stores, so
  physical-to-logical mapping, odd-address and straddling writes, FP stores and AMOs need no
  special handling.

**Kept as requirements of the implementation** (see the plan):

- Exact hook `ir`, instruction length and trap values, including for compressed code.
- Predecoding encoding fields only, never conclusions that depend on privilege or CSR/FP state.
- Explicit cacheable ranges that cover every fetched byte.
- The default-off loop unchanged; a native-image size report; no second ISA implementation
  (shared execution helpers).
- Stale-hit tests: warm an entry, then revoke access, remap, change the bus or state, write
  through an alias, and confirm `FENCE.I` and the flush take effect.

## What V-32 would need to change at adoption

1. Add `FENCE.I` to the AP's system-call return path for code-loading calls, to any
   decompressor or loader, and to IOP overlay loading if it exists.
2. Build firmware with Zifencei in `-march`, for example `rv32imac_zicsr_zifencei`, or the
   equivalent for the AP profile.
3. Call the core's flush at the Rule 2 boundaries.
4. Choose a capacity per hart and declare cacheable ranges, AP RAM at first.

Until then, the cache is off and nothing changes. `FENCE.I` already executes as a no-op, so
firmware can add it before adoption.

## Confirm

1. Rules 1–4 and the threading rule are acceptable for the AP and the IOP.
2. Every V-32 mapping change, payload load, reset or relaunch, and host code patch happens with
   the affected hart outside `step()`, so a Rule 2 flush is safe at each.
3. The case table is complete. Are there other code-write paths, such as a monitor, an
   interpreter or a guest JIT?
4. The firmware and toolchain changes under adoption are acceptable.
5. AP RAM is the right initial cacheable range, with XIP later.
6. Anything here conflicts with the performance test V-32 is running now.
