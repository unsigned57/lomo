from __future__ import annotations

import hashlib
import os
import platform
import re
import subprocess
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path
from typing import Any

from benchmark_catalog import lookup_case_spec
from demo_benchmark_policy import BENCHMARK_FAMILIES


REPO_ROOT = Path(__file__).resolve().parents[2]
FAMILY_LOOKUP = {family.family_id: family for family in BENCHMARK_FAMILIES}

ENTITY_GROUPS: dict[str, tuple[str, str]] = {
    "locations": ("records.locations", "records"),
    "trades": ("records.trades", "records"),
    "particles": ("records.particles", "records"),
    "sensors": ("records.sensor_readings", "records"),
    "sensor_temp": ("records.sensor_readings", "records"),
    "user_profiles": ("records.user_profiles", "records"),
    "i32_vec": ("collections.i32_vec", "collections"),
    "f64_vec": ("collections.f64_vec", "collections"),
    "directions": ("enums.directions", "enums"),
}

AGGREGATE_GROUPS: dict[str, tuple[str, str, str]] = {
    "ratings": ("records.locations.sum_ratings", "records", "host_to_rust"),
    "trade_volumes": ("records.trades.sum_trade_volumes", "records", "host_to_rust"),
    "particle_masses": ("records.particles.sum_particle_masses", "records", "host_to_rust"),
    "user_scores": ("records.user_profiles.sum_user_scores", "records", "host_to_rust"),
    "active_users": ("records.user_profiles.count_active_users", "records", "host_to_rust"),
    "north": ("enums.directions.count_north", "enums", "host_to_rust"),
    "sensor_temp": ("records.sensor_readings.average_temperature", "records", "host_to_rust"),
}


