//! Stage-6 LAN FFI conversion surface (P6-08).
//!
//! Conversion-only mapping between `BoltFFI` DTOs and `lomo-lan`. Every business rule — transcript
//! construction, short-code derivation, signature verification, batch limits, preview containment,
//! approval durability — stays in `lomo-lan`; this module only parses wire types, delegates, and
//! maps results back.
//!
//! Deliberately **not** registered in production DI. LAN production remains the Kotlin wire until
//! the P6-10 cutover, and every function here is reachable only from host contracts.
//!
//! Only operations with real `lomo-lan` behavior behind them are exported. Session lifecycle
//! (`start_lan_service` / `stop_lan_service`) and live batch queries need a session manager that
//! has not landed; adding placeholder exports for them would be exactly the `NoOp` surface the
//! repository forbids, so they stay absent until the manager exists.

use boltffi::{data, export};
use lomo_core::{ErrorCategory, LomoError, RetryDisposition};
use lomo_lan::{
    DeviceId, DevicePublicKey, DisplayName, LanApproval, LanAttachmentRef, LanBatchId,
    LanBatchPlan, LanItemPlan, LanJournal, LanJournalPaths, MAX_BATCH_ITEMS, PairingTranscript,
    derive_pairing_code, verify_pairing_confirmation,
};

use crate::EngineError;

/// Maximum UTF-8 bytes accepted for any path-shaped wire argument.
const MAX_PATH_BYTES: usize = 4_096;

/// Maximum peers returned in one page (mirrors the durable registry ceiling).
const MAX_PEER_PAGE_ITEMS: usize = 64;

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

fn checked_root(journal_root: &str) -> Result<LanJournalPaths, LomoError> {
    if journal_root.is_empty() || journal_root.len() > MAX_PATH_BYTES {
        return Err(boundary_err(
            "lan_ffi_journal_root_invalid",
            "journal_root must be 1..=4096 bytes",
        ));
    }
    LanJournalPaths::new(journal_root)
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

/// One attachment reference in a send request.
#[data]
#[derive(Clone, Debug, Default)]
pub struct LanAttachmentDto {
    pub slot: u32,
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

fn build_transcript(wire: &LanPairingTranscriptDto) -> Result<PairingTranscript, LomoError> {
    PairingTranscript::build(
        &DevicePublicKey::parse(&wire.initiator_public_key)?,
        &DisplayName::parse(&wire.initiator_display_name)?,
        &wire.initiator_ephemeral,
        &DevicePublicKey::parse(&wire.responder_public_key)?,
        &DisplayName::parse(&wire.responder_display_name)?,
        &wire.responder_ephemeral,
        &wire.shared_secret,
    )
}

fn peer_page(journal: &LanJournal) -> LanPeerPageDto {
    let peers: Vec<LanPeerDto> = journal
        .peers()
        .values()
        .take(MAX_PEER_PAGE_ITEMS)
        .map(|peer| LanPeerDto {
            device_id: peer.device_id().as_str().to_owned(),
            display_name: peer.display_name().as_str().to_owned(),
            public_key: peer.public_key().as_bytes().to_vec(),
            paired_at_ms: peer.paired_at_ms(),
            revoked: peer.is_revoked(),
            revoked_at_ms: peer.revoked_at_ms(),
        })
        .collect();
    let total = u32::try_from(journal.peers().len()).unwrap_or(u32::MAX);
    LanPeerPageDto { peers, total }
}

/// Derives the short authentication code both users compare during pairing.
///
/// # Errors
///
/// Validation when any key, name, ephemeral point or the shared secret is malformed.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned wire types"
)]
pub fn lan_pairing_short_code(transcript: LanPairingTranscriptDto) -> Result<String, EngineError> {
    let built = build_transcript(&transcript).map_err(EngineError::from)?;
    Ok(derive_pairing_code(&built))
}

