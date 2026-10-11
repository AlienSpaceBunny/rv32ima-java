#!/usr/bin/env python3
"""Decoded-cache comparison tables from run_matrix.py summaries.

Usage: dccompare.py RESULTS_DIR   (containing main, off, sizing, ceiling, stats)
"""
import csv
import pathlib
import sys

root = pathlib.Path(sys.argv[1])


def load(name):
    rows = {}
    with open(root / name / "summary.csv") as f:
        for r in csv.DictReader(f):
            rows[(r["label"], r["bus"], r["workload"])] = float(r["median_ms"])
    return rows


main, off, sizing, ceiling = (load(n) for n in ("main", "off", "sizing", "ceiling"))

print("## Default-off overhead: branch (cache off) / main, median time ratio (>1 = branch slower)\n")
by_label = {}
for (label, bus, wl), ms in sorted(off.items()):
    if (label, bus, wl) in main:
        by_label.setdefault(label, []).append((ms / main[(label, bus, wl)], bus, wl))
print("| image | cells | min | median | max | worst cell |")
print("| --- | --- | --- | --- | --- | --- |")
for label, vals in by_label.items():
    vals.sort()
    rs = [v[0] for v in vals]
    worst = vals[-1]
    print(f"| {label} | {len(rs)} | {rs[0]:.3f} | {rs[len(rs) // 2]:.3f} | {rs[-1]:.3f} | {worst[1]}/{worst[2]} |")

GUEST = ["reg-ima", "reg-ap", "regc-ap", "reg-ap-step1", "copy4k-ap", "copy6m-ap", "mix-ap", "fp-ap",
         "coremark-c", "coremark-noc", "fpkernels-c", "fpkernels-noc", "spread-ap", "collide-ap"]
SIZES = ["256", "1024", "4096", "16384"]
print("\n## Sizing: speedup over the same binary uncached (uncached median / cached median)\n")
print("Ceiling = archived whole-window tier-2 replica (exp/predecode) over this run's main cell.\n")
for image in ("jvm", "mono", "poly", "poly-o3"):
    for bus in ("bytearray", "router"):
        if (image, bus, "coremark-c") not in sizing:
            continue
        print(f"### {image} / {bus}\n")
        print("| workload | uncached ms | " + " | ".join(f"{s}" for s in SIZES) + " | vs main @4096 | ceiling |")
        print("| --- | --- | " + " | ".join("---" for _ in SIZES) + " | --- | --- |")
        for wl in GUEST:
            base = sizing.get((image, bus, wl))
            if base is None:
                continue
            cells = []
            for s in SIZES:
                ms = sizing.get((f"{image}-dc{s}", bus, wl))
                cells.append(f"{base / ms:.2f}" if ms else "-")
            m = main.get((image, bus, wl))
            c4k = sizing.get((f"{image}-dc4096", bus, wl))
            vs_main = f"{m / c4k:.2f}" if m and c4k else "-"
            ceil = ceiling.get((f"{image}-pd", bus, wl))
            ceil_s = f"{m / ceil:.2f}" if m and ceil else "-"
            print(f"| {wl} | {base:.1f} | " + " | ".join(cells) + f" | {vs_main} | {ceil_s} |")
        print()

print("## Instrumented counters (JVM, bytearray, one process per size: first + 1 warmup round + 1 sample)\n")
print("| slots | host bytes | workload | instructions | hit % | fill % | bypass % | delegated % | fills/invocation |")
print("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
for s in SIZES:
    path = root / "stats" / f"dc{s}.err"
    with open(path) as f:
        rows = [l.strip().split(",") for l in f if l.startswith("cache-stats,") and not l.startswith("cache-stats,label")]
    for r in rows:
        _, label, wl, slots, inv, instr, fills, byp, deleg, flushes = r
        instr, fills, byp, deleg, inv = int(instr), int(fills), int(byp), int(deleg), int(inv)
        hits = instr - fills - byp
        print(f"| {slots} | {16 * int(slots)} | {wl} | {instr} | {100 * hits / instr:.3f} | {100 * fills / instr:.4f} "
              f"| {100 * byp / instr:.4f} | {100 * deleg / instr:.2f} | {fills / inv:.0f} |")
