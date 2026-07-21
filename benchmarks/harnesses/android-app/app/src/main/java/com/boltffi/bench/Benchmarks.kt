package com.boltffi.bench

import com.example.bench_boltffi.*
import kotlinx.coroutines.runBlocking

fun runAllBenchmarks(onProgress: (name: String, progress: Float) -> Unit): List<BenchmarkResult> {
    val boltffiLocations1k = generateLocations(1000)
    val boltffiLocations10k = generateLocations(10000)
    val uniffiLocations1k = uniffi.demo.generateLocations(1000)
    val uniffiLocations10k = uniffi.demo.generateLocations(10000)

    val boltffiTrades1k = generateTrades(1000)
    val boltffiTrades10k = generateTrades(10000)
    val uniffiTrades1k = uniffi.demo.generateTrades(1000)
    val uniffiTrades10k = uniffi.demo.generateTrades(10000)

    val boltffiParticles1k = generateParticles(1000)
    val boltffiParticles10k = generateParticles(10000)
    val uniffiParticles1k = uniffi.demo.generateParticles(1000)
    val uniffiParticles10k = uniffi.demo.generateParticles(10000)

    val boltffiSensors1k = generateSensorReadings(1000)
    val boltffiSensors10k = generateSensorReadings(10000)
    val uniffiSensors1k = uniffi.demo.generateSensorReadings(1000)
    val uniffiSensors10k = uniffi.demo.generateSensorReadings(10000)

    val boltffiDirections1k = generateDirections(1000)
    val boltffiDirections10k = generateDirections(10000)
    val uniffiDirections1k = uniffi.demo.generateDirections(1000)
    val uniffiDirections10k = uniffi.demo.generateDirections(10000)

    val boltffiI32Vec10k = generateI32Vec(10000)
    val boltffiI32Vec100k = generateI32Vec(100_000)
    val uniffiI32Vec10k = uniffi.demo.generateI32Vec(10000)
    val uniffiI32Vec100k = uniffi.demo.generateI32Vec(100_000)
    val echoBytes64k = ByteArray(65_536) { 42 }
    val echoVecI32Values10k = IntArray(10_000) { it }

    val boltffiF64Vec10k = generateF64Vec(10000)
    val uniffiF64Vec10k = uniffi.demo.generateF64Vec(10000)

    val boltffiUsers100 = generateUserProfiles(100)
    val boltffiUsers1k = generateUserProfiles(1000)
    val uniffiUsers100 = uniffi.demo.generateUserProfiles(100)
    val uniffiUsers1k = uniffi.demo.generateUserProfiles(1000)

    val boltffiProvider100 = BoltffiDataProvider(100)
    val boltffiProvider1k = BoltffiDataProvider(1000)
    val uniffiProvider100 = UniffiDataProvider(100)
    val uniffiProvider1k = UniffiDataProvider(1000)

    val benchmarks = listOf(
        bench("noop", "1. FFI Overhead", 10000,
            boltffi = { noop() },
            uniffi = { uniffi.demo.noop() }),
        bench("echo_bool", "1. FFI Overhead", 10000,
            boltffi = { echoBool(true) },
            uniffi = { uniffi.demo.echoBool(true) }),
        bench("negate_bool", "1. FFI Overhead", 10000,
            boltffi = { negateBool(true) },
            uniffi = { uniffi.demo.negateBool(true) }),
        bench("echo_i32", "1. FFI Overhead", 10000,
            boltffi = { echoI32(42) },
            uniffi = { uniffi.demo.echoI32(42) }),
        bench("echo_f64", "1. FFI Overhead", 10000,
            boltffi = { echoF64(3.14159) },
            uniffi = { uniffi.demo.echoF64(3.14159) }),
        bench("add_f64", "1. FFI Overhead", 10000,
            boltffi = { addF64(1.25, 2.5) },
            uniffi = { uniffi.demo.addF64(1.25, 2.5) }),
        bench("add", "1. FFI Overhead", 10000,
            boltffi = { add(100, 200) },
            uniffi = { uniffi.demo.add(100, 200) }),
        bench("multiply", "1. FFI Overhead", 10000,
            boltffi = { multiply(2.5, 4.0) },
            uniffi = { uniffi.demo.multiply(2.5, 4.0) }),
        bench("inc_u64", "1. FFI Overhead", 10000,
            boltffi = {
                val values = longArrayOf(0L)
                incU64(values)
            },
            uniffi = { uniffi.demo.incU64Value(0uL) }),
        bench("inc_u64_value", "1. FFI Overhead", 10000,
            boltffi = { incU64Value(0uL) },
            uniffi = { uniffi.demo.incU64Value(0uL) }),

        bench("echo_string_small", "2. Strings", 5000,
            boltffi = { echoString("hello") },
            uniffi = { uniffi.demo.echoString("hello") }),
        bench("echo_string_1k", "2. Strings", 2000,
            boltffi = { echoString("x".repeat(1000)) },
            uniffi = { uniffi.demo.echoString("x".repeat(1000)) }),
        bench("generate_string_1k", "2. Strings", 2000,
            boltffi = { generateString(1000) },
            uniffi = { uniffi.demo.generateString(1000) }),
        bench("echo_bytes_64k", "2a. Bytes", 500,
            boltffi = { echoBytes(echoBytes64k) },
            uniffi = { uniffi.demo.echoBytes(echoBytes64k) }),
        bench("echo_vec_i32_10k", "2b. Primitive Vectors", 500,
            boltffi = { echoVecI32(echoVecI32Values10k) },
            uniffi = { uniffi.demo.echoVecI32(echoVecI32Values10k.toList()) }),

        bench("generate_locations_1k", "3. Rust→Kotlin Blittable", 500,
            boltffi = { generateLocations(1000) },
            uniffi = { uniffi.demo.generateLocations(1000) }),
        bench("generate_locations_10k", "3. Rust→Kotlin Blittable", 50,
            boltffi = { generateLocations(10000) },
            uniffi = { uniffi.demo.generateLocations(10000) }),
        bench("generate_trades_1k", "3. Rust→Kotlin Blittable", 500,
            boltffi = { generateTrades(1000) },
            uniffi = { uniffi.demo.generateTrades(1000) }),
        bench("generate_trades_10k", "3. Rust→Kotlin Blittable", 50,
            boltffi = { generateTrades(10000) },
            uniffi = { uniffi.demo.generateTrades(10000) }),
        bench("generate_particles_1k", "3. Rust→Kotlin Blittable", 500,
            boltffi = { generateParticles(1000) },
            uniffi = { uniffi.demo.generateParticles(1000) }),
        bench("generate_particles_10k", "3. Rust→Kotlin Blittable", 50,
            boltffi = { generateParticles(10000) },
            uniffi = { uniffi.demo.generateParticles(10000) }),
        bench("generate_sensor_readings_1k", "3. Rust→Kotlin Blittable", 500,
            boltffi = { generateSensorReadings(1000) },
            uniffi = { uniffi.demo.generateSensorReadings(1000) }),
        bench("generate_sensor_readings_10k", "3. Rust→Kotlin Blittable", 50,
            boltffi = { generateSensorReadings(10000) },
            uniffi = { uniffi.demo.generateSensorReadings(10000) }),
        bench("generate_i32_vec_10k", "3. Rust→Kotlin Blittable", 500,
            boltffi = { generateI32Vec(10000) },
            uniffi = { uniffi.demo.generateI32Vec(10000) }),
        bench("generate_i32_vec_100k", "3. Rust→Kotlin Blittable", 50,
            boltffi = { generateI32Vec(100_000) },
            uniffi = { uniffi.demo.generateI32Vec(100_000) }),
        bench("generate_f64_vec_10k", "3. Rust→Kotlin Blittable", 500,
            boltffi = { generateF64Vec(10000) },
            uniffi = { uniffi.demo.generateF64Vec(10000) }),
        bench("generate_bytes_64k", "3. Rust→Kotlin Blittable", 200,
            boltffi = { generateBytes(65536) },
            uniffi = { uniffi.demo.generateBytes(65536) }),
        bench("generate_directions_1k", "3. Rust→Kotlin Blittable", 500,
            boltffi = { generateDirections(1000) },
            uniffi = { uniffi.demo.generateDirections(1000) }),
        bench("generate_directions_10k", "3. Rust→Kotlin Blittable", 50,
            boltffi = { generateDirections(10000) },
            uniffi = { uniffi.demo.generateDirections(10000) }),

        bench("process_locations_1k", "4. Kotlin→Rust Blittable", 1000,
            boltffi = { processLocations(boltffiLocations1k) },
            uniffi = { uniffi.demo.processLocations(uniffiLocations1k) }),
        bench("process_locations_10k", "4. Kotlin→Rust Blittable", 100,
            boltffi = { processLocations(boltffiLocations10k) },
            uniffi = { uniffi.demo.processLocations(uniffiLocations10k) }),
        bench("sum_ratings_1k", "4. Kotlin→Rust Blittable", 1000,
            boltffi = { sumRatings(boltffiLocations1k) },
            uniffi = { uniffi.demo.sumRatings(uniffiLocations1k) }),
        bench("sum_ratings_10k", "4. Kotlin→Rust Blittable", 100,
            boltffi = { sumRatings(boltffiLocations10k) },
            uniffi = { uniffi.demo.sumRatings(uniffiLocations10k) }),
        bench("sum_trade_volumes_1k", "4. Kotlin→Rust Blittable", 1000,
            boltffi = { sumTradeVolumes(boltffiTrades1k) },
            uniffi = { uniffi.demo.sumTradeVolumes(uniffiTrades1k) }),
        bench("sum_trade_volumes_10k", "4. Kotlin→Rust Blittable", 100,
            boltffi = { sumTradeVolumes(boltffiTrades10k) },
            uniffi = { uniffi.demo.sumTradeVolumes(uniffiTrades10k) }),
        bench("sum_particle_masses_1k", "4. Kotlin→Rust Blittable", 1000,
            boltffi = { sumParticleMasses(boltffiParticles1k) },
            uniffi = { uniffi.demo.sumParticleMasses(uniffiParticles1k) }),
        bench("sum_particle_masses_10k", "4. Kotlin→Rust Blittable", 100,
            boltffi = { sumParticleMasses(boltffiParticles10k) },
            uniffi = { uniffi.demo.sumParticleMasses(uniffiParticles10k) }),
        bench("avg_sensor_temp_1k", "4. Kotlin→Rust Blittable", 1000,
            boltffi = { avgSensorTemperature(boltffiSensors1k) },
            uniffi = { uniffi.demo.avgSensorTemperature(uniffiSensors1k) }),
        bench("avg_sensor_temp_10k", "4. Kotlin→Rust Blittable", 100,
            boltffi = { avgSensorTemperature(boltffiSensors10k) },
            uniffi = { uniffi.demo.avgSensorTemperature(uniffiSensors10k) }),
        bench("sum_i32_vec_10k", "4. Kotlin→Rust Blittable", 500,
            boltffi = { sumI32Vec(boltffiI32Vec10k) },
            uniffi = { uniffi.demo.sumI32Vec(uniffiI32Vec10k) }),
        bench("sum_i32_vec_100k", "4. Kotlin→Rust Blittable", 50,
            boltffi = { sumI32Vec(boltffiI32Vec100k) },
            uniffi = { uniffi.demo.sumI32Vec(uniffiI32Vec100k) }),
        bench("sum_f64_vec_10k", "4. Kotlin→Rust Blittable", 500,
            boltffi = { sumF64Vec(boltffiF64Vec10k) },
            uniffi = { uniffi.demo.sumF64Vec(uniffiF64Vec10k) }),
        bench("count_north_1k", "4. Kotlin→Rust Blittable", 1000,
            boltffi = { countNorth(boltffiDirections1k) },
            uniffi = { uniffi.demo.countNorth(uniffiDirections1k) }),
        bench("count_north_10k", "4. Kotlin→Rust Blittable", 100,
            boltffi = { countNorth(boltffiDirections10k) },
            uniffi = { uniffi.demo.countNorth(uniffiDirections10k) }),

        bench("generate_user_profiles_100", "5. Rust→Kotlin Complex", 200,
            boltffi = { generateUserProfiles(100) },
            uniffi = { uniffi.demo.generateUserProfiles(100) }),
        bench("generate_user_profiles_1k", "5. Rust→Kotlin Complex", 20,
            boltffi = { generateUserProfiles(1000) },
            uniffi = { uniffi.demo.generateUserProfiles(1000) }),

        bench("sum_user_scores_100", "6. Kotlin→Rust Complex", 500,
            boltffi = { sumUserScores(boltffiUsers100) },
            uniffi = { uniffi.demo.sumUserScores(uniffiUsers100) }),
        bench("sum_user_scores_1k", "6. Kotlin→Rust Complex", 50,
            boltffi = { sumUserScores(boltffiUsers1k) },
            uniffi = { uniffi.demo.sumUserScores(uniffiUsers1k) }),
        bench("count_active_users_100", "6. Kotlin→Rust Complex", 500,
            boltffi = { countActiveUsers(boltffiUsers100) },
            uniffi = { uniffi.demo.countActiveUsers(uniffiUsers100) }),
        bench("count_active_users_1k", "6. Kotlin→Rust Complex", 50,
            boltffi = { countActiveUsers(boltffiUsers1k) },
            uniffi = { uniffi.demo.countActiveUsers(uniffiUsers1k) }),
        bench("async_add", "6a. Async", 1000,
            boltffi = { runBlocking { asyncAdd(100, 200) } },
            uniffi = { runBlocking { uniffi.demo.asyncAdd(100, 200) } }),
        bench("callback_100", "6b. Callbacks", 500,
            boltffi = {
                DataConsumer().use { consumer ->
                    consumer.setProvider(boltffiProvider100)
                    consumer.computeSum()
                }
            },
            uniffi = {
                uniffi.demo.DataConsumer().use { consumer ->
                    consumer.setProvider(uniffiProvider100)
                    consumer.computeSum()
                }
            }),
        bench("callback_1k", "6b. Callbacks", 100,
            boltffi = {
                DataConsumer().use { consumer ->
                    consumer.setProvider(boltffiProvider1k)
                    consumer.computeSum()
                }
            },
            uniffi = {
                uniffi.demo.DataConsumer().use { consumer ->
                    consumer.setProvider(uniffiProvider1k)
                    consumer.computeSum()
                }
            }),

        bench("counter_increment_mutex", "7. Classes", 100,
            boltffi = {
                Counter(0).use { c -> repeat(1000) { c.increment() } }
            },
            uniffi = {
                uniffi.demo.Counter(0).use { c -> repeat(1000) { c.increment() } }
            }),
        benchBoltffiOnly("counter_increment_single_threaded", "7. Classes (BoltFFI-only)", 100) {
            CounterSingleThreaded().use { c -> repeat(1000) { c.increment() } }
        },
        bench("datastore_add_record_1k", "7. Classes", 50,
            boltffi = {
                DataStore().use { s ->
                    repeat(1000) { i ->
                        s.add(DataPoint(i.toDouble(), i.toDouble() * 2.0, i.toLong()))
                    }
                }
            },
            uniffi = {
                uniffi.demo.DataStore().use { s ->
                    repeat(1000) { i ->
                        s.add(uniffi.demo.DataPoint(i.toDouble(), i.toDouble() * 2.0, i.toLong()))
                    }
                }
            }),
        bench("accumulator_mutex", "7. Classes", 100,
            boltffi = {
                Accumulator().use { a ->
                    repeat(1000) { i -> a.add(i.toLong()) }
                    a.get()
                    a.reset()
                }
            },
            uniffi = {
                uniffi.demo.Accumulator().use { a ->
                    repeat(1000) { i -> a.add(i.toLong()) }
                    a.get()
                    a.reset()
                }
            }),
        benchBoltffiOnly("accumulator_single_threaded", "7. Classes (BoltFFI-only)", 100) {
            AccumulatorSingleThreaded().use { a ->
                repeat(1000) { i -> a.add(i.toLong()) }
                a.get()
                a.reset()
            }
        },

        bench("simple_enum", "8. Enums", 5000,
            boltffi = {
                oppositeDirection(Direction.NORTH)
                directionToDegrees(Direction.EAST)
            },
            uniffi = {
                uniffi.demo.oppositeDirection(uniffi.demo.Direction.NORTH)
                uniffi.demo.directionToDegrees(uniffi.demo.Direction.EAST)
            }),
        bench("echo_direction", "8. Enums", 5000,
            boltffi = { echoDirection(Direction.NORTH) },
            uniffi = { uniffi.demo.echoDirection(uniffi.demo.Direction.NORTH) }),
        bench("find_direction", "8. Enums", 5000,
            boltffi = { findDirection(0) },
            uniffi = { uniffi.demo.findDirection(0) }),
        bench("data_enum_input", "8. Enums", 5000,
            boltffi = {
                getStatusProgress(TaskStatus.InProgress(50))
                isStatusComplete(TaskStatus.Completed(100))
            },
            uniffi = {
                uniffi.demo.getStatusProgress(uniffi.demo.TaskStatus.InProgress(50))
                uniffi.demo.isStatusComplete(uniffi.demo.TaskStatus.Completed(100))
            }),

        bench("find_even_100", "9. Options", 1000,
            boltffi = { repeat(100) { i -> findEven(i) } },
            uniffi = { repeat(100) { i -> uniffi.demo.findEven(i) } }),
        bench("find_positive_f64", "9. Options", 5000,
            boltffi = { findPositiveF64(3.14) },
            uniffi = { uniffi.demo.findPositiveF64(3.14) }),
        bench("find_name", "9. Options", 5000,
            boltffi = { findName(1) },
            uniffi = { uniffi.demo.findName(1) }),
        bench("find_names_100", "9. Options", 500,
            boltffi = { findNames(100) },
            uniffi = { uniffi.demo.findNames(100) }),
        bench("find_numbers_100", "9. Options", 500,
            boltffi = { findNumbers(100) },
            uniffi = { uniffi.demo.findNumbers(100) }),
        bench("find_locations_100", "9. Options", 500,
            boltffi = { findLocations(100) },
            uniffi = { uniffi.demo.findLocations(100) }),
    )

    return benchmarks.mapIndexed { index, b ->
        onProgress(b.name, index.toFloat() / benchmarks.size)
        b.run()
    }
}

