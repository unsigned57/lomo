import json
import math
import re
import argparse
from pathlib import Path


def format_time_ns(value_ns: float) -> str:
    if value_ns < 1_000:
        return f"{value_ns:.1f}ns"
    if value_ns < 1_000_000:
        return f"{value_ns / 1_000:.1f}us"
    if value_ns < 1_000_000_000:
        return f"{value_ns / 1_000_000:.1f}ms"
    return f"{value_ns / 1_000_000_000:.2f}s"


def format_speedup(uniffi_ns: float, boltffi_ns: float) -> str:
    if boltffi_ns <= 0.0:
        return ""
    ratio = uniffi_ns / boltffi_ns
    if ratio >= 1.0:
        return f"+{ratio:.2f}x"
    return f"-{(1.0 / ratio):.2f}x"


def parse_kotlin_bench_ids(kotlin_path: Path) -> tuple[list[str], set[str]]:
    kotlin_source = kotlin_path.read_text()

    boltffi_names = re.findall(r"@Benchmark\s+open fun\s+(boltffi_[A-Za-z0-9_]+)\s*\(", kotlin_source)
    uniffi_names = re.findall(r"@Benchmark\s+open fun\s+(uniffi_[A-Za-z0-9_]+)\s*\(", kotlin_source)

    ordered_ids: list[str] = []
    seen: set[str] = set()
    for name in boltffi_names:
        bench_id = name[len("boltffi_") :]
        if bench_id not in seen:
            seen.add(bench_id)
            ordered_ids.append(bench_id)

    uniffi_ids = {name[len("uniffi_") :] for name in uniffi_names}
    return ordered_ids, uniffi_ids


def render_markdown(rows: list[tuple[str, str, str, str]]) -> str:
    lines = ["| bench | boltffi | uniffi | speedup |", "|---|---:|---:|---:|"]
    lines.extend(f"| {bench} | {boltffi} | {uniffi} | {speedup} |" for bench, boltffi, uniffi, speedup in rows)
    return "\n".join(lines) + "\n"


def render_plain(rows: list[tuple[str, str, str, str]]) -> str:
    if not rows:
        return ""

    bench_width = max(len("bench"), max((len(r[0]) for r in rows), default=0))
    boltffi_width = max(len("boltffi"), max((len(r[1]) for r in rows), default=0))
    uniffi_width = max(len("uniffi"), max((len(r[2]) for r in rows), default=0))
    speedup_width = max(len("speedup"), max((len(r[3]) for r in rows), default=0))

    header = (
        f"{'bench'.ljust(bench_width)}  "
        f"{'boltffi'.rjust(boltffi_width)}  "
        f"{'uniffi'.rjust(uniffi_width)}  "
        f"{'speedup'.rjust(speedup_width)}"
    )
    sep = (
        f"{'-' * bench_width}  "
        f"{'-' * boltffi_width}  "
        f"{'-' * uniffi_width}  "
        f"{'-' * speedup_width}"
    )

    lines = [header, sep]
    lines.extend(
        f"{bench.ljust(bench_width)}  {boltffi.rjust(boltffi_width)}  {uniffi.rjust(uniffi_width)}  {speedup.rjust(speedup_width)}"
        for bench, boltffi, uniffi, speedup in rows
    )
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--format", choices=("plain", "markdown", "both"), default="plain")
    args = parser.parse_args()

    root = Path(__file__).resolve().parent
    results_path = root / "build" / "results" / "jmh" / "results.json"
    kotlin_bench_path = root / "src" / "jmh" / "kotlin" / "com" / "example" / "bench_compare" / "JmhBenchmarks.kt"

    results = json.loads(results_path.read_text())
    by_method: dict[str, tuple[float, str]] = {}
    for entry in results:
        benchmark = entry["benchmark"]
        method = benchmark.split(".")[-1]
        metric = entry["primaryMetric"]
        score = float(metric["score"])
        unit = metric["scoreUnit"]
        by_method[method] = (score, unit)

    ordered_ids, expected_uniffi_ids = parse_kotlin_bench_ids(kotlin_bench_path)

    rows: list[tuple[str, str, str, str]] = []

    for bench_id in ordered_ids:
        boltffi_key = f"boltffi_{bench_id}"
        boltffi = by_method.get(boltffi_key)

        uniffi_key = f"uniffi_{bench_id}"
        uniffi_expected = bench_id in expected_uniffi_ids
        uniffi = by_method.get(uniffi_key) if uniffi_expected else None

        if boltffi is None:
            boltffi_cell = "MISSING"
        else:
            boltffi_score, boltffi_unit = boltffi
            boltffi_cell = format_time_ns(boltffi_score) if boltffi_unit == "ns/op" else f"{boltffi_score:.3f}{boltffi_unit}"

        if uniffi_expected:
            if uniffi is None:
                uniffi_cell = "MISSING"
                speedup_cell = ""
            else:
                uniffi_score, uniffi_unit = uniffi
                uniffi_cell = (
                    format_time_ns(uniffi_score)
                    if uniffi_unit == "ns/op"
                    else f"{uniffi_score:.3f}{uniffi_unit}"
                )
                speedup_cell = (
                    format_speedup(uniffi_score, boltffi_score) if boltffi is not None and boltffi_unit == "ns/op" else ""
                )
        else:
            uniffi_cell = "N/A"
            speedup_cell = ""

        rows.append((bench_id, boltffi_cell, uniffi_cell, speedup_cell))

    markdown = render_markdown(rows)
    plain = render_plain(rows)

    results_dir = results_path.parent
    (results_dir / "report.md").write_text(markdown)
    (results_dir / "report.txt").write_text(plain)

    if args.format in ("plain", "both"):
        print(plain)
    if args.format in ("markdown", "both"):
        print(markdown)


if __name__ == "__main__":
    main()
