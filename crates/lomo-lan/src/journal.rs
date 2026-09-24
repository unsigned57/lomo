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
use std::io::{self, Read as _, Write as _};
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

use crate::batch::{
    LanApproval, LanAttachmentRef, LanBatchDecision, LanBatchId, LanBatchPlan, LanBatchSnapshot,
    LanDurableBatch, LanItemOutcome, LanItemPlan, PlannedPayload, chunk_count,
    expected_chunk_length, planned_payload, planned_payload_coordinates,
};
use crate::commit::ApprovedGeneration;
use crate::error::{
    authentication, conflict, corrupt_state, permission, resource_limit, storage, validation,
};
use crate::identity::{DeviceId, DevicePublicKey, DisplayName, PeerRecord};
use crate::limits::{
    CHUNK_PLAINTEXT_BYTES, LAN_BATCH_RETIRE_DELAY_MS, LAN_CONFIRMED_LOG_COMPACT_BYTES,
    LAN_DURABLE_SCHEMA, LAN_DURABLE_SCHEMA_MIN_READ, LAN_RETIRED_WITNESS_RETENTION_MS,
    LAN_SESSION_WITNESS_RETENTION_MS, MAX_BATCH_TOTAL_BYTES, MAX_LAN_RECORD_BYTES,
    MAX_RETIRED_WITNESSES, MAX_SESSION_WITNESSES, MAX_TRUSTED_PEERS,
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
/// The strict decode only accepts the current schema; the open path uses
/// `decode_record_versioned` so records written by older builds stay readable.
///
/// # Errors
///
/// Corruption for any header or checksum mismatch; resource-limit for an oversized declared length.
pub fn decode_record(bytes: &[u8]) -> Result<Vec<u8>, LomoError> {
    decode_record_versioned(bytes, LAN_DURABLE_SCHEMA).map(|(body, _schema)| body)
}

/// Decodes a record body whose schema may be any version this build can still read.
///
/// Returns the body together with the record's own schema so format-versioned payloads (session
/// witnesses, outgoing batch facts) can branch on it instead of guessing.
///
/// # Errors
///
/// Corruption for any header, schema-range or checksum mismatch; resource-limit for an oversized
/// declared length.
fn decode_record_versioned(bytes: &[u8], min_schema: u32) -> Result<(Vec<u8>, u32), LomoError> {
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
    if !(min_schema..=LAN_DURABLE_SCHEMA).contains(&schema) {
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
    if &Sha256::digest(body)[..] != expected_digest {
        return Err(corrupt_state(
            "lan_record_checksum_mismatch",
            "durable LAN record checksum does not match its body",
        ));
    }
    Ok((body.to_vec(), schema))
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

    /// Append-only tail of confirmed coordinates between compactions.
    #[must_use]
    pub fn confirmed_log(&self) -> PathBuf {
        self.root.join("chunks.log")
    }

    /// Contiguous reassembled payload bytes for one transfer coordinate.
    #[must_use]
    fn assembled_payload(&self, batch_id: &LanBatchId, item_index: u16, slot: u16) -> PathBuf {
        self.payload_batch_dir(batch_id)
            .join(format!("assembled-{item_index}-{slot}.payload"))
    }

    #[must_use]
    pub fn retired(&self) -> PathBuf {
        self.root.join("retired.rec")
    }

    #[must_use]
    fn payloads_dir(&self) -> PathBuf {
        self.root.join("payloads")
    }

    #[must_use]
    fn payload_batch_dir(&self, batch_id: &LanBatchId) -> PathBuf {
        self.payloads_dir().join(batch_id.as_str())
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

/// A digest-verified contiguous payload staged in the private LAN journal tree.
///
/// The artifact reference is what crosses the commit boundary: commit code re-checks the durable
/// size/digest facts and streams the file onward instead of holding the payload in memory.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanStagedPayload {
    path: PathBuf,
    size_bytes: u64,
    digest: String,
}

impl LanStagedPayload {
    #[must_use]
    pub fn path(&self) -> &Path {
        &self.path
    }

    #[must_use]
    pub const fn size_bytes(&self) -> u64 {
        self.size_bytes
    }

    #[must_use]
    pub fn digest(&self) -> &str {
        &self.digest
    }
}

#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
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
    sessions: BTreeMap<LanSessionId, i64>,
    batches: BTreeMap<LanBatchId, LanDurableBatch>,
    outgoing_batches: BTreeMap<LanBatchId, LanDurableOutgoingBatch>,
    approvals: BTreeMap<LanBatchId, LanApproval>,
    confirmed: BTreeSet<DurableChunkCoordinate>,
    retired: BTreeMap<(DeviceId, LanBatchId), i64>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LanOutgoingDecision {
    AwaitingApproval,
    Approved,
    Rejected,
}

/// A terminal refusal observed on an outgoing batch: the peer or transport refused it with a
/// stable disposition code, so the batch drives as failed instead of retrying forever.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanOutgoingFailure {
    pub(crate) code: String,
    pub(crate) failed_at_ms: i64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanDurableOutgoingBatch {
    plan: LanBatchPlan,
    session_id: LanSessionId,
    peer_device_id: DeviceId,
    peer_display_name: DisplayName,
    decision: LanOutgoingDecision,
    failure: Option<LanOutgoingFailure>,
    terminal_at_ms: Option<i64>,
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
            failure: None,
            terminal_at_ms: None,
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

    pub(crate) fn failure_code(&self) -> Option<&str> {
        self.failure.as_ref().map(|failure| failure.code.as_str())
    }

    pub(crate) const fn peer_device_id(&self) -> &DeviceId {
        &self.peer_device_id
    }

    pub(crate) const fn peer_display_name(&self) -> &DisplayName {
        &self.peer_display_name
    }

    pub(crate) const fn snapshot(&self) -> &LanBatchSnapshot {
        &self.snapshot
    }

    /// True when every payload coordinate's chunks are all durably confirmed by the receiver.
    pub(crate) fn all_payloads_confirmed(&self) -> Result<bool, LomoError> {
        for (item_index, attachment_slot) in planned_payload_coordinates(&self.plan)? {
            let payload = planned_payload(&self.plan, item_index, attachment_slot)?;
            let total_chunks = chunk_count(payload.size_bytes)?;
            for chunk_index in 0..total_chunks {
                if !self.confirmed.contains(&(
                    payload.item_index,
                    payload.attachment_slot,
                    chunk_index,
                )) {
                    return Ok(false);
                }
            }
        }
        Ok(true)
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

    /// Durable confirmed bytes for this outgoing batch, summed from plan-declared chunk lengths.
    ///
    /// # Errors
    ///
    /// Validation/corruption when the plan's payload coordinates cannot be reconstructed.
    pub(crate) fn confirmed_payload_bytes(&self) -> Result<u64, LomoError> {
        let mut bytes = 0_u64;
        for (item_index, attachment_slot) in planned_payload_coordinates(&self.plan)? {
            let payload = planned_payload(&self.plan, item_index, attachment_slot)?;
            let total_chunks = chunk_count(payload.size_bytes)?;
            for chunk_index in 0..total_chunks {
                if self.confirmed.contains(&(
                    payload.item_index,
                    payload.attachment_slot,
                    chunk_index,
                )) {
                    bytes = bytes.saturating_add(
                        u64::try_from(expected_chunk_length(payload.size_bytes, chunk_index)?)
                            .unwrap_or(0),
                    );
                }
            }
        }
        Ok(bytes)
    }
}

impl LanJournal {
    /// Opens (or initializes) the journal, failing closed on any corrupt record.
    ///
    /// Opening reconciles durable confirmations against staged bytes: a confirmed coordinate whose
    /// batch, file, planned length or payload digest cannot be verified downgrades to
    /// retransmittable instead of poisoning the batch as "confirmed but impossible to complete".
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
        let mut confirmed = read_confirmed(&paths.confirmed_chunks())?;
        confirmed.extend(read_confirmed_log(&paths.confirmed_log())?);
        let retired = read_retired(&paths.retired())?;
        let mut journal = Self {
            paths,
            peers,
            sessions,
            batches,
            outgoing_batches,
            approvals,
            confirmed,
            retired,
        };
        journal.reconcile_confirmed()?;
        journal.reclaim_orphan_payloads()?;
        Ok(journal)
    }

    /// Trusted peers by device id.
    #[must_use]
    pub const fn peers(&self) -> &BTreeMap<DeviceId, PeerRecord> {
        &self.peers
    }

    /// Accepts a fresh session identity exactly once across process restarts.
    ///
    /// The acceptance instant is journaled with the id so the witness can retire past the replay
    /// retention window instead of growing forever.
    ///
    /// # Errors
    ///
    /// Authentication when the id was already accepted; storage when durability fails.
    pub fn accept_session(
        &mut self,
        session_id: &LanSessionId,
        accepted_at_ms: i64,
    ) -> Result<(), LomoError> {
        if self.sessions.contains_key(session_id) {
            return Err(authentication(
                "lan_session_replayed",
                "session id was already used and may not be replayed",
            ));
        }
        self.sessions.insert(session_id.clone(), accepted_at_ms);
        if let Err(error) = self.flush_sessions() {
            self.sessions.remove(session_id);
            return Err(error);
        }
        Ok(())
    }

    /// True when recovery refers to a session that was previously authenticated.
    #[must_use]
    pub fn has_session(&self, session_id: &LanSessionId) -> bool {
        self.sessions.contains_key(session_id)
    }

    /// True when `(counterparty, batch id)` was retired and must never resurrect with new facts.
    pub(crate) fn is_batch_retired(&self, counterparty: &DeviceId, batch_id: &LanBatchId) -> bool {
        self.retired
            .contains_key(&(counterparty.clone(), batch_id.clone()))
    }

    /// Stores complete pending recovery state before exposing its approval preview.
    ///
    /// # Errors
    ///
    /// Conflict when the id was retired inside the anti-replay window; storage/resource-limit when
    /// the checksummed batch record cannot be persisted.
    pub fn store_batch(&mut self, batch: LanDurableBatch) -> Result<(), LomoError> {
        let batch_id = batch.plan().batch_id().clone();
        if self.is_batch_retired(batch.sender_device_id(), &batch_id) {
            return Err(conflict(
                "lan_batch_retired",
                "batch id was retired inside the anti-replay window and cannot resurrect",
            ));
        }
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
            return Err(conflict(
                "lan_outgoing_batch_replayed_with_different_plan",
                "outgoing batch id was reused with different durable facts",
            ));
        }
        if self.is_batch_retired(&batch.peer_device_id, &batch_id) {
            return Err(conflict(
                "lan_batch_retired",
                "batch id was retired inside the anti-replay window and cannot resurrect",
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
            LanOutgoingDecision::Rejected => Err(conflict(
                "lan_batch_decision_terminal",
                "a rejected outgoing batch cannot become approved",
            )),
        })
    }

    pub(crate) fn reject_outgoing_batch(
        &mut self,
        batch_id: &LanBatchId,
        rejected_at_ms: i64,
    ) -> Result<(), LomoError> {
        self.mutate_outgoing_batch(batch_id, |batch| match batch.decision {
            LanOutgoingDecision::AwaitingApproval | LanOutgoingDecision::Rejected => {
                batch.decision = LanOutgoingDecision::Rejected;
                batch.terminal_at_ms.get_or_insert(rejected_at_ms);
                Ok(())
            }
            LanOutgoingDecision::Approved => Err(conflict(
                "lan_batch_decision_terminal",
                "an approved outgoing batch cannot become rejected",
            )),
        })
    }

    /// Marks an outgoing batch terminally failed: the peer or transport refused it with a stable
    /// disposition code. The first durable failure fact wins; a repeated mark is idempotent.
    ///
    /// # Errors
    ///
    /// Validation for an unknown batch; storage when durability fails.
    pub(crate) fn fail_outgoing_batch(
        &mut self,
        batch_id: &LanBatchId,
        code: &str,
        failed_at_ms: i64,
    ) -> Result<(), LomoError> {
        self.mutate_outgoing_batch(batch_id, |batch| {
            if batch.failure.is_none() {
                batch.failure = Some(LanOutgoingFailure {
                    code: code.to_owned(),
                    failed_at_ms,
                });
                batch.terminal_at_ms.get_or_insert(failed_at_ms);
            }
            Ok(())
        })
    }

    pub(crate) fn update_outgoing_batch_status(
        &mut self,
        batch_id: &LanBatchId,
        session_id: &LanSessionId,
        decision: LanOutgoingDecision,
        confirmed: BTreeSet<(u16, u16, u32)>,
        outcomes: &[LanItemOutcome],
        now_ms: i64,
    ) -> Result<(), LomoError> {
        self.mutate_outgoing_batch(batch_id, |batch| {
            if batch.session_id != *session_id {
                return Err(permission(
                    "lan_batch_session_mismatch",
                    "remote batch status does not belong to the outgoing batch session",
                ));
            }
            batch.decision = match (batch.decision, decision) {
                (current, remote) if current == remote => current,
                (LanOutgoingDecision::AwaitingApproval, remote) => remote,
                _ => {
                    return Err(conflict(
                        "lan_outgoing_status_regressed",
                        "remote batch decision conflicts with durable outgoing state",
                    ));
                }
            };
            if !batch.confirmed.is_subset(&confirmed) {
                return Err(conflict(
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
                    return Err(conflict(
                        "lan_outgoing_status_regressed",
                        "remote item outcome moved behind durable outgoing state",
                    ));
                }
                if matches!(current, LanItemOutcome::Committed { .. }) && current != outcome {
                    return Err(conflict(
                        "lan_outgoing_status_regressed",
                        "remote committed item result changed after durability",
                    ));
                }
                batch.snapshot.record(item.item_id(), outcome.clone())?;
            }
            batch.confirmed = confirmed;
            if batch.decision == LanOutgoingDecision::Rejected || batch.snapshot.is_complete() {
                batch.terminal_at_ms.get_or_insert(now_ms);
            }
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

    /// Retires a received batch past its terminal anchor plus the anti-replay window.
    ///
    /// Retirement reclaims the batch record, its confirmed coordinates and staged payload bytes,
    /// and leaves a durable `(sender, batch id)` witness so the id cannot resurrect inside the
    /// replay horizon. The witness is journaled first so a crash between flushes still fails
    /// closed: record-present wins over the witness, and witness-only blocks resurrection.
    ///
    /// # Errors
    ///
    /// Validation for an unknown batch; conflict while the batch is not terminal or the
    /// anti-replay window is still open; storage when durability fails.
    pub fn retire_batch(&mut self, batch_id: &LanBatchId, now_ms: i64) -> Result<(), LomoError> {
        let batch = self.batches.get(batch_id).ok_or_else(|| {
            validation(
                "lan_batch_unknown",
                "batch is not present in durable recovery state",
            )
        })?;
        let Some(anchor) = received_terminal_anchor(batch) else {
            return Err(conflict(
                "lan_batch_not_retirable",
                "a batch whose decision is still pending can never be reclaimed",
            ));
        };
        if now_ms < anchor.saturating_add(LAN_BATCH_RETIRE_DELAY_MS) {
            return Err(conflict(
                "lan_batch_not_retirable",
                "the anti-replay window must close before the batch can be reclaimed",
            ));
        }
        let owner = batch.sender_device_id().clone();
        self.retired.insert((owner, batch_id.clone()), now_ms);
        self.flush_retired()?;
        let removed = self.batches.remove(batch_id);
        let removed_coordinates: Vec<DurableChunkCoordinate> = self
            .confirmed
            .iter()
            .filter(|coordinate| coordinate.batch_id == batch_id.as_str())
            .cloned()
            .collect();
        self.confirmed
            .retain(|coordinate| coordinate.batch_id != batch_id.as_str());
        if let Err(error) = self.flush_batches().and_then(|()| self.compact_confirmed()) {
            if let Some(batch) = removed {
                self.batches.insert(batch_id.clone(), batch);
            }
            self.confirmed.extend(removed_coordinates);
            return Err(error);
        }
        let dir = self.paths.payload_batch_dir(batch_id);
        match fs::remove_dir_all(&dir) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(storage(
                "lan_payload_reclaim_failed",
                &format!("cannot reclaim retired LAN payload bytes: {error}"),
            )),
        }
    }

    /// Retires an outgoing batch whose terminal anchor plus the anti-replay window has passed.
    ///
    /// The witness is keyed by `(peer device, batch id)`: the id may not be reused with that peer
    /// inside the replay horizon.
    ///
    /// # Errors
    ///
    /// Validation for an unknown batch; conflict while not retirable; storage on durability
    /// failure.
    pub(crate) fn retire_outgoing_batch(
        &mut self,
        batch_id: &LanBatchId,
        now_ms: i64,
    ) -> Result<(), LomoError> {
        let batch = self.outgoing_batches.get(batch_id).ok_or_else(|| {
            validation(
                "lan_batch_unknown",
                "outgoing batch is not present in durable recovery state",
            )
        })?;
        let Some(anchor) = outgoing_terminal_anchor(batch) else {
            return Err(conflict(
                "lan_batch_not_retirable",
                "an outgoing batch still awaiting a decision or transfer can never be reclaimed",
            ));
        };
        if now_ms < anchor.saturating_add(LAN_BATCH_RETIRE_DELAY_MS) {
            return Err(conflict(
                "lan_batch_not_retirable",
                "the anti-replay window must close before the outgoing batch can be reclaimed",
            ));
        }
        let peer = batch.peer_device_id.clone();
        self.retired.insert((peer, batch_id.clone()), now_ms);
        self.flush_retired()?;
        let removed = self.outgoing_batches.remove(batch_id);
        if let Err(error) = self.flush_outgoing_batches() {
            if let Some(batch) = removed {
                self.outgoing_batches.insert(batch_id.clone(), batch);
            }
            return Err(error);
        }
        Ok(())
    }

    /// Drives every durable lifecycle that time alone advances: expiring session witnesses,
    /// retiring terminal batches, evicting expired retired witnesses and reclaiming orphaned
    /// payload bytes. Bounded work per call; safe to run on every inbox read.
    ///
    /// # Errors
    ///
    /// Storage when any durable flush or payload reclamation fails.
    pub fn maintain(&mut self, now_ms: i64) -> Result<(), LomoError> {
        let sessions_before = self.sessions.len();
        self.sessions.retain(|_session_id, accepted_at_ms| {
            now_ms < accepted_at_ms.saturating_add(LAN_SESSION_WITNESS_RETENTION_MS)
        });
        while self.sessions.len() > MAX_SESSION_WITNESSES {
            let oldest = self
                .sessions
                .iter()
                .min_by_key(|(session_id, accepted_at_ms)| (*accepted_at_ms, (*session_id).clone()))
                .map(|(session_id, _accepted_at_ms)| session_id.clone());
            match oldest {
                Some(session_id) => {
                    self.sessions.remove(&session_id);
                }
                None => break,
            }
        }
        if self.sessions.len() != sessions_before {
            self.flush_sessions()?;
        }

        let retired_before = self.retired.len();
        self.retired.retain(|_key, retired_at_ms| {
            now_ms < retired_at_ms.saturating_add(LAN_RETIRED_WITNESS_RETENTION_MS)
        });
        while self.retired.len() > MAX_RETIRED_WITNESSES {
            let oldest = self
                .retired
                .iter()
                .min_by_key(|(key, retired_at_ms)| (*retired_at_ms, (*key).clone()))
                .map(|(key, _retired_at_ms)| key.clone());
            match oldest {
                Some(key) => {
                    self.retired.remove(&key);
                }
                None => break,
            }
        }
        if self.retired.len() != retired_before {
            self.flush_retired()?;
        }

        let retirable_received: Vec<LanBatchId> = self
            .batches
            .iter()
            .filter(|(_batch_id, batch)| {
                received_terminal_anchor(batch).is_some_and(|anchor| {
                    now_ms >= anchor.saturating_add(LAN_BATCH_RETIRE_DELAY_MS)
                })
            })
            .map(|(batch_id, _batch)| batch_id.clone())
            .collect();
        for batch_id in retirable_received {
            self.retire_batch(&batch_id, now_ms)?;
        }

        let retirable_outgoing: Vec<LanBatchId> = self
            .outgoing_batches
            .iter()
            .filter(|(_batch_id, batch)| {
                outgoing_terminal_anchor(batch).is_some_and(|anchor| {
                    now_ms >= anchor.saturating_add(LAN_BATCH_RETIRE_DELAY_MS)
                })
            })
            .map(|(batch_id, _batch)| batch_id.clone())
            .collect();
        for batch_id in retirable_outgoing {
            self.retire_outgoing_batch(&batch_id, now_ms)?;
        }

        self.reclaim_orphan_payloads()?;
        self.maybe_compact_confirmed()
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
        if self.confirmed.contains(&coordinate) {
            return Ok(());
        }
        // The append fsyncs before the in-memory mark: an ACK must never precede durability.
        self.append_confirmed(&coordinate)?;
        self.confirmed.insert(coordinate);
        self.maybe_compact_confirmed()?;
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
        // The batch plan is the staging reservation: a known batch may only ever hold the exact
        // bytes its declared coordinate reserved. Writes beyond the plan are refused before any
        // byte reaches disk.
        if let Ok(batch_id) = LanBatchId::parse(binding.batch_id())
            && let Some(batch) = self.batches.get(&batch_id)
        {
            let payload = planned_payload(
                batch.plan(),
                binding.item_index(),
                binding.attachment_slot(),
            )?;
            let expected = expected_chunk_length(payload.size_bytes, binding.chunk_index())?;
            if plaintext.len() != expected {
                return Err(validation(
                    "lan_chunk_plan_mismatch",
                    "staged chunk length does not match the batch plan reservation",
                ));
            }
        }
        let coordinate = DurableChunkCoordinate::from(binding);
        let path = self.paths.staged_chunk(&coordinate);
        match lomo_core::read_bounded(&path, CHUNK_PLAINTEXT_BYTES as u64) {
            Ok(existing) if existing == plaintext => return Ok(()),
            Ok(_existing) => {
                return Err(authentication(
                    "lan_chunk_replayed_with_different_bytes",
                    "a confirmed chunk binding was replayed with different plaintext bytes",
                ));
            }
            Err(lomo_core::BoundedReadError::Io(error))
                if error.kind() == io::ErrorKind::NotFound => {}
            Err(lomo_core::BoundedReadError::ExceedsLimit { .. }) => {
                return Err(corrupt_state(
                    "lan_chunk_stage_oversized",
                    "a staged LAN chunk exceeds the fixed chunk ceiling",
                ));
            }
            Err(lomo_core::BoundedReadError::Io(error)) => {
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
        write_chunk_synced(&temp, plaintext)?;
        fs::rename(&temp, &path).map_err(|error| {
            storage(
                "lan_chunk_stage_commit_failed",
                &format!("cannot commit a staged LAN chunk: {error}"),
            )
        })?;
        sync_parent_directory(&path)
    }

    /// Streams every confirmed chunk into one contiguous staged payload file.
    ///
    /// Reassembly never holds the payload in memory: each staged chunk file is copied through a
    /// SHA-256 tee into the private assembled artifact, which is fsynced and renamed before its
    /// reference is handed out. A confirmed coordinate whose staged file vanished downgrades to
    /// retransmittable (journaled) instead of poisoning the batch; the payload then reports
    /// `None`. An already-assembled file is re-hashed and reused only while it still matches the
    /// recorded facts — bytes on disk are verified, not trusted because AEAD once sealed them.
    ///
    /// # Errors
    ///
    /// Resource-limit when the requested range or assembled bytes exceed the batch ceiling;
    /// storage on I/O failure or when the durable downgrade cannot be journaled.
    pub fn assemble_confirmed_payload(
        &mut self,
        batch_id: &LanBatchId,
        item_index: u16,
        attachment_slot: u16,
        total_chunks: u32,
    ) -> Result<Option<LanStagedPayload>, LomoError> {
        let max_chunks = MAX_BATCH_TOTAL_BYTES.div_ceil(CHUNK_PLAINTEXT_BYTES as u64);
        if u64::from(total_chunks) > max_chunks {
            return Err(resource_limit(
                "lan_chunk_range_too_large",
                "payload chunk range exceeds the maximum LAN batch size",
            ));
        }
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
        }
        let target = self
            .paths
            .assembled_payload(batch_id, item_index, attachment_slot);
        if let Some(payload) = verify_assembled_payload(&target)? {
            return Ok(Some(payload));
        }
        let batch_dir = self.paths.payload_batch_dir(batch_id);
        fs::create_dir_all(&batch_dir).map_err(|error| {
            storage(
                "lan_payload_assemble_failed",
                &format!("cannot create the LAN payload directory: {error}"),
            )
        })?;
        let temp = target.with_extension("payload.tmp");
        let file = fs::File::create(&temp).map_err(|error| stage_io_error(&error))?;
        let mut hasher = Sha256::new();
        let mut writer = io::BufWriter::new(HashWriter {
            inner: file,
            hasher: &mut hasher,
        });
        match self.stream_confirmed_chunks(
            batch_id,
            item_index,
            attachment_slot,
            total_chunks,
            &mut writer,
        ) {
            Ok(StreamedChunks::Complete(assembled)) => {
                writer.flush().map_err(|error| {
                    storage(
                        "lan_payload_assemble_failed",
                        &format!("cannot flush a staged LAN payload: {error}"),
                    )
                })?;
                let file = writer
                    .into_inner()
                    .map_err(|error| {
                        storage(
                            "lan_payload_assemble_failed",
                            &format!("cannot finish a staged LAN payload: {error}"),
                        )
                    })?
                    .inner;
                file.sync_all().map_err(|error| {
                    storage(
                        "lan_payload_assemble_failed",
                        &format!("cannot sync a staged LAN payload: {error}"),
                    )
                })?;
                fs::rename(&temp, &target).map_err(|error| {
                    storage(
                        "lan_payload_assemble_failed",
                        &format!("cannot commit a staged LAN payload: {error}"),
                    )
                })?;
                sync_parent_directory(&target)?;
                Ok(Some(LanStagedPayload {
                    path: target,
                    size_bytes: assembled,
                    digest: format!("{:x}", hasher.finalize()),
                }))
            }
            Ok(StreamedChunks::Missing(coordinate)) => {
                drop(writer);
                let _ignored = fs::remove_file(&temp);
                self.drop_confirmed(&coordinate)?;
                Ok(None)
            }
            Err(error) => {
                drop(writer);
                let _ignored = fs::remove_file(&temp);
                Err(error)
            }
        }
    }

    /// Streams every confirmed chunk of one payload into `writer`, enforcing the batch byte
    /// ceiling as bytes flow. `Missing` reports the coordinate whose staged file vanished so the
    /// caller can downgrade it to retransmittable.
    fn stream_confirmed_chunks(
        &self,
        batch_id: &LanBatchId,
        item_index: u16,
        attachment_slot: u16,
        total_chunks: u32,
        writer: &mut impl io::Write,
    ) -> Result<StreamedChunks, LomoError> {
        let mut assembled = 0_u64;
        for chunk_index in 0..total_chunks {
            let coordinate = DurableChunkCoordinate {
                batch_id: batch_id.as_str().to_owned(),
                item_index,
                attachment_slot,
                chunk_index,
            };
            let mut chunk = match fs::File::open(self.paths.staged_chunk(&coordinate)) {
                Ok(chunk) => chunk,
                Err(error) if error.kind() == io::ErrorKind::NotFound => {
                    return Ok(StreamedChunks::Missing(coordinate));
                }
                Err(error) => {
                    return Err(storage(
                        "lan_chunk_stage_read_failed",
                        &format!("cannot read a staged LAN chunk: {error}"),
                    ));
                }
            };
            assembled =
                assembled.saturating_add(io::copy(&mut chunk, writer).map_err(|error| {
                    storage(
                        "lan_payload_assemble_failed",
                        &format!("cannot assemble a staged LAN payload: {error}"),
                    )
                })?);
            if assembled > MAX_BATCH_TOTAL_BYTES {
                return Err(resource_limit(
                    "lan_payload_too_large",
                    "assembled LAN payload exceeds the batch byte ceiling",
                ));
            }
        }
        Ok(StreamedChunks::Complete(assembled))
    }

    /// Drops every confirmed coordinate of one payload and deletes its staged files, so a payload
    /// that fails plan-digest verification becomes retransmittable instead of a poison pill.
    ///
    /// # Errors
    ///
    /// Storage when the durable downgrade cannot be journaled.
    pub(crate) fn unconfirm_payload(
        &mut self,
        batch_id: &LanBatchId,
        item_index: u16,
        attachment_slot: u16,
        total_chunks: u32,
    ) -> Result<(), LomoError> {
        let mut dropped = false;
        for chunk_index in 0..total_chunks {
            let coordinate = DurableChunkCoordinate {
                batch_id: batch_id.as_str().to_owned(),
                item_index,
                attachment_slot,
                chunk_index,
            };
            dropped |= self.confirmed.remove(&coordinate);
            let path = self.paths.staged_chunk(&coordinate);
            match fs::remove_file(&path) {
                Ok(()) => {}
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                Err(error) => {
                    return Err(storage(
                        "lan_payload_reclaim_failed",
                        &format!("cannot remove an unconfirmed staged LAN chunk: {error}"),
                    ));
                }
            }
        }
        let assembled = self
            .paths
            .assembled_payload(batch_id, item_index, attachment_slot);
        match fs::remove_file(&assembled) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => {
                return Err(storage(
                    "lan_payload_reclaim_failed",
                    &format!("cannot remove an unconfirmed assembled payload: {error}"),
                ));
            }
        }
        if dropped {
            self.compact_confirmed()?;
        }
        Ok(())
    }

    /// True when the chunk is already confirmed.
    #[must_use]
    pub fn is_chunk_confirmed(&self, binding: &ChunkBinding) -> bool {
        self.confirmed
            .contains(&DurableChunkCoordinate::from(binding))
    }

    /// Durable confirmed bytes for one received batch: the sum of plan-declared lengths for every
    /// confirmed coordinate. Progress facts come from this durable set, never from wire traffic.
    ///
    /// # Errors
    ///
    /// Validation/corruption when the plan's payload coordinates cannot be reconstructed.
    pub(crate) fn confirmed_payload_bytes(
        &self,
        batch: &LanDurableBatch,
    ) -> Result<u64, LomoError> {
        let mut bytes = 0_u64;
        for (item_index, attachment_slot) in planned_payload_coordinates(batch.plan())? {
            let payload = planned_payload(batch.plan(), item_index, attachment_slot)?;
            let total_chunks = chunk_count(payload.size_bytes)?;
            for chunk_index in 0..total_chunks {
                let coordinate = DurableChunkCoordinate {
                    batch_id: batch.plan().batch_id().as_str().to_owned(),
                    item_index: payload.item_index,
                    attachment_slot: payload.attachment_slot,
                    chunk_index,
                };
                if self.confirmed.contains(&coordinate) {
                    bytes = bytes.saturating_add(
                        u64::try_from(expected_chunk_length(payload.size_bytes, chunk_index)?)
                            .unwrap_or(0),
                    );
                }
            }
        }
        Ok(bytes)
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
        for (session_id, accepted_at_ms) in &self.sessions {
            push_field(&mut body, session_id.as_str().as_bytes());
            body.extend_from_slice(&accepted_at_ms.to_be_bytes());
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

    /// Appends one confirmed coordinate to the append tail. The tail is the durability record
    /// between compactions: fsync-before-ACK ordering is preserved because the append is flushed
    /// before `confirm_chunk` returns.
    fn append_confirmed(&self, coordinate: &DurableChunkCoordinate) -> Result<(), LomoError> {
        let path = self.paths.confirmed_log();
        let mut entry = Vec::with_capacity(coordinate.batch_id.len() + 12);
        push_field(&mut entry, coordinate.batch_id.as_bytes());
        entry.extend_from_slice(&coordinate.item_index.to_be_bytes());
        entry.extend_from_slice(&coordinate.attachment_slot.to_be_bytes());
        entry.extend_from_slice(&coordinate.chunk_index.to_be_bytes());
        let created = !path.exists();
        let mut file = fs::OpenOptions::new()
            .append(true)
            .create(true)
            .open(&path)
            .map_err(|error| {
                storage(
                    "lan_journal_write_failed",
                    &format!("cannot open the LAN confirmed-chunk log: {error}"),
                )
            })?;
        file.write_all(&entry)
            .and_then(|()| file.sync_all())
            .map_err(|error| {
                storage(
                    "lan_journal_write_failed",
                    &format!("cannot append to the LAN confirmed-chunk log: {error}"),
                )
            })?;
        if created {
            sync_parent_directory(&path)?;
        }
        Ok(())
    }

    /// Folds the append tail into the compacted snapshot record, then removes the tail.
    ///
    /// The record is durable before the tail is deleted; a crash between the two replays the
    /// same `+` entries over the fresh snapshot, which is idempotent because the tail only ever
    /// carries additions.
    fn compact_confirmed(&self) -> Result<(), LomoError> {
        self.flush_confirmed()?;
        let log = self.paths.confirmed_log();
        match fs::remove_file(&log) {
            Ok(()) => sync_parent_directory(&log),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(storage(
                "lan_journal_commit_failed",
                &format!("cannot retire the LAN confirmed-chunk log: {error}"),
            )),
        }
    }

    /// Compacts the append tail once it outgrows the amortization bound.
    fn maybe_compact_confirmed(&self) -> Result<(), LomoError> {
        let oversized = fs::metadata(self.paths.confirmed_log())
            .is_ok_and(|metadata| metadata.len() > LAN_CONFIRMED_LOG_COMPACT_BYTES);
        if oversized {
            self.compact_confirmed()?;
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

    fn flush_retired(&self) -> Result<(), LomoError> {
        let mut body = Vec::new();
        for ((counterparty, batch_id), retired_at_ms) in &self.retired {
            push_field(&mut body, counterparty.as_str().as_bytes());
            push_field(&mut body, batch_id.as_str().as_bytes());
            body.extend_from_slice(&retired_at_ms.to_be_bytes());
        }
        write_record(&self.paths.retired(), &body)
    }

    /// Removes one confirmed coordinate and journals the downgrade.
    fn drop_confirmed(&mut self, coordinate: &DurableChunkCoordinate) -> Result<(), LomoError> {
        if self.confirmed.remove(coordinate) {
            self.compact_confirmed()?;
        }
        Ok(())
    }

    /// Drops confirmed coordinates that cannot be backed by durable verifiable bytes: the owning
    /// batch is gone, the coordinate is outside the plan, the staged file is missing or torn, or a
    /// fully confirmed payload no longer matches the plan digest. The downgrade is journaled so a
    /// crash cannot resurrect a coordinate that was already found unverifiable.
    fn reconcile_confirmed(&mut self) -> Result<(), LomoError> {
        let coordinates = std::mem::take(&mut self.confirmed);
        let mut dirty = false;
        for coordinate in coordinates {
            if self.staged_chunk_verifiable(&coordinate)? {
                self.confirmed.insert(coordinate);
            } else {
                dirty = true;
            }
        }

        let batch_ids: Vec<LanBatchId> = self.batches.keys().cloned().collect();
        for batch_id in batch_ids {
            let plan = self
                .batches
                .get(&batch_id)
                .map(|batch| batch.plan().clone())
                .ok_or_else(|| {
                    corrupt_state(
                        "lan_batch_record_invalid",
                        "durable batch disappeared during reconciliation",
                    )
                })?;
            for (item_index, attachment_slot) in planned_payload_coordinates(&plan)? {
                let payload = planned_payload(&plan, item_index, attachment_slot)?;
                let total_chunks = chunk_count(payload.size_bytes)?;
                if total_chunks == 0 {
                    continue;
                }
                let all_confirmed = (0..total_chunks).all(|chunk_index| {
                    self.confirmed.contains(&DurableChunkCoordinate {
                        batch_id: batch_id.as_str().to_owned(),
                        item_index: payload.item_index,
                        attachment_slot: payload.attachment_slot,
                        chunk_index,
                    })
                });
                if !all_confirmed {
                    continue;
                }
                if self.hash_staged_payload(&batch_id, &payload, total_chunks)? != payload.digest {
                    self.unconfirm_payload(
                        &batch_id,
                        payload.item_index,
                        payload.attachment_slot,
                        total_chunks,
                    )?;
                    dirty = true;
                }
            }
        }
        if dirty {
            self.compact_confirmed()?;
        }
        Ok(())
    }

    /// True only when the coordinate belongs to a known batch plan and its staged file exists with
    /// the exact length the plan declares. A torn file is reclaimed as garbage.
    fn staged_chunk_verifiable(
        &self,
        coordinate: &DurableChunkCoordinate,
    ) -> Result<bool, LomoError> {
        let Ok(batch_id) = LanBatchId::parse(&coordinate.batch_id) else {
            return Ok(false);
        };
        let Some(batch) = self.batches.get(&batch_id) else {
            return Ok(false);
        };
        let Ok(payload) = planned_payload(
            batch.plan(),
            coordinate.item_index,
            coordinate.attachment_slot,
        ) else {
            return Ok(false);
        };
        let Ok(expected_length) = expected_chunk_length(payload.size_bytes, coordinate.chunk_index)
        else {
            return Ok(false);
        };
        let path = self.paths.staged_chunk(coordinate);
        match fs::metadata(&path) {
            Ok(metadata) if metadata.len() == expected_length as u64 => Ok(true),
            Ok(_metadata) => {
                fs::remove_file(&path).map_err(|error| {
                    storage(
                        "lan_payload_reclaim_failed",
                        &format!("cannot remove a torn staged LAN chunk: {error}"),
                    )
                })?;
                Ok(false)
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(false),
            Err(error) => Err(storage(
                "lan_chunk_stage_read_failed",
                &format!("cannot inspect a staged LAN chunk: {error}"),
            )),
        }
    }

    /// Hashes a fully confirmed payload straight from its staged chunk files so a large payload
    /// never needs a second in-memory copy.
    fn hash_staged_payload(
        &self,
        batch_id: &LanBatchId,
        payload: &PlannedPayload,
        total_chunks: u32,
    ) -> Result<String, LomoError> {
        let mut hasher = Sha256::new();
        for chunk_index in 0..total_chunks {
            let coordinate = DurableChunkCoordinate {
                batch_id: batch_id.as_str().to_owned(),
                item_index: payload.item_index,
                attachment_slot: payload.attachment_slot,
                chunk_index,
            };
            let bytes = lomo_core::read_bounded(
                &self.paths.staged_chunk(&coordinate),
                CHUNK_PLAINTEXT_BYTES as u64,
            )
            .map_err(|error| match error {
                lomo_core::BoundedReadError::ExceedsLimit { .. } => corrupt_state(
                    "lan_chunk_stage_oversized",
                    "a staged LAN chunk exceeds the fixed chunk ceiling",
                ),
                lomo_core::BoundedReadError::Io(error) => storage(
                    "lan_chunk_stage_read_failed",
                    &format!("cannot read a staged LAN chunk: {error}"),
                ),
            })?;
            hasher.update(&bytes);
        }
        Ok(format!("{:x}", hasher.finalize()))
    }

    /// Deletes staged payload subtrees whose batch id no longer owns a durable batch record.
    fn reclaim_orphan_payloads(&self) -> Result<(), LomoError> {
        let dir = self.paths.payloads_dir();
        let entries = match fs::read_dir(&dir) {
            Ok(entries) => entries,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(error) => {
                return Err(storage(
                    "lan_payload_reclaim_failed",
                    &format!("cannot inspect staged LAN payloads: {error}"),
                ));
            }
        };
        for entry in entries {
            let entry = entry.map_err(|error| {
                storage(
                    "lan_payload_reclaim_failed",
                    &format!("cannot inspect a staged LAN payload directory: {error}"),
                )
            })?;
            let is_dir = entry.file_type().map_err(|error| {
                storage(
                    "lan_payload_reclaim_failed",
                    &format!("cannot inspect a staged LAN payload entry: {error}"),
                )
            })?;
            if !is_dir.is_dir() {
                continue;
            }
            let name = entry.file_name();
            let known = LanBatchId::parse(&name.to_string_lossy())
                .is_ok_and(|batch_id| self.batches.contains_key(&batch_id));
            if !known {
                fs::remove_dir_all(entry.path()).map_err(|error| {
                    storage(
                        "lan_payload_reclaim_failed",
                        &format!("cannot reclaim an orphaned staged LAN payload: {error}"),
                    )
                })?;
            }
        }
        Ok(())
    }
}

/// The instant a received batch becomes terminal for reclamation: explicit rejection, or the end
/// of the approval window that bounded replay.
const fn received_terminal_anchor(batch: &LanDurableBatch) -> Option<i64> {
    match batch.decision() {
        LanBatchDecision::Pending => None,
        LanBatchDecision::Rejected { rejected_at_ms } => Some(*rejected_at_ms),
        LanBatchDecision::Approved { approval, .. } => {
            Some(approval.approved_at_ms().saturating_add(approval.ttl_ms()))
        }
    }
}

/// The instant an outgoing batch becomes terminal for reclamation: the recorded terminal instant,
/// or — for records written before terminal timestamps existed — epoch 0 when the durable facts
/// are already terminal (rejected or fully resolved items).
fn outgoing_terminal_anchor(batch: &LanDurableOutgoingBatch) -> Option<i64> {
    if let Some(terminal_at_ms) = batch.terminal_at_ms {
        return Some(terminal_at_ms);
    }
    if batch.decision == LanOutgoingDecision::Rejected || batch.snapshot.is_complete() {
        return Some(0);
    }
    None
}

fn read_peers(path: &Path) -> Result<BTreeMap<DeviceId, PeerRecord>, LomoError> {
    let Some((body, _schema)) = read_record(path)? else {
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
    let Some((body, _schema)) = read_record(path)? else {
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

fn read_sessions(path: &Path) -> Result<BTreeMap<LanSessionId, i64>, LomoError> {
    let Some((body, schema)) = read_record(path)? else {
        return Ok(BTreeMap::new());
    };
    let mut sessions = BTreeMap::new();
    let mut cursor = 0_usize;
    while cursor < body.len() {
        let (session_bytes, next) = take_field(&body, cursor)?;
        cursor = next;
        let accepted_at_ms = if schema >= 4 {
            let accepted_at_ms = take_i64(&body, cursor)?;
            cursor = cursor.saturating_add(8);
            accepted_at_ms
        } else {
            // Schema 3 carried no timestamp; such a witness predates any live session TTL and
            // retires at the first maintenance pass.
            0
        };
        let session_text = std::str::from_utf8(session_bytes).map_err(|_error| {
            corrupt_state(
                "lan_session_record_invalid",
                "session id is not valid UTF-8",
            )
        })?;
        sessions.insert(LanSessionId::parse(session_text)?, accepted_at_ms);
    }
    Ok(sessions)
}

fn read_retired(path: &Path) -> Result<BTreeMap<(DeviceId, LanBatchId), i64>, LomoError> {
    let Some((body, _schema)) = read_record(path)? else {
        return Ok(BTreeMap::new());
    };
    let mut retired = BTreeMap::new();
    let mut cursor = 0_usize;
    while cursor < body.len() {
        let (counterparty_bytes, next) = take_field(&body, cursor)?;
        let (batch_bytes, next) = take_field(&body, next)?;
        let retired_at_ms = take_i64(&body, next)?;
        cursor = next.saturating_add(8);
        let counterparty = DeviceId::parse(record_text(counterparty_bytes)?)?;
        let batch_id = LanBatchId::parse(record_text(batch_bytes)?)?;
        retired.insert((counterparty, batch_id), retired_at_ms);
    }
    Ok(retired)
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
        match &batch.failure {
            Some(failure) => {
                body.push(1);
                push_field(&mut body, failure.code.as_bytes());
                body.extend_from_slice(&failure.failed_at_ms.to_be_bytes());
            }
            None => body.push(0),
        }
        body.extend_from_slice(&batch.terminal_at_ms.unwrap_or(0).to_be_bytes());
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
    let Some((body, _schema)) = read_record(path)? else {
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
    let Some((body, schema)) = read_record(path)? else {
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
        let failure = if schema >= 4 {
            match take_u8(&body, cursor)? {
                0 => {
                    cursor = cursor.saturating_add(1);
                    None
                }
                1 => {
                    let (code_bytes, next) = take_field(&body, cursor.saturating_add(1))?;
                    let code = record_text(code_bytes)?.to_owned();
                    let failed_at_ms = take_i64(&body, next)?;
                    cursor = next.saturating_add(8);
                    Some(LanOutgoingFailure { code, failed_at_ms })
                }
                _ => return Err(batch_record_invalid()),
            }
        } else {
            None
        };
        let terminal_at_ms = if schema >= 4 {
            let terminal_at_ms = take_i64(&body, cursor)?;
            cursor = cursor.saturating_add(8);
            (terminal_at_ms != 0).then_some(terminal_at_ms)
        } else {
            None
        };
        let batch_id = plan.batch_id().clone();
        let durable = LanDurableOutgoingBatch {
            plan,
            session_id,
            peer_device_id,
            peer_display_name,
            decision,
            failure,
            terminal_at_ms,
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

fn read_confirmed(path: &Path) -> Result<BTreeSet<DurableChunkCoordinate>, LomoError> {
    let Some((body, _schema)) = read_record(path)? else {
        return Ok(BTreeSet::new());
    };
    let mut confirmed = BTreeSet::new();
    let mut cursor = 0_usize;
    while cursor < body.len() {
        let coordinate = take_confirmed_entry(&body, &mut cursor)?;
        confirmed.insert(coordinate);
    }
    Ok(confirmed)
}

/// Replays the append-only confirmed tail. The tail carries the same entry encoding as the
/// compacted snapshot; a torn tail (a crash mid-append) contributes its valid prefix and the rest
/// is dropped — the next compaction rewrites the snapshot from the in-memory set anyway.
fn read_confirmed_log(path: &Path) -> Result<BTreeSet<DurableChunkCoordinate>, LomoError> {
    let limit = MAX_LAN_RECORD_BYTES.saturating_mul(8) as u64;
    let file = match fs::File::open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            return Ok(BTreeSet::new());
        }
        Err(error) => {
            return Err(storage(
                "lan_journal_read_failed",
                &format!("cannot read the LAN confirmed-chunk log: {error}"),
            ));
        }
    };
    let mut bytes = Vec::new();
    if let Err(error) = file.take(limit.saturating_add(1)).read_to_end(&mut bytes) {
        return Err(storage(
            "lan_journal_read_failed",
            &format!("cannot read the LAN confirmed-chunk log: {error}"),
        ));
    }
    if bytes.len() as u64 > limit {
        return Err(corrupt_state(
            "lan_journal_record_oversized",
            "the LAN confirmed-chunk log exceeds its durable bound",
        ));
    }
    let mut confirmed = BTreeSet::new();
    let mut cursor = 0_usize;
    while cursor < bytes.len() {
        match take_confirmed_entry(&bytes, &mut cursor) {
            Ok(coordinate) => {
                confirmed.insert(coordinate);
            }
            Err(_torn) => break,
        }
    }
    Ok(confirmed)
}

fn take_confirmed_entry(
    body: &[u8],
    cursor: &mut usize,
) -> Result<DurableChunkCoordinate, LomoError> {
    let (batch_bytes, next) = take_field(body, *cursor)?;
    let item_index = take_u16(body, next)?;
    let attachment_slot = take_u16(body, next.saturating_add(2))?;
    let chunk_index = take_u32(body, next.saturating_add(4))?;
    *cursor = next.saturating_add(8);

    let batch_id = std::str::from_utf8(batch_bytes).map_err(|_error| {
        corrupt_state("lan_chunk_record_invalid", "batch id is not valid UTF-8")
    })?;
    LanBatchId::parse(batch_id)?;
    Ok(DurableChunkCoordinate {
        batch_id: batch_id.to_owned(),
        item_index,
        attachment_slot,
        chunk_index,
    })
}

/// Re-hashes an existing assembled artifact so reuse is backed by current bytes, not by the fact
/// it was verified once. `None` means no assembled file exists yet.
fn verify_assembled_payload(target: &Path) -> Result<Option<LanStagedPayload>, LomoError> {
    let file = match fs::File::open(target) {
        Ok(file) => file,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => {
            return Err(storage(
                "lan_payload_assemble_failed",
                &format!("cannot inspect a staged LAN payload: {error}"),
            ));
        }
    };
    let mut hasher = Sha256::new();
    let mut sink = HashWriter {
        inner: io::sink(),
        hasher: &mut hasher,
    };
    let mut reader = io::BufReader::new(file);
    let size_bytes = io::copy(&mut reader, &mut sink).map_err(|error| {
        storage(
            "lan_payload_assemble_failed",
            &format!("cannot hash a staged LAN payload: {error}"),
        )
    })?;
    Ok(Some(LanStagedPayload {
        path: target.to_path_buf(),
        size_bytes,
        digest: format!("{:x}", hasher.finalize()),
    }))
}

/// The outcome of streaming one payload's confirmed chunks into an assembled artifact.
enum StreamedChunks {
    Complete(u64),
    Missing(DurableChunkCoordinate),
}

/// A write-through tee that feeds every written byte to a SHA-256 hasher.
struct HashWriter<'a, W: io::Write> {
    inner: W,
    hasher: &'a mut Sha256,
}

impl<W: io::Write> io::Write for HashWriter<'_, W> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let written = self.inner.write(buf)?;
        if let Some(consumed) = buf.get(..written) {
            self.hasher.update(consumed);
        }
        Ok(written)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }

    fn write_all(&mut self, buf: &[u8]) -> io::Result<()> {
        self.inner.write_all(buf)?;
        self.hasher.update(buf);
        Ok(())
    }
}

fn read_record(path: &Path) -> Result<Option<(Vec<u8>, u32)>, LomoError> {
    match lomo_core::read_bounded(path, MAX_LAN_RECORD_BYTES as u64) {
        Ok(bytes) => decode_record_versioned(&bytes, LAN_DURABLE_SCHEMA_MIN_READ).map(Some),
        Err(lomo_core::BoundedReadError::Io(error)) if error.kind() == io::ErrorKind::NotFound => {
            Ok(None)
        }
        Err(lomo_core::BoundedReadError::ExceedsLimit { .. }) => Err(corrupt_state(
            "lan_journal_record_oversized",
            "the LAN journal record exceeds the durable record byte bound",
        )),
        Err(lomo_core::BoundedReadError::Io(error)) => Err(storage(
            "lan_journal_read_failed",
            &format!("cannot read the LAN journal record: {error}"),
        )),
    }
}

/// Writes a record temp-then-rename so a crash never leaves a half record.
///
/// The temp file is flushed before the rename and the parent directory is flushed after it, so a
/// power loss leaves either the previous record or the complete new one — not a rename that the
/// filesystem later forgets.
fn write_record(path: &Path, body: &[u8]) -> Result<(), LomoError> {
    let encoded = encode_record(body)?;
    let temp = path.with_extension("rec.tmp");
    write_synced(
        &temp,
        &encoded,
        "lan_journal_write_failed",
        "cannot write the LAN journal temp record",
    )?;
    fs::rename(&temp, path).map_err(|error| {
        storage(
            "lan_journal_commit_failed",
            &format!("cannot commit the LAN journal record: {error}"),
        )
    })?;
    sync_parent_directory(path)
}

/// Writes one staged chunk, surfacing storage exhaustion as a batch-scoped resource limit rather
/// than a generic fault so the peer records an explicit failure disposition.
fn write_chunk_synced(path: &Path, bytes: &[u8]) -> Result<(), LomoError> {
    let mut file = fs::File::create(path).map_err(|error| stage_io_error(&error))?;
    file.write_all(bytes)
        .and_then(|()| file.sync_all())
        .map_err(|error| stage_io_error(&error))
}

fn stage_io_error(error: &io::Error) -> LomoError {
    if error.kind() == io::ErrorKind::StorageFull {
        return resource_limit(
            "lan_stage_quota_exceeded",
            "device storage is exhausted; the batch keeps an explicit failure result",
        );
    }
    storage(
        "lan_chunk_stage_write_failed",
        &format!("cannot write a staged LAN chunk: {error}"),
    )
}

/// Writes `bytes` and flushes them to stable storage before returning.
fn write_synced(
    path: &Path,
    bytes: &[u8],
    write_code: &str,
    write_message: &str,
) -> Result<(), LomoError> {
    let mut file = fs::File::create(path)
        .map_err(|error| storage(write_code, &format!("{write_message}: {error}")))?;
    file.write_all(bytes)
        .and_then(|()| file.sync_all())
        .map_err(|error| storage(write_code, &format!("{write_message}: {error}")))
}

/// Flushes the directory entry so a preceding rename survives a crash.
fn sync_parent_directory(path: &Path) -> Result<(), LomoError> {
    let Some(parent) = path.parent() else {
        return Ok(());
    };
    let directory = fs::File::open(parent).map_err(|error| {
        storage(
            "lan_journal_sync_failed",
            &format!("cannot open the LAN journal directory: {error}"),
        )
    })?;
    directory.sync_all().map_err(|error| {
        storage(
            "lan_journal_sync_failed",
            &format!("cannot sync the LAN journal directory: {error}"),
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
