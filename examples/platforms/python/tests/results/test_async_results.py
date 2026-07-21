from tests.support import AsyncDemoTestCase

import demo


class AsyncResultTests(AsyncDemoTestCase):
    async def assert_runtime_error_contains(self, expected: str, call) -> None:
        with self.assertRaises(RuntimeError) as error:
            await call()
        self.assertIn(expected, str(error.exception))

    async def test_fallible_fetch(self) -> None:
        self.demo_case("case:results.async_results.fallible_fetch.should_return_value_for_non_negative_key")
        self.assertEqual(await demo.async_fallible_fetch(7), "value_7")
        self.demo_case("case:results.async_results.fallible_fetch.should_reject_negative_key")
        await self.assert_runtime_error_contains("invalid key", lambda: demo.async_fallible_fetch(-1))

    async def test_safe_divide(self) -> None:
        self.demo_case("case:results.async_results.safe_divide.should_return_quotient")
        self.assertEqual(await demo.async_safe_divide(10, 2), 5)
        self.demo_case("case:results.async_results.safe_divide.should_reject_division_by_zero")
        await self.assert_runtime_error_value(
            demo.MathError.DIVISION_BY_ZERO,
            lambda: demo.async_safe_divide(10, 0),
        )

    async def test_find_value(self) -> None:
        self.demo_case("case:results.async_results.find_value.should_return_some_for_positive_key")
        self.assertEqual(await demo.async_find_value(4), 40)
        self.demo_case("case:results.async_results.find_value.should_return_none_for_zero_key")
        self.assertIsNone(await demo.async_find_value(0))
        self.demo_case("case:results.async_results.find_value.should_reject_negative_key")
        await self.assert_runtime_error_contains("invalid key", lambda: demo.async_find_value(-1))
