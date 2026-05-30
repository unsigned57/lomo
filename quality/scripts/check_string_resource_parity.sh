#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"

python3 - "$repo_root" <<'PY'
import sys
from pathlib import Path
from xml.etree import ElementTree

repo_root = Path(sys.argv[1])
modules = ("app", "data", "ui-components")
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
checked_modules = []

for module in modules:
    default_path = repo_root / module / "src/main/res/values/strings.xml"
    zh_path = repo_root / module / "src/main/res/values-zh-rCN/strings.xml"

    if not default_path.exists() and not zh_path.exists():
        continue
    if not default_path.exists():
        failures.append(f"{module}: missing default strings.xml")
        continue
    if not zh_path.exists():
        failures.append(f"{module}: missing values-zh-rCN/strings.xml")
        continue

    checked_modules.append(module)
    default_keys = resource_keys(default_path)
    zh_keys = resource_keys(zh_path)

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

print(f"string resource parity OK: {', '.join(checked_modules)}")
PY
