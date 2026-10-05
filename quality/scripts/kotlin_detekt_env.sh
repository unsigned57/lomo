#!/usr/bin/env bash
# Shared helpers for running detekt CLI without a project Gradle entrypoint.
set -euo pipefail

# The detekt CLI engine is pinned: version and digest are source facts, not
# environment channels — `LOMO_DETEKT_VERSION` could swap the whole engine and a
# pre-seeded jar at `LOMO_DETEKT_CACHE_DIR` previously bypassed the checksum that
# only ran on download. The jar is verified on EVERY load, so cache location
# (`LOMO_DETEKT_CACHE_DIR`) stays a harmless path knob: whatever jar it contains
# must match the pinned digest to run.
DETEKT_VERSION="2.0.0-alpha.6"
DETEKT_CLI_SHA1="543c524afee8f40330b4bf66e760cb141accb87a"
# The coroutines ruleset plugin is a published artifact — pinned to the digest
# Maven Central serves for `dev.detekt:detekt-rules-coroutines:${DETEKT_VERSION}`.
# Its rules are configuration-required: every module config under
# `quality/detekt/config/` activates `coroutines:` rules, so an absent plugin
# silently unenforces GlobalCoroutineUsage/InjectDispatcher — resolution must
# succeed (verified cache copy or verified download) or fail the gate.
DETEKT_COROUTINES_SHA1="056cb5c33cd41775e374474fac1964c1b080380b"
DETEKT_CLI_CACHE_DIR_DEFAULT=""

lomo_detekt_repo_root() {
  git rev-parse --show-toplevel
}

lomo_detekt_cli_jar() {
  local cache_root cache_dir jar_path url actual
  cache_root="${XDG_CACHE_HOME:-${HOME:?HOME must be set}/.cache}"
  cache_dir="${LOMO_DETEKT_CACHE_DIR:-$cache_root/lomo/detekt}"
  jar_path="$cache_dir/detekt-cli-${DETEKT_VERSION}-all.jar"
  mkdir -p "$cache_dir"
  if [ ! -f "$jar_path" ]; then
    url="https://repo1.maven.org/maven2/dev/detekt/detekt-cli/${DETEKT_VERSION}/detekt-cli-${DETEKT_VERSION}-all.jar"
    echo "kotlin-detekt: downloading detekt CLI ${DETEKT_VERSION}" >&2
    curl -fsSL -o "$jar_path.partial" "$url"
    actual="$(sha1sum "$jar_path.partial" | awk '{print $1}')"
    if [ "$actual" != "$DETEKT_CLI_SHA1" ]; then
      rm -f "$jar_path.partial"
      echo "kotlin-detekt: checksum mismatch for detekt-cli-${DETEKT_VERSION}-all.jar (expected $DETEKT_CLI_SHA1, got $actual)" >&2
      return 1
    fi
    mv "$jar_path.partial" "$jar_path"
  fi
  actual="$(sha1sum "$jar_path" | awk '{print $1}')"
  if [ "$actual" != "$DETEKT_CLI_SHA1" ]; then
    echo "kotlin-detekt: cached detekt-cli-${DETEKT_VERSION}-all.jar at $jar_path fails the pinned sha1 (expected $DETEKT_CLI_SHA1, got $actual) — remove the tampered cache entry" >&2
    return 1
  fi
  printf '%s\n' "$jar_path"
}

