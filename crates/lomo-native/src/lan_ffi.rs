//! Stage-6 LAN FFI conversion surface (P6-08).
//!
//! Conversion-only mapping between `BoltFFI` DTOs and `lomo-lan`. Every business rule — transcript
//! construction, short-code derivation, signature verification, batch limits, preview containment,
//! approval durability — stays in `lomo-lan`; this module only parses wire types, delegates, and
//! maps results back.
//!
//! Runtime operations are methods on the sole `LomoEngine` handle. Public free functions below are
//! pure conversion/contract helpers used by the Rust boundary corpus; they own no runtime state.

use boltffi::data;
use lomo_core::{ErrorCategory, LomoError, RetryDisposition};
use lomo_lan::{
    APPROVAL_TTL_MS, ATTACHMENT_SLOT_BODY, DevicePublicKey, DiscoveredPeerEndpoint, DisplayName,
    LAN_PROTOCOL_VERSION, LanAttachmentRef, LanBatchId, LanBatchPlan, LanBatchPreview,
    LanBatchRecovery, LanBindCandidate, LanDiscoverySnapshot, LanItemPlan, LanNetworkSnapshot,
    LanOutgoingBatchDrive, LanPairingChallenge, LanPairingId, LanReceivedBatchDecision,
    LanReceivedBatchDrive, LanReceivedItemOutcome, LanRuntimeInbox, LanServiceManager,
    LanServicePhase, LanServiceSnapshot, LanSessionChallenge, LanSessionId, LanSessionPhase,
    LanSessionSnapshot, MAX_BATCH_ITEMS, PAIRING_TTL_MS, RUNTIME_CHUNK_PLAINTEXT_BYTES_U32,
    SESSION_TTL_MS,
};

/// Maximum peers returned in one page (mirrors the durable registry ceiling).
const MAX_PEER_PAGE_ITEMS: usize = 64;

/// Rust-owned wire coordinates Kotlin needs only to stream source bytes.
#[data]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct LanTransferShapeDto {
    pub body_slot: u32,
    pub chunk_plaintext_bytes: u32,
    /// Bounded in-flight window: Kotlin submits at most this many chunks per send call.
    pub max_inflight_chunks: u32,
}

/// Exposes owner constants without duplicating the protocol shape in Kotlin.
#[must_use]
pub fn transfer_shape_to_ffi() -> LanTransferShapeDto {
    LanTransferShapeDto {
        body_slot: u32::from(ATTACHMENT_SLOT_BODY),
        chunk_plaintext_bytes: RUNTIME_CHUNK_PLAINTEXT_BYTES_U32,
        max_inflight_chunks: u32::try_from(lomo_lan::MAX_INFLIGHT_CHUNKS).unwrap_or(u32::MAX),
    }
}

/// One chunk payload Kotlin streams into the Rust-owned sliding-window sender.
///
/// The session/batch/coordinate binding is validated and sealed by `lomo-lan`; plaintext is
/// bounded by `chunk_plaintext_bytes`.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanChunkSendDto {
    pub session_id: String,
    pub batch_id: String,
    pub item_index: u32,
    pub attachment_slot: u32,
    pub chunk_index: u32,
    pub plaintext: Vec<u8>,
}

/// Protocol version and lifetimes owned by `lomo-lan`. Kotlin only displays remaining time.
#[data]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct LanProtocolLimitsDto {
    pub protocol_version: u32,
    pub pairing_ttl_ms: i64,
    pub session_ttl_ms: i64,
    pub approval_ttl_ms: i64,
}

/// Exposes owner protocol limits without duplicating them in Kotlin.
#[must_use]
pub fn protocol_limits_to_ffi() -> LanProtocolLimitsDto {
    LanProtocolLimitsDto {
        protocol_version: u32::from(LAN_PROTOCOL_VERSION),
        pairing_ttl_ms: PAIRING_TTL_MS,
        session_ttl_ms: SESSION_TTL_MS,
        approval_ttl_ms: APPROVAL_TTL_MS,
    }
}

