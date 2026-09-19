"""Inspect real Phone CS DATA CSV timing without synthesizing timestamps. Python standard library only."""
import argparse
import csv
import json
import statistics
from pathlib import Path


def analyze(path):
    with Path(path).open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        required = {"row_kind", "record_elapsed_realtime_ns", "timestamp_epoch_ms", "gps_timestamp_epoch_ms",
                    "distance_sample_count", "distance_timestamp_epoch_ms", "ranging_rssi_dbm"}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError(f"Missing columns: {sorted(required - set(reader.fieldnames or []))}")
        rows = list(reader)
    samples = [r for r in rows if r["row_kind"] == "SAMPLE"]
    if len(samples) < 2:
        raise ValueError("At least two SAMPLE rows required")
    ticks = [int(r["record_elapsed_realtime_ns"]) for r in samples]
    intervals = [(b - a) / 1e6 for a, b in zip(ticks, ticks[1:])]
    duration = (ticks[-1] - ticks[0]) / 1e9
    gps = [r["gps_timestamp_epoch_ms"] for r in rows if r["gps_timestamp_epoch_ms"]]
    distances = [r["distance_sample_count"] for r in rows if r["distance_sample_count"]]
    issues = []
    if min(intervals) <= 0:
        issues.append("Non-increasing monotonic SAMPLE timestamps")
    if len(gps) != len(set(gps)):
        issues.append("Repeated GPS fix timestamps")
    if len(distances) != len(set(distances)):
        issues.append("Repeated distance sample counts")
    if any(r["ranging_rssi_dbm"] and not r["distance_timestamp_epoch_ms"] for r in rows):
        issues.append("Ranging RSSI without its distance event")
    if any(v in {"Null", "null", "None", "N/A", "NaN", "nan"} for r in rows for v in r.values()):
        issues.append("Non-empty missing-value sentinel")
    wall = [int(r["timestamp_epoch_ms"]) for r in samples]
    return {
        "file": str(path), "samples": len(samples), "final_pending_rows": len(rows) - len(samples),
        "duration_s": duration, "effective_rate_hz": (len(samples) - 1) / duration if duration > 0 else None,
        "mean_interval_ms": statistics.mean(intervals), "median_interval_ms": statistics.median(intervals),
        "min_interval_ms": min(intervals), "max_interval_ms": max(intervals),
        "interval_stddev_ms": statistics.pstdev(intervals),
        "wall_clock_nonincreasing_intervals": sum(b <= a for a, b in zip(wall, wall[1:])),
        "gps_fixes": len(gps), "distance_events": len(distances), "issues": issues,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path)
    args = parser.parse_args()
    result = analyze(args.csv)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(1 if result["issues"] else 0)
