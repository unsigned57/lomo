#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/ai_gradle_env.sh
source "$script_dir/ai_gradle_env.sh"

usage() {
  cat <<'EOF'
Usage: quality/scripts/verified_batch_commit.sh <command> [args...]

Commands:
  start       Run working-tree test metadata checks and qualityCheck, then record
              the verified final tree for a consecutive commit batch.
  commit      Run git commit --no-verify for one staged group in the active batch.
              Pass normal git commit arguments after "commit".
  finish      Require a clean tree matching the verified snapshot, rerun
              qualityCheck, and clear the active batch marker.
  status      Show the active batch marker and whether the current clean HEAD
              matches the verified tree.
  abort       Clear the active batch marker without running checks.
  self-test   Run local script contract checks in a temporary Git repository.

Example:
  quality/scripts/verified_batch_commit.sh start
  git add -- path/to/group
  quality/scripts/verified_batch_commit.sh commit -F /tmp/message.txt
  quality/scripts/verified_batch_commit.sh finish
EOF
}

state_file() {
  git rev-parse --git-path lomo-verified-batch-commit
}

require_repo_root() {
  repo_root="$(git rev-parse --show-toplevel)"
  cd "$repo_root"
}

snapshot_worktree_tree() {
  local tmp_index
  tmp_index="$(mktemp "${TMPDIR:-/tmp}/lomo-verified-batch-index.XXXXXX")"
  rm -f "$tmp_index"
  GIT_INDEX_FILE="$tmp_index" git read-tree HEAD
  GIT_INDEX_FILE="$tmp_index" git add -A -- .
  GIT_INDEX_FILE="$tmp_index" git write-tree
  rm -f "$tmp_index"
}

load_state() {
  local state
  state="$(state_file)"
  if [ ! -f "$state" ]; then
    echo "verified-batch-commit: no active batch. Run 'quality/scripts/verified_batch_commit.sh start' first." >&2
    exit 1
  fi
  # shellcheck source=/dev/null
  source "$state"
}

run_working_tree_test_contract_check() {
  MEANINGFUL_TEST_CHECK_MODE=working-tree "$script_dir/check_meaningful_tests.sh"
}

run_committed_range_test_contract_check() {
  local base_head="$1"
  MEANINGFUL_TEST_DIFF_BASE="$base_head" "$script_dir/check_meaningful_tests.sh"
}

run_quality_check() {
  lomo_ai_prepare_gradle_env "verified-batch-commit"
  echo "verified-batch-commit: running ./gradlew qualityCheck"
  echo "verified-batch-commit: using GRADLE_USER_HOME at $ai_gradle_user_home"
  echo "verified-batch-commit: using HOME at $ai_home"
  lomo_ai_run_gradle qualityCheck
}

start_batch() {
  require_repo_root
  local state
  state="$(state_file)"
  if [ -f "$state" ]; then
    echo "verified-batch-commit: active batch already exists at $state" >&2
    echo "Run 'quality/scripts/verified_batch_commit.sh finish' or 'abort' first." >&2
    exit 1
  fi
  if [ -z "$(git status --porcelain)" ]; then
    echo "verified-batch-commit: nothing to batch commit; working tree is clean." >&2
    exit 1
  fi

  run_working_tree_test_contract_check
  run_quality_check

  local base_head verified_tree started_at
  base_head="$(git rev-parse HEAD)"
  verified_tree="$(snapshot_worktree_tree)"
  started_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

  cat > "$state" <<EOF
base_head='$base_head'
verified_tree='$verified_tree'
started_at='$started_at'
EOF

  cat <<EOF
verified-batch-commit: started
  base HEAD:     $base_head
  verified tree: $verified_tree
  marker:        $state

Stage each logical group, then run:
  quality/scripts/verified_batch_commit.sh commit -F /tmp/message.txt

When all groups are committed, run:
  quality/scripts/verified_batch_commit.sh finish
EOF
}

commit_batch() {
  require_repo_root
  load_state
  if [ "$#" -eq 0 ]; then
    echo "verified-batch-commit: pass normal git commit arguments after 'commit'." >&2
    exit 1
  fi
  if git diff --cached --quiet --exit-code; then
    echo "verified-batch-commit: no staged changes to commit." >&2
    exit 1
  fi
  git commit --no-verify "$@"
}

finish_batch() {
  require_repo_root
  load_state
  local status
  status="$(git status --porcelain)"
  if [ -n "$status" ]; then
    echo "verified-batch-commit: working tree must be clean before finish." >&2
    printf '%s\n' "$status" >&2
    exit 1
  fi

  local current_tree
  current_tree="$(git rev-parse HEAD^{tree})"
  if [ "$current_tree" != "$verified_tree" ]; then
    cat >&2 <<EOF
verified-batch-commit: final HEAD tree does not match the tree verified at start.
  expected: $verified_tree
  current:  $current_tree

This usually means files changed after the start check, or not all verified files
were committed. Run 'abort', inspect the diff/log, and restart the batch check.
EOF
    exit 1
  fi

  run_committed_range_test_contract_check "$base_head"
  run_quality_check
  rm -f "$(state_file)"
  echo "verified-batch-commit: finished and cleared batch marker"
}

