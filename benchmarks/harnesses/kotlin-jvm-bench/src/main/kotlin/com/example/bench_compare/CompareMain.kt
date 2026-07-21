package com.example.bench_compare

import com.example.bench_boltffi.*
import kotlin.math.absoluteValue
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val boltffiLocations1k = generateLocations(1000)
    val boltffiLocations10k = generateLocations(10000)
    val boltffiTrades1k = generateTrades(1000)
    val boltffiTrades10k = generateTrades(10000)
    val boltffiParticles1k = generateParticles(1000)
    val boltffiParticles10k = generateParticles(10000)
    val boltffiSensors1k = generateSensorReadings(1000)
    val boltffiSensors10k = generateSensorReadings(10000)
    val boltffiDirections1k = generateDirections(1000)
    val boltffiDirections10k = generateDirections(10000)
    val boltffiI32Vec10k = generateI32Vec(10000)
    val boltffiI32Vec100k = generateI32Vec(100_000)
    val boltffiF64Vec10k = generateF64Vec(10000)
    val echoBytes64k = ByteArray(65_536) { 42 }
    val echoVecI32Values10k = IntArray(10_000) { it }

    val uniffiLocations1k = uniffi.demo.generateLocations(1000)
    val uniffiLocations10k = uniffi.demo.generateLocations(10000)
    val uniffiTrades1k = uniffi.demo.generateTrades(1000)
    val uniffiTrades10k = uniffi.demo.generateTrades(10000)
    val uniffiParticles1k = uniffi.demo.generateParticles(1000)
    val uniffiParticles10k = uniffi.demo.generateParticles(10000)
    val uniffiSensors1k = uniffi.demo.generateSensorReadings(1000)
    val uniffiSensors10k = uniffi.demo.generateSensorReadings(10000)
    val uniffiDirections1k = uniffi.demo.generateDirections(1000)
    val uniffiDirections10k = uniffi.demo.generateDirections(10000)
    val uniffiI32Vec10k = uniffi.demo.generateI32Vec(10000)
    val uniffiI32Vec100k = uniffi.demo.generateI32Vec(100_000)
    val uniffiF64Vec10k = uniffi.demo.generateF64Vec(10000)

    val boltffiUsers100 = generateUserProfiles(100)
    val boltffiUsers1k = generateUserProfiles(1000)
    val uniffiUsers100 = uniffi.demo.generateUserProfiles(100)
    val uniffiUsers1k = uniffi.demo.generateUserProfiles(1000)
    val boltffiProvider100 = object : DataProvider {
        private val points = List(100) { index ->
            DataPoint(index.toDouble(), index.toDouble() * 2.0, index.toLong())
        }

        override fun getCount(): UInt = points.size.toUInt()

        override fun getItem(index: UInt): DataPoint = points[index.toInt()]
    }
    val boltffiProvider1k = object : DataProvider {
        private val points = List(1000) { index ->
            DataPoint(index.toDouble(), index.toDouble() * 2.0, index.toLong())
        }

        override fun getCount(): UInt = points.size.toUInt()

        override fun getItem(index: UInt): DataPoint = points[index.toInt()]
    }
    val uniffiProvider100 = object : uniffi.demo.DataProvider {
        private val points = List(100) { index ->
            uniffi.demo.DataPoint(index.toDouble(), index.toDouble() * 2.0, index.toLong())
        }

        override fun getCount(): UInt = points.size.toUInt()

        override fun getItem(index: UInt): uniffi.demo.DataPoint = points[index.toInt()]
    }
    val uniffiProvider1k = object : uniffi.demo.DataProvider {
        private val points = List(1000) { index ->
            uniffi.demo.DataPoint(index.toDouble(), index.toDouble() * 2.0, index.toLong())
        }

        override fun getCount(): UInt = points.size.toUInt()

        override fun getItem(index: UInt): uniffi.demo.DataPoint = points[index.toInt()]
    }

    runBoltFFIParityTests()

    val benchmarkSuite = listOf(
        pairedBenchmark("noop", boltffi = { noop() }, uniffi = { uniffi.demo.noop() }),
        pairedBenchmark("echo_bool", boltffi = { echoBool(true) }, uniffi = { uniffi.demo.echoBool(true) }),
        pairedBenchmark("negate_bool", boltffi = { negateBool(true) }, uniffi = { uniffi.demo.negateBool(true) }),
        pairedBenchmark("echo_i32", boltffi = { echoI32(42) }, uniffi = { uniffi.demo.echoI32(42) }),
        pairedBenchmark("echo_f64", boltffi = { echoF64(3.14159) }, uniffi = { uniffi.demo.echoF64(3.14159) }),
        pairedBenchmark("add_f64", boltffi = { addF64(1.25, 2.5) }, uniffi = { uniffi.demo.addF64(1.25, 2.5) }),
        pairedBenchmark("add", boltffi = { add(100, 200) }, uniffi = { uniffi.demo.add(100, 200) }),
        pairedBenchmark("multiply", boltffi = { multiply(2.5, 4.0) }, uniffi = { uniffi.demo.multiply(2.5, 4.0) }),
        pairedBenchmark(
            "inc_u64",
            boltffi = {
                val value = longArrayOf(0L)
                incU64(value)
                check(value[0] == 1L)
            },
            uniffi = {
                val value = 0uL
                check(uniffi.demo.incU64Value(value) == 1uL)
            },
        ),
        pairedBenchmark(
            "inc_u64_value",
            boltffi = {
                check(incU64Value(0uL) == 1uL)
            },
            uniffi = {
                val value = 0uL
                check(uniffi.demo.incU64Value(value) == 1uL)
            },
        ),
        pairedBenchmark("echo_string_small", boltffi = { echoString("hello") }, uniffi = { uniffi.demo.echoString("hello") }),
        pairedBenchmark(
            "echo_string_1k",
            boltffi = { echoString("x".repeat(1000)) },
            uniffi = { uniffi.demo.echoString("x".repeat(1000)) },
        ),
        pairedBenchmark("generate_string_1k", boltffi = { generateString(1000) }, uniffi = { uniffi.demo.generateString(1000) }),
        pairedBenchmark("echo_bytes_64k", boltffi = { echoBytes(echoBytes64k) }, uniffi = { uniffi.demo.echoBytes(echoBytes64k) }),
        pairedBenchmark(
            "echo_vec_i32_10k",
            boltffi = { echoVecI32(echoVecI32Values10k) },
            uniffi = { uniffi.demo.echoVecI32(echoVecI32Values10k.toList()) },
        ),
        pairedBenchmark("generate_locations_1k", boltffi = { generateLocations(1000) }, uniffi = { uniffi.demo.generateLocations(1000) }),
        pairedBenchmark("generate_locations_10k", boltffi = { generateLocations(10000) }, uniffi = { uniffi.demo.generateLocations(10000) }),
        pairedBenchmark("generate_trades_1k", boltffi = { generateTrades(1000) }, uniffi = { uniffi.demo.generateTrades(1000) }),
        pairedBenchmark("generate_trades_10k", boltffi = { generateTrades(10000) }, uniffi = { uniffi.demo.generateTrades(10000) }),
        pairedBenchmark("generate_particles_1k", boltffi = { generateParticles(1000) }, uniffi = { uniffi.demo.generateParticles(1000) }),
        pairedBenchmark("generate_particles_10k", boltffi = { generateParticles(10000) }, uniffi = { uniffi.demo.generateParticles(10000) }),
        pairedBenchmark("generate_sensor_readings_1k", boltffi = { generateSensorReadings(1000) }, uniffi = { uniffi.demo.generateSensorReadings(1000) }),
        pairedBenchmark("generate_sensor_readings_10k", boltffi = { generateSensorReadings(10000) }, uniffi = { uniffi.demo.generateSensorReadings(10000) }),
        pairedBenchmark("generate_i32_vec_10k", boltffi = { generateI32Vec(10000) }, uniffi = { uniffi.demo.generateI32Vec(10000) }),
        pairedBenchmark("generate_i32_vec_100k", boltffi = { generateI32Vec(100_000) }, uniffi = { uniffi.demo.generateI32Vec(100_000) }),
        pairedBenchmark("generate_f64_vec_10k", boltffi = { generateF64Vec(10000) }, uniffi = { uniffi.demo.generateF64Vec(10000) }),
        pairedBenchmark("generate_bytes_64k", boltffi = { generateBytes(65536) }, uniffi = { uniffi.demo.generateBytes(65536) }),
        pairedBenchmark("sum_ratings_1k", boltffi = { sumRatings(boltffiLocations1k) }, uniffi = { uniffi.demo.sumRatings(uniffiLocations1k) }),
        pairedBenchmark("sum_ratings_10k", boltffi = { sumRatings(boltffiLocations10k) }, uniffi = { uniffi.demo.sumRatings(uniffiLocations10k) }),
        pairedBenchmark("sum_trade_volumes_1k", boltffi = { sumTradeVolumes(boltffiTrades1k) }, uniffi = { uniffi.demo.sumTradeVolumes(uniffiTrades1k) }),
        pairedBenchmark("sum_trade_volumes_10k", boltffi = { sumTradeVolumes(boltffiTrades10k) }, uniffi = { uniffi.demo.sumTradeVolumes(uniffiTrades10k) }),
        pairedBenchmark("sum_particle_masses_1k", boltffi = { sumParticleMasses(boltffiParticles1k) }, uniffi = { uniffi.demo.sumParticleMasses(uniffiParticles1k) }),
        pairedBenchmark("sum_particle_masses_10k", boltffi = { sumParticleMasses(boltffiParticles10k) }, uniffi = { uniffi.demo.sumParticleMasses(uniffiParticles10k) }),
        pairedBenchmark("avg_sensor_temp_1k", boltffi = { avgSensorTemperature(boltffiSensors1k) }, uniffi = { uniffi.demo.avgSensorTemperature(uniffiSensors1k) }),
        pairedBenchmark("avg_sensor_temp_10k", boltffi = { avgSensorTemperature(boltffiSensors10k) }, uniffi = { uniffi.demo.avgSensorTemperature(uniffiSensors10k) }),
        pairedBenchmark("sum_i32_vec_10k", boltffi = { sumI32Vec(boltffiI32Vec10k) }, uniffi = { uniffi.demo.sumI32Vec(uniffiI32Vec10k) }),
        pairedBenchmark("sum_i32_vec_100k", boltffi = { sumI32Vec(boltffiI32Vec100k) }, uniffi = { uniffi.demo.sumI32Vec(uniffiI32Vec100k) }),
        pairedBenchmark("sum_f64_vec_10k", boltffi = { sumF64Vec(boltffiF64Vec10k) }, uniffi = { uniffi.demo.sumF64Vec(uniffiF64Vec10k) }),
        pairedBenchmark("process_locations_1k", boltffi = { processLocations(boltffiLocations1k) }, uniffi = { uniffi.demo.processLocations(uniffiLocations1k) }),
        pairedBenchmark("process_locations_10k", boltffi = { processLocations(boltffiLocations10k) }, uniffi = { uniffi.demo.processLocations(uniffiLocations10k) }),
        pairedBenchmark(
            "counter_increment_mutex",
            boltffi = {
                Counter(0).use { counter ->
                    repeat(1000) { counter.increment() }
                    check(counter.get() == 1000)
                }
            },
            uniffi = {
                uniffi.demo.Counter(0).use { counter ->
                    repeat(1000) { counter.increment() }
                    check(counter.get() == 1000)
                }
            },
        ),
        singleBenchmark(
            "counter_increment_single_threaded",
            boltffi = {
                CounterSingleThreaded().use { counter ->
                    repeat(1000) { counter.increment() }
                    check(counter.get() == 1000)
                }
            },
        ),
        pairedBenchmark(
            "datastore_add_record_1k",
            boltffi = {
                DataStore().use { store ->
                    repeat(1000) { index ->
                        store.add(DataPoint(index.toDouble(), index.toDouble() * 2.0, index.toLong()))
                    }
                    check(store.len() == 1000uL)
                }
            },
            uniffi = {
                uniffi.demo.DataStore().use { store ->
                    repeat(1000) { index ->
                        store.add(uniffi.demo.DataPoint(index.toDouble(), index.toDouble() * 2.0, index.toLong()))
                    }
                    check(store.len() == 1000uL)
                }
            },
        ),
        pairedBenchmark(
            "accumulator_mutex",
            boltffi = {
                Accumulator().use { accumulator ->
                    repeat(1000) { index -> accumulator.add(index.toLong()) }
                    accumulator.get()
                    accumulator.reset()
                }
            },
            uniffi = {
                uniffi.demo.Accumulator().use { accumulator ->
                    repeat(1000) { index -> accumulator.add(index.toLong()) }
                    accumulator.get()
                    accumulator.reset()
                }
            },
        ),
        singleBenchmark(
            "accumulator_single_threaded",
            boltffi = {
                AccumulatorSingleThreaded().use { accumulator ->
                    repeat(1000) { index -> accumulator.add(index.toLong()) }
                    accumulator.get()
                    accumulator.reset()
                }
            },
        ),
        pairedBenchmark(
            "simple_enum",
            boltffi = {
                oppositeDirection(Direction.NORTH)
                directionToDegrees(Direction.EAST)
            },
            uniffi = {
                uniffi.demo.oppositeDirection(uniffi.demo.Direction.NORTH)
                uniffi.demo.directionToDegrees(uniffi.demo.Direction.EAST)
            },
        ),
        pairedBenchmark("echo_direction", boltffi = { echoDirection(Direction.NORTH) }, uniffi = { uniffi.demo.echoDirection(uniffi.demo.Direction.NORTH) }),
        pairedBenchmark("find_direction", boltffi = { findDirection(0) }, uniffi = { uniffi.demo.findDirection(0) }),
        pairedBenchmark("generate_directions_1k", boltffi = { generateDirections(1000) }, uniffi = { uniffi.demo.generateDirections(1000) }),
        pairedBenchmark("generate_directions_10k", boltffi = { generateDirections(10000) }, uniffi = { uniffi.demo.generateDirections(10000) }),
        pairedBenchmark("count_north_1k", boltffi = { countNorth(boltffiDirections1k) }, uniffi = { uniffi.demo.countNorth(uniffiDirections1k) }),
        pairedBenchmark("count_north_10k", boltffi = { countNorth(boltffiDirections10k) }, uniffi = { uniffi.demo.countNorth(uniffiDirections10k) }),
        pairedBenchmark("find_name", boltffi = { findName(1) }, uniffi = { uniffi.demo.findName(1) }),
        pairedBenchmark("find_names_100", boltffi = { findNames(100) }, uniffi = { uniffi.demo.findNames(100) }),
        pairedBenchmark("find_numbers_100", boltffi = { findNumbers(100) }, uniffi = { uniffi.demo.findNumbers(100) }),
        pairedBenchmark("find_locations_100", boltffi = { findLocations(100) }, uniffi = { uniffi.demo.findLocations(100) }),
        pairedBenchmark("find_positive_f64", boltffi = { findPositiveF64(3.14) }, uniffi = { uniffi.demo.findPositiveF64(3.14) }),
        pairedBenchmark(
            "data_enum_input",
            boltffi = {
                getStatusProgress(TaskStatus.InProgress(50))
                isStatusComplete(TaskStatus.Completed(100))
            },
            uniffi = {
                uniffi.demo.getStatusProgress(uniffi.demo.TaskStatus.InProgress(50))
                uniffi.demo.isStatusComplete(uniffi.demo.TaskStatus.Completed(100))
            },
        ),
        pairedBenchmark(
            "find_even_100",
            boltffi = { repeat(100) { index -> findEven(index) } },
            uniffi = { repeat(100) { index -> uniffi.demo.findEven(index) } },
        ),
        pairedBenchmark(
            "async_add",
            boltffi = { runBlocking { asyncAdd(100, 200) } },
            uniffi = { runBlocking { uniffi.demo.asyncAdd(100, 200) } },
        ),
        pairedBenchmark(
            "callback_100",
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
            },
        ),
        pairedBenchmark(
            "callback_1k",
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
            },
        ),
        pairedBenchmark("generate_user_profiles_100", boltffi = { generateUserProfiles(100) }, uniffi = { uniffi.demo.generateUserProfiles(100) }),
        pairedBenchmark("generate_user_profiles_1k", boltffi = { generateUserProfiles(1000) }, uniffi = { uniffi.demo.generateUserProfiles(1000) }),
        pairedBenchmark("sum_user_scores_100", boltffi = { sumUserScores(boltffiUsers100) }, uniffi = { uniffi.demo.sumUserScores(uniffiUsers100) }),
        pairedBenchmark("sum_user_scores_1k", boltffi = { sumUserScores(boltffiUsers1k) }, uniffi = { uniffi.demo.sumUserScores(uniffiUsers1k) }),
        pairedBenchmark("count_active_users_100", boltffi = { countActiveUsers(boltffiUsers100) }, uniffi = { uniffi.demo.countActiveUsers(uniffiUsers100) }),
        pairedBenchmark("count_active_users_1k", boltffi = { countActiveUsers(boltffiUsers1k) }, uniffi = { uniffi.demo.countActiveUsers(uniffiUsers1k) }),
    )

    println("--- Benchmarks (BoltFFI vs UniFFI) ---")
    benchmarkSuite.forEach { it.runAndPrint() }
}

