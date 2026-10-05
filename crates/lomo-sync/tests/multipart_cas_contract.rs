// adversarial-audit: multipart publish honours the CAS anchor end-to-end
//
// Probes (now GREEN regression locks):
//   1. A HEAD *transport failure* during the create-only precondition probe is not proof
//      of absence — the publish fails closed (`Failed`), never `Ok` → unconditional commit.
//   2. `CompleteMultipartUpload` carries the CAS anchor as a commit-time conditional
//      (`If-Match` / `If-None-Match: *`), so a remote change between the HEAD preflight
//      and the commit — a concurrent writer racing the upload — fails closed with
//      `PreconditionFailed` and never overwrites.
#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::format_push_string,
    clippy::too_many_lines,
    reason = "contract probes fail closed on missing facts; hermetic wire slicing is bounds-checked by construction"
)]
mod tests {
    use std::collections::HashMap;
    use std::io::{Read, Write};
    use std::net::{SocketAddr, TcpListener, TcpStream};
    use std::sync::atomic::Ordering;
    use std::sync::{Arc, Mutex};
    use std::thread;
    use std::time::Duration;

    use lomo_sync::{
        BatchAtomicity, ContentDigest, MapS3ConnectParams, MapS3ObjectSource, PathPublishStatus,
        PreparedRemoteBatch, ProviderNeutralIntent, RemoteSyncPort, SyncPath,
        connect_map_s3_source,
    };
    use sha2::Digest;

    /// Committed remote objects: key → (body, `ETag`).
    type CommittedStore = Arc<Mutex<HashMap<String, (Vec<u8>, String)>>>;

    /// Minimal path-style S3 stub honoring conditional writes at every mutating request:
    /// HEAD may fault for configured keys, `CompleteMultipartUpload` enforces
    /// `If-None-Match: *` / `If-Match` against the stored `ETag`, and configured keys can
    /// be "race-inserted" by a simulated concurrent writer at `CreateMultipartUpload`
    /// time — the exact gap between the adapter's HEAD preflight and the commit.
    struct StubServer {
        addr: SocketAddr,
        shutdown: Arc<std::sync::atomic::AtomicBool>,
        committed: CommittedStore,
    }

    impl StubServer {
        fn start(
            existing: HashMap<String, Vec<u8>>,
            head_fault_keys: Vec<String>,
            race_insert_keys: Vec<String>,
        ) -> Self {
            let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
            let addr = listener.local_addr().expect("addr");
            let shutdown = Arc::new(std::sync::atomic::AtomicBool::new(false));
            let committed = Arc::new(Mutex::new(
                existing
                    .into_iter()
                    .map(|(k, body)| (k, (body, "\"etag-live\"".to_owned())))
                    .collect::<HashMap<_, _>>(),
            ));
            let faults = Arc::new(Mutex::new(head_fault_keys));
            let race_insert = Arc::new(Mutex::new(race_insert_keys));
            let state = (
                Arc::clone(&shutdown),
                Arc::clone(&committed),
                faults,
                race_insert,
            );
            thread::spawn(move || {
                loop {
                    let Ok((mut stream, _)) = listener.accept() else {
                        break;
                    };
                    if state.0.load(Ordering::Acquire) {
                        break;
                    }
                    drop(stream.set_read_timeout(Some(Duration::from_secs(5))));
                    handle_conn(&mut stream, &state.1, &state.2, &state.3);
                }
            });
            Self {
                addr,
                shutdown,
                committed,
            }
        }

        fn base_url(&self) -> String {
            format!("http://{}", self.addr)
        }

        fn committed_body(&self, key: &str) -> Option<Vec<u8>> {
            self.committed
                .lock()
                .expect("committed")
                .get(key)
                .map(|o| o.0.clone())
        }
    }

