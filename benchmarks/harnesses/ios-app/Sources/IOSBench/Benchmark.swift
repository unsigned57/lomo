import Foundation
import BenchBoltFFI
import BenchUniffi

public struct BenchmarkResult {
    public let name: String
    public let boltffiTimeNs: UInt64
    public let uniffiTimeNs: UInt64
    public var speedup: Double { Double(uniffiTimeNs) / Double(boltffiTimeNs) }
}

public class Benchmarks {
    
    public static func runAll(iterations: Int = 10000) -> [BenchmarkResult] {
        var results: [BenchmarkResult] = []
        
        results.append(benchmarkNoop(iterations: iterations))
        results.append(benchmarkCounterIncrement(iterations: iterations))
        results.append(benchmarkCounterIncrementSingleThreaded(iterations: iterations))
        results.append(benchmarkSumRatings1k(iterations: iterations))
        results.append(benchmarkProcessLocations1k(iterations: iterations))
        results.append(benchmarkGenerateLocations1k(iterations: iterations))
        results.append(benchmarkSumUserScores100(iterations: iterations))
        results.append(benchmarkGenerateUserProfiles100(iterations: iterations))
        results.append(benchmarkDataEnumInput(iterations: iterations))
        
        return results
    }
    
    public static func runTests() -> Bool {
        let p1 = BenchBoltFFI.getStatusProgress(status: .pending)
        guard p1 == 0 else { print("FAIL: pending should be 0, got \(p1)"); return false }
        
        let p2 = BenchBoltFFI.getStatusProgress(status: .inProgress(progress: 75))
        guard p2 == 75 else { print("FAIL: inProgress(75) should be 75, got \(p2)"); return false }
        
        let p3 = BenchBoltFFI.getStatusProgress(status: .completed(result: 100))
        guard p3 == 100 else { print("FAIL: completed(100) should be 100, got \(p3)"); return false }
        
        let p4 = BenchBoltFFI.getStatusProgress(status: .failed(errorCode: -5, retryCount: 3))
        guard p4 == -5 else { print("FAIL: failed(-5,3) should be -5, got \(p4)"); return false }
        
        let c1 = BenchBoltFFI.isStatusComplete(status: .pending)
        guard c1 == false else { print("FAIL: pending should not be complete"); return false }
        
        let c2 = BenchBoltFFI.isStatusComplete(status: .completed(result: 42))
        guard c2 == true else { print("FAIL: completed should be complete"); return false }
        
        print("All data enum tests passed!")
        return true
    }
    
    public static func benchmarkNoop(iterations: Int) -> BenchmarkResult {
        let boltffiTime = measure(iterations: iterations) {
            BenchBoltFFI.noop()
        }
        let uniffiTime = measure(iterations: iterations) {
            BenchUniffi.noop()
        }
        return BenchmarkResult(name: "noop", boltffiTimeNs: boltffiTime, uniffiTimeNs: uniffiTime)
    }
    
    public static func benchmarkCounterIncrement(iterations: Int) -> BenchmarkResult {
        let boltffiCounter = BenchBoltFFI.Counter(initial: 0)
        let uniffiCounter = BenchUniffi.Counter(initial: 0)
        
        let boltffiTime = measure(iterations: iterations) {
            boltffiCounter.increment()
        }
        let uniffiTime = measure(iterations: iterations) {
            uniffiCounter.increment()
        }
        return BenchmarkResult(name: "counter_increment_mutex", boltffiTimeNs: boltffiTime, uniffiTimeNs: uniffiTime)
    }
    
    public static func benchmarkCounterIncrementSingleThreaded(iterations: Int) -> BenchmarkResult {
        let counter = BenchBoltFFI.CounterSingleThreaded()
        
        let boltffiTime = measure(iterations: iterations) {
            counter.increment()
        }
        // No UniFFI equivalent - report same time to show it's BoltFFI-only
        return BenchmarkResult(name: "counter_increment_single_threaded", boltffiTimeNs: boltffiTime, uniffiTimeNs: boltffiTime)
    }
    
