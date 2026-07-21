from tests.support import DemoTestCase

import demo


class EnumFieldRecordTests(DemoTestCase):
    def test_task(self) -> None:
        self.demo_case("case:records.with_enums.task.should_make_incomplete_task")
        task = demo.make_task("ship bindings", demo.Priority.CRITICAL)
        self.assertEqual(task, demo.Task("ship bindings", demo.Priority.CRITICAL, False))
        self.demo_case("case:records.with_enums.task.should_detect_urgent_priority")
        self.assertIs(demo.is_urgent(task), True)
        self.demo_case("case:records.with_enums.task.should_roundtrip_priority_field")
        self.assertEqual(demo.echo_task(task), task)

    def test_notification(self) -> None:
        notification = demo.Notification("heads up", demo.Priority.HIGH, False)

        self.demo_case("case:records.with_enums.notification.should_roundtrip_priority_field")
        self.assertEqual(demo.echo_notification(notification), notification)

    def test_holder(self) -> None:
        self.demo_case("case:records.with_enums.holder.should_make_triangle_variant")
        triangle = demo.make_triangle_holder()
        self.assertIsInstance(triangle.shape, demo.ShapeTriangle)
        self.demo_case("case:records.with_enums.holder.should_roundtrip_data_enum_field")
        self.assertEqual(demo.echo_holder(triangle), triangle)

    def test_task_header(self) -> None:
        self.demo_case("case:records.with_enums.task_header.should_make_critical_header")
        header = demo.make_critical_task_header(42)
        self.assertEqual(header, demo.TaskHeader(42, demo.Priority.CRITICAL, False))
        self.demo_case("case:records.with_enums.task_header.should_roundtrip_repr_enum_field")
        self.assertEqual(demo.echo_task_header(header), header)

    def test_log_entry(self) -> None:
        self.demo_case("case:records.with_enums.log_entry.should_make_error_entry")
        log_entry = demo.make_error_log_entry(1_234_567_890, 42)
        self.assertEqual(log_entry, demo.LogEntry(1_234_567_890, demo.LogLevel.ERROR, 42))
        self.demo_case("case:records.with_enums.log_entry.should_roundtrip_u8_enum_field")
        self.assertEqual(demo.echo_log_entry(log_entry), log_entry)
