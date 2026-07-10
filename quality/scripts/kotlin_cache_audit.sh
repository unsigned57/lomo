#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

print_section() {
  local title="$1"
  shift

  printf '\n## %s\n' "$title"
  "$@" 2>/dev/null || true
}

printf '# Lomo generated-state audit\n'
printf 'Repository: %s\n' "$repo_root"

print_section "Top-level size" du -h -d 1 .
print_section "Toolchain build dirs" du -h -d 1 .kotlin/toolchain-build
print_section "Gradle homes" du -h -d 1 .gradle
print_section "Reports" du -h -d 1 build/reports
print_section "Tool caches" du -h -d 1 .cache .kotlin-cli .android-sdk
