from tests.support import DemoTestCase

import demo


class ConstructorMatrixTests(DemoTestCase):
    def test_borrowed_points_constructor(self) -> None:
        self.demo_case("case:classes.constructor_matrix.with_borrowed_points.should_accept_borrowed_blittable_slice")
        matrix = demo.ConstructorCoverageMatrix.with_borrowed_points(
            "borrowed",
            [demo.Point(2.0, 3.0), demo.Point(4.0, 5.0)],
        )
        self.assertEqual(matrix.constructor_variant(), "with_borrowed_points")
        self.assertEqual(matrix.summary(), "label=borrowed;points=2;first=2.0:3.0")
        self.assertEqual(matrix.vector_count(), 2)

    def test_borrowed_people_constructor(self) -> None:
        self.demo_case("case:classes.constructor_matrix.with_borrowed_people.should_accept_borrowed_encoded_record_slice")
        matrix = demo.ConstructorCoverageMatrix.with_borrowed_people(
            [demo.Person("Ada", 40), demo.Person("Grace", 41)],
        )
        self.assertEqual(matrix.constructor_variant(), "with_borrowed_people")
        self.assertEqual(matrix.summary(), "people=2;age_total=81;names=Ada|Grace")