# Every plugin jar detekt loads executes arbitrary code inside the gate JVM —
# the same trust level as the pinned CLI engine. Each --plugins channel must
# therefore prove what its trust model can prove before the jar is loaded:
#   * zip validity — `jar tf` reads the archive end-to-end;
#   * artifact identity — a non-empty expected sha1 (argument 2) is the
#     authoritative digest of the *published* artifact: the file must
#     reproduce it. Only when no pin exists does the Gradle modules-2 hash
#     directory apply, and it can only prove cache integrity — a directory
#     named after the jar's own sha1 says nothing about which artifact the
#     bytes came from — plus the identity markers below;
#   * identity markers — caller-supplied entry-name regexes that must each
#     match, so a renamed or gutted artifact cannot stand in for the plugin.
# A failed check aborts the caller — an unverifiable plugin is gate state that
# must be fixed, never silently skipped.
#
# Usage: lomo_detekt_verify_plugin_jar <jar> <expected-sha1-or-""> <marker>...
lomo_detekt_verify_plugin_jar() {
  local jar_path="$1" expected_sha1="$2"
  shift 2
  local entries hash_dir actual marker
  if [ ! -s "$jar_path" ]; then
    echo "kotlin-detekt: plugin jar missing or empty: $jar_path" >&2
    return 1
  fi
  if ! entries="$(jar tf "$jar_path" 2>/dev/null)"; then
    echo "kotlin-detekt: plugin jar is not a readable zip: $jar_path" >&2
    return 1
  fi
  actual="$(sha1sum "$jar_path" | awk '{print $1}')"
  if [ -n "$expected_sha1" ]; then
    if [ "$actual" != "$expected_sha1" ]; then
      echo "kotlin-detekt: plugin jar $jar_path fails the pinned artifact digest (expected $expected_sha1, got $actual) — refusing to load an artifact that is not the pinned publication" >&2
      return 1
    fi
  else
    hash_dir="$(basename "$(dirname "$jar_path")")"
    if [[ "$hash_dir" =~ ^[0-9a-f]{40}$ && "$actual" != "$hash_dir" ]]; then
      echo "kotlin-detekt: plugin jar $jar_path fails its Gradle cache digest (expected $hash_dir, got $actual) — remove the tampered cache entry" >&2
      return 1
    fi
  fi
  for marker in "$@"; do
    if ! grep -Eq -- "$marker" <<<"$entries"; then
      echo "kotlin-detekt: plugin jar $jar_path lacks identity marker $marker — refusing to load an unverified artifact" >&2
      return 1
    fi
  done
}

lomo_detekt_rules_jar() {
  local repo_root build_dir jar_path
  repo_root="$(lomo_detekt_repo_root)"
  build_dir="${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/shared}"
  jar_path="$build_dir/tasks/_detekt-rules_jarJvm/detekt-rules-jvm.jar"
  if [ ! -f "$jar_path" ]; then
    echo "kotlin-detekt: detekt-rules jar missing at $jar_path; build detekt-rules first" >&2
    return 1
  fi
  # The rules jar is built from this repository — no published digest exists,
  # so verification stands on zip validity, cache-directory integrity and the
  # provider identity markers (empty pin argument).
  lomo_detekt_verify_plugin_jar "$jar_path" "" \
    '^META-INF/services/dev\.detekt\.api\.RuleSetProvider$' \
    '^com/lomo/detektrules/LomoArchitectureRuleSetProvider\.class$' || return 1
  printf '%s\n' "$jar_path"
}

