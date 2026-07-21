#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from typing import Any

import pyperf

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
    utc_now,
)


BOLTFFI_VERSION = crate_version(REPO_ROOT / "boltffi/Cargo.toml")
UNIFFI_VERSION = "0.31.1"
SUBJECT_ORDER = ["boltffi", "uniffi"]
SUBJECT_CONFIG = {
    "boltffi": {
        "tool": {
            "name": "boltffi",
            "version": BOLTFFI_VERSION,
            "git_sha": "repository_head",
            "crate_version": BOLTFFI_VERSION,
        },
        "ffi": {
            "bridge": "boltffi",
            "transport": "python_capi",
            "ownership_model": None,
            "attributes": {
                "host_language": "python",
                "binding_runtime": "cpython_extension",
            },
        },
        "attributes": {
            "subject_key": "boltffi",
            "binding_module": "demo",
        },
    },
    "uniffi": {
        "tool": {
            "name": "uniffi",
            "version": UNIFFI_VERSION,
            "git_sha": None,
            "crate_version": None,
        },
        "ffi": {
            "bridge": "uniffi",
            "transport": "ctypes",
            "ownership_model": None,
            "attributes": {
                "host_language": "python",
                "binding_runtime": "ctypes",
            },
        },
        "attributes": {
            "subject_key": "uniffi",
            "binding_module": "demo",
        },
    },
}


def parse_args() -> argparse.Namespace:
    argument_parser = argparse.ArgumentParser()
    argument_parser.add_argument("--results", type=Path, required=True)
    argument_parser.add_argument("--output", type=Path, required=True)
    argument_parser.add_argument("--profile", default="release")
    argument_parser.add_argument("--runner-command")
    return argument_parser.parse_args()


def main() -> None:
    args = parse_args()
    suite = pyperf.BenchmarkSuite.load(str(args.results))
    if len(suite) == 0:
        raise SystemExit(f"no pyperf benchmarks found in {args.results}")

    git = git_context()
    collected_at = utc_now()
    rust_details = rust_toolchain()
    grouped_cases: dict[str, list[dict[str, Any]]] = defaultdict(list)

    for benchmark in suite:
        subject_prefix, case_name = split_subject(benchmark.get_name())
        grouped_cases[case_name].append(
            {
                "descriptor": infer_descriptor(case_name, "native", "python"),
                "variant": build_variant(
                    benchmark=benchmark,
                    subject_prefix=subject_prefix,
                    git=git,
                    rust_details=rust_details,
                    profile=args.profile,
                ),
            }
        )

    benchmarks = [
        {
            "descriptor": case_entries[0]["descriptor"],
            "variants": sorted(
                (entry["variant"] for entry in case_entries),
                key=lambda variant: SUBJECT_ORDER.index(variant["subject"]["attributes"]["subject_key"]),
            ),
            "notes": [],
        }
        for _, case_entries in sorted(grouped_cases.items())
    ]

    first_benchmark = next(iter(suite))
    first_metadata = first_benchmark.get_metadata()

    run = {
        "schema_version": "benchmark_run_v1",
        "run_id": build_run_id("python-pyperf", collected_at, git["commit_sha"]),
        "collected_at": collected_at,
        "provenance": {
            "repository": git,
            "collector": collector_context(
                invocation=args.runner_command or f"pyperf_to_run.py --results {args.results}"
            ),
            "artifacts": artifacts([args.results]),
        },
        "environment": {
            "host": host_context(),
            "toolchains": {
                "rust": rust_details,
                "swift": None,
                "kotlin": None,
                "java": None,
                "node": None,
                "wasm": None,
            },
            "runtime": {
                "engine": "cpython",
                "version": first_metadata.get("python_version"),
                "platform": "native",
                "attributes": {
                    "python_executable": first_metadata.get("python_executable"),
                    "python_implementation": first_metadata.get("python_implementation"),
                    "pyperf_version": first_metadata.get("perf_version"),
                    "runner_command": args.runner_command,
                },
            },
            "ci": ci_context(),
        },
        "suite": {
            "name": "python-pyperf",
            "harness": "pyperf",
            "platform": "native",
            "language": "python",
            "profile": args.profile,
            "tags": [],
            "attributes": {
                "results_sha256": sha256_file(args.results),
                "benchmark_count": len(suite),
            },
        },
        "benchmarks": benchmarks,
        "notes": [],
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(run, indent=2) + "\n")


def split_subject(benchmark_name: str) -> tuple[str, str]:
    for subject_prefix in SUBJECT_CONFIG:
        prefixed_name = f"{subject_prefix}_"
        if benchmark_name.startswith(prefixed_name):
            return subject_prefix, benchmark_name.removeprefix(prefixed_name)

    raise SystemExit(f"unsupported Python benchmark name {benchmark_name!r}")


def build_variant(
    *,
    benchmark: pyperf.Benchmark,
    subject_prefix: str,
    git: dict[str, Any],
    rust_details: dict[str, Any] | None,
    profile: str,
) -> dict[str, Any]:
    values_ns = sorted(value * 1e9 for value in benchmark.get_values())
    metadata = benchmark.get_metadata()
    loops = metadata.get("loops")

    tool_identity = dict(SUBJECT_CONFIG[subject_prefix]["tool"])
    if tool_identity["git_sha"] == "repository_head":
        tool_identity["git_sha"] = git["commit_sha"]

    return {
        "subject": {
            "tool": tool_identity,
            "build": {
                "compiler_name": "rustc",
                "compiler_version": rust_details["rustc_version"] if rust_details else None,
                "target": rust_details["target_triple"] if rust_details else None,
                "profile": profile,
                "optimization": "release" if profile == "release" else "debug",
                "features": ["uniffi"] if subject_prefix == "uniffi" else [],
                "flags": ["--release"] if profile == "release" else [],
            },
            "ffi": SUBJECT_CONFIG[subject_prefix]["ffi"],
            "attributes": SUBJECT_CONFIG[subject_prefix]["attributes"],
        },
        "metrics": {
            "unit": "ns_per_op",
            "estimator": "mean",
            "value": benchmark.mean() * 1e9,
            "std_dev": benchmark.stdev() * 1e9,
            "min": values_ns[0] if values_ns else None,
            "max": values_ns[-1] if values_ns else None,
            "percentiles": {
                "50.0": percentile(values_ns, 0.5),
                "90.0": percentile(values_ns, 0.9),
                "95.0": percentile(values_ns, 0.95),
                "99.0": percentile(values_ns, 0.99),
            },
        },
        "sampling": {
            "warmup_iterations": None,
            "measurement_iterations": len(values_ns),
            "sample_count": len(values_ns),
            "total_operations": loops * len(values_ns) if isinstance(loops, int) else None,
        },
        "notes": [],
    }


def percentile(sorted_values: list[float], ratio: float) -> float | None:
    if not sorted_values:
        return None

    raw_index = (len(sorted_values) - 1) * ratio
    lower_index = int(raw_index)
    upper_index = min(lower_index + 1, len(sorted_values) - 1)
    if lower_index == upper_index:
        return sorted_values[lower_index]

    weight = raw_index - lower_index
    return sorted_values[lower_index] * (1 - weight) + sorted_values[upper_index] * weight


if __name__ == "__main__":
    main()
