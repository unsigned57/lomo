//! Behavior Contract (P5-05 `WebDAV` backend adapter)
//!
//! Capability: public [`RemoteSyncPort`] `WebDAV` adapter compiles/executes intents only; core owns
//! direction/conflict/baseline/tombstone/retry. Hermetic fault server (repo-owned, no public
//! network) exercises the host matrix.
//!
//! Scenarios:
//! - Given a reachable endpoint, when preflight runs, then capabilities report `ETag`/`MOVE` facts.
//! - Given recursive Depth=1 listing with all subtrees ok, when `list_remote` runs, then completeness
//!   is Complete and path digests match `GET` bodies.
//! - Given a subtree `PROPFIND` failure, when `list_remote` runs, then completeness is Incomplete.
//! - Given `EnsurePresent` with If-None-Match, when publish succeeds, then Applied with new token.
//! - Given `EnsurePresent` with stale If-Match, when publish hits 412, then `PreconditionFailed`.
//! - Given `EnsureAbsent` with matching If-Match, when publish runs, then Applied.
//! - Given HTTP 401/403/404/409/412/423/429/5xx, when transport maps status, then stable codes +
//!   retry categories match the Stage-5 policy table.
//! - Given Multi-Status with DOCTYPE/entity, oversized body, illegal href, or path traversal,
//!   when listed, then incomplete snapshot (fail closed for deletes).
//! - Given redirect `Policy::none`, when server returns 302 off-origin, then preflight is non-success
//!   with `webdav_redirect_not_followed` (Network / `AfterUserAction`) and credentials are not
//!   automatically replayed.
//! - Given Multi-Status href with `%2F` inside one segment, when listed, then path-collision
//!   fail-closed yields Incomplete (never invents hierarchy).
//! - Given nested `EnsurePresent` when the server requires parent collections, when publish runs,
//!   then `MKCOL` parents are ensured before PUT.
//! - Given `WebDavAdapter` Incomplete listing + established baseline, when `plan_intents` runs,
//!   then no `EnsureAbsent`.
//! - Given Unicode path segments, when PUT then PROPFIND/GET round-trip, then relative path and
//!   digest match.
//! - Wave-13 residual: `list_remote_pages` override streams multi-page listings (each page ≤512)
//!   without raising single-shot `RemoteSnapshot` past 512; Incomplete listing never authorizes
//!   delete under `run_sync_cycle_streaming`; residual cycle can consume `WebDAV` paged listings
//!   host-only (dark; no production DI).
//!
//! Observable outcomes: [`RemoteCapabilities`], [`SnapshotCompleteness`], [`PathPublishStatus`],
//! `LomoError` code/category/`retry_disposition` (via [`map_http_status`] + transport boundary),
//! endpoint normalization rejections, streaming page sizes / completeness.
//! Excludes: production DI, Kotlin SAF, S3/Git adapters, six real providers, arm64 device.
//! Host-hermetic matrix only — not Nutstore/Nextcloud wire fidelity.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::panic,
    clippy::significant_drop_tightening,
    clippy::format_push_string,
    clippy::match_same_arms,
    clippy::unnecessary_wraps,
    clippy::or_fun_call,
    reason = "contract tests fail closed with panics; hermetic fault server favors readable dense helpers"
)]
mod tests {
    use std::collections::{BTreeSet, HashMap};
    use std::io::{Read, Write};
    use std::net::{SocketAddr, TcpListener, TcpStream};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex};
    use std::thread;
    use std::time::Duration;

    use lomo_core::{ErrorCategory, RetryDisposition};
    use lomo_sync::{
        BaselineHead, BatchAtomicity, ContentDigest, FakeLocalPort, MAX_ACTION_PAGE_ITEMS,
        MAX_WEBDAV_SNAPSHOT_ENTRIES, MapObjectSource, PathPublishStatus, PreparedRemoteBatch,
        ProviderNeutralIntent, RemoteSyncPort, SessionKind, SnapshotCompleteness,
        SyncIdentityFence, SyncPath, SyncSession, TombstoneSet, WebDavAdapter, WebDavCredentials,
        WebDavEndpoint, WebDavObjectSource, connect_map_source, error_category, is_same_origin,
        map_http_status, plan_intents, run_sync_cycle_streaming,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
    use sha2::{Digest, Sha256};
    use tempfile::tempdir;
    use url::Url;

    #[derive(Clone, Debug)]
    struct StoredObject {
        body: Vec<u8>,
        etag: String,
    }

    #[derive(Clone, Debug, Default)]
    #[expect(
        clippy::struct_excessive_bools,
        reason = "fault injection flags are independent hermetic toggles"
    )]
    struct FaultConfig {
        propfind_fail_prefix: Option<String>,
        force_status: Option<(String, u16)>,
        inject_doctype: bool,
        inject_off_origin_href: bool,
        inject_traversal_href: bool,
        inject_path_collision_href: bool,
        options_no_move: bool,
        redirect_off_origin: bool,
        oversized_multistatus_pad: usize,
        /// When true, PUT to nested keys requires prior MKCOL parent collections (real-server shape).
        require_parent_collections: bool,
    }

    struct FaultServer {
        addr: SocketAddr,
        shutdown: Arc<AtomicBool>,
        store: Arc<Mutex<HashMap<String, StoredObject>>>,
        collections: Arc<Mutex<BTreeSet<String>>>,
        faults: Arc<Mutex<FaultConfig>>,
        mkcol_calls: Arc<Mutex<Vec<String>>>,
        join: Option<thread::JoinHandle<()>>,
    }

    impl FaultServer {
        fn start() -> Self {
            let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
            let addr = listener.local_addr().expect("addr");
            listener.set_nonblocking(true).expect("nonblocking");
            let shutdown = Arc::new(AtomicBool::new(false));
            let store = Arc::new(Mutex::new(HashMap::new()));
            let collections = Arc::new(Mutex::new(BTreeSet::from([String::new()])));
            let faults = Arc::new(Mutex::new(FaultConfig::default()));
            let mkcol_calls = Arc::new(Mutex::new(Vec::new()));
            let shutdown_t = Arc::clone(&shutdown);
            let store_t = Arc::clone(&store);
            let collections_t = Arc::clone(&collections);
            let faults_t = Arc::clone(&faults);
            let mkcol_t = Arc::clone(&mkcol_calls);
            let join = thread::spawn(move || {
                while !shutdown_t.load(Ordering::SeqCst) {
                    match listener.accept() {
                        Ok((stream, _)) => {
                            let store = Arc::clone(&store_t);
                            let collections = Arc::clone(&collections_t);
                            let faults = Arc::clone(&faults_t);
                            let mkcol_calls = Arc::clone(&mkcol_t);
                            let _worker = thread::spawn(move || {
                                let _handled: std::io::Result<()> = handle_client(
                                    stream,
                                    &store,
                                    &collections,
                                    &faults,
                                    &mkcol_calls,
                                );
                            });
                        }
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            thread::sleep(Duration::from_millis(2));
                        }
                        Err(_) => break,
                    }
                }
            });
            Self {
                addr,
                shutdown,
                store,
                collections,
                faults,
                mkcol_calls,
                join: Some(join),
            }
        }

        fn base_url(&self) -> String {
            format!("http://127.0.0.1:{}/dav/", self.addr.port())
        }

        fn put_object(&self, relative: &str, body: &[u8]) {
            let key = relative.trim_matches('/').to_owned();
            ensure_parents_recorded(&self.collections, &key);
            let etag = format!("\"e{}\"", simple_etag(body));
            self.store.lock().expect("store").insert(
                key,
                StoredObject {
                    body: body.to_vec(),
                    etag,
                },
            );
        }

        fn set_faults(&self, config: FaultConfig) {
            *self.faults.lock().expect("faults") = config;
        }

        fn etag_of(&self, relative: &str) -> String {
            self.store
                .lock()
                .expect("store")
                .get(relative.trim_matches('/'))
                .expect("object")
                .etag
                .clone()
        }

        fn has(&self, relative: &str) -> bool {
            self.store
                .lock()
                .expect("store")
                .contains_key(relative.trim_matches('/'))
        }

        fn mkcol_paths(&self) -> Vec<String> {
            self.mkcol_calls.lock().expect("mkcol").clone()
        }
    }

    fn ensure_parents_recorded(collections: &Mutex<BTreeSet<String>>, key: &str) {
        let Some((parent, _)) = key.rsplit_once('/') else {
            return;
        };
        let mut guard = collections.lock().expect("collections");
        let mut acc = String::new();
        for segment in parent.split('/') {
            if segment.is_empty() {
                continue;
            }
            if !acc.is_empty() {
                acc.push('/');
            }
            acc.push_str(segment);
            guard.insert(acc.clone());
        }
    }

    impl Drop for FaultServer {
        fn drop(&mut self) {
            self.shutdown.store(true, Ordering::SeqCst);
            let _nudge: Result<TcpStream, std::io::Error> = TcpStream::connect(self.addr);
            if let Some(join) = self.join.take() {
                let _joined: thread::Result<()> = join.join();
            }
        }
    }

    fn simple_etag(body: &[u8]) -> String {
        format!("{:x}", Sha256::digest(body))
            .chars()
            .take(16)
            .collect()
    }

    fn handle_client(
        mut stream: TcpStream,
        store: &Mutex<HashMap<String, StoredObject>>,
        collections: &Mutex<BTreeSet<String>>,
        faults: &Mutex<FaultConfig>,
        mkcol_calls: &Mutex<Vec<String>>,
    ) -> std::io::Result<()> {
        stream.set_read_timeout(Some(Duration::from_secs(2)))?;
        stream.set_write_timeout(Some(Duration::from_secs(2)))?;
        let request = read_request(&mut stream)?;
        if request.is_empty() {
            return Ok(());
        }
        let (method, path, headers, body) = parse_http_request(&request);
        let fault = faults.lock().expect("faults").clone();
        if fault.redirect_off_origin {
            stream.write_all(
                b"HTTP/1.1 302 Found\r\nLocation: https://evil.example/steal\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            )?;
            return Ok(());
        }
        if let Some((force_method, status)) = &fault.force_status
            && force_method.eq_ignore_ascii_case(&method)
        {
            return write_status(&mut stream, *status, b"forced");
        }
        let rel = normalize_request_path(&path);
        match method.as_str() {
            "OPTIONS" => write_options(&mut stream, fault.options_no_move),
            "PROPFIND" => write_propfind(&mut stream, &rel, &headers, store, &fault),
            "GET" => write_get(&mut stream, &rel, store),
            "PUT" => write_put(
                &mut stream,
                &rel,
                &headers,
                body,
                store,
                collections,
                fault.require_parent_collections,
            ),
            "DELETE" => write_delete(&mut stream, &rel, &headers, store),
            "MKCOL" => write_mkcol(&mut stream, &rel, collections, mkcol_calls),
            _ => write_status(&mut stream, 405, b"method"),
        }
    }

    fn write_mkcol(
        stream: &mut TcpStream,
        rel: &str,
        collections: &Mutex<BTreeSet<String>>,
        mkcol_calls: &Mutex<Vec<String>>,
    ) -> std::io::Result<()> {
        let key = rel.trim_matches('/').to_owned();
        if key.is_empty() {
            return write_status(stream, 405, b"root");
        }
        mkcol_calls.lock().expect("mkcol").push(key.clone());
        let mut guard = collections.lock().expect("collections");
        if guard.contains(&key) {
            return write_status(stream, 405, b"exists");
        }
        // Parent of this collection must exist (except top-level under /dav/).
        if let Some((parent, _)) = key.rsplit_once('/')
            && !guard.contains(parent)
        {
            return write_status(stream, 409, b"missing parent");
        }
        guard.insert(key);
        write_status(stream, 201, b"")
    }

    fn write_options(stream: &mut TcpStream, no_move: bool) -> std::io::Result<()> {
        let allow = if no_move {
            "OPTIONS, GET, PUT, DELETE, PROPFIND"
        } else {
            "OPTIONS, GET, PUT, DELETE, PROPFIND, MOVE, COPY"
        };
        let resp = format!(
            "HTTP/1.1 200 OK\r\nAllow: {allow}\r\nDAV: 1,2\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        );
        stream.write_all(resp.as_bytes())
    }

    fn write_propfind(
        stream: &mut TcpStream,
        rel: &str,
        headers: &HashMap<String, String>,
        store: &Mutex<HashMap<String, StoredObject>>,
        fault: &FaultConfig,
    ) -> std::io::Result<()> {
        if let Some(prefix) = &fault.propfind_fail_prefix
            && (rel.starts_with(prefix.trim_matches('/')) || rel.contains(prefix.as_str()))
        {
            return write_status(stream, 500, b"subtree fail");
        }
        let depth = headers
            .get("depth")
            .and_then(|value| optional_u32(value))
            .unwrap_or(0);
        let xml = build_multistatus(rel, depth, store, fault);
        let resp = format!(
            "HTTP/1.1 207 Multi-Status\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            xml.len()
        );
        stream.write_all(resp.as_bytes())?;
        stream.write_all(xml.as_bytes())
    }

    fn write_get(
        stream: &mut TcpStream,
        rel: &str,
        store: &Mutex<HashMap<String, StoredObject>>,
    ) -> std::io::Result<()> {
        let key = rel.trim_matches('/').to_owned();
        let guard = store.lock().expect("store");
        if let Some(obj) = guard.get(&key) {
            let resp = format!(
                "HTTP/1.1 200 OK\r\nETag: {}\r\nContent-Length: {}\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n",
                obj.etag,
                obj.body.len()
            );
            stream.write_all(resp.as_bytes())?;
            stream.write_all(&obj.body)
        } else {
            write_status(stream, 404, b"missing")
        }
    }

    fn write_put(
        stream: &mut TcpStream,
        rel: &str,
        headers: &HashMap<String, String>,
        body: Vec<u8>,
        store: &Mutex<HashMap<String, StoredObject>>,
        collections: &Mutex<BTreeSet<String>>,
        require_parent_collections: bool,
    ) -> std::io::Result<()> {
        let key = rel.trim_matches('/').to_owned();
        if require_parent_collections && let Some((parent, _)) = key.rsplit_once('/') {
            let guard = collections.lock().expect("collections");
            if !guard.contains(parent) {
                return write_status(stream, 409, b"missing collection");
            }
        }
        let if_match = headers.get("if-match").cloned();
        let if_none = headers
            .get("if-none-match")
            .is_some_and(|value| value.trim() == "*");
        let mut guard = store.lock().expect("store");
        if let Some(token) = if_match {
            match guard.get(&key) {
                Some(existing) if existing.etag == token || token == "*" => {}
                Some(_) | None => return write_status(stream, 412, b"precondition"),
            }
        } else if if_none && guard.contains_key(&key) {
            return write_status(stream, 412, b"precondition");
        }
        let etag = format!("\"e{}\"", simple_etag(&body));
        guard.insert(
            key.clone(),
            StoredObject {
                body,
                etag: etag.clone(),
            },
        );
        drop(guard);
        ensure_parents_recorded(collections, &key);
        let resp = format!(
            "HTTP/1.1 201 Created\r\nETag: {etag}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        );
        stream.write_all(resp.as_bytes())
    }

    fn write_delete(
        stream: &mut TcpStream,
        rel: &str,
        headers: &HashMap<String, String>,
        store: &Mutex<HashMap<String, StoredObject>>,
    ) -> std::io::Result<()> {
        let key = rel.trim_matches('/').to_owned();
        let if_match = headers.get("if-match").cloned();
        let mut guard = store.lock().expect("store");
        match guard.get(&key) {
            None => write_status(stream, 404, b"missing"),
            Some(existing) => {
                if let Some(token) = if_match
                    && existing.etag != token
                    && token != "*"
                {
                    return write_status(stream, 412, b"precondition");
                }
                guard.remove(&key);
                write_status(stream, 204, b"")
            }
        }
    }

    fn build_multistatus(
        rel: &str,
        depth: u32,
        store: &Mutex<HashMap<String, StoredObject>>,
        fault: &FaultConfig,
    ) -> String {
        if fault.oversized_multistatus_pad > 0 {
            let pad = "x".repeat(fault.oversized_multistatus_pad);
            return format!(
                "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">{pad}</d:multistatus>"
            );
        }
        if fault.inject_doctype {
            return String::from(
                "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>&xxe;</d:href></d:response></d:multistatus>",
            );
        }
        let mut responses = String::new();
        if fault.inject_off_origin_href {
            responses.push_str(
                "<d:response><d:href>https://evil.example/steal</d:href><d:propstat><d:prop><d:getetag>\"x\"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>",
            );
        }
        if fault.inject_traversal_href {
            responses.push_str(
                "<d:response><d:href>/dav/../../etc/passwd</d:href><d:propstat><d:prop><d:getetag>\"x\"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>",
            );
        }
        if fault.inject_path_collision_href {
            // %2F inside a single segment must fail closed as path collision (not invent hierarchy).
            responses.push_str(
                "<d:response><d:href>/dav/memo%2Fevil.md</d:href><d:propstat><d:prop><d:getetag>\"x\"</d:getetag><d:getcontentlength>1</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>",
            );
        }
        let root_href = if rel.is_empty() {
            "/dav/".to_owned()
        } else {
            format!("/dav/{}/", rel.trim_matches('/'))
        };
        responses.push_str(&format!(
            "<d:response><d:href>{root_href}</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        ));
        if depth >= 1 {
            append_children(&mut responses, rel, store);
        }
        format!(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:multistatus xmlns:d=\"DAV:\">{responses}</d:multistatus>"
        )
    }

    fn append_children(
        responses: &mut String,
        rel: &str,
        store: &Mutex<HashMap<String, StoredObject>>,
    ) {
        let guard = store.lock().expect("store");
        let prefix = rel.trim_matches('/');
        let mut dirs = BTreeSet::new();
        for key in guard.keys() {
            if prefix.is_empty() {
                if let Some((dir, _)) = key.split_once('/') {
                    dirs.insert(dir.to_owned());
                }
            } else if let Some(rest) = key.strip_prefix(&format!("{prefix}/"))
                && let Some((dir, _)) = rest.split_once('/')
            {
                dirs.insert(format!("{prefix}/{dir}"));
            }
        }
        for dir in dirs {
            let href = format!("/dav/{dir}/");
            responses.push_str(&format!(
                "<d:response><d:href>{href}</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
            ));
        }
        for (key, obj) in guard.iter() {
            let is_direct_child = if prefix.is_empty() {
                !key.contains('/')
            } else {
                key.strip_prefix(&format!("{prefix}/"))
                    .is_some_and(|rest| !rest.contains('/'))
            };
            if !is_direct_child {
                continue;
            }
            let href = format!("/dav/{key}");
            responses.push_str(&format!(
                "<d:response><d:href>{href}</d:href><d:propstat><d:prop><d:resourcetype/><d:getetag>{}</d:getetag><d:getcontentlength>{}</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>",
                obj.etag,
                obj.body.len()
            ));
        }
    }

    fn read_request(stream: &mut TcpStream) -> std::io::Result<Vec<u8>> {
        let mut buf = vec![0_u8; 64 * 1024];
        let mut request = Vec::new();
        loop {
            let n = match stream.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => n,
                Err(_) => break,
            };
            request.extend_from_slice(&buf[..n]);
            if let Some(header_end) = find_header_end(&request) {
                if let Some(cl) = content_length(&request) {
                    let body_len = request.len().saturating_sub(header_end);
                    if body_len >= cl {
                        break;
                    }
                } else {
                    break;
                }
            }
            if request.len() > 8 * 1024 * 1024 {
                break;
            }
        }
        Ok(request)
    }

    fn normalize_request_path(path: &str) -> String {
        let decoded = path.split('?').next().unwrap_or(path);
        let without = decoded
            .strip_prefix("/dav/")
            .or_else(|| decoded.strip_prefix("/dav"))
            .unwrap_or(decoded.trim_start_matches('/'));
        percent_decode_simple(without.trim_matches('/'))
    }

    fn percent_decode_simple(input: &str) -> String {
        let bytes = input.as_bytes();
        let mut out = Vec::with_capacity(bytes.len());
        let mut i = 0;
        while i < bytes.len() {
            if bytes[i] == b'%' && i + 2 < bytes.len() {
                let a = bytes[i + 1];
                let b = bytes[i + 2];
                if a.is_ascii_hexdigit() && b.is_ascii_hexdigit() {
                    out.push((hex(a) << 4) | hex(b));
                    i += 3;
                    continue;
                }
            }
            out.push(bytes[i]);
            i += 1;
        }
        String::from_utf8_lossy(&out).into_owned()
    }

    const fn hex(b: u8) -> u8 {
        match b {
            b'0'..=b'9' => b - b'0',
            b'a'..=b'f' => b - b'a' + 10,
            b'A'..=b'F' => b - b'A' + 10,
            _ => 0,
        }
    }

    fn parse_http_request(raw: &[u8]) -> (String, String, HashMap<String, String>, Vec<u8>) {
        let header_end = find_header_end(raw).unwrap_or(raw.len());
        let header_text = String::from_utf8_lossy(&raw[..header_end]);
        let mut lines = header_text.split("\r\n");
        let request_line = lines.next().unwrap_or("");
        let mut parts = request_line.split_whitespace();
        let method = parts.next().unwrap_or("").to_owned();
        let path = parts.next().unwrap_or("/").to_owned();
        let mut headers = HashMap::new();
        for line in lines {
            if let Some((k, v)) = line.split_once(':') {
                headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_owned());
            }
        }
        let body = raw[header_end..].to_vec();
        (method, path, headers, body)
    }

    fn find_header_end(raw: &[u8]) -> Option<usize> {
        raw.windows(4).position(|w| w == b"\r\n\r\n").map(|p| p + 4)
    }

    fn content_length(raw: &[u8]) -> Option<usize> {
        let header_end = find_header_end(raw)?;
        let header_text = String::from_utf8_lossy(&raw[..header_end]);
        for line in header_text.split("\r\n") {
            if let Some((k, v)) = line.split_once(':')
                && k.eq_ignore_ascii_case("content-length")
            {
                return optional_usize(v.trim());
            }
        }
        None
    }

    #[expect(
        clippy::manual_ok_err,
        clippy::option_if_let_else,
        reason = "Result::ok is workspace-disallowed; optional parse maps Err to None deliberately"
    )]
    fn optional_u32(value: &str) -> Option<u32> {
        match value.trim().parse() {
            Ok(n) => Some(n),
            Err(_) => None,
        }
    }

    #[expect(
        clippy::manual_ok_err,
        clippy::option_if_let_else,
        reason = "Result::ok is workspace-disallowed; optional parse maps Err to None deliberately"
    )]
    fn optional_usize(value: &str) -> Option<usize> {
        match value.trim().parse() {
            Ok(n) => Some(n),
            Err(_) => None,
        }
    }

    fn write_status(stream: &mut TcpStream, status: u16, body: &[u8]) -> std::io::Result<()> {
        let reason = match status {
            204 => "No Content",
            401 => "Unauthorized",
            403 => "Forbidden",
            404 => "Not Found",
            409 => "Conflict",
            412 => "Precondition Failed",
            423 => "Locked",
            429 => "Too Many Requests",
            500 => "Internal Server Error",
            503 => "Service Unavailable",
            _ => "Error",
        };
        let retry = if status == 429 {
            "Retry-After: 7\r\n"
        } else {
            ""
        };
        let resp = format!(
            "HTTP/1.1 {status} {reason}\r\n{retry}Content-Length: {}\r\nConnection: close\r\n\r\n",
            body.len()
        );
        stream.write_all(resp.as_bytes())?;
        if !body.is_empty() {
            stream.write_all(body)?;
        }
        Ok(())
    }

    fn digest_of(bytes: &[u8]) -> ContentDigest {
        ContentDigest::parse(&format!("{:x}", Sha256::digest(bytes))).expect("digest")
    }

    fn path(raw: &str) -> SyncPath {
        SyncPath::parse(raw).expect("path")
    }

    fn adapter_with(
        server: &FaultServer,
        objects: MapObjectSource,
    ) -> (tempfile::TempDir, WebDavAdapter<MapObjectSource>) {
        let dir = tempdir().expect("temp");
        let adapter = connect_map_source(
            &server.base_url(),
            "user",
            "secret-pass",
            dir.path(),
            objects,
            Duration::from_secs(3),
        )
        .expect("connect");
        (dir, adapter)
    }

    #[test]
    fn endpoint_normalization_rejects_userinfo_query_and_forces_trailing_slash() {
        let ok = WebDavEndpoint::parse("http://127.0.0.1:9/dav").expect("parse");
        assert!(ok.as_str().ends_with('/'));
        assert_eq!(
            WebDavEndpoint::parse("http://user:pass@127.0.0.1/dav/")
                .expect_err("userinfo")
                .code(),
            "webdav_endpoint_userinfo_forbidden"
        );
        assert_eq!(
            WebDavEndpoint::parse("http://127.0.0.1/dav/?x=1")
                .expect_err("query")
                .code(),
            "webdav_endpoint_query_or_fragment"
        );
        assert_eq!(
            WebDavEndpoint::parse("ftp://127.0.0.1/dav/")
                .expect_err("scheme")
                .code(),
            "webdav_endpoint_scheme"
        );
    }

    #[test]
    fn endpoint_rejects_href_traversal_and_off_origin() {
        let endpoint = WebDavEndpoint::parse("http://127.0.0.1:9/dav/").expect("ep");
        assert_eq!(
            endpoint
                .relative_path_from_href("http://evil.example/x")
                .expect_err("off origin")
                .code(),
            "webdav_href_off_origin"
        );
        assert_eq!(
            endpoint
                .relative_path_from_href("/other/secret")
                .expect_err("outside")
                .code(),
            "webdav_href_outside_root"
        );
    }

    #[test]
    fn preflight_reports_etag_and_move_capabilities() {
        let server = FaultServer::start();
        let (_dir, adapter) = adapter_with(&server, MapObjectSource::default());
        let caps = adapter.preflight().expect("preflight");
        assert!(caps.supports_etag);
        assert!(caps.conditional_write);
        assert!(caps.conditional_delete);
        assert!(caps.supports_move);
        assert!(caps.supports_copy);

        server.set_faults(FaultConfig {
            options_no_move: true,
            ..FaultConfig::default()
        });
        let caps2 = adapter.preflight().expect("preflight2");
        assert!(!caps2.supports_move);
    }

    #[test]
    fn complete_snapshot_lists_files_with_sha256_digests() {
        let server = FaultServer::start();
        let body = b"hello-memo";
        server.put_object("memo/a.md", body);
        let (_dir, adapter) = adapter_with(&server, MapObjectSource::default());
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.completeness, SnapshotCompleteness::Complete);
        assert_eq!(snap.entries.len(), 1);
        assert_eq!(snap.entries[0].path.as_str(), "memo/a.md");
        assert_eq!(snap.entries[0].digest.as_str(), digest_of(body).as_str());
        assert!(!snap.entries[0].revision_token.is_empty());
    }

    #[test]
    fn subtree_propfind_failure_marks_snapshot_incomplete() {
        let server = FaultServer::start();
        server.put_object("ok/file.md", b"ok");
        server.put_object("broken/nested.md", b"nope");
        server.set_faults(FaultConfig {
            propfind_fail_prefix: Some("broken".to_owned()),
            ..FaultConfig::default()
        });
        let (_dir, adapter) = adapter_with(&server, MapObjectSource::default());
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.completeness, SnapshotCompleteness::Incomplete);
        assert!(
            snap.entries
                .iter()
                .all(|entry| !entry.path.as_str().starts_with("broken/")),
            "broken subtree entries must not appear as complete facts: {:?}",
            snap.entries
        );
    }

    #[test]
    fn conditional_put_if_none_match_succeeds_and_412_on_conflict() {
        let server = FaultServer::start();
        let body = b"new-bytes";
        let digest = digest_of(body);
        let mut objects = MapObjectSource::default();
        objects
            .objects
            .insert("memo/new.md".to_owned(), body.to_vec());
        let (_dir, adapter) = adapter_with(&server, objects);
        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/new.md"),
                digest,
                expected_remote_token: None,
            }],
        )
        .expect("batch");
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(matches!(
            receipt.path_results[0].1,
            PathPublishStatus::Applied { .. }
        ));
        let receipt2 = adapter.publish(&batch).expect("publish2");
        assert_eq!(
            receipt2.path_results[0].1,
            PathPublishStatus::PreconditionFailed
        );
    }

    #[test]
    fn conditional_put_stale_if_match_is_precondition_failed() {
        let server = FaultServer::start();
        server.put_object("memo/x.md", b"current");
        let body = b"updated";
        let mut objects = MapObjectSource::default();
        objects
            .objects
            .insert("memo/x.md".to_owned(), body.to_vec());
        let (_dir, adapter) = adapter_with(&server, objects);
        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/x.md"),
                digest: digest_of(body),
                expected_remote_token: Some("\"stale-etag\"".to_owned()),
            }],
        )
        .expect("batch");
        let receipt = adapter.publish(&batch).expect("publish");
        assert_eq!(
            receipt.path_results[0].1,
            PathPublishStatus::PreconditionFailed
        );
    }

    #[test]
    fn conditional_delete_with_matching_etag_applies() {
        let server = FaultServer::start();
        server.put_object("memo/z.md", b"delete-me");
        let etag = server.etag_of("memo/z.md");
        let (_dir, adapter) = adapter_with(&server, MapObjectSource::default());
        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsureAbsent {
                path: path("memo/z.md"),
                expected_remote_token: etag,
            }],
        )
        .expect("batch");
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(matches!(
            receipt.path_results[0].1,
            PathPublishStatus::Applied { .. }
        ));
        assert!(!server.has("memo/z.md"));
    }

    fn assert_webdav_pure_status_map() {
        // Full LomoError category + RetryDisposition on map_http_status.
        // PathPublishStatus only carries stable codes (asserted separately).
        let pure: &[(u16, &str, ErrorCategory, RetryDisposition)] = &[
            (
                401,
                "webdav_unauthorized",
                ErrorCategory::Authentication,
                RetryDisposition::AfterUserAction,
            ),
            (
                403,
                "webdav_forbidden",
                ErrorCategory::Permission,
                RetryDisposition::AfterUserAction,
            ),
            (
                404,
                "webdav_not_found",
                ErrorCategory::Validation,
                RetryDisposition::Never,
            ),
            (
                409,
                "webdav_conflict",
                ErrorCategory::Conflict,
                RetryDisposition::AfterUserAction,
            ),
            (
                412,
                "webdav_precondition_failed",
                ErrorCategory::Conflict,
                RetryDisposition::AfterUserAction,
            ),
            (
                423,
                "webdav_locked",
                ErrorCategory::Busy,
                RetryDisposition::Transient,
            ),
            (
                429,
                "webdav_rate_limited",
                ErrorCategory::Busy,
                RetryDisposition::Transient,
            ),
            (
                302,
                "webdav_redirect_not_followed",
                ErrorCategory::Network,
                RetryDisposition::AfterUserAction,
            ),
            (
                307,
                "webdav_redirect_not_followed",
                ErrorCategory::Network,
                RetryDisposition::AfterUserAction,
            ),
            (
                500,
                "webdav_server_error",
                ErrorCategory::Network,
                RetryDisposition::Transient,
            ),
            (
                503,
                "webdav_server_error",
                ErrorCategory::Network,
                RetryDisposition::Transient,
            ),
        ];
        for (status, code, category, retry) in pure {
            let err = map_http_status("TEST", *status);
            assert_eq!(err.code(), *code, "status {status}");
            assert_eq!(error_category(&err), *category, "status {status}");
            assert_eq!(err.retry_disposition(), *retry, "status {status}");
        }
    }

    fn assert_webdav_transport_auth_status(server: &FaultServer) {
        for (status, code, category, retry) in [
            (
                401_u16,
                "webdav_unauthorized",
                ErrorCategory::Authentication,
                RetryDisposition::AfterUserAction,
            ),
            (
                403,
                "webdav_forbidden",
                ErrorCategory::Permission,
                RetryDisposition::AfterUserAction,
            ),
        ] {
            server.set_faults(FaultConfig {
                force_status: Some(("PROPFIND".to_owned(), status)),
                ..FaultConfig::default()
            });
            let (_dir, adapter) = adapter_with(server, MapObjectSource::default());
            let err = adapter.preflight().expect_err("auth preflight");
            assert_eq!(err.code(), code, "status {status}");
            assert_eq!(error_category(&err), category, "status {status}");
            assert_eq!(err.retry_disposition(), retry, "status {status}");
        }
    }

    fn assert_webdav_path_publish_status_codes(server: &FaultServer) {
        let path_cases: &[(u16, &str)] = &[
            (404, "webdav_not_found"),
            (409, "webdav_conflict"),
            (412, "webdav_precondition_failed"),
            (423, "webdav_locked"),
            (429, "webdav_rate_limited"),
            (500, "webdav_server_error"),
            (503, "webdav_server_error"),
        ];
        for (status, code) in path_cases {
            server.set_faults(FaultConfig {
                force_status: Some(("PUT".to_owned(), *status)),
                ..FaultConfig::default()
            });
            let body = b"x";
            let mut objects = MapObjectSource::default();
            objects.objects.insert("t.md".to_owned(), body.to_vec());
            let (_dir, adapter) = adapter_with(server, objects);
            let batch = PreparedRemoteBatch::new(
                BatchAtomicity::PerPath,
                vec![ProviderNeutralIntent::EnsurePresent {
                    path: path("t.md"),
                    digest: digest_of(body),
                    expected_remote_token: None,
                }],
            )
            .expect("batch");
            let receipt = adapter.publish(&batch).expect("publish maps path status");
            match &receipt.path_results[0].1 {
                PathPublishStatus::Failed { code: c } => assert_eq!(c, code, "status {status}"),
                PathPublishStatus::PreconditionFailed if *status == 412 => {}
                PathPublishStatus::Applied { .. }
                | PathPublishStatus::PreconditionFailed
                | PathPublishStatus::Skipped => {
                    panic!("status {status} unexpected {:?}", receipt.path_results[0].1)
                }
            }
        }
    }

    #[test]
    fn http_status_matrix_maps_to_stable_codes_and_retry_policy() {
        assert_webdav_pure_status_map();
        let server = FaultServer::start();
        assert_webdav_transport_auth_status(&server);
        assert_webdav_path_publish_status_codes(&server);
    }

    #[test]
    fn multistatus_doctype_entities_fail_closed() {
        let server = FaultServer::start();
        server.set_faults(FaultConfig {
            inject_doctype: true,
            ..FaultConfig::default()
        });
        let (_dir, adapter) = adapter_with(&server, MapObjectSource::default());
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.completeness, SnapshotCompleteness::Incomplete);
    }

    #[test]
    fn multistatus_off_origin_or_traversal_href_marks_incomplete() {
        let server = FaultServer::start();
        server.set_faults(FaultConfig {
            inject_off_origin_href: true,
            ..FaultConfig::default()
        });
        let (_dir, adapter) = adapter_with(&server, MapObjectSource::default());
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.completeness, SnapshotCompleteness::Incomplete);

        server.set_faults(FaultConfig {
            inject_traversal_href: true,
            ..FaultConfig::default()
        });
        let snap2 = adapter.list_remote().expect("list2");
        assert_eq!(snap2.completeness, SnapshotCompleteness::Incomplete);
    }

    #[test]
    fn oversized_multistatus_marks_incomplete() {
        let server = FaultServer::start();
        server.set_faults(FaultConfig {
            oversized_multistatus_pad: 2 * 1_048_576 + 64,
            ..FaultConfig::default()
        });
        let (_dir, adapter) = adapter_with(&server, MapObjectSource::default());
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.completeness, SnapshotCompleteness::Incomplete);
    }

    #[test]
    fn off_origin_redirect_does_not_auto_follow_with_credentials() {
        let server = FaultServer::start();
        server.set_faults(FaultConfig {
            redirect_off_origin: true,
            ..FaultConfig::default()
        });
        let (_dir, adapter) = adapter_with(&server, MapObjectSource::default());
        // Policy::none: 302 is non-success; Authorization must never be replayed after hop.
        let err = adapter
            .preflight()
            .expect_err("302 with Policy::none must fail closed as non-success");
        assert_eq!(err.code(), "webdav_redirect_not_followed");
        assert_eq!(error_category(&err), ErrorCategory::Network);
        assert_eq!(err.retry_disposition(), RetryDisposition::AfterUserAction);
        let origin = Url::parse(&server.base_url()).expect("url");
        let evil = Url::parse("https://evil.example/steal").expect("evil");
        assert!(!is_same_origin(&origin, &evil));
    }

    #[test]
    fn percent_encoded_slash_in_href_segment_is_path_collision_fail_closed() {
        let server = FaultServer::start();
        server.set_faults(FaultConfig {
            inject_path_collision_href: true,
            ..FaultConfig::default()
        });
        let (_dir, adapter) = adapter_with(&server, MapObjectSource::default());
        let snap = adapter.list_remote().expect("list");
        assert_eq!(
            snap.completeness,
            SnapshotCompleteness::Incomplete,
            "href segment with %2F must fail closed (incomplete), not invent hierarchy"
        );
        assert!(
            snap.entries
                .iter()
                .all(|entry| !entry.path.as_str().contains("evil")),
            "path-collision href must not yield a decoded multi-segment path: {:?}",
            snap.entries
        );
    }

    #[test]
    fn nested_put_ensures_parent_collections_via_mkcol() {
        let server = FaultServer::start();
        server.set_faults(FaultConfig {
            require_parent_collections: true,
            ..FaultConfig::default()
        });
        let body = b"nested-body";
        let rel = "memo/nested/deep.md";
        let mut objects = MapObjectSource::default();
        objects.objects.insert(rel.to_owned(), body.to_vec());
        let (_dir, adapter) = adapter_with(&server, objects);
        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path(rel),
                digest: digest_of(body),
                expected_remote_token: None,
            }],
        )
        .expect("batch");
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(
            matches!(receipt.path_results[0].1, PathPublishStatus::Applied { .. }),
            "nested put must apply after MKCOL parents: {:?}",
            receipt.path_results[0].1
        );
        let mkcols = server.mkcol_paths();
        assert!(
            mkcols.iter().any(|p| p == "memo"),
            "expected MKCOL memo, got {mkcols:?}"
        );
        assert!(
            mkcols.iter().any(|p| p == "memo/nested"),
            "expected MKCOL memo/nested, got {mkcols:?}"
        );
        assert!(
            server.has(rel),
            "nested object must land after parent ensure"
        );
    }

    #[test]
    fn webdav_incomplete_snapshot_never_plans_ensure_absent() {
        let server = FaultServer::start();
        // Subtree failure → Incomplete; established baseline must not authorize deletes.
        server.put_object("memo/kept.md", b"still-here");
        server.set_faults(FaultConfig {
            propfind_fail_prefix: Some("memo".to_owned()),
            ..FaultConfig::default()
        });
        let (_dir, adapter) = adapter_with(&server, MapObjectSource::default());
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.completeness, SnapshotCompleteness::Incomplete);

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(
            &path("memo/ghost.md"),
            &digest_of(b"gone"),
            "tok-ghost".to_owned(),
        );
        let local = lomo_sync::LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: None,
        };
        let batch = plan_intents(
            SessionKind::Incremental,
            &local,
            &snap,
            &baseline,
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(
            batch.ensure_absent_count(),
            0,
            "Incomplete WebDAV listing must never authorize EnsureAbsent: {:?}",
            batch.intents
        );
    }

    fn fence() -> SyncIdentityFence {
        SyncIdentityFence::from_parts(
            &WorkspaceGenerationId::parse(&"ab".repeat(32)).expect("gen"),
            &RemoteDatasetId::parse("ds").expect("ds"),
            &RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("id"),
        )
    }

    #[test]
    fn unicode_path_put_and_snapshot_round_trip() {
        let server = FaultServer::start();
        let body = "你好世界".as_bytes();
        let digest = digest_of(body);
        let rel = "memo/笔记.md";
        let mut objects = MapObjectSource::default();
        objects.objects.insert(rel.to_owned(), body.to_vec());
        let (_dir, adapter) = adapter_with(&server, objects);
        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path(rel),
                digest: digest.clone(),
                expected_remote_token: None,
            }],
        )
        .expect("batch");
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(
            matches!(receipt.path_results[0].1, PathPublishStatus::Applied { .. }),
            "{:?}",
            receipt.path_results[0].1
        );
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.completeness, SnapshotCompleteness::Complete);
        let entry = snap
            .entries
            .iter()
            .find(|entry| entry.path.as_str() == rel)
            .expect("unicode path present");
        assert_eq!(entry.digest.as_str(), digest.as_str());
    }

    #[test]
    fn verify_reads_digest_and_absent_paths() {
        let server = FaultServer::start();
        let body = b"verify-me";
        server.put_object("memo/v.md", body);
        let (_dir, adapter) = adapter_with(&server, MapObjectSource::default());
        let verified = adapter
            .verify(&[path("memo/v.md"), path("memo/missing.md")])
            .expect("verify");
        assert_eq!(verified.results.len(), 2);
        match &verified.results[0] {
            lomo_sync::VerifyStatus::Verified { digest, .. } => {
                assert_eq!(digest.as_str(), digest_of(body).as_str());
            }
            lomo_sync::VerifyStatus::Failed { .. }
            | lomo_sync::VerifyStatus::AbsentVerified { .. } => {
                panic!("expected verified present, got {:?}", verified.results[0])
            }
        }
        assert!(matches!(
            verified.results[1],
            lomo_sync::VerifyStatus::AbsentVerified { .. }
        ));
    }

    #[test]
    fn credentials_debug_redacts_secrets() {
        let creds = WebDavCredentials::new("alice", "hunter2").expect("creds");
        let rendered = format!("{creds:?}");
        assert!(!rendered.contains("hunter2"));
        assert!(!rendered.contains("alice"));
        assert!(rendered.contains("redacted"));
    }

    #[test]
    fn object_source_digest_mismatch_fails_closed() {
        let mut objects = MapObjectSource::default();
        objects.objects.insert("a.md".to_owned(), b"bytes".to_vec());
        let err = objects
            .load_bytes(&path("a.md"), &digest_of(b"other"))
            .expect_err("mismatch");
        assert_eq!(err.code(), "webdav_object_source_digest_mismatch");
    }

    // --- Wave-13 residual: WebDAV list_remote_pages multi-page stream (mirror S3) ---

    #[test]
    fn list_remote_pages_streams_multi_page_without_raising_single_shot_snapshot() {
        // Wave-13 residual: WebDAV overrides list_remote_pages. Flat multi-file listing is
        // re-chunked into ≤512 action pages; single-shot list_remote stays ≤ MAX_WEBDAV_SNAPSHOT_ENTRIES.
        let server = FaultServer::start();
        // 7 keys under one collection; still well under single-shot 512 ceiling.
        for index in 0..7 {
            let name = format!("p{index}.md");
            server.put_object(&format!("memo/{name}"), name.as_bytes());
        }
        let (_dir, adapter) = adapter_with(&server, MapObjectSource::default());

        let stream = adapter.list_remote_pages().expect("list pages");
        assert_eq!(stream.overall_completeness, SnapshotCompleteness::Complete);
        let total: usize = stream.pages.iter().map(Vec::len).sum();
        assert_eq!(total, 7);
        for page in &stream.pages {
            assert!(
                page.len() <= MAX_ACTION_PAGE_ITEMS,
                "streaming page must stay ≤ action page ceiling"
            );
        }

        // Single-shot path still page-bounded and complete for the same small set.
        let snap = adapter.list_remote().expect("list remote");
        assert_eq!(snap.completeness, SnapshotCompleteness::Complete);
        assert_eq!(snap.entries.len(), 7);
        assert!(snap.entries.len() <= MAX_WEBDAV_SNAPSHOT_ENTRIES);
    }

    #[test]
    fn list_remote_pages_marks_incomplete_when_truncated_past_single_shot_ceiling() {
        // Past MAX_WEBDAV_SNAPSHOT_ENTRIES (512): single-shot caps and Incomplete; multi-page stream
        // still yields ≤512-entry pages and overall Incomplete so delete authority stays closed when
        // truncated at the single-shot path. Stream residual continues past 512 for path keys only.
        let server = FaultServer::start();
        let total = MAX_WEBDAV_SNAPSHOT_ENTRIES + 3;
        for index in 0..total {
            let name = format!("scale-{index:04}.md");
            server.put_object(&format!("memo/{name}"), name.as_bytes());
        }
        let (_dir, adapter) = adapter_with(&server, MapObjectSource::default());

        let snap = adapter.list_remote().expect("single-shot list");
        assert_eq!(snap.completeness, SnapshotCompleteness::Incomplete);
        assert!(snap.entries.len() <= MAX_WEBDAV_SNAPSHOT_ENTRIES);
        assert_eq!(snap.entries.len(), MAX_WEBDAV_SNAPSHOT_ENTRIES);

        let stream = adapter.list_remote_pages().expect("list pages");
        let streamed: usize = stream.pages.iter().map(Vec::len).sum();
        assert!(
            streamed >= MAX_WEBDAV_SNAPSHOT_ENTRIES,
            "paged list must not thrash into one ≤512 snapshot only: {streamed}"
        );
        assert_eq!(streamed, total);
        for page in &stream.pages {
            assert!(page.len() <= MAX_ACTION_PAGE_ITEMS);
        }
        // Full set known without force/fault → Complete for residual cycle path keys.
        assert_eq!(stream.overall_completeness, SnapshotCompleteness::Complete);

        // Wire residual cycle consumption: multi-page stream → plan without single-shot materialize.
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let session = SyncSession::new(fence(), SessionKind::Incremental, "webdav-stream-pages")
            .expect("session");
        let result = run_sync_cycle_streaming(
            &session,
            &local,
            &adapter,
            BaselineHead::empty(),
            None,
            false,
            None,
        )
        .expect("stream cycle via WebDAV adapter pages");
        assert_eq!(result.plan.remote_path_key_count, total);
        assert!(result.plan.peak_remote_page_entries <= MAX_ACTION_PAGE_ITEMS);
        assert!(result.plan.pages_within_limit());
        assert_eq!(result.plan.pull_present_count(), total);
        assert_eq!(result.plan.ensure_absent_count(), 0);
        assert_eq!(result.pages_applied, 0);
    }

    #[test]
    fn list_remote_pages_incomplete_never_authorizes_delete_in_streaming_cycle() {
        let server = FaultServer::start();
        server.put_object("memo/a.md", b"a");
        server.set_faults(FaultConfig {
            propfind_fail_prefix: Some(String::new()),
            ..FaultConfig::default()
        });
        // Force incomplete via adapter injection after construction: list root fails → Incomplete.
        // Prefer force_incomplete on adapter when propfind fail on empty prefix is too aggressive.
        let dir = tempdir().expect("temp");
        let adapter = connect_map_source(
            &server.base_url(),
            "user",
            "secret-pass",
            dir.path(),
            MapObjectSource::default(),
            Duration::from_secs(3),
        )
        .expect("connect")
        .with_force_incomplete(true);

        let stream = adapter.list_remote_pages().expect("pages");
        assert_eq!(
            stream.overall_completeness,
            SnapshotCompleteness::Incomplete
        );

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(
            &path("memo/missing.md"),
            &ContentDigest::parse(&"ab".repeat(32)).expect("d"),
            "tok-miss".to_owned(),
        );
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "webdav-inc").expect("session");
        let result =
            run_sync_cycle_streaming(&session, &local, &adapter, baseline, None, false, None)
                .expect("stream cycle incomplete");
        assert_eq!(result.plan.ensure_absent_count(), 0);
        assert_eq!(result.plan.completeness, SnapshotCompleteness::Incomplete);
    }
}
