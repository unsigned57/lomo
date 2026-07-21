#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
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
    node_toolchain,
    rust_toolchain,
    sha256_file,
    utc_now,
)


BOLTFFI_VERSION = crate_version(REPO_ROOT / "boltffi/Cargo.toml")
WASM_BINDGEN_VERSION = None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profile", default="release")
    args = parser.parse_args()

    payload = json.loads(args.results.read_text())
    rows = payload.get("benchmarks", [])
    if not rows:
        raise SystemExit(f"no Benchmark.js rows found in {args.results}")

    git = git_context()
    collected_at = utc_now()
    rust_details = rust_toolchain()
    node_details = node_toolchain()
    benchmarkjs_version = payload.get("benchmarkjs_version")

    benchmarks = []
    for row in rows:
        benchmark_name = row["name"]
        descriptor = infer_descriptor(benchmark_name, "wasm", "java_script")
        variants = []
        notes = []

        for variant_key, variant_config in variant_configs(git, rust_details, args.profile).items():
            variant_payload = extract_variant_payload(row, variant_key)
            variant_result, variant_note = build_variant(
                variant_payload=variant_payload,
                variant_key=variant_key,
                variant_config=variant_config,
            )
            if variant_result is not None:
                variants.append(variant_result)
            if variant_note is not None:
                notes.append(variant_note)

        if variants:
            benchmarks.append(
                {
                    "descriptor": descriptor,
                    "variants": variants,
                    "notes": notes,
                }
            )

    run = {
        "schema_version": "benchmark_run_v1",
        "run_id": build_run_id("wasm-node-benchmarkjs", collected_at, git["commit_sha"]),
        "collected_at": collected_at,
        "provenance": {
            "repository": git,
            "collector": collector_context(invocation="benchmarkjs_to_run.py"),
            "artifacts": artifacts([args.results]),
        },
        "environment": {
            "host": host_context(),
            "toolchains": {
                "rust": rust_details,
                "swift": None,
                "kotlin": None,
                "java": None,
                "node": node_details,
                "wasm": {
                    "target": "wasm32-unknown-unknown",
                    "bindgen": f"wasm-bindgen {WASM_BINDGEN_VERSION}" if WASM_BINDGEN_VERSION else None,
                    "optimizer": None,
                },
            },
            "runtime": {
                "engine": "node",
                "version": node_details["node_version"] if node_details else None,
                "platform": "wasm",
                "attributes": {
                    "benchmarkjs_version": benchmarkjs_version,
                    "async_case_count": sum(1 for row in rows if row.get("async")),
                },
            },
            "ci": ci_context(),
        },
        "suite": {
            "name": "wasm-node-benchmarkjs",
            "harness": "benchmark_js",
            "platform": "wasm",
            "language": "java_script",
            "profile": args.profile,
            "tags": ["nodejs"],
            "attributes": {
                "results_sha256": sha256_file(args.results),
                "benchmark_count": len(rows),
                "benchmarkjs_version": benchmarkjs_version,
            },
        },
        "benchmarks": benchmarks,
        "notes": [],
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(run, indent=2) + "\n")


def variant_configs(git: dict[str, Any], rust_details: dict[str, Any] | None, profile: str) -> dict[str, dict[str, Any]]:
    return {
        "boltffi": {
            "tool": {
                "name": "boltffi",
                "version": BOLTFFI_VERSION,
                "git_sha": git["commit_sha"],
                "crate_version": BOLTFFI_VERSION,
            },
            "build": {
                "compiler_name": "rustc",
                "compiler_version": rust_details["rustc_version"] if rust_details else None,
                "target": "wasm32-unknown-unknown",
                "profile": profile,
                "optimization": "release" if profile == "release" else "debug",
                "features": [],
                "flags": ["--release"] if profile == "release" else [],
            },
            "ffi": {
                "bridge": "boltffi",
                "transport": "wasm",
                "ownership_model": None,
                "attributes": {
                    "host_language": "javascript",
                    "binding_runtime": "@boltffi/runtime",
                },
            },
        },
        "wasmbindgen": {
            "tool": {
                "name": "wasm-bindgen",
                "version": WASM_BINDGEN_VERSION,
                "git_sha": None,
                "crate_version": None,
            },
            "build": {
                "compiler_name": "rustc",
                "compiler_version": rust_details["rustc_version"] if rust_details else None,
                "target": "wasm32-unknown-unknown",
                "profile": profile,
                "optimization": "release" if profile == "release" else "debug",
                "features": [],
                "flags": ["--release"] if profile == "release" else [],
            },
            "ffi": {
                "bridge": "wasm-bindgen",
                "transport": "wasm",
                "ownership_model": None,
                "attributes": {
                    "host_language": "javascript",
                    "binding_runtime": "wasm-bindgen",
                },
            },
        },
    }


