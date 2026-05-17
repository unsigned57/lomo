#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
script="$repo_root/quality/scripts/verified_batch_commit.sh"

if [ ! -x "$script" ]; then
  echo "verified-batch-commit-contract: missing executable script: $script" >&2
  exit 1
fi

bash -n "$script"

help_output="$("$script" --help)"
for expected in "start" "commit" "finish" "abort" "status" "self-test"; do
  if [[ "$help_output" != *"$expected"* ]]; then
    echo "verified-batch-commit-contract: help output missing command: $expected" >&2
    exit 1
  fi
done

"$script" self-test

echo "verified-batch-commit-contract: ok"
