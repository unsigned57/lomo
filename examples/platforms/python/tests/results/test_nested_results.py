from tests.support import DemoTestCase

import demo


class NestedResultTests(DemoTestCase):
    def assert_runtime_error_contains(self, expected: str, call) -> None:
        with self.assertRaises(RuntimeError) as error:
            call()
        self.assertIn(expected, str(error.exception))

    def test_option_result(self) -> None:
        self.demo_case("case:results.nested_results.option.should_return_some_for_positive_key")
        self.assertEqual(demo.result_of_option(4), 8)
        self.demo_case("case:results.nested_results.option.should_return_none_for_zero_key")
        self.assertIsNone(demo.result_of_option(0))
        self.demo_case("case:results.nested_results.option.should_reject_negative_key")
        self.assert_runtime_error_contains("invalid key", lambda: demo.result_of_option(-1))

    def test_vec_result(self) -> None:
        self.demo_case("case:results.nested_results.vec.should_return_values_for_non_negative_count")
        self.assertEqual(demo.result_of_vec(3), [0, 1, 2])
        self.demo_case("case:results.nested_results.vec.should_reject_negative_count")
        self.assert_runtime_error_contains("negative count", lambda: demo.result_of_vec(-1))

    def test_string_result(self) -> None:
        self.demo_case("case:results.nested_results.string.should_return_value_for_non_negative_key")
        self.assertEqual(demo.result_of_string(7), "item_7")
        self.demo_case("case:results.nested_results.string.should_reject_negative_key")
        self.assert_runtime_error_contains("invalid key", lambda: demo.result_of_string(-1))
