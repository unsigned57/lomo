import math
from tests.support import DemoTestCase

import demo


class PrimitiveVecsTests(DemoTestCase):
    def test_echo_vec_i32(self) -> None:
        self.demo_case("case:primitives.vecs.i32.should_roundtrip_non_empty")
        self.assertEqual(demo.echo_vec_i32([1, 2, 3]), [1, 2, 3])
        self.demo_case("case:primitives.vecs.i32.should_roundtrip_empty")
        self.assertEqual(demo.echo_vec_i32([]), [])

    def test_sum_vec_i32(self) -> None:
        self.demo_case("case:primitives.vecs.i32.should_sum_values")
        self.assertEqual(demo.sum_vec_i32([10, 20, 30]), 60)
        self.assertEqual(demo.sum_vec_i32([]), 0)

    def test_echo_vec_f64(self) -> None:
        self.demo_case("case:primitives.vecs.f64.should_roundtrip_values")
        values = demo.echo_vec_f64([1.5, 2.5])
        self.assertEqual(len(values), 2)
        self.assertTrue(math.isclose(values[0], 1.5, rel_tol=0.0, abs_tol=1e-12))
        self.assertTrue(math.isclose(values[1], 2.5, rel_tol=0.0, abs_tol=1e-12))

    def test_echo_vec_bool(self) -> None:
        self.demo_case("case:primitives.vecs.bool.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_bool([True, False, True]), [True, False, True])

    def test_echo_vec_i8(self) -> None:
        self.demo_case("case:primitives.vecs.i8.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_i8([-1, 0, 7]), [-1, 0, 7])

    def test_echo_vec_u8(self) -> None:
        self.demo_case("case:primitives.vecs.u8.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_u8(bytes([0, 1, 2, 3])), bytes([0, 1, 2, 3]))

    def test_echo_vec_i16(self) -> None:
        self.demo_case("case:primitives.vecs.i16.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_i16([-3, 0, 9]), [-3, 0, 9])

    def test_echo_vec_u16(self) -> None:
        self.demo_case("case:primitives.vecs.u16.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_u16([0, 10, 20]), [0, 10, 20])

    def test_echo_vec_u32(self) -> None:
        self.demo_case("case:primitives.vecs.u32.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_u32([0, 10, 20]), [0, 10, 20])

    def test_echo_vec_i64(self) -> None:
        self.demo_case("case:primitives.vecs.i64.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_i64([-5, 0, 8]), [-5, 0, 8])

    def test_echo_vec_u64(self) -> None:
        self.demo_case("case:primitives.vecs.u64.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_u64([0, 1, 2]), [0, 1, 2])

    def test_echo_vec_isize(self) -> None:
        self.demo_case("case:primitives.vecs.isize.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_isize([-2, 0, 5]), [-2, 0, 5])

    def test_echo_vec_usize(self) -> None:
        self.demo_case("case:primitives.vecs.usize.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_usize([0, 2, 4]), [0, 2, 4])

    def test_echo_vec_f32(self) -> None:
        self.demo_case("case:primitives.vecs.f32.should_roundtrip_values_with_tolerance")
        values = demo.echo_vec_f32([1.25, -2.5])
        self.assertEqual(len(values), 2)
        self.assertTrue(math.isclose(values[0], 1.25, rel_tol=0.0, abs_tol=1e-6))
        self.assertTrue(math.isclose(values[1], -2.5, rel_tol=0.0, abs_tol=1e-6))

    def test_echo_vec_string(self) -> None:
        self.demo_case("case:primitives.vecs.string.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_string(["hello", "world"]), ["hello", "world"])
        self.demo_case("case:primitives.vecs.string.should_report_utf8_byte_lengths")
        self.assertEqual(demo.vec_string_lengths(["hi", "café"]), [2, 5])

    def test_nested_vecs(self) -> None:
        self.demo_case("case:primitives.vecs.nested_i32.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_vec_i32([[1, 2], [], [-3]]), [[1, 2], [], [-3]])
        self.demo_case("case:primitives.vecs.nested_i32.should_roundtrip_empty_outer")
        self.assertEqual(demo.echo_vec_vec_i32([]), [])
        self.demo_case("case:primitives.vecs.nested_bool.should_roundtrip_values")
        self.assertEqual(
            demo.echo_vec_vec_bool([[True, False], [], [False]]),
            [[True, False], [], [False]],
        )
        self.demo_case("case:primitives.vecs.nested_isize.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_vec_isize([[-2, 0, 5], []]), [[-2, 0, 5], []])
        self.demo_case("case:primitives.vecs.nested_usize.should_roundtrip_values")
        self.assertEqual(demo.echo_vec_vec_usize([[0, 2, 4], []]), [[0, 2, 4], []])
        self.demo_case("case:primitives.vecs.nested_string.should_roundtrip_utf8_values")
        self.assertEqual(
            demo.echo_vec_vec_string([["hello", "café"], [], ["world"]]),
            [["hello", "café"], [], ["world"]],
        )
        self.demo_case("case:primitives.vecs.nested_i32.should_flatten_values")
        self.assertEqual(demo.flatten_vec_vec_i32([[1, 2], [], [3]]), [1, 2, 3])
        self.demo_case("case:primitives.vecs.nested_i32.should_flatten_empty")
        self.assertEqual(demo.flatten_vec_vec_i32([]), [])

    def test_make_range(self) -> None:
        self.demo_case("case:primitives.vecs.i32.should_make_range")
        self.assertEqual(demo.make_range(0, 5), [0, 1, 2, 3, 4])

    def test_reverse_vec_i32(self) -> None:
        self.demo_case("case:primitives.vecs.i32.should_reverse_values")
        self.assertEqual(demo.reverse_vec_i32([1, 2, 3]), [3, 2, 1])

    def test_benchmark_vecs(self) -> None:
        self.demo_case("case:primitives.vecs.i32.should_generate_sequence")
        self.assertEqual(demo.generate_i32_vec(4), [0, 1, 2, 3])
        self.demo_case("case:primitives.vecs.i32.should_sum_benchmark_values")
        self.assertEqual(demo.sum_i32_vec([10, 20, 30]), 60)
        self.demo_case("case:primitives.vecs.f64.should_generate_sequence")
        self.assertEqual(demo.generate_f64_vec(3), [0.0, 0.1, 0.2])
        self.demo_case("case:primitives.vecs.f64.should_sum_values")
        self.assertEqual(demo.sum_f64_vec([1.5, 2.5, 4.0]), 8.0)
        self.demo_case("case:primitives.vecs.u64.should_increment_first_value_in_place")
        self.assertEqual(demo.inc_u64([10, 20, 30]), [11, 20, 30])
        self.assertEqual(demo.inc_u64([]), [])
        self.demo_case("case:primitives.vecs.u64.should_increment_value")
        self.assertEqual(demo.inc_u64_value(41), 42)
