from tests.support import DemoTestCase

import demo


class ConstructorTests(DemoTestCase):
    def test_fallible_inventory_constructor(self) -> None:
        self.demo_case("case:classes.constructors.inventory.try_new.should_return_inventory_for_positive_capacity")
        inventory = demo.Inventory.try_new(1)
        self.assertEqual(inventory.capacity(), 1)
        self.assertIs(inventory.add("only"), True)
        self.assertIs(inventory.add("overflow"), False)

        self.demo_case("case:classes.constructors.inventory.try_new.should_reject_zero_capacity")
        with self.assertRaises(RuntimeError) as error:
            demo.Inventory.try_new(0)
        self.assertIn("capacity must be greater than zero", str(error.exception))
