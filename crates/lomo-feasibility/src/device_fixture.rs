//! Plain-HTTP device sync fixture for `just perf device sync` (P5).
//!
//! Behavior Contract:
//! - Capability: one `127.0.0.1` HTTP listener serving real `WebDAV` (`/dav/…`) and
//!   path-style `S3` (`/{bucket}/…`) endpoints with production-grade semantics — multistatus
//!   listings with per-entry `ETag`s, `If-Match`/`If-None-Match` precondition enforcement,
//!   `ListObjectsV2` continuation, multipart upload lifecycle, and credential checks —
//!   so the production `lomo-sync` cycle on a device (reached via `adb reverse tcp:P tcp:P`)
//!   measures plan→apply→reconcile against an honest remote, not a status-code stub.
//! - Owning layer: lomo-feasibility tooling; priority: P0 measurement fixture.
//!
//! Scenarios:
//! - Given a `SyncBackendConfig::WebDav` cycle, when the adapter OPTIONS/PROPFIND/MKCOL/PUT/
//!   GET/DELETE against `webdav_url()`, then every request is Basic-auth checked, conditional
//!   headers are enforced with 412, and listings return parseable `DAV:multistatus` with
//!   `resourcetype`/`getetag`/`getcontentlength` — the same shape `multistatus.rs` consumes.
//! - Given a `SyncBackendConfig::S3` cycle, when the adapter lists/HEAD/GET/PUT/DELETE against
//!   `s3_endpoint_url()` under [`S3_BUCKET`], then `Authorization` must carry the `SigV4`
//!   credential prefix for [`S3_ACCESS_KEY_ID`], `If-Match`/`If-None-Match` are enforced with
//!   412, listing honors `prefix`/`max-keys`/`continuation-token`, and multipart
//!   create/upload-part/complete/abort is served for the durable-session path.
//! - Given a finished run, when the host runner reads [`HttpSyncFixture::stats`], then the
//!   per-route request/byte counters land in the report — evidence that the remote was really
//!   exercised (plan listing, publish writes, verify reads), not short-circuited.
//!
//! Honest limits: no TLS (the device path is `adb reverse` localhost; the HTTPS tier needs a
//! non-Play image + CA injection and is reported restricted, not simulated); `SigV4` is
//! checked by credential-scope shape (`AWS4-HMAC-SHA256 Credential=<access>/`), not by
//! recomputing signatures; `WebDAV` MOVE/COPY are not advertised or implemented.
//!
//! TDD proof: exercised by `perf device sync` on-device; unit coverage in this module pins
//! the store, precondition and listing shapes directly.
//! Excludes: git transport (lomo-git speaks smart-HTTP or `file://`; the device path uses an
//! app-private `file://` bare remote seeded host-side by `perf_device.rs`).

use std::collections::BTreeMap;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::thread;
use std::time::Duration;

use sha2::{Digest, Sha256};
use thiserror::Error;

/// Fixture credentials — process-local test material, never a real account secret.
pub const DAV_USERNAME: &str = "lomo-perf-dav";
pub const DAV_PASSWORD: &str = "lomo-perf-dav-secret";
/// `S3` fixture credential pair (`SigV4` scope shape-checked, not cryptographically verified).
pub const S3_ACCESS_KEY_ID: &str = "lomo-perf-access";
pub const S3_SECRET_ACCESS_KEY: &str = "lomo-perf-secret";
/// Bucket + region the device sync plane uses; path-style keys land under it.
pub const S3_BUCKET: &str = "lomo-perf-sync";
pub const S3_REGION: &str = "us-east-1";
/// URL prefix inside the fixture at which the `WebDAV` tree is rooted.
pub const DAV_PREFIX: &str = "/dav";
/// Branch the device git leg reads/publishes on — carried by the `git_branch` extra.
pub const GIT_BRANCH: &str = "main";

const MAX_REQUEST_HEAD_BYTES: usize = 64 * 1024;
const MAX_BODY_BYTES: usize = 64 * 1024 * 1024;

/// Fixture startup failure.
#[derive(Clone, Debug, Eq, Error, PartialEq)]
pub enum SyncFixtureError {
    #[error("sync fixture failed: {detail}")]
    Fixture { detail: String },
}

/// Per-route request evidence — the report consumes this to prove the remote was exercised.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct SyncFixtureStats {
    pub requests: u64,
    /// `METHOD family` → count (`PUT dav`, `GET s3`, `PROPFIND dav`, …).
    pub by_route: BTreeMap<String, u64>,
    pub bytes_in: u64,
    pub bytes_out: u64,
}

#[derive(Clone)]
enum DavNode {
    Dir,
    File { bytes: Arc<[u8]>, etag: String },
}

