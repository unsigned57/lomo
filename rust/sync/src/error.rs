//! Structured error helpers for the dark `lomo-sync` owner boundary.

use lomo_core::{ErrorCategory, LomoError, RetryDisposition};

/// Builds a validation error on the sync boundary.
#[must_use]
pub fn validation(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
        diagnostic,
    )
}

/// Builds a corruption / `CorruptState` error (never clean-slate).
#[must_use]
pub fn corrupt_state(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Corruption,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a resource-limit error (page/path/size ceilings).
#[must_use]
pub fn resource_limit(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::ResourceLimit,
        code,
        RetryDisposition::Never,
        diagnostic,
    )
}

/// Builds a storage I/O error for durable session/baseline trees.
#[must_use]
pub fn storage(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Storage,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a network transport error (retryable unless policy says otherwise).
#[must_use]
pub fn network(code: &str, diagnostic: &str, retry: RetryDisposition) -> LomoError {
    boundary(ErrorCategory::Network, code, retry, diagnostic)
}

/// Builds an authentication error (401 / missing credentials).
#[must_use]
pub fn authentication(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Authentication,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a permission error (403).
#[must_use]
pub fn permission(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Permission,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a conflict / precondition error (409 / 412 / concurrent change).
#[must_use]
pub fn conflict(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Conflict,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a busy / rate-limit error (423 / 429) — transient retry.
#[must_use]
pub fn busy(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Busy,
        code,
        RetryDisposition::Transient,
        diagnostic,
    )
}

fn boundary(
    category: ErrorCategory,
    code: &str,
    retry: RetryDisposition,
    diagnostic: &str,
) -> LomoError {
    LomoError::from_platform_boundary(category, code, retry, None, None, diagnostic)
        .unwrap_or_else(|error| error)
}
