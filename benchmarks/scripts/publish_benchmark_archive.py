#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
from collections import defaultdict
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    repository_root = Path(__file__).resolve().parents[2]
    default_output_root = repository_root.parent / "boltffi_bench_harness" / "public" / "data"

    parser = argparse.ArgumentParser(
        description="Publish benchmark_run.json documents into the benchmark archive repo."
    )
    parser.add_argument(
        "incoming",
        nargs="*",
        type=Path,
        help="Incoming benchmark_run.json files or directories containing them.",
    )
    parser.add_argument(
        "--output-root",
        type=Path,
        default=default_output_root,
        help="Archive repo public/data directory.",
    )
    return parser.parse_args()


def collect_incoming_paths(incoming_inputs: list[Path]) -> list[Path]:
    incoming_paths: list[Path] = []

    for incoming_input in incoming_inputs:
        resolved_input = incoming_input.expanduser()
        if resolved_input.is_dir():
            incoming_paths.extend(sorted(resolved_input.rglob("*benchmark_run.json")))
        elif resolved_input.is_file():
            incoming_paths.append(resolved_input)
        else:
            raise SystemExit(f"Incoming path does not exist: {incoming_input}")

    unique_paths = sorted({path.resolve() for path in incoming_paths})
    return unique_paths


def load_incoming_run_documents(incoming_paths: list[Path]) -> list[dict[str, Any]]:
    return [json.loads(path.read_text()) for path in incoming_paths]


def load_archived_run_documents(output_root: Path) -> list[dict[str, Any]]:
    archive_root = output_root / "archive"
    if not archive_root.exists():
        return []

    documents: list[dict[str, Any]] = []
    for archive_path in sorted(archive_root.rglob("*.json")):
        documents.append(json.loads(archive_path.read_text()))

    return documents


def slugify(value: str) -> str:
    normalized = re.sub(r"[^a-z0-9]+", "-", value.lower())
    return normalized.strip("-")


def normalize_token(value: str | None) -> str:
    return slugify(value or "unknown")


def iso_now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_iso(iso_value: str) -> datetime:
    return datetime.fromisoformat(iso_value.replace("Z", "+00:00"))


