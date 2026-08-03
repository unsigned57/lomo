//! Durable app-private LAN journal: trusted peers, approvals and confirmed chunk ranges.
//!
//! This tree lives in the **app-private** directory, never in `.lomo`. LAN peer trust belongs to
//! the device installation, so it is never synced, never archived, and never rebuilt from a
//! workspace.
//!
//! Every record is `magic | schema | length | crc | body`, written temp-then-rename so a crash
//! leaves either the previous record or the new one, never a half record. A record whose magic,
//! schema or checksum does not match fails closed as `CorruptState`; it is never silently dropped
//! or reset to an empty set, because doing so would silently un-trust a peer or re-request an
//! approval the user already gave.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

use crate::batch::{
    LanApproval, LanAttachmentRef, LanBatchDecision, LanBatchId, LanBatchPlan, LanBatchSnapshot,
    LanDurableBatch, LanItemOutcome, LanItemPlan,
};
use crate::commit::ApprovedGeneration;
use crate::error::{authentication, corrupt_state, resource_limit, storage, validation};
use crate::identity::{DeviceId, DevicePublicKey, DisplayName, PeerRecord};
use crate::limits::{
    CHUNK_PLAINTEXT_BYTES, LAN_DURABLE_SCHEMA, MAX_BATCH_TOTAL_BYTES, MAX_LAN_RECORD_BYTES,
    MAX_TRUSTED_PEERS,
};
use crate::session::{ChunkBinding, LanSessionId};
use lomo_core::LomoError;

/// Magic marking a Lomo LAN durable record.
pub const LAN_RECORD_MAGIC: [u8; 4] = *b"LMLJ";

/// Header length: magic(4) + schema(4) + length(4) + digest(32).
const RECORD_HEADER_BYTES: usize = 44;

/// Encodes a record body with magic, schema, length and a SHA-256 checksum.
///
/// # Errors
///
/// Resource-limit when the body exceeds the durable record ceiling.
pub fn encode_record(body: &[u8]) -> Result<Vec<u8>, LomoError> {
    if body.len() > MAX_LAN_RECORD_BYTES {
        return Err(resource_limit(
            "lan_record_too_large",
            "durable LAN record exceeds the 256 KiB ceiling",
        ));
    }
    let mut bytes = Vec::with_capacity(RECORD_HEADER_BYTES + body.len());
    bytes.extend_from_slice(&LAN_RECORD_MAGIC);
    bytes.extend_from_slice(&LAN_DURABLE_SCHEMA.to_be_bytes());
    let length = u32::try_from(body.len()).unwrap_or(u32::MAX);
    bytes.extend_from_slice(&length.to_be_bytes());
    bytes.extend_from_slice(&Sha256::digest(body));
    bytes.extend_from_slice(body);
    Ok(bytes)
}

/// Decodes a record body, failing closed on magic, schema, length or checksum mismatch.
///
/// # Errors
///
/// Corruption for any header or checksum mismatch; resource-limit for an oversized declared length.
pub fn decode_record(bytes: &[u8]) -> Result<Vec<u8>, LomoError> {
    let header = bytes.get(0..RECORD_HEADER_BYTES).ok_or_else(|| {
        corrupt_state("lan_record_truncated", "durable LAN record header is short")
    })?;
    if header.get(0..4) != Some(&LAN_RECORD_MAGIC[..]) {
        return Err(corrupt_state(
            "lan_record_bad_magic",
            "durable LAN record magic does not match",
        ));
    }
    let schema = be_u32(header, 4)?;
    if schema != LAN_DURABLE_SCHEMA {
        return Err(corrupt_state(
            "lan_record_unknown_schema",
            "durable LAN record schema is not readable by this build",
        ));
    }
    let declared = be_u32(header, 8)? as usize;
    if declared > MAX_LAN_RECORD_BYTES {
        return Err(resource_limit(
            "lan_record_too_large",
            "declared durable LAN record length exceeds the ceiling",
        ));
    }
    let expected_digest = header.get(12..RECORD_HEADER_BYTES).ok_or_else(|| {
        corrupt_state("lan_record_truncated", "durable LAN record header is short")
    })?;
    let end = RECORD_HEADER_BYTES.checked_add(declared).ok_or_else(|| {
        corrupt_state(
            "lan_record_truncated",
            "durable LAN record length overflows",
        )
    })?;
    let body = bytes
        .get(RECORD_HEADER_BYTES..end)
        .ok_or_else(|| corrupt_state("lan_record_truncated", "durable LAN record body is short"))?;
    if Sha256::digest(body).as_slice() != expected_digest {
        return Err(corrupt_state(
            "lan_record_checksum_mismatch",
            "durable LAN record checksum does not match its body",
        ));
    }
    Ok(body.to_vec())
}

/// Paths of the app-private LAN journal tree.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanJournalPaths {
    root: PathBuf,
}

impl LanJournalPaths {
    /// Builds the journal paths under an app-private root.
    ///
    /// # Errors
    ///
    /// Validation when the root is inside a `.lomo` workspace control tree, which would make peer
    /// trust syncable or archivable.
    pub fn new(app_private_root: impl AsRef<Path>) -> Result<Self, LomoError> {
        let root = app_private_root.as_ref().join("lan").join("v1");
        if root
            .components()
            .any(|component| component.as_os_str() == ".lomo")
        {
            return Err(validation(
                "lan_journal_root_invalid",
                "LAN journal must live in the app-private tree, never under .lomo",
            ));
        }
        Ok(Self { root })
    }

    #[must_use]
    pub fn root(&self) -> &Path {
        &self.root
    }

    #[must_use]
    pub fn peers(&self) -> PathBuf {
        self.root.join("peers.rec")
    }

    #[must_use]
    pub fn approvals(&self) -> PathBuf {
        self.root.join("approvals.rec")
    }

    #[must_use]
    pub fn sessions(&self) -> PathBuf {
        self.root.join("sessions.rec")
    }

    #[must_use]
    pub fn batches(&self) -> PathBuf {
        self.root.join("batches.rec")
    }

    #[must_use]
    pub fn outgoing_batches(&self) -> PathBuf {
        self.root.join("outgoing.rec")
    }

    #[must_use]
    pub fn confirmed_chunks(&self) -> PathBuf {
        self.root.join("chunks.rec")
    }

