from __future__ import annotations

import argparse
import sys
from collections import Counter, defaultdict

from audit_benchmark_catalog import collect_harness_case_names
from benchmark_catalog import lookup_case_spec
from benchmark_source_mapping import case_to_source_exports
from demo_benchmark_policy import BENCHMARK_FAMILIES, coverage_index
from demo_export_inventory import DemoExport, iter_demo_exports


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Audit the machine-readable benchmark policy against examples/demo."
    )
    parser.add_argument(
        "--fail-on-gap",
        action="store_true",
        help="Exit non-zero if any demo export is missing from the benchmark policy.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    exports = iter_demo_exports()
    export_lookup = {export.export_id: export for export in exports}
    policy = coverage_index()

    unknown_policy_exports = sorted(policy.keys() - export_lookup.keys())
    unclassified_exports = tuple(
        export for export in exports if export.export_id not in policy
    )

    direct_exports = {
        export_id for export_id, record in policy.items() if record.status == "direct"
    }
    represented_exports = {
        export_id for export_id, record in policy.items() if record.status == "represented_by"
    }
    skipped_exports = {
        export_id for export_id, record in policy.items() if record.status == "skip"
    }

    actual_harness_case_names = {
        harness.name: canonicalize_case_names(names)
        for harness, names in collect_harness_case_names().items()
    }
    actual_covered_exports = covered_exports_for_cases(
        set().union(*actual_harness_case_names.values()) if actual_harness_case_names else set()
    )
    direct_exports_currently_benchmarked = actual_covered_exports & direct_exports

    print(f"demo callable exports: {len(exports)}")
    print(f"benchmark policy families: {len(BENCHMARK_FAMILIES)}")
    print(f"policy direct exports: {len(direct_exports)}")
    print(f"policy represented exports: {len(represented_exports)}")
    print(f"policy skipped exports: {len(skipped_exports)}")
    print(f"unclassified exports: {len(unclassified_exports)}")
    print(f"actual harness-covered direct exports: {len(direct_exports_currently_benchmarked)}")
    print()

    print("policy coverage by category:")
    for category, counts in sorted(category_counts(policy).items()):
        print(
            f"  {category}: direct={counts['direct']} represented={counts['represented_by']} skip={counts['skip']}"
        )
    print()

    print("actual harness coverage against direct policy exports:")
    for harness_name, case_names in sorted(actual_harness_case_names.items()):
        harness_exports = covered_exports_for_cases(case_names) & direct_exports
        print(
            f"  {harness_name}: {len(harness_exports)} direct exports exercised via {len(case_names)} canonical cases"
        )
    print()

    if unknown_policy_exports:
        print("policy exports not found in demo inventory:")
        for export_id in unknown_policy_exports:
            print(f"  {export_id}")
        print()

    if unclassified_exports:
        print("unclassified demo exports by module:")
        for module, module_exports in group_by_module(unclassified_exports).items():
            names = ", ".join(render_export_name(export) for export in module_exports)
            print(f"  {module} ({len(module_exports)}): {names}")
        print()

    uncovered_direct_exports = tuple(
        export_lookup[export_id]
        for export_id in sorted(direct_exports - direct_exports_currently_benchmarked)
        if export_id in export_lookup
    )
    if uncovered_direct_exports:
        print("direct policy exports not yet exercised by current harnesses:")
        for module, module_exports in group_by_module(uncovered_direct_exports).items():
            names = ", ".join(render_export_name(export) for export in module_exports)
            print(f"  {module} ({len(module_exports)}): {names}")
        print()

    return 1 if args.fail_on_gap and (unknown_policy_exports or unclassified_exports) else 0


def canonicalize_case_names(names: list[str]) -> set[str]:
    canonical_names: set[str] = set()
    for name in names:
        case = lookup_case_spec(name)
        canonical_names.add(case.canonical_name if case else name)
    return canonical_names


def covered_exports_for_cases(case_names: set[str]) -> set[str]:
    covered: set[str] = set()
    for case_name in case_names:
        covered.update(case_to_source_exports(case_name))
    return covered


def category_counts(policy: dict[str, object]) -> dict[str, Counter]:
    counts: dict[str, Counter] = defaultdict(Counter)
    for record in policy.values():
        counts[record.category][record.status] += 1
    return dict(counts)


def group_by_module(exports: tuple[DemoExport, ...]) -> dict[str, list[DemoExport]]:
    grouped: dict[str, list[DemoExport]] = defaultdict(list)
    for export in exports:
        grouped[export.module].append(export)
    return dict(sorted(grouped.items()))


def render_export_name(export: DemoExport) -> str:
    if export.owner:
        return f"{export.owner}::{export.name}"
    return export.name


if __name__ == "__main__":
    sys.exit(main())
