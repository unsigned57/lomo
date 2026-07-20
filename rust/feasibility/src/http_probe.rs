//! Hermetic HTTPS fixture and reqwest/Rustls probes (no native TLS, no public network).
//!
//! Stage-0 P0-08 proves the **wire matrix** against a local path-style S3-shaped endpoint:
//! streaming, timeout/cancel, certificate rejection, pagination, conditional PUT, multipart
//! abort, and AWS SigV4-shaped request signing — without a public network or full AWS SDK crate
//! (volume-constrained; principles: Rustls-only, streaming, explicit errors).

use std::collections::BTreeSet;
use std::error::Error as _;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::thread;
use std::time::Duration;

use rcgen::{CertificateParams, KeyPair};
use reqwest::Certificate;
use reqwest::blocking::Client;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::{ServerConfig, ServerConnection, StreamOwned};
use sha2::{Digest, Sha256};
use thiserror::Error;

/// HTTP probe failures.
#[derive(Clone, Debug, Eq, Error, PartialEq)]
pub enum HttpProbeError {
    #[error("fixture failed: {detail}")]
    Fixture { detail: String },
    #[error("client failed: {detail}")]
    Client { detail: String },
    #[error("unexpected response: {detail}")]
    Unexpected { detail: String },
}

/// Observable counters from one HTTPS fixture lifetime.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct HttpFixtureStats {
    pub requests: u64,
    pub bytes_sent: u64,
    /// Write failures while serving `/stream-slow` (client cancel/drop surfaces here).
    pub stream_write_failures: u64,
    /// Stream request ids that observed a mid-body write failure (request-scoped cancel evidence).
    pub failed_stream_ids: BTreeSet<u64>,
}

/// Local HTTPS server serving deterministic S3-shaped and streaming routes.
pub struct HttpsFixture {
    addr: SocketAddr,
    ca_pem: String,
    shutdown: Arc<AtomicBool>,
    requests: Arc<AtomicU64>,
    bytes_sent: Arc<AtomicU64>,
    stream_write_failures: Arc<AtomicU64>,
    failed_stream_ids: Arc<Mutex<BTreeSet<u64>>>,
    join: Option<thread::JoinHandle<()>>,
}

impl HttpsFixture {
    /// Start a self-signed HTTPS fixture on an ephemeral port.
    ///
    /// # Errors
    ///
    /// Returns [`HttpProbeError`] when certificate or bind fails.
    pub fn start() -> Result<Self, HttpProbeError> {
        // reqwest/Rustls 0.23 requires an explicit process-level provider when both
        // aws-lc-rs and ring may exist on the graph.
        let _provider: Result<(), Arc<rustls::crypto::CryptoProvider>> =
            rustls::crypto::aws_lc_rs::default_provider().install_default();
        let key_pair = KeyPair::generate().map_err(fixture_err)?;
        let mut params =
            CertificateParams::new(vec!["localhost".to_owned(), "127.0.0.1".to_owned()])
                .map_err(fixture_err)?;
        params
            .distinguished_name
            .push(rcgen::DnType::CommonName, "lomo-feasibility");
        let cert = params.self_signed(&key_pair).map_err(fixture_err)?;
        let ca_pem = cert.pem();
        let cert_der = CertificateDer::from(cert.der().to_vec());
        let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_pair.serialize_der()));

        let mut server_config = ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![cert_der], key_der)
            .map_err(fixture_err)?;
        server_config.alpn_protocols = vec![b"http/1.1".to_vec()];
        let server_config = Arc::new(server_config);

        let listener = TcpListener::bind("127.0.0.1:0").map_err(fixture_err)?;
        let addr = listener.local_addr().map_err(fixture_err)?;
        let shutdown = Arc::new(AtomicBool::new(false));
        let requests = Arc::new(AtomicU64::new(0));
        let bytes_sent = Arc::new(AtomicU64::new(0));
        let stream_write_failures = Arc::new(AtomicU64::new(0));
        let stream_seq = Arc::new(AtomicU64::new(0));
        let failed_stream_ids = Arc::new(Mutex::new(BTreeSet::new()));
        let shutdown_thread = Arc::clone(&shutdown);
        let requests_thread = Arc::clone(&requests);
        let bytes_thread = Arc::clone(&bytes_sent);
        let stream_fail_thread = Arc::clone(&stream_write_failures);
        let stream_seq_thread = Arc::clone(&stream_seq);
        let failed_ids_thread = Arc::clone(&failed_stream_ids);
        // `stream_seq` is owned by the accept loop (via stream_seq_thread); not stored on Self.
        listener.set_nonblocking(true).map_err(fixture_err)?;
        let join = thread::spawn(move || {
            while !shutdown_thread.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        let config = Arc::clone(&server_config);
                        let requests = Arc::clone(&requests_thread);
                        let bytes_sent = Arc::clone(&bytes_thread);
                        let stream_write_failures = Arc::clone(&stream_fail_thread);
                        let stream_seq = Arc::clone(&stream_seq_thread);
                        let failed_stream_ids = Arc::clone(&failed_ids_thread);
                        let _worker: thread::JoinHandle<()> = thread::spawn(move || {
                            let _handled: Result<(), HttpProbeError> = handle_connection(
                                stream,
                                config,
                                &requests,
                                &bytes_sent,
                                &stream_write_failures,
                                &stream_seq,
                                &failed_stream_ids,
                            );
                        });
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(_) => break,
                }
            }
        });

        Ok(Self {
            addr,
            ca_pem,
            shutdown,
            requests,
            bytes_sent,
            stream_write_failures,
            failed_stream_ids,
            join: Some(join),
        })
    }

    #[must_use]
    pub fn base_url(&self) -> String {
        format!("https://127.0.0.1:{}", self.addr.port())
    }

    #[must_use]
    pub fn ca_pem(&self) -> &str {
        &self.ca_pem
    }

    #[must_use]
    pub fn stats(&self) -> HttpFixtureStats {
        let failed_stream_ids = self
            .failed_stream_ids
            .lock()
            .map(|guard| guard.clone())
            .unwrap_or_default();
        HttpFixtureStats {
            requests: self.requests.load(Ordering::SeqCst),
            bytes_sent: self.bytes_sent.load(Ordering::SeqCst),
            stream_write_failures: self.stream_write_failures.load(Ordering::SeqCst),
            failed_stream_ids,
        }
    }

    /// True when the fixture recorded a write failure for this stream request id.
    #[must_use]
    pub fn stream_failed(&self, stream_id: u64) -> bool {
        self.failed_stream_ids
            .lock()
            .is_ok_and(|guard| guard.contains(&stream_id))
    }
}

