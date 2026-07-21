#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DEMO_MANIFEST="$ROOT_DIR/examples/demo/Cargo.toml"
cd "$SCRIPT_DIR"

PACKAGE="demo"
TARGET_DIR="target"
DIST_DIR="UniffiPackage"
STAGING_DIR="${TARGET_DIR}/uniffi-staging"
ARCHIVE_NAME="bench_uniffi"

SIMULATOR_X86_64="x86_64-apple-ios"
SIMULATOR_AARCH64="aarch64-apple-ios-sim"
DEVICE_AARCH64="aarch64-apple-ios"
MACOS_X86_64="x86_64-apple-darwin"
MACOS_AARCH64="aarch64-apple-darwin"

echo "=== Building Rust targets ==="
export CARGO_TARGET_DIR="$SCRIPT_DIR/target"
export BOLTFFI_DISABLE_EXPORTS=1
for target in "$SIMULATOR_X86_64" "$SIMULATOR_AARCH64" "$DEVICE_AARCH64" "$MACOS_X86_64" "$MACOS_AARCH64"; do
    echo "Building for $target..."
    cargo build --manifest-path "$DEMO_MANIFEST" --lib --release --features uniffi --target "$target"
done

echo "=== Generating UniFFI bindings ==="
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR"

cargo run --bin uniffi-bindgen generate \
    --library "${TARGET_DIR}/${DEVICE_AARCH64}/release/lib${PACKAGE}.dylib" \
    --language swift \
    --out-dir "$STAGING_DIR"

echo "=== Creating fat libraries ==="
FAT_SIM_DIR="${TARGET_DIR}/ios-simulator-fat"
FAT_MAC_DIR="${TARGET_DIR}/macos-fat"
mkdir -p "$FAT_SIM_DIR" "$FAT_MAC_DIR"

DEVICE_ARCHIVE_PATH="${TARGET_DIR}/${DEVICE_AARCH64}/release/lib${ARCHIVE_NAME}.a"
FAT_SIM_ARCHIVE_PATH="${FAT_SIM_DIR}/lib${ARCHIVE_NAME}.a"
FAT_MAC_ARCHIVE_PATH="${FAT_MAC_DIR}/lib${ARCHIVE_NAME}.a"

cp "${TARGET_DIR}/${DEVICE_AARCH64}/release/lib${PACKAGE}.a" "$DEVICE_ARCHIVE_PATH"

lipo -create \
    "${TARGET_DIR}/${SIMULATOR_X86_64}/release/lib${PACKAGE}.a" \
    "${TARGET_DIR}/${SIMULATOR_AARCH64}/release/lib${PACKAGE}.a" \
    -output "$FAT_SIM_ARCHIVE_PATH"

lipo -create \
    "${TARGET_DIR}/${MACOS_X86_64}/release/lib${PACKAGE}.a" \
    "${TARGET_DIR}/${MACOS_AARCH64}/release/lib${PACKAGE}.a" \
    -output "$FAT_MAC_ARCHIVE_PATH"

echo "=== Preparing headers ==="
HEADER_BUNDLE="demo_headers"
HEADERS_DIR="${STAGING_DIR}/${HEADER_BUNDLE}"
mkdir -p "$HEADERS_DIR"
mv "${STAGING_DIR}"/*.h "$HEADERS_DIR/"
mv "${STAGING_DIR}"/*.modulemap "${HEADERS_DIR}/module.modulemap"

create_framework_bundle() {
    local framework_root="$1"
    local archive_path="$2"
    local headers_path="$3"

    rm -rf "$framework_root"
    mkdir -p "${framework_root}/Headers" "${framework_root}/Modules"
    cp "$archive_path" "${framework_root}/demoFFI"
    cp "${headers_path}/demoFFI.h" "${framework_root}/Headers/demoFFI.h"
    cat > "${framework_root}/Modules/module.modulemap" <<'EOF'
framework module demoFFI {
    header "demoFFI.h"
    export *
    use "Darwin"
    use "_Builtin_stdbool"
    use "_Builtin_stdint"
}
EOF
    cat > "${framework_root}/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>demoFFI</string>
    <key>CFBundleIdentifier</key>
    <string>com.mobiffi.benchuniffi.demoffi</string>
    <key>CFBundleName</key>
    <string>demoFFI</string>
    <key>CFBundlePackageType</key>
    <string>FMWK</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0</string>
    <key>CFBundleVersion</key>
    <string>1</string>
</dict>
</plist>
EOF
}

echo "=== Building XCFramework ==="
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

XCFRAMEWORK_PATH="${DIST_DIR}/BenchUniffi.xcframework"
FRAMEWORKS_DIR="${STAGING_DIR}/frameworks"
DEVICE_FRAMEWORK_PATH="${FRAMEWORKS_DIR}/ios-arm64/demoFFI.framework"
SIM_FRAMEWORK_PATH="${FRAMEWORKS_DIR}/ios-simulator/demoFFI.framework"
MAC_FRAMEWORK_PATH="${FRAMEWORKS_DIR}/macos/demoFFI.framework"

HEADERS_PATH="${STAGING_DIR}/${HEADER_BUNDLE}"
create_framework_bundle "$DEVICE_FRAMEWORK_PATH" "$DEVICE_ARCHIVE_PATH" "$HEADERS_PATH"
create_framework_bundle "$SIM_FRAMEWORK_PATH" "$FAT_SIM_ARCHIVE_PATH" "$HEADERS_PATH"
create_framework_bundle "$MAC_FRAMEWORK_PATH" "$FAT_MAC_ARCHIVE_PATH" "$HEADERS_PATH"

xcodebuild -create-xcframework \
    -framework "$DEVICE_FRAMEWORK_PATH" \
    -framework "$SIM_FRAMEWORK_PATH" \
    -framework "$MAC_FRAMEWORK_PATH" \
    -output "$XCFRAMEWORK_PATH"

echo "=== Copying Swift sources ==="
mkdir -p "${DIST_DIR}/Sources"
cp "${STAGING_DIR}"/*.swift "${DIST_DIR}/Sources/"

echo "=== Creating Package.swift ==="
cat > "${DIST_DIR}/Package.swift" << 'EOF'
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "BenchUniffi",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(
            name: "BenchUniffi",
            targets: ["BenchUniffi"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "BenchUniffiFFI",
            path: "BenchUniffi.xcframework"
        ),
        .target(
            name: "BenchUniffi",
            dependencies: ["BenchUniffiFFI"],
            path: "Sources"
        ),
    ]
)
EOF

echo "=== Done ==="
echo "Output: $DIST_DIR"
ls -la "$DIST_DIR"
