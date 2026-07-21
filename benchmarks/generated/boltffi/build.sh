#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR/../../.."
DEMO_DIR="$ROOT_DIR/examples/demo"
BENCH_OVERLAY="$DEMO_DIR/boltffi.benchmark.toml"

usage() {
    echo "Usage: $0 [--platform <apple|android>] [--skip-bench] [--release|--debug]"
    echo ""
    echo "Options:"
    echo "  --platform <apple|android>  Target platform (default: apple)"
    echo "  --skip-bench                Skip running benchmarks"
    echo "  --release                   Build in release mode"
    echo "  --debug                     Build in debug mode (default)"
    echo "  -h, --help                  Show this help message"
    exit 0
}

PLATFORM="apple"
SKIP_BENCH=false
BUILD_MODE="debug"

while [[ $# -gt 0 ]]; do
    case $1 in
        --platform)
            PLATFORM="$2"
            shift 2
            ;;
        --platform=*)
            PLATFORM="${1#*=}"
            shift
            ;;
        --skip-bench)
            SKIP_BENCH=true
            shift
            ;;
        --release)
            BUILD_MODE="release"
            shift
            ;;
        --debug)
            BUILD_MODE="debug"
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Unknown option: $1"
            usage
            ;;
    esac
done

if [[ "$PLATFORM" == "ios" ]]; then
    PLATFORM="apple"
fi

if [[ "$PLATFORM" != "apple" && "$PLATFORM" != "android" ]]; then
    echo "Error: Invalid platform '$PLATFORM'. Must be 'apple' or 'android'."
    exit 1
fi

BOLTFFI_CLI="$SCRIPT_DIR/target/$BUILD_MODE/boltffi"

export CARGO_TARGET_DIR="$SCRIPT_DIR/target"

cd "$DEMO_DIR"

echo "=== Building riff CLI ($BUILD_MODE) ==="
if [[ "$BUILD_MODE" == "release" ]]; then
    cargo build --release -p boltffi_cli --manifest-path "$ROOT_DIR/Cargo.toml"
else
    cargo build -p boltffi_cli --manifest-path "$ROOT_DIR/Cargo.toml"
fi

if [[ "$PLATFORM" == "apple" ]]; then
    echo "=== Building for Apple ==="
    if [[ "$BUILD_MODE" == "release" ]]; then
        "$BOLTFFI_CLI" --overlay "$BENCH_OVERLAY" build apple --release
    else
        "$BOLTFFI_CLI" --overlay "$BENCH_OVERLAY" build apple
    fi

    echo "=== Packaging Apple artifacts ==="
    if [[ "$BUILD_MODE" == "release" ]]; then
        "$BOLTFFI_CLI" --overlay "$BENCH_OVERLAY" pack apple --release --regenerate
    else
        "$BOLTFFI_CLI" --overlay "$BENCH_OVERLAY" pack apple --regenerate
    fi

    if [[ "$SKIP_BENCH" == false ]]; then
        echo "=== Building & Running Swift Bench ==="
        if [[ ! -d "$ROOT_DIR/benchmarks/adapters/uniffi/UniffiPackage" ]]; then
            echo "=== Preparing UniFFI baseline package ==="
            "$ROOT_DIR/benchmarks/adapters/uniffi/build-xcframework.sh"
        fi
        cd ../swift-macos-bench
        rm -rf .build
        if [[ "$BUILD_MODE" == "release" ]]; then
            swift build -c release
            .build/release/SwiftBench
        else
            swift build
            .build/debug/SwiftBench --allow-debug-build
        fi
    fi

elif [[ "$PLATFORM" == "android" ]]; then
    echo "=== Building for Android targets ==="
    if [[ "$BUILD_MODE" == "release" ]]; then
        "$BOLTFFI_CLI" --overlay "$BENCH_OVERLAY" build android --release
    else
        "$BOLTFFI_CLI" --overlay "$BENCH_OVERLAY" build android
    fi

    echo "=== Packaging Android jniLibs ==="
    if [[ "$BUILD_MODE" == "release" ]]; then
        "$BOLTFFI_CLI" --overlay "$BENCH_OVERLAY" pack android --release --regenerate
    else
        "$BOLTFFI_CLI" --overlay "$BENCH_OVERLAY" pack android --regenerate
    fi

    perl -0pi -e 's/DataPointReader\.read\(buffer, 0\)/DataPoint.decode(WireReader(buffer))/g' \
        "$SCRIPT_DIR/dist/android/kotlin/com/example/bench_boltffi/Demo.kt"

    if [[ "$SKIP_BENCH" == false ]]; then
        echo "=== Running Kotlin bench ==="
        cd ../kotlin-jvm-bench
        ./gradlew test
    fi
fi

echo "=== Done ($PLATFORM) ==="