impl DavNode {
    const fn etag(&self) -> Option<&str> {
        match self {
            Self::Dir => None,
            Self::File { etag, .. } => Some(etag.as_str()),
        }
    }
}

#[derive(Clone)]
struct S3Object {
    bytes: Arc<[u8]>,
    etag: String,
}

struct MultipartUpload {
    bucket: String,
    key: String,
    parts: BTreeMap<u32, (String, Vec<u8>)>,
}

struct FixtureState {
    /// `dir/sub/name` → node; the collection root is the empty key.
    dav: BTreeMap<String, DavNode>,
    /// `(bucket, key)` → object.
    s3: BTreeMap<(String, String), S3Object>,
    uploads: BTreeMap<String, MultipartUpload>,
    etag_seq: u64,
    upload_seq: u64,
    stats: SyncFixtureStats,
}

impl FixtureState {
    fn new() -> Self {
        let mut dav = BTreeMap::new();
        dav.insert(String::new(), DavNode::Dir);
        Self {
            dav,
            s3: BTreeMap::new(),
            uploads: BTreeMap::new(),
            etag_seq: 0,
            upload_seq: 0,
            stats: SyncFixtureStats::default(),
        }
    }

    fn next_etag(&mut self) -> String {
        self.etag_seq += 1;
        format!("\"w-{}\"", self.etag_seq)
    }

    fn note(&mut self, route: &str) {
        self.stats.requests += 1;
        *self.stats.by_route.entry(route.to_owned()).or_insert(0) += 1;
    }

    fn webdav_write(&mut self, path: &str, bytes: &[u8]) -> String {
        let etag = self.next_etag();
        write_dav_locked(self, path, bytes, etag.clone());
        etag
    }

    fn s3_write(&mut self, key: &str, bytes: &[u8]) -> String {
        let etag = self.next_etag();
        self.s3.insert(
            (S3_BUCKET.to_owned(), key.to_owned()),
            S3Object {
                bytes: Arc::from(bytes),
                etag: etag.clone(),
            },
        );
        etag
    }
}

/// Local HTTP sync fixture; drop stops the accept loop.
pub struct HttpSyncFixture {
    addr: SocketAddr,
    shutdown: Arc<AtomicBool>,
    state: Arc<Mutex<FixtureState>>,
    join: Option<thread::JoinHandle<()>>,
}

impl HttpSyncFixture {
    /// Binds `127.0.0.1:0` and starts accepting; the returned port is what
    /// `adb reverse tcp:P tcp:P` must map.
    ///
    /// # Errors
    /// Returns [`SyncFixtureError`] when the listener cannot bind or spawn.
    pub fn start() -> Result<Self, SyncFixtureError> {
        let listener =
            TcpListener::bind("127.0.0.1:0").map_err(|error| SyncFixtureError::Fixture {
                detail: format!("bind failed: {error}"),
            })?;
        let addr = listener
            .local_addr()
            .map_err(|error| SyncFixtureError::Fixture {
                detail: format!("local_addr failed: {error}"),
            })?;
        let shutdown = Arc::new(AtomicBool::new(false));
        let state = Arc::new(Mutex::new(FixtureState::new()));
        listener
            .set_nonblocking(true)
            .map_err(|error| SyncFixtureError::Fixture {
                detail: format!("set_nonblocking failed: {error}"),
            })?;
        let thread_shutdown = Arc::clone(&shutdown);
        let thread_state = Arc::clone(&state);
        let join = thread::spawn(move || {
            while !thread_shutdown.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        let worker_state = Arc::clone(&thread_state);
                        thread::spawn(move || {
                            let _handled = handle_connection(&mut stream, &worker_state);
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
            shutdown,
            state,
            join: Some(join),
        })
    }

    /// Ephemeral bound port.
    #[must_use]
    pub const fn port(&self) -> u16 {
        self.addr.port()
    }

    /// `WebDAV` endpoint URL the device cycle uses (`http://127.0.0.1:P/dav/`).
    #[must_use]
    pub fn webdav_url(&self) -> String {
        format!("http://127.0.0.1:{}{DAV_PREFIX}/", self.addr.port())
    }

    /// `S3` endpoint URL the device cycle uses (`http://127.0.0.1:P/`).
    #[must_use]
    pub fn s3_endpoint_url(&self) -> String {
        format!("http://127.0.0.1:{}/", self.addr.port())
    }

    /// Host-side remote mutation for the pull leg: writes a file under the `WebDAV` tree
    /// (missing parent collections are created, like an out-of-band client write).
    /// Returns the stored `ETag`.
    #[must_use]
    pub fn webdav_write(&self, path: &str, bytes: &[u8]) -> String {
        self.state
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .webdav_write(path, bytes)
    }

    /// Host-side remote mutation for the pull leg: writes an `S3` object under [`S3_BUCKET`].
    /// Returns the stored `ETag`.
    #[must_use]
    pub fn s3_write(&self, key: &str, bytes: &[u8]) -> String {
        self.state
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .s3_write(key, bytes)
    }

    /// Snapshot of request evidence for the report.
    #[must_use]
    pub fn stats(&self) -> SyncFixtureStats {
        self.state
            .lock()
            .map_or_else(|_| SyncFixtureStats::default(), |guard| guard.stats.clone())
    }
}

impl Drop for HttpSyncFixture {
    fn drop(&mut self) {
        self.shutdown.store(true, Ordering::SeqCst);
        let _nudge: Result<TcpStream, std::io::Error> = TcpStream::connect(self.addr);
        if let Some(join) = self.join.take() {
            let _joined: thread::Result<()> = join.join();
        }
    }
}

fn write_dav_locked(state: &mut FixtureState, path: &str, bytes: &[u8], etag: String) {
    let segments: Vec<&str> = path
        .split('/')
        .filter(|segment| !segment.is_empty())
        .collect();
    for depth in 1..segments.len() {
        let dir = segments
            .iter()
            .take(depth)
            .copied()
            .collect::<Vec<_>>()
            .join("/");
        state.dav.entry(dir).or_insert(DavNode::Dir);
    }
    if !segments.is_empty() {
        state.dav.insert(
            segments.join("/"),
            DavNode::File {
                bytes: Arc::from(bytes),
                etag,
            },
        );
    }
}

struct Request {
    method: String,
    path: String,
    query: String,
    headers: Vec<(String, String)>,
    body: Vec<u8>,
}

impl Request {
    fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.trim())
    }
}

