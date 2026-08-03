//! Process-owned LAN lifecycle and bounded Android platform snapshots.
//!
//! Android owns the facts only it can observe — local-network permission, eligible interface
//! addresses and NSD results. It publishes those facts as monotonic snapshots. This module owns
//! every decision made from them: boundary validation, listener bind/release, protocol filtering
//! and the effective state exposed back to Kotlin.

use std::collections::{BTreeMap, BTreeSet};
use std::net::{IpAddr, SocketAddr, TcpListener, TcpStream};
use std::path::Path;
use std::time::Duration;

use aws_lc_rs::agreement;
use aws_lc_rs::rand::{SecureRandom, SystemRandom};
use lomo_core::LomoError;
use sha2::{Digest, Sha256};

use crate::batch::{
    LanApproval, LanAttachmentRef, LanBatchDecision, LanBatchId, LanBatchPlan, LanBatchPreview,
    LanDurableBatch, LanItemOutcome, LanItemPlan,
};
use crate::commit::{
    ApprovedGeneration, AuthorizedReceivedAttachment, AuthorizedReceivedCreate, ReceivedItem,
    authorize_item_commit,
};
use crate::error::{authentication, network, permission, resource_limit, validation};
use crate::frame::{FrameKind, LAN_PROTOCOL_VERSION, LanFrame};
use crate::identity::{DeviceId, DisplayName, PeerRecord};
use crate::journal::{LanDurableOutgoingBatch, LanJournal, LanJournalPaths, LanOutgoingDecision};
use crate::limits::RUNTIME_CHUNK_PLAINTEXT_BYTES;
use crate::pairing::{PairingTranscript, derive_pairing_code, verify_pairing_confirmation};
use crate::session::{
    ATTACHMENT_SLOT_BODY, ChunkBinding, LanSessionId, SessionControlKind, SessionKey,
    SessionTranscript,
};
use crate::transport::{LanDeadlines, bind_listener, connect_peer, poll_peer};

const MAX_BIND_CANDIDATES: usize = 16;
const MAX_DISCOVERED_ENDPOINTS: usize = 128;
const PAIRING_ID_BYTES: usize = 16;
const PAIRING_ID_HEX_BYTES: usize = PAIRING_ID_BYTES * 2;
const PAIRING_SOCKET_DEADLINE: Duration = Duration::from_secs(5);
const LISTENER_POLL_TIMEOUT: Duration = Duration::from_millis(100);
/// Opaque identity of one pairing exchange.
#[derive(Clone, Debug, Eq, PartialEq, Hash, Ord, PartialOrd)]
pub struct LanPairingId(String);

impl LanPairingId {
    fn generate() -> Result<Self, LomoError> {
        let mut bytes = [0_u8; PAIRING_ID_BYTES];
        SystemRandom::new().fill(&mut bytes).map_err(|_error| {
            authentication(
                "lan_pairing_random_failed",
                "secure random generation failed for the pairing identity",
            )
        })?;
        Ok(Self(hex_bytes(&bytes)))
    }

    /// Parses a pairing identity received from the v2 wire.
    ///
    /// # Errors
    ///
    /// Validation when the identity is not exactly 16 random bytes encoded as lowercase hex.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        if raw.len() != PAIRING_ID_HEX_BYTES || !raw.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err(validation(
                "lan_pairing_id_invalid",
                "pairing id must be 32 hexadecimal characters",
            ));
        }
        Ok(Self(raw.to_ascii_lowercase()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Bounded pairing facts shown to the user and passed to the Keystore signing adapter.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanPairingChallenge {
    pairing_id: LanPairingId,
    peer_device_id: DeviceId,
    peer_display_name: DisplayName,
    short_code: String,
    transcript_to_sign: Vec<u8>,
    deadline_ms: i64,
}

impl LanPairingChallenge {
    #[must_use]
    pub const fn pairing_id(&self) -> &LanPairingId {
        &self.pairing_id
    }

    #[must_use]
    pub const fn peer_device_id(&self) -> &DeviceId {
        &self.peer_device_id
    }

    #[must_use]
    pub const fn peer_display_name(&self) -> &DisplayName {
        &self.peer_display_name
    }

    #[must_use]
    pub fn short_code(&self) -> &str {
        &self.short_code
    }

    #[must_use]
    pub fn transcript_to_sign(&self) -> &[u8] {
        &self.transcript_to_sign
    }

    #[must_use]
    pub const fn deadline_ms(&self) -> i64 {
        self.deadline_ms
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct LocalDeviceIdentity {
    public_key: crate::identity::DevicePublicKey,
    display_name: DisplayName,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct PendingPairing {
    challenge: LanPairingChallenge,
    transcript: PairingTranscript,
    peer_public_key: crate::identity::DevicePublicKey,
    peer_address: SocketAddr,
    local_confirmed: bool,
    peer_signature: Option<Vec<u8>>,
}

/// External-signature challenge for one mutually authenticated connection.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanSessionChallenge {
    session_id: LanSessionId,
    peer_device_id: DeviceId,
    transcript_to_sign: Vec<u8>,
    deadline_ms: i64,
}

impl LanSessionChallenge {
    #[must_use]
    pub const fn session_id(&self) -> &LanSessionId {
        &self.session_id
    }

    #[must_use]
    pub const fn peer_device_id(&self) -> &DeviceId {
        &self.peer_device_id
    }

    #[must_use]
    pub fn transcript_to_sign(&self) -> &[u8] {
        &self.transcript_to_sign
    }

    #[must_use]
    pub const fn deadline_ms(&self) -> i64 {
        self.deadline_ms
    }
}

/// Effective product session phase.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LanSessionPhase {
    Authenticated,
}

/// Public state of one authenticated session. Key material never crosses this type.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanSessionSnapshot {
    session_id: LanSessionId,
    peer_device_id: DeviceId,
    phase: LanSessionPhase,
}

impl LanSessionSnapshot {
    #[must_use]
    pub const fn session_id(&self) -> &LanSessionId {
        &self.session_id
    }

    #[must_use]
    pub const fn peer_device_id(&self) -> &DeviceId {
        &self.peer_device_id
    }

    #[must_use]
    pub const fn phase(&self) -> LanSessionPhase {
        self.phase
    }
}

/// One durable received batch awaiting an explicit user decision.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanPendingBatch {
    session_id: LanSessionId,
    preview: LanBatchPreview,
}

impl LanPendingBatch {
    #[must_use]
    pub const fn session_id(&self) -> &LanSessionId {
        &self.session_id
    }

    #[must_use]
    pub const fn preview(&self) -> &LanBatchPreview {
        &self.preview
    }
}

/// Bounded runtime facts requiring Android UI or Keystore action.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct LanRuntimeInbox {
    pairing_challenges: Vec<LanPairingChallenge>,
    session_challenges: Vec<LanSessionChallenge>,
    active_sessions: Vec<LanSessionSnapshot>,
    pending_batches: Vec<LanPendingBatch>,
    batch_recoveries: Vec<LanBatchRecovery>,
    committable_items: Vec<LanCommittableItem>,
    outgoing_batches: Vec<LanOutgoingBatch>,
}

impl LanRuntimeInbox {
    #[must_use]
    pub fn pairing_challenges(&self) -> &[LanPairingChallenge] {
        &self.pairing_challenges
    }

    #[must_use]
    pub fn session_challenges(&self) -> &[LanSessionChallenge] {
        &self.session_challenges
    }

    #[must_use]
    pub fn active_sessions(&self) -> &[LanSessionSnapshot] {
        &self.active_sessions
    }

    #[must_use]
    pub fn pending_batches(&self) -> &[LanPendingBatch] {
        &self.pending_batches
    }

    #[must_use]
    pub fn batch_recoveries(&self) -> &[LanBatchRecovery] {
        &self.batch_recoveries
    }

    #[must_use]
    pub fn committable_items(&self) -> &[LanCommittableItem] {
        &self.committable_items
    }

