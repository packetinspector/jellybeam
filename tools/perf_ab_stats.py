#!/usr/bin/env python3
"""Summarise tools/perf-ab.sh CSVs: median (q1-q3, n) per metric/build/order and a verdict.

Usage: python3 -I tools/perf_ab_stats.py RESULTS.csv [MORE.csv ...]
CSV columns: order,round,build,metric,run,value_ms,note   (order = AB or BA; build = A or B)
Dropped rows: run 0 (warm-up), blank value, or a note naming timeout/notHome/noForegroundMark/missing-mark.
Verdict: "B slower: m1, m2" / "B faster: ..." only when the medians differ with non-overlapping
IQRs (q3 of one build below q1 of the other) in BOTH orders, same direction; else "unchanged".
"""
import csv
import re
import statistics
import sys

METRICS = ["cold", "ttff", "dwell", "quick"]
DROP = re.compile(r"timeout|notHome|noForegroundMark|missing-mark")
ORDERS = [("AB", "A/B/A/B"), ("BA", "B/A/B/A (reversed)")]


def load(paths):
    rows = []
    for p in paths:
        with open(p, newline="") as f:
            for r in csv.DictReader(f):
                if r["run"] in ("0", "") or not r["value_ms"] or DROP.search(r.get("note") or ""):
                    continue
                rows.append((r["order"], r["build"], r["metric"], float(r["value_ms"])))
    return rows


def summary(vals):
    if not vals:
        return None
    if len(vals) < 2:
        return (vals[0], vals[0], vals[0], 1)
    q = statistics.quantiles(vals, n=4, method="exclusive")
    return (statistics.median(vals), q[0], q[2], len(vals))


def cell(s):
    return "-" if s is None else "%d (%d-%d, n%d)" % (round(s[0]), round(s[1]), round(s[2]), s[3])


def pick(rows, order, build, metric):
    return summary([v for o, b, m, v in rows if b == build and m == metric and (order is None or o == order)])


def direction(a, b):
    """+1 B slower, -1 B faster, 0 inside the IQRs or no data."""
    if a is None or b is None:
        return 0
    if b[1] > a[2] and b[0] > a[0]:
        return 1
    if b[2] < a[1] and b[0] < a[0]:
        return -1
    return 0


def main(paths):
    rows = load(paths)
    metrics = [m for m in METRICS if any(r[2] == m for r in rows)]
    if not metrics:
        print("no usable rows")
        return 1
    print("| run | " + " | ".join("%s %s" % (b, m) for m in metrics for b in "AB") + " |")
    print("|" + "---|" * (1 + 2 * len(metrics)))
    for order, label in ORDERS + [(None, "all rounds")]:
        if order and not any(r[0] == order for r in rows):
            continue
        print("| %s | " % label + " | ".join(cell(pick(rows, order, b, m)) for m in metrics for b in "AB") + " |")
    slower, faster = [], []
    for m in metrics:
        d = [direction(pick(rows, o, "A", m), pick(rows, o, "B", m)) for o, _ in ORDERS]
        if d[0] == d[1] == 1:
            slower.append(m)
        elif d[0] == d[1] == -1:
            faster.append(m)
    parts = (["B slower: " + ", ".join(slower)] if slower else []) + (["B faster: " + ", ".join(faster)] if faster else [])
    print("\nVerdict: " + ("; ".join(parts) if parts else "unchanged"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]) if len(sys.argv) > 1 else print(__doc__) or 2)