    impl Drop for StubServer {
        fn drop(&mut self) {
            self.shutdown.store(true, Ordering::Release);
            // behavior-contract: silent-result-ok: the wakeup connect only unblocks accept();
            // a refused connection means the listener is already gone.
            drop(TcpStream::connect(self.addr));
        }
    }

    fn handle_conn(
        stream: &mut TcpStream,
        committed: &CommittedStore,
        faults: &Arc<Mutex<Vec<String>>>,
        race_insert: &Arc<Mutex<Vec<String>>>,
    ) {
        let mut raw = Vec::new();
        let mut buf = [0u8; 8192];
        // One request per connection; reqwest retries/reconnects on `Connection: close`.
        loop {
            match stream.read(&mut buf) {
                Ok(0) | Err(_) => return,
                Ok(n) => raw.extend_from_slice(&buf[..n]),
            }
            if let Some(end) = find_header_end(&raw) {
                let head = String::from_utf8_lossy(&raw[..end]).to_string();
                let len = content_length(&head);
                if raw.len() >= end + 4 + len {
                    break;
                }
            }
        }
        let head = find_header_end(&raw).map_or_else(String::new, |end| {
            String::from_utf8_lossy(&raw[..end]).to_string()
        });
        let (method, path_q) = parse_request_line(&raw);
        let key = path_q
            .split('?')
            .next()
            .unwrap_or("")
            .trim_start_matches('/')
            .strip_prefix("bucket/")
            .unwrap_or("")
            .to_string();

        match (
            method.as_str(),
            path_q.contains("uploads"),
            path_q.contains("uploadId="),
        ) {
            ("HEAD", _, _) => {
                let faulted = faults
                    .lock()
                    .expect("faults")
                    .iter()
                    .any(|k| key.ends_with(k.as_str()));
                if faulted {
                    respond(stream, "500 Internal Server Error", None, b"");
                } else {
                    let etag = committed
                        .lock()
                        .expect("committed")
                        .get(&key)
                        .map(|o| o.1.clone());
                    match etag {
                        Some(etag) => respond(stream, "200 OK", Some(&etag), b""),
                        None => respond(stream, "404 Not Found", None, b""),
                    }
                }
            }
            ("POST", true, false) => {
                // CreateMultipartUpload — a configured key is written by the simulated
                // concurrent writer here: between the adapter's HEAD preflight and the
                // CompleteMultipartUpload commit.
                let raced = race_insert
                    .lock()
                    .expect("race_insert")
                    .iter()
                    .any(|k| key.ends_with(k.as_str()));
                if raced && let Ok(mut map) = committed.lock() {
                    map.insert(key, (b"RACE-WRITER".to_vec(), "\"etag-raced\"".to_owned()));
                }
                respond(
                    stream,
                    "200 OK",
                    None,
                    br#"<?xml version="1.0"?><InitiateMultipartUploadResult><UploadId>u-1</UploadId></InitiateMultipartUploadResult>"#,
                );
            }
            ("PUT", _, true) => {
                respond(stream, "200 OK", Some("\"part-etag\""), b"");
            }
            ("POST", _, true) => {
                // CompleteMultipartUpload — the commit enforces the conditional headers
                // exactly like a conditional PUT: `If-None-Match: *` requires absence,
                // `If-Match` requires the live ETag to still match.
                let if_none_star =
                    header_value(&head, "if-none-match").is_some_and(|v| v.trim() == "*");
                let if_match = header_value(&head, "if-match");
                let live_etag = committed
                    .lock()
                    .expect("committed")
                    .get(&key)
                    .map(|o| o.1.clone());
                let violated = if if_none_star {
                    live_etag.is_some()
                } else {
                    if_match.is_some_and(|expected| {
                        live_etag.as_deref().map(strip_quotes) != Some(strip_quotes(&expected))
                    })
                };
                if violated {
                    respond(stream, "412 Precondition Failed", None, b"");
                    return;
                }
                let body_start = find_header_end(&raw).map_or(raw.len(), |e| e + 4);
                let body_len = content_length(&head);
                let _xml = &raw[body_start..(body_start + body_len).min(raw.len())];
                if let Ok(mut map) = committed.lock() {
                    map.insert(key, (b"NEW-OVERWRITE".to_vec(), "\"etag-new\"".to_owned()));
                }
                respond(stream, "200 OK", Some("\"etag-new\""), b"");
            }
            ("DELETE", _, true) => respond(stream, "204 No Content", None, b""),
            _ => respond(stream, "400 Bad Request", None, b""),
        }
    }

