#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/ai_gradle_env.sh
source "$script_dir/ai_gradle_env.sh"

lomo_ai_prepare_gradle_env "ai-fast-quality-check"

requires_static_quality_check() {
  local file

  while IFS= read -r file; do
    case "$file" in
      app/src/main/*|domain/src/main/*|data/src/main/*|ui-components/src/main/*|benchmark/src/main/*|\
      build.gradle.kts|settings.gradle.kts|gradle/libs.versions.toml|\
      quality/*|quality/*/*|quality/*/*/*|.github/workflows/*|AGENTS.md)
        return 0
        ;;
    esac
  done < <(
    {
      git diff --name-only --diff-filter=ACMR HEAD --
      git ls-files --others --exclude-standard
    } | sort -u
  )

  return 1
}

if [ "${LOMO_ALLOW_FAST_CHECK_WITH_PRODUCTION_CHANGES:-false}" != "true" ] && requires_static_quality_check; then
  cat >&2 <<'EOF'
ai-fast-quality-check: refused because the working tree contains production/build/quality changes.
Run quality/scripts/ai_static_quality_check.sh so detekt architecture checks and lint are included.
Set LOMO_ALLOW_FAST_CHECK_WITH_PRODUCTION_CHANGES=true only for intentional local overrides.
EOF
  exit 1
fi

echo "ai-fast-quality-check: running ./gradlew fastQualityCheck"
echo "ai-fast-quality-check: using GRADLE_USER_HOME at $ai_gradle_user_home"
echo "ai-fast-quality-check: using HOME at $ai_home"

lomo_ai_run_gradle fastQualityCheck "$@"
