from tests.support import DemoTestCase

import demo


class StreamClassTests(DemoTestCase):
    def test_encoded_record_stream_items(self) -> None:
        bus = demo.EventBus()
        subscription = bus.subscribe_messages()

        self.demo_case("case:classes.streams.event_bus.subscribe_messages.should_deliver_encoded_record_items")
        bus.emit_message(demo.StreamMessage("alpha", [1, 2]))
        self.assertEqual(subscription.pop_batch(), [demo.StreamMessage("alpha", [1, 2])])
