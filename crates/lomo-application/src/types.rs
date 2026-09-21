use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
use lomo_media::PromotePlan;
use lomo_store::SafProjectionCommitResult;
use lomo_workspace::{MemoId, ResourceBudget, SourceFingerprint};
use serde::{Deserialize, Serialize};

use crate::calendar::parse_time_token;
use crate::error::validation;

/// A frozen "create memo" command.
///
/// JSON deserialization and host construction share [`CreateMemoRequest::validate`], so an
/// over-budget body, malformed time token, or malformed expected fingerprint cannot become a
/// command. A missing time token or fingerprint stays a real domain state: the session chooses
/// the creation time and skips the baseline check respectively.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(try_from = "CreateMemoRequestJson")]
pub struct CreateMemoRequest {
    pub operation_id: OperationId,
    pub relative_path: Option<RelativeWorkspacePath>,
    pub time_token: Option<String>,
    pub content: String,
    pub expected_document_fingerprint: Option<String>,
    pub pinned: bool,
    pub pending_promotes: Vec<PromotePlan>,
    pub chronology_epoch_ms: Option<i64>,
}

#[derive(Deserialize)]
struct CreateMemoRequestJson {
    operation_id: OperationId,
    relative_path: Option<RelativeWorkspacePath>,
    time_token: Option<String>,
    content: String,
    expected_document_fingerprint: Option<String>,
    pinned: bool,
    #[serde(default)]
    pending_promotes: Vec<PromotePlan>,
    #[serde(default)]
    chronology_epoch_ms: Option<i64>,
}

impl TryFrom<CreateMemoRequestJson> for CreateMemoRequest {
    type Error = LomoError;

    fn try_from(json: CreateMemoRequestJson) -> Result<Self, LomoError> {
        let request = Self {
            operation_id: json.operation_id,
            relative_path: json.relative_path,
            time_token: json.time_token,
            content: json.content,
            expected_document_fingerprint: json.expected_document_fingerprint,
            pinned: json.pinned,
            pending_promotes: json.pending_promotes,
            chronology_epoch_ms: json.chronology_epoch_ms,
        };
        request.validate()?;
        Ok(request)
    }
}

