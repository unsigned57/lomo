//! Reqwest/Rustls path-style S3 transport (redirect `Policy::none`; `SigV4` signed).

use std::io::Write;
use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use reqwest::blocking::{Client, Response};
use reqwest::header::{HeaderMap, HeaderName, HeaderValue};
use reqwest::redirect::Policy;
use reqwest::{Method, StatusCode, Url};
use sha2::{Digest, Sha256};

use crate::error::{network, resource_limit, storage, validation};
use crate::limits::{MAX_S3_LIST_BODY_BYTES, MAX_S3_OBJECT_BYTES};
use crate::s3::endpoint::{S3Credentials, S3Endpoint};
use crate::s3::list_xml::{ListObjectsPage, parse_list_objects_v2};
use crate::s3::sigv4::{self, EMPTY_PAYLOAD_SHA256};
use crate::s3::status_map::map_s3_http_status;
use lomo_core::{LomoError, RetryDisposition};

/// Blocking S3 HTTP client bound to one endpoint + credentials.
pub struct S3Transport {
    endpoint: S3Endpoint,
    credentials: S3Credentials,
    client: Client,
    temp_dir: PathBuf,
    /// When set, overrides wall-clock `x-amz-date` for hermetic `SigV4` tests.
    fixed_amz_date: Option<String>,
}

