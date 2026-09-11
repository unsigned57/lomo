//! HTTP status → structured `LomoError` mapping for `WebDAV` responses.

use crate::error::{authentication, busy, conflict, network, permission, validation};
use lomo_core::{LomoError, RetryDisposition};

/// Maps a `WebDAV` / HTTP status code to a stable structured error.
///
/// Categories follow the Stage-5 retry policy:
/// - 401 → Authentication / `AfterUserAction`
/// - 403 → Permission / `AfterUserAction`
/// - 404 → Validation (not found) / Never
/// - 409 → Conflict / `AfterUserAction`
/// - 412 → Conflict (precondition) / `AfterUserAction`
/// - 423 → Busy (locked) / Transient
/// - 429 → Busy (rate limit) / Transient
/// - 3xx (including 302 with `Policy::none`) → Network / `AfterUserAction` (non-success; no credential follow)
/// - 5xx → Network / Transient
/// - other 4xx → Network / `AfterUserAction`
///
/// Path-level publish results only carry stable error **codes** (`PathPublishStatus::Failed`).
/// Category and `RetryDisposition` are asserted wherever a full [`LomoError`] surfaces
/// (transport boundary, preflight, GET/list failures). Core owns retry scheduling policy.
#[must_use]
pub fn map_http_status(method: &str, status: u16) -> LomoError {
    let diagnostic = format!("webdav {method} returned HTTP {status}");
    match status {
        401 => authentication("webdav_unauthorized", &diagnostic),
        403 => permission("webdav_forbidden", &diagnostic),
        404 => validation("webdav_not_found", &diagnostic),
        409 => conflict("webdav_conflict", &diagnostic),
        412 => conflict("webdav_precondition_failed", &diagnostic),
        423 => busy("webdav_locked", &diagnostic),
        429 => busy("webdav_rate_limited", &diagnostic),
        300..=399 => network(
            "webdav_redirect_not_followed",
            &diagnostic,
            RetryDisposition::AfterUserAction,
        ),
        500..=599 => network(
            "webdav_server_error",
            &diagnostic,
            RetryDisposition::Transient,
        ),
        _ => network(
            "webdav_http_error",
            &diagnostic,
            RetryDisposition::AfterUserAction,
        ),
    }
}
