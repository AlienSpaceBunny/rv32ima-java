"""Predecode comparison: HEAD summary vs an exp summary holding <label> and <label>-pd cells.

Usage: pdcompare.py HEAD_SUMMARY EXP_SUMMARY
Speedup columns are HEAD median / candidate median (>1 = faster). Marks: * = candidate range
entirely below the reference range, ! = entirely above, blank = ranges overlap.
"""
import csv
import sys

WL = ["coremark-c", "coremark-noc", "fpkernels-c", "fpkernels-noc", "reg-ap", "regc-ap", "copy6m-ap",
      "mix-ap", "reg-ap-step1"]
IMAGES = ["jvm", "mono", "poly", "poly-o3"]


def load(p):
    return {(r["label"], r["bus"], r["workload"]): r for r in csv.DictReader(open(p))}


def ratio(ref, cand):
    if ref is None or cand is None:
        return "      -  "
    a, b = float(ref["median_ms"]), float(cand["median_ms"])
    mark = "*" if float(cand["max_ms"]) < float(ref["min_ms"]) else (
        "!" if float(cand["min_ms"]) > float(ref["max_ms"]) else " ")
    return "%7.2fx%s" % (a / b, mark)


head, exp = load(sys.argv[1]), load(sys.argv[2])
cells = sorted({(k[0], k[1]) for k in head}, key=lambda c: (IMAGES.index(c[0]), c[1]))
for title, ref_of, cand_of in [
        ("pd vs HEAD", lambda c, w: head.get((c[0], c[1], w)), lambda c, w: exp.get((c[0] + "-pd", c[1], w))),
        ("pd vs exp plain", lambda c, w: exp.get((c[0], c[1], w)), lambda c, w: exp.get((c[0] + "-pd", c[1], w))),
        ("exp plain vs HEAD", lambda c, w: head.get((c[0], c[1], w)), lambda c, w: exp.get((c[0], c[1], w)))]:
    print(title)
    print("%-18s" % "cell" + "".join("%10s" % w[:9] for w in WL))
    for c in cells:
        print("%-18s" % (c[0] + "/" + c[1]) + "".join(" " + ratio(ref_of(c, w), cand_of(c, w)) for w in WL))
    print()
print("medians ms: HEAD / pd")
for c in cells:
    print("%-18s" % (c[0] + "/" + c[1]) + "".join(
        "%10s" % ("%.0f/%.0f" % (float(head[(c[0], c[1], w)]["median_ms"]),
                                 float(exp[(c[0] + "-pd", c[1], w)]["median_ms"])))
        if (c[0], c[1], w) in head and (c[0] + "-pd", c[1], w) in exp else "%10s" % "-" for w in WL))
