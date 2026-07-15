//! Deterministic phase-0 corpus generation.

use std::collections::BTreeSet;
use std::fs;
use std::path::{Component, Path, PathBuf};

use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::corpus::{
    CorpusFileEntryV1, CorpusManifestV1, CorpusWorkloadV1, LogicalAttachmentEntryV1, hex_digest,
};
use crate::report::ReportValidationError;

pub const CORPUS_VERSION: &str = "format-v1";
pub const QUICK_MEMO_COUNT: u64 = 32;
pub const QUICK_REMOTE_CHANGES: u64 = 8;
pub const QUICK_ATTACHMENT_LOGICAL_BYTES: u64 = 64 * 1024;
pub const SCALE_MEMO_COUNT: u64 = 100_000;
pub const SCALE_REMOTE_CHANGES: u64 = 10_000;
pub const SCALE_ATTACHMENT_LOGICAL_BYTES: u64 = 256 * 1024 * 1024;
pub const CAPACITY_MEMO_COUNT: u64 = 1_000;
pub const CAPACITY_REMOTE_CHANGES: u64 = 1_000;
pub const CAPACITY_ATTACHMENT_LOGICAL_BYTES: u64 = 20 * 1024 * 1024 * 1024;

/// Fixed-seed generation modes from the stage-0 plan.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CorpusMode {
    /// Small contract corpus for CI.
    Quick,
    /// Logical 100k memos + 10k remote changes; sparse on-disk materialization only.
    Scale,
    /// 20 GiB logical attachment capacity without materializing full bytes.
    Capacity,
}

impl CorpusMode {
    /// Parse a CLI/mode token.
    ///
    /// # Errors
    ///
    /// Returns [`GenerateError::UnknownMode`] for unsupported tokens.
    pub fn parse(value: &str) -> Result<Self, GenerateError> {
        match value {
            "quick" => Ok(Self::Quick),
            "scale" => Ok(Self::Scale),
            "capacity" => Ok(Self::Capacity),
            other => Err(GenerateError::UnknownMode {
                mode: other.to_owned(),
            }),
        }
    }

    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Quick => "quick",
            Self::Scale => "scale",
            Self::Capacity => "capacity",
        }
    }

    #[must_use]
    pub const fn workload(self) -> CorpusWorkloadV1 {
        match self {
            Self::Quick => CorpusWorkloadV1 {
                memo_count: QUICK_MEMO_COUNT,
                remote_change_count: QUICK_REMOTE_CHANGES,
                attachment_logical_bytes: QUICK_ATTACHMENT_LOGICAL_BYTES,
            },
            Self::Scale => CorpusWorkloadV1 {
                memo_count: SCALE_MEMO_COUNT,
                remote_change_count: SCALE_REMOTE_CHANGES,
                attachment_logical_bytes: SCALE_ATTACHMENT_LOGICAL_BYTES,
            },
            Self::Capacity => CorpusWorkloadV1 {
                memo_count: CAPACITY_MEMO_COUNT,
                remote_change_count: CAPACITY_REMOTE_CHANGES,
                attachment_logical_bytes: CAPACITY_ATTACHMENT_LOGICAL_BYTES,
            },
        }
    }
}

/// Corpus generation failures.
#[derive(Clone, Debug, Eq, Error, PartialEq)]
pub enum GenerateError {
    #[error("unknown corpus mode `{mode}`")]
    UnknownMode { mode: String },
    #[error("path escapes corpus root: {path}")]
    PathEscapesRoot { path: String },
    #[error("absolute path is forbidden: {path}")]
    AbsolutePath { path: String },
    #[error("duplicate identity `{identity}`")]
    DuplicateIdentity { identity: String },
    #[error("fixture root is missing: {path}")]
    MissingFixtureRoot { path: String },
    #[error("I/O failure: {detail}")]
    Io { detail: String },
    #[error(transparent)]
    Report(#[from] ReportValidationError),
}

/// Options for one corpus generation run.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GenerateRequest {
    pub seed: u64,
    pub mode: CorpusMode,
    pub output_dir: PathBuf,
    pub fixture_root: PathBuf,
}

