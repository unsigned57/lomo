#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import tomllib
from dataclasses import dataclass
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
    utc_now,
)
from dotnet_benchmark_names import method_name_to_case_id


def dependency_version(cargo_toml: Path, dependency_name: str) -> str | None:
    if not cargo_toml.exists():
        return None

    manifest = tomllib.loads(cargo_toml.read_text())
    dependency = (manifest.get("dependencies") or {}).get(dependency_name)
    if dependency is None:
        return None
    if isinstance(dependency, str):
        return dependency
    if isinstance(dependency, dict):
        version = dependency.get("version")
        if isinstance(version, str):
            return version
    return None


BOLTFFI_VERSION = crate_version(REPO_ROOT / "boltffi/Cargo.toml")
UNIFFI_VERSION = dependency_version(REPO_ROOT / "examples/demo/Cargo.toml", "uniffi")
SUITE_NAME = "csharp-dotnet-benchmarkdotnet"


@dataclass(frozen=True)
class BenchmarkDotNetResults:
    paths: list[Path]
    summaries: list[dict[str, Any]]

    @classmethod
    def read(cls, paths: list[Path]) -> "BenchmarkDotNetResults":
        if not paths:
            raise SystemExit("no BenchmarkDotNet result paths provided")

        results = cls(
            paths=paths,
            summaries=[json.loads(path.read_text()) for path in paths],
        )
        if not results.benchmarks:
            joined_paths = ", ".join(str(path) for path in paths)
            raise SystemExit(f"no BenchmarkDotNet results found in {joined_paths}")
        return results

    @property
    def benchmarks(self) -> list[dict[str, Any]]:
        return [
            benchmark
            for summary in self.summaries
            for benchmark in summary.get("Benchmarks") or []
        ]

    @property
    def host_info(self) -> dict[str, Any]:
        return next(
            (
                host_info
                for summary in self.summaries
                if (host_info := summary.get("HostEnvironmentInfo") or {})
            ),
            {},
        )

    @property
    def title(self) -> str | None:
        titles = list(
            dict.fromkeys(
                title
                for summary in self.summaries
                if isinstance((title := summary.get("Title")), str) and title
            )
        )
        if not titles:
            return None
        if len(titles) == 1:
            return titles[0]
        return SUITE_NAME

    @property
    def report_titles(self) -> list[str]:
        return list(
            dict.fromkeys(
                title
                for summary in self.summaries
                if isinstance((title := summary.get("Title")), str) and title
            )
        )

    def invocation(self) -> str:
        joined_paths = " ".join(str(path) for path in self.paths)
        return f"benchmarkdotnet_to_run.py --results {joined_paths}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, nargs="+", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profile", default="release")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    results = BenchmarkDotNetResults.read(args.results)

    git = git_context()
    collected_at = utc_now()
    host_info = results.host_info

    run_document = {
        "schema_version": "benchmark_run_v1",
        "run_id": build_run_id(SUITE_NAME, collected_at, git["commit_sha"]),
        "collected_at": collected_at,
        "provenance": {
            "repository": git,
            "collector": collector_context(
                invocation=results.invocation()
            ),
            "artifacts": artifacts(results.paths),
        },
        "environment": {
            "host": build_host_context(host_info),
            "toolchains": {
                "rust": rust_toolchain(),
                "swift": None,
                "kotlin": None,
                "java": None,
                "node": None,
                "wasm": None,
                "dotnet": build_dotnet_toolchain(host_info),
            },
            "runtime": {
                "engine": "dotnet",
                "version": runtime_version(host_info),
                "platform": "dotnet",
                "attributes": build_runtime_attributes(host_info),
            },
            "ci": ci_context(),
        },
        "suite": {
            "name": SUITE_NAME,
            "harness": "benchmarkdotnet",
            "platform": "dotnet",
            "language": "csharp",
            "profile": args.profile,
            "tags": [],
            "attributes": {
                "title": results.title,
                "report_titles": results.report_titles,
                "benchmarkdotnet_version": host_info.get("BenchmarkDotNetVersion"),
                "dotnet_cli_version": host_info.get("DotNetCliVersion"),
            },
        },
        "benchmarks": build_benchmark_entries(results.benchmarks, host_info, git, args.profile),
        "notes": [],
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(run_document, indent=2) + "\n")


