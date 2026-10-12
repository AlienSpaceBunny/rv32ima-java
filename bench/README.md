# rv32emu-bench

Unpublished JVM/Native Image throughput harness for `rv32emu-core`. It is never
deployed. Its tests run in `./mvnw verify`, but timing runs only when invoked by hand.

The core stays memory-backend-agnostic. The bench-only `MemoryBus` classes here
size backend costs so a consumer can choose its own storage. They are not
recommendations for the core.

## Workloads

Synthetic guest programs are generated in Java (`Asm`); the standard ones are prebuilt
images from [`guest/`](guest/README.md), so running needs no cross-compiler. Every invocation checks an analytical instruction count, final PC, the absence
of traps, and an independent Java reference model of registers and memory.

| Workload | What it exercises |
| --- | --- |
| `reg-ima` | Register ALU/branch loop, base ISA (one word fetch per instruction) |
| `reg-ap` | Same 32-bit program on V-32's AP profile (C enabled: halfword probe + word fetch) |
| `regc-ap` | Same loop, mostly compressed encodings |
| `reg-ap-step1` | `reg-ap` with `step(count=1)`, like V-32's AP attempt loop |
| `copy4k-ap`, `copy6m-ap` | Word copy + checksum, small and large working sets |
| `mix-ap` | LB/LH/LW/SB/SH/SW, AMOADD.W and an LR.W/SC.W pair |
| `fp-ap` | FLW/FSW, FMADD.S, FADD.S, FMUL.S (checked against Java float and `Math.fma`) |
| `spread-ap` | Calls 256 mixed 16/32-bit functions spread over 64 KiB, in a seeded LCG order (synthetic decoded-cache stress) |
| `collide-ap` | Calls eight functions 64 KiB apart round-robin, so they collide in any direct-mapped decoded cache (synthetic worst case) |
| `coremark-c`, `coremark-noc` | EEMBC CoreMark (2K performance seeds, 10 iterations), with/without C; see [`guest/`](guest/README.md) |
| `fpkernels-c`, `fpkernels-noc` | Single-precision sgemm, n-body (`sqrtf`, division), Horner (`fmaf`), with/without C |
| `host-read6m`, `host-copy4k` | Host-only loops through the bus's context overloads, no core |

## Buses

| Bus | Backing |
| --- | --- |
| `bytearray` | Primary neutral backend: `byte[]`, manual little-endian assembly |
| `varhandle` | `byte[]` through byte-array view VarHandles |
| `heapsegment` | `MemorySegment.ofArray(byte[])` |
| `ffm` | Core `FFMMemoryBus` (shared-arena segment) |
| `directbuffer` | LE `ByteBuffer` view of a shared-arena segment |
| `heapbuffer` | LE heap `ByteBuffer` |
| `wrapper` | Fully forwarding wrapper around `bytearray` |
| `mmio` | Core `MMIOBus` over `bytearray`, with one hook outside RAM |

`PolyMain` makes every bus reachable, so a native image sees polymorphic core
call sites, like an embedder with several bus classes. Each `Mono*Main` makes
exactly one bus reachable, which is the closed-world devirtualized control.

## Running

```bash
./mvnw -pl bench -am package
mkdir -p bench/out/jars && cp bench/target/rv32emu-bench-*-SNAPSHOT.jar bench/out/jars/NAME.jar
BENCH_JAR=bench/out/jars/NAME.jar bench/build-native.sh bench/out/native-NAME   # eight images
bench/run_matrix.py --output bench/out/results/NAME \
    --native-dir bench/out/native-NAME --jar bench/out/jars/NAME.jar
```

Each process warms all workloads for at least 3 rounds and 10 s of timed
execution, then takes 3 measured rounds. The second fork reverses both the
process order and the workload order. The output keeps raw CSVs, stderr,
argv/exit codes and hashes (`manifest.json`), and `summary.csv` holds the
median and full range per cell. Ranges show noise only; they are not confidence
intervals. Don't build or run anything else while measuring. Processes are pinned
with `taskset -c 2,3` by default, one P-core's two hyperthreads on the reference
i9-13950HX. Unpinned runs mix P- and E-cores and give bimodal, roughly 2x ranges.
Everything under `bench/out/` is git-ignored and survives `mvn clean`.

`--decoded-cache N` gives every guest core a decoded instruction cache of N slots,
covering its program and flushed whenever the program is reloaded; `--cache-stats`
adds the cache counters as `cache-stats` CSV rows on stderr. `run_matrix.py
--decoded-cache 256,1024` adds `<label>-dc<N>` cells for the `jvm`, `mono`, `poly`
and `poly-o3` images.

`--attempts N` runs every guest workload through `stepAttempts` with N attempts
per call instead of `step` batches, with V-32's AP shape as the controller: a
volatile mailbox read into `mip` before and after each attempt and a volatile stop
check. With it, every workload is attempt-at-a-time, so `reg-ap-step1` is the same
as `reg-ap`. Adding `--step-loop` runs the same attempts as `step(count=1)` calls between the
same callbacks, in units of N: V-32's AP loop before `stepAttempts`. `run_matrix.py --attempts
1061,65536 --step-loop 1061` adds `<label>-att<N>` and `<label>-loop<N>` cells for the `jvm`,
`mono`, `poly` and `poly-o3` images and their `-dc<N>` cells.

For one ad-hoc run, use `java -jar bench/target/rv32emu-bench-*.jar --bus bytearray`
(see `Harness` for options). `--skew 1` must make every workload fail; that is the
negative check.
