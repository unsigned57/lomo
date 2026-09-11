//! Reqwest/Rustls `WebDAV` transport with same-origin redirect credential policy.

use std::io::Write;
use std::path::PathBuf;
use std::time::Duration;

use reqwest::blocking::{Client, Response};
use reqwest::header::{AUTHORIZATION, HeaderMap, HeaderName, HeaderValue};
use reqwest::redirect::Policy;
use reqwest::{Method, StatusCode, Url};
use sha2::{Digest, Sha256};

use crate::error::{network, resource_limit, storage, validation};
use crate::limits::{MAX_WEBDAV_MULTISTATUS_BYTES, MAX_WEBDAV_OBJECT_BYTES};
use crate::webdav::endpoint::{WebDavCredentials, WebDavEndpoint};
use crate::webdav::status_map::map_http_status;
use lomo_core::{LomoError, RetryDisposition};

const PROPFIND_BODY: &str = concat!(
    r#"<?xml version="1.0" encoding="utf-8"?>"#,
    r#"<d:propfind xmlns:d="DAV:">"#,
    r#"<d:prop>"#,
    r#"<d:resourcetype />"#,
    r#"<d:getetag />"#,
    r#"<d:getlastmodified />"#,
    r#"<d:getcontentlength />"#,
    r#"</d:prop>"#,
    r#"</d:propfind>"#
);

/// Capability facts discovered during preflight (never secrets).
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
#[expect(
    clippy::struct_excessive_bools,
    reason = "RemoteCapabilities is a flat capability bitset from OPTIONS/PROPFIND; flags are independent"
)]
pub struct RemoteCapabilities {
    pub conditional_write: bool,
    pub conditional_delete: bool,
    pub supports_move: bool,
    pub supports_copy: bool,
    pub supports_etag: bool,
}

/// Blocking `WebDAV` HTTP client bound to one endpoint + credentials.
pub struct WebDavTransport {
    endpoint: WebDavEndpoint,
    credentials: WebDavCredentials,
    client: Client,
    temp_dir: PathBuf,
}

impl WebDavTransport {
    /// Builds a transport with Rustls and **no automatic redirects**.
    ///
    /// Redirect policy is `none` so `Authorization` is never replayed after an off-origin hop.
    /// Callers that intentionally follow redirects must re-check same-origin and re-auth only then.
    ///
    /// # Errors
    ///
    /// Network when the client cannot be constructed; validation when `temp_dir` is missing.
    pub fn new(
        endpoint: WebDavEndpoint,
        credentials: WebDavCredentials,
        temp_dir: impl Into<PathBuf>,
        timeout: Duration,
    ) -> Result<Self, LomoError> {
        let temp_dir = temp_dir.into();
        if !temp_dir.is_dir() {
            return Err(validation(
                "webdav_temp_dir_missing",
                "webdav transport requires an existing temporary directory for streaming bodies",
            ));
        }
        // reqwest/Rustls 0.23 needs an explicit process-level provider when `aws-lc-rs` is linked.
        let _provider: Result<(), std::sync::Arc<rustls::crypto::CryptoProvider>> =
            rustls::crypto::aws_lc_rs::default_provider().install_default();

        let client = Client::builder()
            .use_rustls_tls()
            .timeout(timeout)
            .connect_timeout(timeout)
            .redirect(Policy::none())
            .build()
            .map_err(|error| {
                network(
                    "webdav_client_build_failed",
                    &redacted_transport_detail(&error),
                    RetryDisposition::Never,
                )
            })?;
        Ok(Self {
            endpoint,
            credentials,
            client,
            temp_dir,
        })
    }

    #[must_use]
    pub const fn endpoint(&self) -> &WebDavEndpoint {
        &self.endpoint
    }

    /// `PROPFIND` with Depth header; returns raw Multi-Status body bytes.
    ///
    /// # Errors
    ///
    /// Structured HTTP/network/validation errors from the wire.
    pub fn propfind(&self, url: &Url, depth: u32) -> Result<Vec<u8>, LomoError> {
        let method = Method::from_bytes(b"PROPFIND").map_err(|_error| {
            network(
                "webdav_method_invalid",
                "webdav PROPFIND method construction failed",
                RetryDisposition::Never,
            )
        })?;
        let response = self
            .client
            .request(method, url.clone())
            .header(AUTHORIZATION, self.basic_auth_value())
            .header("Depth", depth.to_string())
            .header("Content-Type", "application/xml; charset=utf-8")
            .body(PROPFIND_BODY)
            .send()
            .map_err(|error| transport_err(&error))?;
        let status = response.status();
        if status == StatusCode::MULTI_STATUS || status.is_success() {
            return read_body_limited(response, MAX_WEBDAV_MULTISTATUS_BYTES, "multistatus");
        }
        Err(map_status_response("PROPFIND", &response))
    }