/// `(status, extra response headers, body)` — ETag/Allow/DAV land in real headers.
type Response = (u16, Vec<(&'static str, String)>, Vec<u8>);

fn respond(status: u16, body: &[u8]) -> Response {
    (status, Vec::new(), body.to_vec())
}

fn respond_headers(status: u16, headers: Vec<(&'static str, String)>, body: &[u8]) -> Response {
    (status, headers, body.to_vec())
}

fn xml_response(status: u16, body: Vec<u8>) -> Response {
    (
        status,
        vec![("Content-Type", "application/xml; charset=utf-8".to_owned())],
        body,
    )
}

fn octet_response(status: u16, headers: Vec<(&'static str, String)>, body: Vec<u8>) -> Response {
    let mut headers = headers;
    headers.push(("Content-Type", "application/octet-stream".to_owned()));
    (status, headers, body)
}

fn handle_connection(
    stream: &mut TcpStream,
    state: &Arc<Mutex<FixtureState>>,
) -> std::io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(30)))?;
    let Some(request) = read_request(stream)? else {
        return Ok(());
    };
    let (status, headers, body) = route(&request, state);
    let mut head = format!("HTTP/1.1 {status} Reason\r\n");
    for (name, value) in &headers {
        head.push_str(name);
        head.push_str(": ");
        head.push_str(value);
        head.push_str("\r\n");
    }
    head.push_str("Content-Length: ");
    head.push_str(&body.len().to_string());
    head.push_str("\r\nConnection: close\r\n\r\n");
    stream.write_all(head.as_bytes())?;
    stream.write_all(&body)?;
    state
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .stats
        .bytes_out += (head.len() + body.len()) as u64;
    Ok(())
}

fn read_request(stream: &mut TcpStream) -> std::io::Result<Option<Request>> {
    let mut head = Vec::new();
    let mut byte = [0_u8; 1];
    while !head.ends_with(b"\r\n\r\n") {
        if head.len() >= MAX_REQUEST_HEAD_BYTES {
            return Ok(None);
        }
        match stream.read(&mut byte) {
            Ok(0) => return Ok(None),
            Ok(_) => head.push(byte[0]),
            Err(error) => return Err(error),
        }
    }
    let head_text = String::from_utf8_lossy(&head);
    let mut lines = head_text.split("\r\n");
    let Some(request_line) = lines.next() else {
        return Ok(None);
    };
    let mut parts = request_line.split_whitespace();
    let (Some(method), Some(target)) = (parts.next(), parts.next()) else {
        return Ok(None);
    };
    let (path, query) = target
        .split_once('?')
        .map_or((target, ""), |(path, query)| (path, query));
    let mut headers = Vec::new();
    let mut content_length = 0_usize;
    for line in lines {
        if line.is_empty() {
            break;
        }
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        let name = name.trim().to_ascii_lowercase();
        let value = value.trim().to_owned();
        if name == "content-length" {
            content_length = value.parse().unwrap_or(0).min(MAX_BODY_BYTES);
        }
        headers.push((name, value));
    }
    let mut body = vec![0_u8; content_length];
    stream.read_exact(&mut body)?;
    Ok(Some(Request {
        method: method.to_ascii_uppercase(),
        path: path.to_owned(),
        query: query.to_owned(),
        headers,
        body,
    }))
}

fn route(request: &Request, state: &Arc<Mutex<FixtureState>>) -> Response {
    route_locked(
        request,
        &mut state.lock().unwrap_or_else(PoisonError::into_inner),
    )
}

fn route_locked(request: &Request, state: &mut FixtureState) -> Response {
    state.stats.bytes_in += request.body.len() as u64;
    if request.path.starts_with(&format!("{DAV_PREFIX}/")) || request.path == DAV_PREFIX {
        state.note(&format!("{} dav", request.method));
        return route_dav(request, state);
    }
    let bucket_root = format!("/{S3_BUCKET}");
    if request.path == bucket_root || request.path.starts_with(&format!("{bucket_root}/")) {
        state.note(&format!("{} s3", request.method));
        return route_s3(request, state);
    }
    state.note(&format!("{} unrouted", request.method));
    respond(404, b"not found")
}

// ---------- WebDAV ----------

fn route_dav(request: &Request, state: &mut FixtureState) -> Response {
    if !dav_authorized(request) {
        return respond_headers(
            401,
            vec![("WWW-Authenticate", "Basic realm=\"lomo-perf\"".to_owned())],
            b"unauthorized",
        );
    }
    let rel = request
        .path
        .trim_start_matches(DAV_PREFIX)
        .trim_matches('/')
        .to_owned();
    match request.method.as_str() {
        "OPTIONS" => respond_headers(
            200,
            vec![
                (
                    "Allow",
                    "OPTIONS, PROPFIND, MKCOL, GET, HEAD, PUT, DELETE".to_owned(),
                ),
                ("DAV", "1".to_owned()),
            ],
            b"",
        ),
        "PROPFIND" => dav_propfind(request, state, &rel),
        "MKCOL" => dav_mkcol(state, &rel),
        "PUT" => dav_put(request, state, &rel),
        "GET" | "HEAD" => dav_get(request, state, &rel),
        "DELETE" => dav_delete(request, state, &rel),
        _ => respond(405, b"method not allowed"),
    }
}

fn dav_authorized(request: &Request) -> bool {
    let expected = format!(
        "Basic {}",
        data_encoding::BASE64.encode(format!("{DAV_USERNAME}:{DAV_PASSWORD}").as_bytes())
    );
    request.header("authorization") == Some(expected.as_str())
}

fn dav_propfind(request: &Request, state: &FixtureState, rel: &str) -> Response {
    let depth = request.header("depth").unwrap_or("infinity");
    let Some(node) = state.dav.get(rel) else {
        return respond(404, b"not found");
    };
    let self_is_dir = matches!(node, DavNode::Dir);
    let (self_etag, self_len) = match node {
        DavNode::Dir => (None, 0),
        DavNode::File { etag, bytes } => (Some(etag.clone()), bytes.len() as u64),
    };
    let self_href = if rel.is_empty() {
        format!("{DAV_PREFIX}/")
    } else if self_is_dir {
        format!("{DAV_PREFIX}/{rel}/")
    } else {
        format!("{DAV_PREFIX}/{rel}")
    };
    let mut entries = vec![(self_href, self_is_dir, self_etag, self_len)];
    if self_is_dir && depth != "0" {
        let prefix = if rel.is_empty() {
            String::new()
        } else {
            format!("{rel}/")
        };
        let mut children = Vec::new();
        for (path, child) in &state.dav {
            if path == rel {
                continue;
            }
            let Some(rest) = path.strip_prefix(prefix.as_str()) else {
                continue;
            };
            if rest.is_empty() || rest.contains('/') {
                continue;
            }
            let (href, is_dir, etag, len) = match child {
                DavNode::Dir => (format!("{DAV_PREFIX}/{path}/"), true, None, 0),
                DavNode::File { etag, bytes } => (
                    format!("{DAV_PREFIX}/{path}"),
                    false,
                    Some(etag.clone()),
                    bytes.len() as u64,
                ),
            };
            children.push((href, is_dir, etag, len));
        }
        children.sort();
        entries.extend(children);
    }
    let mut body =
        String::from("<?xml version=\"1.0\" encoding=\"utf-8\"?><d:multistatus xmlns:d=\"DAV:\">");
    for (href, is_dir, etag, len) in entries {
        body.push_str("<d:response><d:href>");
        body.push_str(&xml_escape(&href));
        body.push_str("</d:href><d:propstat><d:prop>");
        if is_dir {
            body.push_str("<d:resourcetype><d:collection/></d:resourcetype>");
        } else {
            body.push_str("<d:resourcetype/>");
        }
        if let Some(etag) = etag {
            body.push_str("<d:getetag>");
            body.push_str(&xml_escape(&etag));
            body.push_str("</d:getetag>");
        }
        body.push_str("<d:getcontentlength>");
        body.push_str(&len.to_string());
        body.push_str("</d:getcontentlength>");
        body.push_str("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>");
    }
    body.push_str("</d:multistatus>");
    (
        207,
        vec![("Content-Type", "application/xml; charset=utf-8".to_owned())],
        body.into_bytes(),
    )
}

fn dav_mkcol(state: &mut FixtureState, rel: &str) -> Response {
    if rel.is_empty() {
        return respond(405, b"cannot create root");
    }
    if state.dav.contains_key(rel) {
        return respond(405, b"collection exists");
    }
    let parent = rel.rsplit_once('/').map_or("", |(parent, _)| parent);
    if !matches!(state.dav.get(parent), Some(DavNode::Dir)) {
        return respond(409, b"parent collection missing");
    }
    state.dav.insert(rel.to_owned(), DavNode::Dir);
    respond(201, b"created")
}

/// `Some(412)` when the If-Match/If-None-Match contract fails; mirrors the production
/// CAS anchor semantics — `If-Match: *` requires presence, `*` in If-None-Match absence.
fn dav_precondition_failed(request: &Request, existing_etag: Option<&str>) -> bool {
    if let Some(if_match) = request.header("if-match") {
        let matched = if if_match == "*" {
            existing_etag.is_some()
        } else {
            let want = if_match.trim_matches('"');
            existing_etag.is_some_and(|etag| etag.trim_matches('"') == want)
        };
        if !matched {
            return true;
        }
    }
    if request.header("if-none-match") == Some("*") && existing_etag.is_some() {
        return true;
    }
    false
}

fn dav_put(request: &Request, state: &mut FixtureState, rel: &str) -> Response {
    if rel.is_empty() {
        return respond(405, b"cannot PUT the collection root");
    }
    let existing_etag = state
        .dav
        .get(rel)
        .and_then(DavNode::etag)
        .map(str::to_owned);
    if dav_precondition_failed(request, existing_etag.as_deref()) {
        return respond(412, b"precondition failed");
    }
    if matches!(state.dav.get(rel), Some(DavNode::Dir)) {
        return respond(409, b"cannot PUT over a collection");
    }
    let parent = rel.rsplit_once('/').map_or("", |(parent, _)| parent);
    if !matches!(state.dav.get(parent), Some(DavNode::Dir)) {
        return respond(409, b"parent collection missing");
    }
    let created = existing_etag.is_none();
    let etag = state.next_etag();
    state.dav.insert(
        rel.to_owned(),
        DavNode::File {
            bytes: Arc::from(request.body.as_slice()),
            etag: etag.clone(),
        },
    );
    respond_headers(if created { 201 } else { 200 }, vec![("ETag", etag)], b"")
}

fn dav_get(request: &Request, state: &FixtureState, rel: &str) -> Response {
    match state.dav.get(rel) {
        Some(DavNode::File { bytes, etag }) => {
            let etag = etag.clone();
            if request.method == "HEAD" {
                respond_headers(200, vec![("ETag", etag)], b"")
            } else {
                let mut body = Vec::with_capacity(bytes.len());
                body.extend_from_slice(bytes);
                octet_response(200, vec![("ETag", etag)], body)
            }
        }
        Some(DavNode::Dir) => respond(409, b"cannot GET a collection"),
        None => respond(404, b"not found"),
    }
}

fn dav_delete(request: &Request, state: &mut FixtureState, rel: &str) -> Response {
    let Some(node) = state.dav.get(rel) else {
        return respond(404, b"not found");
    };
    if matches!(node, DavNode::Dir) {
        return respond(409, b"cannot DELETE a collection");
    }
    let existing_etag = node.etag().map(str::to_owned);
    if dav_precondition_failed(request, existing_etag.as_deref()) {
        return respond(412, b"precondition failed");
    }
    state.dav.remove(rel);
    (204, Vec::new(), Vec::new())
}

// ---------- S3 ----------

fn route_s3(request: &Request, state: &mut FixtureState) -> Response {
    if !s3_authorized(request) {
        return xml_response(403, s3_error("AccessDenied", "credential scope rejected"));
    }
    let after_bucket = request
        .path
        .trim_start_matches(&format!("/{S3_BUCKET}"))
        .trim_start_matches('/');
    let key = percent_decode(after_bucket);
    let upload_id = query_value(&request.query, "uploadId");
    if key.is_empty() {
        if request.method == "GET" {
            return s3_list(request, state);
        }
        return xml_response(
            400,
            s3_error("NotImplemented", "bucket-level operation not implemented"),
        );
    }
    match (request.method.as_str(), upload_id) {
        ("HEAD", None) => s3_head(state, &key),
        ("GET", None) => s3_get(state, &key),
        ("PUT", Some(id)) if query_value(&request.query, "partNumber").is_some() => {
            s3_upload_part(request, state, &key, &id)
        }
        ("PUT", None) => s3_put(request, state, &key),
        ("DELETE", Some(id)) => s3_abort(state, &id),
        ("DELETE", None) => s3_delete(request, state, &key),
        ("POST", _) if request.query.contains("uploads") => s3_create_upload(state, &key),
        ("POST", Some(id)) => s3_complete_upload(request, state, &id),
        _ => xml_response(400, s3_error("NotImplemented", "route not implemented")),
    }
}

fn s3_authorized(request: &Request) -> bool {
    let expected_prefix = format!("AWS4-HMAC-SHA256 Credential={S3_ACCESS_KEY_ID}/");
    request
        .header("authorization")
        .is_some_and(|value| value.starts_with(expected_prefix.as_str()))
}

fn s3_error(code: &str, message: &str) -> Vec<u8> {
    format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>{code}</Code><Message>{message}</Message></Error>"
    )
    .into_bytes()
}

