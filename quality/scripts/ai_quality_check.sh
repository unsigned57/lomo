#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/ai_gradle_env.sh
source "$script_dir/ai_gradle_env.sh"

lomo_ai_prepare_gradle_env "ai-quality-check"

echo "ai-quality-check: running final ./gradlew qualityCheck"
echo "ai-quality-check: using GRADLE_USER_HOME at $ai_gradle_user_home"
echo "ai-quality-check: using HOME at $ai_home"

lomo_ai_run_gradle qualityCheck "$@"

if [ "${LOMO_SKIP_LOCAL_MAINTENANCE:-false}" = "true" ]; then
  echo "ai-quality-check: skipping local maintenance because LOMO_SKIP_LOCAL_MAINTENANCE=true"
  exit 0
fi

bash "$script_dir/ai_local_maintenance_check.sh" "$@"
