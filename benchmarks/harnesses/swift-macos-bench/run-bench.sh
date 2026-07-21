#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR/../../.."
RESULTS_DIR="$SCRIPT_DIR/build/results/swift-benchmark"
PROFILE="release"
PUBLISH=false
BENCH_ARGS=()
DEFAULT_COLUMNS="name,avg,median,min,max,std_abs,p50,p90,p95,p99,iterations,warmup"
APPLE_RUST_TARGETS=(
    "aarch64-apple-ios"
    "aarch64-apple-ios-sim"
    "x86_64-apple-ios"
    "x86_64-apple-darwin"
    "aarch64-apple-darwin"
)

cd "$SCRIPT_DIR"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --debug)
            PROFILE="debug"
            shift
            ;;
        --release)
            PROFILE="release"
            shift
            ;;
        --publish)
            PUBLISH=true
            shift
            ;;
        *)
            BENCH_ARGS+=("$1")
            shift
            ;;
    esac
done

install_apple_rust_targets() {
    rustup target add "${APPLE_RUST_TARGETS[@]}"
}

prepare_boltffi_package() {
    local build_args=("--platform" "apple" "--skip-bench")

    if [[ "$PROFILE" == "release" ]]; then
        build_args+=("--release")
    else
        build_args+=("--debug")
    fi

    "$ROOT_DIR/benchmarks/generated/boltffi/build.sh" "${build_args[@]}"
}

prepare_uniffi_package() {
    "$ROOT_DIR/benchmarks/adapters/uniffi/build-xcframework.sh"
}

install_apple_rust_targets
prepare_boltffi_package
prepare_uniffi_package

mkdir -p "$RESULTS_DIR"

swift build -c "$PROFILE" --product SwiftBenchBoltFFI
swift build -c "$PROFILE" --product SwiftBenchUniffi
swift build -c "$PROFILE" --product SwiftBenchAsync

COMMON_ARGS=("--format" "json" "--quiet")
BENCH_ARGS_JOINED="${BENCH_ARGS[*]-}"
if [[ " $BENCH_ARGS_JOINED " != *" --columns "* ]]; then
    COMMON_ARGS+=("--columns" "$DEFAULT_COLUMNS")
fi
if [[ "$PROFILE" == "debug" ]]; then
    COMMON_ARGS+=("--allow-debug-build")
fi
if [[ ${#BENCH_ARGS[@]} -gt 0 ]]; then
    COMMON_ARGS+=("${BENCH_ARGS[@]}")
fi

BOLTFFI_RUNNER=("$SCRIPT_DIR/.build/$PROFILE/SwiftBenchBoltFFI" "${COMMON_ARGS[@]}")
UNIFFI_RUNNER=("$SCRIPT_DIR/.build/$PROFILE/SwiftBenchUniffi" "${COMMON_ARGS[@]}")
ASYNC_RUNNER=("$SCRIPT_DIR/.build/$PROFILE/SwiftBenchAsync" "${COMMON_ARGS[@]}")

BENCH_QUIET_SETUP=1 "${BOLTFFI_RUNNER[@]}" > "$RESULTS_DIR/boltffi_results.json"
BENCH_QUIET_SETUP=1 "${UNIFFI_RUNNER[@]}" > "$RESULTS_DIR/uniffi_results.json"
BENCH_QUIET_SETUP=1 "${ASYNC_RUNNER[@]}" > "$RESULTS_DIR/async_results.json"

python3 - "$RESULTS_DIR/boltffi_results.json" "$RESULTS_DIR/uniffi_results.json" "$RESULTS_DIR/async_results.json" "$RESULTS_DIR/results.json" <<'PY'
import json
import sys
from pathlib import Path

boltffi_path = Path(sys.argv[1])
uniffi_path = Path(sys.argv[2])
async_path = Path(sys.argv[3])
output_path = Path(sys.argv[4])

boltffi_payload = json.loads(boltffi_path.read_text())
uniffi_payload = json.loads(uniffi_path.read_text())
async_payload = json.loads(async_path.read_text())

merged_payload = dict(boltffi_payload)
merged_payload["benchmarks"] = [
    *boltffi_payload.get("benchmarks", []),
    *uniffi_payload.get("benchmarks", []),
    *async_payload.get("benchmarks", []),
]

output_path.write_text(json.dumps(merged_payload))
PY

python3 "$ROOT_DIR/benchmarks/scripts/swift_benchmark_to_run.py" \
    --results "$RESULTS_DIR/results.json" \
    --output "$RESULTS_DIR/benchmark_run.json" \
    --profile "$PROFILE" \
    --runner-command "${BOLTFFI_RUNNER[*]} && ${UNIFFI_RUNNER[*]} && ${ASYNC_RUNNER[*]}"

if [[ "$PUBLISH" == true ]]; then
    "$ROOT_DIR/benchmarks/scripts/publish-benchmark-runs.sh" "$RESULTS_DIR/benchmark_run.json"
fi
