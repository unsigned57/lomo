//! S3 provider adapter (P5-06) — protocol port only.
//!
//! Compiles and executes provider-neutral intents over path-style HTTP S3. Does **not** own
//! direction, conflict, baseline, tombstone, or retry policy. Multipart is an execution detail of
//! [`PreparedRemoteBatch`], not a second planner. `ETag` is a revision token only — never content
//! `SHA-256`. Secrets stay process-local for the adapter lifetime.
//!
//! Host hermetic transport uses `reqwest`/`rustls` + pure `SigV4` (no AWS SDK in the dark production
//! graph). Real-provider smoke and four-ABI AWS SDK link remain later residuals.

mod adapter;
mod endpoint;
mod list_xml;
mod rclone_crypt;
mod sigv4;
mod status_map;
mod transport;

pub use adapter::{
    MapS3ConnectParams, MapS3ObjectSource, MultipartConfirmedPart, MultipartSession, S3Adapter,
    S3ObjectSource, WorkspaceFileObjectSource, connect_map_s3_source, connect_workspace_s3,
};
pub use endpoint::{S3AddressingStyle, S3Credentials, S3Endpoint};
pub use rclone_crypt::{
    RcloneCryptConfig, RcloneFilenameEncoding, RcloneFilenameEncryption, RcloneKeyMaterial,
    decrypt_filename_path, decrypt_payload, encrypt_filename_path, encrypt_payload,
};
pub use sigv4::{EMPTY_PAYLOAD_SHA256, aws_published_sigv4_example_matches};
pub use status_map::map_s3_http_status;
pub use transport::S3Transport;
