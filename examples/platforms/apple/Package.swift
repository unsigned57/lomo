// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "DemoConsumer",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .library(name: "Demo", targets: ["Demo"]),
    ],
    dependencies: [
        .package(path: "ffi"),
    ],
    targets: [
        .target(
            name: "Demo",
            dependencies: [
                .product(name: "DemoFFI", package: "ffi"),
            ],
            path: "Sources/Demo"
        ),
        .testTarget(
            name: "DemoConsumerTests",
            dependencies: ["Demo"],
            path: "Tests/DemoConsumerTests"
        ),
    ]
)