impl S3Transport {
    /// Builds a transport with Rustls and **no automatic redirects**.
    ///
    /// # Errors
    ///
    /// Network when the client cannot be constructed; validation when `temp_dir` is missing.
    pub fn new(
        endpoint: S3Endpoint,
        credentials: S3Credentials,
        temp_dir: impl Into<PathBuf>,
        timeout: Duration,
    ) -> Result<Self, LomoError> {
        let temp_dir = temp_dir.into();
        if !temp_dir.is_dir() {
            return Err(validation(
                "s3_temp_dir_missing",
                "s3 transport requires an existing temporary directory for streaming bodies",
            ));
        }
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
                    "s3_client_build_failed",
                    &redacted_transport_detail(&error),
                    RetryDisposition::Never,
                )
            })?;
        Ok(Self {
            endpoint,
            credentials,
            client,
            temp_dir,
            fixed_amz_date: None,
        })
    }

    /// Test-only: pin `x-amz-date` for deterministic `SigV4` verification.
    #[must_use]
    pub fn with_fixed_amz_date(mut self, amz_date: impl Into<String>) -> Self {
        self.fixed_amz_date = Some(amz_date.into());
        self
    }

    #[must_use]
    pub const fn endpoint(&self) -> &S3Endpoint {
        &self.endpoint
    }

    /// `ListObjectsV2` page (path-style).
    ///
    /// # Errors
    ///
    /// Wire / parse / resource-limit errors.
    pub fn list_page(
        &self,
        continuation: Option<&str>,
        max_keys: u32,
    ) -> Result<ListObjectsPage, LomoError> {
        let url = self.endpoint.list_url(continuation, max_keys)?;
        let response = self.signed_request(Method::GET, &url, Vec::new(), None, false)?;
        if !response.status().is_success() {
            return Err(map_status_response("LIST", &response));
        }
        let body = read_body_limited(response, MAX_S3_LIST_BODY_BYTES, "list")?;
        parse_list_objects_v2(&body)
    }

    /// HEAD object; returns optional `ETag`.
    ///
    /// # Errors
    ///
    /// Network / HTTP errors (404 included).
    pub fn head(&self, key: &str) -> Result<Option<String>, LomoError> {
        let url = self.endpoint.object_url(key)?;
        let response = self.signed_request(Method::HEAD, &url, Vec::new(), None, false)?;
        if response.status() == StatusCode::NOT_FOUND {
            return Err(map_s3_http_status("HEAD", 404));
        }
        if !response.status().is_success() {
            return Err(map_status_response("HEAD", &response));
        }
        Ok(header_str(response.headers(), "etag").map(str::to_owned))
    }

    /// GET object bytes into a temp file; returns path + optional `ETag` + sha256 hex of body.
    ///
    /// # Errors
    ///
    /// Network / HTTP / resource-limit errors.
    pub fn get_to_temp(&self, key: &str) -> Result<(PathBuf, Option<String>, String), LomoError> {
        let url = self.endpoint.object_url(key)?;
        let mut response = self.signed_request(Method::GET, &url, Vec::new(), None, false)?;
        if !response.status().is_success() {
            return Err(map_status_response("GET", &response));
        }
        let etag = header_str(response.headers(), "etag").map(str::to_owned);
        if let Some(length) = response.content_length()
            && length > MAX_S3_OBJECT_BYTES as u64
        {
            return Err(resource_limit(
                "s3_object_too_large",
                "s3 object exceeds the 32 MiB host streaming ceiling",
            ));
        }
        let temp_path = self.temp_path("get");
        let mut file = std::fs::File::create(&temp_path).map_err(|error| {
            storage(
                "s3_temp_create_failed",
                &format!("failed to create s3 temp file: {error}"),
            )
        })?;
        let mut hasher = Sha256::new();
        let mut limited = LimitedWriter {
            inner: &mut file,
            hasher: &mut hasher,
            written: 0,
            limit: MAX_S3_OBJECT_BYTES,
        };
        response.copy_to(&mut limited).map_err(|error| {
            let _removed: Result<(), std::io::Error> = std::fs::remove_file(&temp_path);
            if limited.written > limited.limit {
                return resource_limit(
                    "s3_object_too_large",
                    "s3 object exceeds the 32 MiB host streaming ceiling",
                );
            }
            network(
                "s3_get_read_failed",
                &format!("s3 GET body read failed: {error}"),
                RetryDisposition::Transient,
            )
        })?;
        file.sync_all().map_err(|error| {
            storage(
                "s3_temp_sync_failed",
                &format!("failed to fsync s3 temp file: {error}"),
            )
        })?;
        let digest = format!("{:x}", hasher.finalize());
        Ok((temp_path, etag, digest))
    }

    /// Conditional PUT; returns new `ETag` when present.
    ///
    /// # Errors
    ///
    /// Network / HTTP / conflict errors.
    pub fn put_bytes(
        &self,
        key: &str,
        body: Vec<u8>,
        if_match: Option<&str>,
        if_none_match: bool,
    ) -> Result<Option<String>, LomoError> {
        let url = self.endpoint.object_url(key)?;
        let mut extra = HeaderMap::new();
        if let Some(token) = if_match {
            insert_header(&mut extra, "if-match", token)?;
        } else if if_none_match {
            insert_header(&mut extra, "if-none-match", "*")?;
        }
        let response = self.signed_request(Method::PUT, &url, body, Some(extra), true)?;
        if response.status().is_success() {
            return Ok(header_str(response.headers(), "etag").map(str::to_owned));
        }
        Err(map_status_response("PUT", &response))
    }

    /// Conditional DELETE.
    ///
    /// # Errors
    ///
    /// Network / HTTP / conflict errors.
    pub fn delete(&self, key: &str, if_match: Option<&str>) -> Result<(), LomoError> {
        let url = self.endpoint.object_url(key)?;
        let mut extra = HeaderMap::new();
        if let Some(token) = if_match {
            insert_header(&mut extra, "if-match", token)?;
        }
        let response = self.signed_request(Method::DELETE, &url, Vec::new(), Some(extra), false)?;
        if response.status().is_success() {
            return Ok(());
        }
        if response.status() == StatusCode::NOT_FOUND {
            return Err(map_s3_http_status("DELETE", 404));
        }
        Err(map_status_response("DELETE", &response))
    }

    /// `CreateMultipartUpload`; returns upload id.
    ///
    /// # Errors
    ///
    /// Wire / parse errors.
    pub fn create_multipart_upload(&self, key: &str) -> Result<String, LomoError> {
        let mut url = self.endpoint.object_url(key)?;
        url.query_pairs_mut().append_pair("uploads", "");
        let response = self.signed_request(Method::POST, &url, Vec::new(), None, false)?;
        if !response.status().is_success() {
            return Err(map_status_response("CREATE_MULTIPART", &response));
        }
        let body = read_body_limited(response, MAX_S3_LIST_BODY_BYTES, "multipart-init")?;
        let text = std::str::from_utf8(&body).map_err(|_error| {
            validation(
                "s3_multipart_init_not_utf8",
                "s3 CreateMultipartUpload body must be UTF-8",
            )
        })?;
        extract_xml_local_text(text, "UploadId").ok_or_else(|| {
            validation(
                "s3_multipart_upload_id_missing",
                "s3 CreateMultipartUpload response is missing UploadId",
            )
        })
    }

    /// `UploadPart`; returns part `ETag`.
    ///
    /// # Errors
    ///
    /// Wire errors.
    pub fn upload_part(
        &self,
        key: &str,
        upload_id: &str,
        part_number: u32,
        body: Vec<u8>,
    ) -> Result<String, LomoError> {
        let mut url = self.endpoint.object_url(key)?;
        {
            let mut pairs = url.query_pairs_mut();
            pairs.append_pair("partNumber", &part_number.to_string());
            pairs.append_pair("uploadId", upload_id);
        }
        let response = self.signed_request(Method::PUT, &url, body, None, true)?;
        if !response.status().is_success() {
            return Err(map_status_response("UPLOAD_PART", &response));
        }
        header_str(response.headers(), "etag")
            .map(str::to_owned)
            .ok_or_else(|| {
                validation(
                    "s3_multipart_part_etag_missing",
                    "s3 UploadPart response is missing ETag",
                )
            })
    }

    /// `CompleteMultipartUpload`.
    ///
    /// # Errors
    ///
    /// Wire errors.
    pub fn complete_multipart_upload(
        &self,
        key: &str,
        upload_id: &str,
        parts: &[(u32, String)],
    ) -> Result<Option<String>, LomoError> {
        let mut url = self.endpoint.object_url(key)?;
        url.query_pairs_mut().append_pair("uploadId", upload_id);
        let mut xml = String::from(
            r#"<?xml version="1.0" encoding="UTF-8"?><CompleteMultipartUpload xmlns="http://s3.amazonaws.com/doc/2006-03-01/">"#,
        );
        for (number, etag) in parts {
            xml.push_str("<Part><PartNumber>");
            xml.push_str(&number.to_string());
            xml.push_str("</PartNumber><ETag>");
            xml.push_str(etag);
            xml.push_str("</ETag></Part>");
        }
        xml.push_str("</CompleteMultipartUpload>");
        let response = self.signed_request(Method::POST, &url, xml.into_bytes(), None, true)?;
        if !response.status().is_success() {
            return Err(map_status_response("COMPLETE_MULTIPART", &response));
        }
        Ok(header_str(response.headers(), "etag").map(str::to_owned))
    }

    /// `AbortMultipartUpload` (idempotent on 404).
    ///
    /// # Errors
    ///
    /// Wire errors other than 404.
    pub fn abort_multipart_upload(&self, key: &str, upload_id: &str) -> Result<(), LomoError> {
        let mut url = self.endpoint.object_url(key)?;
        url.query_pairs_mut().append_pair("uploadId", upload_id);
        let response = self.signed_request(Method::DELETE, &url, Vec::new(), None, false)?;
        if response.status().is_success() || response.status() == StatusCode::NOT_FOUND {
            return Ok(());
        }
        Err(map_status_response("ABORT_MULTIPART", &response))
    }

    fn signed_request(
        &self,
        method: Method,
        url: &Url,
        body: Vec<u8>,
        extra_headers: Option<HeaderMap>,
        include_body_hash: bool,
    ) -> Result<Response, LomoError> {
        let payload_hash = if include_body_hash || !body.is_empty() {
            sigv4::sha256_hex(&body)
        } else {
            EMPTY_PAYLOAD_SHA256.to_owned()
        };
        let amz_date = self.amz_date_now();
        let signed = sigv4::sign_request(
            method.as_str(),
            url,
            self.endpoint.region(),
            &self.credentials,
            &amz_date,
            &payload_hash,
        );
        let mut builder = self
            .client
            .request(method, url.clone())
            .header("Host", signed.host)
            .header("x-amz-date", signed.amz_date)
            .header("x-amz-content-sha256", signed.content_sha256)
            .header("Authorization", signed.authorization);
        if let Some(extra) = extra_headers {
            for (name, value) in &extra {
                builder = builder.header(name.clone(), value.clone());
            }
        }
        if !body.is_empty() {
            builder = builder.body(body);
        }
        builder.send().map_err(|error| transport_err(&error))
    }

    fn amz_date_now(&self) -> String {
        if let Some(fixed) = &self.fixed_amz_date {
            return fixed.clone();
        }
        let secs = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| d.as_secs());
        // UTC formatting without chrono: fixed width YYYYMMDDTHHMMSSZ via simple epoch math
        // is incomplete for leap years; use libc-free approximation via format of known epoch
        // path is fine for production: reqwest servers accept current time. For portability we
        // use a minimal UTC formatter.
        format_amz_date(secs)
    }

    fn temp_path(&self, prefix: &str) -> PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |duration| duration.as_nanos());
        self.temp_dir.join(format!("lomo-s3-{prefix}-{nanos}.part"))
    }
}

