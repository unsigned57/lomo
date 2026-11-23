//! Deterministic phase-0 corpus generation.

use std::collections::BTreeSet;
use std::fs;
use std::path::{Component, Path, PathBuf};

use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::corpus::{
    CorpusFileEntryV1, CorpusManifestV1, CorpusWorkloadV1, LogicalAttachmentEntryV1,
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
/// Device-injection tier memo count.
///
/// A real user-shaped workspace (`yyyy_MM_dd.md` dated documents at the workspace root plus
/// material `media/` payloads). The size bound is the SAF reconcile a fresh grant must
/// survive, not `adb push` bytes: activation writes durable `.lomo` records per memo
/// (history head + object + a share of the doc identity record), each one a single
/// executor action through the SAF provider. Measured on emulator-5554 (API 36): ~0.35
/// memos/s, so the original 6k sizing reconciles for ~5 hours -- past any instrumented
/// wait. 240 memos over 24 dated documents keeps every journey anchor populated
/// (scroll depth, drawer tags, search hits, dated structure) while a fresh activation
/// stays inside ~12 minutes; a repeated push reuses retained `.lomo` state and
/// reconciles read-bound instead.
pub const DEVICE_MEMO_COUNT: u64 = 240;
pub const DEVICE_MEMOS_PER_DOC: u64 = 10;
pub const DEVICE_DOC_COUNT: u64 = DEVICE_MEMO_COUNT / DEVICE_MEMOS_PER_DOC;
pub const DEVICE_MEDIA_COUNT: u64 = 96;
pub const DEVICE_MEDIA_BYTES: u64 = 96 * 1024;

/// ≥500-file enumeration workload (audit open item #3 / plan §3-D).
///
/// A real `images/bulk/` directory the `listchildren` and `media_classify` probes walk through
/// the production `PlatformAction::ListChildren` lane. No memo references these payloads, and the
/// media orphan sweep collects unreferenced files under `media/`, so the workload lives outside
/// that directory to survive until the probes run. Small deterministic bodies keep `adb push`
/// cost negligible while the directory size carries the measurement.
pub const DEVICE_BULK_COUNT: u64 = 500;
pub const DEVICE_BULK_BYTES: u64 = 256;
/// Relative directory the `listchildren` and `media_classify` probes target — pinned here so the
/// generator, the receiver extras, and the contract test can never disagree on the workload path.
pub const DEVICE_BULK_DIR: &str = "images/bulk";

/// Fixed-seed generation modes from the stage-0 plan.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CorpusMode {
    /// Small contract corpus for CI.
    Quick,
    /// Logical 100k memos + 10k remote changes; sparse on-disk materialization only.
    Scale,
    /// 20 GiB logical attachment capacity without materializing full bytes.
    Capacity,
    /// Workspace-shaped corpus for device injection: dated documents at the workspace root
    /// plus material media payloads; every byte is a real file so SAF/Direct indexing sees
    /// production I/O.
    Device,
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
            "device" => Ok(Self::Device),
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
            Self::Device => "device",
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
            // Device corpora materialize every byte as a workspace file; `logical` attachment
            // weight is a host-tier shortcut that would hide real SAF/Direct I/O.
            Self::Device => CorpusWorkloadV1 {
                memo_count: DEVICE_MEMO_COUNT,
                remote_change_count: 0,
                attachment_logical_bytes: 0,
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

    if request.mode == CorpusMode::Device {
        // The device tier is injected into a real workspace, so it must be a user-shaped
        // vault — dated `yyyy_MM_dd` documents at the root plus `media/` payloads — not the
        // host-tier fixture/memo/remote/git buckets.
        append_device_documents(request, &mut files, &mut identities)?;
        append_device_media(request, &mut files, &mut identities)?;
        append_device_bulk(request, &mut files, &mut identities)?;
    } else {
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
    }

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
    if request.mode == CorpusMode::Device {
        // A pushed device workspace must not carry empty host-tier buckets.
        return Ok(());
    }
    for name in ["memo", "remote", "media", "git"] {
        fs::create_dir_all(request.output_dir.join(name)).map_err(io_error)?;
    }
    Ok(())
}

/// On-disk materialization policy.
/// - Quick: contract-size material files.
/// - Scale: full memo/remote materialization for parse/store benchmarks (gitignored output).
/// - Capacity: sparse material files; bulk attachment weight stays logical/stream-digested.
/// - Device: materialized through the workspace-shaped writers, never this bucket path.
const fn material_memo_count(mode: CorpusMode) -> u64 {
    match mode {
        CorpusMode::Quick => QUICK_MEMO_COUNT,
        CorpusMode::Scale => SCALE_MEMO_COUNT,
        CorpusMode::Capacity => 32,
        CorpusMode::Device => 0,
    }
}

const fn material_remote_count(mode: CorpusMode) -> u64 {
    match mode {
        CorpusMode::Quick => QUICK_REMOTE_CHANGES,
        CorpusMode::Scale => SCALE_REMOTE_CHANGES,
        CorpusMode::Capacity => 16,
        CorpusMode::Device => 0,
    }
}

/// Dated `yyyy_MM_dd.md` documents at the workspace root — the production file shape the
/// storage-filename default (`StorageFilenameFormats.DEFAULT_PATTERN`) indexes.
fn append_device_documents(
    request: &GenerateRequest,
    files: &mut Vec<CorpusFileEntryV1>,
    identities: &mut BTreeSet<String>,
) -> Result<(), GenerateError> {
    let mut date = (2020_u32, 1_u32, 1_u32);
    for doc_index in 0..DEVICE_DOC_COUNT {
        let (year, month, day) = date;
        let relative = format!("{year:04}_{month:02}_{day:02}.md");
        let identity = format!("device:doc:{doc_index:04}");
        claim_identity(identities, &identity)?;
        let bytes = device_document_body(request.seed, doc_index)?.into_bytes();
        write_relative(&request.output_dir, &relative, &bytes)?;
        files.push(CorpusFileEntryV1 {
            relative_path: relative,
            sha256: lomo_core::hex_encode(&Sha256::digest(&bytes)),
            byte_length: bytes.len() as u64,
        });
        date = next_day(date);
    }
    Ok(())
}

/// A complete, decodable baseline JPEG (96x64, ~440 B).
///
/// The corpus media must open in a real image pipeline: memo cards, gallery cells and the
/// reel only surface an image when the platform decoder accepts the bytes, so SOI/EOI
/// bookends around noise never exercise the production image path. Deterministic byte
/// literal; the deterministic tail pads each file to [`DEVICE_MEDIA_BYTES`] so the
/// attachment weight stays material. libjpeg-class decoders (Skia/BitmapFactory/Coil
/// on-device) stop at EOI and ignore trailing bytes.
pub const DEVICE_MEDIA_JPEG_PREFIX: &[u8] = &[
    0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
    0x00, 0x01, 0x00, 0x00, 0xff, 0xdb, 0x00, 0x43, 0x00, 0x06, 0x04, 0x04, 0x05, 0x04, 0x04, 0x06,
    0x05, 0x05, 0x05, 0x06, 0x06, 0x06, 0x07, 0x09, 0x0e, 0x09, 0x09, 0x08, 0x08, 0x09, 0x12, 0x0d,
    0x0d, 0x0a, 0x0e, 0x15, 0x12, 0x16, 0x16, 0x15, 0x12, 0x14, 0x14, 0x17, 0x1a, 0x21, 0x1c, 0x17,
    0x18, 0x1f, 0x19, 0x14, 0x14, 0x1d, 0x27, 0x1d, 0x1f, 0x22, 0x23, 0x25, 0x25, 0x25, 0x16, 0x1c,
    0x29, 0x2c, 0x28, 0x24, 0x2b, 0x21, 0x24, 0x25, 0x24, 0xff, 0xdb, 0x00, 0x43, 0x01, 0x06, 0x06,
    0x06, 0x09, 0x08, 0x09, 0x11, 0x09, 0x09, 0x11, 0x24, 0x18, 0x14, 0x18, 0x24, 0x24, 0x24, 0x24,
    0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24,
    0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24,
    0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0xff, 0xc0,
    0x00, 0x11, 0x08, 0x00, 0x40, 0x00, 0x60, 0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11,
    0x01, 0xff, 0xc4, 0x00, 0x16, 0x00, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x06, 0xff, 0xc4, 0x00, 0x15, 0x10, 0x01, 0x01,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x12,
    0xff, 0xc4, 0x00, 0x17, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x06, 0x05, 0x07, 0xff, 0xc4, 0x00, 0x15, 0x11, 0x01, 0x01,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x14,
    0xff, 0xda, 0x00, 0x0c, 0x03, 0x01, 0x00, 0x02, 0x11, 0x03, 0x11, 0x00, 0x3f, 0x00, 0xcf, 0x59,
    0x68, 0xd9, 0x6c, 0x49, 0x5d, 0x4a, 0xa5, 0xac, 0xb4, 0x6c, 0xb2, 0x52, 0xa5, 0xac, 0xb4, 0x6c,
    0xb2, 0x52, 0xa5, 0xac, 0xb4, 0x6c, 0xb2, 0x52, 0xa5, 0xac, 0xb4, 0x6c, 0xb2, 0x52, 0xa5, 0xac,
    0xb4, 0x6c, 0xb2, 0x52, 0xa4, 0x6c, 0xb4, 0x2c, 0xb5, 0x1c, 0xa9, 0xda, 0x97, 0xb2, 0xd0, 0xb2,
    0xc9, 0x4a, 0x97, 0xb2, 0xd0, 0xb2, 0xc9, 0x4a, 0x97, 0xb2, 0xd0, 0xb2, 0xc9, 0x4a, 0x97, 0xb2,
    0xd0, 0xb2, 0xc9, 0x4a, 0x97, 0xb2, 0xd0, 0xb2, 0xc9, 0x4a, 0x91, 0xb2, 0xd1, 0xb2, 0xd4, 0x52,
    0xa7, 0x6a, 0x5a, 0xcb, 0x46, 0xcb, 0x25, 0x2a, 0x5a, 0xcb, 0x46, 0xcb, 0x25, 0x2a, 0x5a, 0xcb,
    0x46, 0xcb, 0x25, 0x2a, 0x5a, 0xcb, 0x46, 0xcb, 0x25, 0x2a, 0x5a, 0xcb, 0x46, 0xcb, 0x25, 0x2a,
    0x46, 0xcb, 0x46, 0xcb, 0x51, 0xca, 0x9e, 0xa9, 0x6b, 0x2d, 0x1b, 0x2c, 0x94, 0xa9, 0x6b, 0x2d,
    0x1b, 0x2c, 0x94, 0xa9, 0x6b, 0x2d, 0x1b, 0x2c, 0x94, 0xa9, 0x6b, 0x2d, 0x1b, 0x2c, 0x94, 0xa9,
    0x6b, 0x2d, 0x1b, 0x2c, 0x94, 0xa9, 0xff, 0xd9,
];

/// Material media payloads under `media/img/` — real decodable images at the SAF/Direct
/// attachment weight the device tier is sized around (SAF `adb push` stays feasible at
/// ~9 MiB). Each file is [`DEVICE_MEDIA_JPEG_PREFIX`] followed by deterministic padding so
/// on-device surfaces measure the true decode path instead of an error placeholder.
fn append_device_media(
    request: &GenerateRequest,
    files: &mut Vec<CorpusFileEntryV1>,
    identities: &mut BTreeSet<String>,
) -> Result<(), GenerateError> {
    for index in 0..DEVICE_MEDIA_COUNT {
        let relative = format!("media/img/att-{index:04}.jpg");
        let identity = format!("device:media:{index:04}");
        claim_identity(identities, &identity)?;
        let length = usize::try_from(DEVICE_MEDIA_BYTES).map_err(|error| GenerateError::Io {
            detail: format!("device media length {DEVICE_MEDIA_BYTES} overflows usize: {error}"),
        })?;
        let mut bytes = deterministic_bytes(request.seed ^ 0x4A50_4547 ^ index, length);
        for (slot, prefix_byte) in bytes.iter_mut().zip(DEVICE_MEDIA_JPEG_PREFIX.iter()) {
            *slot = *prefix_byte;
        }
        write_relative(&request.output_dir, &relative, &bytes)?;
        files.push(CorpusFileEntryV1 {
            relative_path: relative,
            sha256: lomo_core::hex_encode(&Sha256::digest(&bytes)),
            byte_length: bytes.len() as u64,
        });
    }
    Ok(())
}

/// `images/bulk/` — the ≥500-file enumeration and classification workload. Names rotate through
/// image, audio, video and unclassified suffixes, one of them with a multi-byte stem, so the
/// classifier sees every media class; none is Markdown, which keeps them out of document walks.
fn append_device_bulk(
    request: &GenerateRequest,
    files: &mut Vec<CorpusFileEntryV1>,
    identities: &mut BTreeSet<String>,
) -> Result<(), GenerateError> {
    for index in 0..DEVICE_BULK_COUNT {
        let relative = match index % 6 {
            0 => format!("{DEVICE_BULK_DIR}/img-{index:04}.jpg"),
            1 => format!("{DEVICE_BULK_DIR}/pic-{index:04}.png"),
            2 => format!("{DEVICE_BULK_DIR}/媒体-{index:04}.webp"),
            3 => format!("{DEVICE_BULK_DIR}/voice-{index:04}.m4a"),
            4 => format!("{DEVICE_BULK_DIR}/clip-{index:04}.mp4"),
            _ => format!("{DEVICE_BULK_DIR}/blob-{index:04}.bin"),
        };
        let identity = format!("device:bulk:{index:04}");
        claim_identity(identities, &identity)?;
        let length = usize::try_from(DEVICE_BULK_BYTES).map_err(|error| GenerateError::Io {
            detail: format!("device bulk length {DEVICE_BULK_BYTES} overflows usize: {error}"),
        })?;
        let bytes = deterministic_bytes(request.seed ^ 0xB1_2C ^ index, length);
        write_relative(&request.output_dir, &relative, &bytes)?;
        files.push(CorpusFileEntryV1 {
            relative_path: relative,
            sha256: lomo_core::hex_encode(&Sha256::digest(&bytes)),
            byte_length: bytes.len() as u64,
        });
    }
    Ok(())
}

fn device_document_body(seed: u64, doc_index: u64) -> Result<String, GenerateError> {
    use std::fmt::Write as _;

    let mut rng = SeedRng::new(seed ^ 0xD00C ^ doc_index);
    let mut body = String::new();
    for memo_index in 0..DEVICE_MEMOS_PER_DOC {
        let memo_ordinal = doc_index * DEVICE_MEMOS_PER_DOC + memo_index;
        let tag = match memo_ordinal % 6 {
            0 => "#bench",
            1 => "#work/dev",
            2 => "#生活/记录",
            _ => "#notes",
        };
        let attachment = if memo_ordinal.is_multiple_of(17) {
            format!(
                "\n![shot](media/img/att-{:04}.jpg)",
                memo_ordinal % DEVICE_MEDIA_COUNT
            )
        } else if memo_ordinal % 13 == 3 {
            format!("\n![ext](https://example.com/assets/photo-{memo_ordinal}.png)")
        } else {
            String::new()
        };
        let cjk = if memo_ordinal % 5 == 1 {
            " 中文记录测试：多字节字符与预览截断边界。"
        } else {
            ""
        };
        let emoji = if memo_ordinal % 7 == 2 {
            " 🙂🚀✨"
        } else {
            ""
        };
        let long_tail = if memo_ordinal.is_multiple_of(11) {
            "\nExtended markdown detail paragraph for realistic preview truncation and multi-line rendering coverage across ASCII, 中文示例段落, and `inline_code` spans without adding extra list markers; second sentence validates stable byte length and render layout."
        } else {
            ""
        };
        let noise = rng.next_u64();
        // `- HH:mm:ss` is the production storage timestamp default
        // (`StorageTimestampFormats.DEFAULT_PATTERN`).
        let hour = 6 + memo_index;
        let minute = (memo_ordinal * 7 + doc_index) % 60;
        let second = (memo_ordinal * 13 + doc_index * 3) % 60;
        body.write_fmt(format_args!(
            "- {hour:02}:{minute:02}:{second:02}\nseed={seed} doc={doc_index} memo={memo_index} noise={noise} {tag}{attachment}\nbody line for device memo {memo_ordinal}{cjk}{emoji}{long_tail}\n"
        ))
        .map_err(io_error)?;
        if memo_ordinal.is_multiple_of(6) {
            body.write_fmt(format_args!(
                "\n- [ ] pending device task {memo_ordinal}\n- [x] completed device task {memo_ordinal}\n"
            ))
            .map_err(io_error)?;
        }
    }
    Ok(body)
}

/// Calendar stepper for the dated document sequence — six hundred consecutive days starting
/// 2020-01-01 cover roughly twenty months of vault history. Pure `u32` arithmetic keeps the
/// corpus deterministic without civil-date casts.
const fn next_day(date: (u32, u32, u32)) -> (u32, u32, u32) {
    let (year, month, day) = date;
    if day < days_in_month(year, month) {
        (year, month, day + 1)
    } else if month < 12 {
        (year, month + 1, 1)
    } else {
        (year + 1, 1, 1)
    }
}

const fn days_in_month(year: u32, month: u32) -> u32 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        _ => {
            if year.is_multiple_of(4) && (!year.is_multiple_of(100) || year.is_multiple_of(400)) {
                29
            } else {
                28
            }
        }
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
            sha256: lomo_core::hex_encode(&Sha256::digest(bytes)),
            byte_length: bytes.len() as u64,
        });
    } else {
        logical_attachments.push(LogicalAttachmentEntryV1 {
            identity,
            logical_bytes: bytes.len() as u64,
            sha256: lomo_core::hex_encode(&Sha256::digest(bytes)),
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
        sha256: lomo_core::hex_encode(&Sha256::digest(&bytes)),
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
        sha256: lomo_core::hex_encode(&Sha256::digest(&bytes)),
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
                sha256: lomo_core::hex_encode(&Sha256::digest(&bytes)),
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
            if let (Some(dst), Some(src)) = (block.get_mut(offset..end), value.get(..copy)) {
                dst.copy_from_slice(src);
            }
        }
        hasher.update(block.get(..chunk).unwrap_or(&[]));
        remaining -= chunk as u64;
    }
    lomo_core::hex_encode(&hasher.finalize())
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
    lomo_core::hex_encode(&hasher.finalize())
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
