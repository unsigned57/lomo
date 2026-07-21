#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR/../../.."
DEMO_MANIFEST="$ROOT_DIR/examples/demo/Cargo.toml"

cd "$SCRIPT_DIR"

DIST_DIR="dist/java"
PACKAGE="demo"
BENCH_LIBRARY_BASENAME="bench_uniffi"
UNIFFI_BINDGEN_JAVA_VERSION="0.4.2"

resolve_bindgen_java() {
    if [[ -n "${UNIFFI_BINDGEN_JAVA:-}" && -x "${UNIFFI_BINDGEN_JAVA}" ]]; then
        printf '%s\n' "${UNIFFI_BINDGEN_JAVA}"
        return 0
    fi

    if command -v uniffi-bindgen-java >/dev/null 2>&1; then
        command -v uniffi-bindgen-java
        return 0
    fi

    local install_root="$SCRIPT_DIR/target/uniffi-bindgen-java-$UNIFFI_BINDGEN_JAVA_VERSION"
    local install_binary="$install_root/bin/uniffi-bindgen-java"

    if [[ -x "$install_binary" ]]; then
        printf '%s\n' "$install_binary"
        return 0
    fi

    cargo install \
        uniffi-bindgen-java \
        --version "$UNIFFI_BINDGEN_JAVA_VERSION" \
        --root "$install_root"

    printf '%s\n' "$install_binary"
}

export CARGO_TARGET_DIR="$SCRIPT_DIR/target"
export BOLTFFI_DISABLE_EXPORTS=1

cargo build --manifest-path "$DEMO_MANIFEST" --lib --release --features uniffi

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

if [ "$(uname)" == "Darwin" ]; then
    LIBRARY_FILE="lib${PACKAGE}.dylib"
    BENCH_LIBRARY_FILE="lib${BENCH_LIBRARY_BASENAME}.dylib"
elif [ "$(expr substr $(uname -s) 1 5)" == "Linux" ]; then
    LIBRARY_FILE="lib${PACKAGE}.so"
    BENCH_LIBRARY_FILE="lib${BENCH_LIBRARY_BASENAME}.so"
else
    echo "Unknown platform: $(uname)"
    exit 1
fi

BINDGEN_JAVA="$(resolve_bindgen_java)"

"$BINDGEN_JAVA" generate \
    --out-dir "$DIST_DIR" \
    "target/release/$LIBRARY_FILE"

find "$DIST_DIR" -name '*.java' -print0 | xargs -0 perl -0pi -e 's/\),\}/),/g; s/\);\}/);/g'

cp "target/release/$LIBRARY_FILE" "target/release/$BENCH_LIBRARY_FILE"

perl -0pi -e 's/return "demo";/return "bench_uniffi";/g; s/findLibraryName\\("demo"\\)/findLibraryName("bench_uniffi")/g' \
    "$DIST_DIR/uniffi/demo/NamespaceLibrary.java"

echo "Java FFM bindings generated in $DIST_DIR"
