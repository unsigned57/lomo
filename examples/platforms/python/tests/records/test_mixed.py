from tests.support import DemoTestCase

import demo


class MixedRecordTests(DemoTestCase):
    def test_mixed_record(self) -> None:
        record = demo.MixedRecord(
            "sample",
            demo.Point(1.0, 2.0),
            demo.Priority.HIGH,
            demo.ShapeCircle(5.0),
            demo.MixedRecordParameters(
                ["alpha", "beta"],
                [demo.Point(0.0, 0.0), demo.Point(3.0, 4.0)],
                demo.Point(9.0, 9.0),
                3,
                False,
            ),
        )

        self.demo_case("case:records.mixed.should_roundtrip_composed_record")
        self.assertEqual(demo.echo_mixed_record(record), record)
        self.demo_case("case:records.mixed.should_make_from_composed_parts")
        self.assertEqual(
            demo.make_mixed_record(
                record.name,
                record.anchor,
                record.priority,
                record.shape,
                record.parameters,
            ),
            record,
        )
