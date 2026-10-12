#!/usr/bin/env python3
"""Runs the bench matrix: one process per (image, bus), two forks, the second in reversed
process and workload order. Refuses an existing output directory, keeps every raw CSV and
stderr log, and writes summary.csv (median and full range per cell) plus manifest.json.

Usage:
  bench/run_matrix.py --output DIR --native-dir DIR [--java PATH] [--jar PATH]
                      [--decoded-cache N,..] [--attempts N,..] [--step-loop N]
                      [--warmup-seconds 10] [--samples 3] [--forks 2]
  bench/run_matrix.py --summarize DIR      # rebuild summary.csv from raw output
"""

import argparse
import csv
import hashlib
import json
import os
import pathlib
import statistics
import subprocess
import sys
import time

ALL_BUSES = ["bytearray", "varhandle", "heapsegment", "ffm", "directbuffer", "heapbuffer", "wrapper", "mmio",
             "router"]
FLAG_BUSES = ["bytearray", "ffm"]
GRAAL_BUSES = ["bytearray", "router"]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cells(args):
    """(image label, argv prefix, bus) for every process in one fork."""
    java = [args.java, "-jar", args.jar]
    nd = pathlib.Path(args.native_dir)
    out = [("jvm", java, b) for b in ALL_BUSES]
    if args.graal_java:
        # Same jar on GraalVM's JDK: Graal JIT (default) vs C2. Separates "no runtime profile"
        # (AOT) from "Graal code generation" as causes of the native gap.
        out += [("jvm-graaljit", [args.graal_java, "-jar", args.jar], b) for b in GRAAL_BUSES]
        out += [("jvm-graalc2", [args.graal_java, "-XX:-UseJVMCICompiler", "-jar", args.jar], b)
                for b in GRAAL_BUSES]
    out += [("poly", [str(nd / "poly")], b) for b in ALL_BUSES]
    out += [("mono", [str(nd / "mono-bytearray")], "bytearray")]
    out += [("mono", [str(nd / "mono-ffm")], "ffm")]
    out += [("mono", [str(nd / "mono-directbuffer")], "directbuffer")]
    out += [("mono", [str(nd / "mono-router")], "router")]
    if not args.no_flags:
        out += [("poly-o3", [str(nd / "poly-o3")], b) for b in FLAG_BUSES]
        out += [("poly-v2", [str(nd / "poly-v2")], b) for b in FLAG_BUSES]
        out += [("poly-v3", [str(nd / "poly-v3")], b) for b in FLAG_BUSES]
    if args.decoded_cache:
        # The same binaries with a decoded instruction cache of each listed size.
        dc = []
        for slots in args.decoded_cache.split(","):
            for label, prefix, bus in out:
                if label in ("jvm", "mono", "poly", "poly-o3"):
                    dc.append((f"{label}-dc{slots}", prefix + ["--decoded-cache", slots], bus))
        out += dc
    # Attempt-at-a-time variants of those cells, cached or not: stepAttempts at each budget, and
    # V-32's former loop of step(count=1) calls in units of N.
    modes = [(f"att{n}", ["--attempts", n]) for n in args.attempts.split(",")] if args.attempts else []
    if args.step_loop:
        modes.append((f"loop{args.step_loop}", ["--attempts", args.step_loop, "--step-loop"]))
    out += [(f"{label}-{suffix}", prefix + extra, bus)
            for suffix, extra in modes
            for label, prefix, bus in list(out)
            if label.split("-dc")[0] in ("jvm", "mono", "poly", "poly-o3")]
    if args.only:
        labels = set(args.only.split(","))
        out = [c for c in out if c[0] in labels]
    if args.buses:
        keep = set(args.buses.split(","))
        out = [c for c in out if c[2] in keep]
    return out


