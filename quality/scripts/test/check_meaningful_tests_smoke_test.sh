#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
script_path="$repo_root/quality/scripts/check_meaningful_tests.sh"
fixtures_root="$repo_root/quality/scripts/test/check_meaningful_tests_fixtures"

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

create_fixture_repo() {
  local case_dir="$1"
  local repo_dir="$2"

  mkdir -p "$repo_dir"
  git -C "$repo_dir" init -q
  git -C "$repo_dir" config user.name "Fixture Runner"
  git -C "$repo_dir" config user.email "fixture@example.com"

  if [ -d "$case_dir/base" ]; then
    copy_fixture_tree "$case_dir/base" "$repo_dir"
  else
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

  if [ -d "$case_dir/head" ]; then
    copy_fixture_tree "$case_dir/head" "$repo_dir"
  fi
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
  temp_dir="$(mktemp -d)"
  local repo_dir="$temp_dir/repo"
  local base_sha
  base_sha="$(create_fixture_repo "$fixture_dir" "$repo_dir")"
  prepare_fixture_head "$fixture_dir" "$repo_dir"
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
  temp_dir="$(mktemp -d)"
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

run_case "prod_diff_not_applicable" 1 "Red phase cannot be 'Not applicable' when production code changed."
run_case "missing_scenario_matrix" 1 "Scenario matrix is required for newly added test files."
run_case "source_string_new_test" 1 "Source-string assertion test forbidden."
run_case "boundary_marker_allowed" 0 "validated 1 changed test file(s)"
run_case "architecture_path_allowed" 0 "validated 1 changed test file(s)"
run_case "testing_support_helper" 0 "no changed test files to validate"
run_case "test_only_not_applicable" 0 "validated 1 changed test file(s)"
run_case "half_migrated" 1 "Half-migrated test file. Convert all assertions in this file in one PR; do not mix styles."
run_working_tree_case "missing_scenario_matrix" 1 "Scenario matrix is required for newly added test files."

echo "check_meaningful_tests smoke tests passed"