impl CreateMemoRequest {
    /// Validates the frozen command before it can be persisted or replayed.
    ///
    /// # Errors
    /// Resource-limit when the body exceeds the editable memo budget; validation when the time
    /// token or expected document fingerprint is malformed.
    pub fn validate(&self) -> Result<(), LomoError> {
        validate_memo_body(&self.content)?;
        if let Some(token) = &self.time_token {
            validate_time_token(token)?;
        }
        if let Some(fingerprint) = &self.expected_document_fingerprint {
            validate_document_fingerprint(fingerprint)?;
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CreateMemoResult {
    pub memo_id: MemoId,
    pub commit_result: SafProjectionCommitResult,
}

/// A frozen "update memo" command.
///
/// The expected document fingerprint is required: an edit without a frozen baseline is not a
/// legal command, so both JSON and host construction pass through [`UpdateMemoRequest::validate`].
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(try_from = "UpdateMemoRequestJson")]
pub struct UpdateMemoRequest {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
    pub content: String,
    pub expected_document_fingerprint: String,
    pub pending_promotes: Vec<PromotePlan>,
}

#[derive(Deserialize)]
struct UpdateMemoRequestJson {
    operation_id: OperationId,
    memo_id: MemoId,
    content: String,
    expected_document_fingerprint: String,
    #[serde(default)]
    pending_promotes: Vec<PromotePlan>,
}

impl TryFrom<UpdateMemoRequestJson> for UpdateMemoRequest {
    type Error = LomoError;

    fn try_from(json: UpdateMemoRequestJson) -> Result<Self, LomoError> {
        let request = Self {
            operation_id: json.operation_id,
            memo_id: json.memo_id,
            content: json.content,
            expected_document_fingerprint: json.expected_document_fingerprint,
            pending_promotes: json.pending_promotes,
        };
        request.validate()?;
        Ok(request)
    }
}

impl UpdateMemoRequest {
    /// Validates the frozen command before it can be persisted or replayed.
    ///
    /// # Errors
    /// Resource-limit when the body exceeds the editable memo budget; validation when the
    /// expected document fingerprint is malformed.
    pub fn validate(&self) -> Result<(), LomoError> {
        validate_memo_body(&self.content)?;
        validate_document_fingerprint(&self.expected_document_fingerprint)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UpdateMemoResult {
    pub commit_result: SafProjectionCommitResult,
    pub file_fingerprint: String,
    pub event_sequence: u64,
}

/// A frozen "delete memo" command.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(try_from = "DeleteMemoRequestJson")]
pub struct DeleteMemoRequest {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
    pub expected_document_fingerprint: String,
    pub trashed_at_ms: Option<i64>,
}

#[derive(Deserialize)]
struct DeleteMemoRequestJson {
    operation_id: OperationId,
    memo_id: MemoId,
    expected_document_fingerprint: String,
    trashed_at_ms: Option<i64>,
}

impl TryFrom<DeleteMemoRequestJson> for DeleteMemoRequest {
    type Error = LomoError;

    fn try_from(json: DeleteMemoRequestJson) -> Result<Self, LomoError> {
        let request = Self {
            operation_id: json.operation_id,
            memo_id: json.memo_id,
            expected_document_fingerprint: json.expected_document_fingerprint,
            trashed_at_ms: json.trashed_at_ms,
        };
        request.validate()?;
        Ok(request)
    }
}

impl DeleteMemoRequest {
    /// Validates the frozen command before it can be persisted or replayed.
    ///
    /// # Errors
    /// Validation when the expected document fingerprint is malformed.
    pub fn validate(&self) -> Result<(), LomoError> {
        validate_document_fingerprint(&self.expected_document_fingerprint)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DeleteMemoResult {
    pub commit_result: SafProjectionCommitResult,
    pub file_fingerprint: String,
    pub event_sequence: u64,
}

/// A frozen "pin memo" command.
///
/// The pin state is one closed policy rather than two independent fields, so a command cannot
/// request an unpin that still carries a pin timestamp. JSON, native and host construction all
/// pass through [`PinMemoRequest::new`], which applies [`PinMemoRequest::validate`], so a raw
/// timestamp can never become a command.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(try_from = "PinMemoRequestJson", into = "PinMemoRequestJson")]
pub struct PinMemoRequest {
    operation_id: OperationId,
    memo_id: MemoId,
    pin: PinPolicy,
}

/// The closed pin state of one frozen command.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PinPolicy {
    /// Clears the pin; no timestamp is meaningful.
    Unpinned,
    /// Sets the pin. `None` lets the session freeze the moment the command is applied.
    Pinned { at_ms: Option<i64> },
}

impl PinPolicy {
    /// Whether this policy sets a pin.
    #[must_use]
    pub const fn is_pinned(self) -> bool {
        matches!(self, Self::Pinned { .. })
    }
}

/// The untrusted wire form. It is a superset of the legal commands: the fields are the historical
/// flat pair, and an unpin that still carries a timestamp is rejected by [`PinMemoRequest::new`]
/// instead of being silently dropped.
#[derive(Deserialize, Serialize)]
struct PinMemoRequestJson {
    operation_id: OperationId,
    memo_id: MemoId,
    pinned: bool,
    #[serde(default)]
    pinned_at_ms: Option<i64>,
}

impl TryFrom<PinMemoRequestJson> for PinMemoRequest {
    type Error = LomoError;

    fn try_from(json: PinMemoRequestJson) -> Result<Self, LomoError> {
        let pin = match (json.pinned, json.pinned_at_ms) {
            (true, at_ms) => PinPolicy::Pinned { at_ms },
            (false, None) => PinPolicy::Unpinned,
            (false, Some(_)) => {
                return Err(validation(
                    "invalid_pin_request",
                    "an unpin cannot carry a pin timestamp",
                ));
            }
        };
        Self::new(json.operation_id, json.memo_id, pin)
    }
}

impl From<PinMemoRequest> for PinMemoRequestJson {
    fn from(request: PinMemoRequest) -> Self {
        let (pinned, pinned_at_ms) = match request.pin {
            PinPolicy::Unpinned => (false, None),
            PinPolicy::Pinned { at_ms } => (true, at_ms),
        };
        Self {
            operation_id: request.operation_id,
            memo_id: request.memo_id,
            pinned,
            pinned_at_ms,
        }
    }
}

impl PinMemoRequest {
    /// Builds a pin command, rejecting a pin time the domain cannot represent.
    ///
    /// # Errors
    /// Validation when a pinned state carries a non-positive timestamp.
    pub fn new(
        operation_id: OperationId,
        memo_id: MemoId,
        pin: PinPolicy,
    ) -> Result<Self, LomoError> {
        let request = Self {
            operation_id,
            memo_id,
            pin,
        };
        request.validate()?;
        Ok(request)
    }

    /// Validates the frozen command before it can be persisted or replayed.
    ///
    /// # Errors
    /// Validation when a pinned state carries a non-positive timestamp.
    pub fn validate(&self) -> Result<(), LomoError> {
        if let PinPolicy::Pinned { at_ms: Some(at_ms) } = self.pin
            && at_ms <= 0
        {
            return Err(validation(
                "invalid_pin_timestamp",
                "pin timestamp must be positive",
            ));
        }
        Ok(())
    }

    /// Consumes the command into its verified parts.
    pub(crate) fn into_parts(self) -> (OperationId, MemoId, PinPolicy) {
        (self.operation_id, self.memo_id, self.pin)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PinMemoResult {
    pub commit_result: SafProjectionCommitResult,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SessionMemoView {
    pub memo_id: String,
    pub source_path: String,
    pub file_fingerprint: String,
    pub body: String,
    pub is_pinned: bool,
    pub is_trashed: bool,
    pub created_at_ms: i64,
    pub updated_at_ms: i64,
}

/// Applies the shared editable-memo budget to a command body.
fn validate_memo_body(content: &str) -> Result<(), LomoError> {
    ResourceBudget::check_editable_memo_chars(content.chars().count())
}

/// Rejects a time token the calendar owner cannot resolve.
fn validate_time_token(token: &str) -> Result<(), LomoError> {
    parse_time_token(token)?;
    Ok(())
}

/// Rejects an expected document fingerprint that is not a SHA-256 digest.
fn validate_document_fingerprint(fingerprint: &str) -> Result<(), LomoError> {
    SourceFingerprint::parse(fingerprint)?;
    Ok(())
}
