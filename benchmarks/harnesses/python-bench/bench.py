from __future__ import annotations

import argparse
import asyncio
import importlib
import importlib.util
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from types import ModuleType
from typing import Callable

import pyperf


@dataclass(frozen=True)
class BenchmarkCase:
    canonical_name: str
    boltffi: Callable[[], object]
    uniffi: Callable[[], object] | None = None


@dataclass(frozen=True)
class LoadedSubjects:
    boltffi: ModuleType
    uniffi: ModuleType


@dataclass(frozen=True)
class DataProviderFixture:
    count: int
    data_point: type

    def get_count(self) -> int:
        return self.count

    def get_item(self, index: int) -> object:
        return self.data_point(x=float(index), y=float(index) * 2.0, timestamp=index)


@dataclass(frozen=True)
class SubjectFixtures:
    echo_string_small: str
    echo_string_200: str
    echo_string_1k: str
    echo_string_64k: str
    echo_bytes_64k: bytes
    roundtrip_i32_vec_1k: list[int]
    echo_vec_i32_10k: list[int]
    boltffi_i32_vec_1k: list[int]
    boltffi_i32_vec_10k: list[int]
    boltffi_i32_vec_100k: list[int]
    uniffi_i32_vec_1k: list[int]
    uniffi_i32_vec_10k: list[int]
    uniffi_i32_vec_100k: list[int]
    boltffi_f64_vec_10k: list[float]
    uniffi_f64_vec_10k: list[float]
    boltffi_locations_100: list[object]
    boltffi_locations_1k: list[object]
    boltffi_locations_10k: list[object]
    uniffi_locations_100: list[object]
    uniffi_locations_1k: list[object]
    uniffi_locations_10k: list[object]
    boltffi_trades_1k: list[object]
    boltffi_trades_10k: list[object]
    uniffi_trades_1k: list[object]
    uniffi_trades_10k: list[object]
    boltffi_particles_1k: list[object]
    boltffi_particles_10k: list[object]
    uniffi_particles_1k: list[object]
    uniffi_particles_10k: list[object]
    boltffi_sensor_readings_1k: list[object]
    boltffi_sensor_readings_10k: list[object]
    uniffi_sensor_readings_1k: list[object]
    uniffi_sensor_readings_10k: list[object]
    boltffi_directions_1k: list[object]
    boltffi_directions_10k: list[object]
    uniffi_directions_1k: list[object]
    uniffi_directions_10k: list[object]
    boltffi_user_profiles_100: list[object]
    boltffi_user_profiles_1k: list[object]
    uniffi_user_profiles_100: list[object]
    uniffi_user_profiles_1k: list[object]
    data_point_tuples_1k: tuple[tuple[float, float, int], ...]
    boltffi_address: object
    uniffi_address: object
    boltffi_person: object
    uniffi_person: object
    boltffi_line: object
    uniffi_line: object
    boltffi_task_status_pending: object
    boltffi_task_status_in_progress: object
    boltffi_task_status_completed: object
    uniffi_task_status_pending: object
    uniffi_task_status_in_progress: object
    uniffi_task_status_completed: object
    boltffi_provider_100: DataProviderFixture
    boltffi_provider_1k: DataProviderFixture
    uniffi_provider_100: DataProviderFixture
    uniffi_provider_1k: DataProviderFixture


