import math

from tests.support import DemoTestCase

import demo


class DataEnumTests(DemoTestCase):
    def assert_point(self, point: demo.Point, *, x: float, y: float, tolerance: float = 1e-12) -> None:
        self.assertTrue(math.isclose(point.x, x, rel_tol=0.0, abs_tol=tolerance))
        self.assertTrue(math.isclose(point.y, y, rel_tol=0.0, abs_tol=tolerance))

    def test_shape_constructors_and_methods(self) -> None:
        self.demo_case("case:enums.data_enum.shape.should_support_primary_constructor")
        self.assertEqual(demo.Shape.new(5.0), demo.ShapeCircle(5.0))
        self.demo_case("case:enums.data_enum.shape.unit_circle.should_construct_circle")
        self.assertEqual(demo.Shape.unit_circle(), demo.ShapeCircle(1.0))
        self.demo_case("case:enums.data_enum.shape.square.should_construct_rectangle")
        self.assertEqual(demo.Shape.square(3.0), demo.ShapeRectangle(3.0, 3.0))
        self.demo_case("case:enums.data_enum.shape.try_circle.should_return_circle_for_positive_radius")
        self.assertEqual(demo.Shape.try_circle(2.0), demo.ShapeCircle(2.0))
        self.demo_case("case:enums.data_enum.shape.should_reject_non_positive_circle_radius")
        with self.assertRaises(Exception) as error:
            demo.Shape.try_circle(0.0)
        self.assertIn("radius must be positive", str(error.exception))
        self.demo_case("case:enums.data_enum.shape.should_support_numeric_instance_methods")
        self.assertTrue(math.isclose(demo.ShapeCircle(2.0).area(), math.pi * 4.0, rel_tol=0.0, abs_tol=1e-12))
        self.demo_case("case:enums.data_enum.shape.should_support_string_instance_methods")
        self.assertEqual(demo.ShapePoint().describe(), "point")
        self.demo_case("case:enums.data_enum.shape.should_report_variant_count")
        self.assertEqual(demo.Shape.variant_count(), 6)
        self.demo_case("case:enums.data_enum.shape.should_support_free_function_factories")
        self.assertEqual(demo.make_circle(5.0), demo.ShapeCircle(5.0))
        self.assertEqual(demo.make_rectangle(3.0, 4.0), demo.ShapeRectangle(3.0, 4.0))
        self.demo_case("case:enums.data_enum.shape.maybe_circle.should_return_some_for_positive_radius")
        self.assertEqual(demo.Shape.maybe_circle(2.0), demo.ShapeCircle(2.0))
        self.demo_case("case:enums.data_enum.shape.maybe_circle.should_return_none_for_non_positive_radius")
        self.assertIsNone(demo.Shape.maybe_circle(0.0))

    def test_shape_roundtrips(self) -> None:
        triangle = demo.ShapeTriangle(demo.Point(0.0, 0.0), demo.Point(3.0, 0.0), demo.Point(0.0, 4.0))

        self.demo_case("case:enums.data_enum.shape.should_roundtrip_core_variants")
        self.assertEqual(demo.echo_shape(demo.ShapeCircle(2.0)), demo.ShapeCircle(2.0))
        self.assertEqual(demo.echo_shape(demo.ShapeRectangle(3.0, 4.0)), demo.ShapeRectangle(3.0, 4.0))
        self.assertEqual(demo.echo_shape(triangle), triangle)
        self.assertEqual(demo.echo_shape(demo.ShapePoint()), demo.ShapePoint())
        self.demo_case("case:enums.data_enum.shape.apex.should_roundtrip_some_point_payload")
        self.assertEqual(demo.echo_shape(demo.ShapeApex(demo.Point(3.0, 4.0))), demo.ShapeApex(demo.Point(3.0, 4.0)))
        self.demo_case("case:enums.data_enum.shape.apex.should_roundtrip_none_payload")
        self.assertEqual(demo.echo_shape(demo.ShapeApex(None)), demo.ShapeApex(None))
        self.demo_case("case:enums.data_enum.shape.should_roundtrip_vector_record_fields")
        self.assertEqual(demo.echo_shape(demo.ShapeCluster([demo.Point(1.0, 2.0)])), demo.ShapeCluster([demo.Point(1.0, 2.0)]))
        self.demo_case("case:enums.data_enum.shape.try_apex_point.should_return_some_for_positive_radius")
        self.assert_point(demo.Shape.try_apex_point(2.5), x=0.0, y=2.5)
        self.demo_case("case:enums.data_enum.shape.try_apex_point.should_return_none_for_non_positive_radius")
        self.assertIsNone(demo.Shape.try_apex_point(-1.0))
        self.demo_case("case:enums.data_enum.shape.should_roundtrip_vectors")
        self.assertEqual(
            demo.echo_vec_shape([demo.ShapeCircle(2.0), demo.ShapeRectangle(3.0, 4.0), demo.ShapePoint()]),
            [demo.ShapeCircle(2.0), demo.ShapeRectangle(3.0, 4.0), demo.ShapePoint()],
        )

    def test_message(self) -> None:
        text_message = demo.MessageText("hello")
        image_message = demo.MessageImage("https://example.com/image.png", 640, 480)

        self.demo_case("case:enums.data_enum.message.text.should_roundtrip_string_payload")
        self.assertEqual(demo.echo_message(text_message), text_message)
        self.demo_case("case:enums.data_enum.message.image.should_roundtrip_url_dimensions_payload")
        self.assertEqual(demo.echo_message(image_message), image_message)
        self.demo_case("case:enums.data_enum.message.ping.should_roundtrip_unit_variant")
        self.assertEqual(demo.echo_message(demo.MessagePing()), demo.MessagePing())
        self.demo_case("case:enums.data_enum.message.text.should_render_text_summary")
        self.assertEqual(demo.message_summary(demo.MessageText("hi")), "text: hi")
        self.demo_case("case:enums.data_enum.message.image.should_render_image_summary")
        self.assertEqual(demo.message_summary(image_message), "image: 640x480 at https://example.com/image.png")
        self.demo_case("case:enums.data_enum.message.ping.should_render_ping_summary")
        self.assertEqual(demo.message_summary(demo.MessagePing()), "ping")

    def test_animal(self) -> None:
        dog = demo.AnimalDog("Rex", "Labrador")
        cat = demo.AnimalCat("Milo", True)
        fish = demo.AnimalFish(5)

        self.demo_case("case:enums.data_enum.animal.dog.should_roundtrip_string_payloads")
        self.assertEqual(demo.echo_animal(dog), dog)
        self.demo_case("case:enums.data_enum.animal.cat.should_roundtrip_name_and_bool_payload")
        self.assertEqual(demo.echo_animal(cat), cat)
        self.demo_case("case:enums.data_enum.animal.fish.should_roundtrip_count_payload")
        self.assertEqual(demo.echo_animal(fish), fish)
        self.demo_case("case:enums.data_enum.animal.fish.should_derive_count_label")
        self.assertEqual(demo.animal_name(fish), "5 fish")
        self.demo_case("case:enums.data_enum.animal.dog.should_derive_name")
        self.assertEqual(demo.animal_name(dog), "Rex")
        self.demo_case("case:enums.data_enum.animal.cat.should_derive_name")
        self.assertEqual(demo.animal_name(cat), "Milo")

    def test_lifecycle_event(self) -> None:
        self.demo_case("case:enums.data_enum.lifecycle_event.should_make_critical_event")
        started = demo.make_critical_lifecycle_event(7)
        self.assertEqual(started, demo.LifecycleEventTaskStarted(demo.Priority.CRITICAL, 7))
        self.demo_case("case:enums.data_enum.lifecycle_event.should_roundtrip_priority_payload")
        self.assertEqual(demo.echo_lifecycle_event(started), started)
        self.demo_case("case:enums.data_enum.lifecycle_event.should_roundtrip_tick_variant")
        self.assertEqual(demo.echo_lifecycle_event(demo.LifecycleEventTick()), demo.LifecycleEventTick())
