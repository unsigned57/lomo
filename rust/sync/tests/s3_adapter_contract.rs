//! Behavior Contract (P5-06 S3 backend adapter — host hermetic slice)
//!
//! Capability: public [`RemoteSyncPort`] S3 adapter compiles/executes intents only; core owns
//! direction/conflict/baseline/tombstone/retry. Hermetic path-style S3 fault server (repo-owned,
//! no public network) exercises the host matrix. rclone crypt vectors from
//! `fixtures/remote/rclone-crypt-vectors.json` are verified bidirectionally for data decrypt and
//! filename encrypt where the fixture provides ciphertext.
//!
//! Scenarios:
//! - Given a path-style endpoint, when list/HEAD/GET/PUT/DELETE run, then objects round-trip with
//!   `ETag` revision tokens (not content SHA-256).
//! - Given `ListObjectsV2` pagination + truncated pages, when `list_remote` runs, then all keys
//!   under prefix are collected or incompleteness is marked.
//! - Given `EnsurePresent` If-None-Match / stale If-Match, when publish hits 412, then
//!   `PreconditionFailed`.
//! - Given `EnsureAbsent` with matching If-Match, when publish runs, then Applied.
//! - Given HTTP 401/403/404/409/412/429/5xx/3xx, when status maps, then stable codes + category +
//!   `RetryDisposition` on full `LomoError`.
//! - Given redirect `Policy::none` (302), when signed GET runs, then non-success
//!   `s3_redirect_not_followed` without credential follow.
//! - Given DOCTYPE/entity list body or list failure, when listed, then Incomplete (no delete
//!   authority).
//! - Given Incomplete S3 listing + established baseline, when `plan_intents` runs, then no
//!   `EnsureAbsent`.
//! - Given body above multipart threshold, when publish runs, then multipart create/part/complete
//!   applies (happy path).
//! - Given mid-upload part failure inject, when a second publish runs with the same digest, then the
//!   adapter reuses the multipart session and does not re-POST/PUT already confirmed parts;
//!   digest mismatch aborts the stale session before a new create.
//! - Given rclone fixture password/password2 + ciphertext, when `decrypt_payload` runs, then plain
//!   UTF-8 matches; filename encrypt of fixture plain segments matches ciphertext names when
//!   standard/base32/dir-encryption.
//! - Given AWS published `SigV4` example inputs, when signer runs, then golden signature matches.
//!
//! Observable outcomes: [`SnapshotCompleteness`], [`PathPublishStatus`], `LomoError` category/retry,
//! endpoint normalization, redacted credentials Debug.
//! Wave-12 residual: `list_remote_pages` override streams multi-page listings (each page ≤512)
//! without raising single-shot `RemoteSnapshot` past 512; Incomplete listing never authorizes
//! delete under `run_sync_cycle_streaming`; residual cycle can consume S3 paged listings host-only.
//!
//! Wave-14 residual: durable on-disk multipart sessions under `.lomo/sync/v1/multipart/` allow a
//! second adapter process to resume confirmed parts after process death without re-upload; corrupt
//! durable multipart records fail closed (`CorruptState`) without clean-slating other sync state.
//!
//! Wave-15 product-law freezes (absolute host residual dry):
//! - `PathStyle` + `Auto` share path-style URL construction (virtual-hosted is not Stage-5 host residual).
//! - rclone host-proven surface is fixture standard/base32/dir + data seal; other modes are typed
//!   code paths only (not residual OPEN for full CLI goldens).
//!
//! Excludes: production DI, AWS four-ABI production link, real R2/S3 smoke, full rclone CLI goldens
//! for non-fixture modes (product-bound, not residual OPEN), 10k-path scale matrix, Git adapter,
//! arm64 device. Host-hermetic matrix only.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::panic,
    clippy::format_push_string,
    clippy::too_many_lines,
    clippy::too_many_arguments,
    reason = "contract tests fail closed with panics; hermetic fault server favors readable dense helpers"
)]
mod tests {
    use std::collections::HashMap;
    use std::fs;
    use std::io::{Read, Write};
    use std::net::{SocketAddr, TcpListener, TcpStream};
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex};
    use std::thread;
    use std::time::Duration;

    use lomo_core::{ErrorCategory, RetryDisposition};
    use lomo_sync::{
        BaselineHead, BatchAtomicity, ContentDigest, FakeLocalPort, MAX_ACTION_PAGE_ITEMS,
        MAX_S3_SNAPSHOT_ENTRIES, MapS3ObjectSource, PathPublishStatus, PreparedRemoteBatch,
        ProviderNeutralIntent, RcloneCryptConfig, RcloneFilenameEncoding, RcloneFilenameEncryption,
        RcloneKeyMaterial, RemoteSyncPort, S3AddressingStyle, S3Credentials, S3Endpoint,
        S3ObjectSource, SessionKind, SnapshotCompleteness, SyncIdentityFence, SyncPath,
        SyncSession, TombstoneSet, aws_published_sigv4_example_matches, connect_map_s3_source,
        decrypt_filename_path, decrypt_payload, encrypt_filename_path, encrypt_payload,
        error_category, map_s3_http_status, plan_intents, run_sync_cycle_streaming,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
    use sha2::{Digest, Sha256};
    use tempfile::tempdir;

    #[derive(Clone, Debug)]
    struct StoredObject {
        body: Vec<u8>,
        etag: String,
    }

    #[derive(Clone, Debug, Default)]
    struct FaultConfig {
        force_status: Option<(String, u16)>,
        redirect_off_origin: bool,
        inject_doctype_list: bool,
        list_fail: bool,
        /// Max keys returned per `ListObjectsV2` page (for pagination).
        list_page_size: usize,
        /// After this many successful `UploadPart` responses, subsequent parts return 500.
        fail_after_n_successful_parts: Option<usize>,
    }

    #[derive(Clone, Debug)]
    struct MultipartUpload {
        key: String,
        parts: HashMap<u32, (String, Vec<u8>)>,
    }

    /// One observed multipart wire operation for resume contract assertions.
    #[derive(Clone, Debug, Eq, PartialEq)]
    enum MultipartWireEvent {
        Create { upload_id: String, key: String },
        UploadPart { upload_id: String, part_number: u32 },
        Complete { upload_id: String },
        Abort { upload_id: String },
    }

    struct FaultServer {
        addr: SocketAddr,
        shutdown: Arc<AtomicBool>,
        store: Arc<Mutex<HashMap<String, StoredObject>>>,
        #[expect(
            dead_code,
            reason = "held so Drop keeps multipart state alive with server"
        )]
        multiparts: Arc<Mutex<HashMap<String, MultipartUpload>>>,
        faults: Arc<Mutex<FaultConfig>>,
        multipart_wire_log: Arc<Mutex<Vec<MultipartWireEvent>>>,
        successful_parts: Arc<std::sync::atomic::AtomicUsize>,
        /// Monotonic counter so Create upload ids stay unique after abort.
        #[expect(dead_code, reason = "held so Drop keeps id sequence alive with server")]
        multipart_id_seq: Arc<std::sync::atomic::AtomicUsize>,
        _join: Option<thread::JoinHandle<()>>,
    }

    impl FaultServer {
        fn start() -> Self {
            let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
            let addr = listener.local_addr().expect("addr");
            let shutdown = Arc::new(AtomicBool::new(false));
            let store = Arc::new(Mutex::new(HashMap::new()));
            let multiparts = Arc::new(Mutex::new(HashMap::new()));
            let faults = Arc::new(Mutex::new(FaultConfig {
                list_page_size: 1000,
                ..FaultConfig::default()
            }));
            let multipart_wire_log = Arc::new(Mutex::new(Vec::new()));
            let successful_parts = Arc::new(std::sync::atomic::AtomicUsize::new(0));
            let multipart_id_seq = Arc::new(std::sync::atomic::AtomicUsize::new(0));
            let shutdown_t = Arc::clone(&shutdown);
            let store_t = Arc::clone(&store);
            let multiparts_t = Arc::clone(&multiparts);
            let faults_t = Arc::clone(&faults);
            let wire_t = Arc::clone(&multipart_wire_log);
            let parts_t = Arc::clone(&successful_parts);
            let id_seq_t = Arc::clone(&multipart_id_seq);
            listener.set_nonblocking(true).expect("nonblocking");
            let join = thread::spawn(move || {
                while !shutdown_t.load(Ordering::SeqCst) {
                    match listener.accept() {
                        Ok((stream, _)) => {
                            let store = Arc::clone(&store_t);
                            let multiparts = Arc::clone(&multiparts_t);
                            let faults = Arc::clone(&faults_t);
                            let wire = Arc::clone(&wire_t);
                            let parts = Arc::clone(&parts_t);
                            let id_seq = Arc::clone(&id_seq_t);
                            let _worker = thread::spawn(move || {
                                let _handled: std::io::Result<()> = handle_client(
                                    stream,
                                    &store,
                                    &multiparts,
                                    &faults,
                                    &wire,
                                    &parts,
                                    &id_seq,
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
            Self {
                addr,
                shutdown,
                store,
                multiparts,
                faults,
                multipart_wire_log,
                successful_parts,
                multipart_id_seq,
                _join: Some(join),
            }
        }

        fn base_url(&self) -> String {
            format!("http://{}/", self.addr)
        }

        fn put_object(&self, key: &str, body: &[u8]) {
            let etag = simple_etag(body);
            self.store.lock().expect("store").insert(
                key.to_owned(),
                StoredObject {
                    body: body.to_vec(),
                    etag,
                },
            );
        }

        fn set_faults(&self, config: FaultConfig) {
            *self.faults.lock().expect("faults") = config;
        }

        fn clear_multipart_wire_log(&self) {
            self.multipart_wire_log.lock().expect("wire").clear();
            self.successful_parts.store(0, Ordering::SeqCst);
        }

        fn multipart_wire_log(&self) -> Vec<MultipartWireEvent> {
            self.multipart_wire_log.lock().expect("wire").clone()
        }

        fn has(&self, key: &str) -> bool {
            self.store.lock().expect("store").contains_key(key)
        }

        fn etag_of(&self, key: &str) -> String {
            self.store
                .lock()
                .expect("store")
                .get(key)
                .map_or_else(String::new, |obj| obj.etag.clone())
        }
    }

    impl Drop for FaultServer {
        fn drop(&mut self) {
            self.shutdown.store(true, Ordering::SeqCst);
        }
    }

    fn simple_etag(body: &[u8]) -> String {
        let digest = format!("{:x}", Sha256::digest(body));
        let short: String = digest.chars().take(16).collect();
        format!("\"{short}\"")
    }

    fn handle_client(
        mut stream: TcpStream,
        store: &Mutex<HashMap<String, StoredObject>>,
        multiparts: &Mutex<HashMap<String, MultipartUpload>>,
        faults: &Mutex<FaultConfig>,
        wire_log: &Mutex<Vec<MultipartWireEvent>>,
        successful_parts: &std::sync::atomic::AtomicUsize,
        multipart_id_seq: &std::sync::atomic::AtomicUsize,
    ) -> std::io::Result<()> {
        let raw = read_request(&mut stream)?;
        let (method, path_q, headers, body) = parse_http_request(&raw);
        let fault = faults
            .lock()
            .map_or_else(|_| FaultConfig::default(), |g| g.clone());

        if fault.redirect_off_origin {
            return write_status(
                &mut stream,
                302,
                b"redirect",
                "Location: http://evil.example/steal\r\n",
            );
        }

        if let Some((method_match, status)) = &fault.force_status
            && method.eq_ignore_ascii_case(method_match)
        {
            return write_status(&mut stream, *status, b"fault", "");
        }

        let (path, query) = split_path_query(&path_q);
        // path-style: /{bucket}/{key...}
        let rel = path.trim_start_matches('/');
        let Some((_bucket, key_part)) = rel.split_once('/') else {
            // list on bucket root: /bucket or /bucket/
            if method == "GET" && query.contains("list-type=2") {
                return write_list(&mut stream, store, "", &query, &fault);
            }
            return write_status(&mut stream, 404, b"missing", "");
        };
        let key = percent_decode_simple(key_part.trim_matches('/'));

        if method == "GET" && query.contains("list-type=2") {
            // list URL is /bucket/?list-type=2 — key_part may be empty
            return write_list(&mut stream, store, "", &query, &fault);
        }

        // Multipart query routes
        if method == "POST" && query.contains("uploads") {
            return write_create_multipart(
                &mut stream,
                multiparts,
                wire_log,
                multipart_id_seq,
                &key,
            );
        }
        if method == "PUT" && query.contains("partNumber=") && query.contains("uploadId=") {
            return write_upload_part(
                &mut stream,
                multiparts,
                wire_log,
                successful_parts,
                &fault,
                &key,
                &query,
                &body,
            );
        }
        if method == "POST" && query.contains("uploadId=") && !query.contains("partNumber=") {
            return write_complete_multipart(
                &mut stream,
                store,
                multiparts,
                wire_log,
                &key,
                &query,
            );
        }
        if method == "DELETE" && query.contains("uploadId=") {
            return write_abort_multipart(&mut stream, multiparts, wire_log, &query);
        }

        match method.as_str() {
            "GET" => write_get(&mut stream, store, &key),
            "HEAD" => write_head(&mut stream, store, &key),
            "PUT" => write_put(&mut stream, store, &key, &headers, &body),
            "DELETE" => write_delete(&mut stream, store, &key, &headers),
            _ => write_status(&mut stream, 405, b"method", ""),
        }
    }

    fn write_list(
        stream: &mut TcpStream,
        store: &Mutex<HashMap<String, StoredObject>>,
        _prefix_filter: &str,
        query: &str,
        fault: &FaultConfig,
    ) -> std::io::Result<()> {
        if fault.list_fail {
            return write_status(stream, 500, b"list-fail", "");
        }
        if fault.inject_doctype_list {
            let body = b"<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><ListBucketResult></ListBucketResult>";
            return write_status(stream, 200, body, "Content-Type: application/xml\r\n");
        }
        let mut keys: Vec<(String, StoredObject)> = match store.lock() {
            Ok(guard) => guard.iter().map(|(k, v)| (k.clone(), v.clone())).collect(),
            Err(_) => return write_status(stream, 500, b"store-lock", ""),
        };
        keys.sort_by(|a, b| a.0.cmp(&b.0));

        // continuation-token is a simple index string (malformed → start at 0)
        let start = query_value(query, "continuation-token")
            .and_then(|token| optional_usize(&token))
            .unwrap_or(0);
        let page_size = fault.list_page_size.max(1);
        let end = (start + page_size).min(keys.len());
        let page = &keys[start..end];
        let truncated = end < keys.len();
        let mut xml = String::from(
            r#"<?xml version="1.0" encoding="UTF-8"?><ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">"#,
        );
        xml.push_str("<Name>bucket</Name>");
        xml.push_str(&format!(
            "<IsTruncated>{}</IsTruncated>",
            if truncated { "true" } else { "false" }
        ));
        if truncated {
            xml.push_str(&format!(
                "<NextContinuationToken>{end}</NextContinuationToken>"
            ));
        }
        for (key, obj) in page {
            xml.push_str("<Contents><Key>");
            xml.push_str(key);
            xml.push_str("</Key><ETag>");
            xml.push_str(&obj.etag);
            xml.push_str("</ETag><Size>");
            xml.push_str(&obj.body.len().to_string());
            xml.push_str("</Size></Contents>");
        }
        xml.push_str("</ListBucketResult>");
        write_status(
            stream,
            200,
            xml.as_bytes(),
            "Content-Type: application/xml\r\n",
        )
    }

    fn write_get(
        stream: &mut TcpStream,
        store: &Mutex<HashMap<String, StoredObject>>,
        key: &str,
    ) -> std::io::Result<()> {
        let obj = match store.lock() {
            Ok(guard) => guard.get(key).cloned(),
            Err(_) => return write_status(stream, 500, b"store-lock", ""),
        };
        let Some(obj) = obj else {
            return write_status(stream, 404, b"missing", "");
        };
        let extra = format!("ETag: {}\r\n", obj.etag);
        write_status(stream, 200, &obj.body, &extra)
    }

    fn write_head(
        stream: &mut TcpStream,
        store: &Mutex<HashMap<String, StoredObject>>,
        key: &str,
    ) -> std::io::Result<()> {
        let obj = match store.lock() {
            Ok(guard) => guard.get(key).cloned(),
            Err(_) => return write_status(stream, 500, b"store-lock", ""),
        };
        let Some(obj) = obj else {
            return write_status(stream, 404, b"", "");
        };
        let extra = format!(
            "ETag: {}\r\nContent-Length: {}\r\n",
            obj.etag,
            obj.body.len()
        );
        write_status(stream, 200, b"", &extra)
    }

    fn write_put(
        stream: &mut TcpStream,
        store: &Mutex<HashMap<String, StoredObject>>,
        key: &str,
        headers: &HashMap<String, String>,
        body: &[u8],
    ) -> std::io::Result<()> {
        let if_match = headers.get("if-match").cloned();
        let if_none = headers
            .get("if-none-match")
            .is_some_and(|v| v.trim() == "*");
        let existing = match store.lock() {
            Ok(guard) => guard.get(key).cloned(),
            Err(_) => return write_status(stream, 500, b"store-lock", ""),
        };
        if if_none {
            if existing.is_some() {
                return write_status(stream, 412, b"precondition", "");
            }
        } else if let Some(expected) = if_match {
            match existing {
                Some(obj) if strip_q(&obj.etag) != strip_q(&expected) => {
                    return write_status(stream, 412, b"precondition", "");
                }
                None => return write_status(stream, 412, b"precondition", ""),
                Some(_) => {}
            }
        }
        let etag = simple_etag(body);
        if let Ok(mut guard) = store.lock() {
            guard.insert(
                key.to_owned(),
                StoredObject {
                    body: body.to_vec(),
                    etag: etag.clone(),
                },
            );
        }
        let extra = format!("ETag: {etag}\r\n");
        write_status(stream, 200, b"stored", &extra)
    }

    fn write_delete(
        stream: &mut TcpStream,
        store: &Mutex<HashMap<String, StoredObject>>,
        key: &str,
        headers: &HashMap<String, String>,
    ) -> std::io::Result<()> {
        let if_match = headers.get("if-match").cloned();
        let existing = match store.lock() {
            Ok(guard) => guard.get(key).cloned(),
            Err(_) => return write_status(stream, 500, b"store-lock", ""),
        };
        let Some(obj) = existing else {
            return write_status(stream, 404, b"missing", "");
        };
        if let Some(expected) = if_match
            && strip_q(&obj.etag) != strip_q(&expected)
        {
            return write_status(stream, 412, b"precondition", "");
        }
        if let Ok(mut guard) = store.lock() {
            guard.remove(key);
        }
        write_status(stream, 204, b"", "")
    }

    fn write_create_multipart(
        stream: &mut TcpStream,
        multiparts: &Mutex<HashMap<String, MultipartUpload>>,
        wire_log: &Mutex<Vec<MultipartWireEvent>>,
        multipart_id_seq: &std::sync::atomic::AtomicUsize,
        key: &str,
    ) -> std::io::Result<()> {
        let seq = multipart_id_seq.fetch_add(1, Ordering::SeqCst);
        let upload_id = format!(
            "up-{}-{}",
            simple_etag(key.as_bytes()).trim_matches('"'),
            seq
        );
        if let Ok(mut guard) = multiparts.lock() {
            guard.insert(
                upload_id.clone(),
                MultipartUpload {
                    key: key.to_owned(),
                    parts: HashMap::new(),
                },
            );
        }
        if let Ok(mut log) = wire_log.lock() {
            log.push(MultipartWireEvent::Create {
                upload_id: upload_id.clone(),
                key: key.to_owned(),
            });
        }
        let body = format!(
            r#"<?xml version="1.0"?><InitiateMultipartUploadResult><Bucket>bucket</Bucket><Key>{key}</Key><UploadId>{upload_id}</UploadId></InitiateMultipartUploadResult>"#
        );
        write_status(
            stream,
            200,
            body.as_bytes(),
            "Content-Type: application/xml\r\n",
        )
    }

    fn write_upload_part(
        stream: &mut TcpStream,
        multiparts: &Mutex<HashMap<String, MultipartUpload>>,
        wire_log: &Mutex<Vec<MultipartWireEvent>>,
        successful_parts: &std::sync::atomic::AtomicUsize,
        fault: &FaultConfig,
        key: &str,
        query: &str,
        body: &[u8],
    ) -> std::io::Result<()> {
        let Some(upload_id) = query_value(query, "uploadId") else {
            return write_status(stream, 400, b"missing-upload-id", "");
        };
        let Some(part_raw) = query_value(query, "partNumber") else {
            return write_status(stream, 400, b"missing-part-number", "");
        };
        let Some(part_number) = optional_u32(&part_raw) else {
            return write_status(stream, 400, b"bad-part-number", "");
        };
        if let Some(limit) = fault.fail_after_n_successful_parts {
            let ok_so_far = successful_parts.load(Ordering::SeqCst);
            if ok_so_far >= limit {
                return write_status(stream, 500, b"part-fault", "");
            }
        }
        let etag = simple_etag(body);
        if let Ok(mut guard) = multiparts.lock() {
            let Some(upload) = guard.get_mut(&upload_id) else {
                return write_status(stream, 404, b"no-upload", "");
            };
            if upload.key != key {
                return write_status(stream, 400, b"key-mismatch", "");
            }
            upload
                .parts
                .insert(part_number, (etag.clone(), body.to_vec()));
        }
        let _count = successful_parts.fetch_add(1, Ordering::SeqCst);
        if let Ok(mut log) = wire_log.lock() {
            log.push(MultipartWireEvent::UploadPart {
                upload_id,
                part_number,
            });
        }
        let extra = format!("ETag: {etag}\r\n");
        write_status(stream, 200, b"part", &extra)
    }

    fn write_complete_multipart(
        stream: &mut TcpStream,
        store: &Mutex<HashMap<String, StoredObject>>,
        multiparts: &Mutex<HashMap<String, MultipartUpload>>,
        wire_log: &Mutex<Vec<MultipartWireEvent>>,
        key: &str,
        query: &str,
    ) -> std::io::Result<()> {
        let Some(upload_id) = query_value(query, "uploadId") else {
            return write_status(stream, 400, b"missing-upload-id", "");
        };
        let upload = match multiparts.lock() {
            Ok(mut guard) => guard.remove(&upload_id),
            Err(_) => return write_status(stream, 500, b"multipart-lock", ""),
        };
        let Some(upload) = upload else {
            return write_status(stream, 404, b"no-upload", "");
        };
        let mut part_nums: Vec<u32> = upload.parts.keys().copied().collect();
        part_nums.sort_unstable();
        let mut body = Vec::new();
        for num in part_nums {
            if let Some((_, chunk)) = upload.parts.get(&num) {
                body.extend_from_slice(chunk);
            }
        }
        let etag = simple_etag(&body);
        if let Ok(mut guard) = store.lock() {
            guard.insert(
                key.to_owned(),
                StoredObject {
                    body,
                    etag: etag.clone(),
                },
            );
        }
        if let Ok(mut log) = wire_log.lock() {
            log.push(MultipartWireEvent::Complete { upload_id });
        }
        let extra = format!("ETag: {etag}\r\n");
        write_status(stream, 200, b"complete", &extra)
    }

    fn write_abort_multipart(
        stream: &mut TcpStream,
        multiparts: &Mutex<HashMap<String, MultipartUpload>>,
        wire_log: &Mutex<Vec<MultipartWireEvent>>,
        query: &str,
    ) -> std::io::Result<()> {
        let Some(upload_id) = query_value(query, "uploadId") else {
            return write_status(stream, 400, b"missing-upload-id", "");
        };
        if let Ok(mut guard) = multiparts.lock() {
            guard.remove(&upload_id);
        }
        if let Ok(mut log) = wire_log.lock() {
            log.push(MultipartWireEvent::Abort { upload_id });
        }
        write_status(stream, 204, b"", "")
    }

    fn strip_q(token: &str) -> &str {
        token.trim_matches('"')
    }

    fn query_value(query: &str, key: &str) -> Option<String> {
        for pair in query.split('&') {
            let mut it = pair.splitn(2, '=');
            let k = it.next()?;
            if k == key {
                return Some(percent_decode_simple(it.next().unwrap_or("")));
            }
        }
        None
    }

    fn split_path_query(path_q: &str) -> (String, String) {
        match path_q.split_once('?') {
            Some((p, q)) => (p.to_owned(), q.to_owned()),
            None => (path_q.to_owned(), String::new()),
        }
    }

    fn read_request(stream: &mut TcpStream) -> std::io::Result<Vec<u8>> {
        let _timeout: Result<(), std::io::Error> =
            stream.set_read_timeout(Some(Duration::from_secs(2)));
        let mut buf = Vec::new();
        let mut chunk = [0_u8; 4096];
        loop {
            let n = stream.read(&mut chunk)?;
            if n == 0 {
                break;
            }
            buf.extend_from_slice(&chunk[..n]);
            if let Some(header_end) = find_header_end(&buf) {
                let content_len = content_length(&buf).unwrap_or(0);
                let body_start = header_end + 4;
                while buf.len() < body_start + content_len {
                    let n = stream.read(&mut chunk)?;
                    if n == 0 {
                        break;
                    }
                    buf.extend_from_slice(&chunk[..n]);
                }
                break;
            }
            if buf.len() > 2 * 1024 * 1024 {
                break;
            }
        }
        Ok(buf)
    }

    fn find_header_end(raw: &[u8]) -> Option<usize> {
        raw.windows(4).position(|w| w == b"\r\n\r\n")
    }

    fn content_length(raw: &[u8]) -> Option<usize> {
        let text = String::from_utf8_lossy(raw);
        for line in text.lines() {
            if let Some(v) = line.to_ascii_lowercase().strip_prefix("content-length:") {
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

    fn parse_http_request(raw: &[u8]) -> (String, String, HashMap<String, String>, Vec<u8>) {
        let header_end = find_header_end(raw).unwrap_or(raw.len());
        let header_text = String::from_utf8_lossy(&raw[..header_end]);
        let mut lines = header_text.lines();
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
        let body = if header_end + 4 <= raw.len() {
            raw[header_end + 4..].to_vec()
        } else {
            Vec::new()
        };
        (method, path, headers, body)
    }

    fn percent_decode_simple(input: &str) -> String {
        let bytes = input.as_bytes();
        let mut out = Vec::new();
        let mut i = 0;
        while i < bytes.len() {
            if bytes[i] == b'%' && i + 2 < bytes.len() {
                let h = hex_val(bytes[i + 1]);
                let l = hex_val(bytes[i + 2]);
                if let (Some(h), Some(l)) = (h, l) {
                    out.push((h << 4) | l);
                    i += 3;
                    continue;
                }
            }
            out.push(bytes[i]);
            i += 1;
        }
        String::from_utf8_lossy(&out).into_owned()
    }

    fn hex_val(b: u8) -> Option<u8> {
        match b {
            b'0'..=b'9' => Some(b - b'0'),
            b'a'..=b'f' => Some(b - b'a' + 10),
            b'A'..=b'F' => Some(b - b'A' + 10),
            _ => None,
        }
    }

    fn write_status(
        stream: &mut TcpStream,
        status: u16,
        body: &[u8],
        extra_headers: &str,
    ) -> std::io::Result<()> {
        let reason = match status {
            200 => "OK",
            204 => "No Content",
            302 => "Found",
            401 => "Unauthorized",
            403 => "Forbidden",
            404 => "Not Found",
            405 => "Method Not Allowed",
            409 => "Conflict",
            412 => "Precondition Failed",
            429 => "Too Many Requests",
            500 => "Internal Server Error",
            503 => "Service Unavailable",
            _ => "Error",
        };
        let header = format!(
            "HTTP/1.1 {status} {reason}\r\nContent-Length: {}\r\nConnection: close\r\n{extra_headers}\r\n",
            body.len()
        );
        stream.write_all(header.as_bytes())?;
        stream.write_all(body)?;
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
        objects: MapS3ObjectSource,
    ) -> (tempfile::TempDir, lomo_sync::S3Adapter<MapS3ObjectSource>) {
        let dir = tempdir().expect("temp");
        let adapter = connect_map_s3_source(lomo_sync::MapS3ConnectParams {
            endpoint_url: &server.base_url(),
            bucket: "bucket",
            prefix: "lomo/",
            region: "us-east-1",
            access_key_id: "test-access",
            secret_access_key: "test-secret",
            temp_dir: dir.path(),
            objects,
            timeout: Duration::from_secs(5),
        })
        .expect("adapter");
        (dir, adapter)
    }

    fn fence() -> SyncIdentityFence {
        SyncIdentityFence::from_parts(
            &WorkspaceGenerationId::parse(&"ab".repeat(32)).expect("gen"),
            &RemoteDatasetId::parse("ds").expect("ds"),
            &RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("id"),
        )
    }

    fn repo_root() -> PathBuf {
        let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        // rust/sync -> repo root
        dir.pop();
        dir.pop();
        dir
    }

    #[test]
    fn endpoint_normalization_rejects_userinfo_query_and_bad_bucket() {
        let err = S3Endpoint::parse(
            "http://user:pass@localhost/s3/",
            "bucket",
            "lomo",
            "us-east-1",
            S3AddressingStyle::PathStyle,
        )
        .expect_err("userinfo");
        assert_eq!(err.code(), "s3_endpoint_userinfo_forbidden");

        let err = S3Endpoint::parse(
            "http://localhost/s3/?x=1",
            "bucket",
            "lomo",
            "us-east-1",
            S3AddressingStyle::PathStyle,
        )
        .expect_err("query");
        assert_eq!(err.code(), "s3_endpoint_query_or_fragment");

        let err = S3Endpoint::parse(
            "http://localhost/s3/",
            "bad/bucket",
            "lomo",
            "us-east-1",
            S3AddressingStyle::PathStyle,
        )
        .expect_err("bucket");
        assert_eq!(err.code(), "s3_bucket_invalid");

        let ok = S3Endpoint::parse(
            "http://localhost:9000/s3",
            "bucket",
            "lomo/memo",
            "auto",
            S3AddressingStyle::PathStyle,
        )
        .expect("ok");
        assert!(ok.base_url().as_str().ends_with('/'));
        assert_eq!(ok.prefix(), "lomo/memo/");
        assert_eq!(ok.object_key("a.md").expect("key"), "lomo/memo/a.md");
    }

    /// Stage-5 product law: `Auto` resolves to the same path-style object/list URL shape as
    /// `PathStyle` for custom endpoints (virtual-hosted is not a host residual).
    #[test]
    fn auto_addressing_style_emits_path_style_object_and_list_urls() {
        let path_style = S3Endpoint::parse(
            "http://localhost:9000/s3",
            "bucket",
            "lomo",
            "us-east-1",
            S3AddressingStyle::PathStyle,
        )
        .expect("path-style");
        let auto = S3Endpoint::parse(
            "http://localhost:9000/s3",
            "bucket",
            "lomo",
            "us-east-1",
            S3AddressingStyle::Auto,
        )
        .expect("auto");
        assert_eq!(path_style.style(), S3AddressingStyle::PathStyle);
        assert_eq!(auto.style(), S3AddressingStyle::Auto);

        let path_object = path_style.object_url("memo/a.md").expect("path object");
        let auto_object = auto.object_url("memo/a.md").expect("auto object");
        assert_eq!(path_object.as_str(), auto_object.as_str());
        assert!(
            path_object.as_str().contains("/bucket/memo/a.md"),
            "path-style object URL must keep bucket in path: {path_object}"
        );
        let host = path_object.host_str().expect("object URL host");
        assert!(
            !host.starts_with("bucket."),
            "Stage-5 must not virtual-host bucket into host: {path_object}"
        );

        let path_list = path_style.list_url(None, 100).expect("path list");
        let auto_list = auto.list_url(None, 100).expect("auto list");
        assert_eq!(path_list.path(), auto_list.path());
        assert!(
            path_list.path().ends_with("/bucket/") || path_list.path().contains("/bucket"),
            "list path must be path-style under bucket: {}",
            path_list.path()
        );
    }

    #[test]
    fn credentials_debug_redacts_secrets() {
        let creds = S3Credentials::new("AKIAEXAMPLE", "secret-value").expect("creds");
        let rendered = format!("{creds:?}");
        assert!(!rendered.contains("secret-value"));
        assert!(!rendered.contains("AKIAEXAMPLE"));
        assert!(rendered.contains("redacted"));
    }

    #[test]
    fn aws_published_sigv4_golden_matches() {
        assert!(
            aws_published_sigv4_example_matches(),
            "SigV4 signer must match the AWS published S3 example vector"
        );
    }

    #[test]
    fn complete_snapshot_lists_files_with_sha256_digests_etag_is_revision_only() {
        let server = FaultServer::start();
        let body = b"hello-s3";
        server.put_object("lomo/memo/a.md", body);
        let content_sha = format!("{:x}", Sha256::digest(body));
        let etag = server.etag_of("lomo/memo/a.md");
        assert_ne!(
            etag.trim_matches('"'),
            content_sha,
            "ETag must not equal content SHA-256 (revision token only)"
        );

        let (_dir, adapter) = adapter_with(&server, MapS3ObjectSource::default());
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.completeness, SnapshotCompleteness::Complete);
        let entry = snap
            .entries
            .iter()
            .find(|e| e.path.as_str() == "memo/a.md")
            .expect("entry under prefix");
        assert_eq!(entry.digest.as_str(), content_sha);
        assert_eq!(entry.revision_token, etag);
    }

    #[test]
    fn list_pagination_collects_all_keys() {
        let server = FaultServer::start();
        server.set_faults(FaultConfig {
            list_page_size: 2,
            ..FaultConfig::default()
        });
        for name in ["a.md", "b.md", "c.md", "d.md", "e.md"] {
            server.put_object(&format!("lomo/memo/{name}"), name.as_bytes());
        }
        let (_dir, adapter) = adapter_with(&server, MapS3ObjectSource::default());
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.completeness, SnapshotCompleteness::Complete);
        assert_eq!(snap.entries.len(), 5);
    }

    #[test]
    fn list_remote_pages_streams_multi_page_without_raising_single_shot_snapshot() {
        // Wave-12 residual: S3 overrides list_remote_pages. Transport pages of 2 keys are
        // re-chunked into ≤512 action pages; single-shot list_remote stays ≤ MAX_S3_SNAPSHOT_ENTRIES.
        let server = FaultServer::start();
        server.set_faults(FaultConfig {
            list_page_size: 2,
            ..FaultConfig::default()
        });
        // 7 keys > one transport page, still well under single-shot 512 ceiling.
        for index in 0..7 {
            let name = format!("p{index}.md");
            server.put_object(&format!("lomo/memo/{name}"), name.as_bytes());
        }
        let (_dir, adapter) = adapter_with(&server, MapS3ObjectSource::default());

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
        assert!(snap.entries.len() <= MAX_S3_SNAPSHOT_ENTRIES);
    }

    #[test]
    fn list_remote_pages_marks_incomplete_when_truncated_past_single_shot_ceiling() {
        // Past MAX_S3_SNAPSHOT_ENTRIES (512): single-shot caps and Incomplete; multi-page stream
        // still yields ≤512-entry pages and overall Incomplete so delete authority stays closed.
        let server = FaultServer::start();
        server.set_faults(FaultConfig {
            list_page_size: 100,
            ..FaultConfig::default()
        });
        let total = MAX_S3_SNAPSHOT_ENTRIES + 3;
        for index in 0..total {
            let name = format!("scale-{index:04}.md");
            server.put_object(&format!("lomo/memo/{name}"), name.as_bytes());
        }
        let (_dir, adapter) = adapter_with(&server, MapS3ObjectSource::default());

        let snap = adapter.list_remote().expect("single-shot list");
        assert_eq!(snap.completeness, SnapshotCompleteness::Incomplete);
        assert!(snap.entries.len() <= MAX_S3_SNAPSHOT_ENTRIES);
        assert_eq!(snap.entries.len(), MAX_S3_SNAPSHOT_ENTRIES);

        let stream = adapter.list_remote_pages().expect("list pages");
        // Stream can continue past the single-shot ceiling (path keys only for residual cycle).
        let streamed: usize = stream.pages.iter().map(Vec::len).sum();
        assert!(
            streamed >= MAX_S3_SNAPSHOT_ENTRIES,
            "paged list must not thrash into one ≤512 snapshot only: {streamed}"
        );
        assert_eq!(streamed, total);
        for page in &stream.pages {
            assert!(page.len() <= MAX_ACTION_PAGE_ITEMS);
        }
        // Completeness is Complete only when the full set is known without force/fault.
        assert_eq!(stream.overall_completeness, SnapshotCompleteness::Complete);

        // Wire residual cycle consumption: multi-page stream → plan without single-shot materialize.
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let session = SyncSession::new(fence(), SessionKind::Incremental, "s3-stream-pages")
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
        .expect("stream cycle via S3 adapter pages");
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
        server.set_faults(FaultConfig {
            list_page_size: 2,
            list_fail: true,
            ..FaultConfig::default()
        });
        server.put_object("lomo/memo/a.md", b"a");
        let (_dir, adapter) = adapter_with(&server, MapS3ObjectSource::default());
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
            SyncSession::new(fence(), SessionKind::Incremental, "s3-inc").expect("session");
        let result =
            run_sync_cycle_streaming(&session, &local, &adapter, baseline, None, false, None)
                .expect("stream cycle incomplete");
        assert_eq!(result.plan.ensure_absent_count(), 0);
        assert_eq!(result.plan.completeness, SnapshotCompleteness::Incomplete);
    }

    #[test]
    fn list_failure_marks_snapshot_incomplete() {
        let server = FaultServer::start();
        server.put_object("lomo/memo/a.md", b"x");
        server.set_faults(FaultConfig {
            list_fail: true,
            ..FaultConfig::default()
        });
        let (_dir, adapter) = adapter_with(&server, MapS3ObjectSource::default());
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.completeness, SnapshotCompleteness::Incomplete);
    }

    #[test]
    fn doctype_list_body_marks_incomplete() {
        let server = FaultServer::start();
        server.set_faults(FaultConfig {
            inject_doctype_list: true,
            ..FaultConfig::default()
        });
        let (_dir, adapter) = adapter_with(&server, MapS3ObjectSource::default());
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.completeness, SnapshotCompleteness::Incomplete);
    }

    #[test]
    fn conditional_put_if_none_match_and_412_on_conflict() {
        let server = FaultServer::start();
        let body = b"new-object";
        let mut objects = MapS3ObjectSource::default();
        objects
            .objects
            .insert("memo/new.md".to_owned(), body.to_vec());
        let (_dir, adapter) = adapter_with(&server, objects);
        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/new.md"),
                digest: digest_of(body),
                expected_remote_token: None,
            }],
        )
        .expect("batch");
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(matches!(
            receipt.path_results[0].1,
            PathPublishStatus::Applied { .. }
        ));
        assert!(server.has("lomo/memo/new.md"));

        // Second create-only must 412.
        let receipt2 = adapter.publish(&batch).expect("publish2");
        assert!(matches!(
            receipt2.path_results[0].1,
            PathPublishStatus::PreconditionFailed
        ));
    }

    #[test]
    fn conditional_put_stale_if_match_is_precondition_failed() {
        let server = FaultServer::start();
        server.put_object("lomo/memo/x.md", b"remote");
        let body = b"local";
        let mut objects = MapS3ObjectSource::default();
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
        assert!(matches!(
            receipt.path_results[0].1,
            PathPublishStatus::PreconditionFailed
        ));
    }

    #[test]
    fn conditional_delete_with_matching_etag_applies() {
        let server = FaultServer::start();
        server.put_object("lomo/memo/del.md", b"bye");
        let etag = server.etag_of("lomo/memo/del.md");
        let (_dir, adapter) = adapter_with(&server, MapS3ObjectSource::default());
        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsureAbsent {
                path: path("memo/del.md"),
                expected_remote_token: etag,
            }],
        )
        .expect("batch");
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(matches!(
            receipt.path_results[0].1,
            PathPublishStatus::Applied { .. }
        ));
        assert!(!server.has("lomo/memo/del.md"));
    }

    #[test]
    fn http_status_matrix_maps_to_stable_codes_category_and_retry() {
        let pure: &[(u16, &str, ErrorCategory, RetryDisposition)] = &[
            (
                401,
                "s3_unauthorized",
                ErrorCategory::Authentication,
                RetryDisposition::AfterUserAction,
            ),
            (
                403,
                "s3_forbidden",
                ErrorCategory::Permission,
                RetryDisposition::AfterUserAction,
            ),
            (
                404,
                "s3_not_found",
                ErrorCategory::Validation,
                RetryDisposition::Never,
            ),
            (
                409,
                "s3_conflict",
                ErrorCategory::Conflict,
                RetryDisposition::AfterUserAction,
            ),
            (
                412,
                "s3_precondition_failed",
                ErrorCategory::Conflict,
                RetryDisposition::AfterUserAction,
            ),
            (
                429,
                "s3_rate_limited",
                ErrorCategory::Busy,
                RetryDisposition::Transient,
            ),
            (
                302,
                "s3_redirect_not_followed",
                ErrorCategory::Network,
                RetryDisposition::AfterUserAction,
            ),
            (
                500,
                "s3_server_error",
                ErrorCategory::Network,
                RetryDisposition::Transient,
            ),
            (
                503,
                "s3_server_error",
                ErrorCategory::Network,
                RetryDisposition::Transient,
            ),
        ];
        for (status, code, category, retry) in pure {
            let err = map_s3_http_status("PUT", *status);
            assert_eq!(err.code(), *code, "status {status}");
            assert_eq!(error_category(&err), *category, "status {status}");
            assert_eq!(err.retry_disposition(), *retry, "status {status}");
        }

        // Wire path: force 401 on PUT
        let server = FaultServer::start();
        server.set_faults(FaultConfig {
            force_status: Some(("PUT".to_owned(), 401)),
            ..FaultConfig::default()
        });
        let body = b"auth";
        let mut objects = MapS3ObjectSource::default();
        objects
            .objects
            .insert("memo/a.md".to_owned(), body.to_vec());
        let (_dir, adapter) = adapter_with(&server, objects);
        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/a.md"),
                digest: digest_of(body),
                expected_remote_token: None,
            }],
        )
        .expect("batch");
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(matches!(
            &receipt.path_results[0].1,
            PathPublishStatus::Failed { code } if code == "s3_unauthorized"
        ));
    }

    #[test]
    fn off_origin_redirect_does_not_auto_follow() {
        let server = FaultServer::start();
        server.set_faults(FaultConfig {
            redirect_off_origin: true,
            ..FaultConfig::default()
        });
        let (_dir, adapter) = adapter_with(&server, MapS3ObjectSource::default());
        let snap = adapter.list_remote().expect("list incomplete on redirect");
        assert_eq!(snap.completeness, SnapshotCompleteness::Incomplete);
    }

    #[test]
    fn s3_incomplete_snapshot_never_plans_ensure_absent() {
        let server = FaultServer::start();
        server.put_object("lomo/memo/kept.md", b"still-here");
        server.set_faults(FaultConfig {
            list_fail: true,
            ..FaultConfig::default()
        });
        let (_dir, adapter) = adapter_with(&server, MapS3ObjectSource::default());
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
            "Incomplete S3 listing must never authorize EnsureAbsent: {:?}",
            batch.intents
        );
    }

    #[test]
    fn multipart_publish_happy_path_create_part_complete() {
        let server = FaultServer::start();
        // Body larger than threshold 8 bytes → multipart
        let body = b"0123456789abcdef0123456789abcdef"; // 32 bytes
        let mut objects = MapS3ObjectSource::default();
        objects
            .objects
            .insert("memo/big.bin".to_owned(), body.to_vec());
        let dir = tempdir().expect("temp");
        let adapter = connect_map_s3_source(lomo_sync::MapS3ConnectParams {
            endpoint_url: &server.base_url(),
            bucket: "bucket",
            prefix: "lomo/",
            region: "us-east-1",
            access_key_id: "test-access",
            secret_access_key: "test-secret",
            temp_dir: dir.path(),
            objects,
            timeout: Duration::from_secs(5),
        })
        .expect("adapter")
        .with_multipart_threshold(8);

        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/big.bin"),
                digest: digest_of(body),
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
        assert!(server.has("lomo/memo/big.bin"));
        assert!(adapter.multipart_sessions_snapshot().is_empty());
        let wire = server.multipart_wire_log();
        assert!(
            wire.iter()
                .any(|e| matches!(e, MultipartWireEvent::Create { .. })),
            "expected Create: {wire:?}"
        );
        assert_eq!(
            wire.iter()
                .filter(|e| matches!(e, MultipartWireEvent::UploadPart { .. }))
                .count(),
            4,
            "32-byte body / 8-byte parts → 4 UploadPart: {wire:?}"
        );
        assert!(
            wire.iter()
                .any(|e| matches!(e, MultipartWireEvent::Complete { .. })),
            "expected Complete: {wire:?}"
        );
    }

    #[test]
    fn multipart_resume_skips_confirmed_parts_after_mid_upload_fail() {
        let server = FaultServer::start();
        let body = b"0123456789abcdef0123456789abcdef"; // 32 bytes → 4 parts @ 8
        let mut objects = MapS3ObjectSource::default();
        objects
            .objects
            .insert("memo/resume.bin".to_owned(), body.to_vec());
        let dir = tempdir().expect("temp");
        let adapter = connect_map_s3_source(lomo_sync::MapS3ConnectParams {
            endpoint_url: &server.base_url(),
            bucket: "bucket",
            prefix: "lomo/",
            region: "us-east-1",
            access_key_id: "test-access",
            secret_access_key: "test-secret",
            temp_dir: dir.path(),
            objects,
            timeout: Duration::from_secs(5),
        })
        .expect("adapter")
        .with_multipart_threshold(8);

        server.set_faults(FaultConfig {
            list_page_size: 1000,
            fail_after_n_successful_parts: Some(1),
            ..FaultConfig::default()
        });
        server.clear_multipart_wire_log();

        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/resume.bin"),
                digest: digest_of(body),
                expected_remote_token: None,
            }],
        )
        .expect("batch");

        let first = adapter.publish(&batch).expect("first publish receipt");
        assert!(
            matches!(first.path_results[0].1, PathPublishStatus::Failed { .. }),
            "mid-upload fault must fail closed: {:?}",
            first.path_results[0].1
        );
        let sessions = adapter.multipart_sessions_snapshot();
        assert_eq!(
            sessions.len(),
            1,
            "session retained after mid-fail: {sessions:?}"
        );
        assert_eq!(sessions[0].confirmed_parts.len(), 1);
        let session_upload_id = sessions[0].upload_id.clone();
        let first_wire = server.multipart_wire_log();
        let first_parts: Vec<u32> = first_wire
            .iter()
            .filter_map(|e| match e {
                MultipartWireEvent::UploadPart {
                    upload_id,
                    part_number,
                } if *upload_id == session_upload_id => Some(*part_number),
                MultipartWireEvent::Create { .. }
                | MultipartWireEvent::UploadPart { .. }
                | MultipartWireEvent::Complete { .. }
                | MultipartWireEvent::Abort { .. } => None,
            })
            .collect();
        assert_eq!(
            first_parts,
            vec![1],
            "only part 1 confirmed before fault: {first_wire:?}"
        );

        // Clear fault; second publish must reuse session and skip part 1.
        server.set_faults(FaultConfig {
            list_page_size: 1000,
            fail_after_n_successful_parts: None,
            ..FaultConfig::default()
        });
        // Do not clear wire log — assert cumulative part uploads never re-POST part 1.
        let second = adapter.publish(&batch).expect("second publish");
        assert!(
            matches!(second.path_results[0].1, PathPublishStatus::Applied { .. }),
            "resume must complete: {:?}",
            second.path_results[0].1
        );
        assert!(server.has("lomo/memo/resume.bin"));
        assert!(
            adapter.multipart_sessions_snapshot().is_empty(),
            "session cleared after complete"
        );

        let wire = server.multipart_wire_log();
        let creates: Vec<&String> = wire
            .iter()
            .filter_map(|e| match e {
                MultipartWireEvent::Create { upload_id, .. } => Some(upload_id),
                MultipartWireEvent::UploadPart { .. }
                | MultipartWireEvent::Complete { .. }
                | MultipartWireEvent::Abort { .. } => None,
            })
            .collect();
        assert_eq!(
            creates.len(),
            1,
            "resume must reuse one Create (no second InitiateMultipart): {wire:?}"
        );
        assert_eq!(creates[0], &session_upload_id);

        let part_events: Vec<(String, u32)> = wire
            .iter()
            .filter_map(|e| match e {
                MultipartWireEvent::UploadPart {
                    upload_id,
                    part_number,
                } => Some((upload_id.clone(), *part_number)),
                MultipartWireEvent::Create { .. }
                | MultipartWireEvent::Complete { .. }
                | MultipartWireEvent::Abort { .. } => None,
            })
            .collect();
        assert_eq!(
            part_events,
            vec![
                (session_upload_id.clone(), 1),
                (session_upload_id.clone(), 2),
                (session_upload_id.clone(), 3),
                (session_upload_id.clone(), 4),
            ],
            "confirmed part 1 must not be re-uploaded; remaining parts only: {wire:?}"
        );
        assert!(
            wire.iter().any(|e| matches!(
                e,
                MultipartWireEvent::Complete { upload_id } if *upload_id == session_upload_id
            )),
            "complete must reuse session upload id: {wire:?}"
        );
        assert!(
            !wire
                .iter()
                .any(|e| matches!(e, MultipartWireEvent::Abort { .. })),
            "same-digest resume must not abort: {wire:?}"
        );
    }

    #[test]
    fn multipart_digest_mismatch_aborts_stale_session_before_restart() {
        use std::sync::Arc as StdArc;

        #[derive(Clone)]
        struct SharedMapSource {
            objects: StdArc<Mutex<HashMap<String, Vec<u8>>>>,
        }
        impl S3ObjectSource for SharedMapSource {
            fn load_bytes(
                &self,
                path: &SyncPath,
                expected_digest: &ContentDigest,
            ) -> Result<Vec<u8>, lomo_core::LomoError> {
                let bytes = self
                    .objects
                    .lock()
                    .expect("objects")
                    .get(path.as_str())
                    .cloned()
                    .ok_or_else(|| {
                        lomo_sync::sync_validation(
                            "s3_object_source_missing",
                            "missing shared object",
                        )
                    })?;
                let digest = format!("{:x}", Sha256::digest(&bytes));
                if digest != expected_digest.as_str() {
                    return Err(lomo_sync::sync_validation(
                        "s3_object_source_digest_mismatch",
                        "shared source digest mismatch",
                    ));
                }
                Ok(bytes)
            }
        }

        let server = FaultServer::start();
        let body_a = b"0123456789abcdef0123456789abcdef"; // 32 bytes
        let body_b = b"ffffffffffffffffffffffffffffffff"; // different digest
        let dir = tempdir().expect("temp");
        let shared = SharedMapSource {
            objects: StdArc::new(Mutex::new(HashMap::from([(
                "memo/swap.bin".to_owned(),
                body_a.to_vec(),
            )]))),
        };
        let endpoint = S3Endpoint::parse(
            &server.base_url(),
            "bucket",
            "lomo/",
            "us-east-1",
            S3AddressingStyle::PathStyle,
        )
        .expect("endpoint");
        let credentials = S3Credentials::new("test-access", "test-secret").expect("credentials");
        let adapter = lomo_sync::S3Adapter::connect(
            endpoint,
            credentials,
            dir.path(),
            shared.clone(),
            Duration::from_secs(5),
        )
        .expect("adapter")
        .with_multipart_threshold(8);

        server.set_faults(FaultConfig {
            list_page_size: 1000,
            fail_after_n_successful_parts: Some(1),
            ..FaultConfig::default()
        });
        server.clear_multipart_wire_log();

        let batch_a = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/swap.bin"),
                digest: digest_of(body_a),
                expected_remote_token: None,
            }],
        )
        .expect("batch_a");
        let first = adapter.publish(&batch_a).expect("first");
        assert!(matches!(
            first.path_results[0].1,
            PathPublishStatus::Failed { .. }
        ));
        let stale_id = adapter.multipart_sessions_snapshot()[0].upload_id.clone();

        // Change content under the same path; second publish uses body_b digest.
        shared
            .objects
            .lock()
            .expect("objects")
            .insert("memo/swap.bin".to_owned(), body_b.to_vec());
        server.set_faults(FaultConfig {
            list_page_size: 1000,
            fail_after_n_successful_parts: None,
            ..FaultConfig::default()
        });

        let batch_b = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/swap.bin"),
                digest: digest_of(body_b),
                expected_remote_token: None,
            }],
        )
        .expect("batch_b");
        let second = adapter.publish(&batch_b).expect("second");
        assert!(
            matches!(second.path_results[0].1, PathPublishStatus::Applied { .. }),
            "digest-mismatch restart must apply: {:?}",
            second.path_results[0].1
        );
        let wire = server.multipart_wire_log();
        assert!(
            wire.iter().any(|e| matches!(
                e,
                MultipartWireEvent::Abort { upload_id } if *upload_id == stale_id
            )),
            "stale session must be aborted: {wire:?}"
        );
        let creates: Vec<&String> = wire
            .iter()
            .filter_map(|e| match e {
                MultipartWireEvent::Create { upload_id, .. } => Some(upload_id),
                MultipartWireEvent::UploadPart { .. }
                | MultipartWireEvent::Complete { .. }
                | MultipartWireEvent::Abort { .. } => None,
            })
            .collect();
        assert_eq!(creates.len(), 2, "abort then new create: {wire:?}");
        assert_ne!(creates[1], &stale_id);
        assert!(server.has("lomo/memo/swap.bin"));
    }

    #[test]
    fn verify_reads_digest_and_absent_paths() {
        let server = FaultServer::start();
        let body = b"verify-me";
        server.put_object("lomo/memo/v.md", body);
        let (_dir, adapter) = adapter_with(&server, MapS3ObjectSource::default());
        let verified = adapter
            .verify(&[path("memo/v.md"), path("memo/missing.md")])
            .expect("verify");
        assert_eq!(verified.results.len(), 2);
        match &verified.results[0] {
            lomo_sync::VerifyStatus::Verified { digest, .. } => {
                assert_eq!(digest.as_str(), digest_of(body).as_str());
            }
            lomo_sync::VerifyStatus::AbsentVerified { .. }
            | lomo_sync::VerifyStatus::Failed { .. } => {
                panic!("expected verified present, got {:?}", verified.results[0])
            }
        }
        assert!(matches!(
            verified.results[1],
            lomo_sync::VerifyStatus::AbsentVerified { .. }
        ));
    }

    #[test]
    fn object_source_digest_mismatch_fails_closed() {
        let mut objects = MapS3ObjectSource::default();
        objects.objects.insert("a.md".to_owned(), b"bytes".to_vec());
        let err = objects
            .load_bytes(&path("a.md"), &digest_of(b"other"))
            .expect_err("mismatch");
        assert_eq!(err.code(), "s3_object_source_digest_mismatch");
    }

    #[test]
    fn rclone_data_vector_decrypts_fixture_plaintext() {
        let vectors_path = repo_root().join("fixtures/remote/rclone-crypt-vectors.json");
        let raw = fs::read_to_string(&vectors_path).expect("vectors file");
        let password = "fixture-test-password";
        let password2 = "fixture-test-salt";
        let material = RcloneKeyMaterial::derive(password, password2).expect("derive");
        // Extract ciphertext_hex for plain-data-block without full JSON dep
        let hex = raw
            .split("\"id\": \"plain-data-block\"")
            .nth(1)
            .expect("vector")
            .split("\"ciphertext_hex\": \"")
            .nth(1)
            .expect("hex field")
            .split('"')
            .next()
            .expect("hex value");
        let ciphertext = hex_decode(hex);
        let plain = decrypt_payload(&ciphertext, &material).expect("decrypt");
        // Ciphertext oracle is authoritative: sealed body includes trailing newline (19 bytes).
        assert_eq!(
            plain,
            b"hello-lomo-fixture
"
        );
    }

    #[test]
    fn rclone_filename_vectors_encrypt_match_fixture_names() {
        // Fixture file presence is a contract anchor; names below are the locked golden pairs.
        let vectors_path = repo_root().join("fixtures/remote/rclone-crypt-vectors.json");
        assert!(
            vectors_path.is_file(),
            "missing rclone crypt vectors at {}",
            vectors_path.display()
        );
        let material =
            RcloneKeyMaterial::derive("fixture-test-password", "fixture-test-salt").expect("key");
        let config = RcloneCryptConfig {
            filename_encryption: RcloneFilenameEncryption::Standard,
            directory_name_encryption: true,
            filename_encoding: RcloneFilenameEncoding::Base32,
            data_encryption_enabled: true,
            encrypted_suffix: String::new(),
        };
        for (plain, cipher) in [
            ("2024-01-02.md", "7o3chldpojhcbj6rpgp3n45fi8"),
            ("lomo", "n5jkq3ikgne08nnt2bguj7ompo"),
            ("media", "pm5t4p9hsjlu79s5h4t8i302ik"),
            ("memo", "8u8olquekmda1qnkv31dj1h5dk"),
        ] {
            let encrypted = encrypt_filename_path(plain, &material, &config).expect("encrypt");
            assert_eq!(
                encrypted, cipher,
                "filename encrypt mismatch for {plain} (fixture {cipher})"
            );
            let decrypted = decrypt_filename_path(&encrypted, &material, &config).expect("decrypt");
            assert_eq!(decrypted, plain);
        }
    }

    #[test]
    fn rclone_payload_encrypt_decrypt_round_trip() {
        let material =
            RcloneKeyMaterial::derive("fixture-test-password", "fixture-test-salt").expect("key");
        let nonce = [7_u8; 24];
        let plain = b"round-trip-body";
        let sealed = encrypt_payload(plain, &material, &nonce).expect("encrypt");
        assert!(sealed.starts_with(b"RCLONE\0\0"));
        let opened = decrypt_payload(&sealed, &material).expect("decrypt");
        assert_eq!(opened, plain);
    }

    /// Stage-5 product bound: host-proven rclone surface is fixture standard/base32 only.
    /// Non-fixture modes remain typed code paths (not residual OPEN for full CLI goldens).
    #[test]
    fn rclone_non_fixture_modes_remain_typed_code_paths_not_host_residual() {
        let material =
            RcloneKeyMaterial::derive("fixture-test-password", "fixture-test-salt").expect("key");

        // Obfuscate + Off are constructible and reversible on the typed path (code-path only).
        let obfuscate = RcloneCryptConfig {
            filename_encryption: RcloneFilenameEncryption::Obfuscate,
            directory_name_encryption: true,
            filename_encoding: RcloneFilenameEncoding::Base32,
            data_encryption_enabled: true,
            encrypted_suffix: String::new(),
        };
        let off = RcloneCryptConfig {
            filename_encryption: RcloneFilenameEncryption::Off,
            directory_name_encryption: false,
            filename_encoding: RcloneFilenameEncoding::Base64,
            data_encryption_enabled: true,
            encrypted_suffix: ".bin".to_owned(),
        };
        let plain = "memo/note.md";
        let obfuscated = encrypt_filename_path(plain, &material, &obfuscate).expect("obfuscate");
        assert_ne!(obfuscated, plain);
        assert_eq!(
            decrypt_filename_path(&obfuscated, &material, &obfuscate).expect("deobfuscate"),
            plain
        );
        let off_name = encrypt_filename_path(plain, &material, &off).expect("off encrypt");
        assert_eq!(off_name, "memo/note.md.bin");
        assert_eq!(
            decrypt_filename_path(&off_name, &material, &off).expect("off decrypt"),
            plain
        );

        // Base64 standard path is typed and round-trips (not fixture residual OPEN).
        let base64_cfg = RcloneCryptConfig {
            filename_encryption: RcloneFilenameEncryption::Standard,
            directory_name_encryption: true,
            filename_encoding: RcloneFilenameEncoding::Base64,
            data_encryption_enabled: true,
            encrypted_suffix: String::new(),
        };
        let b64 = encrypt_filename_path("a.md", &material, &base64_cfg).expect("base64 encrypt");
        assert_eq!(
            decrypt_filename_path(&b64, &material, &base64_cfg).expect("base64 decrypt"),
            "a.md"
        );
    }

    /// Given mid-upload fault + durable multipart root, when a **new** adapter process resumes the
    /// same path/digest, then confirmed parts are not re-uploaded and the object completes.
    #[test]
    fn durable_multipart_session_survives_process_death_and_skips_confirmed_parts() {
        let server = FaultServer::start();
        let body = b"0123456789abcdef0123456789abcdef"; // 32 bytes → 4 parts @ 8
        let mut objects = MapS3ObjectSource::default();
        objects
            .objects
            .insert("memo/durable.bin".to_owned(), body.to_vec());
        let workspace = tempdir().expect("workspace");
        let temp = tempdir().expect("temp");

        let first_adapter = connect_map_s3_source(lomo_sync::MapS3ConnectParams {
            endpoint_url: &server.base_url(),
            bucket: "bucket",
            prefix: "lomo/",
            region: "us-east-1",
            access_key_id: "test-access",
            secret_access_key: "test-secret",
            temp_dir: temp.path(),
            objects: objects.clone(),
            timeout: Duration::from_secs(5),
        })
        .expect("first adapter")
        .with_multipart_threshold(8)
        .with_durable_multipart_root(workspace.path());

        server.set_faults(FaultConfig {
            list_page_size: 1000,
            fail_after_n_successful_parts: Some(1),
            ..FaultConfig::default()
        });
        server.clear_multipart_wire_log();

        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/durable.bin"),
                digest: digest_of(body),
                expected_remote_token: None,
            }],
        )
        .expect("batch");

        let first = first_adapter.publish(&batch).expect("first publish");
        assert!(
            matches!(first.path_results[0].1, PathPublishStatus::Failed { .. }),
            "mid-upload fault must fail closed: {:?}",
            first.path_results[0].1
        );
        let sessions = first_adapter.multipart_sessions_snapshot();
        assert_eq!(
            sessions.len(),
            1,
            "session retained after mid-fail: {sessions:?}"
        );
        assert_eq!(sessions[0].confirmed_parts.len(), 1);
        let session_upload_id = sessions[0].upload_id.clone();
        let multipart_dir = workspace
            .path()
            .join(".lomo")
            .join("sync")
            .join("v1")
            .join("multipart");
        assert!(
            multipart_dir.is_dir(),
            "durable multipart directory must exist after mid-fail"
        );
        let rec_count = fs::read_dir(&multipart_dir)
            .expect("read multipart dir")
            .map(|entry| entry.expect("multipart dir entry"))
            .filter(|e| e.path().extension().is_some_and(|ext| ext == "rec"))
            .count();
        assert_eq!(rec_count, 1, "one durable multipart record expected");

        // Process death: drop first adapter (memory sessions gone) and construct a fresh adapter
        // on the same durable workspace root + shared hermetic server.
        drop(first_adapter);

        server.set_faults(FaultConfig {
            list_page_size: 1000,
            fail_after_n_successful_parts: None,
            ..FaultConfig::default()
        });

        let second_adapter = connect_map_s3_source(lomo_sync::MapS3ConnectParams {
            endpoint_url: &server.base_url(),
            bucket: "bucket",
            prefix: "lomo/",
            region: "us-east-1",
            access_key_id: "test-access",
            secret_access_key: "test-secret",
            temp_dir: temp.path(),
            objects,
            timeout: Duration::from_secs(5),
        })
        .expect("second adapter")
        .with_multipart_threshold(8)
        .with_durable_multipart_root(workspace.path());

        let revived = second_adapter.multipart_sessions_snapshot();
        assert_eq!(
            revived.len(),
            1,
            "fresh process must load durable multipart session: {revived:?}"
        );
        assert_eq!(revived[0].upload_id, session_upload_id);
        assert_eq!(revived[0].confirmed_parts.len(), 1);

        let second = second_adapter.publish(&batch).expect("second publish");
        assert!(
            matches!(second.path_results[0].1, PathPublishStatus::Applied { .. }),
            "process-death resume must complete: {:?}",
            second.path_results[0].1
        );
        assert!(server.has("lomo/memo/durable.bin"));
        assert!(
            second_adapter.multipart_sessions_snapshot().is_empty(),
            "session cleared after complete"
        );
        let remaining = fs::read_dir(&multipart_dir)
            .expect("read multipart dir after complete")
            .map(|entry| entry.expect("multipart dir entry after complete"))
            .filter(|e| e.path().extension().is_some_and(|ext| ext == "rec"))
            .count();
        assert_eq!(remaining, 0, "durable session removed after complete");

        let wire = server.multipart_wire_log();
        let creates: Vec<&String> = wire
            .iter()
            .filter_map(|e| match e {
                MultipartWireEvent::Create { upload_id, .. } => Some(upload_id),
                MultipartWireEvent::UploadPart { .. }
                | MultipartWireEvent::Complete { .. }
                | MultipartWireEvent::Abort { .. } => None,
            })
            .collect();
        assert_eq!(
            creates.len(),
            1,
            "process-death resume must reuse one Create: {wire:?}"
        );
        assert_eq!(creates[0], &session_upload_id);
        let part_events: Vec<(String, u32)> = wire
            .iter()
            .filter_map(|e| match e {
                MultipartWireEvent::UploadPart {
                    upload_id,
                    part_number,
                } => Some((upload_id.clone(), *part_number)),
                MultipartWireEvent::Create { .. }
                | MultipartWireEvent::Complete { .. }
                | MultipartWireEvent::Abort { .. } => None,
            })
            .collect();
        assert_eq!(
            part_events,
            [
                (session_upload_id.as_str(), 1),
                (session_upload_id.as_str(), 2),
                (session_upload_id.as_str(), 3),
                (session_upload_id.as_str(), 4),
            ]
            .map(|(id, n)| (id.to_owned(), n))
            .to_vec(),
            "confirmed part 1 must not be re-uploaded after process death: {wire:?}"
        );
        assert!(
            !wire
                .iter()
                .any(|e| matches!(e, MultipartWireEvent::Abort { .. })),
            "same-digest durable resume must not abort: {wire:?}"
        );
    }

    /// Given a corrupt durable multipart record, when a fresh adapter loads sessions, then
    /// `CorruptState` is returned (never clean-slate / silent drop).
    #[test]
    fn durable_multipart_corrupt_record_fails_closed() {
        let server = FaultServer::start();
        let workspace = tempdir().expect("workspace");
        let temp = tempdir().expect("temp");
        let multipart_dir = workspace
            .path()
            .join(".lomo")
            .join("sync")
            .join("v1")
            .join("multipart");
        fs::create_dir_all(&multipart_dir).expect("multipart dir");

        // Plant a path-keyed corrupt LSYN-looking record for the resume path.
        let path_key = "memo/corrupt.bin";
        let digest_name = format!("{:x}", Sha256::digest(path_key.as_bytes()));
        fs::write(
            multipart_dir.join(format!("{digest_name}.rec")),
            b"LSYN\0\0\0\0truncated",
        )
        .expect("path-keyed corrupt");

        let body = b"0123456789abcdef0123456789abcdef";
        let mut objects = MapS3ObjectSource::default();
        objects.objects.insert(path_key.to_owned(), body.to_vec());
        let adapter = connect_map_s3_source(lomo_sync::MapS3ConnectParams {
            endpoint_url: &server.base_url(),
            bucket: "bucket",
            prefix: "lomo/",
            region: "us-east-1",
            access_key_id: "test-access",
            secret_access_key: "test-secret",
            temp_dir: temp.path(),
            objects,
            timeout: Duration::from_secs(5),
        })
        .expect("adapter")
        .with_multipart_threshold(8)
        .with_durable_multipart_root(workspace.path());

        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path(path_key),
                digest: digest_of(body),
                expected_remote_token: None,
            }],
        )
        .expect("batch");
        let receipt = adapter.publish(&batch).expect("publish returns receipt");
        assert!(
            matches!(
                receipt.path_results[0].1,
                PathPublishStatus::Failed { ref code }
                    if code == "s3_multipart_session_corrupt"
                        || code == "s3_multipart_session_payload_invalid"
                        || code.starts_with("s3_multipart_session")
                        || code.starts_with("sync_")
            ),
            "corrupt durable multipart must fail closed, not clean-slate: {:?}",
            receipt.path_results[0].1
        );
        assert!(
            !server.has("lomo/memo/corrupt.bin"),
            "corrupt session must not complete publish"
        );
    }

    fn hex_decode(hex: &str) -> Vec<u8> {
        let mut out = Vec::with_capacity(hex.len() / 2);
        let bytes = hex.as_bytes();
        let mut i = 0;
        while i + 1 < bytes.len() {
            let h = hex_val(bytes[i]).expect("hex");
            let l = hex_val(bytes[i + 1]).expect("hex");
            out.push((h << 4) | l);
            i += 2;
        }
        out
    }
}