class PythonBenchmarkHarness:
    def __init__(self, boltffi_site: Path, uniffi_directory: Path) -> None:
        self.subjects = LoadedSubjects(
            boltffi=self._load_boltffi_module(boltffi_site),
            uniffi=self._load_uniffi_module(uniffi_directory),
        )
        self.fixtures = SubjectFixtures(
            echo_string_small="hello",
            echo_string_200="x" * 200,
            echo_string_1k="x" * 1000,
            echo_string_64k="x" * 65536,
            echo_bytes_64k=bytes([42]) * 65536,
            roundtrip_i32_vec_1k=list(range(1000)),
            echo_vec_i32_10k=list(range(10000)),
            boltffi_i32_vec_1k=self.subjects.boltffi.generate_i32_vec(1000),
            boltffi_i32_vec_10k=self.subjects.boltffi.generate_i32_vec(10000),
            boltffi_i32_vec_100k=self.subjects.boltffi.generate_i32_vec(100000),
            uniffi_i32_vec_1k=self.subjects.uniffi.generate_i32_vec(1000),
            uniffi_i32_vec_10k=self.subjects.uniffi.generate_i32_vec(10000),
            uniffi_i32_vec_100k=self.subjects.uniffi.generate_i32_vec(100000),
            boltffi_f64_vec_10k=self.subjects.boltffi.generate_f64_vec(10000),
            uniffi_f64_vec_10k=self.subjects.uniffi.generate_f64_vec(10000),
            boltffi_locations_100=self.subjects.boltffi.generate_locations(100),
            boltffi_locations_1k=self.subjects.boltffi.generate_locations(1000),
            boltffi_locations_10k=self.subjects.boltffi.generate_locations(10000),
            uniffi_locations_100=self.subjects.uniffi.generate_locations(100),
            uniffi_locations_1k=self.subjects.uniffi.generate_locations(1000),
            uniffi_locations_10k=self.subjects.uniffi.generate_locations(10000),
            boltffi_trades_1k=self.subjects.boltffi.generate_trades(1000),
            boltffi_trades_10k=self.subjects.boltffi.generate_trades(10000),
            uniffi_trades_1k=self.subjects.uniffi.generate_trades(1000),
            uniffi_trades_10k=self.subjects.uniffi.generate_trades(10000),
            boltffi_particles_1k=self.subjects.boltffi.generate_particles(1000),
            boltffi_particles_10k=self.subjects.boltffi.generate_particles(10000),
            uniffi_particles_1k=self.subjects.uniffi.generate_particles(1000),
            uniffi_particles_10k=self.subjects.uniffi.generate_particles(10000),
            boltffi_sensor_readings_1k=self.subjects.boltffi.generate_sensor_readings(1000),
            boltffi_sensor_readings_10k=self.subjects.boltffi.generate_sensor_readings(10000),
            uniffi_sensor_readings_1k=self.subjects.uniffi.generate_sensor_readings(1000),
            uniffi_sensor_readings_10k=self.subjects.uniffi.generate_sensor_readings(10000),
            boltffi_directions_1k=self.subjects.boltffi.generate_directions(1000),
            boltffi_directions_10k=self.subjects.boltffi.generate_directions(10000),
            uniffi_directions_1k=self.subjects.uniffi.generate_directions(1000),
            uniffi_directions_10k=self.subjects.uniffi.generate_directions(10000),
            boltffi_user_profiles_100=self.subjects.boltffi.generate_user_profiles(100),
            boltffi_user_profiles_1k=self.subjects.boltffi.generate_user_profiles(1000),
            uniffi_user_profiles_100=self.subjects.uniffi.generate_user_profiles(100),
            uniffi_user_profiles_1k=self.subjects.uniffi.generate_user_profiles(1000),
            data_point_tuples_1k=tuple((float(index), float(index) * 2.0, index) for index in range(1000)),
            boltffi_address=self.subjects.boltffi.Address(street="Market Street", city="San Francisco", zip="94103"),
            uniffi_address=self.subjects.uniffi.Address(street="Market Street", city="San Francisco", zip="94103"),
            boltffi_person=self.subjects.boltffi.Person(name="Ada", age=37),
            uniffi_person=self.subjects.uniffi.Person(name="Ada", age=37),
            boltffi_line=self.subjects.boltffi.Line(
                start=self.subjects.boltffi.Point(x=0.0, y=0.0),
                end=self.subjects.boltffi.Point(x=3.0, y=4.0),
            ),
            uniffi_line=self.subjects.uniffi.Line(
                start=self.subjects.uniffi.Point(x=0.0, y=0.0),
                end=self.subjects.uniffi.Point(x=3.0, y=4.0),
            ),
            boltffi_task_status_pending=self.subjects.boltffi.TaskStatusPending(),
            boltffi_task_status_in_progress=self.subjects.boltffi.TaskStatusInProgress(progress=50),
            boltffi_task_status_completed=self.subjects.boltffi.TaskStatusCompleted(result=100),
            uniffi_task_status_pending=self.subjects.uniffi.TaskStatus.PENDING(),
            uniffi_task_status_in_progress=self.subjects.uniffi.TaskStatus.IN_PROGRESS(50),
            uniffi_task_status_completed=self.subjects.uniffi.TaskStatus.COMPLETED(100),
            boltffi_provider_100=DataProviderFixture(100, self.subjects.boltffi.DataPoint),
            boltffi_provider_1k=DataProviderFixture(1000, self.subjects.boltffi.DataPoint),
            uniffi_provider_100=DataProviderFixture(100, self.subjects.uniffi.DataPoint),
            uniffi_provider_1k=DataProviderFixture(1000, self.subjects.uniffi.DataPoint),
        )

    def selected_cases(self, include_pattern: str | None) -> tuple[BenchmarkCase, ...]:
        all_cases = self._cases()
        if include_pattern is None:
            return all_cases

        include_regex = re.compile(include_pattern)
        return tuple(case for case in all_cases if include_regex.search(case.canonical_name))

    def _cases(self) -> tuple[BenchmarkCase, ...]:
        boltffi = self.subjects.boltffi
        uniffi = self.subjects.uniffi
        fixtures = self.fixtures

        return (
            BenchmarkCase("noop", boltffi.noop, uniffi.noop),
            BenchmarkCase("echo_bool", lambda: boltffi.echo_bool(True), lambda: uniffi.echo_bool(True)),
            BenchmarkCase("negate_bool", lambda: boltffi.negate_bool(True), lambda: uniffi.negate_bool(True)),
            BenchmarkCase("echo_i32", lambda: boltffi.echo_i32(42), lambda: uniffi.echo_i32(42)),
            BenchmarkCase("echo_f64", lambda: boltffi.echo_f64(3.14159), lambda: uniffi.echo_f64(3.14159)),
            BenchmarkCase("add", lambda: boltffi.add(100, 200), lambda: uniffi.add(100, 200)),
            BenchmarkCase("add_f64", lambda: boltffi.add_f64(1.25, 2.5), lambda: uniffi.add_f64(1.25, 2.5)),
            BenchmarkCase("multiply", lambda: boltffi.multiply(2.5, 4.0), lambda: uniffi.multiply(2.5, 4.0)),
            BenchmarkCase("inc_u64", lambda: boltffi.inc_u64([0])),
            BenchmarkCase("inc_u64_value", lambda: boltffi.inc_u64_value(0), lambda: uniffi.inc_u64_value(0)),
            BenchmarkCase(
                "echo_string_small",
                lambda: boltffi.echo_string(fixtures.echo_string_small),
                lambda: uniffi.echo_string(fixtures.echo_string_small),
            ),
            BenchmarkCase(
                "echo_string_200",
                lambda: boltffi.echo_string(fixtures.echo_string_200),
                lambda: uniffi.echo_string(fixtures.echo_string_200),
            ),
            BenchmarkCase(
                "echo_string_1k",
                lambda: boltffi.echo_string(fixtures.echo_string_1k),
                lambda: uniffi.echo_string(fixtures.echo_string_1k),
            ),
            BenchmarkCase(
                "echo_string_64k",
                lambda: boltffi.echo_string(fixtures.echo_string_64k),
                lambda: uniffi.echo_string(fixtures.echo_string_64k),
            ),
            BenchmarkCase("generate_string_1k", lambda: boltffi.generate_string(1000), lambda: uniffi.generate_string(1000)),
            BenchmarkCase("generate_string_64k", lambda: boltffi.generate_string(65536), lambda: uniffi.generate_string(65536)),
            BenchmarkCase(
                "echo_bytes_64k",
                lambda: boltffi.echo_bytes(fixtures.echo_bytes_64k),
                lambda: uniffi.echo_bytes(fixtures.echo_bytes_64k),
            ),
            BenchmarkCase("generate_bytes_64k", lambda: boltffi.generate_bytes(65536), lambda: uniffi.generate_bytes(65536)),
            BenchmarkCase(
                "simple_enum",
                lambda: (boltffi.opposite_direction(boltffi.Direction.NORTH), boltffi.direction_to_degrees(boltffi.Direction.EAST)),
                lambda: (uniffi.opposite_direction(uniffi.Direction.NORTH), uniffi.direction_to_degrees(uniffi.Direction.EAST)),
            ),
            BenchmarkCase(
                "data_enum_input",
                lambda: (
                    boltffi.get_status_progress(fixtures.boltffi_task_status_in_progress),
                    boltffi.is_status_complete(fixtures.boltffi_task_status_completed),
                ),
                lambda: (
                    uniffi.get_status_progress(fixtures.uniffi_task_status_in_progress),
                    uniffi.is_status_complete(fixtures.uniffi_task_status_completed),
                ),
            ),
            BenchmarkCase("find_even_100", lambda: self._find_even_100(boltffi), lambda: self._find_even_100(uniffi)),
            BenchmarkCase("find_positive_f64", lambda: boltffi.find_positive_f64(3.14), lambda: uniffi.find_positive_f64(3.14)),
            BenchmarkCase("find_name", lambda: boltffi.find_name(1), lambda: uniffi.find_name(1)),
            BenchmarkCase("find_names_100", lambda: boltffi.find_names(100), lambda: uniffi.find_names(100)),
            BenchmarkCase("find_numbers_100", lambda: boltffi.find_numbers(100), lambda: uniffi.find_numbers(100)),
            BenchmarkCase("async_add", lambda: asyncio.run(boltffi.async_add(100, 200))),
            BenchmarkCase(
                "counter_increment_mutex",
                lambda: self._counter_increment_mutex(boltffi),
                lambda: self._counter_increment_mutex(uniffi),
            ),
            BenchmarkCase(
                "counter_increment_single_threaded",
                lambda: self._counter_increment_single_threaded(boltffi),
            ),
            BenchmarkCase(
                "datastore_add_record_1k",
                lambda: self._datastore_add_record_1k(boltffi),
                lambda: self._datastore_add_record_1k(uniffi),
            ),
            BenchmarkCase(
                "datastore_add_scalars_1k",
                lambda: self._datastore_add_scalars_1k(boltffi),
            ),
            BenchmarkCase("accumulator_mutex", lambda: self._accumulator_mutex(boltffi), lambda: self._accumulator_mutex(uniffi)),
            BenchmarkCase("accumulator_single_threaded", lambda: self._accumulator_single_threaded(boltffi)),
            BenchmarkCase(
                "callback_100",
                lambda: self._callback_compute_sum(boltffi, fixtures.boltffi_provider_100),
                lambda: self._callback_compute_sum(uniffi, fixtures.uniffi_provider_100),
            ),
            BenchmarkCase(
                "callback_1k",
                lambda: self._callback_compute_sum(boltffi, fixtures.boltffi_provider_1k),
                lambda: self._callback_compute_sum(uniffi, fixtures.uniffi_provider_1k),
            ),
            BenchmarkCase(
                "roundtrip_locations_100",
                lambda: boltffi.process_locations(boltffi.generate_locations(100)),
                lambda: uniffi.process_locations(uniffi.generate_locations(100)),
            ),
            BenchmarkCase(
                "roundtrip_i32_vec_1k",
                lambda: boltffi.sum_i32_vec(boltffi.generate_i32_vec(1000)),
                lambda: uniffi.sum_i32_vec(uniffi.generate_i32_vec(1000)),
            ),
            BenchmarkCase(
                "echo_vec_i32_10k",
                lambda: boltffi.echo_vec_i32(fixtures.echo_vec_i32_10k),
                lambda: uniffi.echo_vec_i32(fixtures.echo_vec_i32_10k),
            ),
            BenchmarkCase(
                "echo_direction",
                lambda: boltffi.echo_direction(boltffi.Direction.NORTH),
                lambda: uniffi.echo_direction(uniffi.Direction.NORTH),
            ),
            BenchmarkCase(
                "echo_direction_north",
                lambda: boltffi.echo_direction(boltffi.Direction.NORTH),
                lambda: uniffi.echo_direction(uniffi.Direction.NORTH),
            ),
            BenchmarkCase(
                "echo_direction_west",
                lambda: boltffi.echo_direction(boltffi.Direction.WEST),
                lambda: uniffi.echo_direction(uniffi.Direction.WEST),
            ),
            BenchmarkCase(
                "echo_task_status_unit_variant",
                lambda: boltffi.echo_task_status(fixtures.boltffi_task_status_pending),
                lambda: uniffi.echo_task_status(fixtures.uniffi_task_status_pending),
            ),
            BenchmarkCase(
                "echo_task_status_small_payload",
                lambda: boltffi.echo_task_status(fixtures.boltffi_task_status_in_progress),
                lambda: uniffi.echo_task_status(fixtures.uniffi_task_status_in_progress),
            ),
            BenchmarkCase(
                "echo_task_status_completed_payload",
                lambda: boltffi.echo_task_status(fixtures.boltffi_task_status_completed),
                lambda: uniffi.echo_task_status(fixtures.uniffi_task_status_completed),
            ),
            BenchmarkCase("find_direction", lambda: boltffi.find_direction(0), lambda: uniffi.find_direction(0)),
            BenchmarkCase("find_locations_100", lambda: boltffi.find_locations(100), lambda: uniffi.find_locations(100)),
            BenchmarkCase("make_point", lambda: boltffi.make_point(3.0, 4.0), lambda: uniffi.make_point(3.0, 4.0)),
            BenchmarkCase(
                "echo_address",
                lambda: boltffi.echo_address(fixtures.boltffi_address),
                lambda: uniffi.echo_address(fixtures.uniffi_address),
            ),
            BenchmarkCase(
                "echo_person",
                lambda: boltffi.echo_person(fixtures.boltffi_person),
                lambda: uniffi.echo_person(fixtures.uniffi_person),
            ),
            BenchmarkCase(
                "echo_line",
                lambda: boltffi.echo_line(fixtures.boltffi_line),
                lambda: uniffi.echo_line(fixtures.uniffi_line),
            ),
            BenchmarkCase("generate_locations_100", lambda: boltffi.generate_locations(100), lambda: uniffi.generate_locations(100)),
            BenchmarkCase("generate_trades_100", lambda: boltffi.generate_trades(100), lambda: uniffi.generate_trades(100)),
            BenchmarkCase("generate_particles_100", lambda: boltffi.generate_particles(100), lambda: uniffi.generate_particles(100)),
            BenchmarkCase(
                "generate_sensor_readings_100",
                lambda: boltffi.generate_sensor_readings(100),
                lambda: uniffi.generate_sensor_readings(100),
            ),
            BenchmarkCase("generate_locations_1k", lambda: boltffi.generate_locations(1000), lambda: uniffi.generate_locations(1000)),
            BenchmarkCase("generate_trades_1k", lambda: boltffi.generate_trades(1000), lambda: uniffi.generate_trades(1000)),
            BenchmarkCase("generate_particles_1k", lambda: boltffi.generate_particles(1000), lambda: uniffi.generate_particles(1000)),
            BenchmarkCase(
                "generate_sensor_readings_1k",
                lambda: boltffi.generate_sensor_readings(1000),
                lambda: uniffi.generate_sensor_readings(1000),
            ),
            BenchmarkCase("generate_locations_10k", lambda: boltffi.generate_locations(10000), lambda: uniffi.generate_locations(10000)),
            BenchmarkCase("generate_trades_10k", lambda: boltffi.generate_trades(10000), lambda: uniffi.generate_trades(10000)),
            BenchmarkCase("generate_particles_10k", lambda: boltffi.generate_particles(10000), lambda: uniffi.generate_particles(10000)),
            BenchmarkCase(
                "generate_sensor_readings_10k",
                lambda: boltffi.generate_sensor_readings(10000),
                lambda: uniffi.generate_sensor_readings(10000),
            ),
            BenchmarkCase(
                "sum_ratings_1k",
                lambda: boltffi.sum_ratings(fixtures.boltffi_locations_1k),
                lambda: uniffi.sum_ratings(fixtures.uniffi_locations_1k),
            ),
            BenchmarkCase(
                "sum_trade_volumes_1k",
                lambda: boltffi.sum_trade_volumes(fixtures.boltffi_trades_1k),
                lambda: uniffi.sum_trade_volumes(fixtures.uniffi_trades_1k),
            ),
            BenchmarkCase(
                "sum_particle_masses_1k",
                lambda: boltffi.sum_particle_masses(fixtures.boltffi_particles_1k),
                lambda: uniffi.sum_particle_masses(fixtures.uniffi_particles_1k),
            ),
            BenchmarkCase(
                "avg_sensor_temp_1k",
                lambda: boltffi.avg_sensor_temperature(fixtures.boltffi_sensor_readings_1k),
                lambda: uniffi.avg_sensor_temperature(fixtures.uniffi_sensor_readings_1k),
            ),
            BenchmarkCase(
                "process_locations_1k",
                lambda: boltffi.process_locations(fixtures.boltffi_locations_1k),
                lambda: uniffi.process_locations(fixtures.uniffi_locations_1k),
            ),
            BenchmarkCase("generate_directions_1k", lambda: boltffi.generate_directions(1000), lambda: uniffi.generate_directions(1000)),
            BenchmarkCase("count_north_1k", lambda: boltffi.count_north(fixtures.boltffi_directions_1k), lambda: uniffi.count_north(fixtures.uniffi_directions_1k)),
            BenchmarkCase(
                "sum_ratings_10k",
                lambda: boltffi.sum_ratings(fixtures.boltffi_locations_10k),
                lambda: uniffi.sum_ratings(fixtures.uniffi_locations_10k),
            ),
            BenchmarkCase(
                "sum_trade_volumes_10k",
                lambda: boltffi.sum_trade_volumes(fixtures.boltffi_trades_10k),
                lambda: uniffi.sum_trade_volumes(fixtures.uniffi_trades_10k),
            ),
            BenchmarkCase(
                "sum_particle_masses_10k",
                lambda: boltffi.sum_particle_masses(fixtures.boltffi_particles_10k),
                lambda: uniffi.sum_particle_masses(fixtures.uniffi_particles_10k),
            ),
            BenchmarkCase(
                "avg_sensor_temp_10k",
                lambda: boltffi.avg_sensor_temperature(fixtures.boltffi_sensor_readings_10k),
                lambda: uniffi.avg_sensor_temperature(fixtures.uniffi_sensor_readings_10k),
            ),
            BenchmarkCase(
                "process_locations_10k",
                lambda: boltffi.process_locations(fixtures.boltffi_locations_10k),
                lambda: uniffi.process_locations(fixtures.uniffi_locations_10k),
            ),
            BenchmarkCase("generate_directions_10k", lambda: boltffi.generate_directions(10000), lambda: uniffi.generate_directions(10000)),
            BenchmarkCase("count_north_10k", lambda: boltffi.count_north(fixtures.boltffi_directions_10k), lambda: uniffi.count_north(fixtures.uniffi_directions_10k)),
            BenchmarkCase("generate_i32_vec_1k", lambda: boltffi.generate_i32_vec(1000), lambda: uniffi.generate_i32_vec(1000)),
            BenchmarkCase("generate_i32_vec_10k", lambda: boltffi.generate_i32_vec(10000), lambda: uniffi.generate_i32_vec(10000)),
            BenchmarkCase("generate_i32_vec_100k", lambda: boltffi.generate_i32_vec(100000), lambda: uniffi.generate_i32_vec(100000)),
            BenchmarkCase("sum_i32_vec_1k", lambda: boltffi.sum_i32_vec(fixtures.boltffi_i32_vec_1k), lambda: uniffi.sum_i32_vec(fixtures.uniffi_i32_vec_1k)),
            BenchmarkCase("sum_i32_vec_10k", lambda: boltffi.sum_i32_vec(fixtures.boltffi_i32_vec_10k), lambda: uniffi.sum_i32_vec(fixtures.uniffi_i32_vec_10k)),
            BenchmarkCase("sum_i32_vec_100k", lambda: boltffi.sum_i32_vec(fixtures.boltffi_i32_vec_100k), lambda: uniffi.sum_i32_vec(fixtures.uniffi_i32_vec_100k)),
            BenchmarkCase("generate_f64_vec_10k", lambda: boltffi.generate_f64_vec(10000), lambda: uniffi.generate_f64_vec(10000)),
            BenchmarkCase("sum_f64_vec_10k", lambda: boltffi.sum_f64_vec(fixtures.boltffi_f64_vec_10k), lambda: uniffi.sum_f64_vec(fixtures.uniffi_f64_vec_10k)),
            BenchmarkCase(
                "generate_user_profiles_100",
                lambda: boltffi.generate_user_profiles(100),
                lambda: uniffi.generate_user_profiles(100),
            ),
            BenchmarkCase(
                "sum_user_scores_100",
                lambda: boltffi.sum_user_scores(fixtures.boltffi_user_profiles_100),
                lambda: uniffi.sum_user_scores(fixtures.uniffi_user_profiles_100),
            ),
            BenchmarkCase(
                "count_active_users_100",
                lambda: boltffi.count_active_users(fixtures.boltffi_user_profiles_100),
                lambda: uniffi.count_active_users(fixtures.uniffi_user_profiles_100),
            ),
            BenchmarkCase(
                "generate_user_profiles_1k",
                lambda: boltffi.generate_user_profiles(1000),
                lambda: uniffi.generate_user_profiles(1000),
            ),
            BenchmarkCase(
                "sum_user_scores_1k",
                lambda: boltffi.sum_user_scores(fixtures.boltffi_user_profiles_1k),
                lambda: uniffi.sum_user_scores(fixtures.uniffi_user_profiles_1k),
            ),
            BenchmarkCase(
                "count_active_users_1k",
                lambda: boltffi.count_active_users(fixtures.boltffi_user_profiles_1k),
                lambda: uniffi.count_active_users(fixtures.uniffi_user_profiles_1k),
            ),
        )

    def _find_even_100(self, subject: ModuleType) -> object:
        result = None
        for index in range(100):
            result = subject.find_even(index)
        return result

    def _counter_increment_mutex(self, subject: ModuleType) -> int:
        counter = subject.Counter(0)
        for _ in range(1000):
            counter.increment()
        return counter.get()

    def _counter_increment_single_threaded(self, subject: ModuleType) -> int:
        counter = subject.CounterSingleThreaded()
        for _ in range(1000):
            counter.increment()
        return counter.get()

    def _datastore_add_record_1k(self, subject: ModuleType) -> int:
        store = subject.DataStore()
        for x, y, timestamp in self.fixtures.data_point_tuples_1k:
            store.add(subject.DataPoint(x=x, y=y, timestamp=timestamp))
        return store.len()

    def _datastore_add_scalars_1k(self, subject: ModuleType) -> int:
        store = subject.DataStore()
        for x, y, timestamp in self.fixtures.data_point_tuples_1k:
            store.add_parts(x, y, timestamp)
        return store.len()

    def _accumulator_mutex(self, subject: ModuleType) -> int:
        accumulator = subject.Accumulator()
        for index in range(1000):
            accumulator.add(index)
        value = accumulator.get()
        accumulator.reset()
        return value

    def _accumulator_single_threaded(self, subject: ModuleType) -> int:
        accumulator = subject.AccumulatorSingleThreaded()
        for index in range(1000):
            accumulator.add(index)
        value = accumulator.get()
        accumulator.reset()
        return value

    def _callback_compute_sum(self, subject: ModuleType, provider: DataProviderFixture) -> int:
        consumer = subject.DataConsumer()
        consumer.set_provider(provider)
        return consumer.compute_sum()

    def _load_boltffi_module(self, boltffi_site: Path) -> ModuleType:
        self._purge_demo_modules()
        sys.path.insert(0, str(boltffi_site))
        try:
            return importlib.import_module("demo")
        finally:
            sys.path.pop(0)

    def _load_uniffi_module(self, uniffi_directory: Path) -> ModuleType:
        module_path = uniffi_directory / "demo.py"
        module_spec = importlib.util.spec_from_file_location("demo_uniffi", module_path)
        if module_spec is None or module_spec.loader is None:
            raise RuntimeError(f"unable to load UniFFI Python module from {module_path}")

        module = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(module)
        return module

    def _purge_demo_modules(self) -> None:
        stale_module_names = [module_name for module_name in sys.modules if module_name == "demo" or module_name.startswith("demo.")]
        for module_name in stale_module_names:
            sys.modules.pop(module_name, None)