impl Drop for HttpsFixture {
    fn drop(&mut self) {
        self.shutdown.store(true, Ordering::SeqCst);
        // Nudge the accept loop.
        let _nudge: Result<TcpStream, std::io::Error> = TcpStream::connect(self.addr);
        if let Some(join) = self.join.take() {
            let _joined: thread::Result<()> = join.join();
        }
    }
}

/// Build a reqwest client that trusts only the fixture CA and uses Rustls.
///
/// # Errors
///
/// Returns [`HttpProbeError`] when the client cannot be constructed.
pub fn fixture_client(ca_pem: &str, timeout: Duration) -> Result<Client, HttpProbeError> {
    let cert = Certificate::from_pem(ca_pem.as_bytes()).map_err(client_err)?;
    Client::builder()
        .use_rustls_tls()
        .add_root_certificate(cert)
        .timeout(timeout)
        .connect_timeout(timeout)
        .build()
        .map_err(client_err)
}

/// GET `/echo` and verify body round-trip.
///
/// # Errors
///
/// Returns [`HttpProbeError`] on transport or body mismatch.
pub fn probe_echo(client: &Client, base_url: &str) -> Result<(), HttpProbeError> {
    let response = client
        .get(format!("{base_url}/echo"))
        .send()
        .map_err(client_err)?;
    if !response.status().is_success() {
        return Err(HttpProbeError::Unexpected {
            detail: format!("status {}", response.status()),
        });
    }
    let body = response.text().map_err(client_err)?;
    if body != "echo-ok" {
        return Err(HttpProbeError::Unexpected {
            detail: format!("body={body}"),
        });
    }
    Ok(())
}

/// Stream a large body and enforce client timeout mid-stream (deadline cancel).
///
/// # Errors
///
/// Returns [`HttpProbeError`] when the timeout does not fire or transport fails unexpectedly.
pub fn probe_stream_timeout(base_url: &str, ca_pem: &str) -> Result<(), HttpProbeError> {
    let client = fixture_client(ca_pem, Duration::from_millis(120))?;
    // Headers may arrive quickly; the timeout is enforced while draining the slow body.
    let response = client
        .get(format!("{base_url}/stream-slow"))
        .send()
        .map_err(client_err)?;
    if !response.status().is_success() {
        return Err(HttpProbeError::Unexpected {
            detail: format!("stream status {}", response.status()),
        });
    }
    // `bytes()` is used only as the timeout observation surface; the fixture delays so the full
    // body cannot complete within the client deadline (cancel-by-timeout).
    match response.bytes() {
        Err(error) if error.is_timeout() => Ok(()),
        Err(error) => Err(HttpProbeError::Client {
            detail: format!("expected timeout classification, got: {error}"),
        }),
        Ok(body) => Err(HttpProbeError::Unexpected {
            detail: format!("expected body timeout, got {} bytes", body.len()),
        }),
    }
}

/// Explicit mid-stream cancel with request-scoped server evidence.
///
/// Reads one bounded chunk, drops the response, and requires a write failure for **this**
/// stream id (not a shared global counter that prior timeout workers can race-increment).
///
/// # Errors
///
/// Returns [`HttpProbeError`] when the first chunk cannot be read or this stream id never fails.
pub fn probe_stream_cancel_drop(fixture: &HttpsFixture) -> Result<usize, HttpProbeError> {
    let client = fixture_client(fixture.ca_pem(), Duration::from_secs(5))?;
    let mut response = client
        .get(format!("{}/stream-slow", fixture.base_url()))
        .send()
        .map_err(client_err)?;
    if !response.status().is_success() {
        return Err(HttpProbeError::Unexpected {
            detail: format!("stream status {}", response.status()),
        });
    }
    let stream_id = response
        .headers()
        .get("x-lomo-stream-id")
        .and_then(optional_header_str)
        .and_then(optional_parse::<u64>)
        .ok_or_else(|| HttpProbeError::Unexpected {
            detail: "missing X-Lomo-Stream-Id response header".to_owned(),
        })?;
    let mut chunk = [0_u8; 8 * 1024];
    let n = response.read(&mut chunk).map_err(client_err)?;
    if n == 0 {
        return Err(HttpProbeError::Unexpected {
            detail: "expected first stream chunk before cancel".to_owned(),
        });
    }
    // Explicit cancel: drop the body handle without draining the remainder.
    drop(response);
    // Wait for this stream id's worker to hit a write failure after client disconnect.
    for _ in 0..100 {
        if fixture.stream_failed(stream_id) {
            return Ok(n);
        }
        thread::sleep(Duration::from_millis(20));
    }
    Err(HttpProbeError::Unexpected {
        detail: format!(
            "server did not observe write failure for stream id {stream_id} (failed_ids={:?})",
            fixture.stats().failed_stream_ids
        ),
    })
}

