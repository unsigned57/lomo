import math
from tests.support import DemoTestCase

import demo


class BlittableRecordsTests(DemoTestCase):
    def assert_point(
        self,
        point: demo.Point,
        *,
        x: float,
        y: float,
        tolerance: float = 1e-12,
    ) -> None:
        self.assertIsInstance(point, demo.Point)
        self.assertTrue(math.isclose(point.x, x, rel_tol=0.0, abs_tol=tolerance))
        self.assertTrue(math.isclose(point.y, y, rel_tol=0.0, abs_tol=tolerance))

    def test_point_surface(self) -> None:
        self.demo_case("case:records.blittable.point.should_construct_with_static_new")
        self.assert_point(demo.Point.new(1.0, 2.0), x=1.0, y=2.0)
        self.demo_case("case:records.blittable.point.should_return_origin")
        self.assert_point(demo.Point.origin(), x=0.0, y=0.0)
        self.demo_case("case:records.blittable.point.should_construct_from_polar_coordinates")
        self.assert_point(demo.Point.from_polar(2.0, math.pi / 2.0), x=0.0, y=2.0, tolerance=1e-9)
        self.demo_case("case:records.blittable.point.should_normalize_unit_vector")
        self.assert_point(demo.Point.try_unit(3.0, 4.0), x=0.6, y=0.8)
        self.demo_case("case:records.blittable.point.should_reject_zero_unit_vector")
        self.assert_runtime_error_value(
            "cannot normalize zero vector",
            lambda: demo.Point.try_unit(0.0, 0.0),
        )
        self.demo_case("case:records.blittable.point.should_return_some_for_checked_unit")
        self.assert_point(demo.Point.checked_unit(3.0, 4.0), x=0.6, y=0.8)
        self.demo_case("case:records.blittable.point.should_return_none_for_zero_checked_unit")
        self.assertIsNone(demo.Point.checked_unit(0.0, 0.0))
        self.demo_case("case:records.blittable.point.should_compute_path_length")
        self.assertTrue(
            math.isclose(
                demo.Point.path_length([demo.Point(0.0, 0.0), demo.Point(3.0, 4.0), demo.Point(6.0, 8.0)]),
                10.0,
                rel_tol=0.0,
                abs_tol=1e-12,
            )
        )
        self.demo_case("case:records.blittable.point.should_report_dimension_count")
        self.assertEqual(demo.Point.dimensions(), 2)

    def test_point_instance_methods(self) -> None:
        point = demo.Point(3.0, 4.0)

        self.demo_case("case:records.blittable.point.should_compute_distance")
        self.assertTrue(math.isclose(point.distance(), 5.0, rel_tol=0.0, abs_tol=1e-12))
        self.demo_case("case:records.blittable.point.should_scale_coordinates")
        self.assert_point(point.scale(2.0), x=6.0, y=8.0)
        self.demo_case("case:records.blittable.point.should_add_coordinates")
        self.assert_point(point.add(demo.Point(5.0, 6.0)), x=8.0, y=10.0)

    def test_point_functions(self) -> None:
        point = demo.Point(1.0, 2.0)

        self.demo_case("case:records.blittable.point.should_roundtrip_value")
        self.assert_point(demo.echo_point(point), x=1.0, y=2.0)
        self.demo_case("case:records.blittable.point.should_make_from_coordinates")
        self.assert_point(demo.make_point(1.0, 2.0), x=1.0, y=2.0)
        self.demo_case("case:records.blittable.point.should_add_values")
        self.assert_point(
            demo.add_points(demo.Point(3.0, 4.0), demo.Point(5.0, 6.0)),
            x=8.0,
            y=10.0,
        )
        self.demo_case("case:records.blittable.point.should_return_some_for_nonzero_coordinates")
        self.assert_point(demo.try_make_point(2.0, 3.0), x=2.0, y=3.0)
        self.demo_case("case:records.blittable.point.should_return_none_for_origin_coordinates")
        self.assertIsNone(demo.try_make_point(0.0, 0.0))

    def test_color_functions(self) -> None:
        color = demo.Color(1, 2, 3, 255)

        self.demo_case("case:records.blittable.color.should_roundtrip_value")
        self.assertEqual(demo.echo_color(color), color)
        self.demo_case("case:records.blittable.color.should_make_from_channels")
        self.assertEqual(demo.make_color(9, 8, 7, 6), demo.Color(9, 8, 7, 6))

    def test_location_vectors(self) -> None:
        self.demo_case("case:records.blittable.locations.should_generate_sample_vector")
        locations = demo.generate_locations(3)
        self.assertEqual(len(locations), 3)
        self.demo_case("case:records.blittable.locations.should_count_vector_items")
        self.assertEqual(demo.process_locations(locations), 3)
        self.demo_case("case:records.blittable.locations.should_count_empty_vector")
        self.assertEqual(demo.process_locations([]), 0)

        host_locations = [
            demo.Location(1, 1.0, 2.0, 3.5, 4, True),
            demo.Location(2, 5.0, 6.0, 2.5, 8, False),
        ]
        self.demo_case("case:records.blittable.locations.should_count_host_constructed_vector")
        self.assertEqual(demo.process_locations(host_locations), 2)
        self.demo_case("case:records.blittable.locations.should_sum_generated_ratings")
        self.assertTrue(math.isclose(demo.sum_ratings(locations), 9.3, rel_tol=0.0, abs_tol=1e-4))
        self.demo_case("case:records.blittable.locations.should_sum_host_constructed_ratings")
        self.assertTrue(math.isclose(demo.sum_ratings(host_locations), 6.0, rel_tol=0.0, abs_tol=1e-4))

        self.demo_case("case:records.blittable.locations.find_location.should_return_some_for_positive_id")
        found_location = demo.find_location(7)
        self.assertIsNotNone(found_location)
        self.assertEqual(found_location.id, 7)
        self.demo_case("case:records.blittable.locations.find_location.should_return_none_for_non_positive_id")
        self.assertIsNone(demo.find_location(0))
        self.demo_case("case:records.blittable.locations.find_locations.should_return_some_vector_for_positive_count")
        found_locations = demo.find_locations(3)
        self.assertIsNotNone(found_locations)
        self.assertEqual(len(found_locations), 3)
        self.demo_case("case:records.blittable.locations.find_locations.should_return_none_for_non_positive_count")
        self.assertIsNone(demo.find_locations(0))

    def test_trade_particle_and_sensor_vectors(self) -> None:
        self.demo_case("case:records.blittable.trades.should_generate_sample_vector")
        trades = demo.generate_trades(3)
        self.assertEqual(len(trades), 3)
        self.demo_case("case:records.blittable.trades.should_sum_volumes")
        self.assertEqual(demo.sum_trade_volumes(trades), 3_000)
        self.demo_case("case:records.blittable.trades.should_aggregate_with_locations")
        self.assertEqual(demo.aggregate_location_trade_stats(demo.generate_locations(3), trades), 3_002)

        self.demo_case("case:records.blittable.particles.should_generate_sample_vector")
        particles = demo.generate_particles(3)
        self.assertEqual(len(particles), 3)
        self.demo_case("case:records.blittable.particles.should_sum_masses")
        self.assertTrue(math.isclose(demo.sum_particle_masses(particles), 3.003, rel_tol=0.0, abs_tol=1e-4))

        self.demo_case("case:records.blittable.sensor_readings.should_generate_sample_vector")
        readings = demo.generate_sensor_readings(3)
        self.assertEqual(len(readings), 3)
        self.demo_case("case:records.blittable.sensor_readings.should_average_generated_temperatures")
        self.assertTrue(math.isclose(demo.avg_sensor_temperature(readings), 21.0, rel_tol=0.0, abs_tol=1e-4))
        self.demo_case("case:records.blittable.sensor_readings.should_average_empty_vector_as_zero")
        self.assertEqual(demo.avg_sensor_temperature([]), 0.0)
