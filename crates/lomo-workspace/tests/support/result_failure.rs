pub trait ResultFailureTestExt<E> {
    fn test_err(self, context: &str) -> E;
}

impl<T, E: std::fmt::Debug> ResultFailureTestExt<E> for Result<T, E> {
    fn test_err(self, context: &str) -> E {
        match self {
            Ok(_) => panic!("{context}: unexpectedly succeeded"),
            Err(error) => error,
        }
    }
}
