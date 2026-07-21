#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR/../../.."
DEMO_MANIFEST="$ROOT_DIR/examples/demo/Cargo.toml"

cd "$SCRIPT_DIR"

PACKAGE="demo"
TARGET_DIR="target"
DIST_DIR="dist/kotlin"
BENCH_LIBRARY_BASENAME="bench_uniffi"

export CARGO_TARGET_DIR="$SCRIPT_DIR/target"
export BOLTFFI_DISABLE_EXPORTS=1

cargo build --manifest-path "$DEMO_MANIFEST" --lib --release --features uniffi

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

if [ "$(uname)" == "Darwin" ]; then
    # Mac
    LIBRARY_FILE="lib${PACKAGE}.dylib"
    BENCH_LIBRARY_FILE="lib${BENCH_LIBRARY_BASENAME}.dylib"
elif [ "$(expr substr $(uname -s) 1 5)" == "Linux" ]; then
    # Linux
    LIBRARY_FILE="lib${PACKAGE}.so"
    BENCH_LIBRARY_FILE="lib${BENCH_LIBRARY_BASENAME}.so"
else
    echo "Unknown platform: $(uname)"
    exit 1
fi

cargo run --bin uniffi-bindgen generate \
  --library "${TARGET_DIR}/release/$LIBRARY_FILE" \
  --language kotlin \
  --out-dir "$DIST_DIR"

cp "${TARGET_DIR}/release/$LIBRARY_FILE" "${TARGET_DIR}/release/$BENCH_LIBRARY_FILE"

perl -0pi -e 's/return "demo"/return "bench_uniffi"/g; s/componentName = "demo"/componentName = "bench_uniffi"/g' \
  "$DIST_DIR/uniffi/demo/demo.kt"
