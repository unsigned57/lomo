import Benchmark
import BenchUniffi
import Foundation

private let shouldLogSetup = ProcessInfo.processInfo.environment["BENCH_QUIET_SETUP"] != "1"

private func setupLog(_ message: String) {
    if shouldLogSetup {
        print(message)
    }
}

setupLog("DEBUG: Starting - about to initialize globals...")
let echoBytes64k = Data(repeating: 42, count: 65536)
let echoVecI32Values10k = (0 ..< 10_000).map(Int32.init)

let uniffiLocations1k = BenchUniffi.generateLocations(count: 1000)
let uniffiLocations10k = BenchUniffi.generateLocations(count: 10000)
let uniffiTrades1k = BenchUniffi.generateTrades(count: 1000)
let uniffiTrades10k = BenchUniffi.generateTrades(count: 10000)
let uniffiParticles1k = BenchUniffi.generateParticles(count: 1000)
let uniffiParticles10k = BenchUniffi.generateParticles(count: 10000)
let uniffiSensors1k = BenchUniffi.generateSensorReadings(count: 1000)
let uniffiSensors10k = BenchUniffi.generateSensorReadings(count: 10000)
let uniffiI32Vec10k = BenchUniffi.generateI32Vec(count: 10000)
let uniffiI32Vec100k = BenchUniffi.generateI32Vec(count: 100_000)
let uniffiF64Vec10k = BenchUniffi.generateF64Vec(count: 10000)

benchmark("uniffi_noop") { BenchUniffi.noop() }

benchmark("uniffi_echo_i32") { _ = BenchUniffi.echoI32(v: 42) }

benchmark("uniffi_echo_f64") { _ = BenchUniffi.echoF64(v: 3.14159) }

benchmark("uniffi_echo_bool") { _ = BenchUniffi.echoBool(v: true) }

benchmark("uniffi_negate_bool") { _ = BenchUniffi.negateBool(v: true) }

benchmark("uniffi_add") { _ = BenchUniffi.add(a: 100, b: 200) }

benchmark("uniffi_add_f64") { _ = BenchUniffi.addF64(a: 1.25, b: 2.5) }

benchmark("uniffi_multiply") { _ = BenchUniffi.multiply(a: 2.5, b: 4.0) }

benchmark("uniffi_inc_u64") {
    var x: UInt64 = 0
    x = BenchUniffi.incU64Value(value: x)
    precondition(x == 1)
}

benchmark("uniffi_inc_u64_value") {
    let value = BenchUniffi.incU64Value(value: 0)
    precondition(value == 1)
}

benchmark("uniffi_echo_string_small") { _ = BenchUniffi.echoString(v: "hello") }

benchmark("uniffi_echo_string_1k") {
    _ = BenchUniffi.echoString(v: String(repeating: "x", count: 1000))
}

benchmark("uniffi_generate_string_1k") { _ = BenchUniffi.generateString(size: 1000) }

benchmark("uniffi_echo_bytes_64k") { _ = BenchUniffi.echoBytes(data: echoBytes64k) }

benchmark("uniffi_echo_vec_i32_10k") { _ = BenchUniffi.echoVecI32(v: echoVecI32Values10k) }

benchmark("uniffi_generate_locations_1k") { _ = BenchUniffi.generateLocations(count: 1000) }

benchmark("uniffi_generate_locations_10k") { _ = BenchUniffi.generateLocations(count: 10000) }

benchmark("uniffi_generate_trades_1k") { _ = BenchUniffi.generateTrades(count: 1000) }

benchmark("uniffi_generate_trades_10k") { _ = BenchUniffi.generateTrades(count: 10000) }

benchmark("uniffi_generate_particles_1k") { _ = BenchUniffi.generateParticles(count: 1000) }

benchmark("uniffi_generate_particles_10k") { _ = BenchUniffi.generateParticles(count: 10000) }

benchmark("uniffi_generate_sensor_readings_1k") { _ = BenchUniffi.generateSensorReadings(count: 1000) }

benchmark("uniffi_generate_sensor_readings_10k") { _ = BenchUniffi.generateSensorReadings(count: 10000) }

benchmark("uniffi_generate_i32_vec_10k") { _ = BenchUniffi.generateI32Vec(count: 10000) }

benchmark("uniffi_generate_i32_vec_100k") { _ = BenchUniffi.generateI32Vec(count: 100_000) }

benchmark("uniffi_generate_f64_vec_10k") { _ = BenchUniffi.generateF64Vec(count: 10000) }

benchmark("uniffi_generate_bytes_64k") { _ = BenchUniffi.generateBytes(size: 65536) }

benchmark("uniffi_sum_ratings_1k") { _ = BenchUniffi.sumRatings(locations: uniffiLocations1k) }

benchmark("uniffi_sum_ratings_10k") { _ = BenchUniffi.sumRatings(locations: uniffiLocations10k) }

benchmark("uniffi_sum_trade_volumes_1k") { _ = BenchUniffi.sumTradeVolumes(trades: uniffiTrades1k) }

benchmark("uniffi_sum_trade_volumes_10k") {
    _ = BenchUniffi.sumTradeVolumes(trades: uniffiTrades10k)
}

benchmark("uniffi_sum_particle_masses_1k") {
    _ = BenchUniffi.sumParticleMasses(particles: uniffiParticles1k)
}

benchmark("uniffi_sum_particle_masses_10k") {
    _ = BenchUniffi.sumParticleMasses(particles: uniffiParticles10k)
}

benchmark("uniffi_avg_sensor_temp_1k") {
    _ = BenchUniffi.avgSensorTemperature(readings: uniffiSensors1k)
}

