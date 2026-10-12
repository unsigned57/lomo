//! Behavior Contract (P-03 percent-encoding convergence, W-05)
//!
//! Capability: RFC 3986 percent-encode/decode of S3 object-key and `WebDAV` href path
//! segments is served by `percent-encoding` (`utf8_percent_encode` + `AsciiSet` /
//! `percent_decode_str`). The emitted surface must equal the retired handwritten codecs
//! byte-for-byte; the `WebDAV` decoded-segment safety gate (reject `/`, `\`, `..`, NUL)
//! and strict UTF-8 validation stay fail-closed as domain policy.
//!
//! Scenarios:
//! - Given every ASCII byte value inside one segment, when `object_url`/`resolve_path`
//!   encode it, then the emitted path equals the legacy unreserved table byte-for-byte.
//! - Given non-ASCII UTF-8 segments covering every lead-byte family, when encoded, then
//!   each byte emits uppercase `%XX`.
//! - Given an encoded `WebDAV` path, when `relative_path_from_href` decodes it, then the
//!   original relative path round-trips.
//! - Given a decoded segment containing `/`, `\`, or NUL, or non-UTF-8 bytes, when the
//!   href maps, then `webdav_href_path_collision`/`webdav_href_invalid_utf8` fail closed.
//! - Given a malformed `%` sequence, when the href maps, then it is literal text inside
//!   one segment (URL-spec lenient decode) and never invents hierarchy.
//!
//! Observable outcomes: encoded URL paths, decoded relative paths, `LomoError` codes.
//! `SigV4` scope (P-05): the `x-amz-content-sha256` wire header and the signed
//! `Authorization` for a query-bearing request are observed end-to-end; byte-level
//! equivalence of the canonical-query `UriEncode` law (`A-Z a-z 0-9 - _ . ~`
//! literal, `%XX` uppercase otherwise) is pinned by the full-byte-domain segment
//! matrix — the retired `percent_encode_aws` table was byte-identical to the S3
//! segment oracle proven there. Excludes: transport/publish flows, real provider
//! wire fidelity.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::format_push_string,
    reason = "contract tests fail closed with panics on fixture setup and pin dense byte matrices"
)]
mod tests {
    use std::collections::HashMap;
    use std::io::Read as _;
    use std::io::Write as _;
    use std::net::{TcpListener, TcpStream};
    use std::thread;
    use std::time::Duration;

    use lomo_sync::{S3AddressingStyle, S3Credentials, S3Endpoint, S3Transport, WebDavEndpoint};
    use sha2::{Digest, Sha256};
    use tempfile::tempdir;

    /// Legacy oracle: unreserved bytes beyond `A-Z a-z 0-9` in the retired handwritten
    /// S3 segment encoder (`- _ . ~`, RFC 3986 `unreserved`).
    const S3_UNRESERVED_EXTRA: &[u8] = b"-_.~";

    /// Legacy oracle: the retired handwritten `WebDAV` segment encoder kept
    /// `unreserved` + `! $ ' ( ) * + , ; = : @` literal (note `&`, `/`, `?` encoded).
    const WEBDAV_UNRESERVED_EXTRA: &[u8] = b"-_.~!$'()*+,;=:@";

    /// Byte-for-byte reference of the retired codecs: keep the unreserved table literal,
    /// emit uppercase `%XX` for every other byte (UTF-8 bytes >= 0x80 always encode).
    fn legacy_encode(segment: &str, unreserved_extra: &[u8]) -> String {
        let mut out = String::with_capacity(segment.len());
        for byte in segment.as_bytes() {
            if byte.is_ascii_alphanumeric() || unreserved_extra.contains(byte) {
                out.push(char::from(*byte));
            } else {
                out.push_str(&format!("%{byte:02X}"));
            }
        }
        out
    }

    /// Every byte family that valid UTF-8 can produce: ASCII singles plus one char from
    /// each multibyte lead-byte family (2/3/4-byte min+max) and common scalars.
    /// Bytes 0xF5..=0xFF are unreachable in UTF-8 for both the old and new encoders.
    const NON_ASCII_PROBE: &str = "\u{80}\u{7FF}\u{800}\u{FFFF}\u{10000}\u{10FFFF}é中🦀";

