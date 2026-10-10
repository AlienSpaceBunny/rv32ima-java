# Bench guest programs

Prebuilt flat images in `../src/main/resources/com/alienspacebunny/bench/guest/`, run by
`Workloads.ImageWorkload`. Each is built twice: with C (`rv32imafc_zicsr_zba_zbb`, matching
V-32's AP profile, run on the AP core profile) and without C (`rv32imaf_zicsr_zba_zbb`, run on
the AP profile minus C), to isolate compressed-instruction fetch and expansion costs. Both
builds execute the same number of instructions; only encoding and size differ.

| Image | What | Checked against |
| --- | --- | --- |
| `coremark-*` | EEMBC CoreMark, standard 2K performance-run seeds, 10 iterations | CoreMark's own known list/matrix/state CRCs, zero errors, pinned final CRC (identical for both builds) |
| `fpkernels-*` | `fpkernels.c`: 48×48 sgemm, 8-body n-body with `sqrtf`/division, degree-8 Horner with `fmaf` | `Workloads.FpKernelModel`, a Java reference of the same operations |

Instruction counts and CoreMark's final CRC are pinned in `expected.properties`; rebuilding the
images means re-pinning them (an unpinned count of `0` makes the harness report the actual one).

**CoreMark.** The benchmark sources are EEMBC's, Apache-2.0
(`LICENSE-coremark.md`, which also contains EEMBC's trademark agreement). They are fetched
unmodified at a pinned commit by `fetch-coremark.sh`, not vendored. Only the port layer in
`coremark-port/`, derived from upstream's `barebones` port, is kept here. The results are not
compliant CoreMark scores and must not be reported as such: iterations are fixed, run time is
far below the reporting rules' minimum, and nothing is printed.

## Building

Requires clang/lld with the riscv32 target and `llvm-objcopy`:

```bash
bench/guest/fetch-coremark.sh          # into bench/out/ext/coremark (git-ignored)
make -C bench/guest clean all          # ELFs in bench/out/guest-build, .bin into resources
```

Memory map (guest addresses): image at 0 (must end below `0x100000`), stack top `0xFF0000`,
result block `0xFF8000` (word 0 = completion magic, 1 = `main`'s return value, 2 = address of
the final `WFI`, 3+ = program results).
