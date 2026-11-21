pub trait OptionTestExt<T> {
    fn test_ok(self, context: &str) -> T;
}

impl<T> OptionTestExt<T> for Option<T> {
    fn test_ok(self, context: &str) -> T {
        self.unwrap_or_else(|| panic!("{context}: value was absent"))
    }
}