def add_worker_args(command: list[str], harness_args: argparse.Namespace) -> None:
    command.extend(
        [
            "--boltffi-site",
            str(harness_args.boltffi_site),
            "--uniffi-dir",
            str(harness_args.uniffi_dir),
        ]
    )
    if harness_args.include:
        command.extend(["--include", harness_args.include])


def main() -> None:
    argument_parser = argparse.ArgumentParser(add_help=False)
    argument_parser.add_argument("--boltffi-site", type=Path, required=True)
    argument_parser.add_argument("--uniffi-dir", type=Path, required=True)
    argument_parser.add_argument("--include")
    harness_args, pyperf_args = argument_parser.parse_known_args()
    sys.argv = [sys.argv[0], *pyperf_args]

    harness = PythonBenchmarkHarness(
        boltffi_site=harness_args.boltffi_site.resolve(),
        uniffi_directory=harness_args.uniffi_dir.resolve(),
    )
    cases = harness.selected_cases(harness_args.include)
    if not cases:
        raise SystemExit("no Python benchmark cases matched the requested filter")

    runner = pyperf.Runner(add_cmdline_args=lambda command, _args: add_worker_args(command, harness_args))
    runner.metadata["suite_name"] = "python-bench"

    for case in cases:
        runner.bench_func(f"boltffi_{case.canonical_name}", case.boltffi)
        if case.uniffi is not None:
            runner.bench_func(f"uniffi_{case.canonical_name}", case.uniffi)


if __name__ == "__main__":
    main()
