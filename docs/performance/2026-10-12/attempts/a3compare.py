"""Step A3 tables from three run_matrix.py result directories (HEAD, timer version, v0.1.8).

Usage: python3 a3compare.py HEAD_DIR TIMER_DIR V018_DIR > comparison.md
Each cell is a ratio of median throughputs (MIPS, or host words for host-*); brackets give the
numerator's min-max over the denominator's median. Above 1 means the numerator is faster.
"""
import csv
import pathlib
import sys

IMAGES = ["jvm", "mono", "poly"]


def load(d):
    out = {}
    with open(pathlib.Path(d) / "summary.csv") as f:
        for r in csv.DictReader(f):
            work = int(r["work"])
            out[(r["label"], r["workload"])] = {
                "med": work / float(r["median_ms"]) / 1e3,
                "lo": work / float(r["max_ms"]) / 1e3,
                "hi": work / float(r["min_ms"]) / 1e3,
                "alloc": float(r["alloc_bytes_per_work"]),
            }
    return out


def label(image, mode):
    return image if mode == "" else f"{image}-{mode}"


def ratio(num, den):
    if num is None or den is None:
        return "–"
    return f"{num['med'] / den['med']:.2f} [{num['lo'] / den['med']:.2f}–{num['hi'] / den['med']:.2f}]"


def table(title, note, cols, data):
    """cols: (header, (dataset, mode), (dataset, mode) or None). None = absolute MIPS."""
    print(f"## {title}\n\n{note}\n")
    for image in IMAGES:
        workloads = sorted({w for (l, w) in data["head"] if l == image})
        print(f"### {image}\n")
        print("| Workload | " + " | ".join(c[0] for c in cols) + " |")
        print("| --- |" + " --- |" * len(cols))
        for w in workloads:
            cells = []
            for _, (ds, mode), den in cols:
                num = data[ds].get((label(image, mode), w))
                if den is None:
                    cells.append(f"{num['med']:.1f}" if num else "–")
                else:
                    cells.append(ratio(num, data[den[0]].get((label(image, den[1]), w))))
            print(f"| `{w}` | " + " | ".join(cells) + " |")
        print()


def main():
    data = {"head": load(sys.argv[1]), "timer": load(sys.argv[2]), "v018": load(sys.argv[3])}
    att, loop, dc = "att1061", "loop1061", "dc4096"
    table("V-32's path against its former AP loop",
          "Cached `stepAttempts` at 1,061, V-32's current budget, on HEAD (V-32's chosen path, absolute MIPS first) against "
          "the timer version's `step(count=1)` loop with the same callbacks, cached and uncached.",
          [("HEAD cached attempts, MIPS", ("head", f"{dc}-{att}"), None),
           ("vs timer cached loop", ("head", f"{dc}-{att}"), ("timer", f"{dc}-{loop}")),
           ("vs timer uncached loop", ("head", f"{dc}-{att}"), ("timer", loop)),
           ("HEAD uncached attempts vs timer uncached loop", ("head", att), ("timer", loop))],
          data)
    table("Attempt modes on HEAD",
          "Each cell against HEAD's uncached `stepAttempts` at 1,061 (absolute MIPS first).",
          [("Uncached attempts, MIPS", ("head", att), None),
           ("Cached attempts", ("head", f"{dc}-{att}"), ("head", att)),
           ("Uncached loop", ("head", loop), ("head", att)),
           ("Cached loop", ("head", f"{dc}-{loop}"), ("head", att)),
           ("Uncached, budget 64", ("head", "att64"), ("head", att)),
           ("Cached, budget 64", ("head", f"{dc}-att64"), ("head", f"{dc}-{att}")),
           ("Uncached, budget 65,536", ("head", "att65536"), ("head", att)),
           ("Cached, budget 65,536", ("head", f"{dc}-att65536"), ("head", f"{dc}-{att}")),
           ("Cached batched `step`", ("head", dc), ("head", att))],
          data)
    table("HEAD against the timer version and 0.1.8",
          "Unchanged paths: `step` batches (`reg-ap-step1` is a bare `step(count=1)` loop) and the "
          "`step(count=1)` loop with callbacks.",
          [("Batched, vs timer", ("head", ""), ("timer", "")),
           ("Cached batched, vs timer", ("head", dc), ("timer", dc)),
           ("Loop, vs timer", ("head", loop), ("timer", loop)),
           ("Cached loop, vs timer", ("head", f"{dc}-{loop}"), ("timer", f"{dc}-{loop}")),
           ("Batched, vs 0.1.8", ("head", ""), ("v018", "")),
           ("Cached batched, vs 0.1.8", ("head", dc), ("v018", dc))],
          data)
    print("## Allocation (bytes per instruction, median)\n")
    print("Same-version pairs share a column header; `t:` marks the timer version.\n")
    cols = [("head", ""), ("timer", ""), ("head", dc), ("timer", dc), ("head", loop), ("timer", loop),
            ("head", f"{dc}-{loop}"), ("timer", f"{dc}-{loop}"), ("head", "att64"), ("head", f"{dc}-att64"),
            ("head", att), ("head", f"{dc}-{att}"), ("head", "att65536"), ("head", f"{dc}-att65536")]
    for image in IMAGES:
        print(f"### {image}\n")
        print("| Workload | " + " | ".join(("t:" if ds == "timer" else "") + (m or "batched") for ds, m in cols) + " |")
        print("| --- |" + " --- |" * len(cols))
        for w in sorted({w for (l, w) in data["head"] if l == image}):
            print(f"| `{w}` | " + " | ".join(f"{data[ds][(label(image, m), w)]['alloc']:.2f}" for ds, m in cols) + " |")
        print()

if __name__ == "__main__":
    main()
