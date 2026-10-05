#!/usr/bin/env bash
set -euo pipefail

# Behavior Contract:
# Capability: check evidence for the tested production scope; owning layer: quality; priority: P1.
# Scenarios: Given adjacent evidence or unrelated edits, when the real checker runs, then legal
#   maintenance passes; given affected production, missing evidence or malformed scope, then it fails.
# Observable outcomes: actual checker exit status and diagnostic, in committed and worktree modes.
# TDD proof: the new adjacent-evidence case fails on the original checker; rerun this suite for GREEN.
# Excludes: compiling fixture Kotlin and proving the semantic completeness of a declared scope.

repo_root="$(git rev-parse --show-toplevel)"
script_path="$repo_root/quality/scripts/check_meaningful_tests.sh"
fixtures_root="$repo_root/quality/scripts/test/check_meaningful_tests_fixtures"
fixture_temp_root="$(mktemp -d)"
trap 'rm -rf -- "$fixture_temp_root"' EXIT

copy_fixture_tree() {
  local source_dir="$1"
  local target_dir="$2"

  [ -d "$source_dir" ] || return 0

  while IFS= read -r -d '' source_file; do
    local relative_path
    relative_path="${source_file#"$source_dir"/}"
    mkdir -p "$target_dir/$(dirname "$relative_path")"
    cp "$source_file" "$target_dir/$relative_path"
  done < <(find "$source_dir" -type f -print0 | sort -z)
}

