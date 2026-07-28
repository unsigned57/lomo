//! AWS Signature Version 4 signing for path-style S3 (host hermetic + real AWS shape).
//!
//! Pure `HMAC-SHA256` over `sha2` — same construction proven by stage-0 feasibility against the
//! published AWS S3 `SigV4` example vector. No AWS SDK dependency (size-constrained dark host slice).

use sha2::{Digest, Sha256};
use url::Url;

use crate::s3::endpoint::S3Credentials;

/// Empty-body SHA-256 hex (canonical for signed GETs/HEADs/DELETEs without payload).
pub const EMPTY_PAYLOAD_SHA256: &str =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

/// Signed request headers ready to attach to a reqwest builder.
#[derive(Clone, Debug)]
pub struct SignedHeaders {
    pub authorization: String,
    pub amz_date: String,
    pub content_sha256: String,
    pub host: String,
}

/// Signs an S3 HTTP request with `SigV4` (header auth).
///
/// `amz_date` must be `YYYYMMDDTHHMMSSZ`. Payload hash is lowercase hex SHA-256 of the body
/// (use [`EMPTY_PAYLOAD_SHA256`] when the body is empty).
#[must_use]
pub fn sign_request(
    method: &str,
    url: &Url,
    region: &str,
    credentials: &S3Credentials,
    amz_date: &str,
    payload_sha256_hex: &str,
) -> SignedHeaders {
    let host = host_header_value(url);
    let path = canonical_uri(url);
    let query = canonical_query(url);
    let date_stamp = amz_date.get(..8).unwrap_or("");
    let credential_scope = format!("{date_stamp}/{region}/s3/aws4_request");
    let canonical_headers =
        format!("host:{host}\nx-amz-content-sha256:{payload_sha256_hex}\nx-amz-date:{amz_date}\n");
    let signed_headers = "host;x-amz-content-sha256;x-amz-date";
    let canonical_request = format!(
        "{method}\n{path}\n{query}\n{canonical_headers}\n{signed_headers}\n{payload_sha256_hex}"
    );
    let canonical_hash = hex_encode(Sha256::digest(canonical_request.as_bytes()));
    let string_to_sign =
        format!("AWS4-HMAC-SHA256\n{amz_date}\n{credential_scope}\n{canonical_hash}");
    let signing_key = signing_key_for(credentials.secret_access_key(), date_stamp, region, "s3");
    let signature = hex_encode(hmac_sha256(&signing_key, string_to_sign.as_bytes()));
    let authorization = format!(
        "AWS4-HMAC-SHA256 Credential={}/{credential_scope}, SignedHeaders={signed_headers}, Signature={signature}",
        credentials.access_key_id()
    );
    SignedHeaders {
        authorization,
        amz_date: amz_date.to_owned(),
        content_sha256: payload_sha256_hex.to_owned(),
        host,
    }
}

/// Verifies the published AWS S3 `SigV4` example (same oracle as stage-0 feasibility).
#[must_use]
pub fn aws_published_sigv4_example_matches() -> bool {
    const EXPECTED: &str = "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41";
    let amz_date = "20130524T000000Z";
    let date_stamp = "20130524";
    let region = "us-east-1";
    let secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    let payload_hash = EMPTY_PAYLOAD_SHA256;
    let canonical_headers = format!(
        "host:examplebucket.s3.amazonaws.com\nrange:bytes=0-9\nx-amz-content-sha256:{payload_hash}\nx-amz-date:{amz_date}\n"
    );
    let signed_headers = "host;range;x-amz-content-sha256;x-amz-date";
    let canonical_request =
        format!("GET\n/test.txt\n\n{canonical_headers}\n{signed_headers}\n{payload_hash}");
    let canonical_hash = hex_encode(Sha256::digest(canonical_request.as_bytes()));
    let credential_scope = format!("{date_stamp}/{region}/s3/aws4_request");
    let string_to_sign =
        format!("AWS4-HMAC-SHA256\n{amz_date}\n{credential_scope}\n{canonical_hash}");
    let signing_key = signing_key_for(secret, date_stamp, region, "s3");
    let signature = hex_encode(hmac_sha256(&signing_key, string_to_sign.as_bytes()));
    signature == EXPECTED
}

