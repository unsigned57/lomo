#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

mode="${MEANINGFUL_TEST_CHECK_MODE:-auto}"

has_contract_comment() {
  local file="$1"
  local snippet

  snippet="$(sed -n '1,160p' "$file")"
  [[ "$snippet" == *"Test Contract:"* ]] &&
    [[ "$snippet" == *"Observable outcomes:"* ]] &&
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
  snippet="$(sed -n '1,200p' "$file")"
  [[ "$snippet" == *"Test Contract"* ]] &&
    [[ "$snippet" == *"Observable outcomes"* ]] &&
    [[ "$snippet" == *"Excludes"* ]]
}

collect_changed_files() {
  if [ -n "${MEANINGFUL_TEST_DIFF_BASE:-}" ]; then
    git diff --name-only --diff-filter=ACMR "${MEANINGFUL_TEST_DIFF_BASE}"...HEAD --
    return
  fi

  case "$mode" in
    staged)
      git diff --cached --name-only --diff-filter=ACMR --
      return
      ;;
    working-tree)
      git diff --name-only --diff-filter=ACMR HEAD --
      return
      ;;
  esac

  if [ "${CI:-}" = "true" ] || [ "$mode" = "ci" ]; then
    if [ -n "${GITHUB_BASE_REF:-}" ] && git rev-parse --verify -q "origin/${GITHUB_BASE_REF}" >/dev/null; then
      git diff --name-only --diff-filter=ACMR "origin/${GITHUB_BASE_REF}"...HEAD --
      return
    fi

    if git rev-parse --verify -q HEAD~1 >/dev/null; then
      git diff --name-only --diff-filter=ACMR HEAD~1...HEAD --
      return
    fi

    return
  fi

  git diff --cached --name-only --diff-filter=ACMR --
}

mapfile -t changed_files < <(collect_changed_files | sort -u)

test_files=()
for file in "${changed_files[@]}"; do
  case "$file" in
    */src/test/*.kt|*/src/test/*.kts|*/src/androidTest/*.kt|*/src/androidTest/*.kts)
      [ -f "$file" ] && test_files+=("$file")
      ;;
  esac
done

if [ "${#test_files[@]}" -eq 0 ]; then
  echo "meaningful-test-check: no changed test files to validate"
  exit 0
fi

failures=()
for file in "${test_files[@]}"; do
  if has_contract_comment "$file"; then
    continue
  fi

  contract_doc=""
  if contract_doc="$(contract_markdown_path "$file")" && markdown_has_contract "$contract_doc"; then
    continue
  fi

  failures+=("$file")
done

if [ "${#failures[@]}" -eq 0 ]; then
  echo "meaningful-test-check: validated ${#test_files[@]} changed test file(s)"
  exit 0
fi

echo "meaningful-test-check: missing test contract metadata in:" >&2
for file in "${failures[@]}"; do
  echo "  - $file" >&2
done

cat >&2 <<'EOF'

Add one of the following before merging:
1. A comment within the first 160 lines containing:
   - Test Contract:
   - Observable outcomes:
   - Excludes:
2. Or an adjacent markdown file named <TestFile>.contract.md with the same sections.
EOF

exit 1