fun runPhaseIsolation(): String {
    return "Phase isolation temporarily disabled (jbyteArray migration)"
}

private class BoltffiDataProvider(count: Int) : DataProvider {
    private val points = List(count) { index ->
        DataPoint(index.toDouble(), index.toDouble() * 2.0, index.toLong())
    }

    override fun getCount(): UInt = points.size.toUInt()

    override fun getItem(index: UInt): DataPoint = points[index.toInt()]
}

private class UniffiDataProvider(count: Int) : uniffi.demo.DataProvider {
    private val points = List(count) { index ->
        uniffi.demo.DataPoint(index.toDouble(), index.toDouble() * 2.0, index.toLong())
    }

    override fun getCount(): UInt = points.size.toUInt()

    override fun getItem(index: UInt): uniffi.demo.DataPoint = points[index.toInt()]
}

private class BenchSpec(
    val name: String,
    val category: String,
    val iterations: Int,
    val boltffi: () -> Unit,
    val uniffi: () -> Unit,
) {
    fun run(): BenchmarkResult {
        repeat(10) { boltffi(); uniffi() }
        val boltffiNs = measureAvgNs(iterations, boltffi)
        val uniffiNs = measureAvgNs(iterations, uniffi)
        return BenchmarkResult(name, category, boltffiNs, uniffiNs)
    }
}

private fun bench(
    name: String,
    category: String,
    iterations: Int,
    boltffi: () -> Unit,
    uniffi: () -> Unit,
) = BenchSpec(name, category, iterations, boltffi, uniffi)

private fun benchBoltffiOnly(
    name: String,
    category: String,
    iterations: Int,
    boltffi: () -> Unit,
) = BenchSpec(name, category, iterations, boltffi, boltffi)

private inline fun measureAvgNs(iterations: Int, block: () -> Unit): Long {
    val start = System.nanoTime()
    repeat(iterations) { block() }
    val elapsed = System.nanoTime() - start
    return elapsed / iterations.toLong()
}