fn format_amz_date(unix_secs: u64) -> String {
    // Algorithm from civil_from_days (Howard Hinnant) for UTC.
    let days = i64::try_from(unix_secs / 86_400).unwrap_or(i64::MAX);
    let time = unix_secs % 86_400;
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = u64::try_from(z - era * 146_097).unwrap_or(0);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let y = i64::try_from(yoe).unwrap_or(0) + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    let hour = time / 3600;
    let min = (time % 3600) / 60;
    let sec = time % 60;
    format!("{y:04}{m:02}{d:02}T{hour:02}{min:02}{sec:02}Z")
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
            return Err(std::io::Error::other("s3 object exceeds streaming ceiling"));
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

fn insert_header(map: &mut HeaderMap, name: &str, value: &str) -> Result<(), LomoError> {
    let key = HeaderName::from_bytes(name.as_bytes()).map_err(|_error| {
        validation(
            "s3_header_name_invalid",
            "s3 conditional header name is invalid",
        )
    })?;
    let val = HeaderValue::from_str(value).map_err(|_error| {
        validation(
            "s3_header_value_invalid",
            "s3 conditional header value is invalid",
        )
    })?;
    map.insert(key, val);
    Ok(())
}

fn header_str<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    let Ok(key) = HeaderName::from_bytes(name.as_bytes()) else {
        return None;
    };
    let value = headers.get(key)?;
    optional_header_str(value)
}

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
            "s3_body_too_large",
            &format!("s3 {label} body exceeds the configured limit"),
        ));
    }
    let bytes = response.bytes().map_err(|error| {
        network(
            "s3_body_read_failed",
            &format!("s3 {label} body read failed: {error}"),
            RetryDisposition::Transient,
        )
    })?;
    if bytes.len() > limit {
        return Err(resource_limit(
            "s3_body_too_large",
            &format!("s3 {label} body exceeds the configured limit"),
        ));
    }
    Ok(bytes.to_vec())
}