fn boundary_err(code: &str, diagnostic: &str) -> LomoError {
    match LomoError::from_platform_boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
        None,
        None,
        diagnostic,
    ) {
        Ok(error) | Err(error) => error,
    }
}

/// One trusted peer as the UI sees it. Never carries key material beyond the public key bytes.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanPeerDto {
    pub device_id: String,
    pub display_name: String,
    pub public_key: Vec<u8>,
    pub paired_at_ms: i64,
    pub revoked: bool,
    pub revoked_at_ms: Option<i64>,
}

/// A bounded page of trusted peers.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanPeerPageDto {
    pub peers: Vec<LanPeerDto>,
    pub total: u32,
}

/// Public installation identity passed from Android Keystore-backed code.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanDeviceIdentityDto {
    pub public_key: Vec<u8>,
    pub display_name: String,
}

/// Rust-derived public identity advertised through Android NSD.
#[data]
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct LanLocalIdentityDto {
    pub device_id: String,
    pub display_name: String,
}

/// Rust-owned pairing challenge. The transcript is the only value Android signs.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanPairingChallengeDto {
    pub pairing_id: String,
    pub peer_device_id: String,
    pub peer_display_name: String,
    pub short_code: String,
    pub transcript_to_sign: Vec<u8>,
    pub deadline_ms: i64,
}

/// Rust-owned session transcript challenge. Android signs these bytes with its device key.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanSessionChallengeDto {
    pub session_id: String,
    pub peer_device_id: String,
    pub transcript_to_sign: Vec<u8>,
    pub deadline_ms: i64,
}

/// Public authenticated-session phase; key material is deliberately absent.
#[data]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum LanSessionPhaseDto {
    #[default]
    Authenticated,
}

/// Public state for one authenticated session.
#[data]
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct LanSessionSnapshotDto {
    pub session_id: String,
    pub peer_device_id: String,
    pub phase: LanSessionPhaseDto,
}

/// One attachment reference in a send request.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanAttachmentDto {
    pub slot: u32,
    pub source_reference: String,
    pub name: String,
    pub digest: String,
    pub size_bytes: u64,
}

/// One memo the user asked to send.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanSendItemDto {
    pub timestamp_ms: i64,
    pub content_digest: String,
    pub content_bytes: u64,
    pub title: String,
    pub attachments: Vec<LanAttachmentDto>,
}

/// The bounded preview the receiving user approves. Carries no body or attachment bytes.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanBatchPreviewDto {
    pub batch_id: String,
    pub sender_device_id: String,
    pub sender_display_name: String,
    pub item_count: u32,
    pub attachment_count: u32,
    pub total_bytes: u64,
    pub titles: Vec<String>,
}

/// One durable received batch awaiting an explicit user decision.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanPendingBatchDto {
    pub session_id: String,
    pub preview: LanBatchPreviewDto,
}

#[data]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum LanReceivedBatchDecisionDto {
    #[default]
    Pending,
    Approved,
    Rejected,
}

#[data]
#[derive(Clone, Debug, Default)]
pub struct LanPendingReceivedItemDto {
    pub item_id: String,
    pub item_index: u32,
}

#[data]
#[derive(Clone, Debug, Default)]
pub struct LanCommittedReceivedItemDto {
    pub item_id: String,
    pub item_index: u32,
    pub memo_id: String,
}

#[data]
#[derive(Clone, Debug, Default)]
pub struct LanFailedReceivedItemDto {
    pub item_id: String,
    pub item_index: u32,
    pub code: String,
}

/// The durable drive a received batch needs next — derived state, never a platform guess.
#[data]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum LanReceivedBatchDriveDto {
    #[default]
    AwaitingDecision,
    Receiving,
    ReadyToCommit,
    NeedsRebind,
    ApprovalExpired,
    Rejected,
    Complete,
}

