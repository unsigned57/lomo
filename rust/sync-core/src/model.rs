use std::fmt;

pub const MAGIC: [u8; 4] = *b"LOMO";
pub const PROTOCOL_VERSION: u16 = 1;
pub const MAX_PAYLOAD_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_ITEMS: usize = 1_000_000;
pub const MAX_STRING_BYTES: usize = 1024 * 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Backend {
    S3 = 1,
    WebDav = 2,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RemoteAbsenceVerification {
    VerifiedAbsent = 0,
    UnverifiedAbsent = 1,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Direction {
    None = 0,
    Upload = 1,
    Download = 2,
    DeleteLocal = 3,
    DeleteRemote = 4,
    Conflict = 5,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Reason {
    Unchanged = 0,
    LocalOnly = 1,
    RemoteOnly = 2,
    LocalNewer = 3,
    RemoteNewer = 4,
    LocalDeleted = 5,
    RemoteDeleted = 6,
    SameTimestamp = 7,
    Conflict = 8,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LocalSnapshot {
    pub path: String,
    pub last_modified: i64,
    pub size: Option<i64>,
    pub fingerprint: Option<String>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RemoteSnapshot {
    pub path: String,
    pub etag: Option<String>,
    pub last_modified: Option<i64>,
    pub size: Option<i64>,
    pub fingerprint: Option<String>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MetadataSnapshot {
    pub path: String,
    pub etag: Option<String>,
    pub remote_last_modified: Option<i64>,
    pub local_last_modified: Option<i64>,
    pub local_fingerprint: Option<String>,
    pub last_synced_at: i64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Action {
    pub path: String,
    pub direction: Direction,
    pub reason: Reason,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Request {
    pub backend: Backend,
    pub timestamp_tolerance_ms: i64,
    pub local: Vec<LocalSnapshot>,
    pub remote: Vec<RemoteSnapshot>,
    pub metadata: Vec<MetadataSnapshot>,
    pub pre_resolved: Vec<Action>,
    pub suppressed: Vec<String>,
    pub missing_remote_verification: Vec<(String, RemoteAbsenceVerification)>,
    pub default_missing_remote_verification: RemoteAbsenceVerification,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Plan {
    pub actions: Vec<Action>,
    pub pending_changes: u32,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ProtocolError {
    PayloadTooLarge,
    Truncated,
    InvalidMagic,
    UnsupportedVersion(u16),
    InvalidEnum { field: &'static str, value: u8 },
    InvalidCount { field: &'static str, value: usize },
    InvalidString { field: &'static str },
    InvalidPath { path: String },
    DuplicatePath { field: &'static str, path: String },
    NegativeValue { field: &'static str, value: i64 },
    PendingCountMismatch { expected: usize, actual: u32 },
    OutputOverflow,
}

impl fmt::Display for ProtocolError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{self:?}")
    }
}

impl std::error::Error for ProtocolError {}
