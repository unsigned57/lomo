//! Strict S3 endpoint / bucket / prefix normalization and credential holders.

use crate::error::validation;
use lomo_core::LomoError;
use url::Url;

/// S3 addressing style for Stage-5 custom endpoints.
///
/// **Stage-5 product law (host-complete):** the dark S3 adapter ships **path-style only** for
/// custom endpoints (R2 / `MinIO` / hermetic fault server). [`S3AddressingStyle::Auto`] resolves to
/// the same path-style object/list URL shape. AWS virtual-hosted addressing is **not** a Stage-5
/// host residual — it remains real-provider smoke / post-cutover (`pending_env`), never claimed
/// host-GREEN here.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum S3AddressingStyle {
    /// `https://endpoint/{bucket}/{key}` (R2 / `MinIO` / hermetic fault server).
    PathStyle,
    /// Auto → path-style for Stage-5 custom endpoints (same URL shape as [`Self::PathStyle`]).
    Auto,
}

/// Normalized S3 root: endpoint + bucket + optional key prefix (always slash-terminated when set).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct S3Endpoint {
    base: Url,
    bucket: String,
    prefix: String,
    region: String,
    style: S3AddressingStyle,
}

impl S3Endpoint {
    /// Parses and normalizes an S3 endpoint configuration.
    ///
    /// Rules:
    /// - scheme must be `http` or `https`
    /// - host required; userinfo / query / fragment forbidden on the endpoint URL
    /// - bucket non-empty, no `/`
    /// - prefix is workspace-relative under the bucket (no `..`, no absolute)
    ///
    /// # Errors
    ///
    /// Validation when any field violates the rules above.
    pub fn parse(
        endpoint_url: &str,
        bucket: &str,
        prefix: &str,
        region: &str,
        style: S3AddressingStyle,
    ) -> Result<Self, LomoError> {
        let trimmed = endpoint_url.trim();
        if trimmed.is_empty() {
            return Err(validation(
                "s3_endpoint_empty",
                "s3 endpoint must be non-empty",
            ));
        }
        let mut base = Url::parse(trimmed).map_err(|_error| {
            validation(
                "s3_endpoint_invalid",
                "s3 endpoint must be an absolute http(s) URL",
            )
        })?;
        if base.scheme() != "http" && base.scheme() != "https" {
            return Err(validation(
                "s3_endpoint_scheme",
                "s3 endpoint scheme must be http or https",
            ));
        }
        if base.host_str().is_none() {
            return Err(validation(
                "s3_endpoint_host_missing",
                "s3 endpoint requires a host",
            ));
        }
        if !base.username().is_empty() || base.password().is_some() {
            return Err(validation(
                "s3_endpoint_userinfo_forbidden",
                "s3 endpoint must not embed credentials; use S3Credentials",
            ));
        }
        if base.query().is_some() || base.fragment().is_some() {
            return Err(validation(
                "s3_endpoint_query_or_fragment",
                "s3 endpoint must not carry query or fragment",
            ));
        }
        {
            let path = base.path();
            if !path.ends_with('/') {
                let mut with_slash = path.to_owned();
                with_slash.push('/');
                base.set_path(&with_slash);
            }
        }
        let bucket = bucket.trim().to_owned();
        if bucket.is_empty() || bucket.contains('/') || bucket.contains('\\') {
            return Err(validation(
                "s3_bucket_invalid",
                "s3 bucket must be a non-empty single path segment",
            ));
        }
        let prefix = normalize_prefix(prefix)?;
        let region = region.trim().to_owned();
        if region.is_empty() {
            return Err(validation(
                "s3_region_empty",
                "s3 region must be non-empty (use a placeholder for path-style custom endpoints)",
            ));
        }
        Ok(Self {
            base,
            bucket,
            prefix,
            region,
            style,
        })
    }

    #[must_use]
    pub const fn base_url(&self) -> &Url {
        &self.base
    }

    #[must_use]
    pub fn bucket(&self) -> &str {
        &self.bucket
    }

    #[must_use]
    pub fn prefix(&self) -> &str {
        &self.prefix
    }

    #[must_use]
    pub fn region(&self) -> &str {
        &self.region
    }

    #[must_use]
    pub const fn style(&self) -> S3AddressingStyle {
        self.style
    }

    /// Maps a workspace-relative path to the full object key under the configured prefix.
    ///
    /// # Errors
    ///
    /// Validation when the relative path is illegal.
    pub fn object_key(&self, relative: &str) -> Result<String, LomoError> {
        let rel = normalize_relative(relative)?;
        if self.prefix.is_empty() {
            Ok(rel)
        } else if rel.is_empty() {
            Ok(self.prefix.trim_end_matches('/').to_owned())
        } else {
            Ok(format!("{}{rel}", self.prefix))
        }
    }

    /// Strips the configured prefix from an object key to a workspace-relative path.
    ///
    /// # Errors
    ///
    /// Validation when the key is outside the prefix.
    pub fn relative_from_key(&self, key: &str) -> Result<String, LomoError> {
        let key = key.trim_matches('/');
        if self.prefix.is_empty() {
            return normalize_relative(key);
        }
        let prefix_trim = self.prefix.trim_end_matches('/');
        let rest = key.strip_prefix(prefix_trim).ok_or_else(|| {
            validation(
                "s3_key_outside_prefix",
                "s3 object key is outside the configured prefix",
            )
        })?;
        let rest = rest.trim_start_matches('/');
        if rest.is_empty() {
            return Err(validation(
                "s3_key_is_prefix_root",
                "s3 object key resolved to the prefix root, not a child object",
            ));
        }
        normalize_relative(rest)
    }

