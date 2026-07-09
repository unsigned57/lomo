#!/usr/bin/env bash
set -euo pipefail

lomo_kotlin_prepare_env() {
  local script_name="$1"

  repo_root="$(git rev-parse --show-toplevel)"
  kotlin_wrapper="$repo_root/kotlin"
  kotlin_home="${LOMO_KOTLIN_HOME:-$repo_root/.home}"
  kotlin_xdg_cache_home="${LOMO_KOTLIN_XDG_CACHE_HOME:-$repo_root/.cache}"
  kotlin_xdg_data_home="${LOMO_KOTLIN_XDG_DATA_HOME:-$repo_root/.local/share}"
  kotlin_xdg_config_home="${LOMO_KOTLIN_XDG_CONFIG_HOME:-$repo_root/.config}"
  kotlin_android_home="${LOMO_KOTLIN_ANDROID_HOME:-$repo_root/.android}"
  kotlin_android_sdk="${LOMO_KOTLIN_ANDROID_SDK:-$repo_root/.android-sdk}"
  kotlin_bootstrap_cache="${KOTLIN_CLI_BOOTSTRAP_CACHE_DIR:-$repo_root/.kotlin-cli}"
  kotlin_gradle_user_home="${LOMO_KOTLIN_GRADLE_USER_HOME:-$repo_root/.gradle/kotlin-toolchain}"

  if [ ! -x "$kotlin_wrapper" ]; then
    echo "$script_name: Kotlin Toolchain wrapper not found or not executable: $kotlin_wrapper" >&2
    exit 1
  fi

  mkdir -p \
    "$kotlin_home" \
    "$kotlin_xdg_cache_home" \
    "$kotlin_xdg_data_home" \
    "$kotlin_xdg_config_home" \
    "$kotlin_android_home" \
    "$kotlin_android_sdk" \
    "$kotlin_bootstrap_cache" \
    "$kotlin_gradle_user_home"
}

lomo_kotlin_run() {
  env \
    HOME="$kotlin_home" \
    XDG_CACHE_HOME="$kotlin_xdg_cache_home" \
    XDG_DATA_HOME="$kotlin_xdg_data_home" \
    XDG_CONFIG_HOME="$kotlin_xdg_config_home" \
    ANDROID_HOME="$kotlin_android_sdk" \
    ANDROID_SDK_ROOT="$kotlin_android_sdk" \
    ANDROID_USER_HOME="$kotlin_android_home" \
    KOTLIN_CLI_BOOTSTRAP_CACHE_DIR="$kotlin_bootstrap_cache" \
    KOTLIN_CLI_NO_WELCOME_BANNER="${KOTLIN_CLI_NO_WELCOME_BANNER:-1}" \
    GRADLE_USER_HOME="$kotlin_gradle_user_home" \
    "$kotlin_wrapper" \
    --log-level="${KOTLIN_LOG_LEVEL:-warn}" \
    "$@"
}