/// S3-shaped list pagination against the fixture.
///
/// # Errors
///
/// Returns [`HttpProbeError`] when pagination is incomplete or malformed.
pub fn probe_s3_list_pagination(client: &Client, base_url: &str) -> Result<usize, HttpProbeError> {
    let mut token: Option<String> = None;
    let mut keys = Vec::new();
    loop {
        let mut url = format!("{base_url}/s3/bucket?list-type=2&max-keys=2");
        if let Some(value) = &token {
            url.push_str("&continuation-token=");
            url.push_str(value);
        }
        let response = client.get(url).send().map_err(client_err)?;
        if !response.status().is_success() {
            return Err(HttpProbeError::Unexpected {
                detail: format!("list status {}", response.status()),
            });
        }
        let body = response.text().map_err(client_err)?;
        for line in body.lines() {
            if let Some(key) = line.strip_prefix("KEY ") {
                keys.push(key.to_owned());
            } else if let Some(next) = line.strip_prefix("NEXT ") {
                token = Some(next.to_owned());
            } else if line == "END" {
                token = None;
            }
        }
        if token.is_none() {
            break;
        }
    }
    if keys != ["a.md", "b.md", "c.md", "d.md"] {
        return Err(HttpProbeError::Unexpected {
            detail: format!("keys={keys:?}"),
        });
    }
    Ok(keys.len())
}

/// Conditional PUT that must fail with 412 when etag mismatches.
///
/// # Errors
///
/// Returns [`HttpProbeError`] when the precondition behavior is wrong.
pub fn probe_s3_conditional_put(client: &Client, base_url: &str) -> Result<(), HttpProbeError> {
    let ok = client
        .put(format!("{base_url}/s3/bucket/object.md"))
        .header("If-None-Match", "*")
        .body("hello")
        .send()
        .map_err(client_err)?;
    if ok.status().as_u16() != 200 {
        return Err(HttpProbeError::Unexpected {
            detail: format!("first put {}", ok.status()),
        });
    }
    let conflict = client
        .put(format!("{base_url}/s3/bucket/object.md"))
        .header("If-None-Match", "*")
        .body("hello-again")
        .send()
        .map_err(client_err)?;
    if conflict.status().as_u16() != 412 {
        return Err(HttpProbeError::Unexpected {
            detail: format!("expected 412, got {}", conflict.status()),
        });
    }
    Ok(())
}

/// Certificate rejection: a client that does not trust the fixture CA must fail closed.
///
/// # Errors
///
/// Returns [`HttpProbeError`] when the untrusted client incorrectly succeeds, or when the error
/// is not certificate/TLS related (timeouts alone are rejected as non-evidence).
pub fn probe_certificate_rejection(base_url: &str) -> Result<(), HttpProbeError> {
    let client = Client::builder()
        .use_rustls_tls()
        .timeout(Duration::from_secs(2))
        .connect_timeout(Duration::from_secs(2))
        .build()
        .map_err(client_err)?;
    match client.get(format!("{base_url}/echo")).send() {
        Ok(response) => Err(HttpProbeError::Unexpected {
            detail: format!(
                "untrusted client must fail TLS, got status {}",
                response.status()
            ),
        }),
        Err(error) => {
            // reqwest often wraps rustls as "error sending request"; walk the source chain.
            let mut detail = error.to_string();
            let mut source = error.source();
            while let Some(inner) = source {
                detail.push_str(" | ");
                detail.push_str(&inner.to_string());
                source = inner.source();
            }
            let lower = detail.to_ascii_lowercase();
            let cert_classified = lower.contains("certificate")
                || lower.contains("unknown issuer")
                || lower.contains("invalid peer")
                || lower.contains("not valid for name")
                || lower.contains("cert")
                || lower.contains("tls")
                || lower.contains("ssl")
                || lower.contains("handshake")
                || lower.contains("webpki")
                || lower.contains("invalidcertificate");
            if cert_classified {
                Ok(())
            } else {
                Err(HttpProbeError::Unexpected {
                    detail: format!(
                        "certificate rejection must be TLS/cert classified, got: {detail}"
                    ),
                })
            }
        }
    }
}

/// Path-style custom endpoint: host is the fixture base; bucket is the first path segment.
///
/// # Errors
///
/// Returns [`HttpProbeError`] when path-style list is not served under `/s3/bucket`.
pub fn probe_s3_path_style_endpoint(client: &Client, base_url: &str) -> Result<(), HttpProbeError> {
    // Custom endpoint = base_url; path-style = /{bucket}/... under that host (not virtual-hosted).
    let response = client
        .get(format!("{base_url}/s3/bucket?list-type=2&max-keys=2"))
        .send()
        .map_err(client_err)?;
    if !response.status().is_success() {
        return Err(HttpProbeError::Unexpected {
            detail: format!("path-style list status {}", response.status()),
        });
    }
    let body = response.text().map_err(client_err)?;
    if !body.contains("KEY a.md") {
        return Err(HttpProbeError::Unexpected {
            detail: format!("path-style body={body}"),
        });
    }
    Ok(())
}