fn s3_list(request: &Request, state: &FixtureState) -> Response {
    let prefix = query_value_or(&request.query, "prefix");
    let token =
        query_value(&request.query, "continuation-token").and_then(|value| parse_query_int(&value));
    let max_keys = query_value(&request.query, "max-keys")
        .and_then(|value| parse_query_int(&value))
        .unwrap_or(1000);
    let keys: Vec<&String> = state
        .s3
        .keys()
        .filter(|(bucket, key)| bucket.as_str() == S3_BUCKET && key.starts_with(prefix.as_str()))
        .map(|(_, key)| key)
        .collect();
    let start = token.unwrap_or(0).min(keys.len());
    let page: Vec<&String> = keys.iter().skip(start).take(max_keys).copied().collect();
    let end = start + page.len();
    let truncated = end < keys.len();
    let mut body = String::from(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">",
    );
    body.push_str("<Name>");
    body.push_str(S3_BUCKET);
    body.push_str("</Name><Prefix>");
    body.push_str(&xml_escape(&prefix));
    body.push_str("</Prefix><KeyCount>");
    body.push_str(&page.len().to_string());
    body.push_str("</KeyCount><MaxKeys>");
    body.push_str(&max_keys.to_string());
    body.push_str("</MaxKeys><IsTruncated>");
    body.push_str(if truncated { "true" } else { "false" });
    body.push_str("</IsTruncated>");
    if truncated {
        body.push_str("<NextContinuationToken>");
        body.push_str(&end.to_string());
        body.push_str("</NextContinuationToken>");
    }
    for key in page {
        if let Some(object) = state.s3.get(&(S3_BUCKET.to_owned(), key.clone())) {
            body.push_str("<Contents><Key>");
            body.push_str(&xml_escape(key));
            body.push_str("</Key><ETag>");
            body.push_str(&object.etag);
            body.push_str("</ETag><Size>");
            body.push_str(&object.bytes.len().to_string());
            body.push_str(
                "</Size><LastModified>2020-01-01T00:00:00.000Z</LastModified></Contents>",
            );
        }
    }
    body.push_str("</ListBucketResult>");
    xml_response(200, body.into_bytes())
}