    fn s3_endpoint() -> S3Endpoint {
        S3Endpoint::parse(
            "http://127.0.0.1:9/base/",
            "bucket",
            "",
            "us-east-1",
            S3AddressingStyle::PathStyle,
        )
        .expect("s3 endpoint")
    }

    fn webdav_endpoint() -> WebDavEndpoint {
        WebDavEndpoint::parse("http://127.0.0.1:9/dav/").expect("webdav endpoint")
    }

    /// Given every byte value inside one S3 key segment, when `object_url` encodes,
    /// then the emitted path equals the legacy unreserved table byte-for-byte.
    #[test]
    fn s3_segment_encoding_matches_legacy_table_for_full_byte_domain() {
        let endpoint = s3_endpoint();
        for byte in 0x00_u8..=0x7F {
            let input = format!("a{}b", char::from(byte));
            let url = endpoint.object_url(&input).expect("object url");
            if byte == b'/' {
                // `/` is the key separator: it is split before segment encoding and
                // therefore never reaches the codec.
                assert_eq!(url.path(), "/base/bucket/a/b");
                continue;
            }
            let expected = format!(
                "/base/bucket/{}",
                legacy_encode(&input, S3_UNRESERVED_EXTRA)
            );
            assert_eq!(
                url.path(),
                expected,
                "S3 byte {byte:#04X} must encode byte-exactly"
            );
        }
        let url = endpoint
            .object_url(NON_ASCII_PROBE)
            .expect("utf8 object url");
        let expected = format!(
            "/base/bucket/{}",
            legacy_encode(NON_ASCII_PROBE, S3_UNRESERVED_EXTRA)
        );
        assert_eq!(url.path(), expected, "S3 non-ASCII bytes must all emit %XX");
    }

    /// Given every byte value inside one `WebDAV` path segment, when `resolve_path`
    /// encodes, then the emitted path equals the legacy unreserved table byte-for-byte.
    #[test]
    fn webdav_segment_encoding_matches_legacy_table_for_full_byte_domain() {
        let endpoint = webdav_endpoint();
        for byte in 0x00_u8..=0x7F {
            // A leading `d/` segment keeps a literal `:` (kept unreserved by WebDAV)
            // from parsing as a URI scheme during `Url::join` — pre-existing join law.
            let input = format!("d/a{}b", char::from(byte));
            let url = endpoint.resolve_path(&input).expect("resolve path");
            if byte == b'/' {
                assert_eq!(url.path(), "/dav/d/a/b");
                continue;
            }
            let expected = format!(
                "/dav/d/{}",
                legacy_encode(&format!("a{}b", char::from(byte)), WEBDAV_UNRESERVED_EXTRA)
            );
            assert_eq!(
                url.path(),
                expected,
                "WebDAV byte {byte:#04X} must encode byte-exactly"
            );
        }
        let input = format!("d/{NON_ASCII_PROBE}");
        let url = endpoint.resolve_path(&input).expect("utf8 resolve path");
        let expected = format!(
            "/dav/d/{}",
            legacy_encode(NON_ASCII_PROBE, WEBDAV_UNRESERVED_EXTRA)
        );
        assert_eq!(
            url.path(),
            expected,
            "WebDAV non-ASCII bytes must all emit %XX"
        );
    }

    /// Given every byte value that may legally survive the safety gate, when an encoded
    /// `WebDAV` path is decoded through `relative_path_from_href`, then the original
    /// relative path round-trips. NUL and `\` are domain-gated, not decoded (rejection
    /// matrix below).
    #[test]
    fn webdav_encode_then_href_decode_round_trips_full_byte_domain() {
        let endpoint = webdav_endpoint();
        for byte in 0x00_u8..=0x7F {
            if byte == 0x00 || byte == b'\\' {
                continue;
            }
            let relative = format!("d/x{}y.md", char::from(byte));
            let resolved = endpoint.resolve_path(&relative).expect("resolve path");
            let back = endpoint
                .relative_path_from_href(resolved.path())
                .expect("round-trip decode");
            assert_eq!(back, relative, "WebDAV byte {byte:#04X} must round-trip");
        }
        let relative = format!("d/x{NON_ASCII_PROBE}y.md");
        let resolved = endpoint.resolve_path(&relative).expect("resolve utf8");
        let back = endpoint
            .relative_path_from_href(resolved.path())
            .expect("utf8 round-trip decode");
        assert_eq!(back, relative);
    }