def run(args):
    root = pathlib.Path(args.output)
    if root.exists():
        sys.exit(f"refusing existing output directory {root}")
    (root / "raw").mkdir(parents=True)
    plan = cells(args)
    manifest = {
        "started": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "git_rev": subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip(),
        "git_dirty": bool(subprocess.run(["git", "status", "--porcelain", "--", "core", "bench"],
                                         capture_output=True, text=True).stdout.strip()),
        "jar": args.jar,
        "jar_sha256": sha256(args.jar),
        "java": subprocess.run([args.java, "-version"], capture_output=True, text=True).stderr.strip(),
        "native_images": {},
        "warmup_seconds": args.warmup_seconds,
        "samples_per_fork": args.samples,
        "forks": args.forks,
        "cpus": args.cpus,
        "processes": [],
    }
    for label, prefix, _ in plan:
        if not label.startswith("jvm"):
            log = pathlib.Path(prefix[0] + ".build.log")
            applied = [l.strip(" -\n") for l in log.read_text().splitlines() if "optimization level" in l] \
                if log.exists() else []
            manifest["native_images"][prefix[0]] = {"sha256": sha256(prefix[0]),
                                                    "compiler": applied[0] if applied else "unknown"}
    for fork in range(1, args.forks + 1):
        order = plan if fork % 2 == 1 else list(reversed(plan))
        for label, prefix, bus in order:
            name = f"f{fork}-{label}-{bus}"
            argv = prefix + ["--bus", bus, "--label", label, "--warmup-seconds", str(args.warmup_seconds),
                             "--samples", str(args.samples)]
            if fork % 2 == 0:
                argv.append("--reverse")
            if args.workloads:
                argv += ["--workloads", args.workloads]
            if args.cpus:
                argv = ["taskset", "-c", args.cpus] + argv
            print(f"[{time.strftime('%H:%M:%S')}] {name}", flush=True)
            start = time.monotonic()
            with open(root / "raw" / f"{name}.csv", "w") as out, open(root / "raw" / f"{name}.err", "w") as err:
                code = subprocess.run(argv, stdout=out, stderr=err).returncode
            manifest["processes"].append({"name": name, "argv": argv, "exit": code,
                                          "seconds": round(time.monotonic() - start, 1)})
            if code != 0:
                (root / "manifest.json").write_text(json.dumps(manifest, indent=2))
                sys.exit(f"{name} exited {code}; see raw/{name}.err")
    manifest["finished"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    manifest["complete"] = True
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2))
    summarize(root)


def summarize(root):
    root = pathlib.Path(root)
    groups = {}
    for path in sorted((root / "raw").glob("*.csv")):
        with open(path) as f:
            for row in csv.DictReader(f):
                if row["phase"] != "measured":
                    continue
                key = (row["label"], row["runtime"], row["bus"], row["workload"])
                groups.setdefault(key, []).append(row)
    with open(root / "summary.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["label", "runtime", "bus", "workload", "n", "median_ms", "min_ms", "max_ms", "work",
                    "mops_at_median", "alloc_bytes_per_work", "gc_count_median"])
        for key in sorted(groups):
            rows = groups[key]
            ms = [int(r["nanos"]) / 1e6 for r in rows]
            work = int(rows[0]["work"])
            med = statistics.median(ms)
            allocs = [int(r["alloc_bytes"]) for r in rows]
            alloc = statistics.median(allocs) / work if min(allocs) >= 0 else -1
            w.writerow([*key, len(rows), f"{med:.3f}", f"{min(ms):.3f}", f"{max(ms):.3f}", work,
                        f"{work / med / 1e3:.1f}", f"{alloc:.2f}",
                        statistics.median(int(r["gc_count"]) for r in rows)])
    print(f"wrote {root / 'summary.csv'}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--output")
    p.add_argument("--native-dir")
    p.add_argument("--java", default=os.path.expanduser("~/.jdks/corretto-25.0.4.1/bin/java"))
    p.add_argument("--jar")
    p.add_argument("--graal-java", help="GraalVM JDK java; adds jvm-graaljit / jvm-graalc2 cells")
    p.add_argument("--decoded-cache",
                   help="comma-separated slot counts; adds <label>-dc<N> cells for jvm, mono, poly, poly-o3")
    p.add_argument("--attempts",
                   help="comma-separated stepAttempts budgets; adds <label>-att<N> cells for jvm, mono, poly, poly-o3 "
                        "and their -dc<N> cells")
    p.add_argument("--step-loop",
                   help="budget N; adds <cell>-loop<N> cells running step(count=1) loops with the same callbacks")
    p.add_argument("--only", help="comma-separated image labels to run (e.g. jvm-graaljit,jvm-graalc2)")
    p.add_argument("--warmup-seconds", type=float, default=10)
    p.add_argument("--samples", type=int, default=3)
    p.add_argument("--forks", type=int, default=2)
    p.add_argument("--cpus", default="2,3",
                   help="taskset CPU list (default: one P-core's two hyperthreads on the i9-13950HX; "
                        "two CPUs keep JVM ergonomics on G1); '' disables pinning")
    p.add_argument("--workloads")
    p.add_argument("--buses", help="comma-separated subset of buses to run (all images)")
    p.add_argument("--no-flags", action="store_true", help="skip the -O3 / x86-64-v2 / x86-64-v3 images")
    p.add_argument("--summarize")
    args = p.parse_args()
    if args.summarize:
        summarize(args.summarize)
        return
    if not (args.output and args.native_dir and args.jar):
        p.error("--output, --native-dir and --jar are required")
    run(args)


if __name__ == "__main__":
    main()
