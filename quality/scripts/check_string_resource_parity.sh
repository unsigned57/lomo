#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"

python3 - "$repo_root" <<'PY'
import sys
from pathlib import Path
from xml.etree import ElementTree

repo_root = Path(sys.argv[1])
module_resource_roots = {
    "app": Path("res"),
    "ui-components": Path("composeResources"),
}
resource_tags = {"string", "plurals", "string-array"}


def resource_keys(path):
    try:
        root = ElementTree.parse(path).getroot()
    except ElementTree.ParseError as error:
        raise SystemExit(f"{path.relative_to(repo_root)}: invalid XML: {error}") from error

    keys = set()
    for child in root:
        if child.tag in resource_tags and "name" in child.attrib:
            keys.add(child.attrib["name"])
    return keys


failures = []
checked = []


def module_keys(directory):
    """Keys of every string/plurals/string-array in any XML of one values directory."""
    keys = {}
    for path in sorted(directory.glob("*.xml")):
        for key in resource_keys(path):
            if key in keys:
                failures.append(f"{path.relative_to(repo_root)}: duplicate key {key} (also in {keys[key]})")
            keys[key] = path.name
    return set(keys)


for module, resource_root in module_resource_roots.items():
    mod_dir = repo_root / "apps/android" / module
    if not mod_dir.exists():
        mod_dir = repo_root / module
    default_dir = mod_dir / resource_root / "values"
    zh_dir = mod_dir / resource_root / "values-zh-rCN"
    # Both locales are required for every listed module; a missing directory is a broken
    # resource layout, never a module to skip.
    if not default_dir.is_dir():
        failures.append(f"{module}: missing default values directory {default_dir.relative_to(repo_root)}")
        continue
    if not zh_dir.is_dir():
        failures.append(f"{module}: missing values-zh-rCN directory {zh_dir.relative_to(repo_root)}")
        continue

    default_keys = module_keys(default_dir)
    zh_keys = module_keys(zh_dir)
    if not default_keys:
        failures.append(f"{module}: no string resources under {default_dir.relative_to(repo_root)}")
        continue
    checked.append(f"{module} ({len(default_keys)} keys)")

    missing_zh = sorted(default_keys - zh_keys)
    missing_default = sorted(zh_keys - default_keys)

    if missing_zh:
        failures.append(
            f"{module}: missing in values-zh-rCN: {', '.join(missing_zh)}"
        )
    if missing_default:
        failures.append(
            f"{module}: missing in values: {', '.join(missing_default)}"
        )

if failures:
    print("string resource parity FAILED", file=sys.stderr)
    for failure in failures:
        print(f"- {failure}", file=sys.stderr)
    sys.exit(1)

print(f"string resource parity OK: {', '.join(checked)}")
PY
