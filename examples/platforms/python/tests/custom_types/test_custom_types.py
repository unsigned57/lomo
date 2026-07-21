from tests.support import DemoTestCase

import demo


class CustomTypeTests(DemoTestCase):
    def test_email(self) -> None:
        email = "café@example.com"

        self.demo_case("case:custom_types.email.should_roundtrip_value")
        self.assertEqual(demo.echo_email(email), email)
        self.demo_case("case:custom_types.email.should_extract_domain")
        self.assertEqual(demo.email_domain(email), "example.com")

    def test_datetime(self) -> None:
        timestamp = 1_701_234_567_890

        self.demo_case("case:custom_types.datetime.should_roundtrip_millis")
        self.assertEqual(demo.echo_datetime(timestamp), timestamp)
        self.demo_case("case:custom_types.datetime.should_convert_to_millis")
        self.assertEqual(demo.datetime_to_millis(timestamp), timestamp)
        self.demo_case("case:custom_types.datetime.should_format_rfc3339_timestamp")
        self.assertEqual(demo.format_timestamp(timestamp), "2023-11-29T05:09:27.890+00:00")

    def test_event(self) -> None:
        event = demo.Event("launch", 1_701_234_567_890)

        self.demo_case("case:custom_types.event.should_expose_datetime_field")
        self.assertEqual(event.timestamp, 1_701_234_567_890)
        self.demo_case("case:custom_types.event.should_roundtrip_datetime_field")
        self.assertEqual(demo.echo_event(event), event)
        self.demo_case("case:custom_types.event.should_extract_timestamp_millis")
        self.assertEqual(demo.event_timestamp(event), 1_701_234_567_890)

    def test_vectors(self) -> None:
        emails = ["café@example.com", "user@example.org"]
        datetimes = [1_710_000_000_000, 1_710_000_001_000, 1_710_000_002_000]

        self.demo_case("case:custom_types.vectors.emails.should_roundtrip_values")
        self.assertEqual(demo.echo_emails(emails), emails)
        self.demo_case("case:custom_types.vectors.datetimes.should_roundtrip_millis_values")
        self.assertEqual(demo.echo_datetimes(datetimes), datetimes)