/// Generate a deterministic corpus and write the canonical manifest.
///
/// # Errors
///
/// Returns [`GenerateError`] on path/identity violations or I/O failures.
pub fn generate_corpus(request: &GenerateRequest) -> Result<CorpusManifestV1, GenerateError> {
    prepare_output_dirs(request)?;
    let workload = request.mode.workload();
    let mut identities = BTreeSet::new();
    let mut files = Vec::new();
    let mut logical_attachments = Vec::new();

    append_golden_fixtures(
        &request.fixture_root,
        &request.output_dir,
        &mut files,
        &mut identities,
    )?;
    append_synthetic_memos(
        request,
        workload.memo_count,
        &mut files,
        &mut logical_attachments,
        &mut identities,
    )?;
    append_synthetic_remotes(
        request,
        workload.remote_change_count,
        &mut files,
        &mut logical_attachments,
        &mut identities,
    )?;
    append_capacity_attachment(
        request,
        workload.attachment_logical_bytes,
        &mut logical_attachments,
        &mut identities,
    )?;
    append_quick_media(request, &mut files, &mut identities)?;
    append_git_scenarios(request, &mut files, &mut identities)?;

    let manifest = CorpusManifestV1 {
        schema_version: CorpusManifestV1::SCHEMA_VERSION,
        corpus_version: format!("{}-{}", CORPUS_VERSION, request.mode.as_str()),
        seed: request.seed,
        workload,
        files,
        logical_attachments,
    };
    let json = manifest.to_canonical_json()?;
    fs::write(request.output_dir.join("corpus-manifest.v1.json"), &json).map_err(io_error)?;
    Ok(manifest)
}

fn prepare_output_dirs(request: &GenerateRequest) -> Result<(), GenerateError> {
    validate_output_root(&request.output_dir)?;
    if !request.fixture_root.is_dir() {
        return Err(GenerateError::MissingFixtureRoot {
            path: request.fixture_root.display().to_string(),
        });
    }
    fs::create_dir_all(&request.output_dir).map_err(io_error)?;
    for name in ["memo", "remote", "media", "git"] {
        fs::create_dir_all(request.output_dir.join(name)).map_err(io_error)?;
    }
    Ok(())
}

/// On-disk materialization policy.
/// - Quick: contract-size material files.
/// - Scale: full memo/remote materialization for parse/store benchmarks (gitignored output).
/// - Capacity: sparse material files; bulk attachment weight stays logical/stream-digested.
const fn material_memo_count(mode: CorpusMode) -> u64 {
    match mode {
        CorpusMode::Quick => QUICK_MEMO_COUNT,
        CorpusMode::Scale => SCALE_MEMO_COUNT,
        CorpusMode::Capacity => 32,
    }
}

const fn material_remote_count(mode: CorpusMode) -> u64 {
    match mode {
        CorpusMode::Quick => QUICK_REMOTE_CHANGES,
        CorpusMode::Scale => SCALE_REMOTE_CHANGES,
        CorpusMode::Capacity => 16,
    }
}

fn append_synthetic_memos(
    request: &GenerateRequest,
    memo_count: u64,
    files: &mut Vec<CorpusFileEntryV1>,
    logical_attachments: &mut Vec<LogicalAttachmentEntryV1>,
    identities: &mut BTreeSet<String>,
) -> Result<(), GenerateError> {
    let material_memos = material_memo_count(request.mode);
    let mut rng = SeedRng::new(request.seed);
    for index in 0..memo_count {
        let relative = format!("memo/{index:06}.md");
        let identity = format!("memo:{index:06}");
        claim_identity(identities, &identity)?;
        let bytes = synthetic_memo_body(request.seed, index, &mut rng).into_bytes();
        push_material_or_logical(
            request,
            index < material_memos,
            relative,
            identity,
            &bytes,
            files,
            logical_attachments,
        )?;
    }
    Ok(())
}

fn append_synthetic_remotes(
    request: &GenerateRequest,
    remote_count: u64,
    files: &mut Vec<CorpusFileEntryV1>,
    logical_attachments: &mut Vec<LogicalAttachmentEntryV1>,
    identities: &mut BTreeSet<String>,
) -> Result<(), GenerateError> {
    let material_remotes = material_remote_count(request.mode);
    for index in 0..remote_count {
        let relative = format!("remote/change-{index:06}.json");
        let identity = format!("remote:{index:06}");
        claim_identity(identities, &identity)?;
        let bytes = synthetic_remote_change(request.seed, index).into_bytes();
        push_material_or_logical(
            request,
            index < material_remotes,
            relative,
            identity,
            &bytes,
            files,
            logical_attachments,
        )?;
    }
    Ok(())
}

fn push_material_or_logical(
    request: &GenerateRequest,
    materialize: bool,
    relative: String,
    identity: String,
    bytes: &[u8],
    files: &mut Vec<CorpusFileEntryV1>,
    logical_attachments: &mut Vec<LogicalAttachmentEntryV1>,
) -> Result<(), GenerateError> {
    if materialize {
        write_relative(&request.output_dir, &relative, bytes)?;
        files.push(CorpusFileEntryV1 {
            relative_path: relative,
            sha256: hex_digest(bytes),
            byte_length: bytes.len() as u64,
        });
    } else {
        logical_attachments.push(LogicalAttachmentEntryV1 {
            identity,
            logical_bytes: bytes.len() as u64,
            sha256: hex_digest(bytes),
        });
    }
    Ok(())
}

