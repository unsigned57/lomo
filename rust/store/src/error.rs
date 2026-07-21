//! Structured store errors mapped through `lomo-core::LomoError`.

use lomo_core::{ErrorCategory, LomoError, RetryDisposition};

/// Builds a validation error on the store boundary.
#[must_use]
pub fn validation(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
        diagnostic,
    )
}

/// Builds a corruption error (durable record or index damage).
#[must_use]
pub fn corruption(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Corruption,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a storage I/O error.
#[must_use]
pub fn storage(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Storage,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a busy/conflict error (rebuild active, lock, etc.).
#[must_use]
pub fn busy(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Busy,
        code,
        RetryDisposition::Transient,
        diagnostic,
    )
}

/// Builds a resource-limit error.
#[must_use]
pub fn resource_limit(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::ResourceLimit,
        code,
        RetryDisposition::Never,
        diagnostic,
    )
}

/// Builds a conflict error (`stale_snapshot`, concurrent writers).
#[must_use]
pub fn conflict(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Conflict,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Maps a `rusqlite` error without swallowing structured detail.
#[must_use]
pub fn from_sqlite(error: &rusqlite::Error) -> LomoError {
    storage("sqlite_error", &error.to_string())
}

fn boundary(
    category: ErrorCategory,
    code: &str,
    retry: RetryDisposition,
    diagnostic: &str,
) -> LomoError {
    match LomoError::from_platform_boundary(category, code, retry, None, None, diagnostic) {
        Ok(error) | Err(error) => error,
    }
}
