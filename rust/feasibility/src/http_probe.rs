//! Hermetic HTTPS fixture and reqwest/Rustls probes (no native TLS, no public network).

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::thread;
use std::time::Duration;

use rcgen::{CertificateParams, KeyPair};
use reqwest::Certificate;
use reqwest::blocking::Client;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::{ServerConfig, ServerConnection, StreamOwned};
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
}

/// Local HTTPS server serving deterministic S3-shaped and streaming routes.
pub struct HttpsFixture {
    addr: SocketAddr,
    ca_pem: String,
    shutdown: Arc<AtomicBool>,
    requests: Arc<AtomicU64>,
    bytes_sent: Arc<AtomicU64>,
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
        let shutdown_thread = Arc::clone(&shutdown);
        let requests_thread = Arc::clone(&requests);
        let bytes_thread = Arc::clone(&bytes_sent);
        listener.set_nonblocking(true).map_err(fixture_err)?;
        let join = thread::spawn(move || {
            while !shutdown_thread.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        let config = Arc::clone(&server_config);
                        let requests = Arc::clone(&requests_thread);
                        let bytes_sent = Arc::clone(&bytes_thread);
                        let _worker: thread::JoinHandle<()> = thread::spawn(move || {
                            let _handled: Result<(), HttpProbeError> =
                                handle_connection(stream, config, &requests, &bytes_sent);
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
        HttpFixtureStats {
            requests: self.requests.load(Ordering::SeqCst),
            bytes_sent: self.bytes_sent.load(Ordering::SeqCst),
        }
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

/// Stream a large body and enforce timeout cancellation.
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
    match response.bytes() {
        Err(error) if error.is_timeout() || error.is_body() || error.is_request() => Ok(()),
        Err(error) => Err(HttpProbeError::Client {
            detail: error.to_string(),
        }),
        Ok(body) => Err(HttpProbeError::Unexpected {
            detail: format!("expected body timeout, got {} bytes", body.len()),
        }),
    }
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

fn handle_connection(
    stream: TcpStream,
    config: Arc<ServerConfig>,
    requests: &AtomicU64,
    bytes_sent: &AtomicU64,
) -> Result<(), HttpProbeError> {
    stream
        .set_read_timeout(Some(Duration::from_secs(2)))
        .map_err(fixture_err)?;
    stream
        .set_write_timeout(Some(Duration::from_secs(2)))
        .map_err(fixture_err)?;
    let connection = ServerConnection::new(config).map_err(fixture_err)?;
    let mut tls = StreamOwned::new(connection, stream);
    let mut buffer = Vec::new();
    let mut chunk = [0_u8; 1024];
    loop {
        let read = tls.read(&mut chunk).map_err(fixture_err)?;
        if read == 0 {
            break;
        }
        buffer.extend_from_slice(&chunk[..read]);
        if buffer.windows(4).any(|window| window == b"\r\n\r\n") {
            break;
        }
        if buffer.len() > 64 * 1024 {
            break;
        }
    }
    let request = String::from_utf8_lossy(&buffer);
    let first_line = request.lines().next().unwrap_or("");
    requests.fetch_add(1, Ordering::SeqCst);
    if first_line.starts_with("GET /stream-slow ") {
        // Header first, then delayed body so short client timeouts fire mid-stream.
        let header = "HTTP/1.1 200 OK\r\nContent-Length: 2097152\r\nConnection: close\r\n\r\n";
        tls.write_all(header.as_bytes()).map_err(fixture_err)?;
        tls.flush().map_err(fixture_err)?;
        bytes_sent.fetch_add(header.len() as u64, Ordering::SeqCst);
        let chunk = vec![b'x'; 64 * 1024];
        for _ in 0..32 {
            thread::sleep(Duration::from_millis(50));
            tls.write_all(&chunk).map_err(fixture_err)?;
            tls.flush().map_err(fixture_err)?;
            bytes_sent.fetch_add(chunk.len() as u64, Ordering::SeqCst);
        }
        return Ok(());
    }
    let (status, body, extra_headers) = route(first_line, &request);
    let response = format!(
        "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n{extra_headers}\r\n{body}",
        body.len()
    );
    bytes_sent.fetch_add(response.len() as u64, Ordering::SeqCst);
    tls.write_all(response.as_bytes()).map_err(fixture_err)?;
    tls.flush().map_err(fixture_err)?;
    Ok(())
}

fn route(first_line: &str, raw: &str) -> (&'static str, String, String) {
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
        let mut body = String::new();
        for key in keys {
            body.push_str("KEY ");
            body.push_str(key);
            body.push('\n');
        }
        if let Some(next_token) = next {
            body.push_str("NEXT ");
            body.push_str(next_token);
            body.push('\n');
        } else {
            body.push_str("END\n");
        }
        return ("200 OK", body, String::new());
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
    ("404 Not Found", "missing".to_owned(), String::new())
}

fn object_exists() -> bool {
    OBJECT_EXISTS.load(Ordering::SeqCst)
}

fn set_object_exists(value: bool) {
    OBJECT_EXISTS.store(value, Ordering::SeqCst);
}

static OBJECT_EXISTS: AtomicBool = AtomicBool::new(false);

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
}
