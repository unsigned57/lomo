//! Behavior Contract
//!
//! Capability: build deterministic phase-0 corpora from fixed seeds and golden fixtures.
//!
//! Scenarios:
//! - Given the same seed and quick mode, when generated twice, then manifests and digests match.
//! - Given path escape or absolute paths, when validated, then the boundary rejects them.
//! - Given a duplicate identity claim, when generation would collide, then generation fails.
//! - Given scale/capacity modes, when generated, then workload counters match the plan.
//! - Given `device` mode, when generated, then the corpus is workspace-shaped — dated
//!   `yyyy_MM_dd.md` documents at the root plus material `media/` payloads, deterministic
//!   under a fixed seed, with no host-tier buckets or logical-only attachments — so it can be
//!   pushed into a real SAF/Direct workspace and indexed by the production engine.
//! - Given the device corpus, when production parsing projects it, then 40 memos carry
//!   80 task facts, covering both pending and completed work without changing memo counts.
//! - Given repository golden fixtures, when generation runs, then fixtures are embedded under
//!   `fixtures/` with stable digests.
//!
//! Observable outcomes: byte-stable manifests, explicit `GenerateError` variants, correct workload.
//! TDD proof: unstable ordering or silent path escapes fail before probes consume corpora.
//! Test Change Justification:
//! Reason category: equivalent prefix assertion without unchecked indexing.
//! Old behavior/assertion being replaced: slicing both arrays to compare the JPEG prefix.
//! Why old assertion is no longer correct: unchecked indexing violates the strict lint gate.
//! Coverage preserved by: `starts_with` checks every prefix byte and rejects short input.
//! Why this is not fitting the test to the implementation: the golden prefix remains unchanged.
//! Reason category: broaden device workload to real task bodies.
//! Old behavior/assertion being replaced: counting every `- ` line as a memo header.
//! Why old assertion is no longer correct: task list items also start with `- `.
//! Coverage preserved by: a separate production parse test proves exactly 10 memos per
//! document, 40 task-bearing memos and 80 semantic task facts across the corpus.
//! Excludes: production DI, real remote providers, full 20 GiB materialization on disk.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "feasibility contract harness fails closed with panics on missing probe facts"
)]
mod tests {
    use std::fs;
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};

    use lomo_feasibility::{
        CAPACITY_ATTACHMENT_LOGICAL_BYTES, CAPACITY_MEMO_COUNT, CAPACITY_REMOTE_CHANGES,
        CorpusMode, DEVICE_BULK_COUNT, DEVICE_BULK_DIR, DEVICE_DOC_COUNT, DEVICE_MEDIA_COUNT,
        DEVICE_MEDIA_JPEG_PREFIX, DEVICE_MEMO_COUNT, DEVICE_MEMOS_PER_DOC, GenerateError,
        GenerateRequest, QUICK_MEMO_COUNT, QUICK_REMOTE_CHANGES, SCALE_MEMO_COUNT,
        SCALE_REMOTE_CHANGES, generate_corpus, stream_digest, validate_relative_path,
    };
    use lomo_workspace::{SemanticFactKind, SourceBytes, parse_workspace_document};

    fn repository_root() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../..")
            .canonicalize()
            .expect("repository root")
    }

    fn temp_dir(label: &str) -> PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("time")
            .as_nanos();
        let path = std::env::temp_dir().join(format!("lomo-feasibility-{label}-{nanos}"));
        fs::create_dir_all(&path).expect("temp dir");
        path
    }

    fn request(mode: CorpusMode, seed: u64, out: PathBuf) -> GenerateRequest {
        GenerateRequest {
            seed,
            mode,
            output_dir: out,
            fixture_root: repository_root().join("fixtures"),
        }
    }

    fn cleanup(path: PathBuf) {
        drop(fs::remove_dir_all(path));
    }

    #[test]
    fn same_seed_quick_corpus_is_byte_stable() {
        let first_dir = temp_dir("quick-a");
        let second_dir = temp_dir("quick-b");
        let first = generate_corpus(&request(CorpusMode::Quick, 7, first_dir.clone()))
            .expect("first generate");
        let second = generate_corpus(&request(CorpusMode::Quick, 7, second_dir.clone()))
            .expect("second generate");
        assert_eq!(
            first.to_canonical_json().expect("json"),
            second.to_canonical_json().expect("json")
        );
        assert_eq!(
            first.canonical_digest().expect("digest"),
            second.canonical_digest().expect("digest")
        );
        assert_eq!(first.workload.memo_count, QUICK_MEMO_COUNT);
        assert_eq!(first.workload.remote_change_count, QUICK_REMOTE_CHANGES);
        assert!(first.files.iter().any(|file| {
            file.relative_path
                .contains("fixtures/markdown/lomo-basic.md")
        }));
        assert!(first_dir.join("corpus-manifest.v1.json").is_file());
        cleanup(first_dir);
        cleanup(second_dir);
    }

    #[test]
    fn scale_and_capacity_workloads_match_plan_without_huge_files() {
        let scale_dir = temp_dir("scale");
        let capacity_dir = temp_dir("capacity");
        let scale =
            generate_corpus(&request(CorpusMode::Scale, 11, scale_dir.clone())).expect("scale");
        let capacity = generate_corpus(&request(CorpusMode::Capacity, 11, capacity_dir.clone()))
            .expect("capacity");
        assert_eq!(scale.workload.memo_count, SCALE_MEMO_COUNT);
        assert_eq!(scale.workload.remote_change_count, SCALE_REMOTE_CHANGES);
        // Scale materializes every memo/remote for parse/store baselines.
        let scale_memo_files = scale
            .files
            .iter()
            .filter(|entry| entry.relative_path.starts_with("memo/"))
            .count();
        let scale_remote_files = scale
            .files
            .iter()
            .filter(|entry| entry.relative_path.starts_with("remote/"))
            .count();
        assert_eq!(
            scale_memo_files,
            usize::try_from(SCALE_MEMO_COUNT).expect("memo count fits usize")
        );
        assert_eq!(
            scale_remote_files,
            usize::try_from(SCALE_REMOTE_CHANGES).expect("remote count fits usize")
        );
        assert_eq!(capacity.workload.memo_count, CAPACITY_MEMO_COUNT);
        assert_eq!(
            capacity.workload.remote_change_count,
            CAPACITY_REMOTE_CHANGES
        );
        assert_eq!(
            capacity.workload.attachment_logical_bytes,
            CAPACITY_ATTACHMENT_LOGICAL_BYTES
        );
        let capacity_attachment = capacity
            .logical_attachments
            .iter()
            .find(|entry| entry.identity == "attachment:capacity-stream")
            .expect("capacity attachment");
        assert_eq!(
            capacity_attachment.logical_bytes,
            CAPACITY_ATTACHMENT_LOGICAL_BYTES
        );
        assert_eq!(
            capacity_attachment.sha256,
            stream_digest(11, CAPACITY_ATTACHMENT_LOGICAL_BYTES)
        );
        let total_written: u64 = walk_size(&capacity_dir);
        assert!(
            total_written < 50 * 1024 * 1024,
            "capacity corpus wrote too many bytes: {total_written}"
        );
        cleanup(scale_dir);
        cleanup(capacity_dir);
    }

    #[test]
    fn device_mode_generates_workspace_shaped_corpus() {
        // Device corpora are pushed into a real workspace (`/sdcard/Documents/<ws>` or an
        // app-private Direct root), so they must look like a user vault: dated `yyyy_MM_dd.md`
        // documents at the root plus material `media/` payloads — never fixture/memo/remote/git
        // buckets or logical-only attachments, which the engine would reject or skip.
        let dir = temp_dir("device");
        let manifest =
            generate_corpus(&request(CorpusMode::Device, 13, dir.clone())).expect("device corpus");
        assert_eq!(
            CorpusMode::parse("device").expect("device token parses"),
            CorpusMode::Device
        );
        assert_eq!(manifest.workload.memo_count, DEVICE_MEMO_COUNT);
        assert_eq!(manifest.workload.remote_change_count, 0);
        assert_eq!(manifest.workload.attachment_logical_bytes, 0);
        assert!(
            manifest.logical_attachments.is_empty(),
            "device corpora materialize every byte; logical attachments are a host-tier shortcut"
        );
        let dated_docs = manifest
            .files
            .iter()
            .filter(|entry| {
                let name = entry.relative_path.as_str();
                !name.contains('/')
                    && name.strip_suffix(".md").is_some_and(|stem| {
                        stem.len() == 10 && stem.chars().all(|c| c.is_ascii_digit() || c == '_')
                    })
            })
            .count();
        assert_eq!(dated_docs as u64, DEVICE_DOC_COUNT);
        assert_eq!(
            manifest
                .files
                .iter()
                .filter(|entry| entry.relative_path.starts_with("media/img/"))
                .count() as u64,
            DEVICE_MEDIA_COUNT
        );
        // `media/bulk/` is the ≥500-file enumeration workload the device `listchildren`
        // probe walks — it must be a real directory of materialized files, not a manifest
        // count pretending to be directory depth.
        let bulk_entries: Vec<_> = manifest
            .files
            .iter()
            .filter(|entry| {
                entry
                    .relative_path
                    .starts_with(&format!("{DEVICE_BULK_DIR}/"))
            })
            .collect();
        assert_eq!(
            bulk_entries.len() as u64,
            DEVICE_BULK_COUNT,
            "bulk directory must carry the full {DEVICE_BULK_COUNT}-file workload"
        );
        assert!(
            bulk_entries
                .iter()
                .all(|entry| dir.join(&entry.relative_path).is_file()),
            "every bulk manifest entry must materialize on disk"
        );
        assert_eq!(
            bulk_entries.len(),
            bulk_entries
                .iter()
                .map(|entry| &entry.relative_path)
                .collect::<std::collections::BTreeSet<_>>()
                .len(),
            "bulk entries must be uniquely named"
        );
        for banned_prefix in ["fixtures/", "memo/", "remote/", "git/"] {
            assert!(
                manifest
                    .files
                    .iter()
                    .all(|entry| !entry.relative_path.starts_with(banned_prefix)),
                "device corpus must not contain host-tier bucket {banned_prefix}"
            );
        }
        // Media payloads must be decodable images, not noise with marker bookends: the
        // on-device gallery/reel/image-viewer surfaces only exercise the real pipeline
        // when the platform decoder accepts the bytes (they render an error tile
        // otherwise). The generator embeds a complete baseline JPEG prefix and pads the
        // tail deterministically, so the file starts with SOI+JFIF and ends inside the
        // padding — the EOI lives mid-file and decoders ignore trailing bytes.
        let first_media = manifest
            .files
            .iter()
            .find(|entry| entry.relative_path == "media/img/att-0000.jpg")
            .expect("media file media/img/att-0000.jpg must exist");
        let media_bytes = fs::read(dir.join(&first_media.relative_path)).expect("media bytes");
        assert_eq!(
            media_bytes.len() as u64,
            first_media.byte_length,
            "media entry length must equal the manifest byte_length"
        );
        assert!(
            media_bytes.starts_with(DEVICE_MEDIA_JPEG_PREFIX),
            "media payload must begin with the embedded decodable JPEG stream"
        );
        cleanup(dir);
    }

    #[test]
    fn device_corpus_has_real_task_coverage_after_production_parsing() {
        let dir = temp_dir("device-tasks");
        let manifest =
            generate_corpus(&request(CorpusMode::Device, 13, dir.clone())).expect("device corpus");
        let mut task_memos = 0;
        let mut task_facts = 0;
        let mut pending_tasks = 0;
        let mut completed_tasks = 0;
        let mut memo_count = 0;
        for entry in &manifest.files {
            let path = std::path::Path::new(&entry.relative_path);
            if path.extension().is_none_or(|extension| extension != "md") {
                continue;
            }
            let body = fs::read_to_string(dir.join(path)).expect("document bytes");
            let source = SourceBytes::try_from_str(&body).expect("bounded source");
            let stem = path
                .file_stem()
                .and_then(|stem| stem.to_str())
                .expect("stem");
            let document = parse_workspace_document(&source, stem).expect("production parse");
            assert_eq!(document.memos().len() as u64, DEVICE_MEMOS_PER_DOC);
            memo_count += document.memos().len();
            task_memos += document
                .memos()
                .iter()
                .filter(|memo| memo.has_todo())
                .count();
            task_facts += document
                .render_document()
                .semantic_facts()
                .iter()
                .filter(|fact| fact.kind() == SemanticFactKind::TaskItem)
                .count();
            for fact in document
                .render_document()
                .semantic_facts()
                .iter()
                .filter(|fact| fact.kind() == SemanticFactKind::TaskItem)
            {
                match fact.value() {
                    "[ ]" => pending_tasks += 1,
                    "[x]" => completed_tasks += 1,
                    value => panic!("unexpected production task marker: {value}"),
                }
            }
        }
        assert_eq!(memo_count as u64, DEVICE_MEMO_COUNT);
        assert_eq!(
            task_memos, 40,
            "device task measurements require populated memo bodies"
        );
        assert_eq!(
            task_facts, 80,
            "each task memo covers pending and completed work"
        );
        assert_eq!(pending_tasks, 40);
        assert_eq!(completed_tasks, 40);
        cleanup(dir);
    }

    #[test]
    fn device_mode_is_byte_stable_across_runs() {
        let first_dir = temp_dir("device-a");
        let second_dir = temp_dir("device-b");
        let first = generate_corpus(&request(CorpusMode::Device, 29, first_dir.clone()))
            .expect("first device corpus");
        let second = generate_corpus(&request(CorpusMode::Device, 29, second_dir.clone()))
            .expect("second device corpus");
        assert_eq!(
            first.to_canonical_json().expect("json"),
            second.to_canonical_json().expect("json")
        );
        assert_eq!(
            first.canonical_digest().expect("digest"),
            second.canonical_digest().expect("digest")
        );
        cleanup(first_dir);
        cleanup(second_dir);
    }

    #[test]
    fn path_escape_and_absolute_paths_are_rejected() {
        assert_eq!(
            validate_relative_path("../secret"),
            Err(GenerateError::PathEscapesRoot {
                path: "../secret".to_owned(),
            })
        );
        assert_eq!(
            validate_relative_path("/tmp/x"),
            Err(GenerateError::AbsolutePath {
                path: "/tmp/x".to_owned(),
            })
        );
        validate_relative_path("memo/000001.md").expect("relative path accepted");
    }

    #[test]
    fn generated_identities_and_paths_are_unique() {
        let dir = temp_dir("dup");
        let manifest = generate_corpus(&request(CorpusMode::Quick, 1, dir.clone())).expect("ok");
        let mut seen = std::collections::BTreeSet::new();
        for file in &manifest.files {
            assert!(
                seen.insert(file.relative_path.clone()),
                "duplicate path {}",
                file.relative_path
            );
        }
        let mut attachment_ids = std::collections::BTreeSet::new();
        for entry in &manifest.logical_attachments {
            assert!(
                attachment_ids.insert(entry.identity.clone()),
                "duplicate identity {}",
                entry.identity
            );
        }
        let err = GenerateError::DuplicateIdentity {
            identity: "memo:000000".to_owned(),
        };
        assert!(err.to_string().contains("duplicate identity"));
        cleanup(dir);
    }

    #[test]
    fn stream_digest_is_seed_stable() {
        assert_eq!(stream_digest(9, 1024), stream_digest(9, 1024));
        assert_ne!(stream_digest(9, 1024), stream_digest(10, 1024));
        assert_eq!(
            stream_digest(1, CAPACITY_ATTACHMENT_LOGICAL_BYTES),
            stream_digest(1, CAPACITY_ATTACHMENT_LOGICAL_BYTES)
        );
    }

    #[test]
    fn golden_markdown_fixture_set_is_complete() {
        let root = repository_root().join("fixtures/markdown");
        for required in [
            "lomo-basic.md",
            "thino-basic.md",
            "plain.md",
            "empty.md",
            "bom-newline.md",
            "long-line.md",
            "duplicate-timestamps.md",
            "dst-edge.md",
            "cjk-emoji.md",
            "gfm-extensions.md",
            "invalid-utf8.bin",
        ] {
            assert!(
                root.join(required).is_file(),
                "missing markdown golden fixture: {required}"
            );
        }
        assert!(
            repository_root()
                .join("fixtures/remote/s3-layout.json")
                .is_file()
        );
        assert!(
            repository_root()
                .join("fixtures/remote/webdav-layout.json")
                .is_file()
        );
        assert!(
            repository_root()
                .join("fixtures/remote/rclone-crypt-vectors.json")
                .is_file()
        );
    }

    fn walk_size(root: &std::path::Path) -> u64 {
        let mut total = 0;
        let mut pending = vec![root.to_path_buf()];
        while let Some(dir) = pending.pop() {
            for entry in fs::read_dir(dir).expect("read") {
                let path = entry.expect("entry").path();
                if path.is_dir() {
                    pending.push(path);
                } else {
                    total += fs::metadata(&path).expect("meta").len();
                }
            }
        }
        total
    }
}
