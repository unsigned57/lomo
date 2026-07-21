// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "SwiftBench",
    platforms: [
        .macOS(.v13),
    ],
    dependencies: [
        .package(url: "https://github.com/google/swift-benchmark", from: "0.1.2"),
        .package(name: "BenchBoltFFI", path: "../../generated/boltffi/BoltFFIPackage"),
        .package(name: "BenchUniffi", path: "../../adapters/uniffi/UniffiPackage"),
    ],
    targets: [
        .executableTarget(
            name: "SwiftBenchBoltFFI",
            dependencies: [
                .product(name: "Benchmark", package: "swift-benchmark"),
                .product(name: "BenchBoltFFI", package: "BenchBoltFFI"),
            ],
            path: "Sources/BoltFFI"
        ),
        .executableTarget(
            name: "SwiftBenchUniffi",
            dependencies: [
                .product(name: "Benchmark", package: "swift-benchmark"),
                .product(name: "BenchUniffi", package: "BenchUniffi"),
            ],
            path: "Sources/Uniffi"
        ),
        .executableTarget(
            name: "SwiftBenchAsync",
            dependencies: [
                .product(name: "BenchBoltFFI", package: "BenchBoltFFI"),
                .product(name: "BenchUniffi", package: "BenchUniffi"),
            ],
            path: "Sources/AsyncRunner"
        ),
    ]
)
