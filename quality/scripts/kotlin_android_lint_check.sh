#!/usr/bin/env bash
# Android Lint via SDK lint CLI with a Toolchain-derived project descriptor.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_toolchain_env.sh
source "$script_dir/kotlin_toolchain_env.sh"

lomo_kotlin_prepare_env "kotlin-android-lint-check"
repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

lint_bin="${LOMO_ANDROID_LINT:-$kotlin_android_sdk/cmdline-tools/latest/bin/lint}"
if [ ! -x "$lint_bin" ]; then
  echo "kotlin-android-lint-check: Android lint not found at $lint_bin" >&2
  exit 1
fi

report_root="$repo_root/build/reports/android-lint"
mkdir -p "$report_root"
project_xml="$report_root/project.xml"
report_xml="$report_root/lint-report.xml"
report_html="$report_root/lint-report.html"
expanded_dir="$report_root/expanded-aars"
build_dir="${LOMO_LINT_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/lint-gate}"

echo "kotlin-android-lint-check: building Android app (debug) to materialize classpath"
lomo_kotlin_run build --module app --platform android --variant debug --build-dir "$build_dir"

echo "kotlin-android-lint-check: generating lint project descriptor"
python3 - "$repo_root" "$build_dir" "$project_xml" "$kotlin_android_sdk" "$expanded_dir" <<'PY'
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

candidates = sorted(build_dir.glob("tasks/_app_prepareAndroid*/gradle-project/settings.gradle.kts"))
if not candidates:
    candidates = sorted(
        Path(repo_root, ".kotlin/toolchain-build").glob(
            "*/tasks/_app_prepareAndroid*/gradle-project/settings.gradle.kts"
        )
    )
if not candidates:
    raise SystemExit("kotlin-android-lint-check: no Toolchain prepareAndroid bridge found")

settings = candidates[-1]
text = settings.read_text(encoding="utf-8")
match = re.search(r'jsonData = """(.*)"""', text, re.S)
if not match:
    raise SystemExit(f"kotlin-android-lint-check: no jsonData in {settings}")
data = json.loads(match.group(1))

classpath: list[Path] = []
for platform in sorted((sdk / "platforms").glob("android-*"), reverse=True):
    android_jar = platform / "android.jar"
    if android_jar.is_file():
        classpath.append(android_jar)
        break

for pattern in (
    "tasks/_app_jarAndroidDebug/**/*.jar",
    "tasks/_data_jarAndroidDebug/**/*.jar",
    "tasks/_domain_jarAndroidDebug/**/*.jar",
    "tasks/_ui-components_jarAndroidDebug/**/*.jar",
    "tasks/_app_prepareAndroidDebug/R.jar",
    "tasks/_app_prepareAndroidDebug/**/R.jar",
):
    classpath.extend(sorted(build_dir.glob(pattern)))

expanded_dir.mkdir(parents=True, exist_ok=True)
for module in data.get("modules", []):
    for dep in module.get("resolvedAndroidRuntimeDependencies") or []:
        path = dep.get("path")
        if not path:
            continue
        p = Path(path)
        if not p.exists():
            continue
        if p.suffix == ".jar":
            classpath.append(p)
            continue
        if p.suffix != ".aar":
            classpath.append(p)
            continue
        dest = expanded_dir / p.stem
        classes_jar = dest / "classes.jar"
        if not classes_jar.is_file():
            dest.mkdir(parents=True, exist_ok=True)
            try:
                with zipfile.ZipFile(p) as zf:
                    for member in ("classes.jar", "lint.jar"):
                        if member in zf.namelist():
                            zf.extract(member, dest)
            except zipfile.BadZipFile:
                continue
        if classes_jar.is_file():
            classpath.append(classes_jar)
        lint_jar = dest / "lint.jar"
        if lint_jar.is_file():
            classpath.append(lint_jar)

seen: set[str] = set()
unique_cp: list[Path] = []
for item in classpath:
    if not item.exists():
        continue
    key = str(item.resolve())
    if key in seen:
        continue
    seen.add(key)
    unique_cp.append(item)

# Amper keeps min/target SDK in module.yaml, not the source manifest. Inject for lint model.
src_manifest = (repo_root / "app/src/AndroidManifest.xml").read_text(encoding="utf-8")
if "uses-sdk" not in src_manifest:
    src_manifest = src_manifest.replace(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"',
        (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:versionCode="46"\n'
            '    android:versionName="1.6.2"'
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
        + '\n    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="37" />'
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
    '  <module name="app" android="true" library="false" compile-sdk-version="37">',
    f'    <manifest file="{esc(str(merged_manifest))}" />',
    f'    <src file="{esc(str(repo_root / "app/src"))}" />',
    f'    <resource file="{esc(str(repo_root / "app/res"))}" />',
]
for jar in unique_cp:
    lines.append(f'    <classpath jar="{esc(str(jar))}" />')
lines.append("  </module>")
lines.append("</project>")
project_xml.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(
    f"kotlin-android-lint-check: wrote {project_xml} with {len(unique_cp)} classpath entries "
    f"from {settings}"
)
PY

compose_lint_jar="$(
  find "$repo_root/.gradle" "$repo_root/.cache" -path '*compose-lint-checks*.jar' 2>/dev/null | head -1 || true
)"
lint_rule_args=()
if [ -n "$compose_lint_jar" ]; then
  lint_rule_args+=(--lint-rule-jars "$compose_lint_jar")
  echo "kotlin-android-lint-check: compose lint checks: $compose_lint_jar"
else
  echo "kotlin-android-lint-check: compose-lint-checks jar not found; Compose IDs may be unknown" >&2
fi

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
  --compile-sdk-version 37 \
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
