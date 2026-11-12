#!/usr/bin/env bash
# Android Lint via SDK lint CLI with a Toolchain-derived project descriptor.
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
kotlin_android_sdk="${LOMO_KOTLIN_ANDROID_SDK:?xtask must provide LOMO_KOTLIN_ANDROID_SDK}"
# App version facts come from xtask (single source: app/module.yaml); the script must not
# carry a second copy that can drift.
app_version_code="${LOMO_APP_VERSION_CODE:?xtask must provide LOMO_APP_VERSION_CODE}"
app_version_name="${LOMO_APP_VERSION_NAME:?xtask must provide LOMO_APP_VERSION_NAME}"
app_min_sdk="${LOMO_APP_MIN_SDK:?xtask must provide LOMO_APP_MIN_SDK}"
app_target_sdk="${LOMO_APP_TARGET_SDK:?xtask must provide LOMO_APP_TARGET_SDK}"
app_compile_sdk="${LOMO_APP_COMPILE_SDK:?xtask must provide LOMO_APP_COMPILE_SDK}"
runtime_modules="${LOMO_ANDROID_RUNTIME_MODULES:?xtask must provide the packaged runtime module closure}"
# Slack compose-lints is pinned by version and content hash. The activation canary below
# verifies that the installed SDK lint CLI actually loads and runs its Compose rules.
compose_lint_version="1.6.1"
compose_lint_sha1="fc68271e25cc6e6435e21fc8be3387846b7b51ac"
compose_lint_jar_name="compose-lint-checks-${compose_lint_version}.jar"
compose_lint_url="https://repo1.maven.org/maven2/com/slack/lint/compose/compose-lint-checks/${compose_lint_version}/${compose_lint_jar_name}"

lint_bin="${LOMO_ANDROID_LINT:-$kotlin_android_sdk/cmdline-tools/latest/bin/lint}"
if [ ! -x "$lint_bin" ]; then
  echo "kotlin-android-lint-check: Android lint not found at $lint_bin" >&2
  exit 1
fi

report_root="$repo_root/reports/android-lint"
mkdir -p "$report_root"
project_xml="$report_root/project.xml"
report_xml="$report_root/lint-report.xml"
report_html="$report_root/lint-report.html"
build_dir="${LOMO_LINT_BUILD_DIR:-${LOMO_KOTLIN_BUILD_DIR:-${LOMO_GENERATED_ROOT:-$repo_root/target/lomo}/build/kotlin}}"
cache_root="${XDG_CACHE_HOME:-${HOME:?HOME must be set}/.cache}"
compose_lint_cache_dir="${LOMO_COMPOSE_LINT_CACHE_DIR:-$cache_root/lomo/lint-checks}"
compose_lint_jar="$compose_lint_cache_dir/$compose_lint_jar_name"

if [ ! -f "$compose_lint_jar" ]; then
  echo "kotlin-android-lint-check: downloading compose-lint-checks ${compose_lint_version}"
  mkdir -p "$compose_lint_cache_dir"
  curl -fsSL -o "$compose_lint_jar.partial" "$compose_lint_url" \
    || { rm -f "$compose_lint_jar.partial"; echo "kotlin-android-lint-check: failed to download $compose_lint_url" >&2; exit 1; }
  mv "$compose_lint_jar.partial" "$compose_lint_jar"
fi
# A cached jar is verified on every run; a mismatched cache is never trusted.
if [ "$(sha1sum "$compose_lint_jar" | awk '{print $1}')" != "$compose_lint_sha1" ]; then
  echo "kotlin-android-lint-check: checksum mismatch for $compose_lint_jar" >&2
  exit 1
fi
echo "kotlin-android-lint-check: compose lint checks: $compose_lint_jar"

echo "kotlin-android-lint-check: generating lint project descriptor"
python3 - "$repo_root" "$build_dir" "$project_xml" "$app_version_code" "$app_version_name" "$app_min_sdk" "$app_target_sdk" "$app_compile_sdk" "$runtime_modules" "$compose_lint_jar" <<'PY'
import json
import sys
from pathlib import Path

