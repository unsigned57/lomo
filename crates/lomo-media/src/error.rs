//! Structured media errors mapped through `lomo-core::LomoError`.

use lomo_core::{ErrorCategory, LomoError, RetryDisposition};

/// Builds a validation error on the media boundary.
#[must_use]
pub fn validation(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
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

/// Builds a corruption error.
#[must_use]
pub fn corruption(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Corruption,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a conflict error.
#[must_use]
pub fn conflict(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Conflict,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
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
