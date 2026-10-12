import csv, glob, os, sys, statistics as st
d = sys.argv[1]; base = sys.argv[2]; variants = sys.argv[3].split(',')
rows = {}
for f in glob.glob(os.path.join(d, '*.csv')):
    for r in csv.DictReader(open(f)):
        if r['phase'] != 'measured': continue
        v, mode = r['label'].split('/')
        rows.setdefault((mode, r['workload'], v), []).append(int(r['work']) / int(r['nanos']) * 1e3)
for mode in sorted({m for m, _, _ in rows}):
    print(f"== {mode}: {base} MIPS, then each variant / {base} (median of all samples, [min-max])")
    for w in sorted({w for m, w, _ in rows if m == mode}):
        b = st.median(rows[(mode, w, base)])
        cells = []
        for v in variants:
            x = rows[(mode, w, v)]
            cells.append(f"{v} {st.median(x)/b:.3f} [{min(x)/b:.2f}-{max(x)/b:.2f}]")
        print(f"  {w:14} {b:7.1f}  " + "  ".join(cells))