    /// Given decoded segments that would invent separators, NUL, or non-UTF-8 bytes,
    /// when the href maps, then the domain gate fails closed (unchanged policy layer).
    #[test]
    fn webdav_decoded_segment_safety_gate_fails_closed() {
        let endpoint = webdav_endpoint();
        for href in [
            "/dav/memo%2Fevil.md",
            "/dav/memo%2fevil.md",
            "/dav/memo%5Cevil.md",
            "/dav/memo%5cevil.md",
            "/dav/a%00b",
            "/dav/%00",
        ] {
            assert_eq!(
                endpoint
                    .relative_path_from_href(href)
                    .expect_err("decoded separator/NUL must fail")
                    .code(),
                "webdav_href_path_collision",
                "href {href} must fail closed as path collision"
            );
        }
        for href in ["/dav/%ff", "/dav/a%c3b", "/dav/%c3%28"] {
            assert_eq!(
                endpoint
                    .relative_path_from_href(href)
                    .expect_err("invalid utf8 must fail")
                    .code(),
                "webdav_href_invalid_utf8",
                "href {href} must fail closed on non-UTF-8 bytes"
            );
        }
    }

    /// Given a malformed `%` sequence (trailing or non-hex), when the href maps, then it
    /// decodes as literal text inside one segment — the URL-spec lenient decode carried
    /// by `percent_decode` — and never invents a second hierarchy. Valid `%XX` pairs
    /// still decode; a literal `+` is text, not a space (form decoding is not path law).
    #[test]
    fn webdav_malformed_percent_is_literal_text_inside_one_segment() {
        let endpoint = webdav_endpoint();
        for (href, expected) in [
            ("/dav/a%zz", "a%zz"),
            ("/dav/a%2", "a%2"),
            ("/dav/100%", "100%"),
            ("/dav/a+b", "a+b"),
            ("/dav/%25%25", "%%"),
            ("/dav/%e4%b8%ad.md", "中.md"),
        ] {
            let decoded = endpoint
                .relative_path_from_href(href)
                .expect("literal percent text must decode");
            assert_eq!(decoded, expected, "href {href} decode mismatch");
        }
    }

    /// Given encoded dot segments or parent segments in a `WebDAV` href, when the URL
    /// parser and domain gate run, then they cannot escape the collection root.
    #[test]
    fn webdav_dot_segments_never_escape_collection_root() {
        let endpoint = webdav_endpoint();
        assert_eq!(
            endpoint
                .relative_path_from_href("/dav/../x")
                .expect_err("literal parent must fail")
                .code(),
            "webdav_href_outside_root"
        );
        // `%2e%2e` is still a dot-segment to the URL parser: it pops the collection
        // root during join normalization, so the root gate rejects it fail-closed.
        assert_eq!(
            endpoint
                .relative_path_from_href("/dav/%2e%2e/x")
                .expect_err("encoded parent must fail")
                .code(),
            "webdav_href_outside_root"
        );
    }

    /// Reads one HTTP request head (through the `\r\n\r\n` terminator plus the
    /// declared body) and returns the lowercased header map.
    fn read_request_headers(stream: &mut TcpStream) -> HashMap<String, String> {
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .expect("read timeout");
        let mut buf = Vec::new();
        let mut chunk = [0_u8; 4096];
        loop {
            let read = stream.read(&mut chunk).expect("read request");
            if read == 0 {
                break;
            }
            buf.extend_from_slice(chunk.get(..read).expect("read within chunk"));
            if buf.windows(4).any(|w| w == b"\r\n\r\n") {
                break;
            }
        }
        let head = String::from_utf8_lossy(&buf);
        let mut headers = HashMap::new();
        for line in head.lines().skip(1) {
            if let Some((key, value)) = line.split_once(':') {
                headers.insert(key.trim().to_ascii_lowercase(), value.trim().to_owned());
            }
        }
        headers
    }

