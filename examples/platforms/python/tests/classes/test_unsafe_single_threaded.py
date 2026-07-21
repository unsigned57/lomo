from tests.support import DemoTestCase

import demo


class UnsafeSingleThreadedClassTests(DemoTestCase):
    def test_map_view_marker(self) -> None:
        map_view = demo.MapView()

        self.demo_case("case:classes.unsafe_single_threaded.map_view.add_marker.should_return_single_threaded_marker_handle")
        marker = map_view.add_marker(demo.MarkerOptions(7, "harbor"))
        self.assertEqual(marker.id(), 7)
        self.assertEqual(marker.title(), "harbor")
        self.assertEqual(map_view.marker_count(), 1)
