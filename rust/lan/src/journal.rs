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

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

use crate::batch::{LanApproval, LanBatchId};
use crate::error::{corrupt_state, resource_limit, storage, validation};
use crate::identity::{DeviceId, DevicePublicKey, DisplayName, PeerRecord};
use crate::limits::{LAN_DURABLE_SCHEMA, MAX_LAN_RECORD_BYTES, MAX_TRUSTED_PEERS};
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
    pub fn confirmed_chunks(&self) -> PathBuf {
        self.root.join("chunks.rec")
    }
}

/// The durable LAN journal.
#[derive(Clone, Debug)]
pub struct LanJournal {
    paths: LanJournalPaths,
    peers: BTreeMap<DeviceId, PeerRecord>,
    approvals: BTreeMap<LanBatchId, LanApproval>,
    confirmed: Vec<ChunkBinding>,
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
        let approvals = read_approvals(&paths.approvals())?;
        let confirmed = read_confirmed(&paths.confirmed_chunks())?;
        Ok(Self {
            paths,
            peers,
            approvals,
            confirmed,
        })
    }

    /// Trusted peers by device id.
    #[must_use]
    pub const fn peers(&self) -> &BTreeMap<DeviceId, PeerRecord> {
        &self.peers
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
        if !self.confirmed.contains(binding) {
            self.confirmed.push(binding.clone());
            self.flush_confirmed()?;
        }
        Ok(())
    }

    /// True when the chunk is already confirmed.
    #[must_use]
    pub fn is_chunk_confirmed(&self, binding: &ChunkBinding) -> bool {
        self.confirmed.contains(binding)
    }

    /// Chunk indices still to send for one item/attachment slot in one session.
    ///
    /// This is the resume answer: everything not already confirmed, in order.
    #[must_use]
    pub fn unconfirmed_chunk_indices(
        &self,
        session_id: &LanSessionId,
        batch_id: &LanBatchId,
        item_index: u16,
        attachment_slot: u16,
        total_chunks: u32,
    ) -> Vec<u32> {
        (0..total_chunks)
            .filter(|chunk_index| {
                ChunkBinding::new(
                    session_id,
                    batch_id.as_str(),
                    item_index,
                    attachment_slot,
                    *chunk_index,
                )
                .is_ok_and(|binding| !self.is_chunk_confirmed(&binding))
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

    fn flush_confirmed(&self) -> Result<(), LomoError> {
        let mut body = Vec::new();
        for binding in &self.confirmed {
            push_field(&mut body, binding.session_id().as_str().as_bytes());
            push_field(&mut body, binding.batch_id().as_bytes());
            body.extend_from_slice(&binding.item_index().to_be_bytes());
            body.extend_from_slice(&binding.attachment_slot().to_be_bytes());
            body.extend_from_slice(&binding.chunk_index().to_be_bytes());
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

fn read_confirmed(path: &Path) -> Result<Vec<ChunkBinding>, LomoError> {
    let Some(body) = read_record(path)? else {
        return Ok(Vec::new());
    };
    let mut confirmed = Vec::new();
    let mut cursor = 0_usize;
    while cursor < body.len() {
        let (session_bytes, next) = take_field(&body, cursor)?;
        let (batch_bytes, next) = take_field(&body, next)?;
        let item_index = take_u16(&body, next)?;
        let attachment_slot = take_u16(&body, next.saturating_add(2))?;
        let chunk_index = take_u32(&body, next.saturating_add(4))?;
        cursor = next.saturating_add(8);

        let session_id =
            LanSessionId::parse(std::str::from_utf8(session_bytes).map_err(|_error| {
                corrupt_state("lan_chunk_record_invalid", "session id is not valid UTF-8")
            })?)?;
        let batch_id = std::str::from_utf8(batch_bytes).map_err(|_error| {
            corrupt_state("lan_chunk_record_invalid", "batch id is not valid UTF-8")
        })?;
        confirmed.push(ChunkBinding::new(
            &session_id,
            batch_id,
            item_index,
            attachment_slot,
            chunk_index,
        )?);
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

fn take_u32(body: &[u8], cursor: usize) -> Result<u32, LomoError> {
    let slice = body
        .get(cursor..cursor.saturating_add(4))
        .ok_or_else(|| corrupt_state("lan_journal_truncated", "record field is truncated"))?;
    let bytes: [u8; 4] = slice
        .try_into()
        .map_err(|_error| corrupt_state("lan_journal_truncated", "record field is truncated"))?;
    Ok(u32::from_be_bytes(bytes))
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