/// Verifies a pairing confirmation and stores the peer durably.
///
/// The signature must verify over **this endpoint's** transcript under the claimed device key, so a
/// substituted key or a foreign transcript stores nothing.
///
/// # Errors
///
/// Validation for malformed inputs, authentication when the confirmation does not verify, storage
/// when the durable write fails.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned wire types"
)]
pub fn lan_confirm_pairing(
    journal_root: String,
    transcript: LanPairingTranscriptDto,
    peer_public_key: Vec<u8>,
    peer_display_name: String,
    signature: Vec<u8>,
    paired_at_ms: i64,
) -> Result<LanPeerPageDto, EngineError> {
    let paths = checked_root(&journal_root).map_err(EngineError::from)?;
    let built = build_transcript(&transcript).map_err(EngineError::from)?;
    let peer = verify_pairing_confirmation(
        &built,
        &DevicePublicKey::parse(&peer_public_key).map_err(EngineError::from)?,
        &DisplayName::parse(&peer_display_name).map_err(EngineError::from)?,
        &signature,
        paired_at_ms,
    )
    .map_err(EngineError::from)?;

    let mut journal = LanJournal::open(paths).map_err(EngineError::from)?;
    journal.store_peer(peer).map_err(EngineError::from)?;
    Ok(peer_page(&journal))
}

/// Lists trusted peers.
///
/// # Errors
///
/// Validation for a malformed root, corruption when a durable record fails its checksum.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned wire types"
)]
pub fn lan_list_peers(journal_root: String) -> Result<LanPeerPageDto, EngineError> {
    let paths = checked_root(&journal_root).map_err(EngineError::from)?;
    let journal = LanJournal::open(paths).map_err(EngineError::from)?;
    Ok(peer_page(&journal))
}

/// Revokes a peer and returns the updated registry.
///
/// The record is retained in revoked form so a later connection is refused explicitly rather than
/// treated as an unknown device.
///
/// # Errors
///
/// Validation when the device id is malformed or was never paired; storage on write failure.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned wire types"
)]
pub fn lan_revoke_peer(
    journal_root: String,
    device_id: String,
    revoked_at_ms: i64,
) -> Result<LanPeerPageDto, EngineError> {
    let paths = checked_root(&journal_root).map_err(EngineError::from)?;
    let parsed = DeviceId::parse(&device_id).map_err(EngineError::from)?;
    let mut journal = LanJournal::open(paths).map_err(EngineError::from)?;
    journal
        .revoke_peer(&parsed, revoked_at_ms)
        .map_err(EngineError::from)?;
    Ok(peer_page(&journal))
}

/// Validates a send request against every LAN v2 limit and returns the bounded approval preview.
///
/// Rejection happens **before** any transfer starts, so an over-limit batch never opens a socket.
/// The preview is derived from plan metadata, so it structurally cannot carry a body.
///
/// # Errors
///
/// Resource-limit when the item count, total bytes or any attachment exceeds its ceiling;
/// validation for malformed identifiers, digests or names.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned wire types"
)]
pub fn lan_prepare_send(
    batch_id: String,
    sender_device_id: String,
    sender_display_name: String,
    items: Vec<LanSendItemDto>,
) -> Result<LanBatchPreviewDto, EngineError> {
    if items.len() > MAX_BATCH_ITEMS {
        // Reject before parsing every item so an oversized request is cheap to refuse.
        return Err(EngineError::from(
            LomoError::from_platform_boundary(
                ErrorCategory::ResourceLimit,
                "lan_batch_too_many_items",
                RetryDisposition::Never,
                None,
                None,
                "batch exceeds the 100-item LAN ceiling; use a workspace archive instead",
            )
            .unwrap_or_else(|error| error),
        ));
    }

    let parsed_batch = LanBatchId::parse(&batch_id).map_err(EngineError::from)?;
    let mut plans = Vec::with_capacity(items.len());
    for (position, item) in items.iter().enumerate() {
        let index = u16::try_from(position).map_err(|_error| {
            EngineError::from(boundary_err(
                "lan_ffi_item_index_invalid",
                "batch item index does not fit the wire index width",
            ))
        })?;
        let mut attachments = Vec::with_capacity(item.attachments.len());
        for attachment in &item.attachments {
            let slot = u16::try_from(attachment.slot).map_err(|_error| {
                EngineError::from(boundary_err(
                    "lan_ffi_attachment_slot_invalid",
                    "attachment slot does not fit the wire slot width",
                ))
            })?;
            attachments.push(
                LanAttachmentRef::new(
                    slot,
                    &attachment.name,
                    &attachment.digest,
                    attachment.size_bytes,
                )
                .map_err(EngineError::from)?,
            );
        }
        plans.push(
            LanItemPlan::new(
                &parsed_batch,
                index,
                item.timestamp_ms,
                &item.content_digest,
                item.content_bytes,
                &item.title,
                attachments,
            )
            .map_err(EngineError::from)?,
        );
    }

    let plan = LanBatchPlan::new(parsed_batch, plans).map_err(EngineError::from)?;
    let device_id = DeviceId::parse(&sender_device_id).map_err(EngineError::from)?;
    let display_name = DisplayName::parse(&sender_display_name).map_err(EngineError::from)?;
    let preview = plan.preview(&device_id, &display_name);

    Ok(LanBatchPreviewDto {
        batch_id: preview.batch_id().as_str().to_owned(),
        sender_device_id: preview.sender_device_id().as_str().to_owned(),
        sender_display_name: preview.sender_name().as_str().to_owned(),
        item_count: u32::try_from(preview.item_count()).unwrap_or(u32::MAX),
        attachment_count: u32::try_from(preview.attachment_count()).unwrap_or(u32::MAX),
        total_bytes: preview.total_bytes(),
        titles: preview.titles().to_vec(),
    })
}