/// One durable received batch partitioned by its explicit per-item outcomes.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanBatchRecoveryDto {
    pub session_id: String,
    pub preview: LanBatchPreviewDto,
    pub decision: LanReceivedBatchDecisionDto,
    pub drive: LanReceivedBatchDriveDto,
    /// Durable confirmed payload bytes; progress numerator, never wire traffic.
    pub confirmed_bytes: u64,
    pub pending_items: Vec<LanPendingReceivedItemDto>,
    pub committed_items: Vec<LanCommittedReceivedItemDto>,
    pub failed_items: Vec<LanFailedReceivedItemDto>,
}

/// The durable drive an outgoing batch needs next.
#[data]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum LanOutgoingBatchDriveDto {
    #[default]
    AwaitingDecision,
    Sendable,
    AwaitingReport,
    NeedsRebind,
    Rejected,
    Failed,
    Complete,
}

#[data]
#[derive(Clone, Debug, Default)]
pub struct LanOutgoingBatchDto {
    pub batch_id: String,
    pub drive: LanOutgoingBatchDriveDto,
    /// Stable disposition code when the drive is `Failed`.
    pub failure_code: Option<String>,
    /// Durable confirmed and planned payload bytes: honest progress, never wire-guessed.
    pub confirmed_bytes: u64,
    pub total_bytes: u64,
}

#[data]
#[derive(Clone, Debug, Default)]
pub struct LanCommittableItemDto {
    pub batch_id: String,
    pub item_index: u32,
}

/// Bounded runtime facts requiring Android UI or Keystore action.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanRuntimeInboxDto {
    pub pairing_challenges: Vec<LanPairingChallengeDto>,
    pub session_challenges: Vec<LanSessionChallengeDto>,
    pub active_sessions: Vec<LanSessionSnapshotDto>,
    pub pending_batches: Vec<LanPendingBatchDto>,
    pub batch_recoveries: Vec<LanBatchRecoveryDto>,
    pub committable_items: Vec<LanCommittableItemDto>,
    pub outgoing_batches: Vec<LanOutgoingBatchDto>,
}

/// Inbox snapshot plus the generation the listener pump had reached when the wait returned,
/// plus the pump's connection-scoped rejection telemetry.
///
/// `inbox` is `None` when the wait timed out without advancing the generation: an unchanged
/// observation never rebuilds the large DTO, so a stalled wait costs nothing per cycle.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanInboxWaitDto {
    pub generation: u64,
    pub inbox: Option<LanRuntimeInboxDto>,
    pub rejected_connection_count: u64,
    pub last_rejection_diagnostic: Option<String>,
}

/// Parses a foreign send request into the sole Rust-owned batch plan.
///
/// # Errors
///
/// Resource-limit when the item count or byte budgets are exceeded; validation for malformed
/// identifiers, digests, names or wire-width fields.
pub fn batch_plan_from_ffi(
    batch_id: &str,
    items: &[LanSendItemDto],
) -> Result<LanBatchPlan, LomoError> {
    if items.len() > MAX_BATCH_ITEMS {
        return Err(lomo_lan::lan_resource_limit(
            "lan_batch_too_many_items",
            "batch exceeds the 100-item LAN ceiling; use a workspace archive instead",
        ));
    }

    let parsed_batch = LanBatchId::parse(batch_id)?;
    let mut plans = Vec::with_capacity(items.len());
    for (position, item) in items.iter().enumerate() {
        let index = u16::try_from(position).map_err(|_error| {
            boundary_err(
                "lan_ffi_item_index_invalid",
                "batch item index does not fit the wire index width",
            )
        })?;
        let mut attachments = Vec::with_capacity(item.attachments.len());
        for attachment in &item.attachments {
            let slot = u16::try_from(attachment.slot).map_err(|_error| {
                boundary_err(
                    "lan_ffi_attachment_slot_invalid",
                    "attachment slot does not fit the wire slot width",
                )
            })?;
            attachments.push(LanAttachmentRef::new(
                slot,
                &attachment.source_reference,
                &attachment.name,
                &attachment.digest,
                attachment.size_bytes,
            )?);
        }
        plans.push(LanItemPlan::new(
            &parsed_batch,
            index,
            item.timestamp_ms,
            &item.content_digest,
            item.content_bytes,
            &item.title,
            attachments,
        )?);
    }
    LanBatchPlan::new(parsed_batch, plans)
}