def build_variant(
    *,
    variant_payload: dict[str, Any] | None,
    variant_key: str,
    variant_config: dict[str, Any],
) -> tuple[dict[str, Any] | None, str | None]:
    if variant_payload is None:
        return None, f"{variant_key} metric unavailable in benchmark.js output"

    stats = variant_payload.get("stats", {})
    sample_count = positive_int_or_none(stats.get("sample_count"))
    error_message = variant_payload.get("error")
    if variant_payload.get("aborted") and sample_count is None:
        if error_message:
            return None, f"{variant_key} benchmark failed: {error_message}"
        return None, f"{variant_key} metric unavailable in benchmark.js output"

    mean_ns = optional_float(stats.get("mean_ns"))
    if mean_ns is None:
        if error_message:
            return None, f"{variant_key} benchmark failed: {error_message}"
        return None, f"{variant_key} metric unavailable in benchmark.js output"

    operations_per_cycle = positive_int_or_none(variant_payload.get("count"))
    total_operations = None
    if sample_count is not None and operations_per_cycle is not None:
        total_operations = sample_count * operations_per_cycle

    notes = []
    if variant_payload.get("aborted"):
        notes.append("benchmark.js marked this variant as aborted")
    if error_message:
        notes.append(f"benchmark.js error: {error_message}")

    percentiles = {}
    for percentile_key, field_name in {
        "50.0": "median_ns",
        "90.0": "p90_ns",
        "95.0": "p95_ns",
        "99.0": "p99_ns",
    }.items():
        percentile_value = optional_float(stats.get(field_name))
        if percentile_value is not None:
            percentiles[percentile_key] = percentile_value

    return (
        {
            "subject": {
                "tool": variant_config["tool"],
                "build": variant_config["build"],
                "ffi": variant_config["ffi"],
                "attributes": {
                    "subject_key": variant_key,
                    "operations_per_cycle": operations_per_cycle,
                    "cycles": positive_int_or_none(variant_payload.get("cycles")),
                    "hz": optional_float(variant_payload.get("hz")),
                },
            },
            "metrics": {
                "unit": "ns_per_op",
                "estimator": "mean",
                "value": mean_ns,
                "std_dev": optional_float(stats.get("deviation_ns")),
                "min": optional_float(stats.get("min_ns")),
                "max": optional_float(stats.get("max_ns")),
                "percentiles": percentiles,
            },
            "sampling": {
                "warmup_iterations": None,
                "measurement_iterations": None,
                "sample_count": sample_count,
                "total_operations": total_operations,
            },
            "notes": notes,
        },
        None,
    )


def extract_variant_payload(row: dict[str, Any], variant_key: str) -> dict[str, Any] | None:
    variants = row.get("variants")
    if isinstance(variants, dict):
        candidate = variants.get(variant_key)
        if isinstance(candidate, dict):
            return candidate

    legacy_metric = row.get(f"{variant_key}_ns")
    if legacy_metric is None:
        return None
    return {
        "stats": {
            "mean_ns": legacy_metric,
            "sample_count": None,
            "min_ns": None,
            "max_ns": None,
            "median_ns": None,
            "p90_ns": None,
            "p95_ns": None,
            "p99_ns": None,
            "deviation_ns": None,
        }
    }


def dependency_version(cargo_toml: Path, dependency_name: str) -> str | None:
    content = cargo_toml.read_text()
    match = re.search(rf"^{re.escape(dependency_name)}\s*=\s*\"([^\"]+)\"", content, re.MULTILINE)
    if match is None:
        return None
    return match.group(1)


WASM_BINDGEN_VERSION = dependency_version(REPO_ROOT / "examples/demo/Cargo.toml", "wasm-bindgen")


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