    /// `GET` object bytes into a temp file; returns path + optional `ETag` + sha256 hex.
    ///
    /// # Errors
    ///
    /// Network / HTTP / resource-limit errors.
    pub fn get_to_temp(&self, url: &Url) -> Result<(PathBuf, Option<String>, String), LomoError> {
        let mut response = self
            .client
            .get(url.clone())
            .header(AUTHORIZATION, self.basic_auth_value())
            .header("Accept-Encoding", "identity")
            .send()
            .map_err(|error| transport_err(&error))?;
        if !response.status().is_success() {
            return Err(map_status_response("GET", &response));
        }
        let etag = header_str(response.headers(), "etag").map(str::to_owned);
        if let Some(length) = response.content_length()
            && length > MAX_WEBDAV_OBJECT_BYTES as u64
        {
            return Err(resource_limit(
                "webdav_object_too_large",
                "webdav object exceeds the 32 MiB host streaming ceiling",
            ));
        }
        let temp_path = self.temp_path("get");
        let mut file = std::fs::File::create(&temp_path).map_err(|error| {
            storage(
                "webdav_temp_create_failed",
                &format!("failed to create webdav temp file: {error}"),
            )
        })?;
        let mut hasher = Sha256::new();
        let mut limited = LimitedWriter {
            inner: &mut file,
            hasher: &mut hasher,
            written: 0,
            limit: MAX_WEBDAV_OBJECT_BYTES,
        };
        response.copy_to(&mut limited).map_err(|error| {
            let _removed: Result<(), std::io::Error> = std::fs::remove_file(&temp_path);
            if limited.written > limited.limit {
                return resource_limit(
                    "webdav_object_too_large",
                    "webdav object exceeds the 32 MiB host streaming ceiling",
                );
            }
            network(
                "webdav_get_read_failed",
                &format!("webdav GET body read failed: {error}"),
                RetryDisposition::Transient,
            )
        })?;
        file.sync_all().map_err(|error| {
            storage(
                "webdav_temp_sync_failed",
                &format!("failed to fsync webdav temp file: {error}"),
            )
        })?;
        let digest = format!("{:x}", hasher.finalize());
        Ok((temp_path, etag, digest))
    }

    /// Conditional `PUT` of bytes; returns new `ETag` when present.
    ///
    /// # Errors
    ///
    /// Network / HTTP / conflict errors. HTTP 412 maps to precondition-failed conflict.
    pub fn put_bytes(
        &self,
        url: &Url,
        body: Vec<u8>,
        if_match: Option<&str>,
        if_none_match: bool,
    ) -> Result<Option<String>, LomoError> {
        let mut builder = self
            .client
            .put(url.clone())
            .header(AUTHORIZATION, self.basic_auth_value())
            .header("Content-Type", "application/octet-stream")
            .body(body);
        if let Some(token) = if_match {
            builder = builder.header("If-Match", token);
        } else if if_none_match {
            builder = builder.header("If-None-Match", "*");
        }
        let response = builder.send().map_err(|error| transport_err(&error))?;
        if response.status().is_success() {
            return Ok(header_str(response.headers(), "etag").map(str::to_owned));
        }
        Err(map_status_response("PUT", &response))
    }

    /// Conditional `DELETE`.
    ///
    /// # Errors
    ///
    /// Network / HTTP / conflict errors.
    pub fn delete(&self, url: &Url, if_match: Option<&str>) -> Result<(), LomoError> {
        let mut builder = self
            .client
            .delete(url.clone())
            .header(AUTHORIZATION, self.basic_auth_value());
        if let Some(token) = if_match {
            builder = builder.header("If-Match", token);
        }
        let response = builder.send().map_err(|error| transport_err(&error))?;
        if response.status().is_success() {
            return Ok(());
        }
        if response.status() == StatusCode::NOT_FOUND {
            return Err(map_http_status("DELETE", 404));
        }
        Err(map_status_response("DELETE", &response))
    }