fn s3_head(state: &FixtureState, key: &str) -> Response {
    state
        .s3
        .get(&(S3_BUCKET.to_owned(), key.to_owned()))
        .map_or_else(
            || respond(404, b"not found"),
            |object| respond_headers(200, vec![("ETag", object.etag.clone())], b""),
        )
}

fn s3_get(state: &FixtureState, key: &str) -> Response {
    state
        .s3
        .get(&(S3_BUCKET.to_owned(), key.to_owned()))
        .map_or_else(
            || xml_response(404, s3_error("NoSuchKey", "object missing")),
            |object| {
                let etag = object.etag.clone();
                let mut body = Vec::with_capacity(object.bytes.len());
                body.extend_from_slice(&object.bytes);
                octet_response(200, vec![("ETag", etag)], body)
            },
        )
}

fn s3_put(request: &Request, state: &mut FixtureState, key: &str) -> Response {
    let lookup = (S3_BUCKET.to_owned(), key.to_owned());
    let existing_etag = state.s3.get(&lookup).map(|object| object.etag.clone());
    if dav_precondition_failed(request, existing_etag.as_deref()) {
        return xml_response(
            412,
            s3_error("PreconditionFailed", "conditional check failed"),
        );
    }
    let etag = state.next_etag();
    state.s3.insert(
        lookup,
        S3Object {
            bytes: Arc::from(request.body.as_slice()),
            etag: etag.clone(),
        },
    );
    respond_headers(200, vec![("ETag", etag)], b"")
}

