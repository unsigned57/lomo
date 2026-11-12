//! Behavior Contract:
//! Capability: real authenticated device sync fixture HTTP semantics; owner: lomo-feasibility; P0.
//! Scenarios: `WebDAV` authentication/CAS/listing, S3 lifecycle/multipart/credentials,
//! request evidence and a seeded bare Git remote retain their existing assertions.
//! Observable outcomes: HTTP statuses/headers/bytes, request counters and durable Git head.
//! TDD proof: Regression preservation - `cargo test -p lomo-feasibility --lib --locked`
//! before the move: 6 passed; `--test device_fixture_contract` after: same six scenarios.
//! Production scope: crates/lomo-feasibility/src/device_fixture.rs
//! Excludes: physical device, TLS and real remote accounts.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "fixture tests fail closed with panics on broken HTTP semantics"
)]
mod tests {
    use lomo_feasibility::{
        DAV_PASSWORD, DAV_USERNAME, GIT_BRANCH, HttpSyncFixture, S3_ACCESS_KEY_ID, S3_BUCKET,
        S3_REGION, seed_device_git_remote,
    };
    use std::collections::BTreeMap;
    use std::io::{Read, Write};
    use std::net::TcpStream;

    /// Sends one raw HTTP/1.1 request and parses `(status, headers, body)` — the fixture is
    /// an HTTP surface, so the pin must read the wire shape, not call internals.
    fn http(port: u16, request: &str) -> (u16, BTreeMap<String, String>, Vec<u8>) {
        let mut stream = TcpStream::connect(("127.0.0.1", port)).expect("fixture connect");
        stream.write_all(request.as_bytes()).expect("request write");
        let mut raw = Vec::new();
        stream.read_to_end(&mut raw).expect("response read");
        let split = raw
            .windows(4)
            .position(|window| window == b"\r\n\r\n")
            .expect("response header terminator");
        let (head_bytes, body_with_sep) = raw.split_at(split);
        let (_, body) = body_with_sep.split_at(4);
        let head = String::from_utf8_lossy(head_bytes);
        let mut lines = head.split("\r\n");
        let status_line = lines.next().expect("status line");
        let status = status_line
            .split_whitespace()
            .nth(1)
            .expect("status code")
            .parse::<u16>()
            .expect("numeric status code");
        let mut headers = BTreeMap::new();
        for line in lines {
            let Some((name, value)) = line.split_once(':') else {
                continue;
            };
            headers.insert(name.trim().to_ascii_lowercase(), value.trim().to_owned());
        }
        (status, headers, body.to_vec())
    }

    fn dav_auth() -> String {
        format!(
            "Basic {}",
            data_encoding::BASE64.encode(format!("{DAV_USERNAME}:{DAV_PASSWORD}").as_bytes())
        )
    }

    fn s3_auth() -> String {
        format!(
            "AWS4-HMAC-SHA256 Credential={S3_ACCESS_KEY_ID}/20200101/{S3_REGION}/s3/aws4_request, \
             SignedHeaders=host;x-amz-date, Signature=deadbeef"
        )
    }

    #[test]
    fn webdav_put_get_and_preconditions() {
        let fixture = HttpSyncFixture::start().expect("fixture start");
        let port = fixture.port();
        let auth = dav_auth();
        let put = |headers: &str| {
            http(
                port,
                &format!(
                    "PUT /dav/dir/a.txt HTTP/1.1\r\nHost: x\r\nAuthorization: {auth}\r\n{headers}Content-Length: 3\r\n\r\nabc"
                ),
            )
        };
        let (status, _, _) = http(
            port,
            "PUT /dav/dir/a.txt HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\n\r\nabc",
        );
        assert_eq!(status, 401, "unauthenticated PUT must be challenged");
        let (status, _, _) = http(
            port,
            &format!("MKCOL /dav/dir HTTP/1.1\r\nHost: x\r\nAuthorization: {auth}\r\n\r\n"),
        );
        assert_eq!(status, 201, "MKCOL creates the parent collection");
        let (status, headers, _) = put("");
        assert_eq!(status, 201, "first PUT creates the resource");
        let etag = headers.get("etag").expect("ETag header").clone();
        let (status, _, _) = put("If-None-Match: *\r\n");
        assert_eq!(status, 412, "If-None-Match:* on an existing resource fails");
        let (status, _, _) = put(&format!("If-Match: {etag}\r\n"));
        assert_eq!(status, 200, "If-Match with the live ETag updates");
        let (status, _, body) = http(
            port,
            &format!("GET /dav/dir/a.txt HTTP/1.1\r\nHost: x\r\nAuthorization: {auth}\r\n\r\n"),
        );
        assert_eq!(status, 200);
        assert_eq!(body, b"abc", "GET returns the stored bytes");
    }

    #[test]
    fn webdav_propfind_lists_collection() {
        let fixture = HttpSyncFixture::start().expect("fixture start");
        let port = fixture.port();
        let auth = dav_auth();
        let etag = fixture.webdav_write("dir/seed.txt", b"seed");
        let (status, _, body) = http(
            port,
            &format!(
                "PROPFIND /dav/dir HTTP/1.1\r\nHost: x\r\nAuthorization: {auth}\r\nDepth: 1\r\n\r\n"
            ),
        );
        assert_eq!(status, 207, "PROPFIND answers multistatus");
        let xml = String::from_utf8_lossy(&body);
        assert!(
            xml.contains("<d:href>/dav/dir/seed.txt</d:href>"),
            "multistatus lists the seeded file: {xml}"
        );
        assert!(xml.contains("<d:getetag>"), "entries carry etags: {xml}");
        assert!(
            xml.contains(etag.trim_matches('"')),
            "stored etag surfaces (xml-escaped): {xml}"
        );
    }

