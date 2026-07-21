from tests.support import DemoTestCase

import demo


class MultiCrateTests(DemoTestCase):
    def test_exposed_subset_uses_dependency_types(self) -> None:
        self.assertEqual(demo.multi_echo_kind(demo.ForeignKind.ARCHIVE), demo.ForeignKind.ARCHIVE)
        self.assertEqual(demo.multi_kind_label(demo.ForeignKind.EXPRESS), "express")
        self.assertEqual(
            demo.multi_shift_point(demo.ForeignPoint(1.0, 2.0), 3.0, 4.0),
            demo.ForeignPoint(4.0, 6.0),
        )
        self.assertEqual(demo.multi_point_sum(demo.ForeignPoint(2.0, 5.0)), 7.0)
        self.assertEqual(demo.model_echo_kind(demo.ForeignKind.STANDARD), demo.ForeignKind.STANDARD)
        self.assertEqual(demo.model_kind_label(demo.ForeignKind.ARCHIVE), "archive")
        self.assertEqual(
            demo.model_shift_point(demo.ForeignPoint(2.0, 3.0), 5.0, 7.0),
            demo.ForeignPoint(7.0, 10.0),
        )
        self.assertEqual(demo.model_point_sum(demo.ForeignPoint(4.0, 6.0)), 10.0)
