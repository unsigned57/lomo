#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

mode="${MEANINGFUL_TEST_CHECK_MODE:-auto}"

has_contract_comment() {
  local file="$1"
  local snippet

  snippet="$(sed -n '1,200p' "$file")"
  [[ "$snippet" == *"Test Contract:"* ]] &&
    [[ "$snippet" == *"Observable outcomes:"* ]] &&
    [[ "$snippet" == *"Red phase:"* ]] &&
    [[ "$snippet" == *"Excludes:"* ]]
}

contract_markdown_path() {
  local file="$1"
  local dir base

  dir="$(dirname "$file")"
  base="$(basename "$file")"
  base="${base%.kt}"
  base="${base%.kts}"

  if [ -f "$dir/$base.contract.md" ]; then
    printf '%s\n' "$dir/$base.contract.md"
    return 0
  fi

  if [ -f "$dir/$base.md" ]; then
    printf '%s\n' "$dir/$base.md"
    return 0
  fi

  return 1
}

markdown_has_contract() {
  local file="$1"
  local snippet

  [ -f "$file" ] || return 1
  snippet="$(sed -n '1,220p' "$file")"
  [[ "$snippet" == *"Test Contract"* ]] &&
    [[ "$snippet" == *"Observable outcomes"* ]] &&
    [[ "$snippet" == *"Red phase"* ]] &&
    [[ "$snippet" == *"Excludes"* ]]
}

has_scenario_matrix_block() {
  local file="$1"

  [ -f "$file" ] || return 1

  awk '
    NR <= 250 {
      if ($0 ~ /Scenario matrix:/) {
        matrix_seen = 1
        window = 30
      }

      if (window > 0) {
        if ($0 ~ /- Happy:/) happy = 1
        if ($0 ~ /- Boundary:/) boundary = 1
        if ($0 ~ /- Failure:/) failure = 1
        if ($0 ~ /- Must-not-happen:/) must_not_happen = 1
        window--
      }
    }

    END {
      exit !(matrix_seen && happy && boundary && failure && must_not_happen)
    }
  ' "$file"
}

red_phase_is_not_applicable() {
  local file="$1"

  [ -f "$file" ] || return 1

  awk '
    NR <= 250 {
      if ($0 ~ /Red phase:/) {
        red_phase_seen = 1
        window = 10
      }

      if (window > 0 && $0 ~ /Not applicable/) {
        not_applicable = 1
      }

      if (window > 0) {
        window--
      }
    }

    END {
      exit !(red_phase_seen && not_applicable)
    }
  ' "$file"
}

is_architecture_allowlisted_path() {
  local file="$1"
  [[ "$file" =~ /src/test(/[^/]+)*/architecture/ ]]
}

is_fixture_support_path() {
  local file="$1"
  [[ "$file" == quality/scripts/test/check_meaningful_tests_fixtures/* ]]
}

is_test_support_path() {
  local file="$1"
  [[ "$file" =~ /src/(test|androidTest)(/[^/]+)*/testing/ ]]
}

has_architecture_boundary_marker() {
  local file="$1"
  local snippet

  snippet="$(sed -n '1,30p' "$file")"
  [[ "$snippet" == *"// architectural-boundary-check"* ]]
}

is_source_string_assertion_test() {
  local file="$1"

  grep -Eq 'import[[:space:]]+java\.io\.File' "$file" &&
    grep -Eq '\.readText\(\)' "$file" &&
    (
      grep -Eq 'assertTrue\(.*contains\(' "$file" ||
        grep -Eq '\bshouldContain\b' "$file" ||
        grep -Eq '\.contains\(' "$file"
    )
}

has_half_migrated_assertions() {
  local file="$1"

  grep -Eq 'import[[:space:]]+org\.junit\.Assert\.' "$file" &&
    grep -Eq 'import[[:space:]]+io\.kotest\.matchers\.' "$file"
}

vintage_dependency_enabled() {
  local -a files=(
    gradle/libs.versions.toml
    app/build.gradle.kts
    domain/build.gradle.kts
    data/build.gradle.kts
    ui-components/build.gradle.kts
  )
  local existing=()
  local file

  for file in "${files[@]}"; do
    [ -f "$file" ] && existing+=("$file")
  done

  [ "${#existing[@]}" -gt 0 ] &&
    grep -Eq 'junit-vintage-engine|libs\.junit\.vintage\.engine' "${existing[@]}"
}

