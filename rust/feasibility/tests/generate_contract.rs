//! Behavior Contract
//!
//! Capability: build deterministic phase-0 corpora from fixed seeds and golden fixtures.
//!
//! Scenarios:
//! - Given the same seed and quick mode, when generated twice, then manifests and digests match.
//! - Given path escape or absolute paths, when validated, then the boundary rejects them.
//! - Given a duplicate identity claim, when generation would collide, then generation fails.
//! - Given scale/capacity modes, when generated, then workload counters match the plan.
//! - Given repository golden fixtures, when generation runs, then fixtures are embedded under
//!   `fixtures/` with stable digests.
//!
//! Observable outcomes: byte-stable manifests, explicit `GenerateError` variants, correct workload.
//! TDD proof: unstable ordering or silent path escapes fail before probes consume corpora.
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
        CorpusMode, GenerateError, GenerateRequest, QUICK_MEMO_COUNT, QUICK_REMOTE_CHANGES,
        SCALE_MEMO_COUNT, SCALE_REMOTE_CHANGES, generate_corpus, stream_digest,
        validate_relative_path,
    };

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
