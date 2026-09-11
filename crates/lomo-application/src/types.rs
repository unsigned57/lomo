use lomo_core::{OperationId, RelativeWorkspacePath};
use lomo_media::PromotePlan;
use lomo_store::SafProjectionCommitResult;
use lomo_workspace::MemoId;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct CreateMemoRequest {
    pub operation_id: OperationId,
    pub relative_path: Option<RelativeWorkspacePath>,
    pub time_token: Option<String>,
    pub content: String,
    pub expected_document_fingerprint: Option<String>,
    pub pinned: bool,
    #[serde(default)]
    pub pending_promotes: Vec<PromotePlan>,
    #[serde(default)]
    pub chronology_epoch_ms: Option<i64>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CreateMemoResult {
    pub memo_id: MemoId,
    pub commit_result: SafProjectionCommitResult,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct UpdateMemoRequest {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
    pub content: String,
    pub expected_document_fingerprint: String,
    #[serde(default)]
    pub pending_promotes: Vec<PromotePlan>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UpdateMemoResult {
    pub commit_result: SafProjectionCommitResult,
    pub file_fingerprint: String,
    pub event_sequence: u64,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct DeleteMemoRequest {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
    pub expected_document_fingerprint: String,
    pub trashed_at_ms: Option<i64>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DeleteMemoResult {
    pub commit_result: SafProjectionCommitResult,
    pub file_fingerprint: String,
    pub event_sequence: u64,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct PinMemoRequest {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
    pub pinned: bool,
    pub pinned_at_ms: Option<i64>,
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
