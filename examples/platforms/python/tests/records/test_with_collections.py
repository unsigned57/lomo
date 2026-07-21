import math

from tests.support import DemoTestCase

import demo


class CollectionRecordTests(DemoTestCase):
    def assert_point(self, point: demo.Point, *, x: float, y: float, tolerance: float = 1e-12) -> None:
        self.assertTrue(math.isclose(point.x, x, rel_tol=0.0, abs_tol=tolerance))
        self.assertTrue(math.isclose(point.y, y, rel_tol=0.0, abs_tol=tolerance))

    def test_polygon(self) -> None:
        self.demo_case("case:records.with_collections.polygon.should_make_from_points")
        polygon = demo.make_polygon([demo.Point(0.0, 0.0), demo.Point(1.0, 0.0), demo.Point(0.0, 1.0)])
        self.assertEqual(polygon, demo.Polygon([demo.Point(0.0, 0.0), demo.Point(1.0, 0.0), demo.Point(0.0, 1.0)]))
        self.demo_case("case:records.with_collections.polygon.should_roundtrip_point_vector")
        self.assertEqual(demo.echo_polygon(polygon), polygon)
        self.demo_case("case:records.with_collections.polygon.should_report_vertex_count")
        self.assertEqual(demo.polygon_vertex_count(polygon), 3)
        self.demo_case("case:records.with_collections.polygon.should_compute_centroid")
        self.assert_point(demo.polygon_centroid(polygon), x=1.0 / 3.0, y=1.0 / 3.0, tolerance=1e-6)

    def test_team(self) -> None:
        self.demo_case("case:records.with_collections.team.should_make_from_members")
        team = demo.make_team("devs", ["Ali", "Mia"])
        self.assertEqual(team, demo.Team("devs", ["Ali", "Mia"]))
        self.demo_case("case:records.with_collections.team.should_roundtrip_member_vector")
        self.assertEqual(demo.echo_team(team), team)
        self.demo_case("case:records.with_collections.team.should_report_member_count")
        self.assertEqual(demo.team_size(team), 2)

    def test_classroom(self) -> None:
        self.demo_case("case:records.with_collections.classroom.should_make_from_students")
        classroom = demo.make_classroom([demo.Person("Mia", 10), demo.Person("Leo", 11)])
        self.assertEqual(classroom, demo.Classroom([demo.Person("Mia", 10), demo.Person("Leo", 11)]))
        self.demo_case("case:records.with_collections.classroom.should_roundtrip_student_vector")
        self.assertEqual(demo.echo_classroom(classroom), classroom)

    def test_tagged_scores(self) -> None:
        tagged_scores = demo.TaggedScores("math", [90.0, 85.5])

        self.demo_case("case:records.with_collections.tagged_scores.should_roundtrip_score_vector")
        self.assertEqual(demo.echo_tagged_scores(tagged_scores), tagged_scores)
        self.demo_case("case:records.with_collections.tagged_scores.should_average_scores")
        self.assertTrue(math.isclose(demo.average_score(demo.TaggedScores("x", [80.0, 100.0])), 90.0, rel_tol=0.0, abs_tol=1e-12))

    def test_user_profiles(self) -> None:
        self.demo_case("case:records.with_collections.user_profiles.should_generate_profiles")
        profiles = demo.generate_user_profiles(4)
        self.assertEqual(len(profiles), 4)
        self.assertEqual(profiles[0].id, 0)
        self.assertEqual(profiles[3].id, 3)
        self.demo_case("case:records.with_collections.user_profiles.should_sum_scores")
        expected_score_sum = sum(profile.score for profile in profiles)
        self.assertTrue(math.isclose(demo.sum_user_scores(profiles), expected_score_sum, rel_tol=0.0, abs_tol=1e-4))
        self.demo_case("case:records.with_collections.user_profiles.should_count_active_users")
        self.assertEqual(demo.count_active_users(profiles), sum(1 for profile in profiles if profile.is_active))
