#!/usr/bin/env bash
set -euo pipefail
# Contract test: full Detekt consumes the owning compile model's generated friend outputs,
# compiler arguments, and plugins while excluding runtime-only data/native from app classpath.

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
export LOMO_KOTLIN_BUILD_DIR="${LOMO_KOTLIN_BUILD_DIR:-${LOMO_GENERATED_ROOT:-$repo_root/target/lomo}/build/kotlin}"

python3 - "$repo_root" <<'PY'
from pathlib import Path
import json
import os
import subprocess
import sys

repo = Path(sys.argv[1])
script = repo / "quality/scripts/kotlin_analysis_input.py"
build = Path(os.environ["LOMO_KOTLIN_BUILD_DIR"]).resolve()


def tokens(module: str) -> list[str]:
    raw = subprocess.check_output(
        ["python3", str(script), "detekt-args", module],
        cwd=repo,
    )
    return [item.decode() for item in raw.split(b"\0") if item]


def model(module: str) -> dict:
    variant = "jvm-main" if module == "domain" else "android-debug"
    path = build / "analysis-input" / f"{module}-{variant}.json"
    if not path.is_file():
        raise SystemExit(f"analysis-input contract: missing {path}")
    return json.loads(path.read_text())


ui = model("ui-components")
ui_args = tokens("ui-components")
generated = ui["generated_sources"]
if not generated:
    raise SystemExit("analysis-input contract: ui-components model has no generated_sources")
for path in generated:
    if not Path(path).exists():
        raise SystemExit(f"analysis-input contract: generated source missing: {path}")
inputs = [
    ui_args[index + 1]
    for index, item in enumerate(ui_args)
    if item == "--input" and index + 1 < len(ui_args)
]
linted_generated = [path for path in generated if path in inputs]
if linted_generated:
    raise SystemExit(
        "analysis-input contract: generated sources must not be Detekt inputs: "
        + ", ".join(linted_generated)
    )
if "--classpath" not in ui_args:
    raise SystemExit("analysis-input contract: ui-components detekt-args missing --classpath")
classpath_entries = ui_args[ui_args.index("--classpath") + 1].split(os.pathsep)
for output in ui["outputs"]:
    if output not in classpath_entries:
        raise SystemExit(f"analysis-input contract: compiled output missing from classpath: {output}")
    if f"-Xfriend-paths={output}" not in ui_args:
        raise SystemExit(f"analysis-input contract: missing friend path for compiled output: {output}")

for argument in ui["compiler_arguments"]:
    if argument not in ui_args:
        raise SystemExit(f"analysis-input contract: compiler argument not forwarded: {argument}")

plugin_tokens = [item for item in ui_args if item.startswith("-Xplugin=")]
if len(plugin_tokens) != len(ui["compiler_plugins"]):
    raise SystemExit(
        "analysis-input contract: compiler plugin count "
        f"{len(plugin_tokens)} != {len(ui['compiler_plugins'])}"
    )
for plugin, token in zip(ui["compiler_plugins"], plugin_tokens, strict=True):
    jar = Path(token.removeprefix("-Xplugin="))
    coords = plugin["coordinates"]
    expected_name = f"{coords['artifactId']}-{coords['version']}.jar"
    if jar.name != expected_name or not jar.is_file():
        raise SystemExit(f"analysis-input contract: plugin jar missing or mismatched: {token}")
    plugin_id = plugin["id"]
    for option in plugin["options"]:
        expected = f"plugin:{plugin_id}:{option['name']}={option['value']}"
        pairs = list(zip(ui_args, ui_args[1:]))
        if ("-P", expected) not in pairs:
            raise SystemExit(f"analysis-input contract: plugin option not forwarded: -P {expected}")

app_args = tokens("app")
classpath = ""
if "--classpath" in app_args:
    classpath = app_args[app_args.index("--classpath") + 1]
if "/CompiledJvmArtifact/data" in classpath or "/CompiledJvmArtifact/native-bindings" in classpath:
    raise SystemExit("analysis-input contract: app compile classpath leaked runtime-only modules")

print("analysis-input-contract: detekt-args consume generated sources and compiler plugins")
PY