# The coroutines ruleset is REQUIRED — every module detekt config activates
# `coroutines:` rules, so "plugin not found" can never mean "rules skipped":
# the gate resolves the pinned publication or aborts. Resolution order:
#   1. the gate cache copy, re-verified against the pin on every load;
#   2. a Gradle modules-2 candidate whose bytes already reproduce the pin —
#      candidates are sorted, and since a match is byte-identical to the
#      pinned artifact the pick is deterministic (never `head -1` lottery);
#   3. a verified download of the pinned publication from Maven Central.
# A candidate that does not reproduce the pin is not "a plugin we failed to
# verify" — it is not the artifact at all, and is skipped for the next channel.
lomo_detekt_coroutines_jar() {
  local repo_root cache_root cache_dir jar_path url actual candidate
  repo_root="$(lomo_detekt_repo_root)"
  cache_root="${XDG_CACHE_HOME:-${HOME:?HOME must be set}/.cache}"
  cache_dir="${LOMO_DETEKT_CACHE_DIR:-$cache_root/lomo/detekt}"
  jar_path="$cache_dir/detekt-rules-coroutines-${DETEKT_VERSION}.jar"
  mkdir -p "$cache_dir"
  if [ ! -f "$jar_path" ]; then
    while IFS= read -r candidate; do
      [ -f "$candidate" ] || continue
      actual="$(sha1sum "$candidate" | awk '{print $1}')"
      if [ "$actual" = "$DETEKT_COROUTINES_SHA1" ]; then
        cp "$candidate" "$jar_path"
        break
      fi
    done < <(
      find "$repo_root/.gradle" "$HOME/.gradle" \
        -path "*/dev.detekt/detekt-rules-coroutines/${DETEKT_VERSION}/*" \
        -name "detekt-rules-coroutines-${DETEKT_VERSION}.jar" \
        2>/dev/null | sort
    )
  fi
  if [ ! -f "$jar_path" ]; then
    url="https://repo1.maven.org/maven2/dev/detekt/detekt-rules-coroutines/${DETEKT_VERSION}/detekt-rules-coroutines-${DETEKT_VERSION}.jar"
    echo "kotlin-detekt: downloading detekt coroutines ruleset ${DETEKT_VERSION}" >&2
    curl -fsSL -o "$jar_path.partial" "$url"
    actual="$(sha1sum "$jar_path.partial" | awk '{print $1}')"
    if [ "$actual" != "$DETEKT_COROUTINES_SHA1" ]; then
      rm -f "$jar_path.partial"
      echo "kotlin-detekt: checksum mismatch for detekt-rules-coroutines-${DETEKT_VERSION}.jar (expected $DETEKT_COROUTINES_SHA1, got $actual)" >&2
      return 1
    fi
    mv "$jar_path.partial" "$jar_path"
  fi
  # Identity is re-proven on EVERY load — the cache path is a location, not a
  # trust channel; a planted or corrupted copy fails the pinned digest.
  lomo_detekt_verify_plugin_jar "$jar_path" "$DETEKT_COROUTINES_SHA1" \
    '^META-INF/services/dev\.detekt\.api\.RuleSetProvider$' \
    '^dev/detekt/rules/coroutines/[^/]+\.class$' || return 1
  printf '%s\n' "$jar_path"
}

lomo_detekt_extra_plugin_jars() {
  # ktlint-wrapper is intentionally NOT loaded here: it requires the full
  # ktlint-repackage fat classpath and is only used by kotlin_detekt_format.sh.
  lomo_detekt_coroutines_jar || return 1
}

lomo_detekt_run() {
  local cli_jar rules_jar
  local -a plugin_args=()
  local -a java_args=()
  local include_rules="${LOMO_DETEKT_INCLUDE_CUSTOM_RULES:-1}"
  # Explicit `|| return 1` on every verified resolution: callers invoke this
  # function under `if !`/`||`, which suspends errexit for the whole body —
  # without the propagation a failed check would collapse to an empty jar
  # argument instead of aborting the run.
  cli_jar="$(lomo_detekt_cli_jar)" || return 1
  if [ "$include_rules" = "1" ]; then
    rules_jar="$(lomo_detekt_rules_jar)" || return 1
    plugin_args+=(--plugins "$rules_jar")
  fi

  local extra_plugins plugin
  if ! extra_plugins="$(lomo_detekt_extra_plugin_jars)"; then
    # A plugin that fails verification is gate state, not an empty discovery —
    # propagate so no tolerated caller can read silence as "no plugins found".
    return 1
  fi
  while IFS= read -r plugin; do
    [ -n "$plugin" ] || continue
    plugin_args+=(--plugins "$plugin")
  done <<<"$extra_plugins"

  # Only the plugins above may be injected: the rules jar (when custom rules are
  # enabled) and the curated coroutine plugin discovery. A caller that needs
  # additional plugins passes `--plugins <jar>` as ordinary detekt CLI arguments —
  # there is no ambient environment channel for arbitrary plugin injection.

  java_args=(
    -jar "$cli_jar"
    "${plugin_args[@]}"
    --base-path "$(lomo_detekt_repo_root)"
  )
  java "${java_args[@]}" "$@"
}
