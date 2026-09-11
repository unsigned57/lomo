//! Structured error helpers for the `lomo-lan` boundary.
//!
//! Every rejection is a typed `LomoError` with a stable code. LAN failures never degrade into empty
//! collections, `Ok(())`, or message-string matching, and never carry key material or memo bodies.

use lomo_core::{ErrorCategory, LomoError, RetryDisposition};

/// Builds a validation error (malformed frame, bad identity, unknown version).
#[must_use]
pub fn validation(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
        diagnostic,
    )
}

/// Builds a resource-limit error (frame, batch, attachment or page ceiling).
#[must_use]
pub fn resource_limit(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::ResourceLimit,
        code,
        RetryDisposition::Never,
        diagnostic,
    )
}

/// Builds an authentication error (unpaired, revoked, signature or transcript mismatch).
#[must_use]
pub fn authentication(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Authentication,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a permission error (approval missing or expired).
#[must_use]
pub fn permission(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Permission,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a network transport error.
#[must_use]
pub fn network(code: &str, diagnostic: &str, retry: RetryDisposition) -> LomoError {
    boundary(ErrorCategory::Network, code, retry, diagnostic)
}

/// Builds a storage error for the durable LAN journal.
#[must_use]
pub fn storage(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Storage,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a corruption error for a damaged durable LAN record (never clean-slate).
#[must_use]
pub fn corrupt_state(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Corruption,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a conflict error (generation fence, stale session revision).
#[must_use]
pub fn conflict(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Conflict,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a cancelled error (peer revoked mid-session, user cancel, shutdown).
#[must_use]
pub fn cancelled(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Cancelled,
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
    LomoError::from_platform_boundary(category, code, retry, None, None, diagnostic)
        .unwrap_or_else(|error| error)
}
