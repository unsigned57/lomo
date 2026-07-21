package com.example.bench_compare

import com.example.bench_boltffi.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import uniffi.demo.Direction as UniffiDirection

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
open class BoltFFIVsUniffiBench {
    private lateinit var boltffiLocations1k: List<Location>
    private lateinit var boltffiLocations10k: List<Location>
    private lateinit var boltffiTrades1k: List<Trade>
    private lateinit var boltffiTrades10k: List<Trade>
    private lateinit var boltffiParticles1k: List<Particle>
    private lateinit var boltffiParticles10k: List<Particle>
    private lateinit var boltffiSensors1k: List<SensorReading>
    private lateinit var boltffiSensors10k: List<SensorReading>
    private lateinit var boltffiDirections1k: List<Direction>
    private lateinit var boltffiDirections10k: List<Direction>
    private lateinit var boltffiI32Vec10k: IntArray
    private lateinit var boltffiI32Vec100k: IntArray
    private lateinit var boltffiF64Vec10k: DoubleArray
    private lateinit var echoBytes64k: ByteArray
    private lateinit var echoVecI32Values10k: IntArray
    private lateinit var boltffiUsers100: List<BenchmarkUserProfile>
    private lateinit var boltffiUsers1k: List<BenchmarkUserProfile>
    private lateinit var boltffiProvider100: DataProvider
    private lateinit var boltffiProvider1k: DataProvider

    private lateinit var uniffiLocations1k: List<uniffi.demo.Location>
    private lateinit var uniffiLocations10k: List<uniffi.demo.Location>
    private lateinit var uniffiTrades1k: List<uniffi.demo.Trade>
    private lateinit var uniffiTrades10k: List<uniffi.demo.Trade>
    private lateinit var uniffiParticles1k: List<uniffi.demo.Particle>
    private lateinit var uniffiParticles10k: List<uniffi.demo.Particle>
    private lateinit var uniffiSensors1k: List<uniffi.demo.SensorReading>
    private lateinit var uniffiSensors10k: List<uniffi.demo.SensorReading>
    private lateinit var uniffiDirections1k: List<uniffi.demo.Direction>
    private lateinit var uniffiDirections10k: List<uniffi.demo.Direction>
    private lateinit var uniffiI32Vec10k: List<Int>
    private lateinit var uniffiI32Vec100k: List<Int>
    private lateinit var uniffiF64Vec10k: List<Double>
    private lateinit var uniffiEchoVecI32Values10k: List<Int>
    private lateinit var uniffiUsers100: List<uniffi.demo.BenchmarkUserProfile>
    private lateinit var uniffiUsers1k: List<uniffi.demo.BenchmarkUserProfile>
    private lateinit var uniffiProvider100: uniffi.demo.DataProvider
    private lateinit var uniffiProvider1k: uniffi.demo.DataProvider

    @Setup
    open fun setup() {
        boltffiLocations1k = generateLocations(1000)
        boltffiLocations10k = generateLocations(10000)
        boltffiTrades1k = generateTrades(1000)
        boltffiTrades10k = generateTrades(10000)
        boltffiParticles1k = generateParticles(1000)
        boltffiParticles10k = generateParticles(10000)
        boltffiSensors1k = generateSensorReadings(1000)
        boltffiSensors10k = generateSensorReadings(10000)
        boltffiDirections1k = generateDirections(1000)
        boltffiDirections10k = generateDirections(10000)
        boltffiI32Vec10k = generateI32Vec(10000)
        boltffiI32Vec100k = generateI32Vec(100_000)
        boltffiF64Vec10k = generateF64Vec(10000)
        echoBytes64k = ByteArray(65_536) { 42 }
        echoVecI32Values10k = IntArray(10_000) { it }
        boltffiUsers100 = generateUserProfiles(100)
        boltffiUsers1k = generateUserProfiles(1000)
        boltffiProvider100 = BoltffiDataProvider(100)
        boltffiProvider1k = BoltffiDataProvider(1000)

        uniffiLocations1k = uniffi.demo.generateLocations(1000)
        uniffiLocations10k = uniffi.demo.generateLocations(10000)
        uniffiTrades1k = uniffi.demo.generateTrades(1000)
        uniffiTrades10k = uniffi.demo.generateTrades(10000)
        uniffiParticles1k = uniffi.demo.generateParticles(1000)
        uniffiParticles10k = uniffi.demo.generateParticles(10000)
        uniffiSensors1k = uniffi.demo.generateSensorReadings(1000)
        uniffiSensors10k = uniffi.demo.generateSensorReadings(10000)
        uniffiDirections1k = uniffi.demo.generateDirections(1000)
        uniffiDirections10k = uniffi.demo.generateDirections(10000)
        uniffiI32Vec10k = uniffi.demo.generateI32Vec(10000)
        uniffiI32Vec100k = uniffi.demo.generateI32Vec(100_000)
        uniffiF64Vec10k = uniffi.demo.generateF64Vec(10000)
        uniffiEchoVecI32Values10k = echoVecI32Values10k.toList()
        uniffiUsers100 = uniffi.demo.generateUserProfiles(100)
        uniffiUsers1k = uniffi.demo.generateUserProfiles(1000)
        uniffiProvider100 = UniffiDataProvider(100)
        uniffiProvider1k = UniffiDataProvider(1000)
    }

