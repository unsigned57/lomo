from tests.support import DemoTestCase

import demo


class PrimitiveOptionsTests(DemoTestCase):
    def test_optional_i32(self) -> None:
        self.demo_case("case:options.primitives.i32.should_roundtrip_some")
        self.assertEqual(demo.echo_optional_i32(7), 7)
        self.demo_case("case:options.primitives.i32.should_roundtrip_none")
        self.assertIsNone(demo.echo_optional_i32(None))
        self.demo_case("case:options.primitives.i32.should_unwrap_some")
        self.assertEqual(demo.unwrap_or_default_i32(9, 4), 9)
        self.demo_case("case:options.primitives.i32.should_use_default_for_none")
        self.assertEqual(demo.unwrap_or_default_i32(None, 4), 4)
        self.demo_case("case:options.primitives.i32.should_make_some")
        self.assertEqual(demo.make_some_i32(12), 12)
        self.demo_case("case:options.primitives.i32.should_make_none")
        self.assertIsNone(demo.make_none_i32())
        self.demo_case("case:options.primitives.i32.should_double_some")
        self.assertEqual(demo.double_if_some(8), 16)
        self.demo_case("case:options.primitives.i32.should_preserve_none_when_doubling")
        self.assertIsNone(demo.double_if_some(None))
        self.demo_case("case:options.primitives.i32.should_find_even_value")
        self.assertEqual(demo.find_even(8), 8)
        self.demo_case("case:options.primitives.i32.should_return_none_for_odd_value")
        self.assertIsNone(demo.find_even(7))

    def test_optional_f64(self) -> None:
        self.demo_case("case:options.primitives.f64.should_roundtrip_some")
        self.assertEqual(demo.echo_optional_f64(4.5), 4.5)
        self.demo_case("case:options.primitives.f64.should_roundtrip_none")
        self.assertIsNone(demo.echo_optional_f64(None))
        self.demo_case("case:options.primitives.f64.should_find_positive_value")
        self.assertEqual(demo.find_positive_f64(3.5), 3.5)
        self.demo_case("case:options.primitives.f64.should_return_none_for_non_positive_value")
        self.assertIsNone(demo.find_positive_f64(-0.1))

    def test_optional_bool(self) -> None:
        self.demo_case("case:options.primitives.bool.should_roundtrip_some")
        self.assertIs(demo.echo_optional_bool(True), True)
        self.demo_case("case:options.primitives.bool.should_roundtrip_none")
        self.assertIsNone(demo.echo_optional_bool(None))

    def test_optional_i64(self) -> None:
        self.demo_case("case:options.primitives.i64.should_find_positive_value")
        self.assertEqual(demo.find_positive_i64(9_007_199_254_740_993), 9_007_199_254_740_993)
        self.demo_case("case:options.primitives.i64.should_return_none_for_non_positive_value")
        self.assertIsNone(demo.find_positive_i64(0))