fn host_header_value(url: &Url) -> String {
    match url.port() {
        Some(port) if !is_default_port(url.scheme(), port) => {
            format!("{}:{port}", url.host_str().unwrap_or("localhost"))
        }
        Some(_) | None => url.host_str().unwrap_or("localhost").to_owned(),
    }
}

fn is_default_port(scheme: &str, port: u16) -> bool {
    matches!((scheme, port), ("http", 80) | ("https", 443))
}

fn canonical_uri(url: &Url) -> String {
    let path = url.path();
    if path.is_empty() {
        "/".to_owned()
    } else {
        path.to_owned()
    }
}

fn canonical_query(url: &Url) -> String {
    let mut pairs: Vec<(String, String)> = url
        .query_pairs()
        .map(|(k, v)| {
            (
                percent_encode_aws(k.as_ref()),
                percent_encode_aws(v.as_ref()),
            )
        })
        .collect();
    pairs.sort_by(|a, b| a.0.cmp(&b.0).then_with(|| a.1.cmp(&b.1)));
    pairs
        .into_iter()
        .map(|(k, v)| format!("{k}={v}"))
        .collect::<Vec<_>>()
        .join("&")
}

fn percent_encode_aws(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for byte in input.as_bytes() {
        match *byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(char::from(*byte));
            }
            _ => {
                out.push('%');
                out.push(hex_digit_upper(byte >> 4));
                out.push(hex_digit_upper(byte & 0x0f));
            }
        }
    }
    out
}

fn hex_digit_upper(nibble: u8) -> char {
    match nibble {
        0..=9 => char::from(b'0' + nibble),
        10..=15 => char::from(b'A' + (nibble - 10)),
        _ => '0',
    }
}

fn signing_key_for(secret_key: &str, date_stamp: &str, region: &str, service: &str) -> [u8; 32] {
    let k_date = hmac_sha256(
        format!("AWS4{secret_key}").as_bytes(),
        date_stamp.as_bytes(),
    );
    let k_region = hmac_sha256(&k_date, region.as_bytes());
    let k_service = hmac_sha256(&k_region, service.as_bytes());
    hmac_sha256(&k_service, b"aws4_request")
}

fn hmac_sha256(key: &[u8], message: &[u8]) -> [u8; 32] {
    const BLOCK: usize = 64;
    let mut key_block = [0_u8; BLOCK];
    if key.len() > BLOCK {
        let digested = Sha256::digest(key);
        if let Some(dest) = key_block.get_mut(..32) {
            dest.copy_from_slice(&digested);
        }
    } else if let Some(dest) = key_block.get_mut(..key.len()) {
        dest.copy_from_slice(key);
    }
    let mut ipad = [0x36_u8; BLOCK];
    let mut opad = [0x5c_u8; BLOCK];
    for (index, key_byte) in key_block.iter().enumerate() {
        if let Some(left) = ipad.get_mut(index) {
            *left ^= key_byte;
        }
        if let Some(right) = opad.get_mut(index) {
            *right ^= key_byte;
        }
    }
    let mut inner = Sha256::new();
    inner.update(ipad);
    inner.update(message);
    let inner_hash = inner.finalize();
    let mut outer = Sha256::new();
    outer.update(opad);
    outer.update(inner_hash);
    let digest = outer.finalize();
    let mut out = [0_u8; 32];
    out.copy_from_slice(&digest);
    out
}

#[must_use]
pub fn hex_encode(bytes: impl AsRef<[u8]>) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let bytes = bytes.as_ref();
    let mut out = String::with_capacity(bytes.len().saturating_mul(2));
    for byte in bytes {
        let high = HEX.get(usize::from(byte >> 4)).copied().unwrap_or(b'0');
        let low = HEX.get(usize::from(byte & 0x0f)).copied().unwrap_or(b'0');
        out.push(char::from(high));
        out.push(char::from(low));
    }
    out
}

#[must_use]
pub fn sha256_hex(bytes: &[u8]) -> String {
    hex_encode(Sha256::digest(bytes))
}