    fn staged_chunk(&self, coordinate: &DurableChunkCoordinate) -> PathBuf {
        self.root
            .join("payloads")
            .join(&coordinate.batch_id)
            .join(format!(
                "{}-{}",
                coordinate.item_index, coordinate.attachment_slot
            ))
            .join(format!("{}.chunk", coordinate.chunk_index))
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct DurableChunkCoordinate {
    batch_id: String,
    item_index: u16,
    attachment_slot: u16,
    chunk_index: u32,
}

impl From<&ChunkBinding> for DurableChunkCoordinate {
    fn from(binding: &ChunkBinding) -> Self {
        Self {
            batch_id: binding.batch_id().to_owned(),
            item_index: binding.item_index(),
            attachment_slot: binding.attachment_slot(),
            chunk_index: binding.chunk_index(),
        }
    }
}

/// The durable LAN journal.
#[derive(Clone, Debug)]
pub struct LanJournal {
    paths: LanJournalPaths,
    peers: BTreeMap<DeviceId, PeerRecord>,
    sessions: BTreeSet<LanSessionId>,
    batches: BTreeMap<LanBatchId, LanDurableBatch>,
    outgoing_batches: BTreeMap<LanBatchId, LanDurableOutgoingBatch>,
    approvals: BTreeMap<LanBatchId, LanApproval>,
    confirmed: Vec<DurableChunkCoordinate>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LanOutgoingDecision {
    AwaitingApproval,
    Approved,
    Rejected,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanDurableOutgoingBatch {
    plan: LanBatchPlan,
    session_id: LanSessionId,
    peer_device_id: DeviceId,
    peer_display_name: DisplayName,
    decision: LanOutgoingDecision,
    confirmed: BTreeSet<(u16, u16, u32)>,
    snapshot: LanBatchSnapshot,
}

impl LanDurableOutgoingBatch {
    pub(crate) fn new(
        plan: LanBatchPlan,
        session_id: LanSessionId,
        peer_device_id: DeviceId,
        peer_display_name: DisplayName,
    ) -> Self {
        let snapshot = LanBatchSnapshot::pending(&plan);
        Self {
            plan,
            session_id,
            peer_device_id,
            peer_display_name,
            decision: LanOutgoingDecision::AwaitingApproval,
            confirmed: BTreeSet::new(),
            snapshot,
        }
    }

    pub(crate) const fn plan(&self) -> &LanBatchPlan {
        &self.plan
    }

    pub(crate) const fn session_id(&self) -> &LanSessionId {
        &self.session_id
    }

    pub(crate) const fn decision(&self) -> LanOutgoingDecision {
        self.decision
    }

    pub(crate) fn unconfirmed_chunk_indices(
        &self,
        item_index: u16,
        attachment_slot: u16,
        total_chunks: u32,
    ) -> Vec<u32> {
        (0..total_chunks)
            .filter(|chunk_index| {
                !self
                    .confirmed
                    .contains(&(item_index, attachment_slot, *chunk_index))
            })
            .collect()
    }
}

impl LanJournal {
    /// Opens (or initializes) the journal, failing closed on any corrupt record.
    ///
    /// # Errors
    ///
    /// Storage on I/O failure; corruption when a record fails its header or checksum check.
    pub fn open(paths: LanJournalPaths) -> Result<Self, LomoError> {
        fs::create_dir_all(paths.root()).map_err(|error| {
            storage(
                "lan_journal_create_failed",
                &format!("cannot create the LAN journal directory: {error}"),
            )
        })?;
        let peers = read_peers(&paths.peers())?;
        let sessions = read_sessions(&paths.sessions())?;
        let batches = read_batches(&paths.batches())?;
        let outgoing_batches = read_outgoing_batches(&paths.outgoing_batches())?;
        let approvals = read_approvals(&paths.approvals())?;
        let confirmed = read_confirmed(&paths.confirmed_chunks())?;
        Ok(Self {
            paths,
            peers,
            sessions,
            batches,
            outgoing_batches,
            approvals,
            confirmed,
        })
    }

    /// Trusted peers by device id.
    #[must_use]
    pub const fn peers(&self) -> &BTreeMap<DeviceId, PeerRecord> {
        &self.peers
    }

    /// Accepts a fresh session identity exactly once across process restarts.
    ///
    /// # Errors
    ///
    /// Authentication when the id was already accepted; storage when durability fails.
    pub fn accept_session(&mut self, session_id: &LanSessionId) -> Result<(), LomoError> {
        if self.sessions.contains(session_id) {
            return Err(authentication(
                "lan_session_replayed",
                "session id was already used and may not be replayed",
            ));
        }
        self.sessions.insert(session_id.clone());
        if let Err(error) = self.flush_sessions() {
            self.sessions.remove(session_id);
            return Err(error);
        }
        Ok(())
    }

    /// True when recovery refers to a session that was previously authenticated.
    #[must_use]
    pub fn has_session(&self, session_id: &LanSessionId) -> bool {
        self.sessions.contains(session_id)
    }

    /// Stores complete pending recovery state before exposing its approval preview.
    ///
    /// # Errors
    ///
    /// Storage/resource-limit when the checksummed batch record cannot be persisted.
    pub fn store_batch(&mut self, batch: LanDurableBatch) -> Result<(), LomoError> {
        let batch_id = batch.plan().batch_id().clone();
        let previous = self.batches.insert(batch_id.clone(), batch);
        if let Err(error) = self.flush_batches() {
            restore_map_entry(&mut self.batches, batch_id, previous);
            return Err(error);
        }
        Ok(())
    }

    /// Complete recovery state for a batch.
    #[must_use]
    pub fn batch(&self, batch_id: &LanBatchId) -> Option<&LanDurableBatch> {
        self.batches.get(batch_id)
    }

    pub(crate) fn batches(&self) -> impl Iterator<Item = &LanDurableBatch> {
        self.batches.values()
    }

    pub(crate) fn outgoing_batches(&self) -> impl Iterator<Item = &LanDurableOutgoingBatch> {
        self.outgoing_batches.values()
    }

    pub(crate) fn outgoing_batch(&self, batch_id: &LanBatchId) -> Option<&LanDurableOutgoingBatch> {
        self.outgoing_batches.get(batch_id)
    }

    pub(crate) fn store_outgoing_batch(
        &mut self,
        batch: LanDurableOutgoingBatch,
    ) -> Result<(), LomoError> {
        let batch_id = batch.plan.batch_id().clone();
        if let Some(existing) = self.outgoing_batches.get(&batch_id) {
            if existing.plan == batch.plan
                && existing.peer_device_id == batch.peer_device_id
                && existing.peer_display_name == batch.peer_display_name
            {
                let mut rebound = existing.clone();
                rebound.session_id = batch.session_id;
                self.outgoing_batches.insert(batch_id.clone(), rebound);
                self.flush_outgoing_batches()?;
                return Ok(());
            }
            return Err(crate::error::conflict(
                "lan_outgoing_batch_replayed_with_different_plan",
                "outgoing batch id was reused with different durable facts",
            ));
        }
        self.outgoing_batches.insert(batch_id.clone(), batch);
        if let Err(error) = self.flush_outgoing_batches() {
            self.outgoing_batches.remove(&batch_id);
            return Err(error);
        }
        Ok(())
    }

    pub(crate) fn approve_outgoing_batch(
        &mut self,
        batch_id: &LanBatchId,
    ) -> Result<(), LomoError> {
        self.mutate_outgoing_batch(batch_id, |batch| match batch.decision {
            LanOutgoingDecision::AwaitingApproval | LanOutgoingDecision::Approved => {
                batch.decision = LanOutgoingDecision::Approved;
                Ok(())
            }
            LanOutgoingDecision::Rejected => Err(crate::error::conflict(
                "lan_batch_decision_terminal",
                "a rejected outgoing batch cannot become approved",
            )),
        })
    }

    pub(crate) fn reject_outgoing_batch(&mut self, batch_id: &LanBatchId) -> Result<(), LomoError> {
        self.mutate_outgoing_batch(batch_id, |batch| match batch.decision {
            LanOutgoingDecision::AwaitingApproval | LanOutgoingDecision::Rejected => {
                batch.decision = LanOutgoingDecision::Rejected;
                Ok(())
            }
            LanOutgoingDecision::Approved => Err(crate::error::conflict(
                "lan_batch_decision_terminal",
                "an approved outgoing batch cannot become rejected",
            )),
        })
    }

    pub(crate) fn update_outgoing_batch_status(
        &mut self,
        batch_id: &LanBatchId,
        session_id: &LanSessionId,
        peer_device_id: &DeviceId,
        decision: LanOutgoingDecision,
        confirmed: BTreeSet<(u16, u16, u32)>,
        outcomes: &[LanItemOutcome],
    ) -> Result<(), LomoError> {
        self.mutate_outgoing_batch(batch_id, |batch| {
            if batch.session_id != *session_id || batch.peer_device_id != *peer_device_id {
                return Err(crate::error::permission(
                    "lan_batch_session_mismatch",
                    "remote batch status does not belong to the outgoing batch session and peer",
                ));
            }
            batch.decision = match (batch.decision, decision) {
                (current, remote) if current == remote => current,
                (LanOutgoingDecision::AwaitingApproval, remote) => remote,
                _ => {
                    return Err(crate::error::conflict(
                        "lan_outgoing_status_regressed",
                        "remote batch decision conflicts with durable outgoing state",
                    ));
                }
            };
            if !batch.confirmed.is_subset(&confirmed) {
                return Err(crate::error::conflict(
                    "lan_outgoing_status_regressed",
                    "remote confirmed chunks moved behind durable outgoing state",
                ));
            }
            if outcomes.len() != batch.plan.items().len() {
                return Err(validation(
                    "lan_batch_status_invalid",
                    "remote item outcomes do not cover the outgoing plan",
                ));
            }
            for (item, outcome) in batch.plan.items().iter().zip(outcomes) {
                let current = batch.snapshot.outcome(item.item_id()).ok_or_else(|| {
                    validation(
                        "lan_item_outcome_missing",
                        "outgoing batch item has no durable outcome",
                    )
                })?;
                if current.is_terminal() && matches!(outcome, LanItemOutcome::Pending) {
                    return Err(crate::error::conflict(
                        "lan_outgoing_status_regressed",
                        "remote item outcome moved behind durable outgoing state",
                    ));
                }
                if matches!(current, LanItemOutcome::Committed { .. }) && current != outcome {
                    return Err(crate::error::conflict(
                        "lan_outgoing_status_regressed",
                        "remote committed item result changed after durability",
                    ));
                }
                batch.snapshot.record(item.item_id(), outcome.clone())?;
            }
            batch.confirmed = confirmed;
            Ok(())
        })
    }

    /// Durably binds approval to the active workspace generation.
    ///
    /// # Errors
    ///
    /// Validation for an unknown batch; permission for a foreign approval; storage on write.
    pub fn approve_batch(
        &mut self,
        batch_id: &LanBatchId,
        approval: LanApproval,
        generation: ApprovedGeneration,
    ) -> Result<(), LomoError> {
        self.mutate_batch(batch_id, |batch| batch.approve(approval, generation))
    }

    /// Durably records a terminal user rejection.
    ///
    /// # Errors
    ///
    /// Validation for an unknown batch; conflict for a different terminal decision; storage on
    /// write.
    pub fn reject_batch(
        &mut self,
        batch_id: &LanBatchId,
        rejected_at_ms: i64,
    ) -> Result<(), LomoError> {
        self.mutate_batch(batch_id, |batch| batch.reject(rejected_at_ms))
    }

    /// Durably records one per-item result without changing committed siblings.
    ///
    /// # Errors
    ///
    /// Validation for unknown batch/item; storage when persistence fails.
    pub fn record_batch_outcome(
        &mut self,
        batch_id: &LanBatchId,
        item_id: &crate::batch::LanItemId,
        outcome: LanItemOutcome,
    ) -> Result<LanItemOutcome, LomoError> {
        let mut effective = None;
        self.mutate_batch(batch_id, |batch| {
            effective = Some(batch.record(item_id, outcome)?);
            Ok(())
        })?;
        effective.ok_or_else(|| {
            validation(
                "lan_batch_outcome_missing",
                "batch outcome mutation produced no effective result",
            )
        })
    }

    pub(crate) fn rebind_batch_session(
        &mut self,
        batch_id: &LanBatchId,
        session_id: LanSessionId,
    ) -> Result<(), LomoError> {
        self.mutate_batch(batch_id, |batch| {
            batch.rebind_session(session_id);
            Ok(())
        })
    }

    /// Stores a paired peer.
    ///
    /// # Errors
    ///
    /// Resource-limit when the registry is full; storage on write failure.
    pub fn store_peer(&mut self, peer: PeerRecord) -> Result<(), LomoError> {
        if !self.peers.contains_key(peer.device_id()) && self.peers.len() >= MAX_TRUSTED_PEERS {
            return Err(resource_limit(
                "lan_peer_registry_full",
                "trusted peer registry is full; revoke a peer before pairing another",
            ));
        }
        self.peers.insert(peer.device_id().clone(), peer);
        self.flush_peers()
    }

    /// Revokes a peer, keeping the record so later connections are refused explicitly.
    ///
    /// # Errors
    ///
    /// Validation when the peer is unknown; storage on write failure.
    pub fn revoke_peer(
        &mut self,
        device_id: &DeviceId,
        revoked_at_ms: i64,
    ) -> Result<(), LomoError> {
        let Some(existing) = self.peers.get(device_id) else {
            return Err(validation(
                "lan_peer_unknown",
                "cannot revoke a device that is not a trusted peer",
            ));
        };
        let revoked = existing.revoked(revoked_at_ms);
        self.peers.insert(device_id.clone(), revoked);
        self.flush_peers()
    }

    /// Records a batch approval.
    ///
    /// # Errors
    ///
    /// Storage on write failure.
    pub fn store_approval(&mut self, approval: LanApproval) -> Result<(), LomoError> {
        self.approvals.insert(approval.batch_id().clone(), approval);
        self.flush_approvals()
    }

    /// The approval for a batch, if one was recorded.
    #[must_use]
    pub fn approval(&self, batch_id: &LanBatchId) -> Option<&LanApproval> {
        self.approvals.get(batch_id)
    }

    /// Records a confirmed chunk so recovery does not retransmit it.
    ///
    /// Confirming an already-confirmed chunk is idempotent, which is what a resumed transfer does
    /// when it replays the tail of its send window.
    ///
    /// # Errors
    ///
    /// Storage on write failure.
    pub fn confirm_chunk(&mut self, binding: &ChunkBinding) -> Result<(), LomoError> {
        let coordinate = DurableChunkCoordinate::from(binding);
        if !self.confirmed.contains(&coordinate) {
            self.confirmed.push(coordinate);
            self.flush_confirmed()?;
        }
        Ok(())
    }

    /// Persists one authenticated plaintext chunk before its confirmation is journaled.
    ///
    /// An identical retry is idempotent. Different bytes under the same cryptographic binding are
    /// a replay violation and never replace the first durable value.
    ///
    /// # Errors
    ///
    /// Validation/resource-limit for an empty or oversized chunk; authentication for a changed
    /// replay; storage on I/O failure.
    pub fn stage_chunk(
        &mut self,
        binding: &ChunkBinding,
        plaintext: &[u8],
    ) -> Result<(), LomoError> {
        if plaintext.is_empty() {
            return Err(validation(
                "lan_chunk_empty",
                "a transferred chunk must contain at least one plaintext byte",
            ));
        }
        if plaintext.len() > CHUNK_PLAINTEXT_BYTES {
            return Err(resource_limit(
                "lan_chunk_too_large",
                "plaintext chunk exceeds the fixed LAN chunk ceiling",
            ));
        }
        let coordinate = DurableChunkCoordinate::from(binding);
        let path = self.paths.staged_chunk(&coordinate);
        match fs::read(&path) {
            Ok(existing) if existing == plaintext => return Ok(()),
            Ok(_existing) => {
                return Err(authentication(
                    "lan_chunk_replayed_with_different_bytes",
                    "a confirmed chunk binding was replayed with different plaintext bytes",
                ));
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => {
                return Err(storage(
                    "lan_chunk_stage_read_failed",
                    &format!("cannot inspect a staged LAN chunk: {error}"),
                ));
            }
        }
        let parent = path.parent().ok_or_else(|| {
            storage(
                "lan_chunk_stage_path_invalid",
                "staged LAN chunk path has no parent directory",
            )
        })?;
        fs::create_dir_all(parent).map_err(|error| {
            storage(
                "lan_chunk_stage_create_failed",
                &format!("cannot create the staged LAN chunk directory: {error}"),
            )
        })?;
        let temp = path.with_extension("chunk.tmp");
        fs::write(&temp, plaintext).map_err(|error| {
            storage(
                "lan_chunk_stage_write_failed",
                &format!("cannot write a staged LAN chunk: {error}"),
            )
        })?;
        fs::rename(&temp, &path).map_err(|error| {
            storage(
                "lan_chunk_stage_commit_failed",
                &format!("cannot commit a staged LAN chunk: {error}"),
            )
        })
    }

    /// Reassembles a payload only when every requested chunk is durably confirmed.
    ///
    /// # Errors
    ///
    /// Resource-limit when the requested range or assembled bytes exceed the batch ceiling;
    /// corruption when a confirmed chunk file is absent; storage on I/O failure.
    pub fn read_confirmed_payload(
        &self,
        batch_id: &LanBatchId,
        item_index: u16,
        attachment_slot: u16,
        total_chunks: u32,
    ) -> Result<Option<Vec<u8>>, LomoError> {
        let max_chunks = MAX_BATCH_TOTAL_BYTES.div_ceil(CHUNK_PLAINTEXT_BYTES as u64);
        if u64::from(total_chunks) > max_chunks {
            return Err(resource_limit(
                "lan_chunk_range_too_large",
                "payload chunk range exceeds the maximum LAN batch size",
            ));
        }
        let mut payload = Vec::new();
        for chunk_index in 0..total_chunks {
            let coordinate = DurableChunkCoordinate {
                batch_id: batch_id.as_str().to_owned(),
                item_index,
                attachment_slot,
                chunk_index,
            };
            if !self.confirmed.contains(&coordinate) {
                return Ok(None);
            }
            let chunk = fs::read(self.paths.staged_chunk(&coordinate)).map_err(|error| {
                if error.kind() == std::io::ErrorKind::NotFound {
                    return corrupt_state(
                        "lan_confirmed_chunk_missing",
                        "confirmed LAN chunk bytes are missing from staging",
                    );
                }
                storage(
                    "lan_chunk_stage_read_failed",
                    &format!("cannot read a staged LAN chunk: {error}"),
                )
            })?;
            let assembled = payload.len().saturating_add(chunk.len());
            if u64::try_from(assembled).unwrap_or(u64::MAX) > MAX_BATCH_TOTAL_BYTES {
                return Err(resource_limit(
                    "lan_payload_too_large",
                    "assembled LAN payload exceeds the batch byte ceiling",
                ));
            }
            payload.extend_from_slice(&chunk);
        }
        Ok(Some(payload))
    }

    /// True when the chunk is already confirmed.
    #[must_use]
    pub fn is_chunk_confirmed(&self, binding: &ChunkBinding) -> bool {
        self.confirmed
            .contains(&DurableChunkCoordinate::from(binding))
    }

    /// Chunk indices still to send for one item/attachment slot in one session.
    ///
    /// This is the resume answer: everything not already confirmed, in order.
    #[must_use]
    pub fn unconfirmed_chunk_indices(
        &self,
        batch_id: &LanBatchId,
        item_index: u16,
        attachment_slot: u16,
        total_chunks: u32,
    ) -> Vec<u32> {
        (0..total_chunks)
            .filter(|chunk_index| {
                let coordinate = DurableChunkCoordinate {
                    batch_id: batch_id.as_str().to_owned(),
                    item_index,
                    attachment_slot,
                    chunk_index: *chunk_index,
                };
                !self.confirmed.contains(&coordinate)
            })
            .collect()
    }

    fn flush_peers(&self) -> Result<(), LomoError> {
        let mut body = Vec::new();
        for peer in self.peers.values() {
            push_field(&mut body, peer.public_key().as_bytes());
            push_field(&mut body, peer.display_name().as_str().as_bytes());
            body.extend_from_slice(&peer.paired_at_ms().to_be_bytes());
            body.extend_from_slice(&peer.revoked_at_ms().unwrap_or(0).to_be_bytes());
            body.push(u8::from(peer.is_revoked()));
        }
        write_record(&self.paths.peers(), &body)
    }

    fn flush_approvals(&self) -> Result<(), LomoError> {
        let mut body = Vec::new();
        for approval in self.approvals.values() {
            push_field(&mut body, approval.batch_id().as_str().as_bytes());
            body.extend_from_slice(&approval.approved_at_ms().to_be_bytes());
            body.extend_from_slice(&approval.ttl_ms().to_be_bytes());
        }
        write_record(&self.paths.approvals(), &body)
    }

    fn flush_sessions(&self) -> Result<(), LomoError> {
        let mut body = Vec::new();
        for session_id in &self.sessions {
            push_field(&mut body, session_id.as_str().as_bytes());
        }
        write_record(&self.paths.sessions(), &body)
    }

    fn flush_batches(&self) -> Result<(), LomoError> {
        write_record(&self.paths.batches(), &encode_batches(&self.batches))
    }

    fn flush_outgoing_batches(&self) -> Result<(), LomoError> {
        write_record(
            &self.paths.outgoing_batches(),
            &encode_outgoing_batches(&self.outgoing_batches),
        )
    }

    fn mutate_batch(
        &mut self,
        batch_id: &LanBatchId,
        mutate: impl FnOnce(&mut LanDurableBatch) -> Result<(), LomoError>,
    ) -> Result<(), LomoError> {
        let batch = self.batches.get_mut(batch_id).ok_or_else(|| {
            validation(
                "lan_batch_unknown",
                "batch is not present in durable recovery state",
            )
        })?;
        let previous = batch.clone();
        mutate(batch)?;
        if let Err(error) = self.flush_batches() {
            self.batches.insert(batch_id.clone(), previous);
            return Err(error);
        }
        Ok(())
    }

    fn mutate_outgoing_batch(
        &mut self,
        batch_id: &LanBatchId,
        mutate: impl FnOnce(&mut LanDurableOutgoingBatch) -> Result<(), LomoError>,
    ) -> Result<(), LomoError> {
        let batch = self.outgoing_batches.get_mut(batch_id).ok_or_else(|| {
            validation(
                "lan_batch_unknown",
                "outgoing batch is not present in durable recovery state",
            )
        })?;
        let previous = batch.clone();
        mutate(batch)?;
        if let Err(error) = self.flush_outgoing_batches() {
            self.outgoing_batches.insert(batch_id.clone(), previous);
            return Err(error);
        }
        Ok(())
    }

    fn flush_confirmed(&self) -> Result<(), LomoError> {
        let mut body = Vec::new();
        for coordinate in &self.confirmed {
            push_field(&mut body, coordinate.batch_id.as_bytes());
            body.extend_from_slice(&coordinate.item_index.to_be_bytes());
            body.extend_from_slice(&coordinate.attachment_slot.to_be_bytes());
            body.extend_from_slice(&coordinate.chunk_index.to_be_bytes());
        }
        write_record(&self.paths.confirmed_chunks(), &body)
    }
}

fn read_peers(path: &Path) -> Result<BTreeMap<DeviceId, PeerRecord>, LomoError> {
    let Some(body) = read_record(path)? else {
        return Ok(BTreeMap::new());
    };
    let mut peers = BTreeMap::new();
    let mut cursor = 0_usize;
    while cursor < body.len() {
        let (key_bytes, next) = take_field(&body, cursor)?;
        let (name_bytes, next) = take_field(&body, next)?;
        let paired_at_ms = take_i64(&body, next)?;
        let revoked_at_ms = take_i64(&body, next.saturating_add(8))?;
        let revoked_flag = *body.get(next.saturating_add(16)).ok_or_else(|| {
            corrupt_state("lan_peer_record_truncated", "peer record is truncated")
        })?;
        cursor = next.saturating_add(17);

        let public_key = DevicePublicKey::parse(key_bytes)?;
        let display_name =
            DisplayName::parse(std::str::from_utf8(name_bytes).map_err(|_error| {
                corrupt_state(
                    "lan_peer_record_invalid",
                    "peer display name is not valid UTF-8",
                )
            })?)?;
        let record = PeerRecord::paired(public_key, display_name, paired_at_ms);
        let record = if revoked_flag == 1 {
            record.revoked(revoked_at_ms)
        } else {
            record
        };
        peers.insert(record.device_id().clone(), record);
    }
    Ok(peers)
}

fn read_approvals(path: &Path) -> Result<BTreeMap<LanBatchId, LanApproval>, LomoError> {
    let Some(body) = read_record(path)? else {
        return Ok(BTreeMap::new());
    };
    let mut approvals = BTreeMap::new();
    let mut cursor = 0_usize;
    while cursor < body.len() {
        let (id_bytes, next) = take_field(&body, cursor)?;
        let approved_at_ms = take_i64(&body, next)?;
        let ttl_ms = take_i64(&body, next.saturating_add(8))?;
        cursor = next.saturating_add(16);

        let batch_id = LanBatchId::parse(std::str::from_utf8(id_bytes).map_err(|_error| {
            corrupt_state("lan_approval_invalid", "batch id is not valid UTF-8")
        })?)?;
        approvals.insert(
            batch_id.clone(),
            LanApproval::granted(batch_id, approved_at_ms, ttl_ms),
        );
    }
    Ok(approvals)
}

fn read_sessions(path: &Path) -> Result<BTreeSet<LanSessionId>, LomoError> {
    let Some(body) = read_record(path)? else {
        return Ok(BTreeSet::new());
    };
    let mut sessions = BTreeSet::new();
    let mut cursor = 0_usize;
    while cursor < body.len() {
        let (session_bytes, next) = take_field(&body, cursor)?;
        cursor = next;
        let session_text = std::str::from_utf8(session_bytes).map_err(|_error| {
            corrupt_state(
                "lan_session_record_invalid",
                "session id is not valid UTF-8",
            )
        })?;
        sessions.insert(LanSessionId::parse(session_text)?);
    }
    Ok(sessions)
}

fn encode_batches(batches: &BTreeMap<LanBatchId, LanDurableBatch>) -> Vec<u8> {
    let mut body = Vec::new();
    for batch in batches.values() {
        encode_batch_plan(
            &mut body,
            batch.plan(),
            batch.session_id(),
            batch.sender_device_id(),
            batch.sender_name(),
        );
        match batch.decision() {
            LanBatchDecision::Pending => body.push(0),
            LanBatchDecision::Approved {
                approval,
                generation,
            } => {
                body.push(1);
                body.extend_from_slice(&approval.approved_at_ms().to_be_bytes());
                body.extend_from_slice(&approval.ttl_ms().to_be_bytes());
                push_field(&mut body, generation.as_str().as_bytes());
            }
            LanBatchDecision::Rejected { rejected_at_ms } => {
                body.push(2);
                body.extend_from_slice(&rejected_at_ms.to_be_bytes());
            }
        }
        for item in batch.plan().items() {
            match batch.snapshot().outcome(item.item_id()) {
                Some(LanItemOutcome::Pending) => body.push(0),
                Some(LanItemOutcome::Committed { memo_id }) => {
                    body.push(1);
                    push_field(&mut body, memo_id.as_bytes());
                }
                Some(LanItemOutcome::Failed { code }) => {
                    body.push(2);
                    push_field(&mut body, code.as_bytes());
                }
                None => body.push(u8::MAX),
            }
        }
    }
    body
}

fn encode_outgoing_batches(batches: &BTreeMap<LanBatchId, LanDurableOutgoingBatch>) -> Vec<u8> {
    let mut body = Vec::new();
    for batch in batches.values() {
        encode_batch_plan(
            &mut body,
            &batch.plan,
            &batch.session_id,
            &batch.peer_device_id,
            &batch.peer_display_name,
        );
        body.push(match batch.decision {
            LanOutgoingDecision::AwaitingApproval => 0,
            LanOutgoingDecision::Approved => 1,
            LanOutgoingDecision::Rejected => 2,
        });
        body.extend_from_slice(
            &u32::try_from(batch.confirmed.len())
                .unwrap_or(u32::MAX)
                .to_be_bytes(),
        );
        for (item_index, attachment_slot, chunk_index) in &batch.confirmed {
            body.extend_from_slice(&item_index.to_be_bytes());
            body.extend_from_slice(&attachment_slot.to_be_bytes());
            body.extend_from_slice(&chunk_index.to_be_bytes());
        }
        body.extend_from_slice(
            &u16::try_from(batch.plan.items().len())
                .unwrap_or(u16::MAX)
                .to_be_bytes(),
        );
        for item in batch.plan.items() {
            match batch.snapshot.outcome(item.item_id()) {
                Some(LanItemOutcome::Pending) => body.push(0),
                Some(LanItemOutcome::Committed { memo_id }) => {
                    body.push(1);
                    push_field(&mut body, memo_id.as_bytes());
                }
                Some(LanItemOutcome::Failed { code }) => {
                    body.push(2);
                    push_field(&mut body, code.as_bytes());
                }
                None => body.push(u8::MAX),
            }
        }
    }
    body
}

fn encode_batch_plan(
    body: &mut Vec<u8>,
    plan: &LanBatchPlan,
    session_id: &LanSessionId,
    peer_device_id: &DeviceId,
    peer_display_name: &DisplayName,
) {
    push_field(body, plan.batch_id().as_str().as_bytes());
    push_field(body, session_id.as_str().as_bytes());
    push_field(body, peer_device_id.as_str().as_bytes());
    push_field(body, peer_display_name.as_str().as_bytes());
    body.extend_from_slice(
        &u16::try_from(plan.item_count())
            .unwrap_or(u16::MAX)
            .to_be_bytes(),
    );
    for item in plan.items() {
        body.extend_from_slice(&item.timestamp_ms().to_be_bytes());
        push_field(body, item.content_digest().as_bytes());
        body.extend_from_slice(&item.content_bytes().to_be_bytes());
        push_field(body, item.title().as_bytes());
        body.extend_from_slice(
            &u16::try_from(item.attachments().len())
                .unwrap_or(u16::MAX)
                .to_be_bytes(),
        );
        for attachment in item.attachments() {
            body.extend_from_slice(&attachment.slot().to_be_bytes());
            push_field(body, attachment.source_reference().as_bytes());
            push_field(body, attachment.name().as_bytes());
            push_field(body, attachment.digest().as_bytes());
            body.extend_from_slice(&attachment.size_bytes().to_be_bytes());
        }
    }
}

fn read_batches(path: &Path) -> Result<BTreeMap<LanBatchId, LanDurableBatch>, LomoError> {
    let Some(body) = read_record(path)? else {
        return Ok(BTreeMap::new());
    };
    let mut batches = BTreeMap::new();
    let mut cursor = 0_usize;
    while cursor < body.len() {
        let (batch_id, durable, next) = read_batch(&body, cursor)?;
        cursor = next;
        if batches.insert(batch_id, durable).is_some() {
            return Err(batch_record_invalid());
        }
    }
    Ok(batches)
}

fn read_outgoing_batches(
    path: &Path,
) -> Result<BTreeMap<LanBatchId, LanDurableOutgoingBatch>, LomoError> {
    let Some(body) = read_record(path)? else {
        return Ok(BTreeMap::new());
    };
    let mut batches = BTreeMap::new();
    let mut cursor = 0_usize;
    while cursor < body.len() {
        let (plan, session_id, peer_device_id, peer_display_name, next) =
            read_batch_plan(&body, cursor)?;
        let decision = match take_u8(&body, next)? {
            0 => LanOutgoingDecision::AwaitingApproval,
            1 => LanOutgoingDecision::Approved,
            2 => LanOutgoingDecision::Rejected,
            _ => return Err(batch_record_invalid()),
        };
        cursor = next.saturating_add(1);
        let confirmed_count = take_u32(&body, cursor)?;
        cursor = cursor.saturating_add(4);
        let mut confirmed = BTreeSet::new();
        for _ in 0..confirmed_count {
            let item_index = take_u16(&body, cursor)?;
            let attachment_slot = take_u16(&body, cursor.saturating_add(2))?;
            let chunk_index = take_u32(&body, cursor.saturating_add(4))?;
            cursor = cursor.saturating_add(8);
            if !confirmed.insert((item_index, attachment_slot, chunk_index)) {
                return Err(batch_record_invalid());
            }
        }
        let outcome_count = usize::from(take_u16(&body, cursor)?);
        cursor = cursor.saturating_add(2);
        if outcome_count != plan.items().len() {
            return Err(batch_record_invalid());
        }
        let mut snapshot = LanBatchSnapshot::pending(&plan);
        for item in plan.items() {
            let (outcome, next) = read_item_outcome(&body, cursor)?;
            cursor = next;
            snapshot.record(item.item_id(), outcome)?;
        }
        let batch_id = plan.batch_id().clone();
        let durable = LanDurableOutgoingBatch {
            plan,
            session_id,
            peer_device_id,
            peer_display_name,
            decision,
            confirmed,
            snapshot,
        };
        if batches.insert(batch_id, durable).is_some() {
            return Err(batch_record_invalid());
        }
    }
    Ok(batches)
}

fn read_batch(
    body: &[u8],
    cursor: usize,
) -> Result<(LanBatchId, LanDurableBatch, usize), LomoError> {
    let (plan, session_id, sender_device_id, sender_name, mut cursor) =
        read_batch_plan(body, cursor)?;
    let batch_id = plan.batch_id().clone();
    let mut durable = LanDurableBatch::pending(plan, session_id, sender_device_id, sender_name);
    let decision = take_u8(body, cursor)?;
    cursor = cursor.saturating_add(1);
    match decision {
        0 => {}
        1 => {
            let approved_at_ms = take_i64(body, cursor)?;
            cursor = cursor.saturating_add(8);
            let ttl_ms = take_i64(body, cursor)?;
            cursor = cursor.saturating_add(8);
            let (generation, next) = take_field(body, cursor)?;
            cursor = next;
            durable.approve(
                LanApproval::granted(batch_id.clone(), approved_at_ms, ttl_ms),
                ApprovedGeneration::capture(record_text(generation)?)?,
            )?;
        }
        2 => {
            durable.reject(take_i64(body, cursor)?)?;
            cursor = cursor.saturating_add(8);
        }
        _ => return Err(batch_record_invalid()),
    }
    let item_ids: Vec<_> = durable
        .plan()
        .items()
        .iter()
        .map(|item| item.item_id().clone())
        .collect();
    for item_id in item_ids {
        let (outcome, next) = read_item_outcome(body, cursor)?;
        cursor = next;
        durable.record(&item_id, outcome)?;
    }
    Ok((batch_id, durable, cursor))
}

fn read_batch_plan(
    body: &[u8],
    cursor: usize,
) -> Result<(LanBatchPlan, LanSessionId, DeviceId, DisplayName, usize), LomoError> {
    let (batch_bytes, mut cursor) = take_field(body, cursor)?;
    let batch_id = LanBatchId::parse(record_text(batch_bytes)?)?;
    let (session_id, next) = take_field(body, cursor)?;
    cursor = next;
    let (sender_device_id, next) = take_field(body, cursor)?;
    cursor = next;
    let (sender_name, next) = take_field(body, cursor)?;
    cursor = next;
    let sender_device_id = DeviceId::parse(record_text(sender_device_id)?)?;
    let session_id = LanSessionId::parse(record_text(session_id)?)?;
    let sender_name = DisplayName::parse(record_text(sender_name)?)?;
    let item_count = usize::from(take_u16(body, cursor)?);
    cursor = cursor.saturating_add(2);
    let mut items = Vec::with_capacity(item_count);
    for index in 0..item_count {
        let timestamp_ms = take_i64(body, cursor)?;
        cursor = cursor.saturating_add(8);
        let (digest, next) = take_field(body, cursor)?;
        cursor = next;
        let content_bytes = take_u64(body, cursor)?;
        cursor = cursor.saturating_add(8);
        let (title, next) = take_field(body, cursor)?;
        cursor = next;
        let (attachments, next) = read_attachments(body, cursor)?;
        cursor = next;
        items.push(LanItemPlan::new(
            &batch_id,
            u16::try_from(index).map_err(|_error| batch_record_invalid())?,
            timestamp_ms,
            record_text(digest)?,
            content_bytes,
            record_text(title)?,
            attachments,
        )?);
    }
    Ok((
        LanBatchPlan::new(batch_id, items)?,
        session_id,
        sender_device_id,
        sender_name,
        cursor,
    ))
}

fn read_attachments(
    body: &[u8],
    mut cursor: usize,
) -> Result<(Vec<LanAttachmentRef>, usize), LomoError> {
    let attachment_count = usize::from(take_u16(body, cursor)?);
    cursor = cursor.saturating_add(2);
    let mut attachments = Vec::with_capacity(attachment_count);
    for _attachment in 0..attachment_count {
        let slot = take_u16(body, cursor)?;
        cursor = cursor.saturating_add(2);
        let (source_reference, next) = take_field(body, cursor)?;
        let (name, next) = take_field(body, next)?;
        let (digest, next) = take_field(body, next)?;
        let size_bytes = take_u64(body, next)?;
        cursor = next.saturating_add(8);
        attachments.push(LanAttachmentRef::new(
            slot,
            record_text(source_reference)?,
            record_text(name)?,
            record_text(digest)?,
            size_bytes,
        )?);
    }
    Ok((attachments, cursor))
}

fn read_item_outcome(body: &[u8], cursor: usize) -> Result<(LanItemOutcome, usize), LomoError> {
    match take_u8(body, cursor)? {
        0 => Ok((LanItemOutcome::Pending, cursor.saturating_add(1))),
        1 | 2 => {
            let tag = take_u8(body, cursor)?;
            let (value, next) = take_field(body, cursor.saturating_add(1))?;
            let text = record_text(value)?;
            let outcome = if tag == 1 {
                LanItemOutcome::committed(text)
            } else {
                LanItemOutcome::failed(text)
            };
            Ok((outcome, next))
        }
        _ => Err(batch_record_invalid()),
    }
}

fn record_text(bytes: &[u8]) -> Result<&str, LomoError> {
    std::str::from_utf8(bytes).map_err(|_error| batch_record_invalid())
}

fn batch_record_invalid() -> LomoError {
    corrupt_state(
        "lan_batch_record_invalid",
        "durable batch recovery record is malformed",
    )
}

fn read_confirmed(path: &Path) -> Result<Vec<DurableChunkCoordinate>, LomoError> {
    let Some(body) = read_record(path)? else {
        return Ok(Vec::new());
    };
    let mut confirmed = Vec::new();
    let mut cursor = 0_usize;
    while cursor < body.len() {
        let (batch_bytes, next) = take_field(&body, cursor)?;
        let item_index = take_u16(&body, next)?;
        let attachment_slot = take_u16(&body, next.saturating_add(2))?;
        let chunk_index = take_u32(&body, next.saturating_add(4))?;
        cursor = next.saturating_add(8);

        let batch_id = std::str::from_utf8(batch_bytes).map_err(|_error| {
            corrupt_state("lan_chunk_record_invalid", "batch id is not valid UTF-8")
        })?;
        LanBatchId::parse(batch_id)?;
        confirmed.push(DurableChunkCoordinate {
            batch_id: batch_id.to_owned(),
            item_index,
            attachment_slot,
            chunk_index,
        });
    }
    Ok(confirmed)
}

fn read_record(path: &Path) -> Result<Option<Vec<u8>>, LomoError> {
    match fs::read(path) {
        Ok(bytes) => decode_record(&bytes).map(Some),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(storage(
            "lan_journal_read_failed",
            &format!("cannot read the LAN journal record: {error}"),
        )),
    }
}

/// Writes a record temp-then-rename so a crash never leaves a half record.
fn write_record(path: &Path, body: &[u8]) -> Result<(), LomoError> {
    let encoded = encode_record(body)?;
    let temp = path.with_extension("rec.tmp");
    fs::write(&temp, &encoded).map_err(|error| {
        storage(
            "lan_journal_write_failed",
            &format!("cannot write the LAN journal temp record: {error}"),
        )
    })?;
    fs::rename(&temp, path).map_err(|error| {
        storage(
            "lan_journal_commit_failed",
            &format!("cannot commit the LAN journal record: {error}"),
        )
    })
}

fn push_field(buffer: &mut Vec<u8>, field: &[u8]) {
    let length = u32::try_from(field.len()).unwrap_or(u32::MAX);
    buffer.extend_from_slice(&length.to_be_bytes());
    buffer.extend_from_slice(field);
}

fn take_field(body: &[u8], cursor: usize) -> Result<(&[u8], usize), LomoError> {
    let length = take_u32(body, cursor)? as usize;
    let start = cursor.saturating_add(4);
    let end = start
        .checked_add(length)
        .ok_or_else(|| corrupt_state("lan_journal_truncated", "record field length overflows"))?;
    let field = body
        .get(start..end)
        .ok_or_else(|| corrupt_state("lan_journal_truncated", "record field is truncated"))?;
    Ok((field, end))
}

fn take_u16(body: &[u8], cursor: usize) -> Result<u16, LomoError> {
    let slice = body
        .get(cursor..cursor.saturating_add(2))
        .ok_or_else(|| corrupt_state("lan_journal_truncated", "record field is truncated"))?;
    let bytes: [u8; 2] = slice
        .try_into()
        .map_err(|_error| corrupt_state("lan_journal_truncated", "record field is truncated"))?;
    Ok(u16::from_be_bytes(bytes))
}

fn take_u8(body: &[u8], cursor: usize) -> Result<u8, LomoError> {
    body.get(cursor)
        .copied()
        .ok_or_else(|| corrupt_state("lan_journal_truncated", "record field is truncated"))
}

fn take_u32(body: &[u8], cursor: usize) -> Result<u32, LomoError> {
    let slice = body
        .get(cursor..cursor.saturating_add(4))
        .ok_or_else(|| corrupt_state("lan_journal_truncated", "record field is truncated"))?;
    let bytes: [u8; 4] = slice
        .try_into()
        .map_err(|_error| corrupt_state("lan_journal_truncated", "record field is truncated"))?;
    Ok(u32::from_be_bytes(bytes))
}

fn take_u64(body: &[u8], cursor: usize) -> Result<u64, LomoError> {
    let slice = body
        .get(cursor..cursor.saturating_add(8))
        .ok_or_else(|| corrupt_state("lan_journal_truncated", "record field is truncated"))?;
    let bytes: [u8; 8] = slice
        .try_into()
        .map_err(|_error| corrupt_state("lan_journal_truncated", "record field is truncated"))?;
    Ok(u64::from_be_bytes(bytes))
}

fn take_i64(body: &[u8], cursor: usize) -> Result<i64, LomoError> {
    let slice = body
        .get(cursor..cursor.saturating_add(8))
        .ok_or_else(|| corrupt_state("lan_journal_truncated", "record field is truncated"))?;
    let bytes: [u8; 8] = slice
        .try_into()
        .map_err(|_error| corrupt_state("lan_journal_truncated", "record field is truncated"))?;
    Ok(i64::from_be_bytes(bytes))
}

fn be_u32(header: &[u8], offset: usize) -> Result<u32, LomoError> {
    let slice = header
        .get(offset..offset.saturating_add(4))
        .ok_or_else(|| corrupt_state("lan_record_truncated", "record header is truncated"))?;
    let bytes: [u8; 4] = slice
        .try_into()
        .map_err(|_error| corrupt_state("lan_record_truncated", "record header is truncated"))?;
    Ok(u32::from_be_bytes(bytes))
}

fn restore_map_entry<K: Ord, V>(map: &mut BTreeMap<K, V>, key: K, previous: Option<V>) {
    if let Some(value) = previous {
        map.insert(key, value);
    } else {
        map.remove(&key);
    }
}
