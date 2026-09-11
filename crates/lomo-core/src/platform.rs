use std::{collections::BTreeSet, fmt};

use serde::{Deserialize, Deserializer, Serialize};

use crate::{
    ActionId, BatchId, CapabilityToken, ExchangeToken, JobId, LomoError, PageSize,
    RelativeWorkspacePath,
};

const PLATFORM_SCHEMA: u32 = 1;
const MAX_ACTIONS_PER_BATCH: usize = 64;
const MAX_DOCUMENT_HANDLE_BYTES: usize = 1_024;

/// Provider-owned, opaque identity returned by document enumeration or stat.
///
/// The value is never interpreted as a workspace path. It may contain provider-specific
/// separators, but must remain bounded and free of controls before it crosses an FFI boundary.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct DocumentHandle(String);

impl DocumentHandle {
    /// Validates an opaque provider document identity.
    ///
    /// # Errors
    ///
    /// Returns validation for empty, oversized, or control-bearing handles.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        if raw.is_empty()
            || raw.len() > MAX_DOCUMENT_HANDLE_BYTES
            || raw.chars().any(char::is_control)
        {
            return Err(LomoError::validation(
                "invalid_document_handle",
                "document handle must be non-empty, bounded UTF-8 without controls",
            ));
        }
        Ok(Self(raw.to_owned()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Sha256Digest(String);

impl Sha256Digest {
    /// Parses a lowercase hexadecimal SHA-256 digest.
    ///
    /// # Errors
    ///
    /// Returns a validation error unless the value is exactly 64 lowercase hexadecimal bytes.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        if raw.len() != 64
            || !raw
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        {
            return Err(LomoError::validation(
                "invalid_sha256_digest",
                "SHA-256 digest must be 64 lowercase hexadecimal bytes",
            ));
        }
        Ok(Self(raw.to_owned()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum ExpectedFingerprint {
    Absent,
    Match(ActionEvidence),
}

impl ExpectedFingerprint {
    #[must_use]
    pub const fn absent() -> Self {
        Self::Absent
    }

    #[must_use]
    pub const fn matching(evidence: ActionEvidence) -> Self {
        Self::Match(evidence)
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ActionEvidence {
    length: u64,
    digest: Sha256Digest,
    fingerprint: String,
}

impl ActionEvidence {
    /// Creates independently verifiable postcondition evidence.
    ///
    /// # Errors
    ///
    /// Returns a validation error for an empty, oversized, or non-protocol fingerprint.
    pub fn verified(
        length: u64,
        digest: Sha256Digest,
        fingerprint: &str,
    ) -> Result<Self, LomoError> {
        let validated = CapabilityToken::parse(fingerprint).map_err(|_error| {
            LomoError::validation(
                "invalid_fingerprint",
                "fingerprint must satisfy the opaque identifier boundary",
            )
        })?;
        Ok(Self {
            length,
            digest,
            fingerprint: validated.as_str().to_owned(),
        })
    }

    #[must_use]
    pub const fn length(&self) -> u64 {
        self.length
    }

    #[must_use]
    pub const fn digest(&self) -> &Sha256Digest {
        &self.digest
    }

    #[must_use]
    pub fn fingerprint(&self) -> &str {
        &self.fingerprint
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ExchangeArtifact {
    token: ExchangeToken,
    length: u64,
    digest: Sha256Digest,
}

impl ExchangeArtifact {
    /// Describes content already present in the application-private exchange directory.
    ///
    /// # Errors
    ///
    /// Returns a validation error when the exchange token is invalid.
    pub fn new(token: &str, length: u64, digest: Sha256Digest) -> Result<Self, LomoError> {
        Ok(Self {
            token: ExchangeToken::parse(token)?,
            length,
            digest,
        })
    }

    #[must_use]
    pub const fn token(&self) -> &ExchangeToken {
        &self.token
    }

    #[must_use]
    pub const fn length(&self) -> u64 {
        self.length
    }

    #[must_use]
    pub const fn digest(&self) -> &Sha256Digest {
        &self.digest
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum WriteMode {
    Create,
    Replace,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum WorkspaceTarget {
    Root,
    Relative(RelativeWorkspacePath),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum DocumentKind {
    File,
    Directory,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct DocumentMetadata {
    target: WorkspaceTarget,
    document_handle: DocumentHandle,
    kind: DocumentKind,
    mime_type: Option<String>,
    evidence: ActionEvidence,
}

impl DocumentMetadata {
    /// Creates bounded, independently verifiable document metadata.
    ///
    /// # Errors
    ///
    /// Returns a validation error for an empty, oversized, or control-bearing MIME type.
    pub fn new(
        target: WorkspaceTarget,
        kind: DocumentKind,
        mime_type: Option<&str>,
        evidence: ActionEvidence,
    ) -> Result<Self, LomoError> {
        let handle = match &target {
            WorkspaceTarget::Root => "root".to_owned(),
            WorkspaceTarget::Relative(path) => path.as_str().to_owned(),
        };
        Self::new_with_handle(
            target,
            DocumentHandle::parse(&handle)?,
            kind,
            mime_type,
            evidence,
        )
    }

    /// Creates metadata with a provider-owned opaque identity distinct from its display path.
    ///
    /// # Errors
    ///
    /// Returns validation for invalid MIME metadata.
    pub fn new_with_handle(
        target: WorkspaceTarget,
        document_handle: DocumentHandle,
        kind: DocumentKind,
        mime_type: Option<&str>,
        evidence: ActionEvidence,
    ) -> Result<Self, LomoError> {
        if mime_type.is_some_and(|value| {
            value.is_empty() || value.len() > 255 || value.chars().any(char::is_control)
        }) {
            return Err(LomoError::validation(
                "invalid_document_mime_type",
                "document MIME type must be non-empty, bounded UTF-8 without controls",
            ));
        }
        Ok(Self {
            target,
            document_handle,
            kind,
            mime_type: mime_type.map(str::to_owned),
            evidence,
        })
    }

    #[must_use]
    pub const fn target(&self) -> &WorkspaceTarget {
        &self.target
    }

    #[must_use]
    pub const fn document_handle(&self) -> &DocumentHandle {
        &self.document_handle
    }

    #[must_use]
    pub const fn kind(&self) -> DocumentKind {
        self.kind
    }

    #[must_use]
    pub fn mime_type(&self) -> Option<&str> {
        self.mime_type.as_deref()
    }

    #[must_use]
    pub const fn evidence(&self) -> &ActionEvidence {
        &self.evidence
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum DocumentLocator {
    Path(RelativeWorkspacePath),
    Opaque(DocumentHandle),
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct MetadataPage {
    items: Vec<DocumentMetadata>,
    next_cursor: Option<CapabilityToken>,
}

impl MetadataPage {
    /// Creates a bounded metadata page with an opaque continuation cursor.
    ///
    /// # Errors
    ///
    /// Returns a resource-limit or validation error for more than 256 entries or an invalid cursor.
    pub fn new(items: Vec<DocumentMetadata>, next_cursor: Option<&str>) -> Result<Self, LomoError> {
        if items.len() > 256 {
            return Err(LomoError::resource_limit(
                "metadata_page_limit_exceeded",
                "metadata page contains more than 256 entries",
            ));
        }
        Ok(Self {
            items,
            next_cursor: next_cursor.map(CapabilityToken::parse).transpose()?,
        })
    }

    #[must_use]
    pub fn items(&self) -> &[DocumentMetadata] {
        &self.items
    }

    #[must_use]
    pub const fn next_cursor(&self) -> Option<&CapabilityToken> {
        self.next_cursor.as_ref()
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct VerifiedAbsence {
    target: WorkspaceTarget,
    fingerprint: String,
}

impl VerifiedAbsence {
    /// Records an independently verified absent target.
    ///
    /// # Errors
    ///
    /// Returns a validation error when the verification fingerprint is invalid.
    pub fn new(target: WorkspaceTarget, fingerprint: &str) -> Result<Self, LomoError> {
        let fingerprint = CapabilityToken::parse(fingerprint)?;
        Ok(Self {
            target,
            fingerprint: fingerprint.as_str().to_owned(),
        })
    }

    #[must_use]
    pub const fn target(&self) -> &WorkspaceTarget {
        &self.target
    }

    #[must_use]
    pub fn fingerprint(&self) -> &str {
        &self.fingerprint
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum PlatformAction {
    Stat {
        action_id: ActionId,
        capability: CapabilityToken,
        target: WorkspaceTarget,
    },
    ListChildren {
        action_id: ActionId,
        capability: CapabilityToken,
        target: WorkspaceTarget,
        cursor: Option<String>,
        page_size: PageSize,
    },
    EnsureDirectory {
        action_id: ActionId,
        capability: CapabilityToken,
        path: RelativeWorkspacePath,
    },
    ReadToExchange {
        action_id: ActionId,
        capability: CapabilityToken,
        path: RelativeWorkspacePath,
        locator: DocumentLocator,
        exchange_token: ExchangeToken,
        expected_source: ExpectedFingerprint,
    },
    WriteFromExchange {
        action_id: ActionId,
        capability: CapabilityToken,
        artifact: ExchangeArtifact,
        path: RelativeWorkspacePath,
        mode: WriteMode,
        expected_target: ExpectedFingerprint,
    },
    Move {
        action_id: ActionId,
        capability: CapabilityToken,
        source: RelativeWorkspacePath,
        target: RelativeWorkspacePath,
        expected_source: ExpectedFingerprint,
        expected_target: ExpectedFingerprint,
    },
    Delete {
        action_id: ActionId,
        capability: CapabilityToken,
        path: RelativeWorkspacePath,
        expected_target: ExpectedFingerprint,
    },
}

impl PlatformAction {
    #[must_use]
    pub const fn stat(
        action_id: ActionId,
        capability: CapabilityToken,
        path: RelativeWorkspacePath,
    ) -> Self {
        Self::Stat {
            action_id,
            capability,
            target: WorkspaceTarget::Relative(path),
        }
    }

    #[must_use]
    pub const fn stat_root(action_id: ActionId, capability: CapabilityToken) -> Self {
        Self::Stat {
            action_id,
            capability,
            target: WorkspaceTarget::Root,
        }
    }

    #[must_use]
    pub const fn list_children(
        action_id: ActionId,
        capability: CapabilityToken,
        path: RelativeWorkspacePath,
        cursor: Option<String>,
        page_size: PageSize,
    ) -> Self {
        Self::ListChildren {
            action_id,
            capability,
            target: WorkspaceTarget::Relative(path),
            cursor,
            page_size,
        }
    }

    #[must_use]
    pub const fn list_root(
        action_id: ActionId,
        capability: CapabilityToken,
        cursor: Option<String>,
        page_size: PageSize,
    ) -> Self {
        Self::ListChildren {
            action_id,
            capability,
            target: WorkspaceTarget::Root,
            cursor,
            page_size,
        }
    }

    #[must_use]
    pub const fn ensure_directory(
        action_id: ActionId,
        capability: CapabilityToken,
        path: RelativeWorkspacePath,
    ) -> Self {
        Self::EnsureDirectory {
            action_id,
            capability,
            path,
        }
    }

    /// Creates a streaming read action.
    ///
    /// # Errors
    ///
    /// Returns a validation error when the exchange token is invalid.
    pub fn read_to_exchange(
        action_id: ActionId,
        capability: CapabilityToken,
        path: RelativeWorkspacePath,
        exchange_token: &str,
        expected_source: ExpectedFingerprint,
    ) -> Result<Self, LomoError> {
        Ok(Self::ReadToExchange {
            action_id,
            capability,
            locator: DocumentLocator::Path(path.clone()),
            path,
            exchange_token: ExchangeToken::parse(exchange_token)?,
            expected_source,
        })
    }

    /// Creates a read bound to identity returned by a prior list/stat action.
    ///
    /// # Errors
    ///
    /// Returns validation when the exchange token is invalid.
    pub fn read_listed_to_exchange(
        action_id: ActionId,
        capability: CapabilityToken,
        path: RelativeWorkspacePath,
        document_handle: DocumentHandle,
        exchange_token: &str,
        expected_source: ExpectedFingerprint,
    ) -> Result<Self, LomoError> {
        Ok(Self::ReadToExchange {
            action_id,
            capability,
            path,
            locator: DocumentLocator::Opaque(document_handle),
            exchange_token: ExchangeToken::parse(exchange_token)?,
            expected_source,
        })
    }

    #[must_use]
    pub const fn write_from_exchange(
        action_id: ActionId,
        capability: CapabilityToken,
        artifact: ExchangeArtifact,
        path: RelativeWorkspacePath,
        mode: WriteMode,
        expected_target: ExpectedFingerprint,
    ) -> Self {
        Self::WriteFromExchange {
            action_id,
            capability,
            artifact,
            path,
            mode,
            expected_target,
        }
    }

    #[must_use]
    pub const fn move_path(
        action_id: ActionId,
        capability: CapabilityToken,
        source: RelativeWorkspacePath,
        target: RelativeWorkspacePath,
        expected_source: ExpectedFingerprint,
        expected_target: ExpectedFingerprint,
    ) -> Self {
        Self::Move {
            action_id,
            capability,
            source,
            target,
            expected_source,
            expected_target,
        }
    }

    #[must_use]
    pub const fn delete(
        action_id: ActionId,
        capability: CapabilityToken,
        path: RelativeWorkspacePath,
        expected_target: ExpectedFingerprint,
    ) -> Self {
        Self::Delete {
            action_id,
            capability,
            path,
            expected_target,
        }
    }

    #[must_use]
    pub const fn id(&self) -> &ActionId {
        match self {
            Self::Stat { action_id, .. }
            | Self::ListChildren { action_id, .. }
            | Self::EnsureDirectory { action_id, .. }
            | Self::ReadToExchange { action_id, .. }
            | Self::WriteFromExchange { action_id, .. }
            | Self::Move { action_id, .. }
            | Self::Delete { action_id, .. } => action_id,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct PlatformActionBatch {
    schema_version: u32,
    job_id: JobId,
    batch_id: BatchId,
    attempt: u32,
    deadline_epoch_millis: u64,
    actions: Vec<PlatformAction>,
}

#[derive(Deserialize)]
struct PlatformActionBatchWire {
    schema_version: u32,
    job_id: JobId,
    batch_id: BatchId,
    attempt: u32,
    deadline_epoch_millis: u64,
    actions: Vec<PlatformAction>,
}

impl<'de> Deserialize<'de> for PlatformActionBatch {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let wire = PlatformActionBatchWire::deserialize(deserializer)?;
        if wire.schema_version != PLATFORM_SCHEMA {
            return Err(serde::de::Error::custom(
                "unsupported platform batch schema",
            ));
        }
        Self::new(
            wire.job_id,
            wire.batch_id,
            wire.attempt,
            wire.deadline_epoch_millis,
            wire.actions,
        )
        .map_err(|error| serde::de::Error::custom(error.to_string()))
    }
}

impl PlatformActionBatch {
    /// Builds a bounded platform action batch.
    ///
    /// # Errors
    ///
    /// Returns validation/resource-limit errors for an invalid attempt/deadline, duplicate action
    /// ids, or an action count outside 1..=64.
    pub fn new(
        job_id: JobId,
        batch_id: BatchId,
        attempt: u32,
        deadline_epoch_millis: u64,
        actions: Vec<PlatformAction>,
    ) -> Result<Self, LomoError> {
        if actions.is_empty() || actions.len() > MAX_ACTIONS_PER_BATCH {
            return Err(LomoError::resource_limit(
                "invalid_platform_batch_size",
                "platform batch must contain 1..=64 actions",
            ));
        }
        if attempt == 0 || deadline_epoch_millis == 0 {
            return Err(LomoError::validation(
                "invalid_platform_batch_identity",
                "platform batch attempt and deadline must be non-zero",
            ));
        }
        let distinct_ids = actions
            .iter()
            .map(PlatformAction::id)
            .collect::<BTreeSet<_>>();
        if distinct_ids.len() != actions.len() {
            return Err(LomoError::validation(
                "duplicate_platform_action_id",
                "platform batch action ids must be unique",
            ));
        }
        Ok(Self {
            schema_version: PLATFORM_SCHEMA,
            job_id,
            batch_id,
            attempt,
            deadline_epoch_millis,
            actions,
        })
    }

    #[must_use]
    pub const fn schema_version(&self) -> u32 {
        self.schema_version
    }

    #[must_use]
    pub const fn job_id(&self) -> &JobId {
        &self.job_id
    }

    #[must_use]
    pub const fn batch_id(&self) -> &BatchId {
        &self.batch_id
    }

    #[must_use]
    pub const fn attempt(&self) -> u32 {
        self.attempt
    }

    #[must_use]
    pub const fn deadline_epoch_millis(&self) -> u64 {
        self.deadline_epoch_millis
    }

    #[must_use]
    pub fn actions(&self) -> &[PlatformAction] {
        &self.actions
    }

    pub(crate) fn remaining_after(&self, prefix_len: usize) -> Option<Self> {
        let actions = self.actions.get(prefix_len..)?.to_vec();
        if actions.is_empty() {
            return None;
        }
        Some(Self {
            schema_version: self.schema_version,
            job_id: self.job_id.clone(),
            batch_id: self.batch_id.clone(),
            attempt: self.attempt,
            deadline_epoch_millis: self.deadline_epoch_millis,
            actions,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum PlatformActionOutput {
    Stat {
        metadata: DocumentMetadata,
    },
    Listed {
        page: MetadataPage,
    },
    DirectoryReady {
        metadata: DocumentMetadata,
    },
    ReadToExchange {
        source_metadata: DocumentMetadata,
        artifact: ExchangeArtifact,
    },
    WriteComplete {
        metadata: DocumentMetadata,
    },
    MoveComplete {
        metadata: DocumentMetadata,
    },
    DeleteComplete {
        absence: VerifiedAbsence,
    },
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum ActionOutcome {
    Applied(PlatformActionOutput),
    AlreadySatisfied(PlatformActionOutput),
    Failed(LomoError),
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ActionResult {
    action_id: ActionId,
    outcome: ActionOutcome,
}

impl ActionResult {
    #[must_use]
    pub const fn new(action_id: ActionId, outcome: ActionOutcome) -> Self {
        Self { action_id, outcome }
    }

    #[must_use]
    pub const fn action_id(&self) -> &ActionId {
        &self.action_id
    }

    #[must_use]
    pub const fn outcome(&self) -> &ActionOutcome {
        &self.outcome
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct PlatformBatchResult {
    schema_version: u32,
    job_id: JobId,
    batch_id: BatchId,
    attempt: u32,
    action_results: Vec<ActionResult>,
}

impl PlatformBatchResult {
    #[must_use]
    pub const fn new(
        schema_version: u32,
        job_id: JobId,
        batch_id: BatchId,
        attempt: u32,
        action_results: Vec<ActionResult>,
    ) -> Self {
        Self {
            schema_version,
            job_id,
            batch_id,
            attempt,
            action_results,
        }
    }

    /// Validates this result as an ordered prefix of `batch`.
    ///
    /// # Errors
    ///
    /// Returns a validation error for any schema/identity mismatch, empty/oversized prefix, or
    /// action id that does not match the batch at the same position.
    pub fn validate_against(&self, batch: &PlatformActionBatch) -> Result<usize, LomoError> {
        if self.schema_version != PLATFORM_SCHEMA
            || self.job_id != batch.job_id
            || self.batch_id != batch.batch_id
            || self.attempt != batch.attempt
        {
            return Err(LomoError::validation(
                "platform_result_identity_mismatch",
                "platform result schema or batch identity does not match",
            ));
        }
        if self.action_results.is_empty() || self.action_results.len() > batch.actions.len() {
            return Err(LomoError::validation(
                "platform_result_prefix_invalid",
                "platform result must be a non-empty ordered action prefix",
            ));
        }
        if self
            .action_results
            .iter()
            .zip(&batch.actions)
            .any(|(result, action)| result.action_id != *action.id())
        {
            return Err(LomoError::validation(
                "platform_result_action_mismatch",
                "platform result action ids must match the ordered batch prefix",
            ));
        }
        for (index, (result, action)) in self.action_results.iter().zip(&batch.actions).enumerate()
        {
            if let Err(mismatch) = result.witness(action) {
                return Err(LomoError::validation(
                    "platform_result_output_mismatch",
                    format!(
                        "platform result at index {index} does not witness its {} action: {mismatch}",
                        action_shape(action)
                    ),
                ));
            }
        }
        Ok(self.action_results.len())
    }

    #[must_use]
    pub const fn schema_version(&self) -> u32 {
        self.schema_version
    }

    #[must_use]
    pub const fn job_id(&self) -> &JobId {
        &self.job_id
    }

    #[must_use]
    pub const fn batch_id(&self) -> &BatchId {
        &self.batch_id
    }

    #[must_use]
    pub const fn attempt(&self) -> u32 {
        self.attempt
    }

    #[must_use]
    pub fn action_results(&self) -> &[ActionResult] {
        &self.action_results
    }
}

/// Shared trait for executing platform action batches.
///
/// Implementations (such as POSIX filesystem on Linux, or SAF on Android) execute
/// capability-bound platform action batches and produce verified batch results.
pub trait PlatformActionExecutor: Send + Sync {
    /// Executes a batch of platform actions against platform-managed storage.
    ///
    /// The returned result is an ordered prefix of actions from the batch,
    /// stopping at the first failed action if any error occurs.
    ///
    /// # Errors
    ///
    /// Returns an error if the batch cannot be executed due to a fatal executor
    /// fault, or returns `PlatformBatchResult` containing `ActionOutcome::Failed`
    /// for individual action failures.
    fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError>;
}

impl ActionResult {
    fn witness(&self, action: &PlatformAction) -> Result<(), OutputMismatch> {
        let output = match &self.outcome {
            ActionOutcome::Applied(output) | ActionOutcome::AlreadySatisfied(output) => output,
            ActionOutcome::Failed(_) => return Ok(()),
        };
        output.witness_action(action)
    }
}

/// The one conjunct that made an output fail to witness its action.
///
/// A boolean answer to "does this output witness this action" destroys the evidence at the only
/// place where both sides of the platform contract are simultaneously observable, which leaves the
/// rejection unable to name anything but its own code. Deciding admissibility therefore yields the
/// reason as part of the decision.
#[derive(Debug)]
enum OutputMismatch {
    Shape {
        expected: &'static str,
        observed: &'static str,
    },
    Kind {
        expected: DocumentKind,
        observed: DocumentKind,
    },
    Target {
        expected: String,
        observed: String,
    },
    ExchangeToken {
        expected: String,
        observed: String,
    },
    Length {
        expected: u64,
        observed: u64,
    },
    Digest {
        expected: String,
        observed: String,
    },
}

impl fmt::Display for OutputMismatch {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Shape { expected, observed } => {
                write!(
                    formatter,
                    "output shape expected {expected}, observed {observed}"
                )
            }
            Self::Kind { expected, observed } => {
                write!(
                    formatter,
                    "document kind expected {expected:?}, observed {observed:?}"
                )
            }
            Self::Target { expected, observed } => {
                write!(formatter, "target expected {expected}, observed {observed}")
            }
            Self::ExchangeToken { expected, observed } => {
                write!(
                    formatter,
                    "exchange token expected {expected}, observed {observed}"
                )
            }
            Self::Length { expected, observed } => {
                write!(formatter, "length expected {expected}, observed {observed}")
            }
            Self::Digest { expected, observed } => {
                write!(formatter, "digest expected {expected}, observed {observed}")
            }
        }
    }
}

/// Keeps every reported field inside the boundary's diagnostic budget.
const MISMATCH_FIELD_CHARS: usize = 160;

fn brief(value: &str) -> String {
    let mut brief: String = value.chars().take(MISMATCH_FIELD_CHARS).collect();
    if brief.len() < value.len() {
        brief.push('…');
    }
    brief
}

fn describe_target(target: &WorkspaceTarget) -> String {
    match target {
        WorkspaceTarget::Root => "<root>".to_owned(),
        WorkspaceTarget::Relative(path) => brief(path.as_str()),
    }
}

const fn action_shape(action: &PlatformAction) -> &'static str {
    match action {
        PlatformAction::Stat { .. } => "Stat",
        PlatformAction::ListChildren { .. } => "ListChildren",
        PlatformAction::EnsureDirectory { .. } => "EnsureDirectory",
        PlatformAction::ReadToExchange { .. } => "ReadToExchange",
        PlatformAction::WriteFromExchange { .. } => "WriteFromExchange",
        PlatformAction::Move { .. } => "Move",
        PlatformAction::Delete { .. } => "Delete",
    }
}

const fn output_shape(output: &PlatformActionOutput) -> &'static str {
    match output {
        PlatformActionOutput::Stat { .. } => "Stat",
        PlatformActionOutput::Listed { .. } => "Listed",
        PlatformActionOutput::DirectoryReady { .. } => "DirectoryReady",
        PlatformActionOutput::ReadToExchange { .. } => "ReadToExchange",
        PlatformActionOutput::WriteComplete { .. } => "WriteComplete",
        PlatformActionOutput::MoveComplete { .. } => "MoveComplete",
        PlatformActionOutput::DeleteComplete { .. } => "DeleteComplete",
    }
}

fn expect_target(
    observed: &WorkspaceTarget,
    expected: &WorkspaceTarget,
) -> Result<(), OutputMismatch> {
    if observed == expected {
        return Ok(());
    }
    Err(OutputMismatch::Target {
        expected: describe_target(expected),
        observed: describe_target(observed),
    })
}

fn expect_relative(
    observed: &WorkspaceTarget,
    expected: &RelativeWorkspacePath,
) -> Result<(), OutputMismatch> {
    expect_target(observed, &WorkspaceTarget::Relative(expected.clone()))
}

const fn expect_kind(observed: DocumentKind, expected: DocumentKind) -> Result<(), OutputMismatch> {
    if matches!(
        (observed, expected),
        (DocumentKind::File, DocumentKind::File)
            | (DocumentKind::Directory, DocumentKind::Directory)
    ) {
        return Ok(());
    }
    Err(OutputMismatch::Kind { expected, observed })
}

fn expect_evidence(
    observed: &ActionEvidence,
    expected: &ExchangeArtifact,
) -> Result<(), OutputMismatch> {
    if observed.length() != expected.length() {
        return Err(OutputMismatch::Length {
            expected: expected.length(),
            observed: observed.length(),
        });
    }
    if observed.digest() != expected.digest() {
        return Err(OutputMismatch::Digest {
            expected: brief(expected.digest().as_str()),
            observed: brief(observed.digest().as_str()),
        });
    }
    Ok(())
}

impl PlatformActionOutput {
    fn witness_action(&self, action: &PlatformAction) -> Result<(), OutputMismatch> {
        match (self, action) {
            (Self::Stat { metadata }, PlatformAction::Stat { target, .. }) => {
                expect_target(metadata.target(), target)
            }
            (Self::Listed { .. }, PlatformAction::ListChildren { .. }) => Ok(()),
            (Self::DirectoryReady { metadata }, PlatformAction::EnsureDirectory { path, .. }) => {
                expect_kind(metadata.kind(), DocumentKind::Directory)?;
                expect_relative(metadata.target(), path)
            }
            (
                Self::ReadToExchange {
                    source_metadata,
                    artifact,
                },
                PlatformAction::ReadToExchange {
                    path,
                    exchange_token,
                    ..
                },
            ) => {
                expect_relative(source_metadata.target(), path)?;
                if artifact.token() == exchange_token {
                    return Ok(());
                }
                Err(OutputMismatch::ExchangeToken {
                    expected: brief(exchange_token.as_str()),
                    observed: brief(artifact.token().as_str()),
                })
            }
            (
                Self::WriteComplete { metadata },
                PlatformAction::WriteFromExchange { artifact, path, .. },
            ) => {
                expect_relative(metadata.target(), path)?;
                expect_evidence(metadata.evidence(), artifact)
            }
            (Self::MoveComplete { metadata }, PlatformAction::Move { target, .. }) => {
                expect_relative(metadata.target(), target)
            }
            (Self::DeleteComplete { absence }, PlatformAction::Delete { path, .. }) => {
                expect_relative(absence.target(), path)
            }
            _ => Err(OutputMismatch::Shape {
                expected: action_shape(action),
                observed: output_shape(self),
            }),
        }
    }
}
