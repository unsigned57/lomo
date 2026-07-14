#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

usage() {
  cat <<'EOF'
Usage: quality/scripts/kotlin_cache_cleanup.sh --dry-run|--apply

Removes repo-local generated state that is safe to recreate:
- old .kotlin/toolchain-build entries except canonical gate directories
- build/reports/configuration-cache
- historical Gradle homes no longer used by the Kotlin Toolchain bridge

The default workflow is --dry-run first, then --apply after reviewing targets.
EOF
}

case "${1:-}" in
  --dry-run)
    mode="dry-run"
    ;;
  --apply)
    mode="apply"
    ;;
  --help|-h)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

canonical_toolchain_dir() {
  case "$1" in
    static-gate|quality-gate|fast-gate|lint-gate|coverage-gate|local-maintenance-release)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

cleanup_target() {
  local target="$1"

  [ -e "$target" ] || return 0

  case "$target" in
    .kotlin/toolchain-build/*|build/reports/configuration-cache|.gradle/plan-inspect|.gradle/shared-user-home)
      ;;
    *)
      echo "kotlin-cache-cleanup: refusing unexpected target: $target" >&2
      exit 1
      ;;
  esac

  if [ "$mode" = "dry-run" ]; then
    printf 'would remove %s\n' "$target"
  else
    printf 'removing %s\n' "$target"
    rm -rf -- "$target"
  fi
}

if [ -d ".kotlin/toolchain-build" ]; then
  while IFS= read -r target; do
    name="${target##*/}"
    if canonical_toolchain_dir "$name"; then
      continue
    fi
    cleanup_target "$target"
  done < <(find .kotlin/toolchain-build -mindepth 1 -maxdepth 1 -type d | sort)
fi

cleanup_target "build/reports/configuration-cache"
cleanup_target ".gradle/plan-inspect"
cleanup_target ".gradle/shared-user-home"

if [ "$mode" = "dry-run" ]; then
  echo "kotlin-cache-cleanup: dry run complete"
else
  echo "kotlin-cache-cleanup: cleanup complete"
fi
