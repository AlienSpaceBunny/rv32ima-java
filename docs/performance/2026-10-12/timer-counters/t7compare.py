#!/usr/bin/env python3
"""T7 comparison: HEAD over v0.1.8, median time ratio per cell (>1 = HEAD slower).

Usage: t7compare.py BASE_DIR NEW_DIR   (each holding a run_matrix.py summary.csv)
"""
import csv
import pathlib
import sys


def load(d):
    rows = {}
    with open(pathlib.Path(d) / "summary.csv") as f:
        for r in csv.DictReader(f):
            rows[(r["label"], r["bus"], r["workload"])] = (float(r["median_ms"]), float(r["min_ms"]), float(r["max_ms"]))
    return rows


base, new = load(sys.argv[1]), load(sys.argv[2])
by_label = {}
cells = []
for key, (ms, lo, hi) in sorted(new.items()):
    if key not in base:
        continue
    b, blo, bhi = base[key]
    ratio = ms / b
    overlap = lo <= bhi and blo <= hi
    by_label.setdefault(key[0], []).append((ratio, key[1], key[2], overlap))
    cells.append((ratio, key, overlap))

print("## Per image: HEAD / v0.1.8 median time ratio (>1 = HEAD slower)\n")
print("| image | cells | min | median | max | slowest cell | ranges overlap |")
print("| --- | --- | --- | --- | --- | --- | --- |")
for label, vals in by_label.items():
    vals.sort()
    rs = [v[0] for v in vals]
    worst = vals[-1]
    ov = sum(1 for v in vals if v[3])
    print(f"| {label} | {len(rs)} | {rs[0]:.3f} | {rs[len(rs) // 2]:.3f} | {rs[-1]:.3f} | "
          f"{worst[1]}/{worst[2]} | {ov}/{len(rs)} |")

print("\n## Per workload (all images and buses)\n")
by_wl = {}
for ratio, (label, bus, wl), _ in cells:
    by_wl.setdefault(wl, []).append(ratio)
print("| workload | cells | min | median | max |")
print("| --- | --- | --- | --- | --- |")
for wl, rs in sorted(by_wl.items()):
    rs.sort()
    print(f"| {wl} | {len(rs)} | {rs[0]:.3f} | {rs[len(rs) // 2]:.3f} | {rs[-1]:.3f} |")

print("\n## Cells at or above 1.05 with non-overlapping ranges\n")
slow = sorted((c for c in cells if c[0] >= 1.05 and not c[2]), reverse=True)
if not slow:
    print("None.")
else:
    print("| ratio | image | bus | workload |")
    print("| --- | --- | --- | --- |")
    for ratio, (label, bus, wl), _ in slow:
        print(f"| {ratio:.3f} | {label} | {bus} | {wl} |")
