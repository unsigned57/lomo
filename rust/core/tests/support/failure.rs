use std::fmt::Debug;

pub trait ResultFailureTestExt<E> {
    fn must_fail(self, context: &str) -> E;
}

impl<T, E: Debug> ResultFailureTestExt<E> for Result<T, E> {
    fn must_fail(self, context: &str) -> E {
        match self {
            Ok(_) => panic!("{context}: unexpectedly succeeded"),
            Err(error) => error,
        }
    }
}
