#!/usr/bin/env bash
# Shared helpers for running detekt CLI without a project Gradle entrypoint.
set -euo pipefail

DETEKT_VERSION="${LOMO_DETEKT_VERSION:-2.0.0-alpha.3}"
DETEKT_CLI_CACHE_DIR_DEFAULT=""

lomo_detekt_repo_root() {
  git rev-parse --show-toplevel
}

lomo_detekt_cli_jar() {
  local repo_root cache_dir jar_path url
  repo_root="$(lomo_detekt_repo_root)"
  cache_dir="${LOMO_DETEKT_CACHE_DIR:-$repo_root/.cache/detekt}"
  jar_path="$cache_dir/detekt-cli-${DETEKT_VERSION}-all.jar"
  mkdir -p "$cache_dir"
  if [ ! -f "$jar_path" ]; then
    url="https://repo1.maven.org/maven2/dev/detekt/detekt-cli/${DETEKT_VERSION}/detekt-cli-${DETEKT_VERSION}-all.jar"
    echo "kotlin-detekt: downloading detekt CLI ${DETEKT_VERSION}" >&2
    curl -fsSL -o "$jar_path.partial" "$url"
    mv "$jar_path.partial" "$jar_path"
  fi
  printf '%s\n' "$jar_path"
}

lomo_detekt_rules_jar() {
  local repo_root build_dir jar_path
  repo_root="$(lomo_detekt_repo_root)"
  build_dir="${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/detekt-gate}"
  jar_path="$build_dir/tasks/_detekt-rules_jarJvm/detekt-rules-jvm.jar"
  if [ ! -f "$jar_path" ]; then
    echo "kotlin-detekt: detekt-rules jar missing at $jar_path; build detekt-rules first" >&2
    return 1
  fi
  printf '%s\n' "$jar_path"
}

lomo_detekt_extra_plugin_jars() {
  # Optional plugin jars already resolved into Toolchain/Gradle caches.
  # ktlint-wrapper is intentionally NOT loaded here: it requires the full
  # ktlint-repackage fat classpath and is only used by kotlin_detekt_format.sh.
  local repo_root
  repo_root="$(lomo_detekt_repo_root)"
  find "$repo_root/.gradle" \
    -path "*/dev.detekt/detekt-rules-coroutines/${DETEKT_VERSION}/*" \
    -name "detekt-rules-coroutines-${DETEKT_VERSION}.jar" \
    2>/dev/null | head -1 || true
}

lomo_detekt_run() {
  local cli_jar rules_jar
  local -a plugin_args=()
  local -a java_args=()
  local include_rules="${LOMO_DETEKT_INCLUDE_CUSTOM_RULES:-1}"
  cli_jar="$(lomo_detekt_cli_jar)"
  if [ "$include_rules" = "1" ]; then
    rules_jar="$(lomo_detekt_rules_jar)"
    plugin_args+=(--plugins "$rules_jar")
  fi

  local plugin
  while IFS= read -r plugin; do
    [ -n "$plugin" ] || continue
    plugin_args+=(--plugins "$plugin")
  done < <(lomo_detekt_extra_plugin_jars)

  # Additional plugins from caller (e.g. LOMO_DETEKT_EXTRA_PLUGINS=path:path)
  if [ -n "${LOMO_DETEKT_EXTRA_PLUGINS:-}" ]; then
    local IFS=':'
    local extra
    for extra in $LOMO_DETEKT_EXTRA_PLUGINS; do
      [ -n "$extra" ] || continue
      plugin_args+=(--plugins "$extra")
    done
  fi

  java_args=(
    -jar "$cli_jar"
    "${plugin_args[@]}"
    --base-path "$(lomo_detekt_repo_root)"
  )
  java "${java_args[@]}" "$@"
}