/// Streaming upload from a chunked reader (no full-payload allocation on the client).
///
/// # Errors
///
/// Returns [`HttpProbeError`] when the stream is truncated or rejected.
pub fn probe_stream_upload(client: &Client, base_url: &str) -> Result<u64, HttpProbeError> {
    const TOTAL: u64 = 256 * 1024;
    let remaining = usize::try_from(TOTAL).map_err(client_err)?;
    // Body is a streaming reader that yields 4 KiB chunks without materializing the full buffer.
    let source = ChunkedUploadSource {
        remaining,
        chunk: 4 * 1024,
    };
    let response = client
        .put(format!("{base_url}/s3/bucket/stream.bin"))
        .header("Content-Type", "application/octet-stream")
        .header("Content-Length", TOTAL.to_string())
        .body(reqwest::blocking::Body::sized(source, TOTAL))
        .send()
        .map_err(client_err)?;
    if !response.status().is_success() {
        return Err(HttpProbeError::Unexpected {
            detail: format!("stream upload status {}", response.status()),
        });
    }
    let body = response.text().map_err(client_err)?;
    let Some(received) = body.strip_prefix("BYTES ") else {
        return Err(HttpProbeError::Unexpected {
            detail: format!("stream upload body={body}"),
        });
    };
    let received: u64 = received.trim().parse().map_err(client_err)?;
    if received != TOTAL {
        return Err(HttpProbeError::Unexpected {
            detail: format!("expected {TOTAL} bytes, got {received}"),
        });
    }
    Ok(received)
}

/// Client-side chunked upload source (bounded chunk buffer only).
struct ChunkedUploadSource {
    remaining: usize,
    chunk: usize,
}

impl Read for ChunkedUploadSource {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if self.remaining == 0 {
            return Ok(0);
        }
        let n = self.remaining.min(self.chunk).min(buf.len());
        buf[..n].fill(b'U');
        self.remaining -= n;
        Ok(n)
    }
}

/// Multipart initiate then abort; aborted upload must not leave a completed object.
///
/// # Errors
///
/// Returns [`HttpProbeError`] when initiate/abort semantics are wrong.
pub fn probe_s3_multipart_abort(client: &Client, base_url: &str) -> Result<(), HttpProbeError> {
    let init = client
        .post(format!("{base_url}/s3/bucket/multi.bin?uploads"))
        .send()
        .map_err(client_err)?;
    if init.status().as_u16() != 200 {
        return Err(HttpProbeError::Unexpected {
            detail: format!("multipart init {}", init.status()),
        });
    }
    let upload_id = init.text().map_err(client_err)?;
    let upload_id = upload_id.trim().to_owned();
    if !upload_id.starts_with("upload-") {
        return Err(HttpProbeError::Unexpected {
            detail: format!("upload id={upload_id}"),
        });
    }
    let abort = client
        .delete(format!(
            "{base_url}/s3/bucket/multi.bin?uploadId={upload_id}"
        ))
        .send()
        .map_err(client_err)?;
    if abort.status().as_u16() != 204 && abort.status().as_u16() != 200 {
        return Err(HttpProbeError::Unexpected {
            detail: format!("multipart abort {}", abort.status()),
        });
    }
    let complete = client
        .post(format!(
            "{base_url}/s3/bucket/multi.bin?uploadId={upload_id}"
        ))
        .body("<CompleteMultipartUpload/>")
        .send()
        .map_err(client_err)?;
    if complete.status().as_u16() != 404 {
        return Err(HttpProbeError::Unexpected {
            detail: format!(
                "complete after abort must be 404, got {}",
                complete.status()
            ),
        });
    }
    Ok(())
}

/// AWS SigV4-shaped signed GET against the fixture (fixed feasibility credentials).
///
/// Also checks the implementation against the AWS published S3 `SigV4` example signature so a
/// mutually-wrong signer/verifier pair cannot self-validate.
///
/// # Errors
///
/// Returns [`HttpProbeError`] when the fixture rejects a correctly signed request, accepts a
/// bad signature, or the AWS golden signature mismatches.
pub fn probe_s3_sigv4_signing(client: &Client, base_url: &str) -> Result<(), HttpProbeError> {
    verify_aws_published_sigv4_test_vector()?;
    let amz_date = "20240102T150405Z";
    let path = "/s3/bucket/signed.md";
    let host = base_url
        .strip_prefix("https://")
        .ok_or_else(|| HttpProbeError::Unexpected {
            detail: format!("base_url={base_url}"),
        })?;
    let authorization = sign_s3_get_with_keys(
        host,
        path,
        amz_date,
        EMPTY_SHA256,
        SIGV4_ACCESS_KEY,
        SIGV4_SECRET_KEY,
        SIGV4_REGION,
        SIGV4_SERVICE,
    );
    let ok = client
        .get(format!("{base_url}{path}"))
        .header("x-amz-date", amz_date)
        .header("x-amz-content-sha256", EMPTY_SHA256)
        .header("Authorization", authorization)
        .send()
        .map_err(client_err)?;
    if ok.status().as_u16() != 200 {
        return Err(HttpProbeError::Unexpected {
            detail: format!("signed get {}", ok.status()),
        });
    }
    let bad = client
        .get(format!("{base_url}{path}"))
        .header("x-amz-date", amz_date)
        .header("x-amz-content-sha256", EMPTY_SHA256)
        .header(
            "Authorization",
            "AWS4-HMAC-SHA256 Credential=bad/20240102/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=00",
        )
        .send()
        .map_err(client_err)?;
    if bad.status().as_u16() != 403 {
        return Err(HttpProbeError::Unexpected {
            detail: format!("bad signature must be 403, got {}", bad.status()),
        });
    }
    Ok(())
}

