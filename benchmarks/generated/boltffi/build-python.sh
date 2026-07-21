#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR/../../.."
DEMO_DIR="$ROOT_DIR/examples/demo"
PYTHON_INTERPRETER=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --python)
            PYTHON_INTERPRETER="${2:-}"
            shift 2
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

export CARGO_TARGET_DIR="$SCRIPT_DIR/target"

cd "$DEMO_DIR"

COMMAND=(
    cargo run
    --manifest-path "$ROOT_DIR/Cargo.toml"
    -p boltffi_cli
    --
    --overlay boltffi.benchmark.toml
    pack python
    --release
    --regenerate
)

if [[ -n "$PYTHON_INTERPRETER" ]]; then
    COMMAND+=(--python "$PYTHON_INTERPRETER")
fi

"${COMMAND[@]}"
