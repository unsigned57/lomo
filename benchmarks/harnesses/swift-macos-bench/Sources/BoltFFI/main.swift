import Benchmark
import BenchBoltFFI
import Foundation

private let shouldLogSetup = ProcessInfo.processInfo.environment["BENCH_QUIET_SETUP"] != "1"

private func setupLog(_ message: String) {
    if shouldLogSetup {
        print(message)
    }
}

setupLog("DEBUG: Starting - about to initialize globals...")

let boltffiLocations1k = BenchBoltFFI.generateLocations(count: 1000)
setupLog("DEBUG: boltffiLocations1k done")
let boltffiLocations10k = BenchBoltFFI.generateLocations(count: 10000)
let boltffiTrades1k = BenchBoltFFI.generateTrades(count: 1000)
let boltffiTrades10k = BenchBoltFFI.generateTrades(count: 10000)
let boltffiParticles1k = BenchBoltFFI.generateParticles(count: 1000)
let boltffiParticles10k = BenchBoltFFI.generateParticles(count: 10000)
let boltffiSensors1k = BenchBoltFFI.generateSensorReadings(count: 1000)
let boltffiSensors10k = BenchBoltFFI.generateSensorReadings(count: 10000)
let boltffiI32Vec10k = BenchBoltFFI.generateI32Vec(count: 10000)
let boltffiI32Vec100k = BenchBoltFFI.generateI32Vec(count: 100_000)
let boltffiF64Vec10k = BenchBoltFFI.generateF64Vec(count: 10000)
let echoBytes64k = Data(repeating: 42, count: 65536)
let echoVecI32Values10k = (0 ..< 10_000).map(Int32.init)

benchmark("boltffi_noop") { BenchBoltFFI.noop() }

benchmark("boltffi_echo_i32") { _ = BenchBoltFFI.echoI32(v: 42) }

benchmark("boltffi_echo_f64") { _ = BenchBoltFFI.echoF64(v: 3.14159) }

benchmark("boltffi_echo_bool") { _ = BenchBoltFFI.echoBool(v: true) }

benchmark("boltffi_negate_bool") { _ = BenchBoltFFI.negateBool(v: true) }

benchmark("boltffi_add") { _ = BenchBoltFFI.add(a: 100, b: 200) }

benchmark("boltffi_add_f64") { _ = BenchBoltFFI.addF64(a: 1.25, b: 2.5) }

benchmark("boltffi_multiply") { _ = BenchBoltFFI.multiply(a: 2.5, b: 4.0) }

benchmark("boltffi_inc_u64") {
    var arr: [UInt64] = [0]
    BenchBoltFFI.incU64(values: &arr)
    precondition(arr[0] == 1)
}

benchmark("boltffi_inc_u64_value") {
    let value = BenchBoltFFI.incU64Value(value: 0)
    precondition(value == 1)
}

benchmark("boltffi_echo_string_small") { _ = BenchBoltFFI.echoString(v: "hello") }

benchmark("boltffi_echo_string_1k") {
    _ = BenchBoltFFI.echoString(v: String(repeating: "x", count: 1000))
}

benchmark("boltffi_generate_string_1k") { _ = BenchBoltFFI.generateString(size: 1000) }

benchmark("boltffi_echo_bytes_64k") { _ = BenchBoltFFI.echoBytes(data: echoBytes64k) }

benchmark("boltffi_echo_vec_i32_10k") { _ = BenchBoltFFI.echoVecI32(v: echoVecI32Values10k) }

benchmark("boltffi_generate_locations_1k") { _ = BenchBoltFFI.generateLocations(count: 1000) }

benchmark("boltffi_generate_locations_10k") { _ = BenchBoltFFI.generateLocations(count: 10000) }

benchmark("boltffi_generate_trades_1k") { _ = BenchBoltFFI.generateTrades(count: 1000) }

benchmark("boltffi_generate_trades_10k") { _ = BenchBoltFFI.generateTrades(count: 10000) }

benchmark("boltffi_generate_particles_1k") { _ = BenchBoltFFI.generateParticles(count: 1000) }

benchmark("boltffi_generate_particles_10k") { _ = BenchBoltFFI.generateParticles(count: 10000) }