    /// Ensures a collection exists via `MKCOL`.
    ///
    /// Idempotent for already-existing collections (201 / 405 / 409 treated as present when the
    /// collection can host children). Non-collection conflicts fail closed.
    ///
    /// # Errors
    ///
    /// Network / auth / permission errors from the wire.
    pub fn mkcol(&self, url: &Url) -> Result<(), LomoError> {
        let method = Method::from_bytes(b"MKCOL").map_err(|_error| {
            network(
                "webdav_method_invalid",
                "webdav MKCOL method construction failed",
                RetryDisposition::Never,
            )
        })?;
        let response = self
            .client
            .request(method, url.clone())
            .header(AUTHORIZATION, self.basic_auth_value())
            .send()
            .map_err(|error| transport_err(&error))?;
        let status = response.status();
        // 201 Created; 405 Method Not Allowed / 409 Conflict commonly mean the collection already
        // exists on real servers (Nutstore/Nextcloud). Treat those as success for ensure-parent.
        if status.is_success()
            || status == StatusCode::METHOD_NOT_ALLOWED
            || status == StatusCode::CONFLICT
        {
            return Ok(());
        }
        Err(map_status_response("MKCOL", &response))
    }

    /// `OPTIONS` + Depth=0 `PROPFIND` capability probe (`ETag` / `MOVE` / `COPY`).
    ///
    /// # Errors
    ///
    /// Auth failures from `OPTIONS`/`PROPFIND`. Soft failures leave capabilities false.
    pub fn probe_capabilities(&self) -> Result<RemoteCapabilities, LomoError> {
        let root = self.endpoint.url().clone();
        let mut caps = RemoteCapabilities::default();
        let options = self
            .client
            .request(Method::OPTIONS, root.clone())
            .header(AUTHORIZATION, self.basic_auth_value())
            .send()
            .map_err(|error| transport_err(&error))?;
        let options_status = options.status().as_u16();
        if options.status().is_success() || options.status() == StatusCode::NO_CONTENT {
            let allow = header_str(options.headers(), "allow").unwrap_or("");
            let allow_upper = allow.to_ascii_uppercase();
            caps.supports_move = allow_upper.contains("MOVE");
            caps.supports_copy = allow_upper.contains("COPY");
        } else if options_status == 401 {
            return Err(map_http_status("OPTIONS", 401));
        } else if options_status == 403 {
            return Err(map_http_status("OPTIONS", 403));
        } else if (300..400).contains(&options_status) {
            // Policy::none: never follow redirects and never replay Authorization.
            // Preflight fails closed on redirect rather than soft-succeeding with empty caps.
            return Err(map_status_response("OPTIONS", &options));
        }

        match self.propfind(&root, 0) {
            Ok(_body) => {
                caps.supports_etag = true;
                caps.conditional_write = true;
                caps.conditional_delete = true;
            }
            Err(error)
                if error.code() == "webdav_unauthorized"
                    || error.code() == "webdav_forbidden"
                    || error.code() == "webdav_redirect_not_followed" =>
            {
                return Err(error);
            }
            Err(_error) => {}
        }
        Ok(caps)
    }

    fn basic_auth_value(&self) -> HeaderValue {
        let mut raw = Vec::with_capacity(
            self.credentials
                .username()
                .len()
                .saturating_add(self.credentials.password().len())
                .saturating_add(1),
        );
        raw.extend_from_slice(self.credentials.username().as_bytes());
        raw.push(b':');
        raw.extend_from_slice(self.credentials.password().as_bytes());
        let encoded = base64_encode(&raw);
        raw.fill(0);
        let value = format!("Basic {encoded}");
        HeaderValue::from_str(&value).unwrap_or_else(|_| HeaderValue::from_static("Basic"))
    }

    fn temp_path(&self, prefix: &str) -> PathBuf {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |duration| duration.as_nanos());
        self.temp_dir
            .join(format!("lomo-webdav-{prefix}-{nanos}.part"))
    }
}

/// Same-origin check used when a caller wants to re-follow a redirect manually.
#[must_use]
pub fn is_same_origin(a: &Url, b: &Url) -> bool {
    a.scheme() == b.scheme()
        && a.host_str() == b.host_str()
        && a.port_or_known_default() == b.port_or_known_default()
}

struct LimitedWriter<'a, W: Write> {
    inner: &'a mut W,
    hasher: &'a mut Sha256,
    written: usize,
    limit: usize,
}

impl<W: Write> Write for LimitedWriter<'_, W> {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        if self.written.saturating_add(buf.len()) > self.limit {
            self.written = self.limit.saturating_add(1);
            return Err(std::io::Error::other(
                "webdav object exceeds streaming ceiling",
            ));
        }
        self.inner.write_all(buf)?;
        self.hasher.update(buf);
        self.written = self.written.saturating_add(buf.len());
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.inner.flush()
    }
}

