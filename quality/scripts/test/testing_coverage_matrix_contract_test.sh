#!/usr/bin/env bash
set -euo pipefail

# Behavior Contract:
# - Unit under test: quality/testing-coverage-matrix.md
# - Owning layer: quality
# - Priority tier: P0
# - Capability: document the executable and diff-only coverage boundary for Kotlin test sources.
#
# Scenarios:
# - Given test-style Detekt excludes are configured, when the matrix is checked, then every exclude
#   is explicitly listed with risk and migration-plan language.
# - Given the meaningful-test script is diff-scoped by default, when the matrix is checked, then the
#   changed-file and all-file modes are both documented.
# - Given Kotlin Toolchain quality scripts run the repository gate, when the matrix is checked,
#   then the matrix identifies those scripts as executable coverage owners.
# - Given quality/README.md is the quality tooling entrypoint, when maintainers navigate testing
#   gates, then the README links to this matrix.
#
# Observable outcomes:
# - Missing matrix sections, missing configured excludes, or missing gate names fail this script.
#
# TDD proof:
# - Fails before the matrix exists with "matrix file missing".
# - Fails before the README links the matrix with "source file quality/README.md no longer contains
#   expected text: quality/testing-coverage-matrix.md".
#
# Excludes:
# - Kotlin Toolchain execution, Detekt rule behavior, and historical test quality migration itself.

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

matrix_path="quality/testing-coverage-matrix.md"
test_style_config="quality/detekt/config/test-style.yml"
meaningful_script="quality/scripts/check_meaningful_tests.sh"
quality_readme="quality/README.md"
root_build="module.yaml"
kotlin_quality_script="quality/scripts/kotlin_quality_check.sh"
kotlin_static_script="quality/scripts/kotlin_static_quality_check.sh"

fail() {
  echo "testing-coverage-matrix-contract: $1" >&2
  exit 1
}

[ -f "$matrix_path" ] || fail "matrix file missing: $matrix_path"

require_matrix_text() {
  local expected="$1"
  grep -Fq -- "$expected" "$matrix_path" ||
    fail "matrix is missing required text: $expected"
}

require_source_text() {
  local file="$1"
  local expected="$2"
  grep -Fq -- "$expected" "$file" ||
    fail "source file $file no longer contains expected text: $expected"
}

while IFS= read -r exclude_pattern; do
  [ -n "$exclude_pattern" ] || continue
  require_matrix_text "\`$exclude_pattern\`"
done < <(
  awk '
    /^[[:space:]]*excludes:/ {
      in_excludes = 1
      next
    }

    in_excludes && /^[[:space:]]*[A-Za-z0-9_-]+:/ {
      in_excludes = 0
    }

    in_excludes && /^[[:space:]]*-[[:space:]]*"/ {
      line = $0
      sub(/^[[:space:]]*-[[:space:]]*"/, "", line)
      sub(/".*$/, "", line)
      print line
    }
  ' "$test_style_config"
)

require_source_text "$kotlin_quality_script" "lomo_kotlin_run show modules"
require_source_text "$kotlin_quality_script" "lomo_kotlin_run build"
require_source_text "$kotlin_quality_script" "kotlin_coverage_check.sh"
require_source_text "$kotlin_quality_script" "check_meaningful_tests.sh"
require_source_text "$kotlin_static_script" "lomo_kotlin_run show modules"
require_source_text "$kotlin_static_script" "lomo_kotlin_run build"
require_source_text "$kotlin_static_script" "lomo_kotlin_run test"

require_source_text "$meaningful_script" "MEANINGFUL_TEST_CHECK_ALL"
require_source_text "$meaningful_script" "MEANINGFUL_TEST_CHECK_MODE"
require_source_text "$meaningful_script" "--all"
require_source_text "$quality_readme" "quality/testing-coverage-matrix.md"

require_matrix_text "Default changed-file mode matches touched Kotlin tests in any module"
require_matrix_text 'All-mode is narrower: it enumerates only `app`, `domain`, `data`, and `ui-components`'
require_matrix_text 'diff-mode covered when touched; all-mode does not enumerate `quality/detekt-rules`'

for gate in \
  "quality/scripts/kotlin_fast_quality_check.sh" \
  "quality/scripts/kotlin_static_quality_check.sh" \
  "quality/scripts/kotlin_quality_check.sh" \
  "quality/scripts/check_meaningful_tests.sh" \
  "MEANINGFUL_TEST_CHECK_ALL" \
  "MEANINGFUL_TEST_CHECK_MODE" \
  "--all" \
  "changed files" \
  "full gate" \
  "Risk" \
  "Migration plan"
do
  require_matrix_text "$gate"
done

echo "testing coverage matrix contract passed"
