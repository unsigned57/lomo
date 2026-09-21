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
# compose-lint-checks is version-pinned and checksum-verified; a missing jar is a hard error,
# never a silent degradation of the Compose lint surface.
compose_lint_version="${LOMO_COMPOSE_LINT_VERSION:-1.4.3}"
compose_lint_jar_name="compose-lint-checks-${compose_lint_version}.jar"

lint_bin="${LOMO_ANDROID_LINT:-$kotlin_android_sdk/cmdline-tools/latest/bin/lint}"
if [ ! -x "$lint_bin" ]; then
  echo "kotlin-android-lint-check: Android lint not found at $lint_bin" >&2
  exit 1
fi

cargo_target="${CARGO_TARGET_DIR:-$repo_root/target}"
report_root="${LOMO_GENERATED_ROOT:-$cargo_target/lomo}"
case "$report_root" in
  /*) ;;
  *) report_root="$repo_root/$report_root" ;;
esac
report_root="$report_root/reports/android-lint"
mkdir -p "$report_root"
project_xml="$report_root/project.xml"
report_xml="$report_root/lint-report.xml"
report_html="$report_root/lint-report.html"
expanded_dir="$report_root/expanded-aars"
build_dir="${LOMO_LINT_BUILD_DIR:-${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/shared}}"
cache_root="${XDG_CACHE_HOME:-${HOME:?HOME must be set}/.cache}"
compose_lint_cache_dir="${LOMO_COMPOSE_LINT_CACHE_DIR:-$cache_root/lomo/lint-checks}"

echo "kotlin-android-lint-check: generating lint project descriptor"
python3 - "$repo_root" "$build_dir" "$project_xml" "$kotlin_android_sdk" "$expanded_dir" "$app_version_code" "$app_version_name" "$app_min_sdk" "$app_target_sdk" "$app_compile_sdk" <<'PY'
import json
import re
import sys
import zipfile
from pathlib import Path

repo_root = Path(sys.argv[1])
build_dir = Path(sys.argv[2])
project_xml = Path(sys.argv[3])
sdk = Path(sys.argv[4])
expanded_dir = Path(sys.argv[5])
app_version_code = sys.argv[6]
app_version_name = sys.argv[7]
app_min_sdk = sys.argv[8]
app_target_sdk = sys.argv[9]
app_compile_sdk = sys.argv[10]

sys.path.insert(0, str(repo_root / "quality/scripts"))
from kotlin_analysis_input import load_model

model = load_model(build_dir, "app")
unique_cp = [Path(path) for path in model["compile_classpath"]]

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
    f'    <src file="{esc(str(app_root / "src"))}" />',
    f'    <resource file="{esc(str(app_root / "res"))}" />',
]
for jar in unique_cp:
    lines.append(f'    <classpath jar="{esc(str(jar))}" />')
lines.append("  </module>")
lines.append("</project>")
project_xml.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(
    f"kotlin-android-lint-check: wrote {project_xml} with {len(unique_cp)} classpath entries "
    f"from AnalysisInput {model['input_digest']}"
)
PY

compose_lint_jar=""
compose_lint_candidates=(
  "${GRADLE_USER_HOME:-$HOME/.gradle}"
  "$compose_lint_cache_dir"
)
for candidate_root in "${compose_lint_candidates[@]}"; do
  found="$(find "$candidate_root" -name "$compose_lint_jar_name" 2>/dev/null | head -1 || true)"
  if [ -n "$found" ]; then
    compose_lint_jar="$found"
    break
  fi
done
if [ -z "$compose_lint_jar" ]; then
  echo "kotlin-android-lint-check: downloading compose-lint-checks ${compose_lint_version}"
  mkdir -p "$compose_lint_cache_dir"
  compose_lint_url="https://maven.google.com/com/google/compose/compose-lint-checks/${compose_lint_version}/${compose_lint_jar_name}"
  download_target="$compose_lint_cache_dir/$compose_lint_jar_name"
  curl -fsSL -o "$download_target.partial" "$compose_lint_url" \
    || { rm -f "$download_target.partial"; echo "kotlin-android-lint-check: failed to download $compose_lint_url" >&2; exit 1; }
  expected_sha1="$(curl -fsSL "$compose_lint_url.sha1" | awk '{print $1}')" \
    || { rm -f "$download_target.partial"; echo "kotlin-android-lint-check: failed to fetch checksum for $compose_lint_jar_name" >&2; exit 1; }
  actual_sha1="$(sha1sum "$download_target.partial" | awk '{print $1}')"
  if [ "$actual_sha1" != "$expected_sha1" ]; then
    rm -f "$download_target.partial"
    echo "kotlin-android-lint-check: checksum mismatch for $compose_lint_jar_name" >&2
    exit 1
  fi
  mv "$download_target.partial" "$download_target"
  compose_lint_jar="$download_target"
fi
echo "kotlin-android-lint-check: compose lint checks: $compose_lint_jar"
lint_rule_args=(--lint-rule-jars "$compose_lint_jar")

echo "kotlin-android-lint-check: running lint"
set +e
# UnusedResources is error in lint.xml for AGP-era intent, but standalone CLI does not resolve
# Compose stringResource/R usages and flags nearly every string. Disable only on this CLI path.
# i18n key parity remains enforced by check_string_resource_parity.sh.
ANDROID_HOME="$kotlin_android_sdk" ANDROID_SDK_ROOT="$kotlin_android_sdk" \
  "$lint_bin" \
  --project "$project_xml" \
  --config "$repo_root/lint.xml" \
  --disable UnusedResources \
  --exitcode \
  --sdk-home "$kotlin_android_sdk" \
  --compile-sdk-version "$app_compile_sdk" \
  "${lint_rule_args[@]}" \
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