private suspend fun runBoltFFIParityTests() {
    val even = findEven(4)
    check(even == 4)
    val notEven = findEven(3)
    check(notEven == null)

    val posI64 = findPositiveI64(100L)
    check(posI64 == 100L)
    val negI64 = findPositiveI64(-5L)
    check(negI64 == null)

    val posF64 = findPositiveF64(3.14)
    check(posF64 != null && (posF64 - 3.14).absoluteValue < 0.000001)
    val negF64 = findPositiveF64(-1.0)
    check(negF64 == null)

    val name = findName(1)
    check(name == "Name_1")
    val noName = findName(-1)
    check(noName == null)

    val loc = findLocation(1)
    check(loc != null && (loc.lat - 37.7749).absoluteValue < 0.000001)
    val noLoc = findLocation(-1)
    check(noLoc == null)

    val nums = findNumbers(3)
    check(nums != null && nums.size == 3)
    val noNums = findNumbers(-1)
    check(noNums == null)

    val locs = findLocations(2)
    check(locs != null && locs.size == 2)
    val noLocs = findLocations(-1)
    check(noLocs == null)

    val dir = findDirection(0)
    check(dir == Direction.NORTH)
    val noDir = findDirection(-1)
    check(noDir == null)

    val apiRes = findApiResult(0)
    check(apiRes != null)
    val noApiRes = findApiResult(-1)
    check(noApiRes == null)

    println("Option tests passed")

    val pendingProgress = getStatusProgress(TaskStatus.Pending)
    check(pendingProgress == 0)
    val inProgressProgress = getStatusProgress(TaskStatus.InProgress(75))
    check(inProgressProgress == 75)
    val completedProgress = getStatusProgress(TaskStatus.Completed(100))
    check(completedProgress == 100)
    val failedProgress = getStatusProgress(TaskStatus.Failed(-5, 3))
    check(failedProgress == -5)

    val isPendingComplete = isStatusComplete(TaskStatus.Pending)
    check(!isPendingComplete)
    val isCompletedComplete = isStatusComplete(TaskStatus.Completed(42))
    check(isCompletedComplete)

    println("Data enum tests passed")

    val asyncOk = tryComputeAsync(5)
    check(asyncOk == 10)

    runCatching { tryComputeAsync(-1) }.onSuccess { error("tryComputeAsync(-1) should throw") }
    val fetched = fetchData(5)
    check(fetched == 50)
    runCatching { fetchData(-1) }.onSuccess { error("fetchData(-1) should throw") }

    val asyncNums = asyncGetNumbers(5)
    check(asyncNums.contentEquals(intArrayOf(0, 1, 2, 3, 4)))

    println("Async helper tests passed")
    println("Async Result tests passed")

    val successPoint = DataPoint(1.5, 2.5, 999L)
    val successResponse = createSuccessResponse(42L, successPoint)
    check(successResponse.requestId == 42L)
    check(isResponseSuccess(successResponse))

    check(successResponse.result.isSuccess)
    val successResult = successResponse.result.getOrThrow()
    check(successResult.x == 1.5)
    check(successResult.y == 2.5)
    check(successResult.timestamp == 999L)

    val successValue = getResponseValue(successResponse)
    check(successValue != null && successValue.x == 1.5)

    val errorResponse = createErrorResponse(100L, ComputeError.InvalidInput(-999))
    check(errorResponse.requestId == 100L)
    check(!isResponseSuccess(errorResponse))

    check(errorResponse.result.isFailure)
    val error = errorResponse.result.exceptionOrNull()
    check(error is ComputeError.InvalidInput && error.value0 == -999)

    val errorValue = getResponseValue(errorResponse)
    check(errorValue == null)

    println("Result field tests passed")
}