    #[must_use]
    pub fn outgoing_batches(&self) -> &[LanOutgoingBatch] {
        &self.outgoing_batches
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LanReceivedBatchDecision {
    Pending,
    Approved,
    Rejected,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum LanReceivedItemOutcome {
    Pending,
    Committed { memo_id: String },
    Failed { code: String },
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanReceivedItemRecovery {
    item_id: String,
    item_index: u16,
    outcome: LanReceivedItemOutcome,
}

impl LanReceivedItemRecovery {
    #[must_use]
    pub fn item_id(&self) -> &str {
        &self.item_id
    }

    #[must_use]
    pub const fn item_index(&self) -> u16 {
        self.item_index
    }

    #[must_use]
    pub const fn outcome(&self) -> &LanReceivedItemOutcome {
        &self.outcome
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanBatchRecovery {
    session_id: LanSessionId,
    preview: LanBatchPreview,
    decision: LanReceivedBatchDecision,
    items: Vec<LanReceivedItemRecovery>,
}

impl LanBatchRecovery {
    #[must_use]
    pub const fn session_id(&self) -> &LanSessionId {
        &self.session_id
    }

    #[must_use]
    pub const fn preview(&self) -> &LanBatchPreview {
        &self.preview
    }

    #[must_use]
    pub const fn decision(&self) -> LanReceivedBatchDecision {
        self.decision
    }

    #[must_use]
    pub fn items(&self) -> &[LanReceivedItemRecovery] {
        &self.items
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanCommittableItem {
    batch_id: LanBatchId,
    item_index: u16,
}

impl LanCommittableItem {
    #[must_use]
    pub const fn batch_id(&self) -> &LanBatchId {
        &self.batch_id
    }

    #[must_use]
    pub const fn item_index(&self) -> u16 {
        self.item_index
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LanOutgoingBatchPhase {
    AwaitingApproval,
    Approved,
    Rejected,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanOutgoingBatch {
    batch_id: LanBatchId,
    phase: LanOutgoingBatchPhase,
}

impl LanOutgoingBatch {
    #[must_use]
    pub const fn batch_id(&self) -> &LanBatchId {
        &self.batch_id
    }

    #[must_use]
    pub const fn phase(&self) -> LanOutgoingBatchPhase {
        self.phase
    }
}

#[derive(Debug)]
struct PendingSession {
    challenge: LanSessionChallenge,
    transcript: SessionTranscript,
    peer_public_key: crate::identity::DevicePublicKey,
    peer_address: SocketAddr,
    key: SessionKey,
    local_confirmed: bool,
    peer_signature: Option<Vec<u8>>,
}

#[derive(Debug)]
struct ActiveSession {
    snapshot: LanSessionSnapshot,
    peer_address: SocketAddr,
    key: SessionKey,
}

/// One concrete local address Android says is eligible for the LAN listener.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanBindCandidate(SocketAddr);

impl LanBindCandidate {
    /// Parses a numeric address at the Android/Rust boundary.
    ///
    /// Port zero is permitted for the listener so the OS can choose an available port. Hostnames,
    /// unspecified and multicast addresses are rejected; Kotlin cannot smuggle DNS or interface
    /// selection policy into the protocol core.
    ///
    /// # Errors
    ///
    /// Validation when `host` is not a concrete unicast IP address.
    pub fn parse(host: &str, port: u16) -> Result<Self, LomoError> {
        let ip = host.parse::<IpAddr>().map_err(|_error| {
            validation(
                "lan_bind_address_invalid",
                "LAN bind candidate must be a numeric IP address",
            )
        })?;
        if ip.is_unspecified() || ip.is_multicast() {
            return Err(validation(
                "lan_bind_address_invalid",
                "LAN bind candidate must be a concrete unicast address",
            ));
        }
        Ok(Self(SocketAddr::new(ip, port)))
    }

    #[must_use]
    pub const fn address(&self) -> SocketAddr {
        self.0
    }
}

/// Monotonic Android network facts.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanNetworkSnapshot {
    revision: u64,
    local_network_permission_granted: bool,
    candidates: Vec<LanBindCandidate>,
}

impl LanNetworkSnapshot {
    /// Builds a bounded network snapshot.
    ///
    /// # Errors
    ///
    /// Validation for revision zero; resource-limit above the candidate ceiling.
    pub fn new(
        revision: u64,
        local_network_permission_granted: bool,
        candidates: Vec<LanBindCandidate>,
    ) -> Result<Self, LomoError> {
        if revision == 0 {
            return Err(validation(
                "lan_network_snapshot_revision_invalid",
                "LAN network snapshot revision must be non-zero",
            ));
        }
        if candidates.len() > MAX_BIND_CANDIDATES {
            return Err(resource_limit(
                "lan_network_snapshot_too_large",
                "LAN network snapshot exceeds the 16-candidate ceiling",
            ));
        }
        Ok(Self {
            revision,
            local_network_permission_granted,
            candidates,
        })
    }

    #[must_use]
    pub const fn revision(&self) -> u64 {
        self.revision
    }
}

/// One v2 endpoint discovered by Android NSD.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DiscoveredPeerEndpoint {
    device_id: DeviceId,
    display_name: DisplayName,
    address: SocketAddr,
}

impl DiscoveredPeerEndpoint {
    /// Parses one NSD result at the boundary.
    ///
    /// # Errors
    ///
    /// Validation for foreign protocol, malformed identity/name/address, a zero port, or a
    /// non-concrete address.
    pub fn parse(
        device_id: &str,
        display_name: &str,
        host: &str,
        port: u16,
        protocol_version: u16,
    ) -> Result<Self, LomoError> {
        if protocol_version != LAN_PROTOCOL_VERSION {
            return Err(validation(
                "lan_discovery_protocol_unsupported",
                "only LAN protocol v2 discovery entries are accepted",
            ));
        }
        if port == 0 {
            return Err(validation(
                "lan_discovery_address_invalid",
                "discovered LAN peer port must be non-zero",
            ));
        }
        let ip = host.parse::<IpAddr>().map_err(|_error| {
            validation(
                "lan_discovery_address_invalid",
                "discovered LAN peer must have a numeric IP address",
            )
        })?;
        if ip.is_unspecified() || ip.is_multicast() {
            return Err(validation(
                "lan_discovery_address_invalid",
                "discovered LAN peer must have a concrete unicast address",
            ));
        }
        Ok(Self {
            device_id: DeviceId::parse(device_id)?,
            display_name: DisplayName::parse(display_name)?,
            address: SocketAddr::new(ip, port),
        })
    }

    #[must_use]
    pub const fn device_id(&self) -> &DeviceId {
        &self.device_id
    }

    #[must_use]
    pub const fn display_name(&self) -> &DisplayName {
        &self.display_name
    }

    #[must_use]
    pub const fn address(&self) -> SocketAddr {
        self.address
    }
}

/// Monotonic, bounded NSD facts.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanDiscoverySnapshot {
    revision: u64,
    peers: Vec<DiscoveredPeerEndpoint>,
}

impl LanDiscoverySnapshot {
    /// Builds a bounded discovery snapshot.
    ///
    /// # Errors
    ///
    /// Validation for revision zero or duplicate device/address pairs; resource-limit above 128
    /// endpoints.
    pub fn new(revision: u64, peers: Vec<DiscoveredPeerEndpoint>) -> Result<Self, LomoError> {
        if revision == 0 {
            return Err(validation(
                "lan_discovery_snapshot_revision_invalid",
                "LAN discovery snapshot revision must be non-zero",
            ));
        }
        if peers.len() > MAX_DISCOVERED_ENDPOINTS {
            return Err(resource_limit(
                "lan_discovery_snapshot_too_large",
                "LAN discovery snapshot exceeds the 128-endpoint ceiling",
            ));
        }
        for (index, peer) in peers.iter().enumerate() {
            if peers
                .iter()
                .skip(index.saturating_add(1))
                .any(|other| other.device_id == peer.device_id || other.address == peer.address)
            {
                return Err(validation(
                    "lan_discovery_snapshot_duplicate",
                    "LAN discovery snapshot contains a duplicate device or endpoint",
                ));
            }
        }
        Ok(Self { revision, peers })
    }

    #[must_use]
    pub const fn revision(&self) -> u64 {
        self.revision
    }
}

/// Effective Rust-owned service lifecycle phase.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LanServicePhase {
    Stopped,
    Listening,
}

/// Effective service state returned to adapters and UI.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanServiceSnapshot {
    phase: LanServicePhase,
    listen_address: Option<SocketAddr>,
}

impl LanServiceSnapshot {
    const fn stopped() -> Self {
        Self {
            phase: LanServicePhase::Stopped,
            listen_address: None,
        }
    }

    const fn listening(address: SocketAddr) -> Self {
        Self {
            phase: LanServicePhase::Listening,
            listen_address: Some(address),
        }
    }

    #[must_use]
    pub const fn phase(&self) -> LanServicePhase {
        self.phase
    }

    #[must_use]
    pub fn listen_address(&self) -> Option<String> {
        self.listen_address.map(|address| address.to_string())
    }
}

/// The single process-owned LAN runtime.
///
/// It owns the durable installation journal and the only listener. Repeated `start` is idempotent;
/// `stop` drops the listener synchronously so no second Kotlin/native server can remain alive.
#[derive(Debug)]
pub struct LanServiceManager {
    journal: LanJournal,
    identity: Option<LocalDeviceIdentity>,
    network: Option<LanNetworkSnapshot>,
    discovery: Option<LanDiscoverySnapshot>,
    listener: Option<TcpListener>,
    service: LanServiceSnapshot,
    pending_pairings: BTreeMap<LanPairingId, PendingPairing>,
    pending_sessions: BTreeMap<LanSessionId, PendingSession>,
    active_sessions: BTreeMap<LanSessionId, ActiveSession>,
}

impl LanServiceManager {
    /// Opens the installation-level journal and initializes a stopped runtime.
    ///
    /// # Errors
    ///
    /// Storage/corruption when durable peer trust cannot be read safely.
    pub fn open(app_private_root: impl AsRef<Path>) -> Result<Self, LomoError> {
        let journal = LanJournal::open(LanJournalPaths::new(app_private_root)?)?;
        Ok(Self {
            journal,
            identity: None,
            network: None,
            discovery: None,
            listener: None,
            service: LanServiceSnapshot::stopped(),
            pending_pairings: BTreeMap::new(),
            pending_sessions: BTreeMap::new(),
            active_sessions: BTreeMap::new(),
        })
    }

    /// Installs the public half of the non-exportable device key and the local display name.
    ///
    /// Repeating the same facts is idempotent; replacing the key inside a live runtime is rejected
    /// because peer/device identity must not silently rotate.
    ///
    /// # Errors
    ///
    /// Conflict when different identity facts replace an installed identity.
    pub fn configure_identity(
        &mut self,
        public_key: crate::identity::DevicePublicKey,
        display_name: DisplayName,
    ) -> Result<(), LomoError> {
        let identity = LocalDeviceIdentity {
            public_key,
            display_name,
        };
        if let Some(current) = &self.identity {
            if current == &identity {
                return Ok(());
            }
            return Err(crate::error::conflict(
                "lan_device_identity_changed",
                "device identity cannot change while the LAN runtime is open",
            ));
        }
        self.identity = Some(identity);
        Ok(())
    }

    /// Returns the bounded work queue derived from live handshakes and durable batch truth.
    ///
    /// # Errors
    ///
    /// Corruption/validation when durable batch coordinates cannot reconstruct valid commit work.
    pub fn inbox(&self) -> Result<LanRuntimeInbox, LomoError> {
        Ok(LanRuntimeInbox {
            pairing_challenges: self
                .pending_pairings
                .values()
                .map(|pending| pending.challenge.clone())
                .collect(),
            session_challenges: self
                .pending_sessions
                .values()
                .map(|pending| pending.challenge.clone())
                .collect(),
            active_sessions: self
                .active_sessions
                .values()
                .map(|active| active.snapshot.clone())
                .collect(),
            pending_batches: self.pending_batches(),
            batch_recoveries: self.batch_recoveries()?,
            committable_items: self.committable_items()?,
            outgoing_batches: self.outgoing_batches(),
        })
    }

    fn pending_batches(&self) -> Vec<LanPendingBatch> {
        self.journal
            .batches()
            .filter(|batch| matches!(batch.decision(), LanBatchDecision::Pending))
            .map(|batch| LanPendingBatch {
                session_id: batch.session_id().clone(),
                preview: batch.preview(),
            })
            .collect()
    }

    fn outgoing_batches(&self) -> Vec<LanOutgoingBatch> {
        self.journal
            .outgoing_batches()
            .map(|batch| LanOutgoingBatch {
                batch_id: batch.plan().batch_id().clone(),
                phase: match batch.decision() {
                    LanOutgoingDecision::AwaitingApproval => {
                        LanOutgoingBatchPhase::AwaitingApproval
                    }
                    LanOutgoingDecision::Approved => LanOutgoingBatchPhase::Approved,
                    LanOutgoingDecision::Rejected => LanOutgoingBatchPhase::Rejected,
                },
            })
            .collect()
    }

    fn committable_items(&self) -> Result<Vec<LanCommittableItem>, LomoError> {
        let mut items = Vec::new();
        for batch in self
            .journal
            .batches()
            .filter(|batch| matches!(batch.decision(), LanBatchDecision::Approved { .. }))
        {
            for item in batch.plan().items() {
                let retryable = matches!(
                    batch.snapshot().outcome(item.item_id()),
                    Some(LanItemOutcome::Pending | LanItemOutcome::Failed { .. })
                );
                if retryable && self.item_payloads_are_confirmed(batch, item.index())? {
                    items.push(LanCommittableItem {
                        batch_id: batch.plan().batch_id().clone(),
                        item_index: item.index(),
                    });
                }
            }
        }
        Ok(items)
    }

    fn batch_recoveries(&self) -> Result<Vec<LanBatchRecovery>, LomoError> {
        self.journal
            .batches()
            .map(|batch| {
                let items = batch
                    .plan()
                    .items()
                    .iter()
                    .map(|item| {
                        let outcome =
                            batch.snapshot().outcome(item.item_id()).ok_or_else(|| {
                                validation(
                                    "lan_item_outcome_missing",
                                    "durable batch item has no recovery outcome",
                                )
                            })?;
                        Ok(LanReceivedItemRecovery {
                            item_id: item.item_id().as_str().to_owned(),
                            item_index: item.index(),
                            outcome: match outcome {
                                LanItemOutcome::Pending => LanReceivedItemOutcome::Pending,
                                LanItemOutcome::Committed { memo_id } => {
                                    LanReceivedItemOutcome::Committed {
                                        memo_id: memo_id.clone(),
                                    }
                                }
                                LanItemOutcome::Failed { code } => {
                                    LanReceivedItemOutcome::Failed { code: code.clone() }
                                }
                            },
                        })
                    })
                    .collect::<Result<Vec<_>, LomoError>>()?;
                Ok(LanBatchRecovery {
                    session_id: batch.session_id().clone(),
                    preview: batch.preview(),
                    decision: match batch.decision() {
                        LanBatchDecision::Pending => LanReceivedBatchDecision::Pending,
                        LanBatchDecision::Approved { .. } => LanReceivedBatchDecision::Approved,
                        LanBatchDecision::Rejected { .. } => LanReceivedBatchDecision::Rejected,
                    },
                    items,
                })
            })
            .collect()
    }

    fn item_payloads_are_confirmed(
        &self,
        batch: &LanDurableBatch,
        item_index: u16,
    ) -> Result<bool, LomoError> {
        let mut coordinates = BTreeSet::from([(item_index, ATTACHMENT_SLOT_BODY)]);
        let item = batch
            .plan()
            .items()
            .get(usize::from(item_index))
            .ok_or_else(|| {
                validation(
                    "lan_item_not_in_batch",
                    "durable batch item index is not present in its plan",
                )
            })?;
        for attachment in item.attachments() {
            let coordinate = batch
                .plan()
                .attachment_transfer_coordinate(attachment.digest())
                .ok_or_else(|| {
                    validation(
                        "lan_attachment_transfer_missing",
                        "durable attachment has no canonical transfer coordinate",
                    )
                })?;
            coordinates.insert(coordinate);
        }
        for (payload_item, slot) in coordinates {
            let payload = planned_payload(batch.plan(), payload_item, slot)?;
            let total_chunks = chunk_count(payload.size_bytes)?;
            if !self
                .journal
                .unconfirmed_chunk_indices(
                    batch.plan().batch_id(),
                    payload.item_index,
                    payload.attachment_slot,
                    total_chunks,
                )
                .is_empty()
            {
                return Ok(false);
            }
        }
        Ok(true)
    }

    /// Replaces Android network facts only when their revision advances.
    ///
    /// # Errors
    ///
    /// Validation when stale or conflicting same-revision facts are submitted.
    pub fn update_network(&mut self, snapshot: LanNetworkSnapshot) -> Result<(), LomoError> {
        if let Some(current) = &self.network {
            if snapshot.revision < current.revision {
                return Err(validation(
                    "lan_network_snapshot_stale",
                    "LAN network snapshot revision moved backwards",
                ));
            }
            if snapshot.revision == current.revision {
                if snapshot == *current {
                    return Ok(());
                }
                return Err(validation(
                    "lan_network_snapshot_revision_conflict",
                    "different LAN network facts reused one revision",
                ));
            }
        }
        self.network = Some(snapshot);
        Ok(())
    }

    /// Replaces Android NSD facts only when their revision advances.
    ///
    /// # Errors
    ///
    /// Validation when stale or conflicting same-revision facts are submitted.
    pub fn update_discovery(&mut self, snapshot: LanDiscoverySnapshot) -> Result<(), LomoError> {
        if let Some(current) = &self.discovery {
            if snapshot.revision < current.revision {
                return Err(validation(
                    "lan_discovery_snapshot_stale",
                    "LAN discovery snapshot revision moved backwards",
                ));
            }
            if snapshot.revision == current.revision {
                if snapshot == *current {
                    return Ok(());
                }
                return Err(validation(
                    "lan_discovery_snapshot_revision_conflict",
                    "different LAN discovery facts reused one revision",
                ));
            }
        }
        self.discovery = Some(snapshot);
        Ok(())
    }

    /// Starts the sole Rust listener from the newest validated platform snapshot.
    ///
    /// # Errors
    ///
    /// Permission without Android local-network authority; validation when no snapshot exists;
    /// network when there is no eligible address or every bind fails.
    pub fn start(&mut self) -> Result<LanServiceSnapshot, LomoError> {
        if self.listener.is_some() {
            return Ok(self.service.clone());
        }
        let snapshot = self.network.as_ref().ok_or_else(|| {
            validation(
                "lan_network_snapshot_missing",
                "LAN service cannot start before Android publishes network facts",
            )
        })?;
        if !snapshot.local_network_permission_granted {
            return Err(permission(
                "lan_local_network_permission_denied",
                "Android local-network permission is required before listener bind",
            ));
        }
        if snapshot.candidates.is_empty() {
            return Err(network(
                "lan_network_unavailable",
                "no eligible LAN bind candidate is available",
                lomo_core::RetryDisposition::Transient,
            ));
        }

        let mut last_error = None;
        for candidate in &snapshot.candidates {
            match bind_listener(candidate.address()) {
                Ok(listener) => {
                    listener.set_nonblocking(true).map_err(|_error| {
                        network(
                            "lan_listener_nonblocking_failed",
                            "LAN listener cannot enter bounded polling mode",
                            lomo_core::RetryDisposition::Transient,
                        )
                    })?;
                    let address = listener.local_addr().map_err(|_error| {
                        network(
                            "lan_listener_address_unavailable",
                            "bound LAN listener has no observable local address",
                            lomo_core::RetryDisposition::Transient,
                        )
                    })?;
                    self.listener = Some(listener);
                    self.service = LanServiceSnapshot::listening(address);
                    return Ok(self.service.clone());
                }
                Err(error) => last_error = Some(error),
            }
        }
        Err(last_error.unwrap_or_else(|| {
            network(
                "lan_listener_bind_failed",
                "no eligible LAN bind candidate could be bound",
                lomo_core::RetryDisposition::Transient,
            )
        }))
    }

    /// Stops the listener synchronously.
    #[must_use]
    pub fn stop(&mut self) -> LanServiceSnapshot {
        self.listener = None;
        self.pending_pairings.clear();
        self.pending_sessions.clear();
        self.active_sessions.clear();
        self.service = LanServiceSnapshot::stopped();
        self.service.clone()
    }

    /// Begins one pairing exchange over the Rust-owned v2 framed socket.
    ///
    /// # Errors
    ///
    /// Validation for a non-positive TTL or missing identity; authentication for a revoked peer;
    /// network/crypto errors from the exchange.
    pub fn begin_pairing(
        &mut self,
        peer: &DiscoveredPeerEndpoint,
        now_ms: i64,
        ttl_ms: i64,
    ) -> Result<LanPairingChallenge, LomoError> {
        if ttl_ms <= 0 {
            return Err(validation(
                "lan_pairing_ttl_invalid",
                "pairing time-to-live must be positive",
            ));
        }
        if let Some(stored) = self.journal.peers().get(peer.device_id()) {
            stored.assert_connectable()?;
        }
        let local = self.identity.clone().ok_or_else(identity_missing)?;
        let pairing_id = LanPairingId::generate()?;
        let ephemeral = EphemeralKey::generate()?;
        let listen_port = self
            .service
            .listen_address
            .ok_or_else(|| {
                validation(
                    "lan_service_not_listening",
                    "pairing requires this endpoint's Rust listener to be started",
                )
            })?
            .port();
        let hello = PairHello {
            pairing_id: pairing_id.clone(),
            public_key: local.public_key.clone(),
            display_name: local.display_name.clone(),
            ephemeral_public: ephemeral.public.clone(),
            listen_port,
            deadline_ms: now_ms.saturating_add(ttl_ms),
        };
        let mut stream = connect_peer(
            peer.address(),
            PAIRING_SOCKET_DEADLINE,
            pairing_deadlines()?,
        )?;
        stream.write_frame(&LanFrame::new(
            FrameKind::PairHello,
            encode_pair_hello(&hello),
        )?)?;
        let accept_frame = stream.read_frame()?;
        if accept_frame.kind() != FrameKind::PairAccept {
            return Err(validation(
                "lan_pairing_frame_order_invalid",
                "pairing initiator expected a PairAccept frame",
            ));
        }
        let accept = decode_pair_accept(accept_frame.payload())?;
        if accept.pairing_id != pairing_id {
            return Err(validation(
                "lan_pairing_id_mismatch",
                "pairing response identity does not match the request",
            ));
        }
        if DeviceId::derive(&accept.public_key) != *peer.device_id() {
            return Err(authentication(
                "lan_pairing_peer_mismatch",
                "pairing response key does not match the discovered peer identity",
            ));
        }
        let shared = ephemeral.agree(&accept.ephemeral_public)?;
        let transcript = PairingTranscript::build(
            &local.public_key,
            &local.display_name,
            &hello.ephemeral_public,
            &accept.public_key,
            &accept.display_name,
            &accept.ephemeral_public,
            &shared,
        )?;
        let challenge = pairing_challenge(
            pairing_id.clone(),
            &accept.public_key,
            accept.display_name.clone(),
            &transcript,
            hello.deadline_ms,
        );
        self.pending_pairings.insert(
            pairing_id,
            PendingPairing {
                challenge: challenge.clone(),
                transcript,
                peer_public_key: accept.public_key,
                peer_address: peer.address(),
                local_confirmed: false,
                peer_signature: None,
            },
        );
        Ok(challenge)
    }

    /// Accepts and processes exactly one pairing frame from the Rust-owned listener.
    ///
    /// # Errors
    ///
    /// Lifecycle when the listener/identity is absent; validation/authentication/network for a
    /// malformed, expired or out-of-order pairing frame.
    pub fn poll_listener(&mut self, now_ms: i64) -> Result<(), LomoError> {
        let listener = self.listener.as_ref().ok_or_else(|| {
            validation(
                "lan_service_not_listening",
                "LAN listener must be started before accepting pairing frames",
            )
        })?;
        let Some((mut stream, peer_address)) =
            poll_peer(listener, LISTENER_POLL_TIMEOUT, pairing_deadlines()?)?
        else {
            return Ok(());
        };
        let frame = stream.read_frame()?;
        match frame.kind() {
            FrameKind::PairHello => {
                self.handle_pair_hello(&mut stream, peer_address, frame.payload(), now_ms)
            }
            FrameKind::PairConfirm => self.handle_pair_confirm(frame.payload(), now_ms),
            FrameKind::SessionHello => {
                self.handle_session_hello(&mut stream, peer_address, frame.payload(), now_ms)
            }
            FrameKind::SessionConfirm => self.handle_session_confirm(frame.payload(), now_ms),
            FrameKind::BatchPrepare => {
                self.handle_batch_prepare(&mut stream, frame.payload(), now_ms)
            }
            FrameKind::BatchApprove => self.handle_batch_approve(frame.payload()),
            FrameKind::BatchReject => self.handle_batch_reject(frame.payload()),
            FrameKind::BatchComplete => self.handle_batch_complete(frame.payload()),
            FrameKind::Chunk => self.handle_chunk(&mut stream, frame.payload(), now_ms),
            FrameKind::PairAccept
            | FrameKind::SessionAccept
            | FrameKind::ChunkAck
            | FrameKind::Error => Err(validation(
                "lan_control_frame_order_invalid",
                "listener received a frame outside the active control state",
            )),
        }
    }

    fn handle_pair_hello(
        &mut self,
        stream: &mut crate::transport::FrameStream<TcpStream>,
        peer_address: SocketAddr,
        payload: &[u8],
        now_ms: i64,
    ) -> Result<(), LomoError> {
        let hello = decode_pair_hello(payload)?;
        assert_before_deadline(
            now_ms,
            hello.deadline_ms,
            "lan_pairing_expired",
            "pairing hello arrived after its deadline",
        )?;
        let local = self.identity.clone().ok_or_else(identity_missing)?;
        let ephemeral = EphemeralKey::generate()?;
        let shared = ephemeral.agree(&hello.ephemeral_public)?;
        let transcript = PairingTranscript::build(
            &hello.public_key,
            &hello.display_name,
            &hello.ephemeral_public,
            &local.public_key,
            &local.display_name,
            &ephemeral.public,
            &shared,
        )?;
        stream.write_frame(&LanFrame::new(
            FrameKind::PairAccept,
            encode_pair_accept(&PairAccept {
                pairing_id: hello.pairing_id.clone(),
                public_key: local.public_key,
                display_name: local.display_name,
                ephemeral_public: ephemeral.public,
            }),
        )?)?;
        let challenge = pairing_challenge(
            hello.pairing_id.clone(),
            &hello.public_key,
            hello.display_name.clone(),
            &transcript,
            hello.deadline_ms,
        );
        self.pending_pairings.insert(
            hello.pairing_id,
            PendingPairing {
                challenge,
                transcript,
                peer_public_key: hello.public_key,
                peer_address: SocketAddr::new(peer_address.ip(), hello.listen_port),
                local_confirmed: false,
                peer_signature: None,
            },
        );
        Ok(())
    }

    fn handle_pair_confirm(&mut self, payload: &[u8], now_ms: i64) -> Result<(), LomoError> {
        let confirm = decode_pair_confirm(payload)?;
        let pending = self
            .pending_pairings
            .get_mut(&confirm.pairing_id)
            .ok_or_else(|| {
                validation(
                    "lan_pairing_unknown",
                    "confirmation does not belong to a pending pairing",
                )
            })?;
        assert_before_deadline(
            now_ms,
            pending.challenge.deadline_ms,
            "lan_pairing_expired",
            "pairing confirmation arrived after its deadline",
        )?;
        verify_pairing_confirmation(
            &pending.transcript,
            &pending.peer_public_key,
            &pending.challenge.peer_display_name,
            &confirm.signature,
            now_ms,
        )?;
        pending.peer_signature = Some(confirm.signature);
        self.commit_pair_if_complete(&confirm.pairing_id, now_ms)
    }

    fn handle_session_hello(
        &mut self,
        stream: &mut crate::transport::FrameStream<TcpStream>,
        peer_address: SocketAddr,
        payload: &[u8],
        now_ms: i64,
    ) -> Result<(), LomoError> {
        let hello = decode_session_hello(payload)?;
        assert_before_deadline(
            now_ms,
            hello.deadline_ms,
            "lan_session_expired",
            "session hello arrived after its deadline",
        )?;
        self.assert_fresh_session(&hello.session_id)?;
        let peer_device_id = DeviceId::derive(&hello.public_key);
        let trusted = self.trusted_peer(&peer_device_id)?;
        if hello.public_key != *trusted.public_key() {
            return Err(authentication(
                "lan_session_peer_mismatch",
                "session opener key does not match the trusted peer record",
            ));
        }
        let local = self.identity.clone().ok_or_else(identity_missing)?;
        let ephemeral = EphemeralKey::generate()?;
        let shared = ephemeral.agree(&hello.ephemeral_public)?;
        let transcript = SessionTranscript::build(
            &hello.session_id,
            &hello.public_key,
            &hello.ephemeral_public,
            &local.public_key,
            &ephemeral.public,
        )?;
        let key = SessionKey::derive(&transcript, &shared)?;
        stream.write_frame(&LanFrame::new(
            FrameKind::SessionAccept,
            encode_session_accept(&SessionAccept {
                session_id: hello.session_id.clone(),
                public_key: local.public_key,
                ephemeral_public: ephemeral.public,
            }),
        )?)?;
        let challenge = session_challenge(
            hello.session_id.clone(),
            peer_device_id,
            &transcript,
            hello.deadline_ms,
        );
        self.pending_sessions.insert(
            hello.session_id,
            PendingSession {
                challenge,
                transcript,
                peer_public_key: hello.public_key,
                peer_address: SocketAddr::new(peer_address.ip(), hello.listen_port),
                key,
                local_confirmed: false,
                peer_signature: None,
            },
        );
        Ok(())
    }

    fn handle_session_confirm(&mut self, payload: &[u8], now_ms: i64) -> Result<(), LomoError> {
        let confirm = decode_session_confirm(payload)?;
        let pending = self
            .pending_sessions
            .get_mut(&confirm.session_id)
            .ok_or_else(|| {
                validation(
                    "lan_session_unknown",
                    "confirmation does not belong to a pending session",
                )
            })?;
        assert_before_deadline(
            now_ms,
            pending.challenge.deadline_ms,
            "lan_session_expired",
            "session confirmation arrived after its deadline",
        )?;
        pending
            .transcript
            .verify_peer(&pending.peer_public_key, &confirm.signature)?;
        pending.peer_signature = Some(confirm.signature);
        self.commit_session_if_complete(&confirm.session_id)
    }

    fn handle_batch_prepare(
        &mut self,
        stream: &mut crate::transport::FrameStream<TcpStream>,
        payload: &[u8],
        now_ms: i64,
    ) -> Result<(), LomoError> {
        let control = decode_batch_control(payload)?;
        let active = self.active_session(&control.session_id)?;
        active.key.verify_control(
            &control.session_id,
            control.batch_id.as_str(),
            SessionControlKind::Prepare,
            &control.body,
            &control.tag,
        )?;
        let plan = decode_batch_plan(&control.body)?;
        if plan.batch_id() != &control.batch_id {
            return Err(authentication(
                "lan_batch_control_mismatch",
                "authenticated control batch id does not match its plan",
            ));
        }
        let peer_id = active.snapshot.peer_device_id.clone();
        let peer = self.trusted_peer(&peer_id)?.clone();
        if let Some(existing) = self.journal.batch(&control.batch_id) {
            if existing.plan() == &plan && existing.sender_device_id() == &peer_id {
                if let Some(approval) = existing.approval()
                    && approval.assert_valid_at(now_ms).is_err()
                {
                    return Err(permission(
                        "lan_approval_expired",
                        "recovery is outside the approval TTL and requires a new batch approval",
                    ));
                }
                self.journal
                    .rebind_batch_session(&control.batch_id, control.session_id.clone())?;
            } else {
                return Err(crate::error::conflict(
                    "lan_batch_replayed_with_different_plan",
                    "batch id was reused with different authenticated metadata",
                ));
            }
        } else {
            self.journal.store_batch(LanDurableBatch::pending(
                plan,
                control.session_id.clone(),
                peer_id,
                peer.display_name().clone(),
            ))?;
        }
        let body = self.encode_current_batch_status(&control.batch_id)?;
        let tag = self
            .active_session(&control.session_id)?
            .key
            .authenticate_control(
                &control.session_id,
                control.batch_id.as_str(),
                SessionControlKind::Complete,
                &body,
            );
        stream.write_frame(&LanFrame::new(
            FrameKind::BatchComplete,
            encode_batch_control(&BatchControl {
                session_id: control.session_id,
                batch_id: control.batch_id,
                body,
                tag,
            }),
        )?)
    }

    fn handle_batch_approve(&mut self, payload: &[u8]) -> Result<(), LomoError> {
        let control = decode_batch_control(payload)?;
        let active = self.active_session(&control.session_id)?;
        active.key.verify_control(
            &control.session_id,
            control.batch_id.as_str(),
            SessionControlKind::Approve,
            &control.body,
            &control.tag,
        )?;
        if !control.body.is_empty() {
            return Err(batch_wire_invalid());
        }
        let outgoing = self
            .journal
            .outgoing_batch(&control.batch_id)
            .ok_or_else(|| {
                validation(
                    "lan_batch_unknown",
                    "approval does not belong to an outgoing batch",
                )
            })?;
        if outgoing.session_id() != &control.session_id {
            return Err(permission(
                "lan_batch_session_mismatch",
                "approval does not belong to the outgoing batch session",
            ));
        }
        self.journal.approve_outgoing_batch(&control.batch_id)
    }

    fn handle_batch_reject(&mut self, payload: &[u8]) -> Result<(), LomoError> {
        let control = decode_batch_control(payload)?;
        let active = self.active_session(&control.session_id)?;
        active.key.verify_control(
            &control.session_id,
            control.batch_id.as_str(),
            SessionControlKind::Reject,
            &control.body,
            &control.tag,
        )?;
        if !control.body.is_empty() {
            return Err(batch_wire_invalid());
        }
        let outgoing = self
            .journal
            .outgoing_batch(&control.batch_id)
            .ok_or_else(|| {
                validation(
                    "lan_batch_unknown",
                    "rejection does not belong to an outgoing batch",
                )
            })?;
        if outgoing.session_id() != &control.session_id {
            return Err(permission(
                "lan_batch_session_mismatch",
                "rejection does not belong to the outgoing batch session",
            ));
        }
        self.journal.reject_outgoing_batch(&control.batch_id)
    }

    fn handle_batch_complete(&mut self, payload: &[u8]) -> Result<(), LomoError> {
        let control = decode_batch_control(payload)?;
        self.apply_authenticated_batch_status(&control)
    }

    fn encode_current_batch_status(&self, batch_id: &LanBatchId) -> Result<Vec<u8>, LomoError> {
        let batch = self
            .journal
            .batch(batch_id)
            .ok_or_else(|| validation("lan_batch_unknown", "batch was not prepared"))?;
        let mut confirmed_ranges = Vec::new();
        for (item_index, attachment_slot) in planned_payload_coordinates(batch.plan())? {
            confirmed_ranges.extend(self.confirmed_ranges_for_payload(
                batch,
                item_index,
                attachment_slot,
            )?);
        }
        let outcomes = batch
            .plan()
            .items()
            .iter()
            .map(|item| {
                batch
                    .snapshot()
                    .outcome(item.item_id())
                    .cloned()
                    .ok_or_else(|| {
                        validation(
                            "lan_item_outcome_missing",
                            "received batch item has no durable outcome",
                        )
                    })
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(encode_batch_status(&BatchStatus {
            decision: match batch.decision() {
                LanBatchDecision::Pending => LanOutgoingDecision::AwaitingApproval,
                LanBatchDecision::Approved { .. } => LanOutgoingDecision::Approved,
                LanBatchDecision::Rejected { .. } => LanOutgoingDecision::Rejected,
            },
            confirmed_ranges,
            outcomes,
        }))
    }

    fn confirmed_ranges_for_payload(
        &self,
        batch: &LanDurableBatch,
        item_index: u16,
        attachment_slot: u16,
    ) -> Result<Vec<ConfirmedChunkRange>, LomoError> {
        let payload = planned_payload(batch.plan(), item_index, attachment_slot)?;
        let total_chunks = chunk_count(payload.size_bytes)?;
        let mut ranges = Vec::new();
        let mut range_start = None;
        for chunk_index in 0..total_chunks {
            let binding = ChunkBinding::new(
                batch.session_id(),
                batch.plan().batch_id().as_str(),
                item_index,
                attachment_slot,
                chunk_index,
            )?;
            if self.journal.is_chunk_confirmed(&binding) {
                range_start.get_or_insert(chunk_index);
            } else if let Some(start) = range_start.take() {
                ranges.push(ConfirmedChunkRange {
                    item_index,
                    attachment_slot,
                    start,
                    end_exclusive: chunk_index,
                });
            }
        }
        if let Some(start) = range_start {
            ranges.push(ConfirmedChunkRange {
                item_index,
                attachment_slot,
                start,
                end_exclusive: total_chunks,
            });
        }
        Ok(ranges)
    }

    fn apply_authenticated_batch_status(
        &mut self,
        control: &BatchControl,
    ) -> Result<(), LomoError> {
        let peer_device_id = {
            let active = self.active_session(&control.session_id)?;
            active.key.verify_control(
                &control.session_id,
                control.batch_id.as_str(),
                SessionControlKind::Complete,
                &control.body,
                &control.tag,
            )?;
            active.snapshot.peer_device_id.clone()
        };
        let plan = self
            .journal
            .outgoing_batch(&control.batch_id)
            .ok_or_else(|| {
                validation(
                    "lan_batch_unknown",
                    "remote status does not belong to an outgoing batch",
                )
            })?
            .plan()
            .clone();
        let status = decode_batch_status(&control.body)?;
        let mut confirmed = BTreeSet::new();
        for range in &status.confirmed_ranges {
            let payload = planned_payload(&plan, range.item_index, range.attachment_slot)?;
            payload.assert_wire_coordinate(range.item_index, range.attachment_slot)?;
            let total_chunks = chunk_count(payload.size_bytes)?;
            if range.start >= range.end_exclusive || range.end_exclusive > total_chunks {
                return Err(validation(
                    "lan_batch_status_range_invalid",
                    "remote confirmed range is outside the planned payload",
                ));
            }
            for chunk_index in range.start..range.end_exclusive {
                if !confirmed.insert((range.item_index, range.attachment_slot, chunk_index)) {
                    return Err(validation(
                        "lan_batch_status_range_invalid",
                        "remote confirmed ranges overlap",
                    ));
                }
            }
        }
        self.journal.update_outgoing_batch_status(
            &control.batch_id,
            &control.session_id,
            &peer_device_id,
            status.decision,
            confirmed,
            &status.outcomes,
        )
    }

    fn handle_chunk(
        &mut self,
        stream: &mut crate::transport::FrameStream<TcpStream>,
        payload: &[u8],
        now_ms: i64,
    ) -> Result<(), LomoError> {
        let transfer = decode_chunk_transfer(payload)?;
        let peer_id = self
            .active_session(&transfer.receipt.session_id)?
            .snapshot
            .peer_device_id
            .clone();
        self.trusted_peer(&peer_id)?;
        let plan = {
            let batch = self
                .journal
                .batch(&transfer.receipt.batch_id)
                .ok_or_else(|| validation("lan_batch_unknown", "chunk batch was not prepared"))?;
            if batch.session_id() != &transfer.receipt.session_id
                || batch.sender_device_id() != &peer_id
            {
                return Err(permission(
                    "lan_chunk_session_mismatch",
                    "chunk does not belong to the authenticated batch session",
                ));
            }
            batch
                .approval()
                .ok_or_else(|| {
                    permission(
                        "lan_batch_not_approved",
                        "chunk bytes are refused until the batch is approved",
                    )
                })?
                .assert_valid_at(now_ms)?;
            batch.plan().clone()
        };
        let expected = planned_payload(
            &plan,
            transfer.receipt.item_index,
            transfer.receipt.attachment_slot,
        )?;
        expected.assert_wire_coordinate(
            transfer.receipt.item_index,
            transfer.receipt.attachment_slot,
        )?;
        let expected_length =
            expected_chunk_length(expected.size_bytes, transfer.receipt.chunk_index)?;
        let binding = transfer.receipt.binding()?;
        let plaintext = self
            .active_session(&transfer.receipt.session_id)?
            .key
            .open_chunk(&binding, transfer.sealed)?;
        if plaintext.len() != expected_length {
            return Err(validation(
                "lan_chunk_length_mismatch",
                "opened chunk length does not match its planned payload range",
            ));
        }
        self.journal.stage_chunk(&binding, &plaintext)?;
        self.journal.confirm_chunk(&binding)?;
        stream.write_frame(&LanFrame::new(
            FrameKind::ChunkAck,
            encode_chunk_receipt(&transfer.receipt),
        )?)
    }

    #[must_use]
    pub fn pairing_challenge(&self, pairing_id: &LanPairingId) -> Option<LanPairingChallenge> {
        self.pending_pairings
            .get(pairing_id)
            .map(|pending| pending.challenge.clone())
    }

    /// Discards a pending local pairing after the user rejects the displayed short code.
    ///
    /// # Errors
    ///
    /// Validation when the pairing is no longer pending.
    pub fn decline_pairing(&mut self, pairing_id: &LanPairingId) -> Result<(), LomoError> {
        self.pending_pairings
            .remove(pairing_id)
            .map(|_pending| ())
            .ok_or_else(|| validation("lan_pairing_unknown", "pairing is not pending"))
    }

    /// Records local user confirmation and sends only the device-key signature to the peer.
    ///
    /// # Errors
    ///
    /// Permission after deadline; authentication for an invalid local signature; network for the
    /// confirmation frame; storage when both confirmations complete and the journal write fails.
    pub fn confirm_pairing(
        &mut self,
        pairing_id: &LanPairingId,
        signature: &[u8],
        now_ms: i64,
    ) -> Result<(), LomoError> {
        let local = self.identity.clone().ok_or_else(identity_missing)?;
        let pending = self
            .pending_pairings
            .get_mut(pairing_id)
            .ok_or_else(|| validation("lan_pairing_unknown", "pairing is not pending"))?;
        if now_ms > pending.challenge.deadline_ms {
            return Err(permission(
                "lan_pairing_expired",
                "pairing confirmation arrived after its deadline",
            ));
        }
        local.public_key.verify(
            pending.transcript.bytes(),
            signature,
            "lan_pairing_signature_invalid",
        )?;
        let mut stream = connect_peer(
            pending.peer_address,
            PAIRING_SOCKET_DEADLINE,
            pairing_deadlines()?,
        )?;
        stream.write_frame(&LanFrame::new(
            FrameKind::PairConfirm,
            encode_pair_confirm(&PairConfirm {
                pairing_id: pairing_id.clone(),
                signature: signature.to_vec(),
            }),
        )?)?;
        pending.local_confirmed = true;
        self.commit_pair_if_complete(pairing_id, now_ms)
    }

    /// Opens a fresh mutually authenticated session with a trusted discovered peer.
    ///
    /// # Errors
    ///
    /// Authentication for an unknown/revoked/mismatched peer; validation for lifecycle or TTL;
    /// network/crypto errors from the hello/accept exchange.
    pub fn begin_session(
        &mut self,
        peer: &DiscoveredPeerEndpoint,
        now_ms: i64,
        ttl_ms: i64,
    ) -> Result<LanSessionChallenge, LomoError> {
        if ttl_ms <= 0 {
            return Err(validation(
                "lan_session_ttl_invalid",
                "session time-to-live must be positive",
            ));
        }
        let trusted = self.trusted_peer(peer.device_id())?.clone();
        let local = self.identity.clone().ok_or_else(identity_missing)?;
        let listen_port = self.listening_port()?;
        let session_id = generate_session_id()?;
        self.assert_fresh_session(&session_id)?;
        let ephemeral = EphemeralKey::generate()?;
        let hello = SessionHello {
            session_id: session_id.clone(),
            public_key: local.public_key.clone(),
            ephemeral_public: ephemeral.public.clone(),
            listen_port,
            deadline_ms: now_ms.saturating_add(ttl_ms),
        };
        let mut stream = connect_peer(
            peer.address(),
            PAIRING_SOCKET_DEADLINE,
            pairing_deadlines()?,
        )?;
        stream.write_frame(&LanFrame::new(
            FrameKind::SessionHello,
            encode_session_hello(&hello),
        )?)?;
        let frame = stream.read_frame()?;
        if frame.kind() != FrameKind::SessionAccept {
            return Err(validation(
                "lan_session_frame_order_invalid",
                "session opener expected a SessionAccept frame",
            ));
        }
        let accept = decode_session_accept(frame.payload())?;
        if accept.session_id != session_id {
            return Err(authentication(
                "lan_session_id_mismatch",
                "session response identity does not match the request",
            ));
        }
        let accepted_device_id = DeviceId::derive(&accept.public_key);
        if accepted_device_id != *peer.device_id() || accept.public_key != *trusted.public_key() {
            return Err(authentication(
                "lan_session_peer_mismatch",
                "session response key does not match the trusted discovered peer",
            ));
        }
        let transcript = SessionTranscript::build(
            &session_id,
            &local.public_key,
            &hello.ephemeral_public,
            &accept.public_key,
            &accept.ephemeral_public,
        )?;
        let key = SessionKey::derive(&transcript, &ephemeral.agree(&accept.ephemeral_public)?)?;
        let challenge = session_challenge(
            session_id.clone(),
            accepted_device_id,
            &transcript,
            hello.deadline_ms,
        );
        self.pending_sessions.insert(
            session_id,
            PendingSession {
                challenge: challenge.clone(),
                transcript,
                peer_public_key: accept.public_key,
                peer_address: peer.address(),
                key,
                local_confirmed: false,
                peer_signature: None,
            },
        );
        Ok(challenge)
    }

    /// Returns a pending external-signature challenge.
    #[must_use]
    pub fn session_challenge(&self, session_id: &LanSessionId) -> Option<LanSessionChallenge> {
        self.pending_sessions
            .get(session_id)
            .map(|pending| pending.challenge.clone())
    }

    /// Signs locally outside Rust, sends the signature, and authenticates only after both sides
    /// have confirmed the same transcript.
    ///
    /// # Errors
    ///
    /// Permission after deadline; authentication for a bad signature; network on delivery;
    /// storage when the accepted session id cannot be journaled.
    pub fn confirm_session(
        &mut self,
        session_id: &LanSessionId,
        signature: &[u8],
        now_ms: i64,
    ) -> Result<(), LomoError> {
        let local = self.identity.clone().ok_or_else(identity_missing)?;
        let pending = self
            .pending_sessions
            .get_mut(session_id)
            .ok_or_else(|| validation("lan_session_unknown", "session is not pending"))?;
        assert_before_deadline(
            now_ms,
            pending.challenge.deadline_ms,
            "lan_session_expired",
            "session confirmation arrived after its deadline",
        )?;
        local.public_key.verify(
            pending.transcript.bytes(),
            signature,
            "lan_session_signature_invalid",
        )?;
        let mut stream = connect_peer(
            pending.peer_address,
            PAIRING_SOCKET_DEADLINE,
            pairing_deadlines()?,
        )?;
        stream.write_frame(&LanFrame::new(
            FrameKind::SessionConfirm,
            encode_session_confirm(&SessionConfirm {
                session_id: session_id.clone(),
                signature: signature.to_vec(),
            }),
        )?)?;
        pending.local_confirmed = true;
        self.commit_session_if_complete(session_id)
    }

    /// Public state of an authenticated session.
    #[must_use]
    pub fn session_snapshot(&self, session_id: &LanSessionId) -> Option<&LanSessionSnapshot> {
        self.active_sessions
            .get(session_id)
            .map(|session| &session.snapshot)
    }

    /// Sends bounded batch metadata under an authenticated session control tag.
    ///
    /// # Errors
    ///
    /// Validation for an unknown session; network for delivery; resource-limit/validation when
    /// the control frame cannot represent the already-validated plan.
    pub fn prepare_batch(
        &mut self,
        session_id: &LanSessionId,
        plan: LanBatchPlan,
    ) -> Result<(), LomoError> {
        let body = encode_batch_plan(&plan);
        let batch_id = plan.batch_id().clone();
        let (control, peer_device_id, peer_display_name) = {
            let active = self.active_session(session_id)?;
            let peer = self.trusted_peer(&active.snapshot.peer_device_id)?;
            (
                BatchControl {
                    session_id: session_id.clone(),
                    batch_id: batch_id.clone(),
                    tag: active.key.authenticate_control(
                        session_id,
                        batch_id.as_str(),
                        SessionControlKind::Prepare,
                        &body,
                    ),
                    body,
                },
                active.snapshot.peer_device_id.clone(),
                peer.display_name().clone(),
            )
        };
        let address = self.active_session(session_id)?.peer_address;
        self.journal
            .store_outgoing_batch(LanDurableOutgoingBatch::new(
                plan,
                session_id.clone(),
                peer_device_id,
                peer_display_name,
            ))?;
        let mut stream = connect_peer(address, PAIRING_SOCKET_DEADLINE, pairing_deadlines()?)?;
        stream.write_frame(&LanFrame::new(
            FrameKind::BatchPrepare,
            encode_batch_control(&control),
        )?)?;
        let response = stream.read_frame()?;
        if response.kind() != FrameKind::BatchComplete {
            return Err(validation(
                "lan_batch_status_frame_invalid",
                "batch prepare expected an authenticated batch status response",
            ));
        }
        let status = decode_batch_control(response.payload())?;
        self.apply_authenticated_batch_status(&status)
    }

    /// Bounded metadata visible to approval UI. Bodies and attachment bytes are absent by type.
    #[must_use]
    pub fn batch_preview(&self, batch_id: &LanBatchId) -> Option<LanBatchPreview> {
        self.journal.batch(batch_id).map(LanDurableBatch::preview)
    }

    /// Complete durable recovery truth for a batch.
    #[must_use]
    pub fn batch_recovery(&self, batch_id: &LanBatchId) -> Option<&LanDurableBatch> {
        self.journal.batch(batch_id)
    }

    /// Persists a generation-bound approval and authenticates it back to the sender.
    ///
    /// # Errors
    ///
    /// Validation for unknown session/batch or non-positive TTL; permission for a mismatched
    /// session peer; storage before notification; network when the sender cannot be reached.
    pub fn approve_batch(
        &mut self,
        session_id: &LanSessionId,
        batch_id: &LanBatchId,
        generation: ApprovedGeneration,
        now_ms: i64,
        ttl_ms: i64,
    ) -> Result<(), LomoError> {
        if ttl_ms <= 0 {
            return Err(validation(
                "lan_approval_ttl_invalid",
                "approval time-to-live must be positive",
            ));
        }
        let (peer_id, address, tag) = {
            let active = self.active_session(session_id)?;
            let tag = active.key.authenticate_control(
                session_id,
                batch_id.as_str(),
                SessionControlKind::Approve,
                &[],
            );
            (
                active.snapshot.peer_device_id.clone(),
                active.peer_address,
                tag,
            )
        };
        let batch = self.journal.batch(batch_id).ok_or_else(|| {
            validation(
                "lan_batch_unknown",
                "cannot approve a batch that was not prepared",
            )
        })?;
        if batch.sender_device_id() != &peer_id {
            return Err(permission(
                "lan_batch_session_mismatch",
                "batch was prepared by another authenticated session peer",
            ));
        }
        let approval = LanApproval::granted(batch_id.clone(), now_ms, ttl_ms);
        approval.assert_valid_at(now_ms)?;
        self.journal.approve_batch(batch_id, approval, generation)?;
        send_control_frame(
            address,
            FrameKind::BatchApprove,
            &BatchControl {
                session_id: session_id.clone(),
                batch_id: batch_id.clone(),
                body: Vec::new(),
                tag,
            },
        )
    }

    /// Persists a terminal rejection and authenticates it back to the sender.
    ///
    /// # Errors
    ///
    /// Validation for unknown session/batch; permission for a mismatched peer; conflict for an
    /// existing terminal decision; storage before notification; network on delivery.
    pub fn reject_batch(
        &mut self,
        session_id: &LanSessionId,
        batch_id: &LanBatchId,
        rejected_at_ms: i64,
    ) -> Result<(), LomoError> {
        let (peer_id, address, tag) = {
            let active = self.active_session(session_id)?;
            (
                active.snapshot.peer_device_id.clone(),
                active.peer_address,
                active.key.authenticate_control(
                    session_id,
                    batch_id.as_str(),
                    SessionControlKind::Reject,
                    &[],
                ),
            )
        };
        let batch = self.journal.batch(batch_id).ok_or_else(|| {
            validation(
                "lan_batch_unknown",
                "cannot reject a batch that was not prepared",
            )
        })?;
        if batch.sender_device_id() != &peer_id {
            return Err(permission(
                "lan_batch_session_mismatch",
                "batch was prepared by another authenticated session peer",
            ));
        }
        self.journal.reject_batch(batch_id, rejected_at_ms)?;
        send_control_frame(
            address,
            FrameKind::BatchReject,
            &BatchControl {
                session_id: session_id.clone(),
                batch_id: batch_id.clone(),
                body: Vec::new(),
                tag,
            },
        )
    }

    /// True only after an authenticated approval arrives for an outgoing batch.
    #[must_use]
    pub fn outgoing_batch_is_approved(&self, batch_id: &LanBatchId) -> bool {
        self.journal
            .outgoing_batch(batch_id)
            .is_some_and(|batch| batch.decision() == LanOutgoingDecision::Approved)
    }

    /// True only after an authenticated rejection arrives for an outgoing batch.
    #[must_use]
    pub fn outgoing_batch_is_rejected(&self, batch_id: &LanBatchId) -> bool {
        self.journal
            .outgoing_batch(batch_id)
            .is_some_and(|batch| batch.decision() == LanOutgoingDecision::Rejected)
    }

    /// Sends one planned body/attachment chunk and returns only after the receiver durably ACKs it.
    ///
    /// # Errors
    ///
    /// Permission before authenticated approval or after rejection; validation for a foreign
    /// item/slot/index/length; crypto/network errors or a mismatched acknowledgement.
    pub fn send_batch_chunk(
        &self,
        session_id: &LanSessionId,
        batch_id: &LanBatchId,
        item_index: u16,
        attachment_slot: u16,
        chunk_index: u32,
        plaintext: &[u8],
    ) -> Result<(), LomoError> {
        let outgoing = self.journal.outgoing_batch(batch_id).ok_or_else(|| {
            validation(
                "lan_batch_unknown",
                "chunk does not belong to an outgoing batch",
            )
        })?;
        match outgoing.decision() {
            LanOutgoingDecision::Rejected => {
                return Err(permission(
                    "lan_batch_rejected",
                    "a rejected batch cannot send payload bytes",
                ));
            }
            LanOutgoingDecision::AwaitingApproval => {
                return Err(permission(
                    "lan_batch_not_approved",
                    "payload bytes cannot be sent before authenticated approval",
                ));
            }
            LanOutgoingDecision::Approved => {}
        }
        if outgoing.session_id() != session_id {
            return Err(permission(
                "lan_batch_session_mismatch",
                "payload session does not own the outgoing batch",
            ));
        }
        let plan = outgoing.plan();
        let expected = planned_payload(plan, item_index, attachment_slot)?;
        expected.assert_wire_coordinate(item_index, attachment_slot)?;
        let expected_length = expected_chunk_length(expected.size_bytes, chunk_index)?;
        if plaintext.len() != expected_length {
            return Err(validation(
                "lan_chunk_length_mismatch",
                "plaintext chunk length does not match its planned payload range",
            ));
        }
        let receipt = ChunkReceipt {
            session_id: session_id.clone(),
            batch_id: batch_id.clone(),
            item_index,
            attachment_slot,
            chunk_index,
        };
        let active = self.active_session(session_id)?;
        let transfer = ChunkTransfer {
            sealed: active
                .key
                .seal_chunk(&receipt.binding()?, plaintext.to_vec())?,
            receipt: receipt.clone(),
        };
        let mut stream = connect_peer(
            active.peer_address,
            PAIRING_SOCKET_DEADLINE,
            pairing_deadlines()?,
        )?;
        stream.write_frame(&LanFrame::new(
            FrameKind::Chunk,
            encode_chunk_transfer(&transfer),
        )?)?;
        let acknowledgement = stream.read_frame()?;
        if acknowledgement.kind() != FrameKind::ChunkAck
            || decode_chunk_receipt(acknowledgement.payload())? != receipt
        {
            return Err(authentication(
                "lan_chunk_ack_mismatch",
                "receiver acknowledgement does not match the sent chunk binding",
            ));
        }
        Ok(())
    }

    /// Chunk indices the receiver still needs for one durable planned payload.
    ///
    /// # Errors
    ///
    /// Validation for an unknown batch/item/slot or an impossible chunk count.
    pub fn unconfirmed_batch_chunks(
        &self,
        batch_id: &LanBatchId,
        item_index: u16,
        attachment_slot: u16,
    ) -> Result<Vec<u32>, LomoError> {
        if let Some(batch) = self.journal.outgoing_batch(batch_id) {
            let payload = planned_payload(batch.plan(), item_index, attachment_slot)?;
            return Ok(batch.unconfirmed_chunk_indices(
                payload.item_index,
                payload.attachment_slot,
                chunk_count(payload.size_bytes)?,
            ));
        }
        let batch = self
            .journal
            .batch(batch_id)
            .ok_or_else(|| validation("lan_batch_unknown", "batch was not prepared"))?;
        let payload = planned_payload(batch.plan(), item_index, attachment_slot)?;
        Ok(self.journal.unconfirmed_chunk_indices(
            batch_id,
            payload.item_index,
            payload.attachment_slot,
            chunk_count(payload.size_bytes)?,
        ))
    }

    /// Returns a complete digest-verified payload, or `None` while confirmed chunks are missing.
    ///
    /// # Errors
    ///
    /// Validation for unknown coordinates, corrupt/missing staged state, or digest/size mismatch.
    pub fn received_batch_payload(
        &self,
        batch_id: &LanBatchId,
        item_index: u16,
        attachment_slot: u16,
    ) -> Result<Option<Vec<u8>>, LomoError> {
        let batch = self
            .journal
            .batch(batch_id)
            .ok_or_else(|| validation("lan_batch_unknown", "batch was not prepared"))?;
        let expected = planned_payload(batch.plan(), item_index, attachment_slot)?;
        let Some(payload) = self.journal.read_confirmed_payload(
            batch_id,
            expected.item_index,
            expected.attachment_slot,
            chunk_count(expected.size_bytes)?,
        )?
        else {
            return Ok(None);
        };
        let actual_size = u64::try_from(payload.len()).map_err(|_error| {
            validation(
                "lan_payload_size_invalid",
                "received payload size does not fit the planned size width",
            )
        })?;
        if actual_size != expected.size_bytes
            || format!("{:x}", Sha256::digest(&payload)) != expected.digest
        {
            return Err(validation(
                "lan_payload_digest_mismatch",
                "received payload does not match its approved size and digest",
            ));
        }
        Ok(Some(payload))
    }

    /// Builds one store-ready create command from durable approved state and a verified body.
    ///
    /// # Errors
    ///
    /// Validation while the body is incomplete/non-UTF-8 or the item is unknown; permission when
    /// approval expired; conflict when the supplied active generation changed. Attachments are
    /// refused until their verified remap/transaction facts are part of the same command.
    pub fn authorize_received_item_create(
        &self,
        batch_id: &LanBatchId,
        item_index: u16,
        active_generation: &str,
        now_ms: i64,
    ) -> Result<Option<AuthorizedReceivedCreate>, LomoError> {
        let batch = self
            .journal
            .batch(batch_id)
            .ok_or_else(|| validation("lan_batch_unknown", "batch was not prepared"))?;
        let plan = batch
            .plan()
            .items()
            .get(usize::from(item_index))
            .ok_or_else(|| {
                validation(
                    "lan_item_not_in_batch",
                    "received item index does not belong to the approved batch",
                )
            })?;
        let bytes = self
            .received_batch_payload(batch_id, item_index, ATTACHMENT_SLOT_BODY)?
            .ok_or_else(|| {
                validation(
                    "lan_item_body_incomplete",
                    "received item body is not fully confirmed",
                )
            })?;
        let content = String::from_utf8(bytes).map_err(|_error| {
            validation(
                "lan_item_body_utf8_invalid",
                "received memo body must be valid UTF-8 Markdown",
            )
        })?;
        let received = ReceivedItem::verified(plan, content)?;
        let mut attachments = Vec::with_capacity(plan.attachments().len());
        for attachment in plan.attachments() {
            let (transfer_item_index, transfer) = batch
                .plan()
                .attachment_transfer_reference(attachment.digest())
                .ok_or_else(|| {
                    validation(
                        "lan_attachment_transfer_missing",
                        "received attachment has no canonical batch transfer coordinate",
                    )
                })?;
            let bytes = self
                .received_batch_payload(batch_id, transfer_item_index, transfer.slot())?
                .ok_or_else(|| {
                    validation(
                        "lan_item_attachments_incomplete",
                        "received item cannot commit before every attachment is verified",
                    )
                })?;
            attachments.push(AuthorizedReceivedAttachment::verified(
                attachment, transfer, bytes,
            )?);
        }
        let approval = batch.approval().ok_or_else(|| {
            permission(
                "lan_batch_not_approved",
                "received item cannot commit before batch approval",
            )
        })?;
        let approved_generation = batch.approved_generation().ok_or_else(|| {
            permission(
                "lan_batch_not_approved",
                "received item has no approved workspace generation",
            )
        })?;
        authorize_item_commit(
            approval,
            approved_generation,
            active_generation,
            now_ms,
            batch.snapshot(),
            &received,
        )
        .map(|command| command.map(|command| command.with_attachments(attachments)))
    }

    /// Durably records the store result for one received item.
    ///
    /// # Errors
    ///
    /// Validation for unknown batch/item and storage for journal persistence failures.
    pub fn record_received_item_committed(
        &mut self,
        batch_id: &LanBatchId,
        item_id: &crate::batch::LanItemId,
        memo_id: &str,
    ) -> Result<LanItemOutcome, LomoError> {
        self.journal
            .record_batch_outcome(batch_id, item_id, LanItemOutcome::committed(memo_id))
    }

    #[must_use]
    pub const fn snapshot(&self) -> &LanServiceSnapshot {
        &self.service
    }

    #[must_use]
    pub fn discovered_peers(&self) -> &[DiscoveredPeerEndpoint] {
        self.discovery
            .as_ref()
            .map_or(&[], |snapshot| snapshot.peers.as_slice())
    }

    #[must_use]
    pub const fn peers(&self) -> &BTreeMap<DeviceId, PeerRecord> {
        self.journal.peers()
    }

    /// Revokes a peer in the installation journal.
    ///
    /// # Errors
    ///
    /// Validation for an unknown peer; storage when the durable write fails.
    pub fn revoke_peer(
        &mut self,
        device_id: &DeviceId,
        revoked_at_ms: i64,
    ) -> Result<(), LomoError> {
        self.journal.revoke_peer(device_id, revoked_at_ms)?;
        self.pending_pairings
            .retain(|_pairing_id, pending| pending.challenge.peer_device_id != *device_id);
        self.pending_sessions
            .retain(|_session_id, pending| pending.challenge.peer_device_id != *device_id);
        self.active_sessions
            .retain(|_session_id, session| session.snapshot.peer_device_id != *device_id);
        Ok(())
    }

    fn commit_pair_if_complete(
        &mut self,
        pairing_id: &LanPairingId,
        paired_at_ms: i64,
    ) -> Result<(), LomoError> {
        let Some(pending) = self.pending_pairings.get(pairing_id) else {
            return Err(validation("lan_pairing_unknown", "pairing is not pending"));
        };
        if !pending.local_confirmed {
            return Ok(());
        }
        let Some(peer_signature) = &pending.peer_signature else {
            return Ok(());
        };
        let peer = verify_pairing_confirmation(
            &pending.transcript,
            &pending.peer_public_key,
            &pending.challenge.peer_display_name,
            peer_signature,
            paired_at_ms,
        )?;
        self.journal.store_peer(peer)?;
        self.pending_pairings.remove(pairing_id);
        Ok(())
    }

    fn commit_session_if_complete(&mut self, session_id: &LanSessionId) -> Result<(), LomoError> {
        let ready = self
            .pending_sessions
            .get(session_id)
            .is_some_and(|pending| pending.local_confirmed && pending.peer_signature.is_some());
        if !ready {
            return Ok(());
        }
        let pending = self
            .pending_sessions
            .remove(session_id)
            .ok_or_else(|| validation("lan_session_unknown", "session is not pending"))?;
        if let Err(error) = self.journal.accept_session(session_id) {
            self.pending_sessions.insert(session_id.clone(), pending);
            return Err(error);
        }
        let snapshot = LanSessionSnapshot {
            session_id: session_id.clone(),
            peer_device_id: pending.challenge.peer_device_id,
            phase: LanSessionPhase::Authenticated,
        };
        self.active_sessions.insert(
            session_id.clone(),
            ActiveSession {
                snapshot,
                peer_address: pending.peer_address,
                key: pending.key,
            },
        );
        Ok(())
    }

    fn trusted_peer(&self, device_id: &DeviceId) -> Result<&PeerRecord, LomoError> {
        let peer = self.journal.peers().get(device_id).ok_or_else(|| {
            authentication(
                "lan_peer_untrusted",
                "session peer is not present in the trusted peer registry",
            )
        })?;
        peer.assert_connectable()?;
        Ok(peer)
    }

    fn assert_fresh_session(&self, session_id: &LanSessionId) -> Result<(), LomoError> {
        if self.journal.has_session(session_id)
            || self.pending_sessions.contains_key(session_id)
            || self.active_sessions.contains_key(session_id)
        {
            return Err(authentication(
                "lan_session_replayed",
                "session id was already observed and may not be replayed",
            ));
        }
        Ok(())
    }

    fn active_session(&self, session_id: &LanSessionId) -> Result<&ActiveSession, LomoError> {
        self.active_sessions.get(session_id).ok_or_else(|| {
            authentication(
                "lan_session_not_authenticated",
                "batch control requires a mutually authenticated active session",
            )
        })
    }

    fn listening_port(&self) -> Result<u16, LomoError> {
        self.service
            .listen_address
            .map(|address| address.port())
            .ok_or_else(|| {
                validation(
                    "lan_service_not_listening",
                    "session requires this endpoint's Rust listener to be started",
                )
            })
    }
}

struct EphemeralKey {
    private: agreement::PrivateKey,
    public: Vec<u8>,
}

impl EphemeralKey {
    fn generate() -> Result<Self, LomoError> {
        let private = agreement::PrivateKey::generate(&agreement::X25519).map_err(|_error| {
            authentication(
                "lan_pairing_ephemeral_generate_failed",
                "X25519 ephemeral key generation failed",
            )
        })?;
        let public = private
            .compute_public_key()
            .map_err(|_error| {
                authentication(
                    "lan_pairing_ephemeral_generate_failed",
                    "X25519 public key derivation failed",
                )
            })?
            .as_ref()
            .to_vec();
        Ok(Self { private, public })
    }

    fn agree(&self, peer_public: &[u8]) -> Result<Vec<u8>, LomoError> {
        agreement::agree(
            &self.private,
            agreement::UnparsedPublicKey::new(&agreement::X25519, peer_public),
            (),
            |shared| Ok(shared.to_vec()),
        )
        .map_err(|_error| {
            authentication(
                "lan_pairing_agreement_failed",
                "X25519 pairing agreement rejected the peer key",
            )
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct PairHello {
    pairing_id: LanPairingId,
    public_key: crate::identity::DevicePublicKey,
    display_name: DisplayName,
    ephemeral_public: Vec<u8>,
    listen_port: u16,
    deadline_ms: i64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct PairAccept {
    pairing_id: LanPairingId,
    public_key: crate::identity::DevicePublicKey,
    display_name: DisplayName,
    ephemeral_public: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct PairConfirm {
    pairing_id: LanPairingId,
    signature: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct SessionHello {
    session_id: LanSessionId,
    public_key: crate::identity::DevicePublicKey,
    ephemeral_public: Vec<u8>,
    listen_port: u16,
    deadline_ms: i64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct SessionAccept {
    session_id: LanSessionId,
    public_key: crate::identity::DevicePublicKey,
    ephemeral_public: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct SessionConfirm {
    session_id: LanSessionId,
    signature: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct BatchControl {
    session_id: LanSessionId,
    batch_id: LanBatchId,
    body: Vec<u8>,
    tag: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct BatchStatus {
    decision: LanOutgoingDecision,
    confirmed_ranges: Vec<ConfirmedChunkRange>,
    outcomes: Vec<LanItemOutcome>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct ConfirmedChunkRange {
    item_index: u16,
    attachment_slot: u16,
    start: u32,
    end_exclusive: u32,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct ChunkReceipt {
    session_id: LanSessionId,
    batch_id: LanBatchId,
    item_index: u16,
    attachment_slot: u16,
    chunk_index: u32,
}

impl ChunkReceipt {
    fn binding(&self) -> Result<ChunkBinding, LomoError> {
        ChunkBinding::new(
            &self.session_id,
            self.batch_id.as_str(),
            self.item_index,
            self.attachment_slot,
            self.chunk_index,
        )
    }
}

#[derive(Debug, Eq, PartialEq)]
struct ChunkTransfer {
    receipt: ChunkReceipt,
    sealed: Vec<u8>,
}

struct PlannedPayload {
    item_index: u16,
    attachment_slot: u16,
    size_bytes: u64,
    digest: String,
}

impl PlannedPayload {
    fn assert_wire_coordinate(
        &self,
        item_index: u16,
        attachment_slot: u16,
    ) -> Result<(), LomoError> {
        if self.item_index != item_index || self.attachment_slot != attachment_slot {
            return Err(validation(
                "lan_attachment_transfer_coordinate_not_canonical",
                "shared attachment bytes must use the batch's single canonical wire coordinate",
            ));
        }
        Ok(())
    }
}

fn pairing_challenge(
    pairing_id: LanPairingId,
    peer_key: &crate::identity::DevicePublicKey,
    peer_display_name: DisplayName,
    transcript: &PairingTranscript,
    deadline_ms: i64,
) -> LanPairingChallenge {
    LanPairingChallenge {
        pairing_id,
        peer_device_id: DeviceId::derive(peer_key),
        peer_display_name,
        short_code: derive_pairing_code(transcript),
        transcript_to_sign: transcript.bytes().to_vec(),
        deadline_ms,
    }
}

fn session_challenge(
    session_id: LanSessionId,
    peer_device_id: DeviceId,
    transcript: &SessionTranscript,
    deadline_ms: i64,
) -> LanSessionChallenge {
    LanSessionChallenge {
        session_id,
        peer_device_id,
        transcript_to_sign: transcript.bytes().to_vec(),
        deadline_ms,
    }
}

fn generate_session_id() -> Result<LanSessionId, LomoError> {
    let mut bytes = [0_u8; PAIRING_ID_BYTES];
    SystemRandom::new().fill(&mut bytes).map_err(|_error| {
        authentication(
            "lan_session_random_failed",
            "secure random generation failed for the session identity",
        )
    })?;
    LanSessionId::parse(&hex_bytes(&bytes))
}

fn assert_before_deadline(
    now_ms: i64,
    deadline_ms: i64,
    code: &str,
    message: &str,
) -> Result<(), LomoError> {
    if now_ms > deadline_ms {
        return Err(permission(code, message));
    }
    Ok(())
}

fn pairing_deadlines() -> Result<LanDeadlines, LomoError> {
    LanDeadlines::new(PAIRING_SOCKET_DEADLINE, PAIRING_SOCKET_DEADLINE)
}

fn identity_missing() -> LomoError {
    validation(
        "lan_device_identity_missing",
        "LAN device public key and display name must be configured before pairing",
    )
}

fn encode_pair_hello(value: &PairHello) -> Vec<u8> {
    let mut bytes = Vec::new();
    push_wire_field(&mut bytes, value.pairing_id.as_str().as_bytes());
    push_wire_field(&mut bytes, value.public_key.as_bytes());
    push_wire_field(&mut bytes, value.display_name.as_str().as_bytes());
    push_wire_field(&mut bytes, &value.ephemeral_public);
    bytes.extend_from_slice(&value.listen_port.to_be_bytes());
    bytes.extend_from_slice(&value.deadline_ms.to_be_bytes());
    bytes
}

fn decode_pair_hello(bytes: &[u8]) -> Result<PairHello, LomoError> {
    let (pairing_id, cursor) = take_wire_field(bytes, 0)?;
    let (public_key, cursor) = take_wire_field(bytes, cursor)?;
    let (display_name, cursor) = take_wire_field(bytes, cursor)?;
    let (ephemeral_public, cursor) = take_wire_field(bytes, cursor)?;
    let listen_port = take_wire_u16_with(bytes, cursor, session_wire_invalid)?;
    if listen_port == 0 {
        return Err(pairing_wire_invalid());
    }
    let deadline_ms = take_wire_i64_with(bytes, cursor.saturating_add(2), session_wire_invalid)?;
    assert_wire_end(bytes, cursor.saturating_add(10))?;
    Ok(PairHello {
        pairing_id: LanPairingId::parse(wire_utf8(pairing_id)?)?,
        public_key: crate::identity::DevicePublicKey::parse(public_key)?,
        display_name: DisplayName::parse(wire_utf8(display_name)?)?,
        ephemeral_public: parse_ephemeral(ephemeral_public)?,
        listen_port,
        deadline_ms,
    })
}

fn encode_pair_accept(value: &PairAccept) -> Vec<u8> {
    let mut bytes = Vec::new();
    push_wire_field(&mut bytes, value.pairing_id.as_str().as_bytes());
    push_wire_field(&mut bytes, value.public_key.as_bytes());
    push_wire_field(&mut bytes, value.display_name.as_str().as_bytes());
    push_wire_field(&mut bytes, &value.ephemeral_public);
    bytes
}

fn decode_pair_accept(bytes: &[u8]) -> Result<PairAccept, LomoError> {
    let (pairing_id, cursor) = take_wire_field(bytes, 0)?;
    let (public_key, cursor) = take_wire_field(bytes, cursor)?;
    let (display_name, cursor) = take_wire_field(bytes, cursor)?;
    let (ephemeral_public, cursor) = take_wire_field(bytes, cursor)?;
    assert_wire_end(bytes, cursor)?;
    Ok(PairAccept {
        pairing_id: LanPairingId::parse(wire_utf8(pairing_id)?)?,
        public_key: crate::identity::DevicePublicKey::parse(public_key)?,
        display_name: DisplayName::parse(wire_utf8(display_name)?)?,
        ephemeral_public: parse_ephemeral(ephemeral_public)?,
    })
}

fn encode_pair_confirm(value: &PairConfirm) -> Vec<u8> {
    let mut bytes = Vec::new();
    push_wire_field(&mut bytes, value.pairing_id.as_str().as_bytes());
    push_wire_field(&mut bytes, &value.signature);
    bytes
}

fn decode_pair_confirm(bytes: &[u8]) -> Result<PairConfirm, LomoError> {
    let (pairing_id, cursor) = take_wire_field(bytes, 0)?;
    let (signature, cursor) = take_wire_field(bytes, cursor)?;
    assert_wire_end(bytes, cursor)?;
    if signature.is_empty() || signature.len() > 144 {
        return Err(validation(
            "lan_pairing_signature_invalid",
            "pairing signature length is outside the P-256 DER boundary",
        ));
    }
    Ok(PairConfirm {
        pairing_id: LanPairingId::parse(wire_utf8(pairing_id)?)?,
        signature: signature.to_vec(),
    })
}

fn encode_session_hello(value: &SessionHello) -> Vec<u8> {
    let mut bytes = Vec::new();
    push_wire_field(&mut bytes, value.session_id.as_str().as_bytes());
    push_wire_field(&mut bytes, value.public_key.as_bytes());
    push_wire_field(&mut bytes, &value.ephemeral_public);
    bytes.extend_from_slice(&value.listen_port.to_be_bytes());
    bytes.extend_from_slice(&value.deadline_ms.to_be_bytes());
    bytes
}

fn decode_session_hello(bytes: &[u8]) -> Result<SessionHello, LomoError> {
    let (session_id, cursor) = take_wire_field_with(bytes, 0, session_wire_invalid)?;
    let (public_key, cursor) = take_wire_field_with(bytes, cursor, session_wire_invalid)?;
    let (ephemeral_public, cursor) = take_wire_field_with(bytes, cursor, session_wire_invalid)?;
    let listen_port = take_wire_u16(bytes, cursor)?;
    if listen_port == 0 {
        return Err(session_wire_invalid());
    }
    let deadline_ms = take_wire_i64(bytes, cursor.saturating_add(2))?;
    assert_wire_end_with(bytes, cursor.saturating_add(10), session_wire_invalid)?;
    Ok(SessionHello {
        session_id: LanSessionId::parse(wire_utf8_with(session_id, session_wire_invalid)?)?,
        public_key: crate::identity::DevicePublicKey::parse(public_key)?,
        ephemeral_public: parse_session_ephemeral(ephemeral_public)?,
        listen_port,
        deadline_ms,
    })
}

fn encode_session_accept(value: &SessionAccept) -> Vec<u8> {
    let mut bytes = Vec::new();
    push_wire_field(&mut bytes, value.session_id.as_str().as_bytes());
    push_wire_field(&mut bytes, value.public_key.as_bytes());
    push_wire_field(&mut bytes, &value.ephemeral_public);
    bytes
}

fn decode_session_accept(bytes: &[u8]) -> Result<SessionAccept, LomoError> {
    let (session_id, cursor) = take_wire_field_with(bytes, 0, session_wire_invalid)?;
    let (public_key, cursor) = take_wire_field_with(bytes, cursor, session_wire_invalid)?;
    let (ephemeral_public, cursor) = take_wire_field_with(bytes, cursor, session_wire_invalid)?;
    assert_wire_end_with(bytes, cursor, session_wire_invalid)?;
    Ok(SessionAccept {
        session_id: LanSessionId::parse(wire_utf8_with(session_id, session_wire_invalid)?)?,
        public_key: crate::identity::DevicePublicKey::parse(public_key)?,
        ephemeral_public: parse_session_ephemeral(ephemeral_public)?,
    })
}

fn encode_session_confirm(value: &SessionConfirm) -> Vec<u8> {
    let mut bytes = Vec::new();
    push_wire_field(&mut bytes, value.session_id.as_str().as_bytes());
    push_wire_field(&mut bytes, &value.signature);
    bytes
}

fn decode_session_confirm(bytes: &[u8]) -> Result<SessionConfirm, LomoError> {
    let (session_id, cursor) = take_wire_field_with(bytes, 0, session_wire_invalid)?;
    let (signature, cursor) = take_wire_field_with(bytes, cursor, session_wire_invalid)?;
    assert_wire_end_with(bytes, cursor, session_wire_invalid)?;
    if signature.is_empty() || signature.len() > 144 {
        return Err(validation(
            "lan_session_signature_invalid",
            "session signature length is outside the P-256 DER boundary",
        ));
    }
    Ok(SessionConfirm {
        session_id: LanSessionId::parse(wire_utf8_with(session_id, session_wire_invalid)?)?,
        signature: signature.to_vec(),
    })
}

fn send_control_frame(
    address: SocketAddr,
    kind: FrameKind,
    control: &BatchControl,
) -> Result<(), LomoError> {
    let mut stream = connect_peer(address, PAIRING_SOCKET_DEADLINE, pairing_deadlines()?)?;
    stream.write_frame(&LanFrame::new(kind, encode_batch_control(control))?)
}

fn encode_batch_control(value: &BatchControl) -> Vec<u8> {
    let mut bytes = Vec::new();
    push_wire_field(&mut bytes, value.session_id.as_str().as_bytes());
    push_wire_field(&mut bytes, value.batch_id.as_str().as_bytes());
    push_wire_field(&mut bytes, &value.body);
    push_wire_field(&mut bytes, &value.tag);
    bytes
}

fn decode_batch_control(bytes: &[u8]) -> Result<BatchControl, LomoError> {
    let (session_id, cursor) = take_wire_field_with(bytes, 0, batch_wire_invalid)?;
    let (batch_id, cursor) = take_wire_field_with(bytes, cursor, batch_wire_invalid)?;
    let (body, cursor) = take_wire_field_with(bytes, cursor, batch_wire_invalid)?;
    let (tag, cursor) = take_wire_field_with(bytes, cursor, batch_wire_invalid)?;
    assert_wire_end_with(bytes, cursor, batch_wire_invalid)?;
    if tag.len() != 32 {
        return Err(authentication(
            "lan_control_authentication_invalid",
            "session control authentication tag must be 32 bytes",
        ));
    }
    Ok(BatchControl {
        session_id: LanSessionId::parse(wire_utf8_with(session_id, batch_wire_invalid)?)?,
        batch_id: LanBatchId::parse(wire_utf8_with(batch_id, batch_wire_invalid)?)?,
        body: body.to_vec(),
        tag: tag.to_vec(),
    })
}

fn encode_batch_status(value: &BatchStatus) -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.push(match value.decision {
        LanOutgoingDecision::AwaitingApproval => 0,
        LanOutgoingDecision::Approved => 1,
        LanOutgoingDecision::Rejected => 2,
    });
    bytes.extend_from_slice(
        &u16::try_from(value.confirmed_ranges.len())
            .unwrap_or(u16::MAX)
            .to_be_bytes(),
    );
    for range in &value.confirmed_ranges {
        bytes.extend_from_slice(&range.item_index.to_be_bytes());
        bytes.extend_from_slice(&range.attachment_slot.to_be_bytes());
        bytes.extend_from_slice(&range.start.to_be_bytes());
        bytes.extend_from_slice(&range.end_exclusive.to_be_bytes());
    }
    bytes.extend_from_slice(
        &u16::try_from(value.outcomes.len())
            .unwrap_or(u16::MAX)
            .to_be_bytes(),
    );
    for outcome in &value.outcomes {
        match outcome {
            LanItemOutcome::Pending => bytes.push(0),
            LanItemOutcome::Committed { memo_id } => {
                bytes.push(1);
                push_wire_field(&mut bytes, memo_id.as_bytes());
            }
            LanItemOutcome::Failed { code } => {
                bytes.push(2);
                push_wire_field(&mut bytes, code.as_bytes());
            }
        }
    }
    bytes
}

fn decode_batch_status(bytes: &[u8]) -> Result<BatchStatus, LomoError> {
    let decision = match take_wire_u8_with(bytes, 0, batch_wire_invalid)? {
        0 => LanOutgoingDecision::AwaitingApproval,
        1 => LanOutgoingDecision::Approved,
        2 => LanOutgoingDecision::Rejected,
        _ => return Err(batch_wire_invalid()),
    };
    let range_count = usize::from(take_wire_u16_with(bytes, 1, batch_wire_invalid)?);
    let mut cursor = 3_usize;
    let mut confirmed_ranges = Vec::new();
    for _range in 0..range_count {
        let item_index = take_wire_u16_with(bytes, cursor, batch_wire_invalid)?;
        let attachment_slot =
            take_wire_u16_with(bytes, cursor.saturating_add(2), batch_wire_invalid)?;
        let start = take_wire_u32_with(bytes, cursor.saturating_add(4), batch_wire_invalid)?;
        let end_exclusive =
            take_wire_u32_with(bytes, cursor.saturating_add(8), batch_wire_invalid)?;
        cursor = cursor.saturating_add(12);
        confirmed_ranges.push(ConfirmedChunkRange {
            item_index,
            attachment_slot,
            start,
            end_exclusive,
        });
    }
    let outcome_count = usize::from(take_wire_u16_with(bytes, cursor, batch_wire_invalid)?);
    cursor = cursor.saturating_add(2);
    if outcome_count > crate::limits::MAX_BATCH_ITEMS {
        return Err(batch_wire_invalid());
    }
    let mut outcomes = Vec::with_capacity(outcome_count);
    for _outcome in 0..outcome_count {
        let kind = take_wire_u8_with(bytes, cursor, batch_wire_invalid)?;
        cursor = cursor.saturating_add(1);
        let outcome = match kind {
            0 => LanItemOutcome::Pending,
            1 | 2 => {
                let (value, next) = take_wire_field_with(bytes, cursor, batch_wire_invalid)?;
                cursor = next;
                let value = wire_utf8_with(value, batch_wire_invalid)?;
                if value.is_empty() {
                    return Err(batch_wire_invalid());
                }
                if kind == 1 {
                    LanItemOutcome::committed(value)
                } else {
                    LanItemOutcome::failed(value)
                }
            }
            _ => return Err(batch_wire_invalid()),
        };
        outcomes.push(outcome);
    }
    assert_wire_end_with(bytes, cursor, batch_wire_invalid)?;
    Ok(BatchStatus {
        decision,
        confirmed_ranges,
        outcomes,
    })
}

fn encode_chunk_receipt(value: &ChunkReceipt) -> Vec<u8> {
    let mut bytes = Vec::new();
    push_wire_field(&mut bytes, value.session_id.as_str().as_bytes());
    push_wire_field(&mut bytes, value.batch_id.as_str().as_bytes());
    bytes.extend_from_slice(&value.item_index.to_be_bytes());
    bytes.extend_from_slice(&value.attachment_slot.to_be_bytes());
    bytes.extend_from_slice(&value.chunk_index.to_be_bytes());
    bytes
}

fn decode_chunk_receipt(bytes: &[u8]) -> Result<ChunkReceipt, LomoError> {
    let (session_id, cursor) = take_wire_field_with(bytes, 0, chunk_wire_invalid)?;
    let (batch_id, cursor) = take_wire_field_with(bytes, cursor, chunk_wire_invalid)?;
    let item_index = take_wire_u16_with(bytes, cursor, chunk_wire_invalid)?;
    let attachment_slot = take_wire_u16_with(bytes, cursor.saturating_add(2), chunk_wire_invalid)?;
    let chunk_index = take_wire_u32_with(bytes, cursor.saturating_add(4), chunk_wire_invalid)?;
    assert_wire_end_with(bytes, cursor.saturating_add(8), chunk_wire_invalid)?;
    Ok(ChunkReceipt {
        session_id: LanSessionId::parse(wire_utf8_with(session_id, chunk_wire_invalid)?)?,
        batch_id: LanBatchId::parse(wire_utf8_with(batch_id, chunk_wire_invalid)?)?,
        item_index,
        attachment_slot,
        chunk_index,
    })
}

fn encode_chunk_transfer(value: &ChunkTransfer) -> Vec<u8> {
    let mut bytes = encode_chunk_receipt(&value.receipt);
    bytes.extend_from_slice(&value.sealed);
    bytes
}

fn decode_chunk_transfer(bytes: &[u8]) -> Result<ChunkTransfer, LomoError> {
    let (session_id, cursor) = take_wire_field_with(bytes, 0, chunk_wire_invalid)?;
    let (batch_id, cursor) = take_wire_field_with(bytes, cursor, chunk_wire_invalid)?;
    let item_index = take_wire_u16_with(bytes, cursor, chunk_wire_invalid)?;
    let attachment_slot = take_wire_u16_with(bytes, cursor.saturating_add(2), chunk_wire_invalid)?;
    let chunk_index = take_wire_u32_with(bytes, cursor.saturating_add(4), chunk_wire_invalid)?;
    let sealed = bytes
        .get(cursor.saturating_add(8)..)
        .filter(|sealed| !sealed.is_empty())
        .ok_or_else(chunk_wire_invalid)?;
    Ok(ChunkTransfer {
        receipt: ChunkReceipt {
            session_id: LanSessionId::parse(wire_utf8_with(session_id, chunk_wire_invalid)?)?,
            batch_id: LanBatchId::parse(wire_utf8_with(batch_id, chunk_wire_invalid)?)?,
            item_index,
            attachment_slot,
            chunk_index,
        },
        sealed: sealed.to_vec(),
    })
}

fn planned_payload_coordinates(plan: &LanBatchPlan) -> Result<BTreeSet<(u16, u16)>, LomoError> {
    let mut coordinates = BTreeSet::new();
    for item in plan.items() {
        coordinates.insert((item.index(), ATTACHMENT_SLOT_BODY));
        for attachment in item.attachments() {
            coordinates.insert(
                plan.attachment_transfer_coordinate(attachment.digest())
                    .ok_or_else(|| {
                        validation(
                            "lan_attachment_transfer_missing",
                            "batch attachment has no canonical transfer coordinate",
                        )
                    })?,
            );
        }
    }
    Ok(coordinates)
}

fn planned_payload(
    plan: &LanBatchPlan,
    item_index: u16,
    attachment_slot: u16,
) -> Result<PlannedPayload, LomoError> {
    let item = plan
        .items()
        .get(usize::from(item_index))
        .filter(|item| item.index() == item_index)
        .ok_or_else(|| validation("lan_item_not_in_batch", "chunk item is not in the batch"))?;
    if attachment_slot == ATTACHMENT_SLOT_BODY {
        return Ok(PlannedPayload {
            item_index,
            attachment_slot,
            size_bytes: item.content_bytes(),
            digest: item.content_digest().to_owned(),
        });
    }
    let attachment = item
        .attachments()
        .iter()
        .find(|attachment| attachment.slot() == attachment_slot)
        .ok_or_else(|| {
            validation(
                "lan_attachment_not_in_item",
                "chunk attachment slot is not referenced by the planned item",
            )
        })?;
    let (transfer_item_index, transfer) = plan
        .attachment_transfer_reference(attachment.digest())
        .ok_or_else(|| {
        validation(
            "lan_attachment_transfer_missing",
            "attachment digest has no canonical batch transfer coordinate",
        )
    })?;
    Ok(PlannedPayload {
        item_index: transfer_item_index,
        attachment_slot: transfer.slot(),
        size_bytes: transfer.size_bytes(),
        digest: transfer.digest().to_owned(),
    })
}

fn chunk_count(size_bytes: u64) -> Result<u32, LomoError> {
    if size_bytes == 0 {
        return Ok(0);
    }
    let chunks = size_bytes.div_ceil(RUNTIME_CHUNK_PLAINTEXT_BYTES as u64);
    u32::try_from(chunks).map_err(|_error| {
        resource_limit(
            "lan_chunk_range_too_large",
            "planned payload needs more chunks than the wire index can represent",
        )
    })
}

fn expected_chunk_length(size_bytes: u64, chunk_index: u32) -> Result<usize, LomoError> {
    let count = chunk_count(size_bytes)?;
    if chunk_index >= count {
        return Err(validation(
            "lan_chunk_index_invalid",
            "chunk index is outside the planned payload range",
        ));
    }
    let offset = u64::from(chunk_index).saturating_mul(RUNTIME_CHUNK_PLAINTEXT_BYTES as u64);
    usize::try_from((size_bytes - offset).min(RUNTIME_CHUNK_PLAINTEXT_BYTES as u64)).map_err(
        |_error| {
            resource_limit(
                "lan_chunk_length_invalid",
                "planned chunk length does not fit this platform",
            )
        },
    )
}

fn encode_batch_plan(plan: &LanBatchPlan) -> Vec<u8> {
    let mut bytes = Vec::new();
    push_wire_field(&mut bytes, plan.batch_id().as_str().as_bytes());
    bytes.extend_from_slice(
        &u16::try_from(plan.item_count())
            .unwrap_or(u16::MAX)
            .to_be_bytes(),
    );
    for item in plan.items() {
        bytes.extend_from_slice(&item.timestamp_ms().to_be_bytes());
        push_wire_field(&mut bytes, item.content_digest().as_bytes());
        bytes.extend_from_slice(&item.content_bytes().to_be_bytes());
        push_wire_field(&mut bytes, item.title().as_bytes());
        bytes.extend_from_slice(
            &u16::try_from(item.attachments().len())
                .unwrap_or(u16::MAX)
                .to_be_bytes(),
        );
        for attachment in item.attachments() {
            bytes.extend_from_slice(&attachment.slot().to_be_bytes());
            push_wire_field(&mut bytes, attachment.source_reference().as_bytes());
            push_wire_field(&mut bytes, attachment.name().as_bytes());
            push_wire_field(&mut bytes, attachment.digest().as_bytes());
            bytes.extend_from_slice(&attachment.size_bytes().to_be_bytes());
        }
    }
    bytes
}

fn decode_batch_plan(bytes: &[u8]) -> Result<LanBatchPlan, LomoError> {
    let (batch_id, mut cursor) = take_wire_field_with(bytes, 0, batch_wire_invalid)?;
    let batch_id = LanBatchId::parse(wire_utf8_with(batch_id, batch_wire_invalid)?)?;
    let item_count = usize::from(take_wire_u16_with(bytes, cursor, batch_wire_invalid)?);
    cursor = cursor.saturating_add(2);
    if item_count > crate::limits::MAX_BATCH_ITEMS {
        return Err(resource_limit(
            "lan_batch_too_many_items",
            "batch exceeds the 100-item LAN ceiling; use a workspace archive instead",
        ));
    }
    let mut items = Vec::with_capacity(item_count);
    for index in 0..item_count {
        let timestamp_ms = take_wire_i64_with(bytes, cursor, batch_wire_invalid)?;
        cursor = cursor.saturating_add(8);
        let (digest, next) = take_wire_field_with(bytes, cursor, batch_wire_invalid)?;
        cursor = next;
        let content_bytes = take_wire_u64_with(bytes, cursor, batch_wire_invalid)?;
        cursor = cursor.saturating_add(8);
        let (title, next) = take_wire_field_with(bytes, cursor, batch_wire_invalid)?;
        cursor = next;
        let attachment_count = usize::from(take_wire_u16_with(bytes, cursor, batch_wire_invalid)?);
        cursor = cursor.saturating_add(2);
        let mut attachments = Vec::with_capacity(attachment_count);
        for _attachment in 0..attachment_count {
            let slot = take_wire_u16_with(bytes, cursor, batch_wire_invalid)?;
            cursor = cursor.saturating_add(2);
            let (source_reference, next) = take_wire_field_with(bytes, cursor, batch_wire_invalid)?;
            let (name, next) = take_wire_field_with(bytes, next, batch_wire_invalid)?;
            let (attachment_digest, next) = take_wire_field_with(bytes, next, batch_wire_invalid)?;
            let size_bytes = take_wire_u64_with(bytes, next, batch_wire_invalid)?;
            cursor = next.saturating_add(8);
            attachments.push(LanAttachmentRef::new(
                slot,
                wire_utf8_with(source_reference, batch_wire_invalid)?,
                wire_utf8_with(name, batch_wire_invalid)?,
                wire_utf8_with(attachment_digest, batch_wire_invalid)?,
                size_bytes,
            )?);
        }
        items.push(LanItemPlan::new(
            &batch_id,
            u16::try_from(index).map_err(|_error| batch_wire_invalid())?,
            timestamp_ms,
            wire_utf8_with(digest, batch_wire_invalid)?,
            content_bytes,
            wire_utf8_with(title, batch_wire_invalid)?,
            attachments,
        )?);
    }
    assert_wire_end_with(bytes, cursor, batch_wire_invalid)?;
    LanBatchPlan::new(batch_id, items)
}

fn parse_session_ephemeral(bytes: &[u8]) -> Result<Vec<u8>, LomoError> {
    if bytes.len() != 32 {
        return Err(validation(
            "lan_session_ephemeral_invalid",
            "session ephemeral public key must be 32 bytes",
        ));
    }
    Ok(bytes.to_vec())
}

fn parse_ephemeral(bytes: &[u8]) -> Result<Vec<u8>, LomoError> {
    if bytes.len() != 32 {
        return Err(validation(
            "lan_pairing_ephemeral_invalid",
            "ephemeral public key must be 32 bytes",
        ));
    }
    Ok(bytes.to_vec())
}

fn push_wire_field(buffer: &mut Vec<u8>, value: &[u8]) {
    let length = u16::try_from(value.len()).unwrap_or(u16::MAX);
    buffer.extend_from_slice(&length.to_be_bytes());
    buffer.extend_from_slice(value);
}

fn take_wire_field(bytes: &[u8], cursor: usize) -> Result<(&[u8], usize), LomoError> {
    take_wire_field_with(bytes, cursor, pairing_wire_invalid)
}

fn take_wire_field_with(
    bytes: &[u8],
    cursor: usize,
    error: fn() -> LomoError,
) -> Result<(&[u8], usize), LomoError> {
    let length_slice = bytes
        .get(cursor..cursor.saturating_add(2))
        .ok_or_else(error)?;
    let length_bytes: [u8; 2] = length_slice.try_into().map_err(|_error| error())?;
    let length = usize::from(u16::from_be_bytes(length_bytes));
    let start = cursor.saturating_add(2);
    let end = start.checked_add(length).ok_or_else(error)?;
    let value = bytes.get(start..end).ok_or_else(error)?;
    Ok((value, end))
}

fn take_wire_i64(bytes: &[u8], cursor: usize) -> Result<i64, LomoError> {
    take_wire_i64_with(bytes, cursor, pairing_wire_invalid)
}

fn take_wire_i64_with(
    bytes: &[u8],
    cursor: usize,
    error: fn() -> LomoError,
) -> Result<i64, LomoError> {
    let slice = bytes
        .get(cursor..cursor.saturating_add(8))
        .ok_or_else(error)?;
    let value: [u8; 8] = slice.try_into().map_err(|_error| error())?;
    Ok(i64::from_be_bytes(value))
}

fn take_wire_u16(bytes: &[u8], cursor: usize) -> Result<u16, LomoError> {
    take_wire_u16_with(bytes, cursor, pairing_wire_invalid)
}

fn take_wire_u8_with(
    bytes: &[u8],
    cursor: usize,
    error: fn() -> LomoError,
) -> Result<u8, LomoError> {
    bytes.get(cursor).copied().ok_or_else(error)
}

fn take_wire_u16_with(
    bytes: &[u8],
    cursor: usize,
    error: fn() -> LomoError,
) -> Result<u16, LomoError> {
    let slice = bytes
        .get(cursor..cursor.saturating_add(2))
        .ok_or_else(error)?;
    let value: [u8; 2] = slice.try_into().map_err(|_error| error())?;
    Ok(u16::from_be_bytes(value))
}

fn take_wire_u32_with(
    bytes: &[u8],
    cursor: usize,
    error: fn() -> LomoError,
) -> Result<u32, LomoError> {
    let slice = bytes
        .get(cursor..cursor.saturating_add(4))
        .ok_or_else(error)?;
    let value: [u8; 4] = slice.try_into().map_err(|_error| error())?;
    Ok(u32::from_be_bytes(value))
}

fn take_wire_u64_with(
    bytes: &[u8],
    cursor: usize,
    error: fn() -> LomoError,
) -> Result<u64, LomoError> {
    let slice = bytes
        .get(cursor..cursor.saturating_add(8))
        .ok_or_else(error)?;
    let value: [u8; 8] = slice.try_into().map_err(|_error| error())?;
    Ok(u64::from_be_bytes(value))
}

fn assert_wire_end(bytes: &[u8], cursor: usize) -> Result<(), LomoError> {
    if cursor != bytes.len() {
        return Err(pairing_wire_invalid());
    }
    Ok(())
}

fn assert_wire_end_with(
    bytes: &[u8],
    cursor: usize,
    error: fn() -> LomoError,
) -> Result<(), LomoError> {
    if cursor != bytes.len() {
        return Err(error());
    }
    Ok(())
}

fn wire_utf8(bytes: &[u8]) -> Result<&str, LomoError> {
    std::str::from_utf8(bytes).map_err(|_error| pairing_wire_invalid())
}

fn wire_utf8_with(bytes: &[u8], error: fn() -> LomoError) -> Result<&str, LomoError> {
    std::str::from_utf8(bytes).map_err(|_error| error())
}

fn pairing_wire_invalid() -> LomoError {
    validation(
        "lan_pairing_wire_invalid",
        "pairing control payload is truncated or malformed",
    )
}

fn session_wire_invalid() -> LomoError {
    validation(
        "lan_session_wire_invalid",
        "session control payload is truncated or malformed",
    )
}

fn batch_wire_invalid() -> LomoError {
    validation(
        "lan_batch_wire_invalid",
        "batch control payload is truncated or malformed",
    )
}

fn chunk_wire_invalid() -> LomoError {
    validation(
        "lan_chunk_wire_invalid",
        "chunk payload is truncated or malformed",
    )
}

fn hex_bytes(bytes: &[u8]) -> String {
    let mut encoded = String::with_capacity(bytes.len().saturating_mul(2));
    for byte in bytes {
        encoded.push(hex_nibble(byte >> 4));
        encoded.push(hex_nibble(byte & 0x0f));
    }
    encoded
}

const fn hex_nibble(value: u8) -> char {
    match value {
        0 => '0',
        1 => '1',
        2 => '2',
        3 => '3',
        4 => '4',
        5 => '5',
        6 => '6',
        7 => '7',
        8 => '8',
        9 => '9',
        10 => 'a',
        11 => 'b',
        12 => 'c',
        13 => 'd',
        14 => 'e',
        _ => 'f',
    }
}