validate_fixture_schema() {
  local case_dir="$1"
  local bucket_dir

  for bucket_dir in "$case_dir"/*; do
    [ -d "$bucket_dir" ] || continue
    case "$(basename "$bucket_dir")" in
      base-src|base-test|base-gradle|head-src|head-test|head-gradle) ;;
      *)
        echo "unsupported fixture bucket: $bucket_dir" >&2
        exit 1
        ;;
    esac
  done
}

copy_fixture_phase() {
  local case_dir="$1"
  local phase="$2"
  local repo_dir="$3"

  copy_fixture_tree "$case_dir/$phase-src" "$repo_dir/apps/android/app/src"
  copy_fixture_tree "$case_dir/$phase-test" "$repo_dir/apps/android/app/test"
  copy_fixture_tree "$case_dir/$phase-gradle" "$repo_dir/gradle"
}

create_fixture_repo() {
  local case_dir="$1"
  local repo_dir="$2"

  mkdir -p "$repo_dir"
  git -C "$repo_dir" init -q
  git -C "$repo_dir" config user.name "Fixture Runner"
  git -C "$repo_dir" config user.email "fixture@example.com"
  validate_fixture_schema "$case_dir"
  copy_fixture_phase "$case_dir" base "$repo_dir"

  if [ -z "$(git -C "$repo_dir" ls-files --others --exclude-standard)" ]; then
    printf 'fixture\n' > "$repo_dir/README.md"
  fi

  git -C "$repo_dir" add -A
  git -C "$repo_dir" commit -qm "base"
  local base_sha
  base_sha="$(git -C "$repo_dir" rev-parse HEAD)"

  printf '%s\n' "$base_sha"
}

prepare_fixture_head() {
  local case_dir="$1"
  local repo_dir="$2"

  copy_fixture_phase "$case_dir" head "$repo_dir"
}

assert_contains() {
  local output="$1"
  local expected="$2"
  local case_name="$3"

  if [[ "$output" != *"$expected"* ]]; then
    echo "[$case_name] expected output to contain: $expected" >&2
    echo "$output" >&2
    exit 1
  fi
}

run_case() {
  local case_name="$1"
  local expected_status="$2"
  local expected_message="$3"
  local fixture_dir="$fixtures_root/$case_name"
  local temp_dir
  temp_dir="$(mktemp -d "$fixture_temp_root/case.XXXXXX")"
  local repo_dir="$temp_dir/repo"
  local base_sha
  base_sha="$(create_fixture_repo "$fixture_dir" "$repo_dir")"
  prepare_fixture_head "$fixture_dir" "$repo_dir"
  if [ "$#" -eq 4 ]; then
    rm -- "$repo_dir/$4"
  fi
  git -C "$repo_dir" add -A
  git -C "$repo_dir" commit -qm "head"

  local output
  local status
  set +e
  output="$(cd "$repo_dir" && MEANINGFUL_TEST_DIFF_BASE="$base_sha" "$script_path" 2>&1)"
  status=$?
  set -e

  if [ "$status" -ne "$expected_status" ]; then
    echo "[$case_name] expected exit $expected_status but got $status" >&2
    echo "$output" >&2
    exit 1
  fi

  if [ -n "$expected_message" ]; then
    assert_contains "$output" "$expected_message" "$case_name"
  fi

  rm -rf "$temp_dir"
}

run_working_tree_case() {
  local case_name="$1"
  local expected_status="$2"
  local expected_message="$3"
  local fixture_dir="$fixtures_root/$case_name"
  local temp_dir
  temp_dir="$(mktemp -d "$fixture_temp_root/case.XXXXXX")"
  local repo_dir="$temp_dir/repo"
  create_fixture_repo "$fixture_dir" "$repo_dir" >/dev/null
  prepare_fixture_head "$fixture_dir" "$repo_dir"

  local output
  local status
  set +e
  output="$(cd "$repo_dir" && MEANINGFUL_TEST_CHECK_MODE=working-tree "$script_path" 2>&1)"
  status=$?
  set -e

  if [ "$status" -ne "$expected_status" ]; then
    echo "[$case_name] expected exit $expected_status but got $status" >&2
    echo "$output" >&2
    exit 1
  fi

  if [ -n "$expected_message" ]; then
    assert_contains "$output" "$expected_message" "$case_name"
  fi

  rm -rf "$temp_dir"
}

run_case "prod_diff_not_applicable" 1 "TDD proof cannot be 'Not applicable' when production code changed."
run_case "missing_scenario_matrix" 1 "Behavior Contract scenarios must use Given/When/Then."
run_case "source_string_new_test" 1 "Source-string assertion test forbidden."
run_case "boundary_marker_allowed" 0 "validated 1 changed test file(s)"
run_case "architecture_path_allowed" 0 "validated 1 changed test file(s)"
run_case "testing_support_helper" 0 "no changed test files to validate"
run_case "test_only_not_applicable" 0 "validated 1 changed test file(s)"
run_case "half_migrated" 1 "Half-migrated test file. Convert all assertions in this file in one PR; do not mix styles."
run_working_tree_case "missing_scenario_matrix" 1 "Behavior Contract scenarios must use Given/When/Then."

run_case "adjacent_justification" 0 "validated 1 changed test file(s)"
run_case "adjacent_contract_and_justification" 0 "validated 1 changed test file(s)"
run_case "duplicate_contract" 1 "Behavior Contract must have one owner"
run_case "behavior_preserving_refactor" 0 "validated 1 changed test file(s)"
run_case "unrelated_production_not_applicable" 0 "validated 1 changed test file(s)"
run_working_tree_case "unrelated_production_not_applicable" 0 "validated 1 changed test file(s)"
run_case "related_scope_not_applicable" 1 "TDD proof cannot be 'Not applicable'"
run_case "related_scope_not_applicable" 1 "TDD proof cannot be 'Not applicable'" "apps/android/app/src/Policy.kt"
run_case "missing_scope_target" 1 "Production scope path does not exist"
run_case "invalid_scope_target" 1 "Invalid production scope"
run_case "missing_justification" 1 "Test Change Justification is required"
run_case "adjacent_contract_changed" 1 "missing Behavior Contract or TDD proof metadata"
run_working_tree_case "adjacent_contract_changed" 1 "missing Behavior Contract or TDD proof metadata"

echo "check_meaningful_tests smoke tests passed"