/// AWS docs example (S3 `SigV4` header-based auth) — independent oracle for the signer.
///
/// Source: Amazon S3 API Reference, Signature Version 4 signing process examples
/// (`GET Object` with `Range` for `examplebucket` on `20130524`).
fn verify_aws_published_sigv4_test_vector() -> Result<(), HttpProbeError> {
    const EXPECTED_SIGNATURE: &str =
        "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41";
    let amz_date = "20130524T000000Z";
    let date_stamp = "20130524";
    let region = "us-east-1";
    let service = "s3";
    let secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    let access = "AKIAIOSFODNN7EXAMPLE";
    let payload_hash = EMPTY_SHA256;
    let canonical_headers = format!(
        "host:examplebucket.s3.amazonaws.com\nrange:bytes=0-9\nx-amz-content-sha256:{payload_hash}\nx-amz-date:{amz_date}\n"
    );
    let signed_headers = "host;range;x-amz-content-sha256;x-amz-date";
    let canonical_request =
        format!("GET\n/test.txt\n\n{canonical_headers}\n{signed_headers}\n{payload_hash}");
    let canonical_hash = hex_sha256(canonical_request.as_bytes());
    let credential_scope = format!("{date_stamp}/{region}/{service}/aws4_request");
    let string_to_sign =
        format!("AWS4-HMAC-SHA256\n{amz_date}\n{credential_scope}\n{canonical_hash}");
    let signing_key = signing_key_for(secret, date_stamp, region, service);
    let signature = hex_encode(hmac_sha256(&signing_key, string_to_sign.as_bytes()));
    if signature != EXPECTED_SIGNATURE {
        return Err(HttpProbeError::Unexpected {
            detail: format!(
                "AWS golden SigV4 mismatch: got {signature}, expected {EXPECTED_SIGNATURE}"
            ),
        });
    }
    let authorization = format!(
        "AWS4-HMAC-SHA256 Credential={access}/{credential_scope}, SignedHeaders={signed_headers}, Signature={signature}"
    );
    if !authorization.contains(EXPECTED_SIGNATURE) {
        return Err(HttpProbeError::Unexpected {
            detail: format!("authorization header missing golden signature: {authorization}"),
        });
    }
    Ok(())
}

/// `WebDAV` method matrix on the hermetic fixture (`PROPFIND`/`MKCOL`/`PUT`/`GET`/`DELETE`).
///
/// # Errors
///
/// Returns [`HttpProbeError`] when any method fails.
pub fn probe_webdav_matrix(client: &Client, base_url: &str) -> Result<(), HttpProbeError> {
    let col = format!("{base_url}/dav/col");
    let mk = client
        .request(
            reqwest::Method::from_bytes(b"MKCOL").map_err(client_err)?,
            col.clone(),
        )
        .send()
        .map_err(client_err)?;
    if mk.status().as_u16() != 201 && mk.status().as_u16() != 200 {
        return Err(HttpProbeError::Unexpected {
            detail: format!("MKCOL {}", mk.status()),
        });
    }
    let put = client
        .put(format!("{col}/note.md"))
        .body("webdav-body")
        .send()
        .map_err(client_err)?;
    if put.status().as_u16() != 201 && put.status().as_u16() != 200 {
        return Err(HttpProbeError::Unexpected {
            detail: format!("WebDAV PUT {}", put.status()),
        });
    }
    let get = client
        .get(format!("{col}/note.md"))
        .send()
        .map_err(client_err)?;
    if get.status().as_u16() != 200 {
        return Err(HttpProbeError::Unexpected {
            detail: format!("WebDAV GET {}", get.status()),
        });
    }
    let body = get.text().map_err(client_err)?;
    if body != "webdav-body" {
        return Err(HttpProbeError::Unexpected {
            detail: format!("WebDAV body={body}"),
        });
    }
    let prop = client
        .request(
            reqwest::Method::from_bytes(b"PROPFIND").map_err(client_err)?,
            col.clone(),
        )
        .header("Depth", "1")
        .send()
        .map_err(client_err)?;
    if prop.status().as_u16() != 207 && prop.status().as_u16() != 200 {
        return Err(HttpProbeError::Unexpected {
            detail: format!("PROPFIND {}", prop.status()),
        });
    }
    let prop_body = prop.text().map_err(client_err)?;
    if !prop_body.contains("note.md") {
        return Err(HttpProbeError::Unexpected {
            detail: format!("PROPFIND body={prop_body}"),
        });
    }
    let del = client
        .delete(format!("{col}/note.md"))
        .send()
        .map_err(client_err)?;
    if del.status().as_u16() != 204 && del.status().as_u16() != 200 {
        return Err(HttpProbeError::Unexpected {
            detail: format!("WebDAV DELETE {}", del.status()),
        });
    }
    Ok(())
}

/// Full P0-08 host wire matrix against one fixture instance.
///
/// # Errors
///
/// Returns the first [`HttpProbeError`] from the matrix.
pub fn run_http_wire_matrix(fixture: &HttpsFixture) -> Result<(), HttpProbeError> {
    reset_http_probe_state();
    let client = fixture_client(fixture.ca_pem(), Duration::from_secs(5))?;
    let base = fixture.base_url();
    probe_echo(&client, &base)?;
    probe_certificate_rejection(&base)?;
    probe_stream_timeout(&base, fixture.ca_pem())?;
    probe_stream_cancel_drop(fixture)?;
    probe_stream_upload(&client, &base)?;
    probe_s3_path_style_endpoint(&client, &base)?;
    probe_s3_list_pagination(&client, &base)?;
    probe_s3_conditional_put(&client, &base)?;
    probe_s3_multipart_abort(&client, &base)?;
    probe_s3_sigv4_signing(&client, &base)?;
    probe_webdav_matrix(&client, &base)?;
    Ok(())
}

const EMPTY_SHA256: &str = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
const SIGV4_ACCESS_KEY: &str = "LOMOFEASIBILITY";
const SIGV4_SECRET_KEY: &str = "lomo-feasibility-secret-key";
const SIGV4_REGION: &str = "us-east-1";
const SIGV4_SERVICE: &str = "s3";