    public static func benchmarkSumRatings1k(iterations: Int) -> BenchmarkResult {
        let boltffiLocations = BenchBoltFFI.generateLocations(count: 1000)
        let uniffiLocations = BenchUniffi.generateLocations(count: 1000)
        
        let boltffiTime = measure(iterations: iterations) {
            _ = BenchBoltFFI.sumRatings(locations: boltffiLocations)
        }
        let uniffiTime = measure(iterations: iterations) {
            _ = BenchUniffi.sumRatings(locations: uniffiLocations)
        }
        return BenchmarkResult(name: "sum_ratings_1k", boltffiTimeNs: boltffiTime, uniffiTimeNs: uniffiTime)
    }
    
    public static func benchmarkProcessLocations1k(iterations: Int) -> BenchmarkResult {
        let boltffiLocations = BenchBoltFFI.generateLocations(count: 1000)
        let uniffiLocations = BenchUniffi.generateLocations(count: 1000)
        
        let boltffiTime = measure(iterations: iterations) {
            _ = BenchBoltFFI.processLocations(locations: boltffiLocations)
        }
        let uniffiTime = measure(iterations: iterations) {
            _ = BenchUniffi.processLocations(locations: uniffiLocations)
        }
        return BenchmarkResult(name: "process_locations_1k", boltffiTimeNs: boltffiTime, uniffiTimeNs: uniffiTime)
    }
    
    public static func benchmarkGenerateLocations1k(iterations: Int) -> BenchmarkResult {
        let boltffiTime = measure(iterations: iterations) {
            _ = BenchBoltFFI.generateLocations(count: 1000)
        }
        let uniffiTime = measure(iterations: iterations) {
            _ = BenchUniffi.generateLocations(count: 1000)
        }
        return BenchmarkResult(name: "generate_locations_1k", boltffiTimeNs: boltffiTime, uniffiTimeNs: uniffiTime)
    }
    
    public static func benchmarkSumUserScores100(iterations: Int) -> BenchmarkResult {
        let boltffiProfiles = BenchBoltFFI.generateUserProfiles(count: 100)
        let uniffiProfiles = BenchUniffi.generateUserProfiles(count: 100)
        
        let boltffiTime = measure(iterations: iterations) {
            _ = BenchBoltFFI.sumUserScores(users: boltffiProfiles)
        }
        let uniffiTime = measure(iterations: iterations) {
            _ = BenchUniffi.sumUserScores(users: uniffiProfiles)
        }
        return BenchmarkResult(name: "sum_user_scores_100", boltffiTimeNs: boltffiTime, uniffiTimeNs: uniffiTime)
    }
    
    public static func benchmarkGenerateUserProfiles100(iterations: Int) -> BenchmarkResult {
        let boltffiTime = measure(iterations: iterations) {
            _ = BenchBoltFFI.generateUserProfiles(count: 100)
        }
        let uniffiTime = measure(iterations: iterations) {
            _ = BenchUniffi.generateUserProfiles(count: 100)
        }
        return BenchmarkResult(name: "generate_user_profiles_100", boltffiTimeNs: boltffiTime, uniffiTimeNs: uniffiTime)
    }
    
    public static func benchmarkDataEnumInput(iterations: Int) -> BenchmarkResult {
        let boltffiStatus = BenchBoltFFI.TaskStatus.inProgress(progress: 50)
        let uniffiStatus = BenchUniffi.TaskStatus.inProgress(progress: 50)
        let boltffiTime = measure(iterations: iterations) {
            _ = BenchBoltFFI.getStatusProgress(status: boltffiStatus)
        }
        let uniffiTime = measure(iterations: iterations) {
            _ = BenchUniffi.getStatusProgress(status: uniffiStatus)
        }
        return BenchmarkResult(name: "data_enum_input", boltffiTimeNs: boltffiTime, uniffiTimeNs: uniffiTime)
    }
    
    private static func measure(iterations: Int, _ block: () -> Void) -> UInt64 {
        let start = DispatchTime.now()
        for _ in 0..<iterations {
            block()
        }
        let end = DispatchTime.now()
        let totalNs = end.uptimeNanoseconds - start.uptimeNanoseconds
        return totalNs / UInt64(iterations)
    }
}
