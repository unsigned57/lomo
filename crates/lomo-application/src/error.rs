use lomo_core::{ErrorCategory, LomoError, RetryDisposition};

#[must_use]
pub fn cancelled(code: &str, diagnostic: &str) -> LomoError {
    LomoError::from_platform_boundary(
        ErrorCategory::Cancelled,
        code,
        RetryDisposition::Never,
        None,
        None,
        diagnostic,
    )
    .unwrap_or_else(|error| error)
}

pub fn validation(code: &str, diagnostic: impl Into<String>) -> LomoError {
    let diag = diagnostic.into();
    LomoError::from_platform_boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
        None,
        None,
        &diag,
    )
    .unwrap_or_else(|error| error)
}

pub fn conflict(code: &str, diagnostic: impl Into<String>) -> LomoError {
    let diag = diagnostic.into();
    LomoError::from_platform_boundary(
        ErrorCategory::Conflict,
        code,
        RetryDisposition::AfterUserAction,
        None,
        None,
        &diag,
    )
    .unwrap_or_else(|error| error)
}

pub fn storage(code: &str, diagnostic: impl Into<String>) -> LomoError {
    let diag = diagnostic.into();
    LomoError::from_platform_boundary(
        ErrorCategory::Storage,
        code,
        RetryDisposition::AfterUserAction,
        None,
        None,
        &diag,
    )
    .unwrap_or_else(|error| error)
}

pub fn corruption(code: &str, diagnostic: impl Into<String>) -> LomoError {
    let diag = diagnostic.into();
    LomoError::from_platform_boundary(
        ErrorCategory::Corruption,
        code,
        RetryDisposition::Never,
        None,
        None,
        &diag,
    )
    .unwrap_or_else(|error| error)
}

pub fn permission(code: &str, diagnostic: impl Into<String>) -> LomoError {
    let diag = diagnostic.into();
    LomoError::from_platform_boundary(
        ErrorCategory::Permission,
        code,
        RetryDisposition::AfterUserAction,
        None,
        None,
        &diag,
    )
    .unwrap_or_else(|error| error)
}
