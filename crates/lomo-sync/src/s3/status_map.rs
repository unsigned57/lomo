//! HTTP status → structured `LomoError` mapping for S3 responses.

use crate::error::{authentication, busy, conflict, network, permission, validation};
use lomo_core::{LomoError, RetryDisposition};

/// Maps an S3 / HTTP status code to a stable structured error.
///
/// Categories follow the Stage-5 retry policy (mirrors `WebDAV` host matrix):
/// - 401 / 403 → Authentication / Permission + `AfterUserAction`
/// - 404 → Validation / Never
/// - 409 / 412 → Conflict / `AfterUserAction` (precondition / concurrent change)
/// - 429 → Busy / Transient
/// - 3xx → Network / `AfterUserAction` (redirect policy is `none`)
/// - 5xx → Network / Transient
///
/// Path-level publish results only carry stable error **codes**. Category and
/// `RetryDisposition` are asserted wherever a full [`LomoError`] surfaces.
#[must_use]
pub fn map_s3_http_status(method: &str, status: u16) -> LomoError {
    let diagnostic = format!("s3 {method} returned HTTP {status}");
    match status {
        401 => authentication("s3_unauthorized", &diagnostic),
        403 => permission("s3_forbidden", &diagnostic),
        404 => validation("s3_not_found", &diagnostic),
        409 => conflict("s3_conflict", &diagnostic),
        412 => conflict("s3_precondition_failed", &diagnostic),
        429 => busy("s3_rate_limited", &diagnostic),
        300..=399 => network(
            "s3_redirect_not_followed",
            &diagnostic,
            RetryDisposition::AfterUserAction,
        ),
        500..=599 => network("s3_server_error", &diagnostic, RetryDisposition::Transient),
        _ => network(
            "s3_http_error",
            &diagnostic,
            RetryDisposition::AfterUserAction,
        ),
    }
}