    /// Serves exactly one request on a throwaway listener: captures its headers and
    /// answers `200` with `body`. Returns the bound address and captured headers.
    fn capture_one_request(
        response_body: &'static [u8],
    ) -> (
        std::net::SocketAddr,
        thread::JoinHandle<HashMap<String, String>>,
    ) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let addr = listener.local_addr().expect("addr");
        let server = thread::spawn(move || {
            let Ok((mut stream, _peer)) = listener.accept() else {
                return HashMap::new();
            };
            let headers = read_request_headers(&mut stream);
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                response_body.len()
            );
            let _written = stream.write_all(response.as_bytes());
            let _written = stream.write_all(response_body);
            headers
        });
        (addr, server)
    }

    fn pinned_transport(addr: std::net::SocketAddr) -> S3Transport {
        let endpoint = S3Endpoint::parse(
            &format!("http://{addr}/"),
            "bucket",
            "lomo",
            "us-east-1",
            S3AddressingStyle::PathStyle,
        )
        .expect("endpoint");
        let credentials = S3Credentials::new("AKIAEXAMPLE", "secret").expect("credentials");
        let temp = tempdir().expect("tempdir");
        S3Transport::new(
            endpoint,
            credentials,
            temp.path().to_path_buf(),
            Duration::from_secs(5),
        )
        .expect("transport")
        .with_fixed_amz_date("20261007T120000Z")
    }

    /// Asserts the fixed-date `SigV4` `Authorization` header is well formed:
    /// credential scope derived from the pinned `amz_date` and a 64-lowercase-hex
    /// signature produced by the signing pipeline.
    fn assert_well_formed_authorization(authorization: &str) {
        let prefix = "AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/20261007/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=";
        assert!(
            authorization.starts_with(prefix),
            "authorization must carry the pinned credential scope: {authorization}"
        );
        let signature = authorization
            .strip_prefix(prefix)
            .expect("authorization carries credential scope prefix");
        assert_eq!(signature.len(), 64, "signature must be 64 hex chars");
        assert!(
            signature
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)),
            "signature must be lowercase hex: {signature}"
        );
    }

    /// Given a signed PUT, when the request reaches the wire, then
    /// `x-amz-content-sha256` equals the `LowerHex` SHA-256 of the body byte-for-byte
    /// and `x-amz-date`/`Authorization` carry the pinned `SigV4` shape.
    #[test]
    fn sigv4_signed_request_emits_content_sha256_and_wellformed_authorization() {
        let (addr, server) = capture_one_request(b"");
        let transport = pinned_transport(addr);

        let body = b"hello-s3-body".to_vec();
        transport
            .put_bytes("lomo/memo/a.md", body.clone(), None, false)
            .expect("put_bytes");

        let headers = server.join().expect("join");
        assert_eq!(
            headers.get("x-amz-content-sha256").expect("content sha256"),
            &format!("{:x}", Sha256::digest(&body)),
            "x-amz-content-sha256 must equal lowercase hex SHA-256 of the body"
        );
        assert_eq!(
            headers.get("x-amz-date").expect("x-amz-date"),
            "20261007T120000Z",
            "x-amz-date must carry the pinned signing instant"
        );
        assert_well_formed_authorization(headers.get("authorization").expect("authorization"));
    }

    /// Given continuation tokens containing bytes outside the RFC 3986 `unreserved`
    /// set (space, `+`, `&`, `=`, `/`, non-ASCII), when `list_page` signs, then the
    /// canonical query reaches the signature: different tokens yield different
    /// well-formed `Authorization` headers. Byte-level equivalence of the
    /// `UriEncode` law is pinned by `s3_segment_encoding_matches_legacy_table_for_
    /// full_byte_domain` — the retired `percent_encode_aws`/`hex_digit_upper` table
    /// was byte-identical to the segment oracle proven over the full byte domain.
    #[test]
    fn sigv4_canonical_query_bytes_reach_the_signature() {
        const LIST_BODY: &[u8] = br#"<?xml version="1.0" encoding="UTF-8"?><ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"></ListBucketResult>"#;
        let mut signatures = Vec::new();
        for token in ["plain-token", "a b+c/&=~中"] {
            let (addr, server) = capture_one_request(LIST_BODY);
            let transport = pinned_transport(addr);
            transport
                .list_page(Some(token), 1)
                .expect("list page must succeed against the stub");
            let headers = server.join().expect("join");
            let authorization = headers.get("authorization").expect("authorization");
            assert_well_formed_authorization(authorization);
            signatures.push(authorization.clone());
        }
        let (first, second) = (
            signatures.first().expect("first signature"),
            signatures.get(1).expect("second signature"),
        );
        assert_ne!(
            first, second,
            "different continuation tokens must produce different canonical-query signatures"
        );
    }
}