def build_benchmark_entries(
    benchmarks: list[dict[str, Any]],
    host_info: dict[str, Any],
    git: dict[str, Any],
    profile: str,
) -> list[dict[str, Any]]:
    grouped_entries: dict[str, dict[str, Any]] = {}

    for benchmark in benchmarks:
        method_name = benchmark_method_name(benchmark)
        case_id = method_name_to_case_id(method_name)
        descriptor = infer_descriptor(case_id, "dotnet", "csharp")
        benchmark_entry = grouped_entries.setdefault(
            descriptor["id"],
            {
                "descriptor": descriptor,
                "variants": [],
                "notes": [],
            },
        )
        benchmark_entry["variants"].append(
            build_variant_entry(
                benchmark=benchmark,
                method_name=method_name,
                host_info=host_info,
                git=git,
                profile=profile,
            )
        )

    return sorted(
        [
            {
                **benchmark,
                "variants": sorted(
                    benchmark["variants"],
                    key=lambda variant: tool_sort_key(variant["subject"]["tool"]["name"]),
                ),
            }
            for benchmark in grouped_entries.values()
        ],
        key=lambda benchmark: benchmark["descriptor"]["id"],
    )


def build_variant_entry(
    benchmark: dict[str, Any],
    method_name: str,
    host_info: dict[str, Any],
    git: dict[str, Any],
    profile: str,
) -> dict[str, Any]:
    tool_name = benchmark_tool_name(benchmark)
    statistics = benchmark.get("Statistics") or {}
    properties = benchmark.get("Properties") or {}
    benchmark_type = benchmark.get("Type") or benchmark.get("TypeName")

    return {
        "subject": {
            "tool": {
                "name": tool_name,
                "version": tool_version(tool_name),
                "git_sha": git["commit_sha"],
                "crate_version": tool_version(tool_name),
            },
            "build": {
                "compiler_name": "dotnet",
                "compiler_version": host_info.get("DotNetCliVersion"),
                "target": benchmark.get("TargetFramework"),
                "profile": profile,
                "optimization": profile,
                "features": [],
                "flags": [],
            },
            "ffi": {
                "bridge": tool_name,
                "transport": "p_invoke",
                "ownership_model": None,
                "attributes": {
                    "host_language": "csharp",
                    "binding_runtime": ".net",
                },
            },
            "attributes": {
                "subject_key": tool_name,
                "binding_package": binding_package(tool_name),
                "benchmark_method": method_name,
                "benchmark_type": benchmark_type,
                "benchmarkdotnet_title": benchmark.get("MethodTitle") or method_name,
            },
        },
        "metrics": {
            "unit": "ns_per_op",
            "estimator": "mean",
            "value": statistics_value(statistics, "Mean"),
            "std_dev": statistics_value(statistics, "StandardDeviation"),
            "min": statistics_value(statistics, "Min"),
            "max": statistics_value(statistics, "Max"),
            "percentiles": {
                key.removeprefix("P"): value
                for key, value in (statistics.get("Percentiles") or {}).items()
                if isinstance(value, (int, float))
            },
        },
        "sampling": {
            "warmup_iterations": parse_count(properties.get("WarmupCount")),
            "measurement_iterations": parse_count(properties.get("IterationCount")),
            "sample_count": parse_count(statistics.get("N")),
            "total_operations": None,
        },
        "notes": build_variant_notes(benchmark),
    }


def benchmark_method_name(benchmark: dict[str, Any]) -> str:
    method_name = benchmark.get("Method") or benchmark.get("MethodTitle")
    if not isinstance(method_name, str) or not method_name:
        raise SystemExit(f"missing BenchmarkDotNet method name in benchmark entry: {benchmark}")
    return method_name


def benchmark_tool_name(benchmark: dict[str, Any]) -> str:
    haystack = " ".join(
        str(value)
        for value in (
            benchmark.get("Type"),
            benchmark.get("TypeName"),
            benchmark.get("Namespace"),
            benchmark.get("FullName"),
            benchmark.get("DisplayInfo"),
        )
        if value is not None
    ).lower()

    if "uniffi" in haystack:
        return "uniffi"
    if "boltffi" in haystack:
        return "boltffi"

    return "boltffi"


def binding_package(tool_name: str) -> str:
    if tool_name == "uniffi":
        return "uniffi.demo"
    return "Demo"


def tool_sort_key(tool_name: str) -> tuple[int, str]:
    if tool_name == "boltffi":
        return (0, tool_name)
    if tool_name == "uniffi":
        return (1, tool_name)
    return (2, tool_name)


def tool_version(tool_name: str) -> str | None:
    if tool_name == "uniffi":
        return UNIFFI_VERSION
    return BOLTFFI_VERSION


