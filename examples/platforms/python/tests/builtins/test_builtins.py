import math
import uuid

from tests.support import DemoTestCase

import demo


class BuiltinValueTests(DemoTestCase):
    def test_duration(self) -> None:
        duration = 2.5

        self.demo_case("case:builtins.duration.should_roundtrip_value")
        self.assertTrue(math.isclose(demo.echo_duration(duration), duration, rel_tol=0.0, abs_tol=1e-12))
        self.demo_case("case:builtins.duration.should_construct_from_parts")
        self.assertTrue(math.isclose(demo.make_duration(3, 25), 3.000000025, rel_tol=0.0, abs_tol=1e-12))
        self.demo_case("case:builtins.duration.should_report_milliseconds")
        self.assertEqual(demo.duration_as_millis(duration), 2_500)

    def test_system_time(self) -> None:
        timestamp = 1_701_234_567.890

        self.demo_case("case:builtins.system_time.should_roundtrip_value")
        self.assertTrue(math.isclose(demo.echo_system_time(timestamp), timestamp, rel_tol=0.0, abs_tol=1e-9))
        self.demo_case("case:builtins.system_time.should_convert_to_epoch_milliseconds")
        self.assertEqual(demo.system_time_to_millis(timestamp), 1_701_234_567_890)
        self.demo_case("case:builtins.system_time.should_construct_from_epoch_milliseconds")
        self.assertTrue(math.isclose(demo.millis_to_system_time(1_701_234_567_890), timestamp, rel_tol=0.0, abs_tol=1e-9))
        self.demo_case("case:builtins.system_time.should_roundtrip_pre_epoch_value")
        self.assertTrue(math.isclose(demo.echo_system_time(-0.5), -0.5, rel_tol=0.0, abs_tol=1e-12))

    def test_uuid(self) -> None:
        value = uuid.UUID("123e4567-e89b-12d3-a456-426614174000")

        self.demo_case("case:builtins.uuid.should_roundtrip_value")
        self.assertEqual(demo.echo_uuid(value), value)
        self.demo_case("case:builtins.uuid.should_format_canonical_string")
        self.assertEqual(demo.uuid_to_string(value), str(value))

    def test_url(self) -> None:
        value = "https://example.com/demo?q=boltffi"

        self.demo_case("case:builtins.url.should_roundtrip_value")
        self.assertEqual(demo.echo_url(value), value)
        self.demo_case("case:builtins.url.should_format_string")
        self.assertEqual(demo.url_to_string(value), value)
