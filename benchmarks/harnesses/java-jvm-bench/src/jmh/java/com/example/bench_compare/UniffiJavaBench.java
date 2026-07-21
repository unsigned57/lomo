package com.example.bench_compare;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import uniffi.demo.Demo;
import uniffi.demo.Location;
import uniffi.demo.Trade;
import uniffi.demo.Particle;
import uniffi.demo.SensorReading;
import uniffi.demo.BenchmarkUserProfile;
import uniffi.demo.Direction;
import uniffi.demo.TaskStatus;
import uniffi.demo.Counter;
import uniffi.demo.DataStore;
import uniffi.demo.DataPoint;
import uniffi.demo.Accumulator;
import uniffi.demo.DataConsumer;
import uniffi.demo.DataProvider;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class UniffiJavaBench {
    private List<Location> locations1k;
    private List<Location> locations10k;
    private List<Trade> trades1k;
    private List<Trade> trades10k;
    private List<Particle> particles1k;
    private List<Particle> particles10k;
    private List<SensorReading> sensors1k;
    private List<SensorReading> sensors10k;
    private List<Direction> directions1k;
    private List<Direction> directions10k;
    private int[] i32Vec10k;
    private int[] i32Vec100k;
    private double[] f64Vec10k;
    private byte[] echoBytes64k;
    private int[] echoVecI32Values10k;
    private List<BenchmarkUserProfile> users100;
    private List<BenchmarkUserProfile> users1k;
    private DataProvider provider100;
    private DataProvider provider1k;

    @Setup
    public void setup() {
        locations1k = Demo.generateLocations(1000);
        locations10k = Demo.generateLocations(10000);
        trades1k = Demo.generateTrades(1000);
        trades10k = Demo.generateTrades(10000);
        particles1k = Demo.generateParticles(1000);
        particles10k = Demo.generateParticles(10000);
        sensors1k = Demo.generateSensorReadings(1000);
        sensors10k = Demo.generateSensorReadings(10000);
        directions1k = Demo.generateDirections(1000);
        directions10k = Demo.generateDirections(10000);
        i32Vec10k = Demo.generateI32Vec(10000);
        i32Vec100k = Demo.generateI32Vec(100_000);
        f64Vec10k = Demo.generateF64Vec(10000);
        echoBytes64k = new byte[65_536];
        java.util.Arrays.fill(echoBytes64k, (byte) 42);
        echoVecI32Values10k = java.util.stream.IntStream.range(0, 10_000).toArray();
        users100 = Demo.generateUserProfiles(100);
        users1k = Demo.generateUserProfiles(1000);
        provider100 = new FixedDataProvider(100);
        provider1k = new FixedDataProvider(1000);
    }

    // --- Call Overhead ---

    @Benchmark
    public void uniffi_java_noop(Blackhole bh) {
        Demo.noop();
        bh.consume(0);
    }

    @Benchmark
    public void uniffi_java_echo_bool(Blackhole bh) {
        bh.consume(Demo.echoBool(true));
    }

    @Benchmark
    public void uniffi_java_negate_bool(Blackhole bh) {
        bh.consume(Demo.negateBool(true));
    }

    @Benchmark
    public void uniffi_java_echo_i32(Blackhole bh) {
        bh.consume(Demo.echoI32(42));
    }

    @Benchmark
    public void uniffi_java_echo_f64(Blackhole bh) {
        bh.consume(Demo.echoF64(3.14159));
    }

    @Benchmark
    public void uniffi_java_add_f64(Blackhole bh) {
        bh.consume(Demo.addF64(1.25, 2.5));
    }

    @Benchmark
    public void uniffi_java_add(Blackhole bh) {
        bh.consume(Demo.add(100, 200));
    }

    @Benchmark
    public void uniffi_java_multiply(Blackhole bh) {
        bh.consume(Demo.multiply(2.5, 4.0));
    }

    @Benchmark
    public void uniffi_java_inc_u64(Blackhole bh) {
        bh.consume(Demo.incU64Value(0L));
    }

    @Benchmark
    public void uniffi_java_inc_u64_value(Blackhole bh) {
        bh.consume(Demo.incU64Value(0L));
    }

    // --- Strings ---

    @Benchmark
    public void uniffi_java_echo_string_small(Blackhole bh) {
        bh.consume(Demo.echoString("hello"));
    }

    @Benchmark
    public void uniffi_java_echo_string_1k(Blackhole bh) {
        bh.consume(Demo.echoString("x".repeat(1000)));
    }

    @Benchmark
    public void uniffi_java_generate_string_1k(Blackhole bh) {
        bh.consume(Demo.generateString(1000));
    }

    @Benchmark
    public void uniffi_java_echo_bytes_64k(Blackhole bh) {
        bh.consume(Demo.echoBytes(echoBytes64k));
    }

    @Benchmark
    public void uniffi_java_echo_vec_i32_10k(Blackhole bh) {
        bh.consume(Demo.echoVecI32(echoVecI32Values10k));
    }

    // --- Struct Generation ---

    @Benchmark
    public void uniffi_java_generate_locations_1k(Blackhole bh) {
        bh.consume(Demo.generateLocations(1000));
    }

    @Benchmark
    public void uniffi_java_generate_locations_10k(Blackhole bh) {
        bh.consume(Demo.generateLocations(10000));
    }

    @Benchmark
    public void uniffi_java_generate_trades_1k(Blackhole bh) {
        bh.consume(Demo.generateTrades(1000));
    }

    @Benchmark
    public void uniffi_java_generate_trades_10k(Blackhole bh) {
        bh.consume(Demo.generateTrades(10000));
    }

    @Benchmark
    public void uniffi_java_generate_particles_1k(Blackhole bh) {
        bh.consume(Demo.generateParticles(1000));
    }

    @Benchmark
    public void uniffi_java_generate_particles_10k(Blackhole bh) {
        bh.consume(Demo.generateParticles(10000));
    }

    @Benchmark
    public void uniffi_java_generate_sensor_readings_1k(Blackhole bh) {
        bh.consume(Demo.generateSensorReadings(1000));
    }

    @Benchmark
    public void uniffi_java_generate_sensor_readings_10k(Blackhole bh) {
        bh.consume(Demo.generateSensorReadings(10000));
    }

    @Benchmark
    public void uniffi_java_generate_user_profiles_100(Blackhole bh) {
        bh.consume(Demo.generateUserProfiles(100));
    }

    @Benchmark
    public void uniffi_java_generate_user_profiles_1k(Blackhole bh) {
        bh.consume(Demo.generateUserProfiles(1000));
    }

    @Benchmark
    public void uniffi_java_generate_directions_1k(Blackhole bh) {
        bh.consume(Demo.generateDirections(1000));
    }

    @Benchmark
    public void uniffi_java_generate_directions_10k(Blackhole bh) {
        bh.consume(Demo.generateDirections(10000));
    }

    // --- Struct Consumption ---

    @Benchmark
    public void uniffi_java_sum_ratings_1k(Blackhole bh) {
        bh.consume(Demo.sumRatings(locations1k));
    }

    @Benchmark
    public void uniffi_java_sum_ratings_10k(Blackhole bh) {
        bh.consume(Demo.sumRatings(locations10k));
    }

    @Benchmark
    public void uniffi_java_sum_trade_volumes_1k(Blackhole bh) {
        bh.consume(Demo.sumTradeVolumes(trades1k));
    }

    @Benchmark
    public void uniffi_java_sum_trade_volumes_10k(Blackhole bh) {
        bh.consume(Demo.sumTradeVolumes(trades10k));
    }

    @Benchmark
    public void uniffi_java_sum_particle_masses_1k(Blackhole bh) {
        bh.consume(Demo.sumParticleMasses(particles1k));
    }

    @Benchmark
    public void uniffi_java_sum_particle_masses_10k(Blackhole bh) {
        bh.consume(Demo.sumParticleMasses(particles10k));
    }

    @Benchmark
    public void uniffi_java_avg_sensor_temp_1k(Blackhole bh) {
        bh.consume(Demo.avgSensorTemperature(sensors1k));
    }

    @Benchmark
    public void uniffi_java_avg_sensor_temp_10k(Blackhole bh) {
        bh.consume(Demo.avgSensorTemperature(sensors10k));
    }

    @Benchmark
    public void uniffi_java_process_locations_1k(Blackhole bh) {
        bh.consume(Demo.processLocations(locations1k));
    }

    @Benchmark
    public void uniffi_java_process_locations_10k(Blackhole bh) {
        bh.consume(Demo.processLocations(locations10k));
    }

    @Benchmark
    public void uniffi_java_sum_user_scores_100(Blackhole bh) {
        bh.consume(Demo.sumUserScores(users100));
    }

    @Benchmark
    public void uniffi_java_sum_user_scores_1k(Blackhole bh) {
        bh.consume(Demo.sumUserScores(users1k));
    }

    @Benchmark
    public void uniffi_java_count_active_users_100(Blackhole bh) {
        bh.consume(Demo.countActiveUsers(users100));
    }

    @Benchmark
    public void uniffi_java_count_active_users_1k(Blackhole bh) {
        bh.consume(Demo.countActiveUsers(users1k));
    }

    @Benchmark
    public void uniffi_java_count_north_1k(Blackhole bh) {
        bh.consume(Demo.countNorth(directions1k));
    }

    @Benchmark
    public void uniffi_java_count_north_10k(Blackhole bh) {
        bh.consume(Demo.countNorth(directions10k));
    }

    // --- Primitive Vectors ---

    @Benchmark
    public void uniffi_java_generate_i32_vec_10k(Blackhole bh) {
        bh.consume(Demo.generateI32Vec(10000));
    }

    @Benchmark
    public void uniffi_java_generate_i32_vec_100k(Blackhole bh) {
        bh.consume(Demo.generateI32Vec(100_000));
    }

    @Benchmark
    public void uniffi_java_generate_f64_vec_10k(Blackhole bh) {
        bh.consume(Demo.generateF64Vec(10000));
    }

    @Benchmark
    public void uniffi_java_generate_bytes_64k(Blackhole bh) {
        bh.consume(Demo.generateBytes(65536));
    }

    @Benchmark
    public void uniffi_java_sum_i32_vec_10k(Blackhole bh) {
        bh.consume(Demo.sumI32Vec(i32Vec10k));
    }

    @Benchmark
    public void uniffi_java_sum_i32_vec_100k(Blackhole bh) {
        bh.consume(Demo.sumI32Vec(i32Vec100k));
    }

    @Benchmark
    public void uniffi_java_sum_f64_vec_10k(Blackhole bh) {
        bh.consume(Demo.sumF64Vec(f64Vec10k));
    }

    // --- Classes ---

    @Benchmark
    public void uniffi_java_counter_increment_mutex(Blackhole bh) {
        try (var counter = new Counter(0)) {
            for (int i = 0; i < 1000; i++) {
                counter.increment();
            }
            bh.consume(counter.get());
        }
    }

    @Benchmark
    public void uniffi_java_datastore_add_record_1k(Blackhole bh) {
        try (var store = new DataStore()) {
            for (int i = 0; i < 1000; i++) {
                store.add(new DataPoint((double) i, (double) i * 2.0, (long) i));
            }
            bh.consume(store.len());
        }
    }

    @Benchmark
    public void uniffi_java_accumulator_mutex(Blackhole bh) {
        try (var acc = new Accumulator()) {
            for (int i = 0; i < 1000; i++) {
                acc.add((long) i);
            }
            bh.consume(acc.get());
            acc.reset();
        }
    }

    @Benchmark
    public void uniffi_java_callback_100(Blackhole bh) {
        try (var consumer = new DataConsumer()) {
            consumer.setProvider(provider100);
            bh.consume(consumer.computeSum());
        }
    }

    @Benchmark
    public void uniffi_java_callback_1k(Blackhole bh) {
        try (var consumer = new DataConsumer()) {
            consumer.setProvider(provider1k);
            bh.consume(consumer.computeSum());
        }
    }

    // --- Enums ---

    @Benchmark
    public void uniffi_java_simple_enum(Blackhole bh) {
        bh.consume(Demo.oppositeDirection(Direction.NORTH));
        bh.consume(Demo.directionToDegrees(Direction.EAST));
    }

    @Benchmark
    public void uniffi_java_echo_direction(Blackhole bh) {
        bh.consume(Demo.echoDirection(Direction.NORTH));
    }

    @Benchmark
    public void uniffi_java_find_direction(Blackhole bh) {
        bh.consume(Demo.findDirection(0));
    }

    @Benchmark
    public void uniffi_java_data_enum_input(Blackhole bh) {
        bh.consume(Demo.getStatusProgress(new TaskStatus.InProgress(50)));
        bh.consume(Demo.isStatusComplete(new TaskStatus.Completed(100)));
    }

    // --- Optional ---

    @Benchmark
    public void uniffi_java_find_even_100(Blackhole bh) {
        for (int i = 0; i < 100; i++) {
            bh.consume(Demo.findEven(i));
        }
    }

    @Benchmark
    public void uniffi_java_find_positive_f64(Blackhole bh) {
        bh.consume(Demo.findPositiveF64(3.14));
    }

    @Benchmark
    public void uniffi_java_find_name(Blackhole bh) {
        bh.consume(Demo.findName(1));
    }

    @Benchmark
    public void uniffi_java_find_names_100(Blackhole bh) {
        bh.consume(Demo.findNames(100));
    }

    @Benchmark
    public void uniffi_java_find_numbers_100(Blackhole bh) {
        bh.consume(Demo.findNumbers(100));
    }

    @Benchmark
    public void uniffi_java_find_locations_100(Blackhole bh) {
        bh.consume(Demo.findLocations(100));
    }

    @Benchmark
    public void uniffi_java_async_add(Blackhole bh) {
        bh.consume(Demo.asyncAdd(100, 200).join());
    }

    private static final class FixedDataProvider implements DataProvider {
        private final DataPoint[] points;

        private FixedDataProvider(int count) {
            this.points = new DataPoint[count];
            for (int index = 0; index < count; index++) {
                points[index] = new DataPoint((double) index, (double) index * 2.0, (long) index);
            }
        }

        @Override
        public int getCount() {
            return points.length;
        }

        @Override
        public DataPoint getItem(int index) {
            return points[index];
        }
    }
}