def build_host_context(host_info: dict[str, Any]) -> dict[str, Any]:
    fallback = host_context()
    attributes = dict(fallback.get("attributes") or {})

    benchmarkdotnet_version = host_info.get("BenchmarkDotNetVersion")
    if benchmarkdotnet_version:
        attributes["benchmarkdotnet_version"] = benchmarkdotnet_version
    clr_version = host_info.get("ClrVersion")
    if clr_version:
        attributes["clr_version"] = clr_version
    configuration = host_info.get("Configuration")
    if configuration:
        attributes["configuration"] = configuration
    jit_modules = host_info.get("JitModules")
    if jit_modules:
        attributes["jit_modules"] = jit_modules
    hardware_timer_kind = host_info.get("HardwareTimerKind")
    if hardware_timer_kind:
        attributes["hardware_timer_kind"] = hardware_timer_kind
    dotnet_cli_version = host_info.get("DotNetCliVersion")
    if dotnet_cli_version:
        attributes["dotnet_cli_version"] = dotnet_cli_version

    return {
        **fallback,
        "os_version": host_info.get("OsVersion") or fallback.get("os_version"),
        "arch": normalize_architecture(host_info.get("Architecture")) or fallback.get("arch"),
        "cpu_model": processor_name(host_info) or fallback.get("cpu_model"),
        "logical_cores": parse_count(host_info.get("ProcessorCount")) or fallback.get("logical_cores"),
        "attributes": attributes,
    }


def build_dotnet_toolchain(host_info: dict[str, Any]) -> dict[str, Any]:
    return {
        "sdk_version": host_info.get("DotNetCliVersion"),
        "runtime_version": runtime_version(host_info),
        "runtime_kind": host_info.get("Runtime"),
    }


def build_runtime_attributes(host_info: dict[str, Any]) -> dict[str, Any]:
    attributes = {
        "benchmarkdotnet_version": host_info.get("BenchmarkDotNetVersion"),
        "dotnet_cli_version": host_info.get("DotNetCliVersion"),
        "clr_version": host_info.get("ClrVersion"),
        "runtime_kind": host_info.get("Runtime"),
        "configuration": host_info.get("Configuration"),
        "chronometer_frequency": host_info.get("ChronometerFrequency"),
        "hardware_timer_kind": host_info.get("HardwareTimerKind"),
    }
    return {key: value for key, value in attributes.items() if value is not None}


def build_variant_notes(benchmark: dict[str, Any]) -> list[str]:
    notes: list[str] = []
    statistics = benchmark.get("Statistics") or {}
    confidence_interval = statistics.get("ConfidenceInterval") or {}
    if confidence_interval:
        lower = confidence_interval.get("Lower")
        upper = confidence_interval.get("Upper")
        if isinstance(lower, (int, float)) and isinstance(upper, (int, float)):
            notes.append(f"confidence_interval_ns=[{float(lower):.6f}, {float(upper):.6f}]")
    memory_bytes = benchmark.get("Memory", {}).get("BytesAllocatedPerOperation")
    if isinstance(memory_bytes, (int, float)):
        notes.append(f"bytes_allocated_per_operation={float(memory_bytes):.6f}")
    return notes
def statistics_value(statistics: dict[str, Any], key: str) -> float:
    value = statistics.get(key)
    if not isinstance(value, (int, float)):
        raise SystemExit(f"missing BenchmarkDotNet statistics value {key}")
    return float(value)


def parse_count(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str):
        stripped = value.strip()
        if not stripped or stripped.lower() == "auto":
            return None
        if stripped.isdigit():
            return int(stripped)
    return None


def processor_name(host_info: dict[str, Any]) -> str | None:
    processor_name = host_info.get("ProcessorName")
    if isinstance(processor_name, str):
        return processor_name
    if isinstance(processor_name, dict):
        value = processor_name.get("Value")
        if isinstance(value, str):
            return value
    return None


def normalize_architecture(architecture: Any) -> str | None:
    if not isinstance(architecture, str):
        return None

    normalized = architecture.lower().replace("-", "").replace("_", "")
    if normalized in {"64bit", "x64", "amd64"}:
        return "x86_64"
    if normalized in {"arm64", "aarch64"}:
        return "arm64"
    return architecture.lower()


def runtime_version(host_info: dict[str, Any]) -> str | None:
    return host_info.get("RuntimeVersion") or host_info.get("ClrVersion")


if __name__ == "__main__":
    main()