fn handle_connection(
    stream: TcpStream,
    config: Arc<ServerConfig>,
    requests: &AtomicU64,
    bytes_sent: &AtomicU64,
    stream_write_failures: &AtomicU64,
    stream_seq: &AtomicU64,
    failed_stream_ids: &Mutex<BTreeSet<u64>>,
) -> Result<(), HttpProbeError> {
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .map_err(fixture_err)?;
    stream
        .set_write_timeout(Some(Duration::from_secs(5)))
        .map_err(fixture_err)?;
    let connection = ServerConnection::new(config).map_err(fixture_err)?;
    let mut tls = StreamOwned::new(connection, stream);
    let mut buffer = Vec::new();
    let mut chunk = [0_u8; 8192];
    loop {
        let read = tls.read(&mut chunk).map_err(fixture_err)?;
        if read == 0 {
            break;
        }
        buffer.extend_from_slice(&chunk[..read]);
        if let Some(header_end) = find_header_end(&buffer) {
            let content_length = content_length_of(&buffer[..header_end]).unwrap_or(0);
            let total = header_end + content_length;
            while buffer.len() < total {
                let read = tls.read(&mut chunk).map_err(fixture_err)?;
                if read == 0 {
                    break;
                }
                buffer.extend_from_slice(&chunk[..read]);
            }
            break;
        }
        if buffer.len() > 16 * 1024 * 1024 {
            break;
        }
    }
    let header_end = find_header_end(&buffer).unwrap_or(buffer.len());
    let header_bytes = &buffer[..header_end];
    let body_bytes = if header_end < buffer.len() {
        &buffer[header_end..]
    } else {
        &[]
    };
    let request = String::from_utf8_lossy(header_bytes);
    let first_line = request.lines().next().unwrap_or("");
    requests.fetch_add(1, Ordering::SeqCst);
    if first_line.starts_with("GET /stream-slow ") {
        let stream_id = stream_seq.fetch_add(1, Ordering::SeqCst) + 1;
        let mark_fail = || {
            stream_write_failures.fetch_add(1, Ordering::SeqCst);
            if let Ok(mut guard) = failed_stream_ids.lock() {
                guard.insert(stream_id);
            }
        };
        // Header first (includes request-scoped id), then delayed body.
        let header = format!(
            "HTTP/1.1 200 OK\r\nContent-Length: 2097152\r\nX-Lomo-Stream-Id: {stream_id}\r\nConnection: close\r\n\r\n"
        );
        if let Err(error) = tls.write_all(header.as_bytes()) {
            mark_fail();
            return Err(fixture_err(error));
        }
        if let Err(error) = tls.flush() {
            mark_fail();
            return Err(fixture_err(error));
        }
        bytes_sent.fetch_add(header.len() as u64, Ordering::SeqCst);
        let chunk = vec![b'x'; 64 * 1024];
        for _ in 0..32 {
            thread::sleep(Duration::from_millis(50));
            if let Err(error) = tls.write_all(&chunk) {
                mark_fail();
                return Err(fixture_err(error));
            }
            if let Err(error) = tls.flush() {
                mark_fail();
                return Err(fixture_err(error));
            }
            bytes_sent.fetch_add(chunk.len() as u64, Ordering::SeqCst);
        }
        return Ok(());
    }
    let (status, body, extra_headers) = route(first_line, &request, body_bytes);
    let response = format!(
        "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n{extra_headers}\r\n{body}",
        body.len()
    );
    bytes_sent.fetch_add(response.len() as u64, Ordering::SeqCst);
    tls.write_all(response.as_bytes()).map_err(fixture_err)?;
    tls.flush().map_err(fixture_err)?;
    Ok(())
}

fn find_header_end(buffer: &[u8]) -> Option<usize> {
    buffer
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .map(|index| index + 4)
}

/// Optional header parse: malformed values are absence, not a probe failure.
///
/// Workspace forbids [`Result::ok`] via `clippy::disallowed_methods`. This helper is the
/// intentional Option boundary for optional HTTP numbers rather than erasing errors at call
/// sites with a bare `.ok()`.
#[allow(
    clippy::manual_ok_err,
    clippy::option_if_let_else,
    clippy::result_map_or_into_option,
    reason = "Result::ok is workspace-disallowed; optional header parse maps Err to None deliberately"
)]
fn optional_parse<T: std::str::FromStr>(value: &str) -> Option<T> {
    match value.trim().parse() {
        Ok(parsed) => Some(parsed),
        Err(_) => None,
    }
}

/// Optional `HeaderValue` to str: invalid UTF-8 is absence for probe header reads.
#[allow(
    clippy::manual_ok_err,
    clippy::option_if_let_else,
    clippy::result_map_or_into_option,
    reason = "Result::ok is workspace-disallowed; invalid header UTF-8 is treated as missing"
)]
fn optional_header_str(value: &reqwest::header::HeaderValue) -> Option<&str> {
    match value.to_str() {
        Ok(text) => Some(text),
        Err(_) => None,
    }
}

fn content_length_of(headers: &[u8]) -> Option<usize> {
    let text = String::from_utf8_lossy(headers);
    for line in text.lines() {
        let lower = line.to_ascii_lowercase();
        if let Some(value) = lower.strip_prefix("content-length:") {
            return optional_parse(value);
        }
    }
    None
}

