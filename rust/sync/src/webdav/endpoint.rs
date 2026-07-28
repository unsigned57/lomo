//! Strict `WebDAV` endpoint normalization and credential holders (never logged).

use crate::error::validation;
use lomo_core::LomoError;
use url::Url;

/// Normalized `WebDAV` collection root (always ends with `/`, no fragment/query secrets).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WebDavEndpoint {
    root: Url,
}

impl WebDavEndpoint {
    /// Parses and normalizes an endpoint URL.
    ///
    /// Rules:
    /// - scheme must be `http` or `https`
    /// - host required
    /// - path forced to end with `/`
    /// - query and fragment rejected (credentials must not ride the URL)
    /// - userinfo in the URL is rejected (use [`WebDavCredentials`])
    ///
    /// # Errors
    ///
    /// Validation when the URL is empty, malformed, or violates the rules above.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            return Err(validation(
                "webdav_endpoint_empty",
                "webdav endpoint must be non-empty",
            ));
        }
        let parsed = Url::parse(trimmed).map_err(|_error| {
            validation(
                "webdav_endpoint_invalid",
                "webdav endpoint must be an absolute http(s) URL",
            )
        })?;
        if parsed.scheme() != "http" && parsed.scheme() != "https" {
            return Err(validation(
                "webdav_endpoint_scheme",
                "webdav endpoint scheme must be http or https",
            ));
        }
        if parsed.host_str().is_none() {
            return Err(validation(
                "webdav_endpoint_host_missing",
                "webdav endpoint requires a host",
            ));
        }
        if !parsed.username().is_empty() || parsed.password().is_some() {
            return Err(validation(
                "webdav_endpoint_userinfo_forbidden",
                "webdav endpoint must not embed credentials; use WebDavCredentials",
            ));
        }
        if parsed.query().is_some() || parsed.fragment().is_some() {
            return Err(validation(
                "webdav_endpoint_query_or_fragment",
                "webdav endpoint must not carry query or fragment",
            ));
        }
        let mut root = parsed;
        {
            let path = root.path();
            if !path.ends_with('/') {
                let mut with_slash = path.to_owned();
                with_slash.push('/');
                root.set_path(&with_slash);
            }
        }
        Ok(Self { root })
    }

    /// Returns the normalized root URL string (trailing slash).
    #[must_use]
    pub fn as_str(&self) -> &str {
        self.root.as_str()
    }

    /// Returns the parsed root URL.
    #[must_use]
    pub const fn url(&self) -> &Url {
        &self.root
    }

    /// Resolves a workspace-relative path under this endpoint.
    ///
    /// # Errors
    ///
    /// Validation when the relative path cannot be joined or escapes the root.
    pub fn resolve_path(&self, relative: &str) -> Result<Url, LomoError> {
        let trimmed = relative.trim_matches('/');
        let joined = if trimmed.is_empty() {
            self.root.clone()
        } else {
            // Encode each segment so Unicode paths round-trip safely.
            let encoded = trimmed
                .split('/')
                .map(percent_encode_segment)
                .collect::<Vec<_>>()
                .join("/");
            self.root.join(&encoded).map_err(|_error| {
                validation(
                    "webdav_path_join_failed",
                    "webdav path could not be joined under the endpoint root",
                )
            })?
        };
        self.ensure_under_root(&joined)?;
        Ok(joined)
    }

    /// Ensures `candidate` is same-origin and under the collection root path.
    ///
    /// # Errors
    ///
    /// Validation on origin mismatch or path traversal outside the root.
    pub fn ensure_under_root(&self, candidate: &Url) -> Result<(), LomoError> {
        if candidate.scheme() != self.root.scheme()
            || candidate.host_str() != self.root.host_str()
            || candidate.port_or_known_default() != self.root.port_or_known_default()
        {
            return Err(validation(
                "webdav_href_off_origin",
                "webdav href must stay on the same origin as the endpoint",
            ));
        }
        let root_segs = path_segments(&self.root);
        let cand_segs = path_segments(candidate);
        if cand_segs.len() < root_segs.len() {
            return Err(validation(
                "webdav_href_outside_root",
                "webdav href path traversal outside the collection root is forbidden",
            ));
        }
        if cand_segs.iter().take(root_segs.len()).ne(root_segs.iter()) {
            return Err(validation(
                "webdav_href_outside_root",
                "webdav href path traversal outside the collection root is forbidden",
            ));
        }
        if cand_segs.iter().any(|seg| seg == "..") {
            return Err(validation(
                "webdav_href_parent_segment",
                "webdav href must not contain parent path segments",
            ));
        }
        Ok(())
    }

    /// Maps an absolute or root-relative href under this root to a workspace-relative path.
    ///
    /// # Errors
    ///
    /// Validation when the href is illegal, off-origin, outside the root, or empty relative.
    pub fn relative_path_from_href(&self, href: &str) -> Result<String, LomoError> {
        let trimmed = href.trim();
        if trimmed.is_empty() {
            return Err(validation(
                "webdav_href_empty",
                "webdav href must be non-empty",
            ));
        }
        if trimmed.contains('\0') {
            return Err(validation(
                "webdav_href_nul",
                "webdav href must not contain NUL",
            ));
        }
        let resolved = self.root.join(trimmed).map_err(|_error| {
            validation(
                "webdav_href_invalid",
                "webdav href could not be resolved against the endpoint root",
            )
        })?;
        self.ensure_under_root(&resolved)?;
        let root_segs = path_segments(&self.root);
        let cand_segs = path_segments(&resolved);
        let relative_encoded: Vec<String> = cand_segs.into_iter().skip(root_segs.len()).collect();
        if relative_encoded.is_empty() {
            return Err(validation(
                "webdav_href_is_root",
                "webdav href resolved to the collection root, not a child path",
            ));
        }
        // Decode each path segment independently so `%2F` (or other separators) inside a
        // segment cannot invent a second path hierarchy (path-collision fail-closed).
        let mut decoded_segments = Vec::with_capacity(relative_encoded.len());
        for segment in relative_encoded {
            let decoded = percent_decode_path(&segment)?;
            if decoded.is_empty()
                || decoded == ".."
                || decoded.contains('/')
                || decoded.contains('\\')
                || decoded.contains('\0')
            {
                return Err(validation(
                    "webdav_href_path_collision",
                    "webdav href segment percent-decoding must not invent path separators or parent segments",
                ));
            }
            decoded_segments.push(decoded);
        }
        Ok(decoded_segments.join("/"))
    }
}

