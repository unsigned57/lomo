from tests.support import DemoTestCase

import demo


class ComplexVariantTests(DemoTestCase):
    def test_filter_variants(self) -> None:
        name_filter = demo.FilterByName("ali")
        point_filter = demo.FilterByPoints([demo.Point(0.0, 0.0), demo.Point(1.0, 1.0)])

        self.demo_case("case:enums.complex_variants.filter.none.should_roundtrip_unit_variant")
        self.assertEqual(demo.echo_filter(demo.FilterNone()), demo.FilterNone())
        self.demo_case("case:enums.complex_variants.filter.by_name.should_roundtrip_string_payload")
        self.assertEqual(demo.echo_filter(name_filter), name_filter)
        self.demo_case("case:enums.complex_variants.filter.by_tags.should_roundtrip_string_vector_payload")
        tag_filter = demo.FilterByTags(["ffi", "jni", "café"])
        self.assertEqual(demo.echo_filter(tag_filter), tag_filter)
        self.demo_case("case:enums.complex_variants.filter.by_groups.should_roundtrip_nested_string_vectors")
        group_filter = demo.FilterByGroups([["ffi", "jni"], [], ["café"]])
        self.assertEqual(demo.echo_filter(group_filter), group_filter)
        self.demo_case("case:enums.complex_variants.filter.by_points.should_roundtrip_record_vector_payload")
        self.assertEqual(demo.echo_filter(point_filter), point_filter)
        self.demo_case("case:enums.complex_variants.filter.by_name.should_describe_string_payload")
        self.assertEqual(demo.describe_filter(name_filter), "filter by name: ali")
        self.demo_case("case:enums.complex_variants.filter.by_points.should_describe_record_vector_payload")
        self.assertEqual(demo.describe_filter(point_filter), "filter by 2 anchor points")
        self.demo_case("case:enums.complex_variants.filter.by_tags.should_describe_string_vector_payload")
        self.assertEqual(demo.describe_filter(demo.FilterByTags(["ffi", "jni"])), "filter by 2 tags")
        self.demo_case("case:enums.complex_variants.filter.by_groups.should_describe_nested_string_vectors")
        self.assertEqual(demo.describe_filter(group_filter), "filter by 3 groups")
        self.demo_case("case:enums.complex_variants.filter.by_range.should_describe_numeric_bounds")
        self.assertEqual(demo.describe_filter(demo.FilterByRange(1.0, 5.0)), "filter by range: 1..5")

    def test_api_response_variants(self) -> None:
        success = demo.ApiResponseSuccess("ok")
        redirect = demo.ApiResponseRedirect("https://example.com")

        self.demo_case("case:enums.complex_variants.api_response.success.should_roundtrip_string_payload")
        self.assertEqual(demo.echo_api_response(success), success)
        self.demo_case("case:enums.complex_variants.api_response.redirect.should_roundtrip_url_payload")
        self.assertEqual(demo.echo_api_response(redirect), redirect)
        self.demo_case("case:enums.complex_variants.api_response.success.should_identify_success")
        self.assertIs(demo.is_success(success), True)
        self.demo_case("case:enums.complex_variants.api_response.empty.should_not_identify_as_success")
        self.assertIs(demo.is_success(demo.ApiResponseEmpty()), False)
