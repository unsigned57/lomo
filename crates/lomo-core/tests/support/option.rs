pub trait OptionTestExt<T> {
    fn must_succeed(self, context: &str) -> T;
}

impl<T> OptionTestExt<T> for Option<T> {
    fn must_succeed(self, context: &str) -> T {
        let Some(value) = self else {
            panic!("{context}: option was None");
        };
        value
    }
}
