//! Structured error helpers for the dark `lomo-git` adapter boundary.

use lomo_core::{ErrorCategory, LomoError, RetryDisposition};

/// Builds a validation error on the git adapter boundary.
#[must_use]
pub fn validation(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
        diagnostic,
    )
}

/// Builds a storage I/O error for local bare-mirror / lock paths.
#[must_use]
pub fn storage(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Storage,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a network / transport error (fetch/push).
#[must_use]
pub fn network(code: &str, diagnostic: &str, retry: RetryDisposition) -> LomoError {
    boundary(ErrorCategory::Network, code, retry, diagnostic)
}

/// Builds an authentication error (missing/invalid credentials).
#[must_use]
pub fn authentication(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Authentication,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a permission error.
#[must_use]
pub fn permission(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Permission,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a conflict / precondition error (non-fast-forward, CAS reject).
#[must_use]
pub fn conflict(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Conflict,
        code,
        RetryDisposition::AfterUserAction,
        diagnostic,
    )
}

/// Builds a busy error (lock held by live owner).
#[must_use]
pub fn busy(code: &str, diagnostic: &str) -> LomoError {
    boundary(
        ErrorCategory::Busy,
        code,
        RetryDisposition::Transient,
        diagnostic,
    )
}

/// Maps a `git2::Error` into a redacted boundary `LomoError`.
#[must_use]
pub fn from_git2(code: &str, error: &git2::Error) -> LomoError {
    let raw = error.message();
    let diagnostic = crate::redaction::redact_diagnostic(raw);
    let (category, retry) = classify_git2(error);
    boundary(category, code, retry, &diagnostic)
}

fn classify_git2(error: &git2::Error) -> (ErrorCategory, RetryDisposition) {
    if error.code() == git2::ErrorCode::NotFastForward {
        return (ErrorCategory::Conflict, RetryDisposition::AfterUserAction);
    }
    match error.class() {
        git2::ErrorClass::Net | git2::ErrorClass::Http | git2::ErrorClass::Ssh => {
            (ErrorCategory::Network, RetryDisposition::Transient)
        }
        git2::ErrorClass::Ssl => (ErrorCategory::Network, RetryDisposition::AfterUserAction),
        git2::ErrorClass::Checkout
        | git2::ErrorClass::Merge
        | git2::ErrorClass::Rebase
        | git2::ErrorClass::Reference
        | git2::ErrorClass::Revert
        | git2::ErrorClass::CherryPick => {
            (ErrorCategory::Conflict, RetryDisposition::AfterUserAction)
        }
        git2::ErrorClass::Filesystem | git2::ErrorClass::Os => {
            (ErrorCategory::Storage, RetryDisposition::AfterUserAction)
        }
        git2::ErrorClass::None
        | git2::ErrorClass::NoMemory
        | git2::ErrorClass::Invalid
        | git2::ErrorClass::Zlib
        | git2::ErrorClass::Repository
        | git2::ErrorClass::Config
        | git2::ErrorClass::Regex
        | git2::ErrorClass::Odb
        | git2::ErrorClass::Index
        | git2::ErrorClass::Object
        | git2::ErrorClass::Tag
        | git2::ErrorClass::Tree
        | git2::ErrorClass::Indexer
        | git2::ErrorClass::Submodule
        | git2::ErrorClass::Thread
        | git2::ErrorClass::Stash
        | git2::ErrorClass::FetchHead
        | git2::ErrorClass::Filter
        | git2::ErrorClass::Callback
        | git2::ErrorClass::Describe
        | git2::ErrorClass::Patch
        | git2::ErrorClass::Worktree
        | git2::ErrorClass::Sha1 => (ErrorCategory::Validation, RetryDisposition::Never),
    }
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
