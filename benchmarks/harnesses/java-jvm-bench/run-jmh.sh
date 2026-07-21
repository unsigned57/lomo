#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR/../../.."
RESULTS_DIR="$SCRIPT_DIR/build/results/jmh"
INCLUDE=""
PUBLISH=false

cd "$SCRIPT_DIR"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --include)
            INCLUDE="$2"
            shift 2
            ;;
        --publish)
            PUBLISH=true
            shift
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

mkdir -p "$RESULTS_DIR"

GRADLE_ARGS=()
if [[ -n "$INCLUDE" ]]; then
    GRADLE_ARGS+=("-PjmhInclude=$INCLUDE")
fi
GRADLE_ARGS+=("jmh")

./gradlew "${GRADLE_ARGS[@]}"

python3 "$ROOT_DIR/benchmarks/scripts/jmh_to_benchmark_run.py" \
    --suite java-jvm \
    --results "$RESULTS_DIR/results.json" \
    --output "$RESULTS_DIR/benchmark_run.json"

if [[ "$PUBLISH" == true ]]; then
    "$ROOT_DIR/benchmarks/scripts/publish-benchmark-runs.sh" "$RESULTS_DIR/benchmark_run.json"
fi
