#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/ai_gradle_env.sh
source "$script_dir/ai_gradle_env.sh"

lomo_ai_prepare_gradle_env "ai-static-quality-check"

echo "ai-static-quality-check: running ./gradlew staticQualityCheck"
echo "ai-static-quality-check: using GRADLE_USER_HOME at $ai_gradle_user_home"
echo "ai-static-quality-check: using HOME at $ai_home"

lomo_ai_run_gradle staticQualityCheck "$@"