    fn header_value(head: &str, name: &str) -> Option<String> {
        head.lines().find_map(|line| {
            line.to_ascii_lowercase()
                .starts_with(&format!("{name}:"))
                .then(|| line.get(name.len() + 1..).map_or("", str::trim).to_owned())
        })
    }

    fn strip_quotes(token: &str) -> &str {
        token.trim_matches('"')
    }

    fn find_header_end(raw: &[u8]) -> Option<usize> {
        raw.windows(4).position(|w| w == b"\r\n\r\n")
    }

    fn content_length(head: &str) -> usize {
        head.lines()
            .find_map(|l| {
                l.to_ascii_lowercase()
                    .starts_with("content-length:")
                    .then(|| l.get(15..).map_or(0, |v| v.trim().parse().unwrap_or(0)))
            })
            .unwrap_or(0)
    }

    fn parse_request_line(raw: &[u8]) -> (String, String) {
        let line = String::from_utf8_lossy(raw)
            .lines()
            .next()
            .unwrap_or("")
            .to_string();
        let mut it = line.split_whitespace();
        (
            it.next().unwrap_or("").to_string(),
            it.next().unwrap_or("").to_string(),
        )
    }

    fn respond(stream: &mut TcpStream, status: &str, etag: Option<&str>, body: &[u8]) {
        let mut resp = format!(
            "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n",
            body.len()
        );
        if let Some(tag) = etag {
            resp.push_str(&format!("ETag: {tag}\r\n"));
        }
        resp.push_str("\r\n");
        // behavior-contract: silent-result-ok: a stub write failure only truncates the test's
        // own response; the client-side assertion observes the failure downstream.
        drop(stream.write_all(resp.as_bytes()));
        drop(stream.write_all(body));
    }

    /// Create-only multipart publish while HEAD faults and the object already exists remotely.
    /// Contract: a faulting precondition probe is not proof of absence — the publish must
    /// fail closed and never commit over the unobserved remote object.
    #[test]
    fn create_only_multipart_with_faulting_head_must_not_overwrite() {
        let key = "lomo/memo/big.md";
        let mut existing = HashMap::new();
        existing.insert(key.to_string(), b"ORIGINAL-REMOTE".to_vec());
        let server = StubServer::start(existing, vec![key.to_string()], Vec::new());

        let body = b"adversarial-body".to_vec();
        let digest =
            ContentDigest::parse(&format!("{:x}", sha2::Sha256::digest(&body))).expect("digest");
        let mut objects = MapS3ObjectSource::default();
        objects.objects.insert("memo/big.md".to_owned(), body);

        let dir = tempfile::tempdir().expect("temp");
        let adapter = connect_map_s3_source(MapS3ConnectParams {
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
        .with_multipart_threshold(1);

        let batch = PreparedRemoteBatch::with_snapshot_token(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: SyncPath::parse("memo/big.md").expect("path"),
                digest,
                expected_remote_token: None, // create-only
            }],
            None,
        )
        .expect("batch");

        let receipt = adapter.publish(&batch).expect("publish");
        let status = &receipt.path_results.first().expect("row").1;

        // A faulting precondition probe must not fail open into an unconditional
        // overwrite of an existing remote object.
        assert!(
            matches!(
                status,
                PathPublishStatus::PreconditionFailed | PathPublishStatus::Failed { .. }
            ),
            "multipart create committed over an existing remote object despite a faulting HEAD probe (status={status:?})"
        );
        assert_eq!(
            server.committed_body(key).as_deref(),
            Some(b"ORIGINAL-REMOTE".as_slice()),
            "remote body overwritten by unconditional CompleteMultipartUpload"
        );
    }

