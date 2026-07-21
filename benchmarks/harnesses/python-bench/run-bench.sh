#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR/../../.."
RESULTS_DIR="$SCRIPT_DIR/build/results/pyperf"
GENERATED_DIR="$SCRIPT_DIR/build/generated"
VENV_DIR="$SCRIPT_DIR/build/venv"
PYTHON_INTERPRETER=""
INCLUDE=""
PUBLISH=false
PYPERF_ARGS=()

resolve_python() {
    if [[ -n "$PYTHON_INTERPRETER" ]]; then
        printf '%s\n' "$PYTHON_INTERPRETER"
        return 0
    fi

    if command -v python3 >/dev/null 2>&1; then
        printf 'python3\n'
        return 0
    fi

    if command -v python >/dev/null 2>&1; then
        printf 'python\n'
        return 0
    fi

    echo "Missing python interpreter" >&2
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --python)
            PYTHON_INTERPRETER="${2:-}"
            shift 2
            ;;
        --include)
            INCLUDE="${2:-}"
            shift 2
            ;;
        --publish)
            PUBLISH=true
            shift
            ;;
        *)
            PYPERF_ARGS+=("$1")
            shift
            ;;
    esac
done

SELECTED_PYTHON="$(resolve_python)"
mkdir -p "$RESULTS_DIR" "$GENERATED_DIR"
rm -f "$RESULTS_DIR/results.json" "$RESULTS_DIR/benchmark_run.json"

rm -rf "$VENV_DIR"
"$SELECTED_PYTHON" -m venv "$VENV_DIR"
"$VENV_DIR/bin/python" -m pip install --quiet -r "$SCRIPT_DIR/requirements.txt"

"$ROOT_DIR/benchmarks/generated/boltffi/build-python.sh" --python "$SELECTED_PYTHON"
"$ROOT_DIR/benchmarks/adapters/uniffi/build-python.sh"

BOLTFFI_SITE="$GENERATED_DIR/boltffi/site"
rm -rf "$BOLTFFI_SITE"
mkdir -p "$BOLTFFI_SITE"
"$VENV_DIR/bin/python" -m pip install --quiet --target "$BOLTFFI_SITE" "$ROOT_DIR"/benchmarks/generated/boltffi/dist/python/wheelhouse/*.whl

RUNNER_COMMAND=(
    "$VENV_DIR/bin/python"
    "$SCRIPT_DIR/bench.py"
    --boltffi-site "$BOLTFFI_SITE"
    --uniffi-dir "$ROOT_DIR/benchmarks/adapters/uniffi/dist/python"
)

if (( ${#PYPERF_ARGS[@]} > 0 )); then
    RUNNER_COMMAND+=("${PYPERF_ARGS[@]}")
fi
RUNNER_COMMAND+=(--output "$RESULTS_DIR/results.json")

if [[ -n "$INCLUDE" ]]; then
    RUNNER_COMMAND+=(--include "$INCLUDE")
fi

"${RUNNER_COMMAND[@]}"

"$VENV_DIR/bin/python" "$ROOT_DIR/benchmarks/scripts/pyperf_to_run.py" \
    --results "$RESULTS_DIR/results.json" \
    --output "$RESULTS_DIR/benchmark_run.json" \
    --profile release \
    --runner-command "${RUNNER_COMMAND[*]}"

if [[ "$PUBLISH" == true ]]; then
    "$ROOT_DIR/benchmarks/scripts/publish-benchmark-runs.sh" "$RESULTS_DIR/benchmark_run.json"
fi
