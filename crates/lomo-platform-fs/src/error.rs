use lomo_core::{ErrorCategory, LomoError, RetryDisposition};

pub fn permission(code: &str, diagnostic: &str) -> LomoError {
    LomoError::from_platform_boundary(
        ErrorCategory::Permission,
        code,
        RetryDisposition::AfterUserAction,
        None,
        None,
        diagnostic,
    )
    .unwrap_or_else(|error| error)
}

pub fn storage(code: &str, diagnostic: &str) -> LomoError {
    LomoError::from_platform_boundary(
        ErrorCategory::Storage,
        code,
        RetryDisposition::AfterUserAction,
        None,
        None,
        diagnostic,
    )
    .unwrap_or_else(|error| error)
}

pub fn conflict(code: &str, diagnostic: &str) -> LomoError {
    LomoError::from_platform_boundary(
        ErrorCategory::Conflict,
        code,
        RetryDisposition::AfterUserAction,
        None,
        None,
        diagnostic,
    )
    .unwrap_or_else(|error| error)
}

pub fn validation(code: &str, diagnostic: &str) -> LomoError {
    LomoError::from_platform_boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
        None,
        None,
        diagnostic,
    )
    .unwrap_or_else(|error| error)
}

pub fn timeout(code: &str, diagnostic: &str) -> LomoError {
    LomoError::from_platform_boundary(
        ErrorCategory::Timeout,
        code,
        RetryDisposition::Transient,
        None,
        None,
        diagnostic,
    )
    .unwrap_or_else(|error| error)
}

pub fn internal(code: &str, diagnostic: &str) -> LomoError {
    LomoError::from_platform_boundary(
        ErrorCategory::Internal,
        code,
        RetryDisposition::Never,
        None,
        None,
        diagnostic,
    )
    .unwrap_or_else(|error| error)
}