collect_diff() {
  local -a diff_args=("$@")

  if [ -n "${MEANINGFUL_TEST_DIFF_BASE:-}" ]; then
    git diff "${diff_args[@]}" "${MEANINGFUL_TEST_DIFF_BASE}"...HEAD --
    return
  fi

  case "$mode" in
    staged)
      git diff "${diff_args[@]}" --cached --
      return
      ;;
    working-tree)
      git diff "${diff_args[@]}" HEAD --
      return
      ;;
  esac

  if [ "${CI:-}" = "true" ] || [ "$mode" = "ci" ]; then
    if [ -n "${GITHUB_BASE_REF:-}" ] && git rev-parse --verify -q "origin/${GITHUB_BASE_REF}" >/dev/null; then
      git diff "${diff_args[@]}" "origin/${GITHUB_BASE_REF}"...HEAD --
      return
    fi

    if git rev-parse --verify -q HEAD~1 >/dev/null; then
      git diff "${diff_args[@]}" HEAD~1...HEAD --
      return
    fi

    return
  fi

  git diff "${diff_args[@]}" --cached --
}

collect_changed_files() {
  collect_diff --name-only --diff-filter=ACMR
  if [ "$mode" = "working-tree" ]; then
    git ls-files --others --exclude-standard
  fi
}

collect_changed_statuses() {
  collect_diff --name-status --diff-filter=ACMR
  if [ "$mode" = "working-tree" ]; then
    git ls-files --others --exclude-standard | sed $'s/^/A\t/'
  fi
}

mapfile -t changed_files < <(collect_changed_files | sort -u)
mapfile -t changed_statuses < <(collect_changed_statuses)

declare -A changed_file_statuses=()
for entry in "${changed_statuses[@]}"; do
  IFS=$'\t' read -r raw_status old_path new_path <<<"$entry"
  status="${raw_status%%[0-9]*}"
  path="$old_path"

  if [ -n "${new_path:-}" ]; then
    path="$new_path"
  fi

  if is_fixture_support_path "$path" || is_test_support_path "$path"; then
    continue
  fi

  changed_file_statuses["$path"]="$status"
done

production_source_changed=false
for file in "${changed_files[@]}"; do
  if is_fixture_support_path "$file" || is_test_support_path "$file"; then
    continue
  fi
  if [[ "$file" =~ /src/main/.*\.(kt|java)$ ]]; then
    production_source_changed=true
    break
  fi
done

test_files=()
for file in "${changed_files[@]}"; do
  if is_fixture_support_path "$file" || is_test_support_path "$file"; then
    continue
  fi
  case "$file" in
    */src/test/*.kt|*/src/test/*.kts|*/src/androidTest/*.kt|*/src/androidTest/*.kts)
      [ -f "$file" ] && test_files+=("$file")
      ;;
  esac
done

vintage_enabled=false
if vintage_dependency_enabled; then
  vintage_enabled=true
fi

if [ "${#test_files[@]}" -eq 0 ]; then
  echo "meaningful-test-check: no changed test files to validate"
  exit 0
fi

failures=()
for file in "${test_files[@]}"; do
  file_status="${changed_file_statuses["$file"]:-M}"
  if has_contract_comment "$file"; then
    contract_source="$file"
  else
    contract_source=""
    if contract_doc="$(contract_markdown_path "$file")" && markdown_has_contract "$contract_doc"; then
      contract_source="$contract_doc"
    fi
  fi

  if [ -z "$contract_source" ]; then
    failures+=("$file: missing test contract or TDD red-phase metadata.")
    continue
  fi

  if [ "$file_status" = "A" ] && ! has_scenario_matrix_block "$contract_source"; then
    failures+=("$file: Scenario matrix is required for newly added test files.")
  fi

  if [ "$file_status" = "A" ] &&
    is_source_string_assertion_test "$file" &&
    ! is_architecture_allowlisted_path "$file" &&
    ! has_architecture_boundary_marker "$file"; then
    failures+=("$file: Source-string assertion test forbidden. Extract the production logic into a testable unit and assert observable outcomes. To allow an architecture-boundary file, add the \`// architectural-boundary-check\` marker.")
  fi

  if [ "$vintage_enabled" = true ] && has_half_migrated_assertions "$file"; then
    failures+=("$file: Half-migrated test file. Convert all assertions in this file in one PR; do not mix styles.")
  fi

  if [ "$production_source_changed" = true ] && red_phase_is_not_applicable "$contract_source"; then
    failures+=("$file: Red phase cannot be 'Not applicable' when production code changed. State how the new test fails before the fix, or split the change.")
  fi
done

if [ "${#failures[@]}" -eq 0 ]; then
  echo "meaningful-test-check: validated ${#test_files[@]} changed test file(s)"
  exit 0
fi

echo "meaningful-test-check: validation failed:" >&2
for failure in "${failures[@]}"; do
  echo "  - $failure" >&2
done

cat >&2 <<'EOF'

Add one of the following before merging:
1. A comment within the first 200 lines containing:
   - Test Contract:
   - Scenario matrix:
   - Observable outcomes:
   - Red phase:
   - Excludes:
2. Or an adjacent markdown file named <TestFile>.contract.md with the same sections.
Newly added test files must include the Scenario matrix entries for Happy, Boundary, Failure, and Must-not-happen.
EOF

exit 1
