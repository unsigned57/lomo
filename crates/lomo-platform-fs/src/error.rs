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

/// The watched root cannot currently be observed — a transient outage the
/// consumer may bounded-retry before declaring the watch dead. The inner
/// failure's diagnostic is preserved inside the message.
pub fn watch_root_lost(diagnostic: &str) -> LomoError {
    LomoError::from_platform_boundary(
        ErrorCategory::Storage,
        crate::watcher::WATCH_ROOT_LOST_CODE,
        RetryDisposition::Transient,
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