fn route(first_line: &str, raw: &str, body: &[u8]) -> (&'static str, String, String) {
    if first_line.starts_with("GET /echo ") {
        return ("200 OK", "echo-ok".to_owned(), String::new());
    }
    if first_line.contains("/s3/bucket?list-type=2") {
        let token = query_value(first_line, "continuation-token");
        let (keys, next) = match token.as_deref() {
            None => (["a.md", "b.md"], Some("page-2")),
            Some("page-2") => (["c.md", "d.md"], None),
            _ => (["a.md", "b.md"], None),
        };
        let mut response_body = String::new();
        for key in keys {
            response_body.push_str("KEY ");
            response_body.push_str(key);
            response_body.push('\n');
        }
        if let Some(next_token) = next {
            response_body.push_str("NEXT ");
            response_body.push_str(next_token);
            response_body.push('\n');
        } else {
            response_body.push_str("END\n");
        }
        return ("200 OK", response_body, String::new());
    }
    if first_line.starts_with("PUT /s3/bucket/stream.bin") {
        return ("200 OK", format!("BYTES {}", body.len()), String::new());
    }
    if first_line.starts_with("POST /s3/bucket/multi.bin?uploads") {
        let id = next_upload_id();
        remember_upload(&id);
        return ("200 OK", id, String::new());
    }
    if first_line.starts_with("DELETE /s3/bucket/multi.bin?uploadId=") {
        let upload_id = query_value(first_line, "uploadId").unwrap_or_default();
        forget_upload(&upload_id);
        return ("204 No Content", String::new(), String::new());
    }
    if first_line.starts_with("POST /s3/bucket/multi.bin?uploadId=") {
        let upload_id = query_value(first_line, "uploadId").unwrap_or_default();
        if upload_exists(&upload_id) {
            forget_upload(&upload_id);
            return ("200 OK", "completed".to_owned(), String::new());
        }
        return ("404 Not Found", "upload aborted".to_owned(), String::new());
    }
    if first_line.starts_with("GET /s3/bucket/signed.md") {
        return match verify_sigv4_get(raw, "/s3/bucket/signed.md") {
            Ok(()) => ("200 OK", "signed-ok".to_owned(), String::new()),
            Err(detail) => ("403 Forbidden", detail, String::new()),
        };
    }
    if first_line.starts_with("PUT /s3/bucket/object.md") {
        let raw_lower = raw.to_ascii_lowercase();
        let if_none_match_star = raw_lower.contains("if-none-match: *");
        if if_none_match_star && object_exists() {
            return (
                "412 Precondition Failed",
                "precondition failed".to_owned(),
                String::new(),
            );
        }
        set_object_exists(true);
        return (
            "200 OK",
            "stored".to_owned(),
            "ETag: \"etag-1\"\r\n".to_owned(),
        );
    }
    if let Some(webdav) = route_webdav(first_line, body) {
        return webdav;
    }
    ("404 Not Found", "missing".to_owned(), String::new())
}

fn route_webdav(first_line: &str, body: &[u8]) -> Option<(&'static str, String, String)> {
    let mut parts = first_line.split_whitespace();
    let method = parts.next()?.to_ascii_uppercase();
    let path = parts.next()?.split('?').next()?.to_owned();
    if !path.starts_with("/dav/") {
        return None;
    }
    match method.as_str() {
        "MKCOL" => {
            webdav_mkdir(&path);
            Some(("201 Created", String::new(), String::new()))
        }
        "PUT" => {
            webdav_put(&path, body);
            Some(("201 Created", "created".to_owned(), String::new()))
        }
        "GET" => {
            let Some(bytes) = webdav_get(&path) else {
                return Some(("404 Not Found", "missing".to_owned(), String::new()));
            };
            Some((
                "200 OK",
                String::from_utf8_lossy(&bytes).into_owned(),
                String::new(),
            ))
        }
        "DELETE" => {
            webdav_delete(&path);
            Some(("204 No Content", String::new(), String::new()))
        }
        "PROPFIND" => {
            let listing = webdav_list(&path);
            Some((
                "207 Multi-Status",
                listing,
                "Content-Type: application/xml\r\n".to_owned(),
            ))
        }
        _ => None,
    }
}

fn webdav_mkdir(path: &str) {
    if let Ok(mut guard) = WEBDAV_STORE.lock() {
        guard.insert(path.trim_end_matches('/').to_owned(), Vec::new());
    }
}

fn webdav_put(path: &str, body: &[u8]) {
    if let Ok(mut guard) = WEBDAV_STORE.lock() {
        guard.insert(path.to_owned(), body.to_vec());
    }
}

fn webdav_get(path: &str) -> Option<Vec<u8>> {
    match WEBDAV_STORE.lock() {
        Ok(guard) => guard.get(path).cloned(),
        // Poisoned fixture mutex is treated as empty store for probe isolation.
        Err(_poisoned) => None,
    }
}

fn webdav_delete(path: &str) {
    if let Ok(mut guard) = WEBDAV_STORE.lock() {
        guard.remove(path);
    }
}

fn webdav_list(path: &str) -> String {
    let prefix = path.trim_end_matches('/');
    let mut names = Vec::new();
    if let Ok(guard) = WEBDAV_STORE.lock() {
        for key in guard.keys() {
            if key.starts_with(prefix)
                && key != prefix
                && let Some(name) = key.rsplit('/').next()
            {
                names.push(name.to_owned());
            }
        }
    }
    names.sort();
    let mut body = String::from("<?xml version=\"1.0\"?><multistatus>");
    for name in names {
        body.push_str("<response><href>");
        body.push_str(&name);
        body.push_str("</href></response>");
    }
    body.push_str("</multistatus>");
    body
}

fn next_upload_id() -> String {
    let id = UPLOAD_SEQ.fetch_add(1, Ordering::SeqCst) + 1;
    format!("upload-{id}")
}

fn remember_upload(id: &str) {
    if let Ok(mut guard) = ACTIVE_UPLOADS.lock() {
        guard.insert(id.to_owned());
    }
}

fn forget_upload(id: &str) {
    if let Ok(mut guard) = ACTIVE_UPLOADS.lock() {
        guard.remove(id);
    }
}

fn upload_exists(id: &str) -> bool {
    ACTIVE_UPLOADS.lock().is_ok_and(|guard| guard.contains(id))
}