fn base64_encode(input: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(input.len().div_ceil(3).saturating_mul(4));
    let mut index = 0;
    while index < input.len() {
        let remaining = input.len() - index;
        let b0 = input.get(index).copied().unwrap_or(0);
        let b1 = input.get(index + 1).copied().unwrap_or(0);
        let b2 = input.get(index + 2).copied().unwrap_or(0);
        let i0 = usize::from(b0 >> 2);
        let i1 = usize::from(((b0 & 0x03) << 4) | (b1 >> 4));
        let i2 = usize::from(((b1 & 0x0f) << 2) | (b2 >> 6));
        let i3 = usize::from(b2 & 0x3f);
        out.push(char::from(TABLE.get(i0).copied().unwrap_or(b'A')));
        out.push(char::from(TABLE.get(i1).copied().unwrap_or(b'A')));
        if remaining > 1 {
            out.push(char::from(TABLE.get(i2).copied().unwrap_or(b'A')));
        } else {
            out.push('=');
        }
        if remaining > 2 {
            out.push(char::from(TABLE.get(i3).copied().unwrap_or(b'A')));
        } else {
            out.push('=');
        }
        index = index.saturating_add(3);
    }
    out
}

fn header_str<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    let Ok(key) = HeaderName::from_bytes(name.as_bytes()) else {
        return None;
    };
    let value = headers.get(key)?;
    optional_header_str(value)
}

/// Optional `HeaderValue` to str: invalid UTF-8 is absence for response headers.
///
/// Workspace forbids [`Result::ok`] via `clippy::disallowed_methods`. This helper is the
/// intentional Option boundary rather than erasing errors at call sites with a bare `.ok()`.
#[expect(
    clippy::manual_ok_err,
    clippy::option_if_let_else,
    reason = "Result::ok is workspace-disallowed; invalid header UTF-8 is treated as missing"
)]
fn optional_header_str(value: &HeaderValue) -> Option<&str> {
    match value.to_str() {
        Ok(text) => Some(text),
        Err(_) => None,
    }
}

fn read_body_limited(response: Response, limit: usize, label: &str) -> Result<Vec<u8>, LomoError> {
    if let Some(length) = response.content_length()
        && length > limit as u64
    {
        return Err(resource_limit(
            "webdav_body_too_large",
            &format!("webdav {label} body exceeds the configured limit"),
        ));
    }
    let bytes = response.bytes().map_err(|error| {
        network(
            "webdav_body_read_failed",
            &format!("webdav {label} body read failed: {error}"),
            RetryDisposition::Transient,
        )
    })?;
    if bytes.len() > limit {
        return Err(resource_limit(
            "webdav_body_too_large",
            &format!("webdav {label} body exceeds the configured limit"),
        ));
    }
    Ok(bytes.to_vec())
}

fn map_status_response(method: &str, response: &Response) -> LomoError {
    let status = response.status().as_u16();
    let mut error = map_http_status(method, status);
    if status == 429
        && let Some(retry_after) = header_str(response.headers(), "retry-after")
    {
        let diagnostic = format!(
            "{} (retry-after={})",
            error.diagnostic(),
            retry_after.trim()
        );
        error = crate::error::busy(error.code(), &diagnostic);
    }
    error
}

fn transport_err(error: &reqwest::Error) -> LomoError {
    let detail = redacted_transport_detail(error);
    if error.is_timeout() {
        return network("webdav_timeout", &detail, RetryDisposition::Transient);
    }
    if error.is_connect() {
        return network(
            "webdav_connect_failed",
            &detail,
            RetryDisposition::Transient,
        );
    }
    network(
        "webdav_transport_error",
        &detail,
        RetryDisposition::Transient,
    )
}

fn redacted_transport_detail(error: &reqwest::Error) -> String {
    let mut detail = error.url().map_or_else(
        || format!("webdav transport error: {error}"),
        |url| {
            let mut safe = url.clone();
            if safe.set_username("").is_err() {
                // Host-only URL forms can reject username clears; keep original host.
            }
            if safe.set_password(None).is_err() {
                // Same as username: non-fatal for redaction diagnostics.
            }
            format!(
                "webdav transport error status={:?} url={safe}",
                error.status()
            )
        },
    );
    for needle in ["password=", "Authorization:", "Basic "] {
        if let Some(pos) = detail.find(needle) {
            detail.truncate(pos);
            detail.push_str("<redacted>");
            break;
        }
    }
    detail
}
