import demo

from tests.support import DemoTestCase


class StringResultMessageCallback:
    def render_message(self, key: int) -> tuple[bool, str]:
        if key < 0:
            return False, f"invalid callback key {key}"
        return True, f"string-message:{key}"


class SyncTraitCallbackTests(DemoTestCase):
    def test_string_result_message_callback(self) -> None:
        callback = StringResultMessageCallback()

        self.demo_case("case:callbacks.sync_traits.string_result_message_callback.should_return_encoded_success")
        self.assertEqual(demo.invoke_string_result_message_callback(callback, 11), "string-message:11")

        self.demo_case("case:callbacks.sync_traits.string_result_message_callback.should_report_string_error")
        self.assert_runtime_error_value(
            "invalid callback key -1",
            lambda: demo.invoke_string_result_message_callback(callback, -1),
        )
