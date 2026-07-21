from tests.support import DemoTestCase

import demo


class OptionalFieldRecordTests(DemoTestCase):
    def test_user_profile(self) -> None:
        self.demo_case("case:records.with_options.user_profile.should_make_with_present_options")
        user_profile = demo.make_user_profile("Alice", 30, "alice@example.com", 98.5)
        self.assertEqual(user_profile, demo.UserProfile("Alice", 30, "alice@example.com", 98.5))
        self.demo_case("case:records.with_options.user_profile.should_roundtrip_present_options")
        self.assertEqual(demo.echo_user_profile(user_profile), user_profile)
        self.demo_case("case:records.with_options.user_profile.should_display_email_when_present")
        self.assertEqual(demo.user_display_name(user_profile), "Alice <alice@example.com>")

        self.demo_case("case:records.with_options.user_profile.should_make_with_absent_options")
        user_without_email = demo.make_user_profile("Bob", 22, None, None)
        self.assertEqual(user_without_email, demo.UserProfile("Bob", 22, None, None))
        self.demo_case("case:records.with_options.user_profile.should_roundtrip_absent_options")
        self.assertEqual(demo.echo_user_profile(user_without_email), user_without_email)
        self.demo_case("case:records.with_options.user_profile.should_display_name_when_email_absent")
        self.assertEqual(demo.user_display_name(user_without_email), "Bob")

        user_mixed_options = demo.make_user_profile("Cleo", 27, "cleo@example.com", None)
        self.demo_case("case:records.with_options.user_profile.should_roundtrip_mixed_options")
        self.assertEqual(demo.echo_user_profile(user_mixed_options), user_mixed_options)
        user_utf8 = demo.make_user_profile("Élodie", 31, "élodie@café.example", 88.25)
        self.demo_case("case:records.with_options.user_profile.should_roundtrip_utf8_optional_string")
        self.assertEqual(demo.echo_user_profile(user_utf8), user_utf8)

    def test_search_result(self) -> None:
        search_result = demo.SearchResult("rust ffi", 12, "cursor-1", 0.99)

        self.demo_case("case:records.with_options.search_result.should_roundtrip_present_options")
        self.assertEqual(demo.echo_search_result(search_result), search_result)
        search_result_absent = demo.SearchResult("rust ffi", 0, None, None)
        self.demo_case("case:records.with_options.search_result.should_roundtrip_absent_options")
        self.assertEqual(demo.echo_search_result(search_result_absent), search_result_absent)
        self.demo_case("case:records.with_options.search_result.should_report_more_results_when_cursor_present")
        self.assertIs(demo.has_more_results(search_result), True)
        self.demo_case("case:records.with_options.search_result.should_report_no_more_results_without_cursor")
        self.assertIs(demo.has_more_results(demo.SearchResult("rust ffi", 12, None, None)), False)
