"""Recompute paired-run medians from the unedited server logs (Python stdlib only)."""
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

root = Path(__file__).resolve().parents[2]
groups = defaultdict(list)
runs = sys.argv[1:] or ["run11", "run12"]
pattern = re.compile(r"\[EI\] METRIC fixture=(\S+) op=(\S+) version=(\S+) round=(\d+) count=(\d+) bytes=(\d+) nanos=(\d+)")
raw = []
for run in runs:
    text = (root / "verification" / f"{run}-server.log").read_text(encoding="utf-8")
    assert "[EI] FAIL" not in text and "[EI] BENCH_DONE" in text, run
    assert all(f"BEHAVIOR_DONE player=Items{n}" in text for n in (1, 2)), run
    rows = pattern.findall(text)
    assert len(rows) == 8 * 3 * 2 * 16, (run, len(rows))
    for fixture, op, version, round_, count, bytes_, nanos in rows:
        count, bytes_, nanos = int(count), int(bytes_), int(nanos)
        groups[fixture, op, version].append((bytes_ / count, nanos / count))
        raw.append(dict(run=run, fixture=fixture, operation=op, version=version,
                        round=int(round_), count=count, bytes=bytes_, nanos=nanos))
results = []
for fixture, op in sorted({(f, o) for f, o, _ in groups}):
    old, new = (groups[fixture, op, v] for v in ("old", "new"))
    a, b = (statistics.median(v[0] for v in samples) for samples in (old, new))
    ta, tb = (statistics.median(v[1] for v in samples) for samples in (old, new))
    row = dict(fixture=fixture, operation=op, oldBytes=a, newBytes=b,
               allocationReductionPercent=100*(a-b)/a if a else None,
               oldNanos=ta, newNanos=tb, timeReductionPercent=100*(ta-tb)/ta,
               samplesPerVersion=len(old))
    results.append(row)
    print(f"{fixture:24} {op:8} {a:9.1f} -> {b:9.1f} B/op {row['allocationReductionPercent']:7.2f}%  time {row['timeReductionPercent']:7.2f}%")
out = dict(runs=runs, measuredCalls=sum(row['count'] for row in raw), results=results, raw=raw)
(root / "verification" / "results.json").write_text(json.dumps(out, indent=2), encoding="utf-8")
