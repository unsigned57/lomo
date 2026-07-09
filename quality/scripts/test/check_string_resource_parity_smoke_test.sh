#!/usr/bin/env bash
set -euo pipefail

# Behavior Contract
# Capability: validate Android default and zh-rCN string resource key parity for release resources.
# Scenarios:
# - Given app and ui-components resource pairs contain the same string/plural/string-array keys,
#   When the resource parity check runs, Then it exits successfully and reports checked modules.
# - Given a zh-rCN strings.xml omits a key present in the default values strings.xml,
#   When the resource parity check runs, Then it fails with the module path and missing key.
# Observable outcomes: shell exit status and diagnostic text name the affected module/key.
# TDD proof: this smoke test is expected to fail before check_string_resource_parity.sh exists, then pass once implemented.
# Excludes: translation quality, placeholder semantics, unused strings, and Android resource merge behavior.

repo_root="$(git rev-parse --show-toplevel)"
script_path="$repo_root/quality/scripts/check_string_resource_parity.sh"

write_strings_pair() {
  local module_dir="$1"
  local resource_root="$2"
  local zh_mode="$3"

  mkdir -p "$module_dir/$resource_root/values" "$module_dir/$resource_root/values-zh-rCN"

  cat > "$module_dir/$resource_root/values/strings.xml" <<'XML'
<resources>
    <string name="app_name">Lomo</string>
    <string name="release_permission_hint">Permission required</string>
    <plurals name="memo_count">
        <item quantity="one">%d memo</item>
        <item quantity="other">%d memos</item>
    </plurals>
    <string-array name="sync_modes">
        <item>Manual</item>
    </string-array>
</resources>
XML

  if [ "$zh_mode" = "complete" ]; then
    cat > "$module_dir/$resource_root/values-zh-rCN/strings.xml" <<'XML'
<resources>
    <string name="app_name">Lomo</string>
    <string name="release_permission_hint">需要权限</string>
    <plurals name="memo_count">
        <item quantity="one">%d 条记录</item>
        <item quantity="other">%d 条记录</item>
    </plurals>
    <string-array name="sync_modes">
        <item>手动</item>
    </string-array>
</resources>
XML
  else
    cat > "$module_dir/$resource_root/values-zh-rCN/strings.xml" <<'XML'
<resources>
    <string name="app_name">Lomo</string>
    <plurals name="memo_count">
        <item quantity="one">%d 条记录</item>
        <item quantity="other">%d 条记录</item>
    </plurals>
    <string-array name="sync_modes">
        <item>手动</item>
    </string-array>
</resources>
XML
  fi
}

create_fixture_repo() {
  local repo_dir="$1"
  local zh_mode="$2"

  mkdir -p "$repo_dir"
  git -C "$repo_dir" init -q
  git -C "$repo_dir" config user.name "Fixture Runner"
  git -C "$repo_dir" config user.email "fixture@example.com"

  write_strings_pair "$repo_dir/app" "res" "$zh_mode"
  write_strings_pair "$repo_dir/ui-components" "composeResources" "complete"

  git -C "$repo_dir" add -A
  git -C "$repo_dir" commit -qm "fixture"
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
  local zh_mode="$2"
  local expected_status="$3"
  local expected_message="$4"
  local temp_dir
  temp_dir="$(mktemp -d)"
  local repo_dir="$temp_dir/repo"
  create_fixture_repo "$repo_dir" "$zh_mode"

  local output
  local status
  set +e
  output="$(cd "$repo_dir" && "$script_path" 2>&1)"
  status=$?
  set -e

  if [ "$status" -ne "$expected_status" ]; then
    echo "[$case_name] expected exit $expected_status but got $status" >&2
    echo "$output" >&2
    exit 1
  fi

  assert_contains "$output" "$expected_message" "$case_name"
  rm -rf "$temp_dir"
}

run_case "complete_parity" "complete" 0 "string resource parity OK"
run_case "missing_zh_key" "missing" 1 "missing in values-zh-rCN: release_permission_hint"

echo "check_string_resource_parity smoke tests passed"