    /// Builds an object URL for `key` (percent-encoded path segments).
    ///
    /// Stage-5 product law: both [`S3AddressingStyle::PathStyle`] and
    /// [`S3AddressingStyle::Auto`] emit path-style `/{bucket}/{key}` under the configured endpoint.
    /// Virtual-hosted host rewriting is intentionally absent (real AWS smoke only).
    ///
    /// # Errors
    ///
    /// Validation when the URL cannot be joined under the endpoint root.
    pub fn object_url(&self, key: &str) -> Result<Url, LomoError> {
        debug_assert!(
            matches!(
                self.style,
                S3AddressingStyle::PathStyle | S3AddressingStyle::Auto
            ),
            "Stage-5 addressing is path-style only"
        );
        let encoded_key = key
            .split('/')
            .map(percent_encode_segment)
            .collect::<Vec<_>>()
            .join("/");
        let path = format!("{}/{}", self.bucket, encoded_key);
        self.base.join(&path).map_err(|_error| {
            validation(
                "s3_object_url_join_failed",
                "s3 object URL could not be joined under the endpoint root",
            )
        })
    }

    /// Builds a bucket list URL (`?list-type=2…`).
    ///
    /// Stage-5 product law: path-style list under `/{bucket}/` for both [`S3AddressingStyle::PathStyle`]
    /// and [`S3AddressingStyle::Auto`].
    ///
    /// # Errors
    ///
    /// Validation when the URL cannot be joined.
    pub fn list_url(&self, continuation: Option<&str>, max_keys: u32) -> Result<Url, LomoError> {
        debug_assert!(
            matches!(
                self.style,
                S3AddressingStyle::PathStyle | S3AddressingStyle::Auto
            ),
            "Stage-5 addressing is path-style only"
        );
        let mut url = self
            .base
            .join(&format!("{}/", self.bucket))
            .map_err(|_error| {
                validation(
                    "s3_list_url_join_failed",
                    "s3 list URL could not be joined under the endpoint root",
                )
            })?;
        {
            let mut pairs = url.query_pairs_mut();
            pairs.append_pair("list-type", "2");
            pairs.append_pair("max-keys", &max_keys.to_string());
            if !self.prefix.is_empty() {
                pairs.append_pair("prefix", &self.prefix);
            }
            if let Some(token) = continuation {
                pairs.append_pair("continuation-token", token);
            }
        }
        Ok(url)
    }
}

/// Ephemeral static S3 credentials. Never place these in diagnostics or durable state.
#[derive(Clone)]
pub struct S3Credentials {
    access_key_id: String,
    secret_access_key: String,
}

impl S3Credentials {
    /// Builds static credentials.
    ///
    /// # Errors
    ///
    /// Validation when access key id is empty.
    pub fn new(
        access_key_id: impl Into<String>,
        secret_access_key: impl Into<String>,
    ) -> Result<Self, LomoError> {
        let access_key_id = access_key_id.into();
        let secret_access_key = secret_access_key.into();
        if access_key_id.is_empty() {
            return Err(validation(
                "s3_access_key_empty",
                "s3 access key id must be non-empty",
            ));
        }
        Ok(Self {
            access_key_id,
            secret_access_key,
        })
    }

    #[must_use]
    pub fn access_key_id(&self) -> &str {
        &self.access_key_id
    }

    #[must_use]
    pub fn secret_access_key(&self) -> &str {
        &self.secret_access_key
    }
}

impl std::fmt::Debug for S3Credentials {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("S3Credentials")
            .field("access_key_id", &"<redacted>")
            .field("secret_access_key", &"<redacted>")
            .finish()
    }
}

fn normalize_prefix(raw: &str) -> Result<String, LomoError> {
    let trimmed = raw.trim().trim_matches('/');
    if trimmed.is_empty() {
        return Ok(String::new());
    }
    let rel = normalize_relative(trimmed)?;
    Ok(format!("{rel}/"))
}

fn normalize_relative(raw: &str) -> Result<String, LomoError> {
    let trimmed = raw.trim().trim_matches('/');
    if trimmed.is_empty() {
        return Ok(String::new());
    }
    if trimmed.starts_with('/') || trimmed.contains('\\') {
        return Err(validation(
            "s3_path_invalid",
            "s3 path must be relative without backslashes",
        ));
    }
    if trimmed.split('/').any(|seg| seg.is_empty() || seg == "..") {
        return Err(validation(
            "s3_path_invalid",
            "s3 path must not contain empty or parent segments",
        ));
    }
    Ok(trimmed.to_owned())
}

fn percent_encode_segment(segment: &str) -> String {
    let mut out = String::with_capacity(segment.len());
    for byte in segment.as_bytes() {
        match *byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(char::from(*byte));
            }
            _ => {
                out.push('%');
                out.push(hex_digit(byte >> 4));
                out.push(hex_digit(byte & 0x0f));
            }
        }
    }
    out
}

fn hex_digit(nibble: u8) -> char {
    match nibble {
        0..=9 => char::from(b'0' + nibble),
        10..=15 => char::from(b'A' + (nibble - 10)),
        _ => '0',
    }
}
