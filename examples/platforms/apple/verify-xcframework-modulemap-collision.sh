#!/usr/bin/env bash
set -euo pipefail

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/boltffi-xcframework-modulemap.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

package_dir="$tmp_dir/FrameworkSmoke"
sdk_path="$(xcrun --sdk macosx --show-sdk-path)"
host_arch="$(uname -m)"
macos_target="${host_arch}-apple-macos13.0"

generate_info_plist() {
    local framework_name="$1"
    cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>${framework_name}</string>
    <key>CFBundleIdentifier</key>
    <string>dev.boltffi.${framework_name}</string>
    <key>CFBundleName</key>
    <string>${framework_name}</string>
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

create_static_framework_xcframework() {
    local framework_name="$1"
    local symbol_name="$2"
    local return_value="$3"
    local framework_root="$tmp_dir/${framework_name}"
    local framework_path="$framework_root/${framework_name}.framework"

    mkdir -p "$framework_path/Headers/$framework_name" "$framework_path/Modules"

    cat > "$framework_path/Headers/$framework_name/${framework_name}.h" <<EOF
#pragma once
int ${symbol_name}(void);
EOF

    cat > "$framework_path/Modules/module.modulemap" <<EOF
framework module ${framework_name} {
    header "${framework_name}/${framework_name}.h"
    export *
}
EOF

    generate_info_plist "$framework_name" > "$framework_path/Info.plist"

    cat > "$framework_root/${framework_name}.c" <<EOF
int ${symbol_name}(void) {
    return ${return_value};
}
EOF

    clang \
        -target "$macos_target" \
        -isysroot "$sdk_path" \
        -c "$framework_root/${framework_name}.c" \
        -o "$framework_root/${framework_name}.o"

    libtool -static -o "$framework_path/$framework_name" "$framework_root/${framework_name}.o"

    xcodebuild \
        -create-xcframework \
        -framework "$framework_path" \
        -output "$package_dir/${framework_name}.xcframework" \
        >/dev/null
}

mkdir -p "$package_dir/Sources/Smoke"

create_static_framework_xcframework "FirstFFI" "first_ffi_value" "1"
create_static_framework_xcframework "SecondFFI" "second_ffi_value" "2"

cat > "$package_dir/Package.swift" <<'EOF'
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "FrameworkSmoke",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "Smoke", targets: ["Smoke"]),
    ],
    targets: [
        .binaryTarget(name: "FirstFFI", path: "FirstFFI.xcframework"),
        .binaryTarget(name: "SecondFFI", path: "SecondFFI.xcframework"),
        .executableTarget(
            name: "Smoke",
            dependencies: ["FirstFFI", "SecondFFI"]
        ),
    ]
)
EOF

cat > "$package_dir/Sources/Smoke/main.swift" <<'EOF'
import FirstFFI
import SecondFFI

let value = first_ffi_value() + second_ffi_value()
if value != 3 {
    fatalError("unexpected static framework result")
}
EOF

(
    cd "$package_dir"
    xcodebuild \
        -scheme FrameworkSmoke \
        -destination 'platform=macOS' \
        build \
        -quiet
)
