//! Test-only failure helpers for integration contracts.
//!
//! They preserve an explicit scenario message without using `unwrap` / `expect`, which are denied
//! across all targets by the workspace governance lints.

pub trait ResultTestExt<T, E> {
    fn test_ok(self, context: &str) -> T;

    fn test_err(self, context: &str) -> E;
}

impl<T, E> ResultTestExt<T, E> for Result<T, E>
where
    E: std::fmt::Debug,
{
    fn test_ok(self, context: &str) -> T {
        match self {
            Ok(value) => value,
            Err(error) => panic!("{context}: {error:?}"),
        }
    }

    fn test_err(self, context: &str) -> E {
        match self {
            Ok(_) => panic!("{context}: unexpectedly succeeded"),
            Err(error) => error,
        }
    }
}

pub trait OptionTestExt<T> {
    fn test_ok(self, context: &str) -> T;
}

impl<T> OptionTestExt<T> for Option<T> {
    fn test_ok(self, context: &str) -> T {
        self.unwrap_or_else(|| panic!("{context}: value was absent"))
    }
}

#[test]
fn result_helpers_preserve_success_and_failure_values() {
    assert_eq!(Ok::<u8, &str>(7).test_ok("ok"), 7);
    assert_eq!(Err::<u8, &str>("failure").test_err("err"), "failure");
}

#[test]
fn option_helper_preserves_present_values() {
    assert_eq!(Some(7_u8).test_ok("some"), 7);
}