def run_command(args: list[str], cwd: Path | None = None) -> str | None:
    try:
        completed = subprocess.run(
            args,
            cwd=cwd or REPO_ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
    except (FileNotFoundError, subprocess.CalledProcessError):
        return None

    output = completed.stdout.strip()
    if output:
        return output
    return completed.stderr.strip() or None


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_context() -> dict[str, Any]:
    commit_sha = run_command(["git", "rev-parse", "HEAD"]) or "unknown"
    branch = run_command(["git", "rev-parse", "--abbrev-ref", "HEAD"])
    dirty = bool(run_command(["git", "status", "--porcelain"]))
    remote_url = run_command(["git", "remote", "get-url", "origin"])

    return {
        "name": REPO_ROOT.name,
        "url": remote_url,
        "branch": branch,
        "commit_sha": commit_sha,
        "dirty": dirty,
    }


def cpu_info() -> tuple[str | None, int | None, int | None, int | None]:
    system = platform.system()
    if system == "Darwin":
        return (
            run_command(["sysctl", "-n", "machdep.cpu.brand_string"]),
            _int_or_none(run_command(["sysctl", "-n", "hw.physicalcpu"])),
            _int_or_none(run_command(["sysctl", "-n", "hw.logicalcpu"])),
            _int_or_none(run_command(["sysctl", "-n", "hw.memsize"])),
        )

    return (None, None, os.cpu_count(), None)


def host_context() -> dict[str, Any]:
    cpu_model, physical_cores, logical_cores, memory_bytes = cpu_info()
    host_attributes: dict[str, Any] = {
        "hostname": platform.node(),
        "kernel_release": platform.release(),
        "kernel_version": platform.version(),
        "python_version": platform.python_version(),
    }
    libc_name, libc_version = platform.libc_ver()
    if libc_name:
        host_attributes["libc_name"] = libc_name
    if libc_version:
        host_attributes["libc_version"] = libc_version

    return {
        "os": platform.system().lower(),
        "os_version": _host_os_version(),
        "arch": platform.machine().lower(),
        "cpu_model": cpu_model,
        "physical_cores": physical_cores,
        "logical_cores": logical_cores,
        "memory_bytes": memory_bytes,
        "attributes": host_attributes,
    }


@lru_cache(maxsize=1)
def rust_toolchain() -> dict[str, Any] | None:
    rustc_verbose = run_command(["rustc", "-Vv"])
    if rustc_verbose is None:
        return None

    verbose_lines = dict(
        line.split(": ", 1)
        for line in rustc_verbose.splitlines()
        if ": " in line
    )
    cargo_version = run_command(["cargo", "-V"])
    rustc_version = run_command(["rustc", "-V"]) or ""

    return {
        "rustc_version": rustc_version,
        "cargo_version": cargo_version,
        "channel": verbose_lines.get("release"),
        "host_triple": verbose_lines.get("host"),
        "target_triple": verbose_lines.get("host"),
    }


@lru_cache(maxsize=None)
def kotlin_toolchain(build_file: Path) -> dict[str, Any] | None:
    content = build_file.read_text()
    version_match = re.search(r'kotlin\("jvm"\) version "([^"]+)"', content)
    gradle_version_text = run_command(["./gradlew", "-version"], cwd=build_file.parent)
    gradle_version_match = re.search(r"Gradle (\S+)", gradle_version_text or "")
    jvm_target_match = re.search(r"jvmToolchain\((\d+)\)", content)
    if version_match is None and gradle_version_match is None and jvm_target_match is None:
        return None

    return {
        "kotlin_version": version_match.group(1) if version_match else "unknown",
        "gradle_version": gradle_version_match.group(1) if gradle_version_match else None,
        "jvm_target": jvm_target_match.group(1) if jvm_target_match else None,
    }


@lru_cache(maxsize=None)
def java_toolchain_from_jmh(jvm_path: str | None, jdk_version: str | None, vm_name: str | None) -> dict[str, Any] | None:
    if jdk_version is None:
        return None

    java_vendor = None
    if jvm_path:
        java_settings = run_command([jvm_path, "-XshowSettings:properties", "-version"])
        if java_settings:
            vendor_match = re.search(r"^\s*java\.vendor\s*=\s*(.+)$", java_settings, re.MULTILINE)
            if vendor_match:
                java_vendor = vendor_match.group(1).strip()

    return {
        "java_version": jdk_version,
        "vendor": java_vendor,
        "vm_name": vm_name,
    }


@lru_cache(maxsize=1)
def swift_toolchain() -> dict[str, Any] | None:
    version_output = run_command(["swift", "--version"])
    if version_output is None:
        return None

    target_match = re.search(r"Target:\s*(.+)$", version_output, re.MULTILINE)
    return {
        "swift_version": version_output.splitlines()[0],
        "target_triple": target_match.group(1).strip() if target_match else None,
        "compiler_flags": [],
    }


@lru_cache(maxsize=1)
def node_toolchain() -> dict[str, Any] | None:
    node_version = run_command(["node", "--version"])
    if node_version is None:
        return None

    npm_version = run_command(["npm", "--version"])
    package_manager = f"npm {npm_version}" if npm_version else None
    return {
        "node_version": node_version,
        "package_manager": package_manager,
    }


def ci_context() -> dict[str, Any] | None:
    if os.getenv("GITHUB_ACTIONS") == "true":
        return {
            "provider": "github_actions",
            "workflow": os.getenv("GITHUB_WORKFLOW"),
            "job": os.getenv("GITHUB_JOB"),
            "runner": os.getenv("RUNNER_NAME"),
        }

    if os.getenv("CI"):
        return {
            "provider": os.getenv("CI_PROVIDER", "generic_ci"),
            "workflow": os.getenv("CI_WORKFLOW"),
            "job": os.getenv("CI_JOB"),
            "runner": os.getenv("CI_RUNNER"),
        }

    return None


def collector_context(invocation: str | None = None) -> dict[str, Any]:
    return {
        "name": "mobiffi-benchmark-adapters",
        "version": "0.1.0",
        "invocation": invocation,
    }


def artifacts(paths: list[Path]) -> list[dict[str, Any]]:
    return [
        {
            "kind": path.suffix.lstrip(".") or "file",
            "path": str(path),
            "sha256": sha256_file(path),
        }
        for path in paths
    ]


def build_run_id(suite_name: str, collected_at: str, commit_sha: str) -> str:
    timestamp = collected_at.lower().replace(":", "-")
    short_sha = commit_sha[:12]
    normalized_suite = re.sub(r"[^a-z0-9]+", "-", suite_name.lower()).strip("-")
    return f"{timestamp}-{normalized_suite}-{short_sha}"


@lru_cache(maxsize=None)
def crate_version(cargo_toml: Path) -> str | None:
    content = cargo_toml.read_text()
    for line in content.splitlines():
        if line.startswith("version = "):
            return line.split("=", 1)[1].strip().strip('"')
    return None


def parse_scale_token(token: str) -> int | None:
    lowered = token.lower()
    if lowered == "small":
        return None
    match = re.fullmatch(r"(\d+)(k)?", lowered)
    if match is None:
        return None
    value = int(match.group(1))
    if match.group(2):
        value *= 1000
    return value


def humanize(text: str) -> str:
    return text.replace("_", " ")


def infer_descriptor(base_name: str, platform_name: str, language_name: str) -> dict[str, Any]:
    catalog_case = lookup_case_spec(base_name)
    if catalog_case is not None:
        return {
            "id": catalog_case.canonical_name,
            "group": catalog_case.group,
            "title": catalog_case.title,
            "category": catalog_case.category,
            "sophistication": catalog_case.sophistication,
            "direction": catalog_case.direction,
            "platform": platform_name,
            "language": language_name,
            "description": catalog_case.description,
            "tags": list(catalog_case.tags),
            "parameters": dict(catalog_case.parameters),
        }

    if base_name.startswith("coverage_"):
        family_id = base_name.removeprefix("coverage_").replace("__", ".")
        if family := FAMILY_LOOKUP.get(family_id):
            return {
                "id": base_name,
                "group": f"coverage.{family.family_id}",
                "title": family.title,
                "category": family.category,
                "sophistication": "coverage",
                "direction": "mixed",
                "platform": platform_name,
                "language": language_name,
                "description": f"Grouped coverage benchmark for {family.title}",
                "tags": ["coverage", family.family_id],
                "parameters": {"family_id": family.family_id, "variant_axes": list(family.variant_axes)},
            }

    descriptor = {
        "id": base_name,
        "group": base_name,
        "title": humanize(base_name).title(),
        "category": "misc",
        "sophistication": "basic",
        "direction": "mixed",
        "platform": platform_name,
        "language": language_name,
        "description": None,
        "tags": [],
        "parameters": {},
    }

    if base_name == "noop":
        return _descriptor_with(descriptor, category="primitives", group="primitives.noop")

    if base_name in {"add", "multiply", "echo_i32", "echo_f64", "inc_u64", "inc_u64_value"}:
        return _descriptor_with(descriptor, category="primitives", group=f"primitives.{base_name}")

    if base_name.startswith("echo_string_"):
        size_token = base_name.removeprefix("echo_string_")
        parameters = {}
        if size_token == "small":
            parameters["string_length"] = 5
        else:
            string_length = parse_scale_token(size_token)
            if string_length is not None:
                parameters["string_length"] = string_length
        return _descriptor_with(
            descriptor,
            category="strings",
            group="strings.echo",
            parameters=parameters,
        )

    if base_name.startswith("generate_string_"):
        string_length = parse_scale_token(base_name.removeprefix("generate_string_"))
        return _descriptor_with(
            descriptor,
            category="strings",
            group="strings.generate",
            direction="rust_to_host",
            parameters=_maybe_parameter("string_length", string_length),
        )

    if base_name.startswith("generate_bytes_"):
        return _descriptor_with(
            descriptor,
            category="bytes",
            group="bytes.generate",
            direction="rust_to_host",
            parameters=_scale_parameters("bytes", base_name.removeprefix("generate_bytes_")),
        )

    if base_name.startswith("generate_"):
        entity_name, scale_token = _split_suffix(base_name.removeprefix("generate_"))
        namespace, category = _entity_info(entity_name)
        parameters = _scale_parameters(entity_name, scale_token)
        sophistication = "structured" if category in {"records", "collections", "enums"} else "basic"
        return _descriptor_with(
            descriptor,
            category=category,
            sophistication=sophistication,
            direction="rust_to_host",
            group=f"{namespace}.generate",
            parameters=parameters,
        )

    if base_name.startswith("sum_"):
        entity_name, scale_token = _split_suffix(base_name.removeprefix("sum_"))
        group_name, category, direction = _aggregate_info(entity_name, operation="sum")
        sophistication = "structured" if category in {"records", "collections"} else "basic"
        return _descriptor_with(
            descriptor,
            category=category,
            sophistication=sophistication,
            direction=direction,
            group=group_name,
            parameters=_scale_parameters(entity_name, scale_token),
        )

    if base_name.startswith("avg_"):
        entity_name, scale_token = _split_suffix(base_name.removeprefix("avg_"))
        group_name, category, direction = _aggregate_info(entity_name, operation="average")
        return _descriptor_with(
            descriptor,
            category=category,
            sophistication="structured",
            direction=direction,
            group=group_name,
            parameters=_scale_parameters(entity_name, scale_token),
        )

    if base_name.startswith("process_"):
        entity_name, scale_token = _split_suffix(base_name.removeprefix("process_"))
        namespace, category = _entity_info(entity_name)
        sophistication = "structured" if category in {"records", "collections"} else "basic"
        return _descriptor_with(
            descriptor,
            category=category,
            sophistication=sophistication,
            direction="host_to_rust",
            group=f"{namespace}.process",
            parameters=_scale_parameters(entity_name, scale_token),
        )

    if base_name.startswith("count_"):
        entity_name, scale_token = _split_suffix(base_name.removeprefix("count_"))
        group_name, category, direction = _aggregate_info(entity_name, operation="count")
        sophistication = "structured" if category in {"records", "enums"} else "basic"
        return _descriptor_with(
            descriptor,
            category=category,
            sophistication=sophistication,
            direction=direction,
            group=group_name,
            parameters=_scale_parameters(entity_name, scale_token),
        )

    if base_name.startswith("counter_increment_"):
        return _descriptor_with(
            descriptor,
            category="classes",
            sophistication="complex",
            direction="host_to_rust",
            group="classes.counter.increment",
            parameters=_class_iteration_parameters(base_name.removeprefix("counter_increment_")),
        )

    if base_name == "datastore_add":
        return _descriptor_with(
            descriptor,
            category="classes",
            sophistication="complex",
            direction="host_to_rust",
            group="classes.datastore.add",
            parameters={"count": 1000, "mode": "record"},
        )

    if base_name.startswith("datastore_add_"):
        mode_name, scale_token = _split_suffix(base_name.removeprefix("datastore_add_"))
        parameters = _scale_parameters("items", scale_token)
        if "count" not in parameters:
            parameters["count"] = 1000
        parameters["mode"] = mode_name
        return _descriptor_with(
            descriptor,
            category="classes",
            sophistication="complex",
            direction="host_to_rust",
            group="classes.datastore.add",
            parameters=parameters,
        )

    if base_name.startswith("accumulator_"):
        return _descriptor_with(
            descriptor,
            category="classes",
            sophistication="complex",
            direction="host_to_rust",
            group="classes.accumulator.add",
            parameters=_class_iteration_parameters(base_name.removeprefix("accumulator_")),
        )

    if base_name == "simple_enum":
        return _descriptor_with(
            descriptor,
            category="enums",
            group="enums.direction.basic_ops",
            description="Calls oppositeDirection and directionToDegrees with simple enum values.",
        )

    if base_name == "data_enum_input":
        return _descriptor_with(
            descriptor,
            category="enums",
            group="enums.task_status.input",
            description="Passes data-carrying enum values into Rust and inspects the result.",
        )

    if base_name == "find_even":
        return _descriptor_with(
            descriptor,
            category="options",
            group="options.find_even",
            parameters={"count": 100},
        )

    if base_name.startswith("find_even_"):
        return _descriptor_with(
            descriptor,
            category="options",
            group="options.find_even",
            parameters=_scale_parameters("find_even", base_name.removeprefix("find_even_")),
        )

    if base_name.startswith("callback_"):
        callback_count = parse_scale_token(base_name.removeprefix("callback_"))
        return _descriptor_with(
            descriptor,
            category="callbacks",
            sophistication="callback",
            direction="callback",
            group="callbacks.data_provider.compute_sum",
            parameters=_maybe_parameter("count", callback_count),
        )

    if base_name.startswith("roundtrip_"):
        entity_name, scale_token = _split_suffix(base_name.removeprefix("roundtrip_"))
        namespace, category = _entity_info(entity_name)
        sophistication = "structured" if category in {"records", "collections"} else "basic"
        return _descriptor_with(
            descriptor,
            category=category,
            sophistication=sophistication,
            direction="roundtrip",
            group=f"{namespace}.roundtrip",
            parameters=_scale_parameters(entity_name, scale_token),
        )

    if base_name.startswith("async_"):
        operation_name = base_name.removeprefix("async_")
        return _descriptor_with(
            descriptor,
            category="async_fns",
            sophistication="async",
            direction="mixed",
            group=f"async_fns.{operation_name}",
        )

    return descriptor


def _descriptor_with(descriptor: dict[str, Any], **updates: Any) -> dict[str, Any]:
    updated_descriptor = dict(descriptor)
    updated_descriptor.update(updates)
    return updated_descriptor


def _host_os_version() -> str | None:
    if platform.system() == "Darwin":
        version = run_command(["sw_vers", "-productVersion"])
        if version:
            return version
    return platform.version() or None


def _split_suffix(value: str) -> tuple[str, str | None]:
    head, _, tail = value.rpartition("_")
    if not head:
        return value, None
    if parse_scale_token(tail) is not None:
        return head, tail
    return value, None


def _entity_info(name: str) -> tuple[str, str]:
    if name in ENTITY_GROUPS:
        return ENTITY_GROUPS[name]
    return (f"records.{name}", "misc")


def _aggregate_info(name: str, operation: str) -> tuple[str, str, str]:
    if name in AGGREGATE_GROUPS:
        return AGGREGATE_GROUPS[name]

    namespace, category = _entity_info(name)
    return (f"{namespace}.{operation}", category, "host_to_rust")


def _scale_parameters(noun: str, scale_token: str | None) -> dict[str, Any]:
    if scale_token is None:
        return {}

    if noun == "bytes":
        kibibytes_match = re.fullmatch(r"(\d+)k", scale_token.lower())
        if kibibytes_match is not None:
            return {"size_bytes": int(kibibytes_match.group(1)) * 1024}

    scale_value = parse_scale_token(scale_token)
    if scale_value is None:
        return {}

    if noun == "string":
        return {"string_length": scale_value}
    return {"count": scale_value}


def _class_iteration_parameters(token: str) -> dict[str, Any]:
    iteration_count = parse_scale_token(token)
    if iteration_count is not None:
        return {"iterations": iteration_count}

    return {
        "iterations": 1000,
        "synchronization": token,
    }


def _maybe_parameter(name: str, value: int | None) -> dict[str, Any]:
    if value is None:
        return {}
    return {name: value}


def _int_or_none(value: str | None) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except ValueError:
        return None