/// Maps a bounded Rust preview without introducing a body-bearing DTO.
#[must_use]
pub fn batch_preview_to_ffi(preview: &LanBatchPreview) -> LanBatchPreviewDto {
    LanBatchPreviewDto {
        batch_id: preview.batch_id().as_str().to_owned(),
        sender_device_id: preview.sender_device_id().as_str().to_owned(),
        sender_display_name: preview.sender_name().as_str().to_owned(),
        item_count: u32::try_from(preview.item_count()).unwrap_or(u32::MAX),
        attachment_count: u32::try_from(preview.attachment_count()).unwrap_or(u32::MAX),
        total_bytes: preview.total_bytes(),
        titles: preview.titles().to_vec(),
    }
}

/// Maps the Rust runtime inbox without introducing a second foreign-owned state machine.
#[must_use]
pub fn runtime_inbox_to_ffi(inbox: &LanRuntimeInbox) -> LanRuntimeInboxDto {
    LanRuntimeInboxDto {
        pairing_challenges: inbox
            .pairing_challenges()
            .iter()
            .map(pairing_challenge_to_ffi)
            .collect(),
        session_challenges: inbox
            .session_challenges()
            .iter()
            .map(session_challenge_to_ffi)
            .collect(),
        active_sessions: inbox
            .active_sessions()
            .iter()
            .map(session_snapshot_to_ffi)
            .collect(),
        pending_batches: inbox
            .pending_batches()
            .iter()
            .map(|pending| LanPendingBatchDto {
                session_id: pending.session_id().as_str().to_owned(),
                preview: batch_preview_to_ffi(pending.preview()),
            })
            .collect(),
        batch_recoveries: inbox
            .batch_recoveries()
            .iter()
            .map(batch_recovery_to_ffi)
            .collect(),
        committable_items: inbox
            .committable_items()
            .iter()
            .map(|item| LanCommittableItemDto {
                batch_id: item.batch_id().as_str().to_owned(),
                item_index: u32::from(item.item_index()),
            })
            .collect(),
        outgoing_batches: inbox
            .outgoing_batches()
            .iter()
            .map(outgoing_batch_to_ffi)
            .collect(),
    }
}