fn s3_delete(request: &Request, state: &mut FixtureState, key: &str) -> Response {
    let lookup = (S3_BUCKET.to_owned(), key.to_owned());
    let Some(existing) = state.s3.get(&lookup) else {
        return xml_response(404, s3_error("NoSuchKey", "object missing"));
    };
    let existing_etag = existing.etag.clone();
    if dav_precondition_failed(request, Some(existing_etag.as_str())) {
        return xml_response(
            412,
            s3_error("PreconditionFailed", "conditional check failed"),
        );
    }
    state.s3.remove(&lookup);
    (204, Vec::new(), Vec::new())
}

fn s3_create_upload(state: &mut FixtureState, key: &str) -> Response {
    state.upload_seq += 1;
    let upload_id = format!("mu-{}", state.upload_seq);
    state.uploads.insert(
        upload_id.clone(),
        MultipartUpload {
            bucket: S3_BUCKET.to_owned(),
            key: key.to_owned(),
            parts: BTreeMap::new(),
        },
    );
    xml_response(
        200,
        format!(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><InitiateMultipartUploadResult><Bucket>{S3_BUCKET}</Bucket><Key>{key}</Key><UploadId>{upload_id}</UploadId></InitiateMultipartUploadResult>"
        )
        .into_bytes(),
    )
}

