from __future__ import annotations

import re

from demo_benchmark_policy import BENCHMARK_FAMILIES


FAMILY_DIRECT_EXPORTS = {
    family.family_id: family.direct_exports for family in BENCHMARK_FAMILIES
}


def _family_id_for_coverage_case(case_name: str) -> str | None:
    if not case_name.startswith("coverage_"):
        return None

    family_token = case_name.removeprefix("coverage_")
    family_id = family_token.replace("__", ".")
    if family_id in FAMILY_DIRECT_EXPORTS:
        return family_id
    return None


def case_to_source_exports(case_name: str) -> tuple[str, ...]:
    if family_id := _family_id_for_coverage_case(case_name):
        return FAMILY_DIRECT_EXPORTS[family_id]

    if case_name in {"noop", "echo_i32", "echo_f64", "add", "multiply"}:
        return (f"primitives::scalars::{case_name}",)

    if case_name in {"echo_string_small", "echo_string_200", "echo_string_1k"}:
        return ("primitives::strings::echo_string",)

    if case_name == "generate_string_1k":
        return ("primitives::strings::generate_string",)

    if case_name == "generate_bytes_64k":
        return ("bytes::generate_bytes",)

    if case_name == "inc_u64":
        return ("primitives::vecs::inc_u64",)

    if case_name == "inc_u64_value":
        return ("primitives::vecs::inc_u64_value",)

    if case_name == "simple_enum":
        return (
            "enums::c_style::opposite_direction",
            "enums::c_style::direction_to_degrees",
        )

    if case_name == "data_enum_input":
        return (
            "enums::data_enum::get_status_progress",
            "enums::data_enum::is_status_complete",
        )

    if case_name in {"echo_direction", "echo_direction_north", "echo_direction_west"}:
        return ("enums::c_style::echo_direction",)

    if case_name in {
        "echo_task_status_unit_variant",
        "echo_task_status_small_payload",
        "echo_task_status_completed_payload",
    }:
        return ("enums::data_enum::echo_task_status",)

    if case_name == "find_even_100":
        return ("options::primitives::find_even",)

    if case_name == "async_add":
        return ("async_fns::async_add",)

    if re.match(r"^generate_locations_(?:100|1k|10k)$", case_name):
        return ("records::blittable::generate_locations",)

    if re.match(r"^process_locations_(?:1k|10k)$", case_name):
        return ("records::blittable::process_locations",)

    if re.match(r"^sum_ratings_(?:1k|10k)$", case_name):
        return ("records::blittable::sum_ratings",)

    if re.match(r"^generate_trades_(?:100|1k|10k)$", case_name):
        return ("records::blittable::generate_trades",)

    if re.match(r"^sum_trade_volumes_(?:1k|10k)$", case_name):
        return ("records::blittable::sum_trade_volumes",)

    if re.match(r"^generate_particles_(?:100|1k|10k)$", case_name):
        return ("records::blittable::generate_particles",)

    if re.match(r"^sum_particle_masses_(?:1k|10k)$", case_name):
        return ("records::blittable::sum_particle_masses",)

    if re.match(r"^generate_sensor_readings_(?:100|1k|10k)$", case_name):
        return ("records::blittable::generate_sensor_readings",)

    if re.match(r"^avg_sensor_temp_(?:1k|10k)$", case_name):
        return ("records::blittable::avg_sensor_temperature",)

    if re.match(r"^generate_i32_vec_(?:1k|10k|100k)$", case_name):
        return ("primitives::vecs::generate_i32_vec",)

    if re.match(r"^sum_i32_vec_(?:1k|10k|100k)$", case_name):
        return ("primitives::vecs::sum_i32_vec",)

    if case_name == "generate_f64_vec_10k":
        return ("primitives::vecs::generate_f64_vec",)

    if case_name == "sum_f64_vec_10k":
        return ("primitives::vecs::sum_f64_vec",)

    if re.match(r"^generate_directions_(?:1k|10k)$", case_name):
        return ("enums::c_style::generate_directions",)

    if re.match(r"^count_north_(?:1k|10k)$", case_name):
        return ("enums::c_style::count_north",)

    if case_name == "counter_increment_mutex":
        return (
            "classes::methods::Counter::new",
            "classes::methods::Counter::increment",
            "classes::methods::Counter::get",
        )

    if case_name == "counter_increment_single_threaded":
        return (
            "classes::unsafe_single_threaded::CounterSingleThreaded::new",
            "classes::unsafe_single_threaded::CounterSingleThreaded::increment",
            "classes::unsafe_single_threaded::CounterSingleThreaded::get",
        )

    if case_name == "datastore_add_record_1k":
        return (
            "classes::thread_safe::DataStore::new",
            "classes::thread_safe::DataStore::add",
            "classes::thread_safe::DataStore::len",
        )

    if case_name == "datastore_add_scalars_1k":
        return (
            "classes::thread_safe::DataStore::new",
            "classes::thread_safe::DataStore::add_parts",
            "classes::thread_safe::DataStore::len",
        )

    if case_name == "accumulator_mutex":
        return (
            "classes::thread_safe::Accumulator::new",
            "classes::thread_safe::Accumulator::add",
            "classes::thread_safe::Accumulator::get",
            "classes::thread_safe::Accumulator::reset",
        )

    if case_name == "accumulator_single_threaded":
        return (
            "classes::unsafe_single_threaded::AccumulatorSingleThreaded::new",
            "classes::unsafe_single_threaded::AccumulatorSingleThreaded::add",
            "classes::unsafe_single_threaded::AccumulatorSingleThreaded::get",
            "classes::unsafe_single_threaded::AccumulatorSingleThreaded::reset",
        )

    if re.match(r"^generate_user_profiles_(?:100|1k)$", case_name):
        return ("records::with_collections::generate_user_profiles",)

    if re.match(r"^sum_user_scores_(?:100|1k)$", case_name):
        return ("records::with_collections::sum_user_scores",)

    if re.match(r"^count_active_users_(?:100|1k)$", case_name):
        return ("records::with_collections::count_active_users",)

    if re.match(r"^callback_(?:100|1k)$", case_name):
        return (
            "callbacks::sync_traits::DataProvider::get_count",
            "callbacks::sync_traits::DataProvider::get_item",
            "callbacks::sync_traits::DataConsumer::new",
            "callbacks::sync_traits::DataConsumer::set_provider",
            "callbacks::sync_traits::DataConsumer::compute_sum",
        )

    if case_name == "roundtrip_locations_100":
        return (
            "records::blittable::generate_locations",
            "records::blittable::process_locations",
        )

    if case_name == "roundtrip_i32_vec_1k":
        return (
            "primitives::vecs::generate_i32_vec",
            "primitives::vecs::sum_i32_vec",
        )

    return ()