fn batch_recovery_to_ffi(recovery: &LanBatchRecovery) -> LanBatchRecoveryDto {
    let mut pending_items = Vec::new();
    let mut committed_items = Vec::new();
    let mut failed_items = Vec::new();
    for item in recovery.items() {
        match item.outcome() {
            LanReceivedItemOutcome::Pending => pending_items.push(LanPendingReceivedItemDto {
                item_id: item.item_id().to_owned(),
                item_index: u32::from(item.item_index()),
            }),
            LanReceivedItemOutcome::Committed { memo_id } => {
                committed_items.push(LanCommittedReceivedItemDto {
                    item_id: item.item_id().to_owned(),
                    item_index: u32::from(item.item_index()),
                    memo_id: memo_id.clone(),
                });
            }
            LanReceivedItemOutcome::Failed { code } => {
                failed_items.push(LanFailedReceivedItemDto {
                    item_id: item.item_id().to_owned(),
                    item_index: u32::from(item.item_index()),
                    code: code.clone(),
                });
            }
        }
    }
    LanBatchRecoveryDto {
        session_id: recovery.session_id().as_str().to_owned(),
        preview: batch_preview_to_ffi(recovery.preview()),
        decision: match recovery.decision() {
            LanReceivedBatchDecision::Pending => LanReceivedBatchDecisionDto::Pending,
            LanReceivedBatchDecision::Approved => LanReceivedBatchDecisionDto::Approved,
            LanReceivedBatchDecision::Rejected => LanReceivedBatchDecisionDto::Rejected,
        },
        drive: match recovery.drive() {
            LanReceivedBatchDrive::AwaitingDecision => LanReceivedBatchDriveDto::AwaitingDecision,
            LanReceivedBatchDrive::Receiving => LanReceivedBatchDriveDto::Receiving,
            LanReceivedBatchDrive::ReadyToCommit => LanReceivedBatchDriveDto::ReadyToCommit,
            LanReceivedBatchDrive::NeedsRebind => LanReceivedBatchDriveDto::NeedsRebind,
            LanReceivedBatchDrive::ApprovalExpired => LanReceivedBatchDriveDto::ApprovalExpired,
            LanReceivedBatchDrive::Rejected => LanReceivedBatchDriveDto::Rejected,
            LanReceivedBatchDrive::Complete => LanReceivedBatchDriveDto::Complete,
        },
        confirmed_bytes: recovery.confirmed_bytes(),
        pending_items,
        committed_items,
        failed_items,
    }
}

fn outgoing_batch_to_ffi(batch: &lomo_lan::LanOutgoingBatch) -> LanOutgoingBatchDto {
    LanOutgoingBatchDto {
        batch_id: batch.batch_id().as_str().to_owned(),
        drive: match batch.drive() {
            LanOutgoingBatchDrive::AwaitingDecision => LanOutgoingBatchDriveDto::AwaitingDecision,
            LanOutgoingBatchDrive::Sendable => LanOutgoingBatchDriveDto::Sendable,
            LanOutgoingBatchDrive::AwaitingReport => LanOutgoingBatchDriveDto::AwaitingReport,
            LanOutgoingBatchDrive::NeedsRebind => LanOutgoingBatchDriveDto::NeedsRebind,
            LanOutgoingBatchDrive::Rejected => LanOutgoingBatchDriveDto::Rejected,
            LanOutgoingBatchDrive::Failed => LanOutgoingBatchDriveDto::Failed,
            LanOutgoingBatchDrive::Complete => LanOutgoingBatchDriveDto::Complete,
        },
        failure_code: batch.failure_code().map(str::to_owned),
        confirmed_bytes: batch.confirmed_bytes(),
        total_bytes: batch.total_bytes(),
    }
}

/// Both endpoints' inputs to one pairing transcript.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanPairingTranscriptDto {
    pub initiator_public_key: Vec<u8>,
    pub initiator_display_name: String,
    pub initiator_ephemeral: Vec<u8>,
    pub responder_public_key: Vec<u8>,
    pub responder_display_name: String,
    pub responder_ephemeral: Vec<u8>,
    /// Agreed X25519 secret. Process-local for the duration of the call; never journaled.
    pub shared_secret: Vec<u8>,
}

/// Maps one durable peer record without changing its trust state.
#[must_use]
pub fn peer_to_ffi(peer: &lomo_lan::PeerRecord) -> LanPeerDto {
    LanPeerDto {
        device_id: peer.device_id().as_str().to_owned(),
        display_name: peer.display_name().as_str().to_owned(),
        public_key: peer.public_key().as_bytes().to_vec(),
        paired_at_ms: peer.paired_at_ms(),
        revoked: peer.is_revoked(),
        revoked_at_ms: peer.revoked_at_ms(),
    }
}

/// Maps the installation journal owned by the engine runtime.
#[must_use]
pub fn peer_page_from_manager(manager: &LanServiceManager) -> LanPeerPageDto {
    let peers: Vec<LanPeerDto> = manager
        .peers()
        .values()
        .take(MAX_PEER_PAGE_ITEMS)
        .map(peer_to_ffi)
        .collect();
    let total = u32::try_from(manager.peers().len()).unwrap_or(u32::MAX);
    LanPeerPageDto { peers, total }
}

