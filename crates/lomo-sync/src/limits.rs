//! Resource limits for stage-5 durable sync surfaces (fail closed, never clamp).

/// Maximum path intents in one durable action page.
pub const MAX_ACTION_PAGE_ITEMS: usize = 512;

/// Maximum conflict records in one page.
pub const MAX_CONFLICT_PAGE_ITEMS: usize = 100;

/// Maximum UTF-8 bytes for a single sync path string.
pub const MAX_SYNC_PATH_BYTES: usize = 1_024;

/// Maximum durable session / baseline record body size (1 MiB).
pub const MAX_DURABLE_RECORD_BYTES: usize = 1_048_576;

/// Maximum conflict candidate artifact body (1 MiB host hermetic).
pub const MAX_CONFLICT_ARTIFACT_BYTES: usize = 1_048_576;

/// Baseline shard count (first byte of path digest).
pub const BASELINE_SHARD_COUNT: usize = 256;

/// Sync durable tree schema version.
pub const SYNC_DURABLE_SCHEMA: u32 = 1;

/// Maximum Multi-Status XML body accepted from a `WebDAV` server (2 MiB).
pub const MAX_WEBDAV_MULTISTATUS_BYTES: usize = 2 * 1_048_576;

/// Maximum single object body streamed through the `WebDAV` adapter (32 MiB host slice).
pub const MAX_WEBDAV_OBJECT_BYTES: usize = 32 * 1_048_576;

/// Maximum directory nesting depth for recursive Depth=1 traversal.
pub const MAX_WEBDAV_TRAVERSAL_DEPTH: usize = 64;

/// Maximum remote entries collected into one hermetic snapshot pass.
pub const MAX_WEBDAV_SNAPSHOT_ENTRIES: usize = MAX_ACTION_PAGE_ITEMS;

/// Maximum single object body streamed through the S3 adapter (32 MiB host slice).
pub const MAX_S3_OBJECT_BYTES: usize = 32 * 1_048_576;

/// Maximum keys collected into one hermetic S3 snapshot pass.
pub const MAX_S3_SNAPSHOT_ENTRIES: usize = MAX_ACTION_PAGE_ITEMS;

/// Maximum `ListObjectsV2` response body (2 MiB).
pub const MAX_S3_LIST_BODY_BYTES: usize = 2 * 1_048_576;

/// Maximum continuation pages for one hermetic `ListObjectsV2` pass.
pub const MAX_S3_LIST_PAGES: usize = 1_024;

/// Multipart part size default (8 MiB) for host hermetic resume slice.
pub const S3_MULTIPART_PART_BYTES: usize = 8 * 1_048_576;

/// Maximum concurrent multipart parts tracked for one object in host slice.
pub const MAX_S3_MULTIPART_PARTS: usize = 10_000;

/// Host scale contract path count (10k-class hermetic streaming).
///
/// Full 100k-path matrix remains OPEN for later residual; this constant locks the
/// budget used by host scale contracts so they cannot silently shrink.
pub const SCALE_HOST_PATH_COUNT: usize = 10_000;

/// Maximum remote path-key working set retained across streaming snapshot pages.
///
/// Path keys (not full body payloads) are required for complete-listing delete
/// derivation. Over-limit fails closed — never clamps or thrash-materializes.
pub const MAX_STREAMING_REMOTE_PATH_KEYS: usize = 100_000;

/// Maximum intermediate intent accumulation while streaming remote pages before page-split.
///
/// Host residual: fails closed rather than unbounded `Vec` growth on multi-page plan.
/// Equals the path-key ceiling so one intent per path remains the structural bound.
pub const MAX_STREAMING_INTERMEDIATE_INTENTS: usize = MAX_STREAMING_REMOTE_PATH_KEYS;