    @Benchmark
    open fun boltffi_noop(blackhole: Blackhole) {
        noop()
        blackhole.consume(0)
    }

    @Benchmark
    open fun uniffi_noop(blackhole: Blackhole) {
        uniffi.demo.noop()
        blackhole.consume(0)
    }

    @Benchmark
    open fun boltffi_echo_i32(blackhole: Blackhole) {
        blackhole.consume(echoI32(42))
    }

    @Benchmark
    open fun uniffi_echo_i32(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.echoI32(42))
    }

    @Benchmark
    open fun boltffi_echo_f64(blackhole: Blackhole) {
        blackhole.consume(echoF64(3.14159))
    }

    @Benchmark
    open fun uniffi_echo_f64(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.echoF64(3.14159))
    }

    @Benchmark
    open fun boltffi_add(blackhole: Blackhole) {
        blackhole.consume(add(100, 200))
    }

    @Benchmark
    open fun uniffi_add(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.add(100, 200))
    }

    @Benchmark
    open fun boltffi_multiply(blackhole: Blackhole) {
        blackhole.consume(multiply(2.5, 4.0))
    }

    @Benchmark
    open fun uniffi_multiply(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.multiply(2.5, 4.0))
    }

    @Benchmark
    open fun boltffi_inc_u64(blackhole: Blackhole) {
        val arr = longArrayOf(0L)
        incU64(arr)
        blackhole.consume(arr[0])
    }

    @Benchmark
    open fun uniffi_inc_u64(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.incU64Value(0uL))
    }

    @Benchmark
    open fun boltffi_inc_u64_value(blackhole: Blackhole) {
        blackhole.consume(incU64Value(0uL))
    }

    @Benchmark
    open fun uniffi_inc_u64_value(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.incU64Value(0uL))
    }

    @Benchmark
    open fun boltffi_echo_string_small(blackhole: Blackhole) {
        blackhole.consume(echoString("hello"))
    }

    @Benchmark
    open fun uniffi_echo_string_small(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.echoString("hello"))
    }

    @Benchmark
    open fun boltffi_echo_string_1k(blackhole: Blackhole) {
        blackhole.consume(echoString("x".repeat(1000)))
    }

    @Benchmark
    open fun uniffi_echo_string_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.echoString("x".repeat(1000)))
    }

    @Benchmark
    open fun boltffi_generate_locations_1k(blackhole: Blackhole) {
        blackhole.consume(generateLocations(1000))
    }

    @Benchmark
    open fun uniffi_generate_locations_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateLocations(1000))
    }

    @Benchmark
    open fun boltffi_generate_locations_10k(blackhole: Blackhole) {
        blackhole.consume(generateLocations(10000))
    }

    @Benchmark
    open fun uniffi_generate_locations_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateLocations(10000))
    }

    @Benchmark
    open fun boltffi_generate_trades_1k(blackhole: Blackhole) {
        blackhole.consume(generateTrades(1000))
    }

    @Benchmark
    open fun uniffi_generate_trades_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateTrades(1000))
    }

    @Benchmark
    open fun boltffi_generate_trades_10k(blackhole: Blackhole) {
        blackhole.consume(generateTrades(10000))
    }

    @Benchmark
    open fun uniffi_generate_trades_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateTrades(10000))
    }

    @Benchmark
    open fun boltffi_generate_particles_1k(blackhole: Blackhole) {
        blackhole.consume(generateParticles(1000))
    }

    @Benchmark
    open fun uniffi_generate_particles_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateParticles(1000))
    }

    @Benchmark
    open fun boltffi_generate_particles_10k(blackhole: Blackhole) {
        blackhole.consume(generateParticles(10000))
    }

    @Benchmark
    open fun uniffi_generate_particles_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateParticles(10000))
    }

    @Benchmark
    open fun boltffi_generate_sensor_readings_1k(blackhole: Blackhole) {
        blackhole.consume(generateSensorReadings(1000))
    }

    @Benchmark
    open fun uniffi_generate_sensor_readings_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateSensorReadings(1000))
    }

    @Benchmark
    open fun boltffi_generate_sensor_readings_10k(blackhole: Blackhole) {
        blackhole.consume(generateSensorReadings(10000))
    }

    @Benchmark
    open fun uniffi_generate_sensor_readings_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateSensorReadings(10000))
    }

    @Benchmark
    open fun boltffi_generate_i32_vec_10k(blackhole: Blackhole) {
        blackhole.consume(generateI32Vec(10000))
    }

    @Benchmark
    open fun uniffi_generate_i32_vec_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateI32Vec(10000))
    }

    @Benchmark
    open fun boltffi_generate_i32_vec_100k(blackhole: Blackhole) {
        blackhole.consume(generateI32Vec(100_000))
    }

    @Benchmark
    open fun uniffi_generate_i32_vec_100k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateI32Vec(100_000))
    }

    @Benchmark
    open fun boltffi_generate_f64_vec_10k(blackhole: Blackhole) {
        blackhole.consume(generateF64Vec(10000))
    }

    @Benchmark
    open fun uniffi_generate_f64_vec_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateF64Vec(10000))
    }

    @Benchmark
    open fun boltffi_generate_bytes_64k(blackhole: Blackhole) {
        blackhole.consume(generateBytes(65536))
    }

    @Benchmark
    open fun uniffi_generate_bytes_64k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateBytes(65536))
    }

    @Benchmark
    open fun boltffi_sum_ratings_1k(blackhole: Blackhole) {
        blackhole.consume(sumRatings(boltffiLocations1k))
    }

    @Benchmark
    open fun uniffi_sum_ratings_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.sumRatings(uniffiLocations1k))
    }

    @Benchmark
    open fun boltffi_sum_ratings_10k(blackhole: Blackhole) {
        blackhole.consume(sumRatings(boltffiLocations10k))
    }

    @Benchmark
    open fun uniffi_sum_ratings_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.sumRatings(uniffiLocations10k))
    }

    @Benchmark
    open fun boltffi_sum_trade_volumes_1k(blackhole: Blackhole) {
        blackhole.consume(sumTradeVolumes(boltffiTrades1k))
    }

    @Benchmark
    open fun uniffi_sum_trade_volumes_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.sumTradeVolumes(uniffiTrades1k))
    }

    @Benchmark
    open fun boltffi_sum_trade_volumes_10k(blackhole: Blackhole) {
        blackhole.consume(sumTradeVolumes(boltffiTrades10k))
    }

    @Benchmark
    open fun uniffi_sum_trade_volumes_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.sumTradeVolumes(uniffiTrades10k))
    }

    @Benchmark
    open fun boltffi_sum_particle_masses_1k(blackhole: Blackhole) {
        blackhole.consume(sumParticleMasses(boltffiParticles1k))
    }

    @Benchmark
    open fun uniffi_sum_particle_masses_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.sumParticleMasses(uniffiParticles1k))
    }

    @Benchmark
    open fun boltffi_sum_particle_masses_10k(blackhole: Blackhole) {
        blackhole.consume(sumParticleMasses(boltffiParticles10k))
    }

    @Benchmark
    open fun uniffi_sum_particle_masses_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.sumParticleMasses(uniffiParticles10k))
    }

    @Benchmark
    open fun boltffi_avg_sensor_temp_1k(blackhole: Blackhole) {
        blackhole.consume(avgSensorTemperature(boltffiSensors1k))
    }

    @Benchmark
    open fun uniffi_avg_sensor_temp_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.avgSensorTemperature(uniffiSensors1k))
    }

    @Benchmark
    open fun boltffi_avg_sensor_temp_10k(blackhole: Blackhole) {
        blackhole.consume(avgSensorTemperature(boltffiSensors10k))
    }

    @Benchmark
    open fun uniffi_avg_sensor_temp_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.avgSensorTemperature(uniffiSensors10k))
    }

    @Benchmark
    open fun boltffi_sum_i32_vec_10k(blackhole: Blackhole) {
        blackhole.consume(sumI32Vec(boltffiI32Vec10k))
    }

    @Benchmark
    open fun uniffi_sum_i32_vec_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.sumI32Vec(uniffiI32Vec10k))
    }

    @Benchmark
    open fun boltffi_sum_i32_vec_100k(blackhole: Blackhole) {
        blackhole.consume(sumI32Vec(boltffiI32Vec100k))
    }

    @Benchmark
    open fun uniffi_sum_i32_vec_100k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.sumI32Vec(uniffiI32Vec100k))
    }

    @Benchmark
    open fun boltffi_sum_f64_vec_10k(blackhole: Blackhole) {
        blackhole.consume(sumF64Vec(boltffiF64Vec10k))
    }

    @Benchmark
    open fun uniffi_sum_f64_vec_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.sumF64Vec(uniffiF64Vec10k))
    }

    @Benchmark
    open fun boltffi_process_locations_1k(blackhole: Blackhole) {
        blackhole.consume(processLocations(boltffiLocations1k))
    }

    @Benchmark
    open fun uniffi_process_locations_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.processLocations(uniffiLocations1k))
    }

    @Benchmark
    open fun boltffi_process_locations_10k(blackhole: Blackhole) {
        blackhole.consume(processLocations(boltffiLocations10k))
    }

    @Benchmark
    open fun uniffi_process_locations_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.processLocations(uniffiLocations10k))
    }

    @Benchmark
    open fun boltffi_counter_increment_mutex(blackhole: Blackhole) {
        Counter(0).use { counter ->
            repeat(1000) { counter.increment() }
            blackhole.consume(counter.get())
        }
    }

    @Benchmark
    open fun uniffi_counter_increment_mutex(blackhole: Blackhole) {
        uniffi.demo.Counter(0).use { counter ->
            repeat(1000) { counter.increment() }
            blackhole.consume(counter.get())
        }
    }

    @Benchmark
    open fun boltffi_counter_increment_single_threaded(blackhole: Blackhole) {
        CounterSingleThreaded().use { counter ->
            repeat(1000) { counter.increment() }
            blackhole.consume(counter.get())
        }
    }

    @Benchmark
    open fun boltffi_datastore_add_record_1k(blackhole: Blackhole) {
        DataStore().use { store ->
            repeat(1000) { index ->
                store.add(DataPoint(index.toDouble(), index.toDouble() * 2.0, index.toLong()))
            }
            blackhole.consume(store.len())
        }
    }

    @Benchmark
    open fun uniffi_datastore_add_record_1k(blackhole: Blackhole) {
        uniffi.demo.DataStore().use { store ->
            repeat(1000) { index ->
                store.add(uniffi.demo.DataPoint(index.toDouble(), index.toDouble() * 2.0, index.toLong()))
            }
            blackhole.consume(store.len())
        }
    }

    @Benchmark
    open fun boltffi_accumulator_mutex(blackhole: Blackhole) {
        Accumulator().use { accumulator ->
            repeat(1000) { index -> accumulator.add(index.toLong()) }
            blackhole.consume(accumulator.get())
            accumulator.reset()
        }
    }

    @Benchmark
    open fun uniffi_accumulator_mutex(blackhole: Blackhole) {
        uniffi.demo.Accumulator().use { accumulator ->
            repeat(1000) { index -> accumulator.add(index.toLong()) }
            blackhole.consume(accumulator.get())
            accumulator.reset()
        }
    }

    @Benchmark
    open fun boltffi_accumulator_single_threaded(blackhole: Blackhole) {
        AccumulatorSingleThreaded().use { accumulator ->
            repeat(1000) { index -> accumulator.add(index.toLong()) }
            blackhole.consume(accumulator.get())
            accumulator.reset()
        }
    }

    @Benchmark
    open fun boltffi_simple_enum(blackhole: Blackhole) {
        blackhole.consume(oppositeDirection(Direction.NORTH))
        blackhole.consume(directionToDegrees(Direction.EAST))
    }

    @Benchmark
    open fun uniffi_simple_enum(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.oppositeDirection(UniffiDirection.NORTH))
        blackhole.consume(uniffi.demo.directionToDegrees(UniffiDirection.EAST))
    }

    @Benchmark
    open fun boltffi_generate_directions_1k(blackhole: Blackhole) {
        blackhole.consume(generateDirections(1000))
    }

    @Benchmark
    open fun uniffi_generate_directions_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateDirections(1000))
    }

    @Benchmark
    open fun boltffi_generate_directions_10k(blackhole: Blackhole) {
        blackhole.consume(generateDirections(10000))
    }

    @Benchmark
    open fun uniffi_generate_directions_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateDirections(10000))
    }

    @Benchmark
    open fun boltffi_count_north_1k(blackhole: Blackhole) {
        blackhole.consume(countNorth(boltffiDirections1k))
    }

    @Benchmark
    open fun uniffi_count_north_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.countNorth(uniffiDirections1k))
    }

    @Benchmark
    open fun boltffi_count_north_10k(blackhole: Blackhole) {
        blackhole.consume(countNorth(boltffiDirections10k))
    }

    @Benchmark
    open fun uniffi_count_north_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.countNorth(uniffiDirections10k))
    }

    @Benchmark
    open fun boltffi_find_even_100(blackhole: Blackhole) {
        repeat(100) { index -> blackhole.consume(findEven(index)) }
    }

    @Benchmark
    open fun uniffi_find_even_100(blackhole: Blackhole) {
        repeat(100) { index -> blackhole.consume(uniffi.demo.findEven(index)) }
    }

    @Benchmark
    open fun boltffi_generate_user_profiles_100(blackhole: Blackhole) {
        blackhole.consume(generateUserProfiles(100))
    }

    @Benchmark
    open fun uniffi_generate_user_profiles_100(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateUserProfiles(100))
    }

    @Benchmark
    open fun boltffi_generate_user_profiles_1k(blackhole: Blackhole) {
        blackhole.consume(generateUserProfiles(1000))
    }

    @Benchmark
    open fun uniffi_generate_user_profiles_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateUserProfiles(1000))
    }

    @Benchmark
    open fun boltffi_sum_user_scores_100(blackhole: Blackhole) {
        blackhole.consume(sumUserScores(boltffiUsers100))
    }

    @Benchmark
    open fun uniffi_sum_user_scores_100(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.sumUserScores(uniffiUsers100))
    }

    @Benchmark
    open fun boltffi_sum_user_scores_1k(blackhole: Blackhole) {
        blackhole.consume(sumUserScores(boltffiUsers1k))
    }

    @Benchmark
    open fun uniffi_sum_user_scores_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.sumUserScores(uniffiUsers1k))
    }

    @Benchmark
    open fun boltffi_count_active_users_100(blackhole: Blackhole) {
        blackhole.consume(countActiveUsers(boltffiUsers100))
    }

    @Benchmark
    open fun uniffi_count_active_users_100(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.countActiveUsers(uniffiUsers100))
    }

    @Benchmark
    open fun boltffi_count_active_users_1k(blackhole: Blackhole) {
        blackhole.consume(countActiveUsers(boltffiUsers1k))
    }

    @Benchmark
    open fun uniffi_count_active_users_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.countActiveUsers(uniffiUsers1k))
    }

    @Benchmark
    open fun boltffi_async_add(blackhole: Blackhole) {
        blackhole.consume(runBlocking { asyncAdd(100, 200) })
    }

    @Benchmark
    open fun uniffi_async_add(blackhole: Blackhole) {
        blackhole.consume(runBlocking { uniffi.demo.asyncAdd(100, 200) })
    }

    @Benchmark
    open fun boltffi_callback_100(blackhole: Blackhole) {
        DataConsumer().use { consumer ->
            consumer.setProvider(boltffiProvider100)
            blackhole.consume(consumer.computeSum())
        }
    }

    @Benchmark
    open fun uniffi_callback_100(blackhole: Blackhole) {
        uniffi.demo.DataConsumer().use { consumer ->
            consumer.setProvider(uniffiProvider100)
            blackhole.consume(consumer.computeSum())
        }
    }

    @Benchmark
    open fun boltffi_callback_1k(blackhole: Blackhole) {
        DataConsumer().use { consumer ->
            consumer.setProvider(boltffiProvider1k)
            blackhole.consume(consumer.computeSum())
        }
    }

    @Benchmark
    open fun uniffi_callback_1k(blackhole: Blackhole) {
        uniffi.demo.DataConsumer().use { consumer ->
            consumer.setProvider(uniffiProvider1k)
            blackhole.consume(consumer.computeSum())
        }
    }

    @Benchmark
    open fun boltffi_data_enum_input(blackhole: Blackhole) {
        blackhole.consume(getStatusProgress(TaskStatus.InProgress(50)))
        blackhole.consume(isStatusComplete(TaskStatus.Completed(100)))
    }

    @Benchmark
    open fun uniffi_data_enum_input(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.getStatusProgress(uniffi.demo.TaskStatus.InProgress(50)))
        blackhole.consume(uniffi.demo.isStatusComplete(uniffi.demo.TaskStatus.Completed(100)))
    }

    @Benchmark
    open fun boltffi_echo_bool(blackhole: Blackhole) {
        blackhole.consume(echoBool(true))
    }

    @Benchmark
    open fun uniffi_echo_bool(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.echoBool(true))
    }

    @Benchmark
    open fun boltffi_negate_bool(blackhole: Blackhole) {
        blackhole.consume(negateBool(true))
    }

    @Benchmark
    open fun uniffi_negate_bool(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.negateBool(true))
    }

    @Benchmark
    open fun boltffi_add_f64(blackhole: Blackhole) {
        blackhole.consume(addF64(1.25, 2.5))
    }

    @Benchmark
    open fun uniffi_add_f64(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.addF64(1.25, 2.5))
    }

    @Benchmark
    open fun boltffi_generate_string_1k(blackhole: Blackhole) {
        blackhole.consume(generateString(1000))
    }

    @Benchmark
    open fun uniffi_generate_string_1k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.generateString(1000))
    }

    @Benchmark
    open fun boltffi_echo_bytes_64k(blackhole: Blackhole) {
        blackhole.consume(echoBytes(echoBytes64k))
    }

    @Benchmark
    open fun uniffi_echo_bytes_64k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.echoBytes(echoBytes64k))
    }

    @Benchmark
    open fun boltffi_echo_vec_i32_10k(blackhole: Blackhole) {
        blackhole.consume(echoVecI32(echoVecI32Values10k))
    }

    @Benchmark
    open fun uniffi_echo_vec_i32_10k(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.echoVecI32(uniffiEchoVecI32Values10k))
    }

    @Benchmark
    open fun boltffi_echo_direction(blackhole: Blackhole) {
        blackhole.consume(echoDirection(Direction.NORTH))
    }

    @Benchmark
    open fun uniffi_echo_direction(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.echoDirection(UniffiDirection.NORTH))
    }

    @Benchmark
    open fun boltffi_find_direction(blackhole: Blackhole) {
        blackhole.consume(findDirection(0))
    }

    @Benchmark
    open fun uniffi_find_direction(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.findDirection(0))
    }

    @Benchmark
    open fun boltffi_find_positive_f64(blackhole: Blackhole) {
        blackhole.consume(findPositiveF64(3.14))
    }

    @Benchmark
    open fun uniffi_find_positive_f64(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.findPositiveF64(3.14))
    }

    @Benchmark
    open fun boltffi_find_name(blackhole: Blackhole) {
        blackhole.consume(findName(1))
    }

    @Benchmark
    open fun uniffi_find_name(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.findName(1))
    }

    @Benchmark
    open fun boltffi_find_names_100(blackhole: Blackhole) {
        blackhole.consume(findNames(100))
    }

    @Benchmark
    open fun uniffi_find_names_100(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.findNames(100))
    }

    @Benchmark
    open fun boltffi_find_numbers_100(blackhole: Blackhole) {
        blackhole.consume(findNumbers(100))
    }

    @Benchmark
    open fun uniffi_find_numbers_100(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.findNumbers(100))
    }

    @Benchmark
    open fun boltffi_find_locations_100(blackhole: Blackhole) {
        blackhole.consume(findLocations(100))
    }

    @Benchmark
    open fun uniffi_find_locations_100(blackhole: Blackhole) {
        blackhole.consume(uniffi.demo.findLocations(100))
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
}