/// Converts the Keystore public identity at the platform boundary.
///
/// # Errors
///
/// Validation when the public key is not P-256 or the display name violates LAN bounds.
pub fn identity_from_ffi(
    identity: &LanDeviceIdentityDto,
) -> Result<(DevicePublicKey, DisplayName), LomoError> {
    Ok((
        DevicePublicKey::parse(&identity.public_key)?,
        DisplayName::parse(&identity.display_name)?,
    ))
}

/// Converts a pairing id at the platform boundary.
///
/// # Errors
///
/// Validation when the id is not the canonical 16-byte lowercase hexadecimal form.
pub fn pairing_id_from_ffi(raw: &str) -> Result<LanPairingId, LomoError> {
    LanPairingId::parse(raw)
}

/// Maps a Rust-owned pairing challenge to the foreign DTO.
#[must_use]
pub fn pairing_challenge_to_ffi(challenge: &LanPairingChallenge) -> LanPairingChallengeDto {
    LanPairingChallengeDto {
        pairing_id: challenge.pairing_id().as_str().to_owned(),
        peer_device_id: challenge.peer_device_id().as_str().to_owned(),
        peer_display_name: challenge.peer_display_name().as_str().to_owned(),
        short_code: challenge.short_code().to_owned(),
        transcript_to_sign: challenge.transcript_to_sign().to_vec(),
        deadline_ms: challenge.deadline_ms(),
    }
}

/// Converts a session id at the platform boundary.
///
/// # Errors
///
/// Validation when the id is not the canonical 16-byte hexadecimal form.
pub fn session_id_from_ffi(raw: &str) -> Result<LanSessionId, LomoError> {
    LanSessionId::parse(raw)
}

/// Maps a Rust-owned session signing challenge without exposing its derived key.
#[must_use]
pub fn session_challenge_to_ffi(challenge: &LanSessionChallenge) -> LanSessionChallengeDto {
    LanSessionChallengeDto {
        session_id: challenge.session_id().as_str().to_owned(),
        peer_device_id: challenge.peer_device_id().as_str().to_owned(),
        transcript_to_sign: challenge.transcript_to_sign().to_vec(),
        deadline_ms: challenge.deadline_ms(),
    }
}

/// Maps authenticated public session state without exposing its derived key.
#[must_use]
pub fn session_snapshot_to_ffi(snapshot: &LanSessionSnapshot) -> LanSessionSnapshotDto {
    LanSessionSnapshotDto {
        session_id: snapshot.session_id().as_str().to_owned(),
        peer_device_id: snapshot.peer_device_id().as_str().to_owned(),
        phase: match snapshot.phase() {
            LanSessionPhase::Authenticated => LanSessionPhaseDto::Authenticated,
        },
    }
}
// ---------------------------------------------------------------------------
// P6-09 runtime conversion edge.
//
// Android publishes facts only it can observe (local-network permission, eligible interface
// addresses, NSD results) as monotonic snapshots. Every decision made from them — revision
// ordering, protocol filtering, address validation, listener bind and release — belongs to
// `lomo-lan::LanServiceManager`, which the single `LomoEngine` handle owns. These functions
// convert and nothing else.
// ---------------------------------------------------------------------------

/// One concrete local address Android says is eligible for the listener.
///
/// Port zero is meaningful: it asks the OS to choose a port.
#[data]
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct LanBindCandidateDto {
    pub host: String,
    pub port: u32,
}

/// Monotonic Android network facts.
#[data]
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct LanNetworkSnapshotDto {
    pub revision: u64,
    pub local_network_permission_granted: bool,
    pub candidates: Vec<LanBindCandidateDto>,
}

