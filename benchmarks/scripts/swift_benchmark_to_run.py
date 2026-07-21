#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import shlex
from collections import defaultdict
from pathlib import Path
from typing import Any

from benchmark_schema import (
    REPO_ROOT,
    artifacts,
    build_run_id,
    ci_context,
    collector_context,
    crate_version,
    git_context,
    host_context,
    infer_descriptor,
    rust_toolchain,
    sha256_file,
    swift_toolchain,
    utc_now,
)


BOLTFFI_VERSION = crate_version(REPO_ROOT / "boltffi/Cargo.toml")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profile", default="release")
    parser.add_argument("--runner-command")
    args = parser.parse_args()

    raw_payload = args.results.read_text()
    sanitized_payload = re.sub(r"\b(?:nan|inf|-inf)\b", "null", raw_payload)
    payload = json.loads(sanitized_payload)
    rows = payload.get("benchmarks", [])
    if not rows:
        raise SystemExit(f"no Swift benchmark rows found in {args.results}")

    git = git_context()
    collected_at = utc_now()
    rust_details = rust_toolchain()
    runner_settings = parse_runner_settings(args.runner_command)
    grouped_cases: dict[str, list[dict[str, Any]]] = defaultdict(list)

    for row in rows:
        case_name = row["name"]
        if "_" not in case_name:
            raise SystemExit(f"unexpected benchmark name {case_name!r}")

        subject_prefix, benchmark_name = case_name.split("_", 1)
        if subject_prefix not in {"boltffi", "uniffi"}:
            raise SystemExit(f"unsupported Swift subject prefix {subject_prefix!r}")

        descriptor = infer_descriptor(benchmark_name, "native", "swift")
        grouped_cases[benchmark_name].append(
            {
                "descriptor": descriptor,
                "variant": build_variant(
                    row=row,
                    subject_prefix=subject_prefix,
                    benchmark_name=benchmark_name,
                    git=git,
                    rust_details=rust_details,
                    profile=args.profile,
                    runner_settings=runner_settings,
                ),
            }
        )

    benchmarks = []
    for benchmark_name in sorted(grouped_cases):
        case_entries = grouped_cases[benchmark_name]
        benchmarks.append(
            {
                "descriptor": case_entries[0]["descriptor"],
                "variants": sorted(
                    (entry["variant"] for entry in case_entries),
                    key=lambda variant: variant["subject"]["tool"]["name"],
                ),
                "notes": [],
            }
        )

    run = {
        "schema_version": "benchmark_run_v1",
        "run_id": build_run_id("swift-macos-benchmark", collected_at, git["commit_sha"]),
        "collected_at": collected_at,
        "provenance": {
            "repository": git,
            "collector": collector_context(invocation=args.runner_command or "swift_benchmark_to_run.py"),
            "artifacts": artifacts([args.results]),
        },
        "environment": {
            "host": host_context(),
            "toolchains": {
                "rust": rust_details,
                "swift": swift_toolchain(),
                "kotlin": None,
                "java": None,
                "node": None,
                "wasm": None,
            },
            "runtime": {
                "engine": "native",
                "version": None,
                "platform": "native",
                "attributes": {
                    "runner_command": args.runner_command,
                    "result_columns": ",".join(rows[0].keys()),
                },
            },
            "ci": ci_context(),
        },
        "suite": {
            "name": "swift-macos-benchmark",
            "harness": "swift_benchmark",
            "platform": "native",
            "language": "swift",
            "profile": args.profile,
            "tags": ["macos"],
            "attributes": {
                "results_sha256": sha256_file(args.results),
                "benchmark_count": len(rows),
                **runner_settings,
            },
        },
        "benchmarks": benchmarks,
        "notes": [],
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(run, indent=2) + "\n")