benchmark("boltffi_generate_sensor_readings_1k") { _ = BenchBoltFFI.generateSensorReadings(count: 1000) }

benchmark("boltffi_generate_sensor_readings_10k") { _ = BenchBoltFFI.generateSensorReadings(count: 10000) }

benchmark("boltffi_generate_i32_vec_10k") { _ = BenchBoltFFI.generateI32Vec(count: 10000) }

benchmark("boltffi_generate_i32_vec_100k") { _ = BenchBoltFFI.generateI32Vec(count: 100_000) }

benchmark("boltffi_generate_f64_vec_10k") { _ = BenchBoltFFI.generateF64Vec(count: 10000) }

benchmark("boltffi_generate_bytes_64k") { _ = BenchBoltFFI.generateBytes(size: 65536) }

benchmark("boltffi_sum_ratings_1k") { _ = BenchBoltFFI.sumRatings(locations: boltffiLocations1k) }

benchmark("boltffi_sum_ratings_10k") { _ = BenchBoltFFI.sumRatings(locations: boltffiLocations10k) }

benchmark("boltffi_sum_trade_volumes_1k") { _ = BenchBoltFFI.sumTradeVolumes(trades: boltffiTrades1k) }

benchmark("boltffi_sum_trade_volumes_10k") { _ = BenchBoltFFI.sumTradeVolumes(trades: boltffiTrades10k) }

benchmark("boltffi_sum_particle_masses_1k") {
    _ = BenchBoltFFI.sumParticleMasses(particles: boltffiParticles1k)
}

benchmark("boltffi_sum_particle_masses_10k") {
    _ = BenchBoltFFI.sumParticleMasses(particles: boltffiParticles10k)
}

benchmark("boltffi_avg_sensor_temp_1k") { _ = BenchBoltFFI.avgSensorTemperature(readings: boltffiSensors1k) }

benchmark("boltffi_avg_sensor_temp_10k") {
    _ = BenchBoltFFI.avgSensorTemperature(readings: boltffiSensors10k)
}

benchmark("boltffi_sum_i32_vec_10k") { _ = BenchBoltFFI.sumI32Vec(values: boltffiI32Vec10k) }

benchmark("boltffi_sum_i32_vec_100k") { _ = BenchBoltFFI.sumI32Vec(values: boltffiI32Vec100k) }

benchmark("boltffi_sum_f64_vec_10k") { _ = BenchBoltFFI.sumF64Vec(values: boltffiF64Vec10k) }

benchmark("boltffi_process_locations_1k") {
    _ = BenchBoltFFI.processLocations(locations: boltffiLocations1k)
}

benchmark("boltffi_process_locations_10k") {
    _ = BenchBoltFFI.processLocations(locations: boltffiLocations10k)
}

benchmark("boltffi_counter_increment_mutex") {
    let counter = BenchBoltFFI.Counter(initial: 0)
    for _ in 0 ..< 1000 {
        counter.increment()
    }
    precondition(counter.get() == 1000)
}

benchmark("boltffi_counter_increment_single_threaded") {
    let counter = BenchBoltFFI.CounterSingleThreaded()
    for _ in 0 ..< 1000 {
        counter.increment()
    }
    precondition(counter.get() == 1000)
}

benchmark("boltffi_datastore_add_record_1k") {
    let store = BenchBoltFFI.DataStore()
    for i in 0 ..< 1000 {
        store.add(point: BenchBoltFFI.DataPoint(x: Double(i), y: Double(i) * 2.0, timestamp: Int64(i)))
    }
    precondition(store.len() == 1000)
}

benchmark("boltffi_accumulator_mutex") {
    let acc = BenchBoltFFI.Accumulator()
    for i: Int64 in 0 ..< 1000 {
        acc.add(amount: i)
    }
    _ = acc.get()
    acc.reset()
}

benchmark("boltffi_accumulator_single_threaded") {
    let acc = BenchBoltFFI.AccumulatorSingleThreaded()
    for i: Int64 in 0 ..< 1000 {
        acc.add(amount: i)
    }
    _ = acc.get()
    acc.reset()
}