repo_root = Path(sys.argv[1])
build_dir = Path(sys.argv[2])
project_xml = Path(sys.argv[3])
app_version_code = sys.argv[4]
app_version_name = sys.argv[5]
app_min_sdk = sys.argv[6]
app_target_sdk = sys.argv[7]
app_compile_sdk = sys.argv[8]
runtime_modules = json.loads(sys.argv[9])
compose_lint_jar = Path(sys.argv[10])
if not isinstance(runtime_modules, list) or not runtime_modules or not all(isinstance(item, str) for item in runtime_modules):
    raise SystemExit("kotlin-android-lint-check: invalid packaged runtime module closure")

sys.path.insert(0, str(repo_root / "quality/scripts"))
from kotlin_analysis_input import load_model, runtime_inputs, runtime_lint_check_jars

model = load_model(build_dir, "app")
# Manifest registrations resolve against the packaged runtime graph. Keep the compile model
# untouched: app Kotlin must still be unable to import runtime-only data/native implementations.
unique_cp = list(dict.fromkeys(
    [Path(path) for path in model["compile_classpath"]] + runtime_inputs(build_dir, runtime_modules)
))
# Every packaged first-party module runs on the app's minSdk floor, so each is a lint library
# module of the app. Generated FFI bindings are owned by the binding generator, not by lint.
library_modules = [module for module in runtime_modules if module not in {"app", "native-bindings"}]
library_models = {module: load_model(build_dir, module) for module in library_modules}
# Rules configured in quality/android/lint.xml come from these jars. The CLI's --lint-rule-jars
# flag is ignored in --project mode, so checks are declared per module in the descriptor.
lint_check_jars = [compose_lint_jar, *runtime_lint_check_jars(build_dir)]


def source_files(module_model):
    # The lint CLI project descriptor silently ignores <src> directories: only listed files are
    # analyzed. An empty listing would turn every source check into a vacuous pass.
    files = sorted(
        path
        for root in module_model["source_roots"]
        for path in Path(root).rglob("*")
        if path.suffix in {".kt", ".java"} and path.is_file()
    )
    if not files:
        raise SystemExit(f"kotlin-android-lint-check: no Kotlin/Java sources for {module_model['module']}")
    return files

# Amper keeps min/target SDK in module.yaml, not the source manifest. Inject for lint model.
app_root = repo_root / "apps/android/app"
if not app_root.exists():
    raise SystemExit(f"kotlin-android-lint-check: missing expected app root: {app_root}")
src_manifest = (app_root / "src/AndroidManifest.xml").read_text(encoding="utf-8")
if "uses-sdk" not in src_manifest:
    src_manifest = src_manifest.replace(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"',
        (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:versionCode="{app_version_code}"\n'
            f'    android:versionName="{app_version_name}"'
        ),
        1,
    )
    manifest_tag = src_manifest.find("<manifest")
    if manifest_tag < 0:
        raise SystemExit("kotlin-android-lint-check: <manifest> tag not found")
    insert_at = src_manifest.find(">", manifest_tag)
    if insert_at < 0:
        raise SystemExit("kotlin-android-lint-check: malformed <manifest> tag")
    src_manifest = (
        src_manifest[: insert_at + 1]
        + f'\n    <uses-sdk android:minSdkVersion="{app_min_sdk}" android:targetSdkVersion="{app_target_sdk}" />'
        + src_manifest[insert_at + 1 :]
    )
merged_manifest = project_xml.parent / "merged-app-manifest.xml"
merged_manifest.write_text(src_manifest, encoding="utf-8")


