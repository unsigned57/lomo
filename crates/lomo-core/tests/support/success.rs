use std::fmt::Debug;

pub trait ResultTestExt<T> {
    fn must_succeed(self, context: &str) -> T;
}

impl<T, E: Debug> ResultTestExt<T> for Result<T, E> {
    fn must_succeed(self, context: &str) -> T {
        match self {
            Ok(value) => value,
            Err(error) => panic!("{context}: {error:?}"),
        }
    }
}