benchmark("boltffi_simple_enum") {
    _ = BenchBoltFFI.oppositeDirection(d: .north)
    _ = BenchBoltFFI.directionToDegrees(direction: .east)
}

benchmark("boltffi_echo_direction") { _ = BenchBoltFFI.echoDirection(d: .north) }

benchmark("boltffi_find_direction") { _ = BenchBoltFFI.findDirection(id: 0) }

benchmark("boltffi_data_enum_input") {
    _ = BenchBoltFFI.getStatusProgress(status: .inProgress(progress: 50))
    _ = BenchBoltFFI.isStatusComplete(status: .completed(result: 100))
}

let boltffiDirections1k = BenchBoltFFI.generateDirections(count: 1000)
let boltffiDirections10k = BenchBoltFFI.generateDirections(count: 10000)

benchmark("boltffi_generate_directions_1k") { _ = BenchBoltFFI.generateDirections(count: 1000) }

benchmark("boltffi_generate_directions_10k") { _ = BenchBoltFFI.generateDirections(count: 10000) }

benchmark("boltffi_count_north_1k") { _ = BenchBoltFFI.countNorth(directions: boltffiDirections1k) }

benchmark("boltffi_count_north_10k") { _ = BenchBoltFFI.countNorth(directions: boltffiDirections10k) }

benchmark("boltffi_find_even_100") {
    for i: Int32 in 0 ..< 100 {
        _ = BenchBoltFFI.findEven(value: i)
    }
}

benchmark("boltffi_find_positive_f64") { _ = BenchBoltFFI.findPositiveF64(value: 3.14) }

benchmark("boltffi_find_name") { _ = BenchBoltFFI.findName(id: 1) }

benchmark("boltffi_find_names_100") { _ = BenchBoltFFI.findNames(count: 100) }

benchmark("boltffi_find_numbers_100") { _ = BenchBoltFFI.findNumbers(count: 100) }

benchmark("boltffi_find_locations_100") { _ = BenchBoltFFI.findLocations(count: 100) }

benchmark("boltffi_generate_user_profiles_100") {
    _ = BenchBoltFFI.generateUserProfiles(count: 100)
}

benchmark("boltffi_generate_user_profiles_1k") {
    _ = BenchBoltFFI.generateUserProfiles(count: 1000)
}

let boltffiUsers100 = BenchBoltFFI.generateUserProfiles(count: 100)
let boltffiUsers1k = BenchBoltFFI.generateUserProfiles(count: 1000)

benchmark("boltffi_sum_user_scores_100") {
    _ = BenchBoltFFI.sumUserScores(users: boltffiUsers100)
}

benchmark("boltffi_sum_user_scores_1k") {
    _ = BenchBoltFFI.sumUserScores(users: boltffiUsers1k)
}

benchmark("boltffi_count_active_users_100") {
    _ = BenchBoltFFI.countActiveUsers(users: boltffiUsers100)
}

benchmark("boltffi_count_active_users_1k") {
    _ = BenchBoltFFI.countActiveUsers(users: boltffiUsers1k)
}

class BoltFFIDataProviderImpl: BenchBoltFFI.DataProvider {
    let points: [BenchBoltFFI.DataPoint]
    init(count: Int) {
        points = (0..<count).map { i in
            BenchBoltFFI.DataPoint(x: Double(i), y: Double(i) * 2.0, timestamp: Int64(i))
        }
    }
    func getCount() -> UInt32 { UInt32(points.count) }
    func getItem(index: UInt32) -> BenchBoltFFI.DataPoint { points[Int(index)] }
}

let boltffiProvider100 = BoltFFIDataProviderImpl(count: 100)
let boltffiProvider1k = BoltFFIDataProviderImpl(count: 1000)

benchmark("boltffi_callback_100") {
    let consumer = BenchBoltFFI.DataConsumer()
    consumer.setProvider(provider: boltffiProvider100)
    _ = consumer.computeSum()
}

benchmark("boltffi_callback_1k") {
    let consumer = BenchBoltFFI.DataConsumer()
    consumer.setProvider(provider: boltffiProvider1k)
    _ = consumer.computeSum()
}

Benchmark.main()
