from tests.support import DemoTestCase

import demo


class StringRecordTests(DemoTestCase):
    def test_person(self) -> None:
        self.demo_case("case:records.with_strings.person.should_make_from_fields")
        person = demo.make_person("Ali", 30)
        self.assertEqual(person, demo.Person("Ali", 30))
        self.demo_case("case:records.with_strings.person.should_roundtrip_value")
        self.assertEqual(demo.echo_person(person), person)
        self.demo_case("case:records.with_strings.person.should_format_greeting")
        self.assertEqual(demo.greet_person(person), "Hello, Ali! You are 30 years old.")

    def test_address(self) -> None:
        address = demo.Address("Main St", "Amsterdam", "1000AA")

        self.demo_case("case:records.with_strings.address.should_roundtrip_value")
        self.assertEqual(demo.echo_address(address), address)
        self.demo_case("case:records.with_strings.address.should_format_value")
        self.assertEqual(demo.format_address(address), "Main St, Amsterdam, 1000AA")