fn verify_sigv4_get(raw_headers: &str, path: &str) -> Result<(), String> {
    let mut host = None;
    let mut amz_date = None;
    let mut authorization = None;
    let mut content_sha = None;
    for line in raw_headers.lines().skip(1) {
        if line.is_empty() {
            break;
        }
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        let name = name.trim().to_ascii_lowercase();
        let value = value.trim();
        match name.as_str() {
            "host" => host = Some(value.to_owned()),
            "x-amz-date" => amz_date = Some(value.to_owned()),
            "authorization" => authorization = Some(value.to_owned()),
            "x-amz-content-sha256" => content_sha = Some(value.to_owned()),
            _ => {}
        }
    }
    let host = host.ok_or_else(|| "missing host".to_owned())?;
    let amz_date = amz_date.ok_or_else(|| "missing x-amz-date".to_owned())?;
    let authorization = authorization.ok_or_else(|| "missing authorization".to_owned())?;
    let content_sha = content_sha.unwrap_or_else(|| EMPTY_SHA256.to_owned());
    let expected = sign_s3_get(&host, path, &amz_date);
    if authorization != expected {
        // Also accept if content sha differs in expected rebuild.
        let expected_with = sign_s3_get_with_payload_hash(&host, path, &amz_date, &content_sha);
        if authorization != expected_with {
            return Err("signature mismatch".to_owned());
        }
    }
    Ok(())
}

fn sign_s3_get(host: &str, path: &str, amz_date: &str) -> String {
    sign_s3_get_with_keys(
        host,
        path,
        amz_date,
        EMPTY_SHA256,
        SIGV4_ACCESS_KEY,
        SIGV4_SECRET_KEY,
        SIGV4_REGION,
        SIGV4_SERVICE,
    )
}

fn sign_s3_get_with_payload_hash(
    host: &str,
    path: &str,
    amz_date: &str,
    payload_hash: &str,
) -> String {
    sign_s3_get_with_keys(
        host,
        path,
        amz_date,
        payload_hash,
        SIGV4_ACCESS_KEY,
        SIGV4_SECRET_KEY,
        SIGV4_REGION,
        SIGV4_SERVICE,
    )
}

#[allow(
    clippy::too_many_arguments,
    reason = "SigV4 inputs are independent credentials and scope fields"
)]
fn sign_s3_get_with_keys(
    host: &str,
    path: &str,
    amz_date: &str,
    payload_hash: &str,
    access_key: &str,
    secret_key: &str,
    region: &str,
    service: &str,
) -> String {
    let date_stamp = &amz_date[..8];
    let credential_scope = format!("{date_stamp}/{region}/{service}/aws4_request");
    let canonical_headers =
        format!("host:{host}\nx-amz-content-sha256:{payload_hash}\nx-amz-date:{amz_date}\n");
    let signed_headers = "host;x-amz-content-sha256;x-amz-date";
    let canonical_request =
        format!("GET\n{path}\n\n{canonical_headers}\n{signed_headers}\n{payload_hash}");
    let canonical_hash = hex_sha256(canonical_request.as_bytes());
    let string_to_sign =
        format!("AWS4-HMAC-SHA256\n{amz_date}\n{credential_scope}\n{canonical_hash}");
    let signing_key = signing_key_for(secret_key, date_stamp, region, service);
    let signature = hex_encode(hmac_sha256(&signing_key, string_to_sign.as_bytes()));
    format!(
        "AWS4-HMAC-SHA256 Credential={access_key}/{credential_scope}, SignedHeaders={signed_headers}, Signature={signature}"
    )
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
        key_block[..32].copy_from_slice(&digested);
    } else {
        key_block[..key.len()].copy_from_slice(key);
    }
    let mut ipad = [0x36_u8; BLOCK];
    let mut opad = [0x5c_u8; BLOCK];
    for index in 0..BLOCK {
        ipad[index] ^= key_block[index];
        opad[index] ^= key_block[index];
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

fn hex_sha256(bytes: &[u8]) -> String {
    hex_encode(Sha256::digest(bytes))
}

fn hex_encode(bytes: impl AsRef<[u8]>) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let bytes = bytes.as_ref();
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(char::from(HEX[usize::from(byte >> 4)]));
        out.push(char::from(HEX[usize::from(byte & 0x0f)]));
    }
    out
}

fn object_exists() -> bool {
    OBJECT_EXISTS.load(Ordering::SeqCst)
}

fn set_object_exists(value: bool) {
    OBJECT_EXISTS.store(value, Ordering::SeqCst);
}

static OBJECT_EXISTS: AtomicBool = AtomicBool::new(false);
static UPLOAD_SEQ: AtomicU64 = AtomicU64::new(0);
static ACTIVE_UPLOADS: Mutex<std::collections::BTreeSet<String>> =
    Mutex::new(std::collections::BTreeSet::new());
static WEBDAV_STORE: Mutex<std::collections::BTreeMap<String, Vec<u8>>> =
    Mutex::new(std::collections::BTreeMap::new());

fn query_value(line: &str, key: &str) -> Option<String> {
    let path = line.split_whitespace().nth(1)?;
    let query = path.split('?').nth(1)?;
    for pair in query.split('&') {
        let mut parts = pair.splitn(2, '=');
        if parts.next()? == key {
            return parts.next().map(str::to_owned);
        }
    }
    None
}

fn fixture_err(error: impl std::fmt::Display) -> HttpProbeError {
    HttpProbeError::Fixture {
        detail: error.to_string(),
    }
}

fn client_err(error: impl std::fmt::Display) -> HttpProbeError {
    HttpProbeError::Client {
        detail: error.to_string(),
    }
}

/// Reset fixture process state between tests.
pub fn reset_http_probe_state() {
    set_object_exists(false);
    if let Ok(mut guard) = ACTIVE_UPLOADS.lock() {
        guard.clear();
    }
    if let Ok(mut guard) = WEBDAV_STORE.lock() {
        guard.clear();
    }
    UPLOAD_SEQ.store(0, Ordering::SeqCst);
}
