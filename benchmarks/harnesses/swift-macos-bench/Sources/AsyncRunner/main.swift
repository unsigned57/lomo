import BenchBoltFFI
import BenchUniffi
import Foundation

struct AsyncRunnerOptions {
    let measurementIterations: Int
    let warmupIterations: Int
    let includePattern: Regex<AnyRegexOutput>?
    let excludePattern: Regex<AnyRegexOutput>?
}

struct BenchmarkRow: Encodable {
    let name: String
    let avg: Double
    let median: Double
    let min: Double
    let max: Double
    let std_abs: Double
    let p50: Double
    let p90: Double
    let p95: Double
    let p99: Double
    let iterations: Double
    let warmup: Double
}

struct BenchmarkPayload: Encodable {
    let benchmarks: [BenchmarkRow]
}

enum AsyncRunnerError: Error, CustomStringConvertible {
    case invalidRegex(String)
    case invalidValue(String, String)

    var description: String {
        switch self {
        case let .invalidRegex(pattern):
            return "invalid regex: \(pattern)"
        case let .invalidValue(flag, value):
            return "invalid value for \(flag): \(value)"
        }
    }
}

@main
struct SwiftBenchAsync {
    static func main() async throws {
        let options = try parseOptions(arguments: Array(CommandLine.arguments.dropFirst()))
        var rows: [BenchmarkRow] = []

        if options.shouldRun("boltffi_async_add") {
            rows.append(
                try await measureBenchmark(
                    name: "boltffi_async_add",
                    options: options,
                    operation: {
                        _ = try await BenchBoltFFI.asyncAdd(a: 100, b: 200)
                    }
                )
            )
        }

        if options.shouldRun("uniffi_async_add") {
            rows.append(
                try await measureBenchmark(
                    name: "uniffi_async_add",
                    options: options,
                    operation: {
                        _ = await BenchUniffi.asyncAdd(a: 100, b: 200)
                    }
                )
            )
        }

        let payload = BenchmarkPayload(benchmarks: rows)
        let encoder = JSONEncoder()
        let data = try encoder.encode(payload)
        FileHandle.standardOutput.write(data)
        FileHandle.standardOutput.write(Data("\n".utf8))
    }

    static func parseOptions(arguments: [String]) throws -> AsyncRunnerOptions {
        var measurementIterations = 10_000
        var warmupIterations = 1_000
        var includePattern: Regex<AnyRegexOutput>?
        var excludePattern: Regex<AnyRegexOutput>?

        var index = 0
        while index < arguments.count {
            let argument = arguments[index]
            switch argument {
            case "--iterations":
                let value = try value(after: index, in: arguments, flag: argument)
                measurementIterations = try parseInt(value, flag: argument)
                index += 2
            case "--warmup-iterations":
                let value = try value(after: index, in: arguments, flag: argument)
                warmupIterations = try parseInt(value, flag: argument)
                index += 2
            case "--filter":
                let pattern = try value(after: index, in: arguments, flag: argument)
                includePattern = try compileRegex(pattern)
                index += 2
            case "--filter-not":
                let pattern = try value(after: index, in: arguments, flag: argument)
                excludePattern = try compileRegex(pattern)
                index += 2
            case "--format", "--columns":
                _ = try value(after: index, in: arguments, flag: argument)
                index += 2
            case "--quiet", "--allow-debug-build":
                index += 1
            default:
                index += 1
            }
        }

        return AsyncRunnerOptions(
            measurementIterations: measurementIterations,
            warmupIterations: warmupIterations,
            includePattern: includePattern,
            excludePattern: excludePattern
        )
    }

    static func value(after index: Int, in arguments: [String], flag: String) throws -> String {
        let nextIndex = index + 1
        guard nextIndex < arguments.count else {
            throw AsyncRunnerError.invalidValue(flag, "<missing>")
        }
        return arguments[nextIndex]
    }

    static func parseInt(_ value: String, flag: String) throws -> Int {
        guard let parsed = Int(value), parsed >= 0 else {
            throw AsyncRunnerError.invalidValue(flag, value)
        }
        return parsed
    }

    static func compileRegex(_ pattern: String) throws -> Regex<AnyRegexOutput> {
        do {
            return try Regex(pattern)
        } catch {
            throw AsyncRunnerError.invalidRegex(pattern)
        }
    }

    static func measureBenchmark(
        name: String,
        options: AsyncRunnerOptions,
        operation: @escaping () async throws -> Void
    ) async throws -> BenchmarkRow {
        for _ in 0..<options.warmupIterations {
            try await operation()
        }

        var samples: [Double] = []
        samples.reserveCapacity(options.measurementIterations)

        let clock = ContinuousClock()
        for _ in 0..<options.measurementIterations {
            let start = clock.now
            try await operation()
            let elapsed = start.duration(to: clock.now)
            samples.append(elapsed.nanoseconds)
        }

        return BenchmarkRow(
            name: name,
            avg: samples.mean,
            median: samples.percentile(0.50),
            min: samples.min() ?? 0,
            max: samples.max() ?? 0,
            std_abs: samples.populationStandardDeviation,
            p50: samples.percentile(0.50),
            p90: samples.percentile(0.90),
            p95: samples.percentile(0.95),
            p99: samples.percentile(0.99),
            iterations: Double(options.measurementIterations),
            warmup: Double(options.warmupIterations)
        )
    }
}

extension AsyncRunnerOptions {
    func shouldRun(_ benchmarkName: String) -> Bool {
        if let includePattern, benchmarkName.firstMatch(of: includePattern) == nil {
            return false
        }

        if let excludePattern, benchmarkName.firstMatch(of: excludePattern) != nil {
            return false
        }

        return true
    }
}

extension Duration {
    var nanoseconds: Double {
        let components = components
        return Double(components.seconds) * 1_000_000_000 + Double(components.attoseconds) / 1_000_000_000
    }
}

extension Array where Element == Double {
    var mean: Double {
        guard !isEmpty else { return 0 }
        return reduce(0, +) / Double(count)
    }

    var populationStandardDeviation: Double {
        guard count > 1 else { return 0 }
        let sampleMean = mean
        let variance = reduce(0) { partial, value in
            let delta = value - sampleMean
            return partial + delta * delta
        } / Double(count)
        return sqrt(variance)
    }

    func percentile(_ quantile: Double) -> Double {
        guard !isEmpty else { return 0 }
        let sortedValues = sorted()
        let scaledIndex = quantile * Double(sortedValues.count - 1)
        let lowerIndex = Int(floor(scaledIndex))
        let upperIndex = Int(ceil(scaledIndex))
        if lowerIndex == upperIndex {
            return sortedValues[lowerIndex]
        }

        let fraction = scaledIndex - Double(lowerIndex)
        let lowerValue = sortedValues[lowerIndex]
        let upperValue = sortedValues[upperIndex]
        return lowerValue + (upperValue - lowerValue) * fraction
    }
}