private fun pairedBenchmark(
    name: String,
    boltffi: () -> Unit,
    uniffi: () -> Unit,
): PairedBenchmark =
    PairedBenchmark(name, boltffi, uniffi)

private fun singleBenchmark(
    name: String,
    boltffi: () -> Unit,
): PairedBenchmark =
    PairedBenchmark(name, boltffi, boltffi)

private class PairedBenchmark(
    private val name: String,
    private val boltffi: () -> Unit,
    private val uniffi: () -> Unit,
) {
    fun runAndPrint() {
        val boltffiTimeNs = measureAvgNs(boltffi)
        val uniffiTimeNs = measureAvgNs(uniffi)
        val speedup = uniffiTimeNs.toDouble() / boltffiTimeNs.toDouble()
        val (winner, factor) = if (speedup >= 1.0) "boltffi" to speedup else "uniffi" to (1.0 / speedup)
        println("boltffi_$name: ${boltffiTimeNs / 1000}us/op")
        println("uniffi_$name: ${uniffiTimeNs / 1000}us/op")
        println("speedup_$name: ${"%.2f".format(factor)}x ($winner)")
    }

    private fun measureAvgNs(block: () -> Unit): Long {
        repeat(10) { block() }
        val iterations = iterationsFor(name)
        val elapsed = measureNs { repeat(iterations) { block() } }
        return elapsed / iterations.toLong()
    }

    private fun iterationsFor(name: String): Int =
        when (name) {
            "generate_locations_10k" -> 20
            "generate_trades_10k" -> 20
            "generate_particles_10k" -> 20
            "generate_sensor_readings_10k" -> 20
            "generate_i32_vec_100k" -> 10
            "sum_ratings_10k" -> 50
            "sum_trade_volumes_10k" -> 50
            "sum_particle_masses_10k" -> 50
            "avg_sensor_temp_10k" -> 50
            "process_locations_10k" -> 50
            "generate_directions_10k" -> 50
            "count_north_10k" -> 100
            "generate_user_profiles_1k" -> 50
            "sum_user_scores_1k" -> 100
            "count_active_users_1k" -> 100
            "callback_1k" -> 100
            else -> 500
        }
}

private class SingleBenchmark(
    private val name: String,
    private val block: () -> Unit,
) {
    fun runAndPrint() {
        val avgNs = measureAvgNs(block)
        println("boltffi_$name: ${avgNs / 1000}us/op")
    }

    private fun measureAvgNs(block: () -> Unit): Long {
        repeat(10) { block() }
        val iterations = iterationsFor(name)
        val elapsed = measureNs { repeat(iterations) { block() } }
        return elapsed / iterations.toLong()
    }

    private fun iterationsFor(name: String): Int =
        when (name) {
            else -> 500
        }
}

private inline fun measureNs(block: () -> Unit): Long {
    val start = System.nanoTime()
    block()
    return System.nanoTime() - start
}
