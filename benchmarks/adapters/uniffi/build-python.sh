#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR/../../.."
DEMO_MANIFEST="$ROOT_DIR/examples/demo/Cargo.toml"
DIST_DIR="$SCRIPT_DIR/dist/python"
TARGET_DIR="$SCRIPT_DIR/target"
PACKAGE="demo"

export CARGO_TARGET_DIR="$TARGET_DIR"
export BOLTFFI_DISABLE_EXPORTS=1

cd "$SCRIPT_DIR"

cargo build --manifest-path "$DEMO_MANIFEST" --lib --release --features uniffi

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

if [[ "$(uname)" == "Darwin" ]]; then
    LIBRARY_FILE="lib${PACKAGE}.dylib"
elif [[ "$(expr substr "$(uname -s)" 1 5)" == "Linux" ]]; then
    LIBRARY_FILE="lib${PACKAGE}.so"
else
    echo "Unknown platform: $(uname)" >&2
    exit 1
fi

cargo run --bin uniffi-bindgen generate \
    --library "$TARGET_DIR/release/$LIBRARY_FILE" \
    --language python \
    --out-dir "$DIST_DIR"

cp "$TARGET_DIR/release/$LIBRARY_FILE" "$DIST_DIR/$LIBRARY_FILE"