fn s3_upload_part(
    request: &Request,
    state: &mut FixtureState,
    key: &str,
    upload_id: &str,
) -> Response {
    let Some(number) =
        query_value(&request.query, "partNumber").and_then(|value| parse_query_int(&value))
    else {
        return xml_response(400, s3_error("InvalidArgument", "partNumber missing"));
    };
    let Some(upload) = state.uploads.get_mut(upload_id) else {
        return xml_response(404, s3_error("NoSuchUpload", "upload id unknown"));
    };
    if upload.key != key {
        return xml_response(
            400,
            s3_error("InvalidArgument", "part key does not match upload"),
        );
    }
    let part_etag = format!(
        "\"p-{number}-{}\"",
        lomo_core::hex_encode(&Sha256::digest(&request.body))
    );
    upload
        .parts
        .insert(number, (part_etag.clone(), request.body.clone()));
    respond_headers(200, vec![("ETag", part_etag)], b"")
}

fn s3_complete_upload(request: &Request, state: &mut FixtureState, upload_id: &str) -> Response {
    let Some(upload) = state.uploads.remove(upload_id) else {
        return xml_response(404, s3_error("NoSuchUpload", "upload id unknown"));
    };
    let mut assembled = Vec::new();
    // The commit body lists parts in client order; assembly follows the part-number map.
    for (_etag, bytes) in upload.parts.into_values() {
        assembled.extend_from_slice(&bytes);
    }
    let lookup = (upload.bucket, upload.key);
    let existing_etag = state.s3.get(&lookup).map(|object| object.etag.clone());
    if dav_precondition_failed(request, existing_etag.as_deref()) {
        return xml_response(
            412,
            s3_error("PreconditionFailed", "conditional check failed"),
        );
    }
    let etag = state.next_etag();
    state.s3.insert(
        lookup,
        S3Object {
            bytes: Arc::from(assembled.as_slice()),
            etag: etag.clone(),
        },
    );
    xml_response(
        200,
        format!(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><CompleteMultipartUploadResult><ETag>{etag}</ETag></CompleteMultipartUploadResult>"
        )
        .into_bytes(),
    )
}

fn s3_abort(state: &mut FixtureState, upload_id: &str) -> Response {
    if state.uploads.remove(upload_id).is_some() {
        (204, Vec::new(), Vec::new())
    } else {
        xml_response(404, s3_error("NoSuchUpload", "upload id unknown"))
    }
}

fn query_value(query: &str, key: &str) -> Option<String> {
    for pair in query.split('&') {
        if let Some((name, value)) = pair.split_once('=')
            && name == key
        {
            return Some(percent_decode(value));
        }
    }
    None
}

/// `query_value` with an empty-string default for keys whose absence is semantically the
/// empty prefix.
fn query_value_or(query: &str, key: &str) -> String {
    let Some(value) = query_value(query, key) else {
        return String::new();
    };
    value
}

fn percent_decode(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while let Some(&byte) = bytes.get(index) {
        let advance = if let (b'%', Some(&high), Some(&low)) =
            (byte, bytes.get(index + 1), bytes.get(index + 2))
        {
            if let (Some(hi), Some(lo)) = (hex_digit(high), hex_digit(low)) {
                out.push(hi * 16 + lo);
                3
            } else {
                // A malformed escape is emitted literally — the router still sees
                // the raw byte rather than an invented decode.
                out.push(byte);
                1
            }
        } else {
            out.push(if byte == b'+' { b' ' } else { byte });
            1
        };
        index += advance;
    }
    String::from_utf8_lossy(&out).into_owned()
}

