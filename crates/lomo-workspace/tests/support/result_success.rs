pub trait ResultTestExt<T> {
    fn test_ok(self, context: &str) -> T;
}

impl<T, E: std::fmt::Debug> ResultTestExt<T> for Result<T, E> {
    fn test_ok(self, context: &str) -> T {
        match self {
            Ok(value) => value,
            Err(error) => panic!("{context}: {error:?}"),
        }
    }
}