def build_variant(
    *,
    row: dict[str, Any],
    subject_prefix: str,
    benchmark_name: str,
    git: dict[str, Any],
    rust_details: dict[str, Any] | None,
    profile: str,
    runner_settings: dict[str, Any],
) -> dict[str, Any]:
    mean_value = optional_float(row.get("avg"))
    median_value = optional_float(row.get("median")) or optional_float(row.get("time"))
    metric_value = mean_value if mean_value is not None else median_value
    if metric_value is None:
        raise SystemExit(f"missing avg/median metric for Swift benchmark {row['name']!r}")

    absolute_std_dev = optional_float(row.get("std_abs"))
    metric_notes = []
    if absolute_std_dev is None and row.get("std") is not None:
        metric_notes.append("swift-benchmark only reported relative standard deviation")

    percentiles: dict[str, float] = {}
    for percentile_key, percentile_name in {
        "50.0": "p50",
        "90.0": "p90",
        "95.0": "p95",
        "99.0": "p99",
    }.items():
        percentile_value = optional_float(row.get(percentile_name))
        if percentile_value is not None:
            percentiles[percentile_key] = percentile_value
    if median_value is not None:
        percentiles.setdefault("50.0", median_value)

    is_boltffi = subject_prefix == "boltffi"
    return {
        "subject": {
            "tool": {
                "name": "boltffi" if is_boltffi else "uniffi",
                "version": BOLTFFI_VERSION if is_boltffi else "0.31.0",
                "git_sha": git["commit_sha"] if is_boltffi else None,
                "crate_version": BOLTFFI_VERSION if is_boltffi else None,
            },
            "build": {
                "compiler_name": "rustc",
                "compiler_version": rust_details["rustc_version"] if rust_details else None,
                "target": rust_details["target_triple"] if rust_details else None,
                "profile": profile,
                "optimization": "release" if profile == "release" else "debug",
                "features": ["uniffi"] if not is_boltffi else [],
                "flags": ["--release"] if profile == "release" else [],
            },
            "ffi": {
                "bridge": "boltffi" if is_boltffi else "uniffi",
                "transport": "swift",
                "ownership_model": None,
                "attributes": {
                    "host_language": "swift",
                    "binding_runtime": "swift_package_manager",
                },
            },
            "attributes": {
                "subject_key": subject_prefix,
                "benchmark_name": benchmark_name,
            },
        },
        "metrics": {
            "unit": "ns_per_op",
            "estimator": "mean" if mean_value is not None else "median",
            "value": metric_value,
            "std_dev": absolute_std_dev,
            "min": optional_float(row.get("min")),
            "max": optional_float(row.get("max")),
            "percentiles": percentiles,
        },
        "sampling": {
            "warmup_iterations": positive_int_or_none(runner_settings.get("warmup_iterations")),
            "measurement_iterations": positive_int_or_none(row.get("iterations"))
            or positive_int_or_none(runner_settings.get("iterations")),
            "sample_count": positive_int_or_none(row.get("iterations")),
            "total_operations": None,
        },
        "notes": metric_notes,
    }


def parse_runner_settings(runner_command: str | None) -> dict[str, Any]:
    if not runner_command:
        return {}

    tokens = shlex.split(runner_command)
    settings: dict[str, Any] = {
        "runner_command": runner_command,
    }
    index = 0
    while index < len(tokens):
        token = tokens[index]
        next_token = tokens[index + 1] if index + 1 < len(tokens) else None

        if token == "--filter" and next_token is not None:
            settings["filter"] = next_token
            index += 2
            continue
        if token == "--filter-not" and next_token is not None:
            settings["filter_not"] = next_token
            index += 2
            continue
        if token == "--iterations" and next_token is not None:
            settings["iterations"] = int(next_token)
            index += 2
            continue
        if token == "--warmup-iterations" and next_token is not None:
            settings["warmup_iterations"] = int(next_token)
            index += 2
            continue
        if token == "--min-time" and next_token is not None:
            settings["min_time_seconds"] = float(next_token)
            index += 2
            continue
        if token == "--max-iterations" and next_token is not None:
            settings["max_iterations"] = int(next_token)
            index += 2
            continue
        if token == "--columns" and next_token is not None:
            settings["columns"] = next_token
            index += 2
            continue
        index += 1

    return settings


def optional_float(value: Any) -> float | None:
    if value is None:
        return None
    return float(value)


def positive_int_or_none(value: Any) -> int | None:
    if value is None:
        return None
    integer_value = int(value)
    if integer_value <= 0:
        return None
    return integer_value


if __name__ == "__main__":
    main()