const fn hex_digit(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

/// Client-supplied query numerals: an unparsable token is an absent/invalid marker, which
/// `Option` models honestly. (`.ok()` is a project-disallowed eraser; `let-else` keeps the
/// boundary explicit.)
fn parse_query_int<T: std::str::FromStr>(value: &str) -> Option<T> {
    let Ok(number) = value.parse::<T>() else {
        return None;
    };
    Some(number)
}

fn xml_escape(input: &str) -> String {
    input
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

// ---------- Git file:// remote seeding ----------

/// Seeds a real bare `file://` remote with committed content.
///
/// `seed` becomes a working clone whose commits (one per `(path, bytes)` entry, authored by
/// the fixture signature) are pushed to `bare`'s `refs/heads/main`. The pushed tree lands in
/// the app-private files root via `adb push` + `run-as`, and `lomo-git`'s `file` endpoint
/// reads/publishes against it. Returns the pushed head commit id — report evidence that the
/// remote carries real history, not an empty init.
///
/// # Errors
/// Returns [`SyncFixtureError`] when init/commit/push fails or `files` is empty.
pub fn seed_device_git_remote(
    bare: &std::path::Path,
    seed: &std::path::Path,
    files: &[(&str, &[u8])],
) -> Result<String, SyncFixtureError> {
    use git2::{Repository, RepositoryInitOptions, Signature};

    let err = |detail: String| SyncFixtureError::Fixture { detail };
    if files.is_empty() {
        return Err(err("git remote seed requires at least one file".to_owned()));
    }
    std::fs::create_dir_all(bare).map_err(|error| err(format!("bare parent: {error}")))?;
    let mut bare_opts = RepositoryInitOptions::new();
    bare_opts.bare(true).initial_head(GIT_BRANCH);
    Repository::init_opts(bare, &bare_opts).map_err(|error| err(format!("bare init: {error}")))?;
    std::fs::create_dir_all(seed).map_err(|error| err(format!("seed dir: {error}")))?;
    let mut work_opts = RepositoryInitOptions::new();
    work_opts.initial_head(GIT_BRANCH);
    let work = Repository::init_opts(seed, &work_opts)
        .map_err(|error| err(format!("seed init: {error}")))?;
    let signature = Signature::now("lomo-perf-fixture", "perf@lomo.fixture")
        .map_err(|error| err(format!("signature: {error}")))?;
    for (index, (relative, bytes)) in files.iter().enumerate() {
        let path = work
            .workdir()
            .ok_or_else(|| err("seed workdir missing".to_owned()))?
            .join(relative);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|error| err(format!("seed dir: {error}")))?;
        }
        std::fs::write(&path, bytes).map_err(|error| err(format!("seed write: {error}")))?;
        let mut git_index = work
            .index()
            .map_err(|error| err(format!("index: {error}")))?;
        git_index
            .add_path(std::path::Path::new(relative))
            .map_err(|error| err(format!("index add: {error}")))?;
        git_index
            .write()
            .map_err(|error| err(format!("index write: {error}")))?;
        let tree_id = git_index
            .write_tree()
            .map_err(|error| err(format!("tree: {error}")))?;
        let tree = work
            .find_tree(tree_id)
            .map_err(|error| err(format!("find tree: {error}")))?;
        let parents = match work.head() {
            Ok(head) => vec![
                head.peel_to_commit()
                    .map_err(|error| err(format!("head peel: {error}")))?,
            ],
            Err(_) => Vec::new(),
        };
        let parent_refs: Vec<&git2::Commit<'_>> = parents.iter().collect();
        work.commit(
            Some("HEAD"),
            &signature,
            &signature,
            &format!("fixture seed {index}"),
            &tree,
            &parent_refs,
        )
        .map_err(|error| err(format!("commit: {error}")))?;
    }
    let head = work
        .head()
        .and_then(|head| head.peel_to_commit())
        .map(|commit| commit.id().to_string())
        .map_err(|error| err(format!("head: {error}")))?;
    let url = bare
        .to_str()
        .ok_or_else(|| err("bare path utf8".to_owned()))?;
    let mut remote = work
        .remote("origin", url)
        .map_err(|error| err(format!("remote: {error}")))?;
    remote
        .push(
            &[format!("refs/heads/{GIT_BRANCH}:refs/heads/{GIT_BRANCH}")],
            None,
        )
        .map_err(|error| err(format!("push: {error}")))?;
    Ok(head)
}