def json_dump(document: Any, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n")


def canonical_json_bytes(document: Any) -> bytes:
    return json.dumps(
        document,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")


def compute_document_sha256(document: Any) -> str:
    return hashlib.sha256(canonical_json_bytes(document)).hexdigest()


def merge_run_documents(
    archived_run_documents: list[dict[str, Any]],
    incoming_run_documents: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    merged_by_run_id: dict[str, dict[str, Any]] = {}
    content_sha_by_run_id: dict[str, str] = {}

    for run_document in [*archived_run_documents, *incoming_run_documents]:
        run_id = run_document["run_id"]
        content_sha = compute_document_sha256(run_document)
        existing_sha = content_sha_by_run_id.get(run_id)

        if existing_sha is not None:
            if existing_sha != content_sha:
                raise SystemExit(
                    f"Conflicting benchmark run content for run_id={run_id}: "
                    f"{existing_sha[:12]} != {content_sha[:12]}"
                )
            continue

        merged_by_run_id[run_id] = run_document
        content_sha_by_run_id[run_id] = content_sha

    return list(merged_by_run_id.values())


def build_machine(environment: dict[str, Any]) -> dict[str, Any]:
    host = environment.get("host") or {}
    host_attributes = host.get("attributes") or {}
    ci = environment.get("ci")

    kind = "ci" if ci else "local"
    ci_provider = None
    ci_runner = None

    if ci:
        ci_provider = ci.get("provider") or ci.get("name") or ci.get("system")
        ci_runner = ci.get("runner") or ci.get("runner_name") or ci.get("label")

    cpu_model = host.get("cpu_model") or host_attributes.get("hostname") or "unknown machine"
    hostname = host_attributes.get("hostname") or "unknown-host"

    if kind == "ci":
        label = ci.get("label") if ci else None
        if not label and ci_provider and host.get("os"):
            label = f"{str(ci_provider).replace('_', ' ').title()} {str(host['os']).title()}"
        label = label or cpu_model
    else:
        label = cpu_model

    subtitle_anchor = ci_runner or ci_provider or hostname
    subtitle = (
        f"{host.get('os', 'unknown')} {host.get('os_version', 'unknown')} • "
        f"{host.get('arch', 'unknown')} • {kind} • {subtitle_anchor}"
    )

    machine_name_token = ci_runner or ci_provider or cpu_model
    machine_id = (
        f"{kind}-"
        f"{normalize_token(host.get('os'))}-"
        f"{normalize_token(host.get('arch'))}-"
        f"{normalize_token(machine_name_token)}"
    )

    return {
        "machine_id": machine_id,
        "label": label,
        "subtitle": subtitle,
        "kind": kind,
        "os": host.get("os"),
        "os_version": host.get("os_version"),
        "arch": host.get("arch"),
        "cpu_model": cpu_model,
        "hostname": hostname,
        "ci_provider": ci_provider,
        "ci_runner": ci_runner,
        "physical_cores": host.get("physical_cores"),
        "logical_cores": host.get("logical_cores"),
        "memory_bytes": host.get("memory_bytes"),
    }


def build_archive_path(output_root: Path, run_document: dict[str, Any]) -> tuple[str, str]:
    collected_at = parse_iso(run_document["collected_at"])
    content_sha256 = compute_document_sha256(run_document)
    archive_storage_path = (
        Path("archive")
        / f"{collected_at:%Y}"
        / f"{collected_at:%m}"
        / f"{collected_at:%d}"
        / f"{run_document['run_id']}--{content_sha256[:12]}.json"
    )
    archive_destination = output_root / archive_storage_path
    json_dump(run_document, archive_destination)
    return f"data/{archive_storage_path.as_posix()}", content_sha256


def build_run_index_entry(
    run_document: dict[str, Any],
    machine: dict[str, Any],
    archive_path: str,
    content_sha256: str,
) -> dict[str, Any]:
    repository = (run_document.get("provenance") or {}).get("repository") or {}
    suite = run_document.get("suite") or {}
    benchmarks = run_document.get("benchmarks") or []

    return {
        "run_id": run_document["run_id"],
        "collected_at": run_document["collected_at"],
        "repository": repository.get("name"),
        "branch": repository.get("branch"),
        "commit_sha": repository.get("commit_sha"),
        "suite_name": suite.get("name"),
        "harness": suite.get("harness"),
        "platform": suite.get("platform"),
        "language": suite.get("language"),
        "archive_path": archive_path,
        "content_sha256": content_sha256,
        "benchmark_count": len(benchmarks),
        "groups": sorted({benchmark["descriptor"]["group"] for benchmark in benchmarks}),
        "tools": sorted(
            {
                variant["subject"]["tool"]["name"]
                for benchmark in benchmarks
                for variant in benchmark.get("variants", [])
            }
        ),
        "machine": machine,
    }


def build_benchmark_run_entry(
    run_entry: dict[str, Any],
    benchmark_document: dict[str, Any],
) -> dict[str, Any]:
    return {
        "run_id": run_entry["run_id"],
        "collected_at": run_entry["collected_at"],
        "suite_name": run_entry["suite_name"],
        "platform": run_entry["platform"],
        "language": run_entry["language"],
        "harness": run_entry["harness"],
        "commit_sha": run_entry["commit_sha"],
        "archive_path": run_entry["archive_path"],
        "machine": run_entry["machine"],
        "descriptor": benchmark_document["descriptor"],
        "variants": benchmark_document.get("variants", []),
        "notes": benchmark_document.get("notes", []),
    }


def compute_benchmark_summary(benchmark_runs: list[dict[str, Any]]) -> dict[str, Any]:
    descriptor = benchmark_runs[0]["descriptor"]
    languages = sorted({run["language"] for run in benchmark_runs})
    latest_run = max(benchmark_runs, key=lambda run: parse_iso(run["collected_at"]))

    averages_by_language: dict[str, Any] = {}
    benchmark_runs_by_language = defaultdict(list)
    for benchmark_run in benchmark_runs:
        benchmark_runs_by_language[benchmark_run["language"]].append(benchmark_run)

    for language, language_runs in benchmark_runs_by_language.items():
        variants_by_tool = defaultdict(list)
        for language_run in language_runs:
            for variant in language_run["variants"]:
                variants_by_tool[variant["subject"]["tool"]["name"]].append(variant)

        tool_summaries = []
        for tool_name, variants in sorted(variants_by_tool.items()):
            metric_values = [variant["metrics"]["value"] for variant in variants]
            tool_summaries.append(
                {
                    "name": tool_name,
                    "unit": variants[0]["metrics"]["unit"],
                    "average_value": sum(metric_values) / len(metric_values),
                    "run_count": len(variants),
                }
            )

        averages_by_language[language] = {
            "run_count": len(language_runs),
            "tools": tool_summaries,
        }

    return {
        "id": descriptor["id"],
        "group": descriptor["group"],
        "title": descriptor["title"],
        "category": descriptor["category"],
        "sophistication": descriptor["sophistication"],
        "direction": descriptor["direction"],
        "parameters": descriptor.get("parameters") or {},
        "latest_collected_at": latest_run["collected_at"],
        "latest_run_id": latest_run["run_id"],
        "run_count": len(benchmark_runs),
        "languages": languages,
        "averages": averages_by_language,
        "view_path": f"data/views/benchmarks/{slugify(descriptor['id'])}.json",
    }


def main() -> None:
    args = parse_args()
    output_root: Path = args.output_root

    incoming_paths = collect_incoming_paths(args.incoming)
    archived_run_documents = load_archived_run_documents(output_root)
    incoming_run_documents = load_incoming_run_documents(incoming_paths)
    run_documents = merge_run_documents(archived_run_documents, incoming_run_documents)

    if not run_documents:
        raise SystemExit(
            f"No benchmark run documents found in archive={output_root / 'archive'} "
            f"or incoming inputs={args.incoming}"
        )

    if output_root.exists():
        shutil.rmtree(output_root)

    output_root.mkdir(parents=True, exist_ok=True)

    generated_at = iso_now()
    run_entries: list[dict[str, Any]] = []
    machine_runs: dict[str, list[dict[str, Any]]] = defaultdict(list)
    group_runs: dict[str, list[dict[str, Any]]] = defaultdict(list)
    benchmark_runs_by_id: dict[str, list[dict[str, Any]]] = defaultdict(list)

    for run_document in run_documents:
        machine = build_machine(run_document.get("environment") or {})
        archive_path, content_sha256 = build_archive_path(output_root, run_document)
        run_entry = build_run_index_entry(run_document, machine, archive_path, content_sha256)
        run_entries.append(run_entry)
        machine_runs[machine["machine_id"]].append(run_entry)

        for benchmark_document in run_document.get("benchmarks", []):
            benchmark_run_entry = build_benchmark_run_entry(run_entry, benchmark_document)
            group_runs[benchmark_document["descriptor"]["group"]].append(benchmark_run_entry)
            benchmark_runs_by_id[benchmark_document["descriptor"]["id"]].append(benchmark_run_entry)

    run_entries.sort(
        key=lambda run_entry: (
            parse_iso(run_entry["collected_at"]),
            run_entry["suite_name"],
        ),
        reverse=True,
    )

    machine_entries = []
    for machine_scoped_runs in machine_runs.values():
        machine_scoped_runs.sort(key=lambda run_entry: parse_iso(run_entry["collected_at"]), reverse=True)
        machine_entries.append(
            {
                "machine": machine_scoped_runs[0]["machine"],
                "latest_collected_at": machine_scoped_runs[0]["collected_at"],
                "run_count": len(machine_scoped_runs),
                "suite_count": len({run_entry["suite_name"] for run_entry in machine_scoped_runs}),
                "group_count": len(
                    {group for run_entry in machine_scoped_runs for group in run_entry["groups"]}
                ),
                "tools": sorted(
                    {tool for run_entry in machine_scoped_runs for tool in run_entry["tools"]}
                ),
                "platforms": sorted({run_entry["platform"] for run_entry in machine_scoped_runs}),
                "languages": sorted({run_entry["language"] for run_entry in machine_scoped_runs}),
            }
        )

    machine_entries.sort(
        key=lambda entry: (parse_iso(entry["latest_collected_at"]), entry["run_count"]),
        reverse=True,
    )

    group_entries = []
    for group_name, grouped_runs in group_runs.items():
        grouped_runs.sort(key=lambda run_entry: parse_iso(run_entry["collected_at"]), reverse=True)
        descriptor = grouped_runs[0]["descriptor"]
        latest_group_run = grouped_runs[0]
        group_view_document = {
            "schema_version": "benchmark_group_view_v1",
            "generated_at": generated_at,
            "group": descriptor["group"],
            "title": descriptor["title"],
            "category": descriptor["category"],
            "sophistication": descriptor["sophistication"],
            "direction": descriptor["direction"],
            "latest_run_id": latest_group_run["run_id"],
            "latest_archive_path": latest_group_run["archive_path"],
            "runs": grouped_runs,
        }
        group_view_path = output_root / "views" / "groups" / f"{slugify(group_name)}.json"
        json_dump(group_view_document, group_view_path)

        group_entries.append(
            {
                "group": descriptor["group"],
                "title": descriptor["title"],
                "category": descriptor["category"],
                "sophistication": descriptor["sophistication"],
                "direction": descriptor["direction"],
                "latest_collected_at": grouped_runs[0]["collected_at"],
                "run_count": len({run_entry["run_id"] for run_entry in grouped_runs}),
                "machine_count": len({run_entry["machine"]["machine_id"] for run_entry in grouped_runs}),
                "machine_ids": sorted(
                    {run_entry["machine"]["machine_id"] for run_entry in grouped_runs}
                ),
                "view_path": f"data/views/groups/{slugify(group_name)}.json",
            }
        )

    group_entries.sort(
        key=lambda group_entry: (
            parse_iso(group_entry["latest_collected_at"]),
            group_entry["title"],
        ),
        reverse=True,
    )

    benchmark_summary_entries = []
    for benchmark_id, benchmark_runs in benchmark_runs_by_id.items():
        benchmark_runs.sort(key=lambda run_entry: parse_iso(run_entry["collected_at"]), reverse=True)
        latest_benchmark_run = benchmark_runs[0]
        benchmark_view_document = {
            "schema_version": "benchmark_view_v1",
            "generated_at": generated_at,
            "benchmark_id": benchmark_id,
            "group": benchmark_runs[0]["descriptor"]["group"],
            "title": benchmark_runs[0]["descriptor"]["title"],
            "category": benchmark_runs[0]["descriptor"]["category"],
            "sophistication": benchmark_runs[0]["descriptor"]["sophistication"],
            "direction": benchmark_runs[0]["descriptor"]["direction"],
            "parameters": benchmark_runs[0]["descriptor"].get("parameters") or {},
            "latest_run_id": latest_benchmark_run["run_id"],
            "latest_archive_path": latest_benchmark_run["archive_path"],
            "runs": benchmark_runs,
        }
        benchmark_view_path = output_root / "views" / "benchmarks" / f"{slugify(benchmark_id)}.json"
        json_dump(benchmark_view_document, benchmark_view_path)

        benchmark_summary_entries.append(compute_benchmark_summary(benchmark_runs))

    benchmark_summary_entries.sort(
        key=lambda entry: (parse_iso(entry["latest_collected_at"]), entry["title"]),
        reverse=True,
    )

    benchmark_catalog_document = {
        "schema_version": "benchmark_catalog_v1",
        "generated_at": generated_at,
        "benchmarks": benchmark_summary_entries,
    }
    json_dump(benchmark_catalog_document, output_root / "catalog" / "benchmarks" / "index.json")

    site_index_document = {
        "schema_version": "benchmark_site_index_v1",
        "generated_at": generated_at,
        "totals": {
            "run_count": len(run_entries),
            "group_count": len(group_entries),
        },
        "machines": machine_entries,
        "runs": run_entries,
        "groups": group_entries,
    }
    json_dump(site_index_document, output_root / "index.json")


if __name__ == "__main__":
    main()