/// Records a durable batch approval with a time-to-live.
///
/// Recovery inside the TTL resumes without asking the user again; past it, re-approval is required.
///
/// # Errors
///
/// Validation for a malformed root or batch id, storage on write failure.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned wire types"
)]
pub fn lan_approve_receive(
    journal_root: String,
    batch_id: String,
    approved_at_ms: i64,
    ttl_ms: i64,
) -> Result<(), EngineError> {
    if ttl_ms <= 0 {
        return Err(EngineError::from(boundary_err(
            "lan_ffi_approval_ttl_invalid",
            "approval time-to-live must be positive",
        )));
    }
    let paths = checked_root(&journal_root).map_err(EngineError::from)?;
    let parsed = LanBatchId::parse(&batch_id).map_err(EngineError::from)?;
    let mut journal = LanJournal::open(paths).map_err(EngineError::from)?;
    journal
        .store_approval(LanApproval::granted(parsed, approved_at_ms, ttl_ms))
        .map_err(EngineError::from)
}

/// Reports whether an approval currently authorizes a batch.
///
/// # Errors
///
/// Validation for a malformed root or batch id; permission when the recorded approval is expired.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned wire types"
)]
pub fn lan_approval_is_valid(
    journal_root: String,
    batch_id: String,
    now_ms: i64,
) -> Result<bool, EngineError> {
    let paths = checked_root(&journal_root).map_err(EngineError::from)?;
    let parsed = LanBatchId::parse(&batch_id).map_err(EngineError::from)?;
    let journal = LanJournal::open(paths).map_err(EngineError::from)?;
    Ok(journal
        .approval(&parsed)
        .is_some_and(|approval| approval.assert_valid_at(now_ms).is_ok()))
}

/// Reports the chunk indices a resumed transfer must still send.
///
/// # Errors
///
/// Validation for a malformed root, session id or batch id.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned wire types"
)]
pub fn lan_unconfirmed_chunks(
    journal_root: String,
    session_id: String,
    batch_id: String,
    item_index: u32,
    attachment_slot: u32,
    total_chunks: u32,
) -> Result<Vec<u32>, EngineError> {
    let paths = checked_root(&journal_root).map_err(EngineError::from)?;
    let session = lomo_lan::LanSessionId::parse(&session_id).map_err(EngineError::from)?;
    let batch = LanBatchId::parse(&batch_id).map_err(EngineError::from)?;
    let item = u16::try_from(item_index).map_err(|_error| {
        EngineError::from(boundary_err(
            "lan_ffi_item_index_invalid",
            "item index does not fit the wire index width",
        ))
    })?;
    let slot = u16::try_from(attachment_slot).map_err(|_error| {
        EngineError::from(boundary_err(
            "lan_ffi_attachment_slot_invalid",
            "attachment slot does not fit the wire slot width",
        ))
    })?;
    let journal = LanJournal::open(paths).map_err(EngineError::from)?;
    Ok(journal.unconfirmed_chunk_indices(&session, &batch, item, slot, total_chunks))
}
