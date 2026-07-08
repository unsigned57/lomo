#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/ai_gradle_env.sh
source "$script_dir/ai_gradle_env.sh"

lomo_ai_prepare_gradle_env "ai-compose-static-analysis"

echo "ai-compose-static-analysis: running ./gradlew composeStaticAnalysisCheck"
echo "ai-compose-static-analysis: using GRADLE_USER_HOME at $ai_gradle_user_home"
echo "ai-compose-static-analysis: using HOME at $ai_home"

lomo_ai_run_gradle composeStaticAnalysisCheck "$@"