fn append_capacity_attachment(
    request: &GenerateRequest,
    logical_bytes: u64,
    logical_attachments: &mut Vec<LogicalAttachmentEntryV1>,
    identities: &mut BTreeSet<String>,
) -> Result<(), GenerateError> {
    let attachment_identity = "attachment:capacity-stream".to_owned();
    claim_identity(identities, &attachment_identity)?;
    logical_attachments.push(LogicalAttachmentEntryV1 {
        identity: attachment_identity,
        logical_bytes,
        sha256: stream_digest(request.seed, logical_bytes),
    });
    Ok(())
}

fn append_quick_media(
    request: &GenerateRequest,
    files: &mut Vec<CorpusFileEntryV1>,
    identities: &mut BTreeSet<String>,
) -> Result<(), GenerateError> {
    if !matches!(request.mode, CorpusMode::Quick) {
        return Ok(());
    }
    let relative = "media/placeholder.bin".to_owned();
    claim_identity(identities, "media:placeholder")?;
    let bytes = deterministic_bytes(request.seed, 1024);
    write_relative(&request.output_dir, &relative, &bytes)?;
    files.push(CorpusFileEntryV1 {
        relative_path: relative,
        sha256: hex_digest(&bytes),
        byte_length: bytes.len() as u64,
    });
    Ok(())
}

fn append_git_scenarios(
    request: &GenerateRequest,
    files: &mut Vec<CorpusFileEntryV1>,
    identities: &mut BTreeSet<String>,
) -> Result<(), GenerateError> {
    let scenarios = request.fixture_root.join("git/scenarios.json");
    if !scenarios.is_file() {
        return Ok(());
    }
    let relative = "git/scenarios.json".to_owned();
    let bytes = fs::read(&scenarios).map_err(io_error)?;
    write_relative(&request.output_dir, &relative, &bytes)?;
    claim_identity(identities, "git:scenarios")?;
    files.push(CorpusFileEntryV1 {
        relative_path: relative,
        sha256: hex_digest(&bytes),
        byte_length: bytes.len() as u64,
    });
    Ok(())
}

fn append_golden_fixtures(
    fixture_root: &Path,
    output_dir: &Path,
    files: &mut Vec<CorpusFileEntryV1>,
    identities: &mut BTreeSet<String>,
) -> Result<(), GenerateError> {
    let mut pending = vec![fixture_root.to_path_buf()];
    while let Some(directory) = pending.pop() {
        for entry in fs::read_dir(&directory).map_err(io_error)? {
            let entry = entry.map_err(io_error)?;
            let path = entry.path();
            if path.is_dir() {
                pending.push(path);
                continue;
            }
            let relative = path
                .strip_prefix(fixture_root)
                .map_err(|_prefix| GenerateError::PathEscapesRoot {
                    path: path.display().to_string(),
                })?
                .to_string_lossy()
                .replace('\\', "/");
            if relative == "README.md" {
                continue;
            }
            validate_relative_path(&relative)?;
            let identity = format!("fixture:{relative}");
            claim_identity(identities, &identity)?;
            let bytes = fs::read(&path).map_err(io_error)?;
            let out_relative = format!("fixtures/{relative}");
            write_relative(output_dir, &out_relative, &bytes)?;
            files.push(CorpusFileEntryV1 {
                relative_path: out_relative,
                sha256: hex_digest(&bytes),
                byte_length: bytes.len() as u64,
            });
        }
    }
    Ok(())
}

/// Reject absolute paths and parent-directory segments at the corpus boundary.
///
/// # Errors
///
/// Returns [`GenerateError`] for absolute or escaping paths.
pub fn validate_relative_path(path: &str) -> Result<(), GenerateError> {
    if path.is_empty() {
        return Err(GenerateError::PathEscapesRoot {
            path: path.to_owned(),
        });
    }
    if path.starts_with('/') || path.chars().nth(1) == Some(':') {
        return Err(GenerateError::AbsolutePath {
            path: path.to_owned(),
        });
    }
    let parsed = Path::new(path);
    if parsed
        .components()
        .any(|component| matches!(component, Component::ParentDir | Component::RootDir))
    {
        return Err(GenerateError::PathEscapesRoot {
            path: path.to_owned(),
        });
    }
    Ok(())
}

fn claim_identity(identities: &mut BTreeSet<String>, identity: &str) -> Result<(), GenerateError> {
    if !identities.insert(identity.to_owned()) {
        return Err(GenerateError::DuplicateIdentity {
            identity: identity.to_owned(),
        });
    }
    Ok(())
}