status_batch() {
  require_repo_root
  load_state
  echo "verified-batch-commit: active batch"
  echo "  started:       $started_at"
  echo "  base HEAD:     $base_head"
  echo "  verified tree: $verified_tree"
  if [ -n "$(git status --porcelain)" ]; then
    echo "  current:       working tree is not clean"
    return 0
  fi
  local current_tree
  current_tree="$(git rev-parse HEAD^{tree})"
  if [ "$current_tree" = "$verified_tree" ]; then
    echo "  current:       clean and matches verified tree"
  else
    echo "  current:       clean but does not match verified tree ($current_tree)"
  fi
}

abort_batch() {
  require_repo_root
  rm -f "$(state_file)"
  echo "verified-batch-commit: cleared batch marker"
}

self_test() {
  local tmp_dir script_path
  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/lomo-verified-batch-self-test.XXXXXX")"
  script_path="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/$(basename -- "${BASH_SOURCE[0]}")"
  LOMO_VERIFIED_BATCH_SELF_TEST_TMP_DIR="$tmp_dir"
  trap 'rm -rf "$LOMO_VERIFIED_BATCH_SELF_TEST_TMP_DIR"' EXIT

  git -C "$tmp_dir" init -q
  git -C "$tmp_dir" config user.name "Lomo Test"
  git -C "$tmp_dir" config user.email "lomo-test@example.invalid"
  printf '%s\n' "tracked-base" > "$tmp_dir/tracked.txt"
  git -C "$tmp_dir" add tracked.txt
  git -C "$tmp_dir" commit -q -m "initial"

  printf '%s\n' "tracked-edited" > "$tmp_dir/tracked.txt"
  printf '%s\n' "new-file" > "$tmp_dir/new.txt"

  local actual_tree expected_tree tmp_index
  actual_tree="$(cd "$tmp_dir" && "$script_path" snapshot-tree-for-test)"
  tmp_index="$(mktemp "${TMPDIR:-/tmp}/lomo-verified-batch-expected-index.XXXXXX")"
  rm -f "$tmp_index"
  GIT_INDEX_FILE="$tmp_index" git -C "$tmp_dir" read-tree HEAD
  GIT_INDEX_FILE="$tmp_index" git -C "$tmp_dir" add -A -- .
  expected_tree="$(GIT_INDEX_FILE="$tmp_index" git -C "$tmp_dir" write-tree)"
  rm -f "$tmp_index"

  if [ "$actual_tree" != "$expected_tree" ]; then
    echo "verified-batch-commit self-test: snapshot tree mismatch" >&2
    echo "  expected: $expected_tree" >&2
    echo "  actual:   $actual_tree" >&2
    exit 1
  fi

  git -C "$tmp_dir" add -A
  git -C "$tmp_dir" commit --no-verify -q -m "batch"
  if [ "$(git -C "$tmp_dir" rev-parse HEAD^{tree})" != "$actual_tree" ]; then
    echo "verified-batch-commit self-test: committed tree does not match snapshot" >&2
    exit 1
  fi

  echo "verified-batch-commit self-test: ok"
}

command="${1:-}"
case "$command" in
  ""|--help|-h)
    usage
    ;;
  start)
    shift
    if [ "$#" -ne 0 ]; then
      echo "verified-batch-commit: start does not accept extra arguments." >&2
      exit 1
    fi
    start_batch
    ;;
  commit)
    shift
    commit_batch "$@"
    ;;
  finish)
    shift
    if [ "$#" -ne 0 ]; then
      echo "verified-batch-commit: finish does not accept extra arguments." >&2
      exit 1
    fi
    finish_batch
    ;;
  status)
    shift
    if [ "$#" -ne 0 ]; then
      echo "verified-batch-commit: status does not accept extra arguments." >&2
      exit 1
    fi
    status_batch
    ;;
  abort)
    shift
    if [ "$#" -ne 0 ]; then
      echo "verified-batch-commit: abort does not accept extra arguments." >&2
      exit 1
    fi
    abort_batch
    ;;
  self-test)
    shift
    if [ "$#" -ne 0 ]; then
      echo "verified-batch-commit: self-test does not accept extra arguments." >&2
      exit 1
    fi
    self_test
    ;;
  snapshot-tree-for-test)
    snapshot_worktree_tree
    ;;
  *)
    echo "verified-batch-commit: unknown command: $command" >&2
    usage >&2
    exit 1
    ;;
esac