def esc(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace('"', "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    "<project>",
    f'  <module name="app" android="true" library="false" compile-sdk-version="{app_compile_sdk}">',
    f'    <manifest file="{esc(str(merged_manifest))}" />',
]
lines.extend(f'    <src file="{esc(str(path))}" />' for path in source_files(model))
lines.append(f'    <resource file="{esc(str(app_root / "res"))}" />')
for jar in unique_cp:
    lines.append(f'    <classpath jar="{esc(str(jar))}" />')
lines.extend(f'    <dep module="{esc(module)}" />' for module in library_modules)
lines.extend(f'    <lint-checks jar="{esc(str(jar))}" />' for jar in lint_check_jars)
lines.append("  </module>")
source_counts = {"app": len(source_files(model))}
for module, library_model in library_models.items():
    library_manifest = project_xml.parent / f"{module}-lint-manifest.xml"
    library_manifest.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        f'package="com.lomo.lint.{module.replace("-", "")}">\n'
        f'    <uses-sdk android:minSdkVersion="{app_min_sdk}" />\n'
        "</manifest>\n",
        encoding="utf-8",
    )
    library_cp = list(dict.fromkeys(library_model["outputs"] + library_model["compile_classpath"]))
    files = source_files(library_model)
    source_counts[module] = len(files)
    lines.append(
        f'  <module name="{esc(module)}" android="true" library="true" compile-sdk-version="{app_compile_sdk}">'
    )
    lines.append(f'    <manifest file="{esc(str(library_manifest))}" />')
    lines.extend(f'    <src file="{esc(str(path))}" />' for path in files)
    lines.extend(f'    <classpath jar="{esc(str(jar))}" />' for jar in library_cp)
    lines.extend(f'    <lint-checks jar="{esc(str(jar))}" />' for jar in lint_check_jars)
    lines.append("  </module>")
lines.append("</project>")
project_xml.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(
    f"kotlin-android-lint-check: wrote {project_xml} with {len(unique_cp)} app classpath entries "
    f"from AnalysisInput {model['input_digest']}; sources per module: {source_counts}; "
    f"lint check jars: {len(lint_check_jars)}"
)
PY


echo "kotlin-android-lint-check: running lint"
set +e
# UnusedResources is error in lint.xml for AGP-era intent, but standalone CLI does not resolve
# Compose stringResource/R usages and flags nearly every string. Disable only on this CLI path.
# i18n key parity remains enforced by check_string_resource_parity.sh.
# Source analysis of every packaged module exceeds the launcher's 1 GiB default heap; an OOM
# surfaces as LintError and fails the gate instead of shrinking its coverage.
LINT_OPTS="${LINT_OPTS:--Xmx6g}" \
ANDROID_HOME="$kotlin_android_sdk" ANDROID_SDK_ROOT="$kotlin_android_sdk" \
  "$lint_bin" \
  --project "$project_xml" \
  --config "$repo_root/quality/android/lint.xml" \
  --disable UnusedResources \
  --exitcode \
  --sdk-home "$kotlin_android_sdk" \
  --compile-sdk-version "$app_compile_sdk" \
  --xml "$report_xml" \
  --html "$report_html" \
  --offline
lint_status=$?
set -e

if [ "$lint_status" -ne 0 ]; then
  echo "kotlin-android-lint-check: failed (exit $lint_status); see $report_html" >&2
  if [ -f "$report_xml" ]; then
    python3 - "$report_xml" <<'PY' || true
import sys
import xml.etree.ElementTree as ET
from collections import Counter

root = ET.parse(sys.argv[1]).getroot()
issues = root.findall("issue")
print(f"kotlin-android-lint-check: {len(issues)} issue node(s)")
for issue_id, count in Counter(i.get("id") for i in issues).most_common(20):
    print(f"  {count:4d}  {issue_id}")
for issue in issues[:10]:
    loc = issue.find("location")
    path = loc.get("file") if loc is not None else ""
    line = loc.get("line") if loc is not None else ""
    print(f"  - [{issue.get('id')}] {path}:{line}: {issue.get('message', '')[:140]}")
PY
  fi
  exit "$lint_status"
fi

echo "kotlin-android-lint-check: ok ($report_html)"
python3 "$repo_root/quality/scripts/test/android_lint_runtime_contract_test.py" \
  "$project_xml" "$build_dir" "$kotlin_android_sdk" "$lint_bin"
