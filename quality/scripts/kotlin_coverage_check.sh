#!/usr/bin/env bash
# Coverage verification via JaCoCo runtime agent (parity with koverVerifyQuality minBound 70).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_toolchain_env.sh
source "$script_dir/kotlin_toolchain_env.sh"
# shellcheck source=quality/scripts/kotlin_toolchain_test_args.sh
source "$script_dir/kotlin_toolchain_test_args.sh"

lomo_kotlin_prepare_env "kotlin-coverage-check"
repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

JACOCO_VERSION="${LOMO_JACOCO_VERSION:-0.8.14}"
coverage_min_bound="${LOMO_COVERAGE_MIN_BOUND:-70}"
cache_dir="${LOMO_JACOCO_CACHE_DIR:-$repo_root/.cache/jacoco}"
agent_jar="$cache_dir/jacocoagent-${JACOCO_VERSION}.jar"
cli_jar="$cache_dir/jacococli-${JACOCO_VERSION}.jar"
work_dir="$repo_root/build/jacoco"
report_dir="$repo_root/build/reports/kover"
exec_file="$work_dir/jacoco.exec"
xml_report="$report_dir/coverage.xml"
html_report="$report_dir/html"
build_dir="${LOMO_COVERAGE_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/coverage-gate}"
mkdir -p "$cache_dir" "$work_dir" "$report_dir"

download() {
  local url="$1"
  local dest="$2"
  if [ -f "$dest" ]; then
    return 0
  fi
  echo "kotlin-coverage-check: downloading $(basename "$dest")"
  curl -fsSL -o "$dest.partial" "$url"
  mv "$dest.partial" "$dest"
}

download "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/${JACOCO_VERSION}/org.jacoco.agent-${JACOCO_VERSION}-runtime.jar" "$agent_jar"
download "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/${JACOCO_VERSION}/org.jacoco.cli-${JACOCO_VERSION}-nodeps.jar" "$cli_jar"

echo "kotlin-coverage-check: building modules"
lomo_kotlin_run build --build-dir "$build_dir"

rm -f "$exec_file"
echo "kotlin-coverage-check: running host tests under JaCoCo agent"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -javaagent:${agent_jar}=destfile=${exec_file},append=true,excludes=jdk.*:java.*:sun.*:com.sun.*:org.gradle.*:worker.org.gradle.*"
set +e
lomo_kotlin_run test "${toolchain_test_modules[@]}" --build-dir "$build_dir"
test_status=$?
set -e
unset JAVA_TOOL_OPTIONS || true

if [ ! -f "$exec_file" ] || [ ! -s "$exec_file" ]; then
  echo "kotlin-coverage-check: JaCoCo exec file missing or empty at $exec_file" >&2
  exit 1
fi

# Prefer Toolchain module jars (stable, production class files).
classfiles_args=()
while IFS= read -r jar; do
  [ -f "$jar" ] || continue
  classfiles_args+=(--classfiles "$jar")
done < <(
  find "$build_dir/tasks" \
    -type f \
    \( -name 'domain-jvm.jar' -o -name 'app-jvm.jar' -o -name 'data-jvm.jar' -o -name 'ui-components-jvm.jar' -o -name 'detekt-rules-jvm.jar' \) \
    2>/dev/null | sort -u
)

if [ "${#classfiles_args[@]}" -eq 0 ]; then
  echo "kotlin-coverage-check: no module jars found under $build_dir/tasks" >&2
  exit 1
fi

echo "kotlin-coverage-check: generating report from ${#classfiles_args[@]} classfile roots"
rm -rf "$html_report"
mkdir -p "$html_report"
java -jar "$cli_jar" report "$exec_file" \
  "${classfiles_args[@]}" \
  --sourcefiles "$repo_root/app/src" \
  --sourcefiles "$repo_root/data/src" \
  --sourcefiles "$repo_root/domain/src" \
  --sourcefiles "$repo_root/ui-components/src" \
  --xml "$xml_report" \
  --html "$html_report" \
  --name "Lomo quality coverage"

python3 - "$xml_report" "$coverage_min_bound" <<'PY'
import fnmatch
import sys
import xml.etree.ElementTree as ET

xml_path, min_bound_s = sys.argv[1], sys.argv[2]
min_bound = float(min_bound_s)
root = ET.parse(xml_path).getroot()

