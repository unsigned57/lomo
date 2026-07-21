#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
BENCHMARKS_DIR="$ROOT_DIR/benchmarks"
ARCHIVE_REPO="${BENCHMARK_ARCHIVE_REPO:-$ROOT_DIR/../boltffi_bench_harness}"
OUTPUT_ROOT="$ARCHIVE_REPO/public/data"

if [[ ! -d "$ARCHIVE_REPO" ]]; then
    echo "Benchmark archive repo not found at $ARCHIVE_REPO" >&2
    exit 1
fi

declare -a INCOMING_PATHS=()

if [[ $# -eq 0 ]]; then
    shopt -s nullglob
    INCOMING_PATHS=("$BENCHMARKS_DIR"/harnesses/*/build/results/*/benchmark_run.json)
    shopt -u nullglob
else
    INCOMING_PATHS=("$@")
fi

python3 "$BENCHMARKS_DIR/scripts/publish_benchmark_archive.py" \
    --output-root "$OUTPUT_ROOT" \
    "${INCOMING_PATHS[@]}"