fn write_relative(root: &Path, relative: &str, bytes: &[u8]) -> Result<(), GenerateError> {
    validate_relative_path(relative)?;
    let target = root.join(relative);
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent).map_err(io_error)?;
    }
    // Ensure the resolved path stays under root.
    let canonical_root = fs::canonicalize(root).map_err(io_error)?;
    if let Some(parent) = target.parent() {
        let canonical_parent = fs::canonicalize(parent).map_err(io_error)?;
        if !canonical_parent.starts_with(&canonical_root) {
            return Err(GenerateError::PathEscapesRoot {
                path: relative.to_owned(),
            });
        }
    }
    fs::write(&target, bytes).map_err(io_error)?;
    Ok(())
}

fn validate_output_root(path: &Path) -> Result<(), GenerateError> {
    if path.as_os_str().is_empty() {
        return Err(GenerateError::PathEscapesRoot {
            path: path.display().to_string(),
        });
    }
    Ok(())
}

fn synthetic_memo_body(seed: u64, index: u64, rng: &mut SeedRng) -> String {
    let tag = if index.is_multiple_of(7) {
        "#中文/标签"
    } else if index.is_multiple_of(5) {
        "#life/note"
    } else {
        "#plain"
    };
    let emoji = if index.is_multiple_of(11) {
        " 😀"
    } else {
        ""
    };
    let noise = rng.next_u64();
    format!(
        "- {hour:02}:{minute:02}:{second:02}\nseed={seed} index={index} noise={noise} {tag}{emoji}\nbody line for memo {index}\n",
        hour = (index % 24) as u32,
        minute = ((index * 7) % 60) as u32,
        second = ((index * 13) % 60) as u32,
    )
}

fn synthetic_remote_change(seed: u64, index: u64) -> String {
    format!(
        "{{\"seed\":{seed},\"index\":{index},\"path\":\"lomo/memo/{index:06}.md\",\"op\":\"upsert\"}}\n"
    )
}

fn deterministic_bytes(seed: u64, length: usize) -> Vec<u8> {
    let mut rng = SeedRng::new(seed ^ 0xA5A5_A5A5_A5A5_A5A5);
    let mut bytes = Vec::with_capacity(length);
    while bytes.len() < length {
        bytes.extend_from_slice(&rng.next_u64().to_le_bytes());
    }
    bytes.truncate(length);
    bytes
}

/// Digest for a deterministic logical attachment stream without allocating the full payload.
///
/// For small streams (`<= 4 MiB`) the digest is the full SHA-256 of the expanded bytes.
/// For larger capacity streams the digest is a domain-separated commitment to
/// `(seed, logical_bytes)` plus fixed checkpoints of the same PRNG stream, so 20 GiB
/// corpora stay CI-bounded while remaining seed-stable and independently re-derivable.
#[must_use]
pub fn stream_digest(seed: u64, logical_bytes: u64) -> String {
    const FULL_HASH_LIMIT: u64 = 4 * 1024 * 1024;
    if logical_bytes <= FULL_HASH_LIMIT {
        return stream_digest_full(seed, logical_bytes);
    }
    stream_digest_commitment(seed, logical_bytes)
}

fn stream_digest_full(seed: u64, logical_bytes: u64) -> String {
    let mut hasher = Sha256::new();
    let mut rng = SeedRng::new(seed ^ logical_bytes.rotate_left(13));
    let mut remaining = logical_bytes;
    let mut block = [0_u8; 8192];
    while remaining > 0 {
        let chunk = usize::try_from(remaining.min(8192)).unwrap_or(8192);
        for offset in (0..chunk).step_by(8) {
            let value = rng.next_u64().to_le_bytes();
            let end = (offset + 8).min(chunk);
            let copy = end - offset;
            block[offset..end].copy_from_slice(&value[..copy]);
        }
        hasher.update(&block[..chunk]);
        remaining -= chunk as u64;
    }
    hex_digest(&hasher.finalize())
}

fn stream_digest_commitment(seed: u64, logical_bytes: u64) -> String {
    let mut hasher = Sha256::new();
    hasher.update(b"lomo-logical-stream-v1");
    hasher.update(seed.to_le_bytes());
    hasher.update(logical_bytes.to_le_bytes());
    let mut rng = SeedRng::new(seed ^ logical_bytes.rotate_left(13));
    // 64 checkpoints bind the PRNG trajectory without hashing tens of gigabytes.
    for _ in 0..64 {
        hasher.update(rng.next_u64().to_le_bytes());
    }
    hex_digest(&hasher.finalize())
}

fn io_error(error: impl std::fmt::Display) -> GenerateError {
    GenerateError::Io {
        detail: error.to_string(),
    }
}

/// SplitMix64-based deterministic generator.
#[derive(Clone, Debug)]
pub struct SeedRng {
    state: u64,
}

impl SeedRng {
    #[must_use]
    pub const fn new(seed: u64) -> Self {
        Self {
            state: seed.wrapping_add(0x9E37_79B9_7F4A_7C15),
        }
    }

    #[must_use]
    pub const fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }
}