# Historical filters from pre-Toolchain root build.gradle.kts (kover quality variant).
packages_excl = [
    "com.lomo.ui",
    "com.lomo.app.util",
    "com.lomo.app.presentation",
    "com.lomo.app.feature.settings",
    "com.lomo.data.source",
    "com.lomo.data.media",
    "com.lomo.data.security",
]
class_patterns = [
    "*.BuildConfig",
    "*.Manifest*",
    "*.R",
    "*.R$*",
    "*.ComposableSingletons*",
    "*_Factory",
    "*_Factory$*",
    "*_Provide*Factory",
    "*_MembersInjector",
    "*_GeneratedInjector",
    "*Dao_Impl*",
    "*Database_Impl*",
    "com.lomo.ui.util.AppHapticFeedback*",
    "com.lomo.app.MainActivity*",
    "com.lomo.app.LomoApplication*",
    "com.lomo.app.LomoAppRootKt*",
    "com.lomo.app.navigation*",
    "com.lomo.app.repository.AppWidgetRepository*",
    "com.lomo.app.widget*",
    "com.lomo.app.di*",
    "com.lomo.app.theme*",
    "com.lomo.app.benchmark*",
    "com.lomo.app.media.AudioPlayerManager*",
    "com.lomo.app.util.ShareCardBitmapRenderer",
    "com.lomo.app.util.ShareUtils*",
    "com.lomo.app.util.HapticManager*",
    "com.lomo.app.util.CameraCaptureUtils*",
    "com.lomo.app.feature.main.MemoUiImageContentResolver*",
    "com.lomo.app.feature.settings.SettingsCoordinatorFactory*",
    "com.lomo.app.provider.ImageMapProvider*",
    "com.lomo.app.feature.*.*Presenter*",
    "com.lomo.app.feature.*.*ScreenKt*",
    "com.lomo.app.feature.*.*SectionKt*",
    "com.lomo.app.feature.*.*SectionsKt*",
    "com.lomo.app.feature.*.*BannerKt*",
    "com.lomo.app.feature.*.*DialogsKt*",
    "com.lomo.app.feature.*.*DialogHostKt*",
    "com.lomo.app.feature.*.*LayoutKt*",
    "com.lomo.app.feature.*.*SheetKt*",
    "com.lomo.app.feature.*.*PanelKt*",
    "com.lomo.app.feature.*.*ScaffoldKt*",
    "com.lomo.app.feature.*.*ContentKt*",
    "com.lomo.app.feature.*.*TopBarKt*",
    "com.lomo.app.feature.*.*FabKt*",
    "com.lomo.app.feature.*.*StateHostsKt*",
    "com.lomo.app.feature.*.*NavigationActionsKt*",
    "com.lomo.app.feature.*.*EventEffectsKt*",
    "com.lomo.app.feature.*.*EmptyStateKt*",
    "com.lomo.app.feature.*.*DirectoryGuideKt*",
    "com.lomo.app.feature.*.*SupportKt*",
    "com.lomo.app.feature.*.*SyncContainersKt*",
    "com.lomo.app.feature.*.*DialogOptionsKt*",
    "com.lomo.app.feature.*.*InteractionHostKt*",
    "com.lomo.app.feature.*.*BinderKt*",
    "com.lomo.app.feature.*.*EntryKt*",
    "com.lomo.app.feature.*.*CardListAnimationKt*",
    "com.lomo.app.feature.*.*ControllerKt*",
    "com.lomo.app.feature.*.*UiState*",
    "com.lomo.app.feature.*.*UiSnapshot*",
    "com.lomo.app.feature.*.*LocalState*",
    "com.lomo.app.feature.*.*HostState*",
    "com.lomo.app.feature.*.*Features*",
    "com.lomo.app.feature.*.*Actions*",
    "com.lomo.app.feature.*.*DialogState*",
    "com.lomo.data.di*",
    "com.lomo.data.local.datastore.LomoDataStoreKeys*",
    "com.lomo.data.local.datastore.LomoDataStoreKt*",
    "com.lomo.data.git.SafGitMirrorBridge*",
    "com.lomo.data.webdav.Dav4jvmWebDavClient*",
    "com.lomo.data.git.GitSyncQueryTestCoordinator*",
    "com.lomo.data.source.FileMediaStorageDataSourceDelegate*",
    "com.lomo.data.media.AudioRecorder*",
    "com.lomo.data.media.AudioPlaybackUriResolverImpl*",
    "com.lomo.data.share.NsdDiscoveryService*",
    "com.lomo.data.share.ShareServiceLifecycleController*",
    "com.lomo.data.share.ShareServiceManager*",
    "com.lomo.ui.util.DateTimeUtils*",
    "com.lomo.ui.util.SharedTransitionLocalsKt*",
    "com.lomo.ui.media.AudioPlayerManagerKt*",
]


def pkg_excluded(pkg_slash: str) -> bool:
    dotted = pkg_slash.replace("/", ".")
    return any(dotted == p or dotted.startswith(p + ".") for p in packages_excl)


def class_excluded(class_slash: str) -> bool:
    dotted = class_slash.replace("/", ".")
    simple = dotted.rsplit(".", 1)[-1]
    for pat in class_patterns:
        if fnmatch.fnmatchcase(dotted, pat):
            return True
        if fnmatch.fnmatchcase(class_slash, pat.replace(".", "/")):
            return True
        if pat.startswith("*.") and fnmatch.fnmatchcase(simple, pat[2:]):
            return True
    return False


missed = 0.0
covered = 0.0
for pkg in root.findall("package"):
    pname = pkg.get("name", "")
    if pkg_excluded(pname):
        continue
    for cls in pkg.findall("class"):
        cname = cls.get("name", "")
        if class_excluded(cname):
            continue
        for counter in cls.findall("counter"):
            if counter.get("type") == "LINE":
                missed += float(counter.get("missed", "0"))
                covered += float(counter.get("covered", "0"))
                break

total = missed + covered
pct = 0.0 if total == 0 else (covered / total) * 100.0
print(
    f"kotlin-coverage-check: filtered line coverage {pct:.2f}% "
    f"covered={covered:.0f} missed={missed:.0f} (min {min_bound:.0f}%)"
)
if total == 0:
    print("kotlin-coverage-check: empty coverage totals after filters", file=sys.stderr)
    sys.exit(1)
if pct + 1e-9 < min_bound:
    print("kotlin-coverage-check: coverage below bound", file=sys.stderr)
    sys.exit(1)
PY

if [ "$test_status" -ne 0 ]; then
  echo "kotlin-coverage-check: tests failed with exit $test_status" >&2
  exit "$test_status"
fi

echo "kotlin-coverage-check: ok"