benchmark("uniffi_avg_sensor_temp_10k") {
    _ = BenchUniffi.avgSensorTemperature(readings: uniffiSensors10k)
}

benchmark("uniffi_sum_i32_vec_10k") { _ = BenchUniffi.sumI32Vec(values: uniffiI32Vec10k) }

benchmark("uniffi_sum_i32_vec_100k") { _ = BenchUniffi.sumI32Vec(values: uniffiI32Vec100k) }

benchmark("uniffi_sum_f64_vec_10k") { _ = BenchUniffi.sumF64Vec(values: uniffiF64Vec10k) }

benchmark("uniffi_process_locations_1k") {
    _ = BenchUniffi.processLocations(locations: uniffiLocations1k)
}

benchmark("uniffi_process_locations_10k") {
    _ = BenchUniffi.processLocations(locations: uniffiLocations10k)
}

benchmark("uniffi_counter_increment_mutex") {
    let counter = BenchUniffi.Counter(initial: 0)
    for _ in 0 ..< 1000 {
        counter.increment()
    }
    precondition(counter.get() == 1000)
}

benchmark("uniffi_datastore_add_record_1k") {
    let store = BenchUniffi.DataStore()
    for i in 0 ..< 1000 {
        store.add(
            point: BenchUniffi.DataPoint(x: Double(i), y: Double(i) * 2.0, timestamp: Int64(i)))
    }
    precondition(store.len() == 1000)
}

benchmark("uniffi_accumulator_mutex") {
    let acc = BenchUniffi.Accumulator()
    for i: Int64 in 0 ..< 1000 {
        acc.add(amount: i)
    }
    _ = acc.get()
    acc.reset()
}

benchmark("uniffi_simple_enum") {
    _ = BenchUniffi.oppositeDirection(d: .north)
    _ = BenchUniffi.directionToDegrees(direction: .east)
}

benchmark("uniffi_echo_direction") { _ = BenchUniffi.echoDirection(d: .north) }

benchmark("uniffi_find_direction") { _ = BenchUniffi.findDirection(id: 0) }

benchmark("uniffi_data_enum_input") {
    _ = BenchUniffi.getStatusProgress(status: .inProgress(progress: 50))
    _ = BenchUniffi.isStatusComplete(status: .completed(result: 100))
}

let uniffiDirections1k = BenchUniffi.generateDirections(count: 1000)
let uniffiDirections10k = BenchUniffi.generateDirections(count: 10000)

benchmark("uniffi_generate_directions_1k") { _ = BenchUniffi.generateDirections(count: 1000) }

benchmark("uniffi_generate_directions_10k") { _ = BenchUniffi.generateDirections(count: 10000) }

benchmark("uniffi_count_north_1k") { _ = BenchUniffi.countNorth(directions: uniffiDirections1k) }

benchmark("uniffi_count_north_10k") { _ = BenchUniffi.countNorth(directions: uniffiDirections10k) }

benchmark("uniffi_find_even_100") {
    for i: Int32 in 0 ..< 100 {
        _ = BenchUniffi.findEven(value: i)
    }
}

benchmark("uniffi_find_positive_f64") { _ = BenchUniffi.findPositiveF64(value: 3.14) }

benchmark("uniffi_find_name") { _ = BenchUniffi.findName(id: 1) }

benchmark("uniffi_find_names_100") { _ = BenchUniffi.findNames(count: 100) }

benchmark("uniffi_find_numbers_100") { _ = BenchUniffi.findNumbers(count: 100) }

benchmark("uniffi_find_locations_100") { _ = BenchUniffi.findLocations(count: 100) }

benchmark("uniffi_generate_user_profiles_100") {
    _ = BenchUniffi.generateUserProfiles(count: 100)
}

benchmark("uniffi_generate_user_profiles_1k") {
    _ = BenchUniffi.generateUserProfiles(count: 1000)
}

let uniffiUsers100 = BenchUniffi.generateUserProfiles(count: 100)
let uniffiUsers1k = BenchUniffi.generateUserProfiles(count: 1000)

benchmark("uniffi_sum_user_scores_100") {
    _ = BenchUniffi.sumUserScores(users: uniffiUsers100)
}

benchmark("uniffi_sum_user_scores_1k") {
    _ = BenchUniffi.sumUserScores(users: uniffiUsers1k)
}

benchmark("uniffi_count_active_users_100") {
    _ = BenchUniffi.countActiveUsers(users: uniffiUsers100)
}

benchmark("uniffi_count_active_users_1k") {
    _ = BenchUniffi.countActiveUsers(users: uniffiUsers1k)
}

final class UniffiDataProviderImpl: BenchUniffi.DataProvider {
    let points: [BenchUniffi.DataPoint]
    init(count: Int) {
        points = (0..<count).map { i in
            BenchUniffi.DataPoint(x: Double(i), y: Double(i) * 2.0, timestamp: Int64(i))
        }
    }
    func getCount() -> UInt32 { UInt32(points.count) }
    func getItem(index: UInt32) -> BenchUniffi.DataPoint { points[Int(index)] }
}

let uniffiProvider100 = UniffiDataProviderImpl(count: 100)
let uniffiProvider1k = UniffiDataProviderImpl(count: 1000)

benchmark("uniffi_callback_100") {
    let consumer = BenchUniffi.DataConsumer()
    consumer.setProvider(provider: uniffiProvider100)
    _ = consumer.computeSum()
}

benchmark("uniffi_callback_1k") {
    let consumer = BenchUniffi.DataConsumer()
    consumer.setProvider(provider: uniffiProvider1k)
    _ = consumer.computeSum()
}

Benchmark.main()