/// Ephemeral `WebDAV` basic-auth credentials. Never place these in diagnostics or durable state.
#[derive(Clone)]
pub struct WebDavCredentials {
    username: String,
    password: String,
}

impl WebDavCredentials {
    /// Builds credentials from username/password strings.
    ///
    /// # Errors
    ///
    /// Validation when username is empty.
    pub fn new(
        username: impl Into<String>,
        password: impl Into<String>,
    ) -> Result<Self, LomoError> {
        let username = username.into();
        let password = password.into();
        if username.is_empty() {
            return Err(validation(
                "webdav_username_empty",
                "webdav username must be non-empty",
            ));
        }
        Ok(Self { username, password })
    }

    #[must_use]
    pub fn username(&self) -> &str {
        &self.username
    }

    #[must_use]
    pub fn password(&self) -> &str {
        &self.password
    }
}

impl std::fmt::Debug for WebDavCredentials {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("WebDavCredentials")
            .field("username", &"<redacted>")
            .field("password", &"<redacted>")
            .finish()
    }
}

// Credentials are process-local only. Do not implement Debug/Display that leak secrets.
// Wipe is best-effort via dropping owned Strings; no first-party unsafe.

fn path_segments(url: &Url) -> Vec<String> {
    let Some(segments) = url.path_segments() else {
        return Vec::new();
    };
    segments
        .filter(|segment| !segment.is_empty())
        .map(ToString::to_string)
        .collect()
}

fn percent_encode_segment(segment: &str) -> String {
    let mut out = String::with_capacity(segment.len());
    for byte in segment.as_bytes() {
        match *byte {
            b'A'..=b'Z'
            | b'a'..=b'z'
            | b'0'..=b'9'
            | b'-'
            | b'_'
            | b'.'
            | b'~'
            | b'!'
            | b'$'
            | b'\''
            | b'('
            | b')'
            | b'*'
            | b'+'
            | b','
            | b';'
            | b'='
            | b':'
            | b'@' => out.push(char::from(*byte)),
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

fn percent_decode_path(path: &str) -> Result<String, LomoError> {
    let bytes = path.as_bytes();
    let mut raw = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        match bytes.get(index) {
            Some(b'%') => {
                let a = *bytes.get(index + 1).ok_or_else(|| {
                    validation(
                        "webdav_href_bad_percent",
                        "webdav href contains an incomplete percent-encoding sequence",
                    )
                })?;
                let b = *bytes.get(index + 2).ok_or_else(|| {
                    validation(
                        "webdav_href_bad_percent",
                        "webdav href contains an incomplete percent-encoding sequence",
                    )
                })?;
                if !(a.is_ascii_hexdigit() && b.is_ascii_hexdigit()) {
                    return Err(validation(
                        "webdav_href_bad_percent",
                        "webdav href contains an incomplete percent-encoding sequence",
                    ));
                }
                raw.push((hex_val(a) << 4) | hex_val(b));
                index += 3;
            }
            Some(byte) => {
                raw.push(*byte);
                index += 1;
            }
            None => break,
        }
    }
    String::from_utf8(raw).map_err(|_error| {
        validation(
            "webdav_href_invalid_utf8",
            "webdav href percent-decoding must yield valid UTF-8",
        )
    })
}

const fn hex_val(byte: u8) -> u8 {
    match byte {
        b'0'..=b'9' => byte - b'0',
        b'a'..=b'f' => byte - b'a' + 10,
        b'A'..=b'F' => byte - b'A' + 10,
        _ => 0,
    }
}
