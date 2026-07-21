#!/usr/bin/env zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$SCRIPT_DIR/../../.."
DEMO_DIR="$ROOT_DIR/examples/demo"
RUST_BOLTFFI_DIR="$ROOT_DIR/benchmarks/generated/boltffi"
RUST_UNIFFI_DIR="$ROOT_DIR/benchmarks/adapters/uniffi"
JNI_LIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs"

echo "=== Generating UniFFI Kotlin bindings ==="
"$RUST_UNIFFI_DIR/build-kotlin.sh"

echo "=== Packaging demo-based BoltFFI Android artifacts ==="
rm -rf "$RUST_BOLTFFI_DIR/dist/android/kotlin" \
       "$RUST_BOLTFFI_DIR/dist/android/include" \
       "$RUST_BOLTFFI_DIR/dist/android/jniLibs"

cd "$DEMO_DIR"
cargo run -p boltffi_cli --manifest-path "$ROOT_DIR/Cargo.toml" -- \
    --overlay boltffi.benchmark.toml \
    --cargo-arg --features \
    --cargo-arg uniffi \
    pack android --release --regenerate

perl -0pi -e 's/DataPointReader\.read\(buffer, 0\)/DataPoint.decode(WireReader(buffer))/g' \
    "$RUST_BOLTFFI_DIR/dist/android/kotlin/com/example/bench_boltffi/Demo.kt"

echo "=== Copying JNI libraries into Android app ==="
rm -rf "$JNI_LIBS_DIR"
mkdir -p "$JNI_LIBS_DIR"
cp -R "$RUST_BOLTFFI_DIR/dist/android/jniLibs/." "$JNI_LIBS_DIR/"
find "$JNI_LIBS_DIR" -name "libbench_boltffi.so" -delete

echo ""
echo "=== Android native libraries built ==="
find "$JNI_LIBS_DIR" -name "*.so" -exec ls -la {} \;