    #[test]
    fn s3_object_lifecycle_and_listing() {
        let fixture = HttpSyncFixture::start().expect("fixture start");
        let port = fixture.port();
        let auth = s3_auth();
        let (status, _, _) = http(
            port,
            &format!(
                "PUT /{S3_BUCKET}/ws/a HTTP/1.1\r\nHost: x\r\nAuthorization: {auth}\r\nContent-Length: 4\r\n\r\ndata"
            ),
        );
        assert_eq!(status, 200);
        let (status, headers, _) = http(
            port,
            &format!("HEAD /{S3_BUCKET}/ws/a HTTP/1.1\r\nHost: x\r\nAuthorization: {auth}\r\n\r\n"),
        );
        assert_eq!(status, 200);
        assert!(headers.contains_key("etag"), "HEAD answers the etag");
        let (status, _, body) = http(
            port,
            &format!("GET /{S3_BUCKET}/ws/a HTTP/1.1\r\nHost: x\r\nAuthorization: {auth}\r\n\r\n"),
        );
        assert_eq!(status, 200);
        assert_eq!(body, b"data");
        let (status, _, body) = http(
            port,
            &format!(
                "GET /{S3_BUCKET}?list-type=2&prefix=ws%2F HTTP/1.1\r\nHost: x\r\nAuthorization: {auth}\r\n\r\n"
            ),
        );
        assert_eq!(status, 200);
        let xml = String::from_utf8_lossy(&body);
        assert!(
            xml.contains("<Key>ws/a</Key>"),
            "listing shows the key: {xml}"
        );
        let (status, _, _) = http(
            port,
            &format!("PUT /{S3_BUCKET}/ws/b HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\n\r\nx"),
        );
        assert_eq!(status, 403, "missing SigV4 credential is rejected");
    }

    #[test]
    fn s3_multipart_upload_lifecycle() {
        let fixture = HttpSyncFixture::start().expect("fixture start");
        let port = fixture.port();
        let auth = s3_auth();
        let (status, _, body) = http(
            port,
            &format!(
                "POST /{S3_BUCKET}/ws/big?uploads HTTP/1.1\r\nHost: x\r\nAuthorization: {auth}\r\n\r\n"
            ),
        );
        assert_eq!(status, 200);
        let xml = String::from_utf8_lossy(&body);
        let upload_id = xml
            .split("<UploadId>")
            .nth(1)
            .and_then(|rest| rest.split("</UploadId>").next())
            .expect("upload id")
            .to_owned();
        for (part, bytes) in [(1, "hello"), (2, "world")] {
            let (status, _, _) = http(
                port,
                &format!(
                    "PUT /{S3_BUCKET}/ws/big?uploadId={upload_id}&partNumber={part} HTTP/1.1\r\nHost: x\r\nAuthorization: {auth}\r\nContent-Length: 5\r\n\r\n{bytes}"
                ),
            );
            assert_eq!(status, 200, "part {part} accepted");
        }
        let (status, _, _) = http(
            port,
            &format!(
                "POST /{S3_BUCKET}/ws/big?uploadId={upload_id} HTTP/1.1\r\nHost: x\r\nAuthorization: {auth}\r\nContent-Length: 0\r\n\r\n"
            ),
        );
        assert_eq!(status, 200, "complete assembles the object");
        let (_, _, body) = http(
            port,
            &format!(
                "GET /{S3_BUCKET}/ws/big HTTP/1.1\r\nHost: x\r\nAuthorization: {auth}\r\n\r\n"
            ),
        );
        assert_eq!(body, b"helloworld", "assembled bytes follow part order");
    }

    #[test]
    fn fixture_stats_track_route_evidence() {
        let fixture = HttpSyncFixture::start().expect("fixture start");
        let port = fixture.port();
        let before = fixture.stats().requests;
        let auth = dav_auth();
        let _dav_options = http(
            port,
            &format!("OPTIONS /dav/ HTTP/1.1\r\nHost: x\r\nAuthorization: {auth}\r\n\r\n"),
        );
        let _s3_list = http(
            port,
            &format!(
                "GET /{S3_BUCKET}?list-type=2 HTTP/1.1\r\nHost: x\r\nAuthorization: {}\r\n\r\n",
                s3_auth()
            ),
        );
        let stats = fixture.stats();
        assert!(stats.requests >= before + 2, "requests counted");
        assert!(
            stats.by_route.get("OPTIONS dav").copied().unwrap_or(0) >= 1,
            "dav route evidence: {stats:?}"
        );
        assert!(
            stats.by_route.get("GET s3").copied().unwrap_or(0) >= 1,
            "s3 route evidence: {stats:?}"
        );
    }

    #[test]
    fn git_remote_seed_pushes_real_history() {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("system clock must follow UNIX epoch")
            .as_nanos();
        let root = std::env::temp_dir().join(format!("lomo-git-seed-{nanos}"));
        let bare = root.join("remote.git");
        let seed = root.join("seed");
        let head = seed_device_git_remote(&bare, &seed, &[("9000_01_01.md", b"seed\n")])
            .expect("seed remote");
        assert!(!head.is_empty(), "pushed head id returned");
        let repo = git2::Repository::open(&bare).expect("open bare remote");
        let reference = repo
            .find_reference(&format!("refs/heads/{GIT_BRANCH}"))
            .expect("seeded branch ref");
        assert_eq!(
            reference.target().map(|oid| oid.to_string()).as_deref(),
            Some(head.as_str()),
            "bare remote head matches the seeded history"
        );
    }
}
