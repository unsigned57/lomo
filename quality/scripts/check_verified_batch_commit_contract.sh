#!/usr/bin/env bash
set -euo pipefail

# Behavior Contract:
# - Unit under test: verified batch commit workflow script.
# - Owning layer: quality
# - Priority tier: P0
# - Capability: split one already-verified working tree into multiple commits without dropping test metadata checks.
#
# Scenarios:
# - Given a batch starts from a dirty working tree, when start records the verified tree, then it first runs
#   working-tree meaningful-test metadata checks.
# - Given a batch finishes after no tree changes, when finish reruns the final quality gate, then it first
#   checks meaningful-test metadata across the committed range.
# - Given the script exposes operator commands, when help is inspected, then all supported commands remain documented.
#
# Observable outcomes:
# - Missing start/finish metadata checks, broken shell syntax, missing help commands, or a broken self-test fail this script.
#
# TDD proof:
# - Fails before restoring the metadata checks because both start and finish calls are commented out.
#
# Excludes:
# - Real Kotlin Toolchain quality execution, real git commits in the repository, and hook installation.

repo_root="$(git rev-parse --show-toplevel)"
script="$repo_root/quality/scripts/verified_batch_commit.sh"

if [ ! -x "$script" ]; then
  echo "verified-batch-commit-contract: missing executable script: $script" >&2
  exit 1
fi

bash -n "$script"

grep -Fq "  run_working_tree_test_contract_check" "$script" ||
  {
    echo "verified-batch-commit-contract: start must run working-tree meaningful-test checks" >&2
    exit 1
  }
grep -Fq "  run_committed_range_test_contract_check \"\$base_head\"" "$script" ||
  {
    echo "verified-batch-commit-contract: finish must run committed-range meaningful-test checks" >&2
    exit 1
  }
if grep -Fq "#  run_working_tree_test_contract_check" "$script" ||
  grep -Fq "#  run_committed_range_test_contract_check" "$script"; then
  echo "verified-batch-commit-contract: meaningful-test checks must not be commented out" >&2
  exit 1
fi

help_output="$("$script" --help)"
for expected in "start" "commit" "finish" "abort" "status" "self-test"; do
  if [[ "$help_output" != *"$expected"* ]]; then
    echo "verified-batch-commit-contract: help output missing command: $expected" >&2
    exit 1
  fi
done

"$script" self-test

echo "verified-batch-commit-contract: ok"
