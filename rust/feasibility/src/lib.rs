//! Phase-0 feasibility evidence types and validation.
//!
//! This crate is tooling-only. Production crates must never depend on it.

mod corpus;
mod exit_code;
mod generate;
mod git_probe;
mod http_probe;
mod markdown_probe;
mod redaction;
mod report;
mod sqlite_probe;

pub use corpus::{
    CorpusFileEntryV1, CorpusManifestV1, CorpusWorkloadV1, LogicalAttachmentEntryV1, hex_digest,
};
pub use exit_code::FeasibilityExitCode;
pub use generate::{
    CAPACITY_ATTACHMENT_LOGICAL_BYTES, CAPACITY_MEMO_COUNT, CAPACITY_REMOTE_CHANGES,
    CORPUS_VERSION, CorpusMode, GenerateError, GenerateRequest, QUICK_ATTACHMENT_LOGICAL_BYTES,
    QUICK_MEMO_COUNT, QUICK_REMOTE_CHANGES, SCALE_ATTACHMENT_LOGICAL_BYTES, SCALE_MEMO_COUNT,
    SCALE_REMOTE_CHANGES, SeedRng, generate_corpus, stream_digest, validate_relative_path,
};
pub use git_probe::{GitProbeError, GitProbeRemoteFlags, GitProbeReport, run_local_git_probe};
pub use http_probe::{
    HttpFixtureStats, HttpProbeError, HttpsFixture, fixture_client, probe_echo,
    probe_s3_conditional_put, probe_s3_list_pagination, probe_stream_timeout,
    reset_http_probe_state,
};
pub use markdown_probe::{
    MarkdownProbeError, MarkdownProbeReport, bytes_stable_after_parse, probe_markdown_file,
    probe_markdown_text,
};
pub use redaction::{RedactionError, redact_sensitive_text, relative_path_for_report};
pub use report::{
    BaselineConclusion, BaselineMetricV1, BaselineReportV1, BaselineSizesV1, DeviceFingerprintV1,
    ReportValidationError, ToolchainFingerprintV1,
};
pub use sqlite_probe::{SqliteProbeError, SqliteProbeReport, run_sqlite_probe};
