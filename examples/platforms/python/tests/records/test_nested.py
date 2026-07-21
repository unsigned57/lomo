import math

from tests.support import DemoTestCase

import demo


class NestedRecordTests(DemoTestCase):
    def test_line(self) -> None:
        self.demo_case("case:records.nested.line.should_make_from_coordinates")
        line = demo.make_line(0.0, 0.0, 3.0, 4.0)
        self.assertEqual(line, demo.Line(demo.Point(0.0, 0.0), demo.Point(3.0, 4.0)))
        self.demo_case("case:records.nested.line.should_roundtrip_nested_points")
        self.assertEqual(demo.echo_line(line), line)
        self.demo_case("case:records.nested.line.should_compute_length")
        self.assertTrue(math.isclose(demo.line_length(line), 5.0, rel_tol=0.0, abs_tol=1e-12))

    def test_rect(self) -> None:
        rect = demo.Rect(demo.Point(1.0, 2.0), demo.Dimensions(3.0, 4.0))

        self.demo_case("case:records.nested.rect.should_roundtrip_nested_records")
        self.assertEqual(demo.echo_rect(rect), rect)
        self.demo_case("case:records.nested.rect.should_compute_area")
        self.assertTrue(math.isclose(demo.rect_area(rect), 12.0, rel_tol=0.0, abs_tol=1e-12))