/// One endpoint Android NSD reported.
#[data]
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct LanDiscoveredPeerDto {
    pub device_id: String,
    pub display_name: String,
    pub host: String,
    pub port: u32,
    pub protocol_version: u32,
}

/// Monotonic Android NSD facts.
#[data]
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct LanDiscoverySnapshotDto {
    pub revision: u64,
    pub peers: Vec<LanDiscoveredPeerDto>,
}

/// Effective Rust-owned lifecycle phase. Named variants only; never an enum ordinal.
#[data]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum LanServicePhaseDto {
    #[default]
    Stopped,
    Listening,
}

/// Effective service state as adapters and UI see it.
#[data]
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct LanServiceSnapshotDto {
    pub phase: LanServicePhaseDto,
    pub listen_address: Option<String>,
}

/// Narrows a wire port to the socket width, failing closed rather than truncating.
fn checked_port(port: u32, code: &str) -> Result<u16, LomoError> {
    u16::try_from(port)
        .map_err(|_error| boundary_err(code, "port does not fit the 16-bit socket port width"))
}

/// Converts Android network facts into the validated owner type.
///
/// # Errors
///
/// Validation for a zero revision or a non-unicast/non-numeric address; resource-limit above the
/// candidate ceiling. Every rule lives in `lomo-lan`.
pub fn network_snapshot_from_ffi(
    snapshot: &LanNetworkSnapshotDto,
) -> Result<LanNetworkSnapshot, LomoError> {
    let mut candidates = Vec::with_capacity(snapshot.candidates.len());
    for candidate in &snapshot.candidates {
        candidates.push(LanBindCandidate::parse(
            &candidate.host,
            checked_port(candidate.port, "lan_ffi_bind_port_invalid")?,
        )?);
    }
    LanNetworkSnapshot::new(
        snapshot.revision,
        snapshot.local_network_permission_granted,
        candidates,
    )
}

/// Converts Android NSD facts into the validated owner type.
///
/// A foreign protocol version, malformed identity or non-concrete address rejects the **whole**
/// snapshot, so a partially-trusted discovery list can never become UI state.
///
/// # Errors
///
/// Validation for a zero revision, foreign protocol, malformed entry or duplicate endpoint;
/// resource-limit above the endpoint ceiling.
pub fn discovery_snapshot_from_ffi(
    snapshot: &LanDiscoverySnapshotDto,
) -> Result<LanDiscoverySnapshot, LomoError> {
    let mut peers = Vec::with_capacity(snapshot.peers.len());
    for peer in &snapshot.peers {
        peers.push(DiscoveredPeerEndpoint::parse(
            &peer.device_id,
            &peer.display_name,
            &peer.host,
            checked_port(peer.port, "lan_ffi_discovery_port_invalid")?,
            checked_port(peer.protocol_version, "lan_ffi_protocol_version_invalid")?,
        )?);
    }
    LanDiscoverySnapshot::new(snapshot.revision, peers)
}

/// Renders the effective service state for adapters.
#[must_use]
pub fn service_snapshot_to_ffi(snapshot: &LanServiceSnapshot) -> LanServiceSnapshotDto {
    LanServiceSnapshotDto {
        phase: match snapshot.phase() {
            LanServicePhase::Stopped => LanServicePhaseDto::Stopped,
            LanServicePhase::Listening => LanServicePhaseDto::Listening,
        },
        listen_address: snapshot.listen_address(),
    }
}

/// Renders one Rust-validated discovery endpoint back to the wire.
#[must_use]
pub fn discovered_peer_to_ffi(peer: &DiscoveredPeerEndpoint) -> LanDiscoveredPeerDto {
    let address = peer.address();
    LanDiscoveredPeerDto {
        device_id: peer.device_id().as_str().to_owned(),
        display_name: peer.display_name().as_str().to_owned(),
        host: address.ip().to_string(),
        port: u32::from(address.port()),
        protocol_version: u32::from(LAN_PROTOCOL_VERSION),
    }
}