fn map_status_response(method: &str, response: &Response) -> LomoError {
    map_s3_http_status(method, response.status().as_u16())
}

fn transport_err(error: &reqwest::Error) -> LomoError {
    let detail = redacted_transport_detail(error);
    if error.is_timeout() {
        return network("s3_timeout", &detail, RetryDisposition::Transient);
    }
    if error.is_connect() {
        return network("s3_connect_failed", &detail, RetryDisposition::Transient);
    }
    network("s3_transport_error", &detail, RetryDisposition::Transient)
}

fn redacted_transport_detail(error: &reqwest::Error) -> String {
    let mut detail = error.url().map_or_else(
        || format!("s3 transport error: {error}"),
        |url| {
            let mut safe = url.clone();
            let _cleared_user: Result<(), ()> = safe.set_username("");
            let _cleared_pass: bool = safe.set_password(None).is_ok();
            format!("s3 transport error status={:?} url={safe}", error.status())
        },
    );
    for needle in ["SecretAccessKey", "AWS4-HMAC-SHA256", "Authorization:"] {
        if let Some(pos) = detail.find(needle) {
            detail.truncate(pos);
            detail.push_str("<redacted>");
            break;
        }
    }
    detail
}

fn extract_xml_local_text(xml: &str, local: &str) -> Option<String> {
    let open_plain = format!("<{local}>");
    let close_plain = format!("</{local}>");
    if let Some(start) = xml.find(&open_plain) {
        let content_start = start + open_plain.len();
        if let Some(tail) = xml.get(content_start..)
            && let Some(end_rel) = tail.find(&close_plain)
            && let Some(content) = xml.get(content_start..content_start + end_rel)
        {
            return Some(content.trim().to_owned());
        }
    }
    // Namespaced form: <ns:UploadId>...
    let needle = format!(":{local}>");
    let mut search_from = 0_usize;
    while let Some(rel) = xml.get(search_from..)?.find(&needle) {
        let abs = search_from + rel;
        let after_gt = abs + needle.len();
        let close = "</";
        let Some(tail) = xml.get(after_gt..) else {
            break;
        };
        if let Some(close_rel) = tail.find(close) {
            let close_abs = after_gt + close_rel;
            let Some(close_region) = xml.get(close_abs..) else {
                break;
            };
            let namespaced_close = format!(":{local}>");
            let plain_close = format!("</{local}>");
            if (close_region.contains(&namespaced_close) || close_region.starts_with(&plain_close))
                && let Some(content) = xml.get(after_gt..close_abs)
            {
                let trimmed = content.trim();
                if !trimmed.is_empty() && !trimmed.contains('<') {
                    return Some(trimmed.to_owned());
                }
            }
        }
        search_from = abs + 1;
    }
    None
}
