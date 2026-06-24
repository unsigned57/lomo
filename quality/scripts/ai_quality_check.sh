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

if [ "${LOMO_RUN_LOCAL_MAINTENANCE:-false}" != "true" ]; then
  echo "ai-quality-check: skipping local maintenance by default"
  echo "ai-quality-check: run quality/scripts/ai_local_maintenance_check.sh or set LOMO_RUN_LOCAL_MAINTENANCE=true for dependency updates, CVEs, and R8 diagnostics"
  exit 0
fi

echo "ai-quality-check: running local maintenance because LOMO_RUN_LOCAL_MAINTENANCE=true"
bash "$script_dir/ai_local_maintenance_check.sh" "$@"