    /// Race coverage: the key is absent at HEAD preflight, but a concurrent writer lands
    /// it between `CreateMultipartUpload` and the commit. `If-None-Match: *` on
    /// `CompleteMultipartUpload` must fail closed — the raced writer's bytes survive.
    #[test]
    fn create_only_multipart_losing_race_to_concurrent_writer_must_not_overwrite() {
        let key = "lomo/memo/race.md";
        let server = StubServer::start(HashMap::new(), Vec::new(), vec![key.to_string()]);

        let body = b"multipart-race-body".to_vec();
        let digest =
            ContentDigest::parse(&format!("{:x}", sha2::Sha256::digest(&body))).expect("digest");
        let mut objects = MapS3ObjectSource::default();
        objects.objects.insert("memo/race.md".to_owned(), body);

        let dir = tempfile::tempdir().expect("temp");
        let adapter = connect_map_s3_source(MapS3ConnectParams {
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
        .with_multipart_threshold(1);

        let batch = PreparedRemoteBatch::with_snapshot_token(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: SyncPath::parse("memo/race.md").expect("path"),
                digest,
                expected_remote_token: None, // create-only: if-none-match must hold at commit
            }],
            None,
        )
        .expect("batch");

        let receipt = adapter.publish(&batch).expect("publish");
        let status = &receipt.path_results.first().expect("row").1;
        assert!(
            matches!(status, PathPublishStatus::PreconditionFailed),
            "create-only complete must fail closed when the key appeared mid-upload (status={status:?})"
        );
        assert_eq!(
            server.committed_body(key).as_deref(),
            Some(b"RACE-WRITER".as_slice()),
            "concurrent writer's object overwritten by unconditional CompleteMultipartUpload"
        );
    }

    /// Same race on the update arm: HEAD preflight sees the expected `ETag`, the remote
    /// object is replaced mid-upload, and `If-Match` on the commit must fail closed.
    #[test]
    fn if_match_multipart_losing_race_to_concurrent_writer_must_not_overwrite() {
        let key = "lomo/memo/race-update.md";
        let mut existing = HashMap::new();
        existing.insert(key.to_string(), b"ORIGINAL-REMOTE".to_vec());
        let server = StubServer::start(existing, Vec::new(), vec![key.to_string()]);

        let body = b"multipart-update-body".to_vec();
        let digest =
            ContentDigest::parse(&format!("{:x}", sha2::Sha256::digest(&body))).expect("digest");
        let mut objects = MapS3ObjectSource::default();
        objects
            .objects
            .insert("memo/race-update.md".to_owned(), body);

        let dir = tempfile::tempdir().expect("temp");
        let adapter = connect_map_s3_source(MapS3ConnectParams {
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
        .with_multipart_threshold(1);

        let batch = PreparedRemoteBatch::with_snapshot_token(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: SyncPath::parse("memo/race-update.md").expect("path"),
                digest,
                expected_remote_token: Some("\"etag-live\"".to_owned()),
            }],
            None,
        )
        .expect("batch");

        let receipt = adapter.publish(&batch).expect("publish");
        let status = &receipt.path_results.first().expect("row").1;
        assert!(
            matches!(status, PathPublishStatus::PreconditionFailed),
            "if-match complete must fail closed when the live etag moved mid-upload (status={status:?})"
        );
        assert_eq!(
            server.committed_body(key).as_deref(),
            Some(b"RACE-WRITER".as_slice()),
            "concurrent writer's object overwritten by unconditional CompleteMultipartUpload"
        );
    }
}
