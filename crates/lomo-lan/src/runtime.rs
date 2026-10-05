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

use crate::journal::LanStagedPayload;
use aws_lc_rs::agreement;
use aws_lc_rs::rand::{SecureRandom, SystemRandom};
use lomo_core::{ErrorCategory, LomoError};
use sha2::{Digest, Sha256};

use crate::batch::{
    LanApproval, LanAttachmentRef, LanBatchDecision, LanBatchId, LanBatchPlan, LanBatchPreview,
    LanDurableBatch, LanItemOutcome, LanItemPlan, chunk_count, expected_chunk_length,
    planned_payload, planned_payload_coordinates,
};
use crate::commit::{
    ApprovedGeneration, AuthorizedReceivedAttachment, AuthorizedReceivedCreate, ReceivedItem,
    authorize_item_commit,
};
use crate::error::{
    authentication, conflict, internal, network, permission, resource_limit, validation,
};
use crate::frame::{FrameKind, LAN_PROTOCOL_VERSION, LanFrame};
use crate::identity::{DeviceId, DisplayName, PeerRecord};
use crate::journal::{LanDurableOutgoingBatch, LanJournal, LanJournalPaths, LanOutgoingDecision};
use crate::pairing::{PairingTranscript, derive_pairing_code, verify_pairing_confirmation};
use crate::session::{
    ATTACHMENT_SLOT_BODY, ChunkBinding, ControlBinding, LanDirection, LanSessionId, NONCE_BYTES,
    SessionControlKind, SessionKey, SessionTranscript,
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

    /// Remaining pairing lifetime for UI display. The protocol deadline is not chosen by Kotlin.
    #[must_use]
    pub const fn remaining_ttl_ms(&self, now_ms: i64) -> i64 {
        crate::limits::remaining_ttl_ms(now_ms, self.deadline_ms)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct LocalDeviceIdentity {
    public_key: crate::identity::DevicePublicKey,
    display_name: DisplayName,
}

/// Per-source admission budget for unauthenticated pairing hellos.
#[derive(Debug)]
struct PairHelloWindow {
    window_start_ms: i64,
    admitted: u32,
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

    /// Remaining session lifetime for UI display. The protocol deadline is not chosen by Kotlin.
    #[must_use]
    pub const fn remaining_ttl_ms(&self, now_ms: i64) -> i64 {
        crate::limits::remaining_ttl_ms(now_ms, self.deadline_ms)
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

/// The durable drive a received batch needs next — the honest next step, never inferred by the
/// platform from absence of data.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LanReceivedBatchDrive {
    /// Waiting for the local user's approval decision.
    AwaitingDecision,
    /// Approved and session-bound; payload chunks are still arriving.
    Receiving,
    /// Approved with at least one retryable item whose payloads are all durably confirmed;
    /// commit work may proceed without any live session.
    ReadyToCommit,
    /// Durable work exists but its session is gone; the sender must re-authenticate and rebind the
    /// same batch id.
    NeedsRebind,
    /// The approval TTL lapsed before completion; the batch needs a fresh user decision.
    ApprovalExpired,
    /// Explicitly rejected; durable state retires inside the anti-replay window.
    Rejected,
    /// Every item reached a terminal outcome.
    Complete,
}

/// The durable drive an outgoing batch needs next.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LanOutgoingBatchDrive {
    /// Prepared and waiting for the peer's approval decision.
    AwaitingDecision,
    /// Approved, session-bound and chunks are still owed to the receiver.
    Sendable,
    /// Every chunk is durably confirmed by the receiver; the per-item outcome report is pending.
    AwaitingReport,
    /// Durable work exists but its session is gone; re-authenticate and rebind the same batch id.
    NeedsRebind,
    /// The peer rejected the batch; the id retires inside the anti-replay window.
    Rejected,
    /// The peer or transport refused the batch with a stable disposition code.
    Failed,
    /// Every item reached a terminal outcome.
    Complete,
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
    drive: LanReceivedBatchDrive,
    /// Durable confirmed bytes: the only honest progress numerator.
    confirmed_bytes: u64,
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
    pub const fn confirmed_bytes(&self) -> u64 {
        self.confirmed_bytes
    }

    #[must_use]
    pub const fn decision(&self) -> LanReceivedBatchDecision {
        self.decision
    }

    #[must_use]
    pub const fn drive(&self) -> LanReceivedBatchDrive {
        self.drive
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

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanOutgoingBatch {
    batch_id: LanBatchId,
    session_id: LanSessionId,
    peer_device_id: DeviceId,
    peer_display_name: DisplayName,
    drive: LanOutgoingBatchDrive,
    failure_code: Option<String>,
    /// Durable confirmed and planned bytes: progress is durable-fact driven, never wire-guessed.
    confirmed_bytes: u64,
    total_bytes: u64,
}

impl LanOutgoingBatch {
    #[must_use]
    pub const fn batch_id(&self) -> &LanBatchId {
        &self.batch_id
    }

    #[must_use]
    pub const fn session_id(&self) -> &LanSessionId {
        &self.session_id
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
    pub const fn drive(&self) -> LanOutgoingBatchDrive {
        self.drive
    }

    #[must_use]
    pub fn failure_code(&self) -> Option<&str> {
        self.failure_code.as_deref()
    }

    #[must_use]
    pub const fn confirmed_bytes(&self) -> u64 {
        self.confirmed_bytes
    }

    #[must_use]
    pub const fn total_bytes(&self) -> u64 {
        self.total_bytes
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum SessionRole {
    Opener,
    Responder,
}

impl SessionRole {
    const fn send_direction(self) -> LanDirection {
        match self {
            Self::Opener => LanDirection::Forward,
            Self::Responder => LanDirection::Reverse,
        }
    }

    const fn receive_direction(self) -> LanDirection {
        match self {
            Self::Opener => LanDirection::Reverse,
            Self::Responder => LanDirection::Forward,
        }
    }
}

#[derive(Debug)]
struct PendingSession {
    challenge: LanSessionChallenge,
    transcript: SessionTranscript,
    peer_public_key: crate::identity::DevicePublicKey,
    peer_address: SocketAddr,
    key: SessionKey,
    role: SessionRole,
    local_confirmed: bool,
    peer_signature: Option<Vec<u8>>,
}

#[derive(Debug)]
struct ActiveSession {
    snapshot: LanSessionSnapshot,
    peer_address: SocketAddr,
    key: SessionKey,
    role: SessionRole,
    next_control_send: u32,
    last_control_recv: Option<u32>,
    /// SHA-256 of the first plaintext planned for each chunk coordinate in this session. A
    /// deterministic chunk nonce may only ever seal identical bytes for one coordinate; the pin
    /// turns "same coordinate, different source bytes" into a planning refusal instead of a
    /// nonce-reused second seal.
    planned_digests: BTreeMap<ChunkBinding, [u8; 32]>,
}

impl ActiveSession {
    fn allocate_control_sequence(&mut self) -> Result<u32, LomoError> {
        let sequence = self.next_control_send;
        self.next_control_send = sequence.checked_add(1).ok_or_else(|| {
            resource_limit(
                "lan_control_nonce_exhausted",
                "control nonce space is exhausted; a new crypto session is required",
            )
        })?;
        Ok(sequence)
    }

    fn accept_control_sequence(&mut self, sequence: u32) -> Result<(), LomoError> {
        match self.last_control_recv {
            None if sequence == 0 => {
                self.last_control_recv = Some(0);
                Ok(())
            }
            Some(last) if sequence == last => Ok(()),
            Some(last) => {
                let expected = last.checked_add(1).ok_or_else(|| {
                    resource_limit(
                        "lan_control_nonce_exhausted",
                        "control nonce space is exhausted; a new crypto session is required",
                    )
                })?;
                if sequence == expected {
                    self.last_control_recv = Some(sequence);
                    Ok(())
                } else {
                    Err(authentication(
                        "lan_control_sequence_invalid",
                        "control sequence is not the next value or a retransmission of the last",
                    ))
                }
            }
            None => Err(authentication(
                "lan_control_sequence_invalid",
                "the first control sequence on a direction must be zero",
            )),
        }
    }
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
                "only the active LAN protocol version is accepted",
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
    pair_hello_windows: BTreeMap<IpAddr, PairHelloWindow>,
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
            pair_hello_windows: BTreeMap::new(),
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
            return Err(conflict(
                "lan_device_identity_changed",
                "device identity cannot change while the LAN runtime is open",
            ));
        }
        self.identity = Some(identity);
        Ok(())
    }

    /// Returns the bounded work queue derived from live handshakes and durable batch truth.
    ///
    /// Reading the inbox also drives durable maintenance at `now_ms` — session-witness expiry,
    /// terminal-batch retirement and payload reclamation — so lifecycle never waits for a lucky
    /// write to advance.
    ///
    /// # Errors
    ///
    /// Corruption/validation when durable batch coordinates cannot reconstruct valid commit work;
    /// storage when durable maintenance cannot be journaled.
    pub fn inbox(&mut self, now_ms: i64) -> Result<LanRuntimeInbox, LomoError> {
        self.journal.maintain(now_ms)?;
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
            batch_recoveries: self.batch_recoveries(now_ms)?,
            committable_items: self.committable_items(now_ms)?,
            outgoing_batches: self.outgoing_batches()?,
        })
    }

    /// Drives durable lifecycle maintenance without producing an inbox projection.
    ///
    /// # Errors
    ///
    /// Storage when a durable flush or payload reclamation fails.
    pub fn maintain(&mut self, now_ms: i64) -> Result<(), LomoError> {
        self.journal.maintain(now_ms)
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

    fn outgoing_batches(&self) -> Result<Vec<LanOutgoingBatch>, LomoError> {
        self.journal
            .outgoing_batches()
            .map(|batch| {
                let drive = if batch.failure_code().is_some() {
                    LanOutgoingBatchDrive::Failed
                } else if batch.decision() == LanOutgoingDecision::Rejected {
                    LanOutgoingBatchDrive::Rejected
                } else if batch.decision() == LanOutgoingDecision::Approved
                    && batch.snapshot().is_complete()
                {
                    LanOutgoingBatchDrive::Complete
                } else if !self.active_sessions.contains_key(batch.session_id()) {
                    LanOutgoingBatchDrive::NeedsRebind
                } else if batch.decision() == LanOutgoingDecision::AwaitingApproval {
                    LanOutgoingBatchDrive::AwaitingDecision
                } else if batch.all_payloads_confirmed()? {
                    LanOutgoingBatchDrive::AwaitingReport
                } else {
                    LanOutgoingBatchDrive::Sendable
                };
                Ok(LanOutgoingBatch {
                    batch_id: batch.plan().batch_id().clone(),
                    session_id: batch.session_id().clone(),
                    peer_device_id: batch.peer_device_id().clone(),
                    peer_display_name: batch.peer_display_name().clone(),
                    drive,
                    failure_code: batch.failure_code().map(str::to_owned),
                    confirmed_bytes: batch.confirmed_payload_bytes()?,
                    total_bytes: batch.plan().total_bytes(),
                })
            })
            .collect()
    }

    /// Items still eligible for the automatic commit loop: pending outcome, confirmed payload,
    /// and a live approval. A durably failed item leaves this queue until an explicit retry drive
    /// re-opens it through `authorize_received_item_create`.
    fn committable_items(&self, now_ms: i64) -> Result<Vec<LanCommittableItem>, LomoError> {
        let mut items = Vec::new();
        for batch in self
            .journal
            .batches()
            .filter(|batch| matches!(batch.decision(), LanBatchDecision::Approved { .. }))
        {
            let approval_live = batch
                .approval()
                .is_some_and(|approval| approval.assert_valid_at(now_ms).is_ok());
            for item in batch.plan().items() {
                let pending = matches!(
                    batch.snapshot().outcome(item.item_id()),
                    Some(LanItemOutcome::Pending)
                );
                if pending
                    && approval_live
                    && self.item_payloads_are_confirmed(batch, item.index())?
                {
                    items.push(LanCommittableItem {
                        batch_id: batch.plan().batch_id().clone(),
                        item_index: item.index(),
                    });
                }
            }
        }
        Ok(items)
    }

    fn batch_recoveries(&self, now_ms: i64) -> Result<Vec<LanBatchRecovery>, LomoError> {
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
                    drive: self.received_drive(batch, now_ms)?,
                    confirmed_bytes: self.journal.confirmed_payload_bytes(batch)?,
                    items,
                })
            })
            .collect()
    }

    /// The honest next step for one received batch: terminal state first, then approval validity,
    /// then commit work that needs no session, then session rebinding, then plain receiving.
    fn received_drive(
        &self,
        batch: &LanDurableBatch,
        now_ms: i64,
    ) -> Result<LanReceivedBatchDrive, LomoError> {
        Ok(match batch.decision() {
            LanBatchDecision::Pending => LanReceivedBatchDrive::AwaitingDecision,
            LanBatchDecision::Rejected { .. } => LanReceivedBatchDrive::Rejected,
            LanBatchDecision::Approved { approval, .. } => {
                if batch.snapshot().is_complete() {
                    LanReceivedBatchDrive::Complete
                } else if approval.assert_valid_at(now_ms).is_err() {
                    LanReceivedBatchDrive::ApprovalExpired
                } else if self.batch_has_committable_item(batch)? {
                    LanReceivedBatchDrive::ReadyToCommit
                } else if !self.active_sessions.contains_key(batch.session_id()) {
                    LanReceivedBatchDrive::NeedsRebind
                } else {
                    LanReceivedBatchDrive::Receiving
                }
            }
        })
    }

    fn batch_has_committable_item(&self, batch: &LanDurableBatch) -> Result<bool, LomoError> {
        for item in batch.plan().items() {
            let retryable = matches!(
                batch.snapshot().outcome(item.item_id()),
                Some(LanItemOutcome::Pending | LanItemOutcome::Failed { .. })
            );
            if retryable && self.item_payloads_are_confirmed(batch, item.index())? {
                return Ok(true);
            }
        }
        Ok(false)
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
    /// When the effective transport facts change — a different bind-candidate set or a lost
    /// local-network grant — live sessions suspend: their durable batches drive as
    /// `NeedsRebind` until the peer re-authenticates over the new topology. A pure revision bump
    /// carrying identical facts keeps every session alive.
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
            if snapshot.candidates != current.candidates
                || snapshot.local_network_permission_granted
                    != current.local_network_permission_granted
            {
                self.pending_pairings.clear();
                self.pending_sessions.clear();
                self.active_sessions.clear();
                self.pair_hello_windows.clear();
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
        self.pair_hello_windows.clear();
        self.service = LanServiceSnapshot::stopped();
        self.service.clone()
    }

    /// Begins one pairing exchange over the Rust-owned v2 framed socket.
    ///
    /// Composed single-call path for callers that may block under their own lock discipline:
    /// [`Self::plan_pairing`] under the lock, [`LanPairingExchange::exchange`] off-lock, then
    /// [`Self::apply_pairing_exchange`] under the lock again.
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
        let exchange = self.plan_pairing(peer, now_ms, ttl_ms)?;
        let reply = exchange.exchange()?;
        self.apply_pairing_exchange(exchange, &reply)
    }

    /// Plans one outbound pairing hello: every check and the ephemeral generation run under the
    /// caller's lock, but no byte touches a socket here.
    ///
    /// The declared deadline is clamped to the local pairing TTL: a deadline is a promise about
    /// *our own* pending capacity, so it can never exceed the local horizon even when a caller
    /// asks for longer.
    ///
    /// # Errors
    ///
    /// Validation for a non-positive TTL, missing identity or a stopped listener; authentication
    /// for a revoked peer.
    pub fn plan_pairing(
        &mut self,
        peer: &DiscoveredPeerEndpoint,
        now_ms: i64,
        ttl_ms: i64,
    ) -> Result<LanPairingExchange, LomoError> {
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
            deadline_ms: now_ms.saturating_add(ttl_ms.min(crate::limits::PAIRING_TTL_MS)),
        };
        Ok(LanPairingExchange {
            pairing_id,
            local,
            ephemeral,
            peer_device_id: peer.device_id().clone(),
            peer_address: peer.address(),
            frame: LanFrame::new(FrameKind::PairHello, encode_pair_hello(&hello))?,
            hello,
        })
    }

    /// Commits an answered pairing exchange: authenticates the accept and installs the pending
    /// challenge under the caller's lock. A refusal reply surfaces as a typed conflict; any other
    /// frame kind is an ordering violation.
    ///
    /// # Errors
    ///
    /// Conflict for a peer refusal; validation/authentication for a malformed, mismatched or
    /// out-of-order accept.
    pub fn apply_pairing_exchange(
        &mut self,
        exchange: LanPairingExchange,
        accept_frame: &LanFrame,
    ) -> Result<LanPairingChallenge, LomoError> {
        if accept_frame.kind() == FrameKind::Error {
            let code = decode_error_reply(accept_frame.payload())?;
            return Err(conflict(
                &code,
                "the peer refused this pairing request with a typed disposition",
            ));
        }
        if accept_frame.kind() != FrameKind::PairAccept {
            return Err(validation(
                "lan_pairing_frame_order_invalid",
                "pairing initiator expected a PairAccept frame",
            ));
        }
        let accept = decode_pair_accept(accept_frame.payload())?;
        if accept.pairing_id != exchange.pairing_id {
            return Err(validation(
                "lan_pairing_id_mismatch",
                "pairing response identity does not match the request",
            ));
        }
        if DeviceId::derive(&accept.public_key) != exchange.peer_device_id {
            return Err(authentication(
                "lan_pairing_peer_mismatch",
                "pairing response key does not match the discovered peer identity",
            ));
        }
        let shared = exchange.ephemeral.agree(&accept.ephemeral_public)?;
        let transcript = PairingTranscript::build(
            &exchange.local.public_key,
            &exchange.local.display_name,
            &exchange.hello.ephemeral_public,
            &accept.public_key,
            &accept.display_name,
            &accept.ephemeral_public,
            &shared,
        )?;
        let challenge = pairing_challenge(
            exchange.pairing_id.clone(),
            &accept.public_key,
            accept.display_name.clone(),
            &transcript,
            exchange.hello.deadline_ms,
        );
        self.pending_pairings.insert(
            exchange.pairing_id,
            PendingPairing {
                challenge: challenge.clone(),
                transcript,
                peer_public_key: accept.public_key,
                peer_address: exchange.peer_address,
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
        let Some((stream, peer_address)) =
            poll_peer(listener, LISTENER_POLL_TIMEOUT, pairing_deadlines()?)?
        else {
            return Ok(());
        };
        self.handle_inbound(stream, peer_address, now_ms)
    }

    /// Clones the bound listener so an accept pump can wait without holding the runtime mutex.
    ///
    /// # Errors
    ///
    /// Network when the OS cannot duplicate the listening socket.
    pub fn clone_listener(&self) -> Result<Option<TcpListener>, LomoError> {
        let Some(listener) = self.listener.as_ref() else {
            return Ok(None);
        };
        listener.try_clone().map(Some).map_err(|_error| {
            network(
                "lan_listener_clone_failed",
                "LAN listener cannot be shared with the accept pump",
                lomo_core::RetryDisposition::Transient,
            )
        })
    }

    /// Waits at most the listener poll timeout for one inbound connection on a cloned socket.
    ///
    /// # Errors
    ///
    /// Network when accept fails or deadlines cannot be applied.
    pub fn accept_one(
        listener: &TcpListener,
    ) -> Result<Option<(crate::transport::FrameStream<TcpStream>, SocketAddr)>, LomoError> {
        poll_peer(listener, LISTENER_POLL_TIMEOUT, pairing_deadlines()?)
    }

    /// Processes one already-accepted inbound control connection.
    ///
    /// Single-frame path for `poll_listener`: reads one frame, computes the reply under the
    /// manager, then writes it.
    ///
    /// # Errors
    ///
    /// Lifecycle, network, validation or authentication errors from the receive state.
    pub fn handle_inbound(
        &mut self,
        mut stream: crate::transport::FrameStream<TcpStream>,
        peer_address: SocketAddr,
        now_ms: i64,
    ) -> Result<(), LomoError> {
        let frame = stream.read_frame()?;
        if let Some(reply) = self.handle_inbound_frame(peer_address, &frame, now_ms)? {
            stream.write_frame(&reply)?;
        }
        Ok(())
    }

    /// Validates and durably applies one inbound frame, returning the reply to write.
    ///
    /// This is the engine-lock phase: durable admission/staging and reply construction only —
    /// the caller writes the reply off-lock, so a slow peer never holds protocol state.
    ///
    /// # Errors
    ///
    /// Lifecycle, validation or authentication errors from the receive state.
    pub fn handle_inbound_frame(
        &mut self,
        peer_address: SocketAddr,
        frame: &LanFrame,
        now_ms: i64,
    ) -> Result<Option<LanFrame>, LomoError> {
        self.journal.maintain(now_ms)?;
        match frame.kind() {
            FrameKind::PairHello => self.handle_pair_hello(peer_address, frame.payload(), now_ms),
            FrameKind::PairConfirm => self.handle_pair_confirm(frame.payload(), now_ms),
            FrameKind::SessionHello => {
                self.handle_session_hello(peer_address, frame.payload(), now_ms)
            }
            FrameKind::SessionConfirm => self.handle_session_confirm(frame.payload(), now_ms),
            FrameKind::BatchPrepare => self.handle_batch_prepare(frame.payload(), now_ms),
            FrameKind::BatchApprove => self.handle_batch_approve(frame.payload()),
            FrameKind::BatchReject => self.handle_batch_reject(frame.payload(), now_ms),
            FrameKind::BatchComplete => self.handle_batch_complete(frame.payload(), now_ms),
            FrameKind::Chunk => self.handle_chunk(frame.payload(), now_ms),
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
        peer_address: SocketAddr,
        payload: &[u8],
        now_ms: i64,
    ) -> Result<Option<LanFrame>, LomoError> {
        match self.apply_pair_hello(peer_address, payload, now_ms) {
            Ok(accept) => Ok(Some(accept)),
            Err(error) if error_is_peer_refusal(&error) => Ok(Some(LanFrame::new(
                FrameKind::Error,
                encode_error_reply(error.code()),
            )?)),
            Err(error) => Err(error),
        }
    }

    /// Admits one unauthenticated pairing hello under rate, capacity and TTL budgets *before*
    /// any ephemeral-key or key-agreement work runs.
    fn admit_pair_hello(&mut self, source: IpAddr, now_ms: i64) -> Result<(), LomoError> {
        self.pending_pairings
            .retain(|_pairing_id, pending| pending.challenge.deadline_ms > now_ms);
        if self.pending_pairings.len() >= crate::limits::MAX_PENDING_PAIRINGS {
            return Err(resource_limit(
                "lan_pairing_capacity",
                "the pending-pairing budget is exhausted; retry after confirmations or expiry",
            ));
        }
        self.pair_hello_windows.retain(|_source, window| {
            window.window_start_ms + crate::limits::PAIR_HELLO_WINDOW_MS > now_ms
        });
        if !self.pair_hello_windows.contains_key(&source)
            && self.pair_hello_windows.len() >= crate::limits::MAX_PAIR_HELLO_SOURCES
        {
            return Err(resource_limit(
                "lan_pairing_rate_limited",
                "too many distinct pairing sources are contending for admission",
            ));
        }
        let window = self
            .pair_hello_windows
            .entry(source)
            .or_insert(PairHelloWindow {
                window_start_ms: now_ms,
                admitted: 0,
            });
        if window.admitted >= crate::limits::MAX_PAIR_HELLOS_PER_WINDOW {
            return Err(resource_limit(
                "lan_pairing_rate_limited",
                "this source exceeded its pairing-hello admission budget",
            ));
        }
        window.admitted += 1;
        Ok(())
    }

    /// Admits one pairing hello and returns the `PairAccept` reply frame to write.
    fn apply_pair_hello(
        &mut self,
        peer_address: SocketAddr,
        payload: &[u8],
        now_ms: i64,
    ) -> Result<LanFrame, LomoError> {
        let hello = decode_pair_hello(payload)?;
        assert_before_deadline(
            now_ms,
            hello.deadline_ms,
            "lan_pairing_expired",
            "pairing hello arrived after its deadline",
        )?;
        // The peer-declared deadline is a request, never a grant: our pending slot is bounded by
        // the local pairing TTL, so `i64::MAX` cannot pin the capacity budget forever.
        let effective_deadline = hello
            .deadline_ms
            .min(now_ms.saturating_add(crate::limits::PAIRING_TTL_MS));
        let local = self.identity.clone().ok_or_else(identity_missing)?;
        self.admit_pair_hello(peer_address.ip(), now_ms)?;
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
        let accept = LanFrame::new(
            FrameKind::PairAccept,
            encode_pair_accept(&PairAccept {
                pairing_id: hello.pairing_id.clone(),
                public_key: local.public_key,
                display_name: local.display_name,
                ephemeral_public: ephemeral.public,
            }),
        )?;
        let challenge = pairing_challenge(
            hello.pairing_id.clone(),
            &hello.public_key,
            hello.display_name.clone(),
            &transcript,
            effective_deadline,
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
        Ok(accept)
    }

    fn handle_pair_confirm(
        &mut self,
        payload: &[u8],
        now_ms: i64,
    ) -> Result<Option<LanFrame>, LomoError> {
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
        self.commit_pair_if_complete(&confirm.pairing_id, now_ms)?;
        Ok(None)
    }

    fn handle_session_hello(
        &mut self,
        peer_address: SocketAddr,
        payload: &[u8],
        now_ms: i64,
    ) -> Result<Option<LanFrame>, LomoError> {
        match self.apply_session_hello(peer_address, payload, now_ms) {
            Ok(accept) => Ok(Some(accept)),
            Err(error) if error_is_peer_refusal(&error) => Ok(Some(LanFrame::new(
                FrameKind::Error,
                encode_error_reply(error.code()),
            )?)),
            Err(error) => Err(error),
        }
    }

    /// Admits one session hello and returns the `SessionAccept` reply frame to write.
    fn apply_session_hello(
        &mut self,
        peer_address: SocketAddr,
        payload: &[u8],
        now_ms: i64,
    ) -> Result<LanFrame, LomoError> {
        let hello = decode_session_hello(payload)?;
        assert_before_deadline(
            now_ms,
            hello.deadline_ms,
            "lan_session_expired",
            "session hello arrived after its deadline",
        )?;
        // The peer-declared deadline is a request, never a grant: our pending slot is bounded by
        // the local session TTL, so `i64::MAX` cannot pin the capacity budget forever.
        let effective_deadline = hello
            .deadline_ms
            .min(now_ms.saturating_add(crate::limits::SESSION_TTL_MS));
        self.assert_fresh_session(&hello.session_id)?;
        // Trust before capacity: an untrusted hello must not learn whether the budget is full.
        let peer_device_id = DeviceId::derive(&hello.public_key);
        let trusted = self.trusted_peer(&peer_device_id)?;
        if hello.public_key != *trusted.public_key() {
            return Err(authentication(
                "lan_session_peer_mismatch",
                "session opener key does not match the trusted peer record",
            ));
        }
        self.pending_sessions
            .retain(|_session_id, pending| pending.challenge.deadline_ms > now_ms);
        if self.pending_sessions.len() >= crate::limits::MAX_PENDING_SESSIONS {
            return Err(resource_limit(
                "lan_session_capacity",
                "the pending-session budget is exhausted; retry after confirmations or expiry",
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
        let accept = LanFrame::new(
            FrameKind::SessionAccept,
            encode_session_accept(&SessionAccept {
                session_id: hello.session_id.clone(),
                public_key: local.public_key,
                ephemeral_public: ephemeral.public,
            }),
        )?;
        let challenge = session_challenge(
            hello.session_id.clone(),
            peer_device_id,
            &transcript,
            effective_deadline,
        );
        self.pending_sessions.insert(
            hello.session_id,
            PendingSession {
                challenge,
                transcript,
                peer_public_key: hello.public_key,
                peer_address: SocketAddr::new(peer_address.ip(), hello.listen_port),
                key,
                role: SessionRole::Responder,
                local_confirmed: false,
                peer_signature: None,
            },
        );
        Ok(accept)
    }

    fn handle_session_confirm(
        &mut self,
        payload: &[u8],
        now_ms: i64,
    ) -> Result<Option<LanFrame>, LomoError> {
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
        self.commit_session_if_complete(&confirm.session_id, now_ms)?;
        Ok(None)
    }

    fn handle_batch_prepare(
        &mut self,
        payload: &[u8],
        now_ms: i64,
    ) -> Result<Option<LanFrame>, LomoError> {
        let control = decode_batch_control(
            payload,
            FrameKind::BatchPrepare,
            SessionControlKind::Prepare,
        )?;
        let body = self.open_batch_control(&control)?;
        let plan = decode_batch_plan(&body)?;
        if plan.batch_id() != &control.batch_id {
            return Err(authentication(
                "lan_batch_control_mismatch",
                "authenticated control batch id does not match its plan",
            ));
        }
        let peer_id = self
            .active_session(&control.session_id)?
            .snapshot
            .peer_device_id
            .clone();
        let peer = self.trusted_peer(&peer_id)?.clone();
        if let Err(error) =
            self.apply_batch_prepare(&control, plan, peer_id, peer.display_name(), now_ms)
        {
            if error_is_peer_refusal(&error) {
                // The refusal is itself a sealed control bound to this session/batch — a
                // cleartext code could be forged onto any pending send.
                let refusal = self.seal_batch_control(
                    &control.session_id,
                    &control.batch_id,
                    FrameKind::Error,
                    SessionControlKind::Refusal,
                    error.code().as_bytes().to_vec(),
                )?;
                return Ok(Some(LanFrame::new(
                    FrameKind::Error,
                    encode_batch_control(&refusal),
                )?));
            }
            return Err(error);
        }
        let status_body = self.encode_current_batch_status(&control.batch_id)?;
        let response = self.seal_batch_control(
            &control.session_id,
            &control.batch_id,
            FrameKind::BatchComplete,
            SessionControlKind::Complete,
            status_body,
        )?;
        Ok(Some(LanFrame::new(
            FrameKind::BatchComplete,
            encode_batch_control(&response),
        )?))
    }

    /// The durable half of batch prepare: rebind an identical resumable batch or store the new
    /// pending record. Every refusal is a typed durable fact the sender may act on.
    fn apply_batch_prepare(
        &mut self,
        control: &BatchControl,
        plan: LanBatchPlan,
        peer_id: DeviceId,
        peer_display_name: &DisplayName,
        now_ms: i64,
    ) -> Result<(), LomoError> {
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
                return Err(conflict(
                    "lan_batch_replayed_with_different_plan",
                    "batch id was reused with different authenticated metadata",
                ));
            }
        } else {
            self.journal.store_batch(LanDurableBatch::pending(
                plan,
                control.session_id.clone(),
                peer_id,
                peer_display_name.clone(),
            ))?;
        }
        Ok(())
    }

    fn handle_batch_approve(&mut self, payload: &[u8]) -> Result<Option<LanFrame>, LomoError> {
        let control = decode_batch_control(
            payload,
            FrameKind::BatchApprove,
            SessionControlKind::Approve,
        )?;
        let body = self.open_batch_control(&control)?;
        if !body.is_empty() {
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
        self.journal.approve_outgoing_batch(&control.batch_id)?;
        Ok(None)
    }

    fn handle_batch_reject(
        &mut self,
        payload: &[u8],
        now_ms: i64,
    ) -> Result<Option<LanFrame>, LomoError> {
        let control =
            decode_batch_control(payload, FrameKind::BatchReject, SessionControlKind::Reject)?;
        let body = self.open_batch_control(&control)?;
        if !body.is_empty() {
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
        self.journal
            .reject_outgoing_batch(&control.batch_id, now_ms)?;
        Ok(None)
    }

    fn handle_batch_complete(
        &mut self,
        payload: &[u8],
        now_ms: i64,
    ) -> Result<Option<LanFrame>, LomoError> {
        let control = decode_batch_control(
            payload,
            FrameKind::BatchComplete,
            SessionControlKind::Complete,
        )?;
        self.apply_authenticated_batch_status(&control, now_ms)?;
        Ok(None)
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
        now_ms: i64,
    ) -> Result<(), LomoError> {
        let body = self.open_batch_control(control)?;
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
        let status = decode_batch_status(&body)?;
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
            status.decision,
            confirmed,
            &status.outcomes,
            now_ms,
        )
    }

    /// Validates and durably stages one inbound chunk, returning the reply frame to write.
    ///
    /// The `ChunkAck`/`Error` frame is constructed under the lock but written by the caller
    /// after release, so a blocked peer write never holds protocol state.
    ///
    /// # Errors
    ///
    /// Crypto/validation errors for malformed or replayed bytes; storage errors for a refused
    /// durable write.
    fn handle_chunk(&mut self, payload: &[u8], now_ms: i64) -> Result<Option<LanFrame>, LomoError> {
        let transfer = decode_chunk_transfer(payload)?;
        match self.apply_chunk(&transfer, now_ms) {
            Ok(receipt) => Ok(Some(self.seal_chunk_response_frame(
                &receipt,
                FrameKind::ChunkAck,
                false,
                Vec::new(),
            )?)),
            Err(error) if error_is_peer_refusal(&error) => {
                Ok(Some(self.seal_chunk_response_frame(
                    &transfer.receipt,
                    FrameKind::Error,
                    true,
                    error.code().as_bytes().to_vec(),
                )?))
            }
            Err(error) => Err(error),
        }
    }

    /// Builds `receipt ∥ nonce ∥ sealed(body)` under the reply direction's batch key. The
    /// cleartext receipt exists only for the sender's window routing; the durable effect
    /// (acknowledged or refused) is settled exclusively by the sealed body.
    fn seal_chunk_response_frame(
        &self,
        receipt: &ChunkReceipt,
        kind: FrameKind,
        refusal: bool,
        body: Vec<u8>,
    ) -> Result<LanFrame, LomoError> {
        let active = self.active_session(&receipt.session_id)?;
        let binding = receipt.binding()?;
        let mut nonce = [0_u8; NONCE_BYTES];
        SystemRandom::new().fill(&mut nonce).map_err(|_error| {
            internal(
                "lan_nonce_random_failed",
                "secure random generation failed for a chunk response nonce",
            )
        })?;
        let sealed = active.key.seal_chunk_response(
            active.role.send_direction(),
            &binding,
            nonce,
            refusal,
            body,
        )?;
        let mut payload = encode_chunk_receipt(receipt);
        payload.extend_from_slice(&nonce);
        payload.extend_from_slice(&sealed);
        LanFrame::new(kind, payload)
    }

    /// The durable half of one inbound chunk: session trust, batch binding, approval window,
    /// planned coordinate/length, AEAD open, stage-then-confirm. Returns the receipt to ACK.
    fn apply_chunk(
        &mut self,
        transfer: &ChunkTransfer,
        now_ms: i64,
    ) -> Result<ChunkReceipt, LomoError> {
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
            if matches!(batch.decision(), LanBatchDecision::Rejected { .. }) {
                return Err(permission(
                    "lan_batch_rejected",
                    "a rejected batch refuses every later chunk",
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
        let plaintext = {
            let active = self.active_session(&transfer.receipt.session_id)?;
            active.key.open_chunk(
                active.role.receive_direction(),
                &binding,
                transfer.sealed.clone(),
            )?
        };
        if plaintext.len() != expected_length {
            return Err(validation(
                "lan_chunk_length_mismatch",
                "opened chunk length does not match its planned payload range",
            ));
        }
        self.journal.stage_chunk(&binding, &plaintext)?;
        self.journal.confirm_chunk(&binding)?;
        Ok(transfer.receipt.clone())
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
    /// Composed single-call path: [`Self::plan_pairing_confirm`] under the lock,
    /// [`LanControlSend::deliver`] off-lock, [`Self::apply_pairing_confirmed`] under the lock.
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
        let send = self.plan_pairing_confirm(pairing_id, signature, now_ms)?;
        send.deliver()?;
        self.apply_pairing_confirmed(pairing_id, now_ms)
    }

    /// Plans one pairing confirmation: the signature is verified against the pending transcript
    /// and the frame is built under the caller's lock; no socket is touched.
    ///
    /// # Errors
    ///
    /// Validation when the pairing is not pending; permission after its deadline; authentication
    /// for an invalid local signature.
    pub fn plan_pairing_confirm(
        &mut self,
        pairing_id: &LanPairingId,
        signature: &[u8],
        now_ms: i64,
    ) -> Result<LanControlSend, LomoError> {
        let local = self.identity.clone().ok_or_else(identity_missing)?;
        let pending = self
            .pending_pairings
            .get(pairing_id)
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
        Ok(LanControlSend {
            address: pending.peer_address,
            frame: LanFrame::new(
                FrameKind::PairConfirm,
                encode_pair_confirm(&PairConfirm {
                    pairing_id: pairing_id.clone(),
                    signature: signature.to_vec(),
                }),
            )?,
        })
    }

    /// Marks the local confirmation durably after the confirm frame left the wire; completes the
    /// pairing when the peer's signature already arrived.
    ///
    /// # Errors
    ///
    /// Validation when the pairing is not pending; storage when the trusted-peer record cannot
    /// be journaled.
    pub fn apply_pairing_confirmed(
        &mut self,
        pairing_id: &LanPairingId,
        now_ms: i64,
    ) -> Result<(), LomoError> {
        let pending = self
            .pending_pairings
            .get_mut(pairing_id)
            .ok_or_else(|| validation("lan_pairing_unknown", "pairing is not pending"))?;
        pending.local_confirmed = true;
        self.commit_pair_if_complete(pairing_id, now_ms)
    }

    /// Opens a fresh mutually authenticated session with a trusted discovered peer.
    ///
    /// Composed single-call path: [`Self::plan_session`] under the lock,
    /// [`LanSessionExchange::exchange`] off-lock, then [`Self::apply_session_exchange`] under
    /// the lock again.
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
        let exchange = self.plan_session(peer, now_ms, ttl_ms)?;
        let reply = exchange.exchange()?;
        self.apply_session_exchange(exchange, &reply)
    }

    /// Plans one outbound session hello: trust, freshness and ephemeral generation under the
    /// caller's lock; no socket is touched. The declared deadline is clamped to the local
    /// session TTL so a pending session can never outlive the local horizon.
    ///
    /// # Errors
    ///
    /// Authentication for an unknown/revoked peer or a replayed session id; validation for a
    /// non-positive TTL or a stopped listener.
    pub fn plan_session(
        &mut self,
        peer: &DiscoveredPeerEndpoint,
        now_ms: i64,
        ttl_ms: i64,
    ) -> Result<LanSessionExchange, LomoError> {
        if ttl_ms <= 0 {
            return Err(validation(
                "lan_session_ttl_invalid",
                "session time-to-live must be positive",
            ));
        }
        self.trusted_peer(peer.device_id())?;
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
            deadline_ms: now_ms.saturating_add(ttl_ms.min(crate::limits::SESSION_TTL_MS)),
        };
        Ok(LanSessionExchange {
            session_id,
            local,
            ephemeral,
            peer_device_id: peer.device_id().clone(),
            peer_address: peer.address(),
            frame: LanFrame::new(FrameKind::SessionHello, encode_session_hello(&hello))?,
            hello,
        })
    }

    /// Commits an answered session exchange: the peer key is re-checked against the *current*
    /// trusted record so a revocation landing between plan and apply still wins.
    ///
    /// # Errors
    ///
    /// Conflict for a peer refusal; validation/authentication for a malformed, mismatched or
    /// replayed accept.
    pub fn apply_session_exchange(
        &mut self,
        exchange: LanSessionExchange,
        frame: &LanFrame,
    ) -> Result<LanSessionChallenge, LomoError> {
        if frame.kind() == FrameKind::Error {
            let code = decode_error_reply(frame.payload())?;
            return Err(conflict(
                &code,
                "the peer refused this session request with a typed disposition",
            ));
        }
        if frame.kind() != FrameKind::SessionAccept {
            return Err(validation(
                "lan_session_frame_order_invalid",
                "session opener expected a SessionAccept frame",
            ));
        }
        let accept = decode_session_accept(frame.payload())?;
        if accept.session_id != exchange.session_id {
            return Err(authentication(
                "lan_session_id_mismatch",
                "session response identity does not match the request",
            ));
        }
        let accepted_device_id = DeviceId::derive(&accept.public_key);
        let trusted = self.trusted_peer(&accepted_device_id)?;
        if accepted_device_id != exchange.peer_device_id
            || accept.public_key != *trusted.public_key()
        {
            return Err(authentication(
                "lan_session_peer_mismatch",
                "session response key does not match the trusted discovered peer",
            ));
        }
        let transcript = SessionTranscript::build(
            &exchange.session_id,
            &exchange.local.public_key,
            &exchange.hello.ephemeral_public,
            &accept.public_key,
            &accept.ephemeral_public,
        )?;
        let key = SessionKey::derive(
            &transcript,
            &exchange.ephemeral.agree(&accept.ephemeral_public)?,
        )?;
        let challenge = session_challenge(
            exchange.session_id.clone(),
            accepted_device_id,
            &transcript,
            exchange.hello.deadline_ms,
        );
        self.pending_sessions.insert(
            exchange.session_id,
            PendingSession {
                challenge: challenge.clone(),
                transcript,
                peer_public_key: accept.public_key,
                peer_address: exchange.peer_address,
                key,
                role: SessionRole::Opener,
                local_confirmed: false,
                peer_signature: None,
            },
        );
        Ok(challenge)
    }

    /// Signs locally outside Rust, sends the signature, and authenticates only after both sides
    /// have confirmed the same transcript.
    ///
    /// Composed single-call path: [`Self::plan_session_confirm`] under the lock,
    /// [`LanControlSend::deliver`] off-lock, [`Self::apply_session_confirmed`] under the lock.
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
        let send = self.plan_session_confirm(session_id, signature, now_ms)?;
        send.deliver()?;
        self.apply_session_confirmed(session_id, now_ms)
    }

    /// Plans one session confirmation: the signature is verified against the pending transcript
    /// and the frame is built under the caller's lock; no socket is touched.
    ///
    /// # Errors
    ///
    /// Validation when the session is not pending; permission after its deadline; authentication
    /// for an invalid local signature.
    pub fn plan_session_confirm(
        &mut self,
        session_id: &LanSessionId,
        signature: &[u8],
        now_ms: i64,
    ) -> Result<LanControlSend, LomoError> {
        let local = self.identity.clone().ok_or_else(identity_missing)?;
        let pending = self
            .pending_sessions
            .get(session_id)
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
        Ok(LanControlSend {
            address: pending.peer_address,
            frame: LanFrame::new(
                FrameKind::SessionConfirm,
                encode_session_confirm(&SessionConfirm {
                    session_id: session_id.clone(),
                    signature: signature.to_vec(),
                }),
            )?,
        })
    }

    /// Marks the local confirmation durably after the confirm frame left the wire; activates the
    /// session when the peer's signature already arrived.
    ///
    /// # Errors
    ///
    /// Validation when the session is not pending; storage when the accepted session id cannot
    /// be journaled.
    pub fn apply_session_confirmed(
        &mut self,
        session_id: &LanSessionId,
        now_ms: i64,
    ) -> Result<(), LomoError> {
        let pending = self
            .pending_sessions
            .get_mut(session_id)
            .ok_or_else(|| validation("lan_session_unknown", "session is not pending"))?;
        pending.local_confirmed = true;
        self.commit_session_if_complete(session_id, now_ms)
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
    /// Composed single-call path: [`Self::plan_batch_prepare`] under the lock,
    /// [`LanBatchExchange::exchange`] off-lock, [`Self::apply_batch_prepare_reply`] under the
    /// lock again.
    ///
    /// A durable refusal travels back as an AEAD-sealed error control: session-scoped refusals
    /// suspend the local session so the next inbox drives `NeedsRebind`; terminal refusals mark
    /// the outgoing batch failed durably instead of retrying forever.
    ///
    /// # Errors
    ///
    /// Validation for an unknown session; network for delivery; resource-limit/validation when
    /// the control frame cannot represent the already-validated plan; permission/conflict for a
    /// durable refusal reported by the receiver.
    pub fn prepare_batch(
        &mut self,
        session_id: &LanSessionId,
        plan: LanBatchPlan,
        now_ms: i64,
    ) -> Result<(), LomoError> {
        let exchange = self.plan_batch_prepare(session_id, plan, now_ms)?;
        let reply = exchange.exchange()?;
        self.apply_batch_prepare_reply(&exchange, &reply, now_ms)
    }

    /// Plans one batch prepare: stores/rebinds the durable outgoing record and seals the
    /// `BatchPrepare` control under the caller's lock; no socket is touched.
    ///
    /// # Errors
    ///
    /// Validation for an unknown session; storage when the outgoing record cannot be journaled;
    /// conflict when the batch id was already stored with different facts.
    pub fn plan_batch_prepare(
        &mut self,
        session_id: &LanSessionId,
        plan: LanBatchPlan,
        now_ms: i64,
    ) -> Result<LanBatchExchange, LomoError> {
        let _: i64 = now_ms;
        let body = encode_batch_plan(&plan);
        let batch_id = plan.batch_id().clone();
        let (peer_device_id, peer_display_name, address) = {
            let active = self.active_session(session_id)?;
            let peer = self.trusted_peer(&active.snapshot.peer_device_id)?;
            (
                active.snapshot.peer_device_id.clone(),
                peer.display_name().clone(),
                active.peer_address,
            )
        };
        self.journal
            .store_outgoing_batch(LanDurableOutgoingBatch::new(
                plan,
                session_id.clone(),
                peer_device_id,
                peer_display_name,
            ))?;
        let control = self.seal_batch_control(
            session_id,
            &batch_id,
            FrameKind::BatchPrepare,
            SessionControlKind::Prepare,
            body,
        )?;
        Ok(LanBatchExchange {
            session_id: session_id.clone(),
            batch_id,
            address,
            frame: LanFrame::new(FrameKind::BatchPrepare, encode_batch_control(&control))?,
        })
    }

    /// Applies the reply to a planned prepare: an authenticated batch status, or a sealed
    /// `Error`-kinded control whose refusal code must be AEAD-bound to this exact exchange.
    ///
    /// # Errors
    ///
    /// Validation when the reply carries neither kind; authentication for a refusal bound to a
    /// different session/batch or a ciphertext that fails to open; conflict/permission for the
    /// authenticated refusal itself.
    pub fn apply_batch_prepare_reply(
        &mut self,
        exchange: &LanBatchExchange,
        response: &LanFrame,
        now_ms: i64,
    ) -> Result<(), LomoError> {
        match response.kind() {
            FrameKind::BatchComplete => {
                let status = decode_batch_control(
                    response.payload(),
                    FrameKind::BatchComplete,
                    SessionControlKind::Complete,
                )?;
                self.apply_authenticated_batch_status(&status, now_ms)
            }
            FrameKind::Error => {
                let control = decode_batch_control(
                    response.payload(),
                    FrameKind::Error,
                    SessionControlKind::Refusal,
                )?;
                if control.session_id != *exchange.session_id()
                    || control.batch_id != *exchange.batch_id()
                {
                    return Err(authentication(
                        "lan_batch_refusal_foreign",
                        "a sealed refusal does not belong to this prepare exchange",
                    ));
                }
                let code = {
                    let body = self.open_batch_control(&control)?;
                    String::from_utf8(body).map_err(|_invalid| {
                        authentication(
                            "lan_refusal_body_invalid",
                            "a sealed refusal does not carry a UTF-8 disposition code",
                        )
                    })?
                };
                self.apply_outgoing_refusal(&exchange.session_id, &exchange.batch_id, &code, now_ms)
            }
            FrameKind::PairHello
            | FrameKind::PairAccept
            | FrameKind::PairConfirm
            | FrameKind::SessionHello
            | FrameKind::SessionAccept
            | FrameKind::SessionConfirm
            | FrameKind::BatchPrepare
            | FrameKind::BatchApprove
            | FrameKind::BatchReject
            | FrameKind::Chunk
            | FrameKind::ChunkAck => Err(validation(
                "lan_batch_status_frame_invalid",
                "batch prepare expected an authenticated batch status response",
            )),
        }
    }

    /// Applies a receiver's authenticated refusal code: session-scoped refusals suspend the local
    /// session so the derived drive becomes `NeedsRebind`; every other refusal is a terminal
    /// durable failure. The code must already have been authenticated by the caller.
    fn apply_outgoing_refusal(
        &mut self,
        session_id: &LanSessionId,
        batch_id: &LanBatchId,
        code: &str,
        now_ms: i64,
    ) -> Result<(), LomoError> {
        if refusal_needs_rebind(code) {
            self.active_sessions.remove(session_id);
            return Err(permission(
                code,
                "the receiver suspended this transfer context; the batch must rebind",
            ));
        }
        if self.journal.outgoing_batch(batch_id).is_some() {
            self.journal.fail_outgoing_batch(batch_id, code, now_ms)?;
        }
        Err(conflict(
            code,
            "the receiver refused this batch with a terminal durable disposition",
        ))
    }

    /// Complete durable recovery truth for a batch.
    #[must_use]
    pub fn batch_recovery(&self, batch_id: &LanBatchId) -> Option<&LanDurableBatch> {
        self.journal.batch(batch_id)
    }

    /// Persists a generation-bound approval and authenticates it back to the sender.
    ///
    /// Composed single-call path: [`Self::plan_batch_approve`] under the lock (the durable
    /// decision commits before the wire), then [`LanControlSend::deliver`] off-lock.
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
        self.plan_batch_approve(session_id, batch_id, generation, now_ms, ttl_ms)?
            .deliver()
    }

    /// Plans one batch approval: the durable decision commits under the caller's lock so a
    /// delivery failure can never roll the approval back; the returned send carries the sealed
    /// control only.
    ///
    /// # Errors
    ///
    /// Validation for unknown session/batch or non-positive TTL; permission for a mismatched
    /// session peer; storage when the approval cannot be journaled; crypto when the control
    /// cannot be sealed.
    pub fn plan_batch_approve(
        &mut self,
        session_id: &LanSessionId,
        batch_id: &LanBatchId,
        generation: ApprovedGeneration,
        now_ms: i64,
        ttl_ms: i64,
    ) -> Result<LanControlSend, LomoError> {
        if ttl_ms <= 0 {
            return Err(validation(
                "lan_approval_ttl_invalid",
                "approval time-to-live must be positive",
            ));
        }
        let (peer_id, address) = {
            let active = self.active_session(session_id)?;
            (active.snapshot.peer_device_id.clone(), active.peer_address)
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
        let control = self.seal_batch_control(
            session_id,
            batch_id,
            FrameKind::BatchApprove,
            SessionControlKind::Approve,
            Vec::new(),
        )?;
        Ok(LanControlSend {
            address,
            frame: LanFrame::new(FrameKind::BatchApprove, encode_batch_control(&control))?,
        })
    }

    /// Persists a terminal rejection and authenticates it back to the sender.
    ///
    /// Composed single-call path: [`Self::plan_batch_reject`] under the lock (the durable
    /// decision commits before the wire), then [`LanControlSend::deliver`] off-lock.
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
        self.plan_batch_reject(session_id, batch_id, rejected_at_ms)?
            .deliver()
    }

    /// Plans one batch rejection: the durable terminal decision commits under the caller's lock;
    /// the returned send carries the sealed control only.
    ///
    /// # Errors
    ///
    /// Validation for unknown session/batch; permission for a mismatched peer; conflict for an
    /// existing terminal decision; storage when the decision cannot be journaled; crypto when
    /// the control cannot be sealed.
    pub fn plan_batch_reject(
        &mut self,
        session_id: &LanSessionId,
        batch_id: &LanBatchId,
        rejected_at_ms: i64,
    ) -> Result<LanControlSend, LomoError> {
        let (peer_id, address) = {
            let active = self.active_session(session_id)?;
            (active.snapshot.peer_device_id.clone(), active.peer_address)
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
        let control = self.seal_batch_control(
            session_id,
            batch_id,
            FrameKind::BatchReject,
            SessionControlKind::Reject,
            Vec::new(),
        )?;
        Ok(LanControlSend {
            address,
            frame: LanFrame::new(FrameKind::BatchReject, encode_batch_control(&control))?,
        })
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

    /// Validates one chunk send and seals it into an immutable [`ChunkSendPlan`].
    ///
    /// This is the short-lock phase: permission/sequence checks and AEAD sealing only; the
    /// network wait belongs to the caller's connection pool.
    ///
    /// # Errors
    ///
    /// Permission before authenticated approval or after rejection/failure; validation for a
    /// foreign item/slot/index/length; crypto errors.
    pub fn plan_batch_chunk(
        &mut self,
        binding: &ChunkBinding,
        plaintext: &[u8],
    ) -> Result<ChunkSendPlan, LomoError> {
        let session_id = binding.session_id();
        let batch_id = LanBatchId::parse(binding.batch_id())?;
        let item_index = binding.item_index();
        let attachment_slot = binding.attachment_slot();
        let chunk_index = binding.chunk_index();
        let outgoing = self.journal.outgoing_batch(&batch_id).ok_or_else(|| {
            validation(
                "lan_batch_unknown",
                "chunk does not belong to an outgoing batch",
            )
        })?;
        if outgoing.failure_code().is_some() {
            return Err(permission(
                "lan_batch_failed",
                "a terminally failed batch cannot send payload bytes",
            ));
        }
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
        // A deterministic chunk nonce may seal one plaintext per coordinate. The first planned
        // digest pins the coordinate for the life of the session: re-planning the same
        // coordinate after the source bytes drifted is refused before a second seal could reuse
        // the nonce. Re-sealing identical bytes stays idempotent for window-tail replays.
        let digest: [u8; 32] = Sha256::digest(plaintext).into();
        let active = self.active_session_mut(session_id)?;
        match active.planned_digests.entry(binding.clone()) {
            std::collections::btree_map::Entry::Occupied(slot) if *slot.get() != digest => {
                return Err(authentication(
                    "lan_chunk_content_changed",
                    "the same chunk coordinate was re-planned with different plaintext bytes",
                ));
            }
            std::collections::btree_map::Entry::Occupied(_) => {}
            std::collections::btree_map::Entry::Vacant(slot) => {
                slot.insert(digest);
            }
        }
        let transfer = ChunkTransfer {
            sealed: active.key.seal_chunk(
                active.role.send_direction(),
                binding,
                plaintext.to_vec(),
            )?,
            receipt: receipt.clone(),
        };
        Ok(ChunkSendPlan {
            session_id: session_id.clone(),
            batch_id,
            receipt,
            address: active.peer_address,
            frame: LanFrame::new(FrameKind::Chunk, encode_chunk_transfer(&transfer))?,
        })
    }

    /// Advances outgoing state for one drained acknowledgement under the short lock.
    ///
    /// Both response kinds are authenticated before they touch durable state: a `ChunkAck`
    /// payload is `receipt ∥ nonce ∥ sealed` where the sealed body opens under the reply
    /// direction's batch key; an `Error` refusal adds a UTF-8 disposition code as its sealed
    /// body and updates the outgoing batch the same way `prepare_batch` does.
    ///
    /// # Errors
    ///
    /// Conflict/permission when the response is a durable refusal; authentication when the
    /// acknowledgement does not match the planned binding or fails to open; validation for a
    /// malformed response frame.
    pub fn apply_chunk_receipt(
        &mut self,
        plan: &ChunkSendPlan,
        response: &LanFrame,
        now_ms: i64,
    ) -> Result<(), LomoError> {
        if response.kind() == FrameKind::Error {
            let (receipt, nonce, sealed) = decode_chunk_response(response.payload())?;
            if receipt != *plan.receipt() {
                return Err(authentication(
                    "lan_error_frame_unsolicited",
                    "a sealed chunk refusal does not belong to the retired receipt",
                ));
            }
            let code = {
                let active = self.active_session(plan.session_id())?;
                let opened = active.key.open_chunk_response(
                    active.role.receive_direction(),
                    &receipt.binding()?,
                    nonce,
                    true,
                    sealed.to_vec(),
                )?;
                String::from_utf8(opened).map_err(|_invalid| {
                    authentication(
                        "lan_refusal_body_invalid",
                        "a sealed chunk refusal does not carry a UTF-8 disposition code",
                    )
                })?
            };
            return self.apply_outgoing_refusal(plan.session_id(), plan.batch_id(), &code, now_ms);
        }
        if response.kind() != FrameKind::ChunkAck {
            return Err(authentication(
                "lan_chunk_ack_mismatch",
                "receiver acknowledgement does not match the sent chunk binding",
            ));
        }
        let (receipt, nonce, sealed) = decode_chunk_response(response.payload())?;
        if receipt != *plan.receipt() {
            return Err(authentication(
                "lan_chunk_ack_mismatch",
                "receiver acknowledgement does not match the sent chunk binding",
            ));
        }
        let active = self.active_session(plan.session_id())?;
        let opened = active.key.open_chunk_response(
            active.role.receive_direction(),
            &receipt.binding()?,
            nonce,
            false,
            sealed.to_vec(),
        )?;
        if !opened.is_empty() {
            return Err(authentication(
                "lan_chunk_ack_mismatch",
                "receiver acknowledgement carried an unexpected sealed body",
            ));
        }
        Ok(())
    }

    /// Sends one planned body/attachment chunk and returns only after the receiver durably ACKs it.
    ///
    /// Full single-call path for protocol tests: plan under the manager, drive the pooled
    /// session channel, then apply every drained acknowledgement. Responses drained before a
    /// later socket error are still applied — a real receipt is an already-observed fact, never
    /// collateral of a subsequent read failure. Production callers split the same phases across
    /// their own lock via `plan_batch_chunk`/`apply_chunk_receipt`.
    ///
    /// # Errors
    ///
    /// Permission/validation/crypto errors from planning; network errors from the channel;
    /// authentication for a mismatched acknowledgement. When drained receipts and the send
    /// both failed, the first receipt-application error wins.
    pub fn send_batch_chunk(
        &mut self,
        pool: &crate::pool::LanConnectionPool,
        binding: &ChunkBinding,
        plaintext: &[u8],
        now_ms: i64,
    ) -> Result<(), LomoError> {
        let plan = self.plan_batch_chunk(binding, plaintext)?;
        let mut drained = Vec::new();
        let send = pool.send_chunk(&plan, &mut drained);
        let mut first_apply_error = None;
        for (confirmed, response) in &drained {
            if let Err(error) = self.apply_chunk_receipt(confirmed, response, now_ms) {
                first_apply_error.get_or_insert(error);
            }
        }
        if let Some(error) = first_apply_error {
            return Err(error);
        }
        send
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

    /// Returns the digest-verified staged payload artifact, or `None` while confirmed chunks are
    /// missing.
    ///
    /// The payload never materializes as one `Vec`: chunks stream into a contiguous staged file
    /// whose durable size and digest are re-checked against the plan here. A fully confirmed
    /// payload whose stored bytes fail the approved size or digest downgrades to retransmittable
    /// (coordinates dropped, staged bytes reclaimed) instead of poisoning the batch.
    ///
    /// # Errors
    ///
    /// Validation for unknown coordinates; storage on I/O failure or when the durable downgrade
    /// cannot be journaled.
    pub fn received_payload_artifact(
        &mut self,
        batch_id: &LanBatchId,
        item_index: u16,
        attachment_slot: u16,
    ) -> Result<Option<LanStagedPayload>, LomoError> {
        let batch = self
            .journal
            .batch(batch_id)
            .ok_or_else(|| validation("lan_batch_unknown", "batch was not prepared"))?;
        let expected = planned_payload(batch.plan(), item_index, attachment_slot)?;
        let total_chunks = chunk_count(expected.size_bytes)?;
        let Some(payload) = self.journal.assemble_confirmed_payload(
            batch_id,
            expected.item_index,
            expected.attachment_slot,
            total_chunks,
        )?
        else {
            return Ok(None);
        };
        if payload.size_bytes() != expected.size_bytes || payload.digest() != expected.digest {
            self.journal.unconfirm_payload(
                batch_id,
                expected.item_index,
                expected.attachment_slot,
                total_chunks,
            )?;
            return Ok(None);
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
        &mut self,
        batch_id: &LanBatchId,
        item_index: u16,
        active_generation: &str,
        now_ms: i64,
    ) -> Result<Option<AuthorizedReceivedCreate>, LomoError> {
        let (plan, approval, approved_generation, snapshot) = {
            let batch = self
                .journal
                .batch(batch_id)
                .ok_or_else(|| validation("lan_batch_unknown", "batch was not prepared"))?;
            (
                batch.plan().clone(),
                batch.approval().cloned().ok_or_else(|| {
                    permission(
                        "lan_batch_not_approved",
                        "received item cannot commit before batch approval",
                    )
                })?,
                batch.approved_generation().cloned().ok_or_else(|| {
                    permission(
                        "lan_batch_not_approved",
                        "received item has no approved workspace generation",
                    )
                })?,
                batch.snapshot().clone(),
            )
        };
        let item_plan = plan.items().get(usize::from(item_index)).ok_or_else(|| {
            validation(
                "lan_item_not_in_batch",
                "received item index does not belong to the approved batch",
            )
        })?;
        let body = self
            .received_payload_artifact(batch_id, item_index, ATTACHMENT_SLOT_BODY)?
            .ok_or_else(|| {
                validation(
                    "lan_item_body_incomplete",
                    "received item body is not fully confirmed",
                )
            })?;
        // The memo body is the one payload that must become a String for the store create path;
        // it is bounded by the approved plan size, never by the whole batch.
        let bytes = match lomo_core::read_bounded(body.path(), body.size_bytes()) {
            Ok(bytes) => bytes,
            Err(lomo_core::BoundedReadError::ExceedsLimit { .. }) => {
                return Err(validation(
                    "lan_item_body_size_invalid",
                    "verified received body artifact exceeds its durable size",
                ));
            }
            Err(lomo_core::BoundedReadError::Io(error)) => {
                return Err(crate::error::storage(
                    "lan_item_body_read_failed",
                    &format!("verified received body artifact cannot be read back: {error}"),
                ));
            }
        };
        let content = String::from_utf8(bytes).map_err(|_error| {
            validation(
                "lan_item_body_utf8_invalid",
                "received memo body must be valid UTF-8 Markdown",
            )
        })?;
        let received = ReceivedItem::verified(item_plan, content)?;
        let mut attachments = Vec::with_capacity(item_plan.attachments().len());
        for attachment in item_plan.attachments() {
            let (transfer_item_index, transfer) = plan
                .attachment_transfer_reference(attachment.digest())
                .ok_or_else(|| {
                    validation(
                        "lan_attachment_transfer_missing",
                        "received attachment has no canonical batch transfer coordinate",
                    )
                })?;
            let payload = self
                .received_payload_artifact(batch_id, transfer_item_index, transfer.slot())?
                .ok_or_else(|| {
                    validation(
                        "lan_item_attachments_incomplete",
                        "received item cannot commit before every attachment is verified",
                    )
                })?;
            attachments.push(AuthorizedReceivedAttachment::verified(
                attachment, transfer, &payload,
            )?);
        }
        authorize_item_commit(
            &approval,
            &approved_generation,
            active_generation,
            now_ms,
            &snapshot,
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

    /// Durably records a commit failure for one received item so it leaves the automatic
    /// committable queue while staying recoverable for an explicit retry drive.
    ///
    /// # Errors
    ///
    /// Validation for unknown batch/item and storage for journal persistence failures.
    pub fn record_received_item_failed(
        &mut self,
        batch_id: &LanBatchId,
        item_id: &crate::batch::LanItemId,
        code: &str,
    ) -> Result<LanItemOutcome, LomoError> {
        self.journal.record_batch_outcome(
            batch_id,
            item_id,
            LanItemOutcome::Failed {
                code: code.to_owned(),
            },
        )
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

    fn commit_session_if_complete(
        &mut self,
        session_id: &LanSessionId,
        now_ms: i64,
    ) -> Result<(), LomoError> {
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
        if let Err(error) = self.journal.accept_session(session_id, now_ms) {
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
                role: pending.role,
                next_control_send: 0,
                last_control_recv: None,
                planned_digests: BTreeMap::new(),
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

    fn active_session_mut(
        &mut self,
        session_id: &LanSessionId,
    ) -> Result<&mut ActiveSession, LomoError> {
        self.active_sessions.get_mut(session_id).ok_or_else(|| {
            authentication(
                "lan_session_not_authenticated",
                "batch control requires a mutually authenticated active session",
            )
        })
    }

    fn seal_batch_control(
        &mut self,
        session_id: &LanSessionId,
        batch_id: &LanBatchId,
        frame_kind: FrameKind,
        control_kind: SessionControlKind,
        body: Vec<u8>,
    ) -> Result<BatchControl, LomoError> {
        let active = self.active_session_mut(session_id)?;
        let sequence = active.allocate_control_sequence()?;
        let binding = ControlBinding::new(
            session_id,
            batch_id.as_str(),
            frame_kind,
            control_kind,
            sequence,
        )?;
        let sealed = active
            .key
            .seal_control(active.role.send_direction(), &binding, body)?;
        Ok(BatchControl {
            session_id: session_id.clone(),
            batch_id: batch_id.clone(),
            frame_kind,
            control_kind,
            sequence,
            sealed,
        })
    }

    fn open_batch_control(&mut self, control: &BatchControl) -> Result<Vec<u8>, LomoError> {
        let active = self.active_session_mut(&control.session_id)?;
        let binding = ControlBinding::new(
            &control.session_id,
            control.batch_id.as_str(),
            control.frame_kind,
            control.control_kind,
            control.sequence,
        )?;
        let body = active.key.open_control(
            active.role.receive_direction(),
            &binding,
            control.sealed.clone(),
        )?;
        active.accept_control_sequence(control.sequence)?;
        Ok(body)
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
    frame_kind: FrameKind,
    control_kind: SessionControlKind,
    sequence: u32,
    sealed: Vec<u8>,
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
pub struct ChunkReceipt {
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

/// One immutable, sealed chunk ready for the wire: the short-lock output of
/// [`LanServiceManager::plan_batch_chunk`].
///
/// The plan carries the sealed frame, the acknowledgement binding and the resolved peer address,
/// so the network wait can run entirely outside the protocol-state lock.
#[derive(Clone, Debug)]
pub struct ChunkSendPlan {
    session_id: LanSessionId,
    batch_id: LanBatchId,
    receipt: ChunkReceipt,
    address: SocketAddr,
    frame: LanFrame,
}

impl ChunkSendPlan {
    /// Session that owns the outbound channel for this plan.
    #[must_use]
    pub const fn session_id(&self) -> &LanSessionId {
        &self.session_id
    }

    /// Batch the chunk belongs to.
    #[must_use]
    pub const fn batch_id(&self) -> &LanBatchId {
        &self.batch_id
    }

    /// The acknowledgement binding the receiver must echo.
    #[must_use]
    pub(crate) const fn receipt(&self) -> &ChunkReceipt {
        &self.receipt
    }

    /// Resolved peer address for this session's channel.
    #[must_use]
    pub const fn address(&self) -> SocketAddr {
        self.address
    }

    /// The sealed wire frame carrying the chunk.
    #[must_use]
    pub const fn frame(&self) -> &LanFrame {
        &self.frame
    }
}

/// A validated outbound pairing hello waiting for its network exchange.
///
/// The token carries the ephemeral private key and every planned fact, so it can only be
/// produced by [`LanServiceManager::plan_pairing`] under the lock. The exchange itself runs
/// off-lock; the result commits through
/// [`LanServiceManager::apply_pairing_exchange`], which owns the durable transition.
pub struct LanPairingExchange {
    pairing_id: LanPairingId,
    local: LocalDeviceIdentity,
    ephemeral: EphemeralKey,
    hello: PairHello,
    peer_device_id: DeviceId,
    peer_address: SocketAddr,
    frame: LanFrame,
}

impl LanPairingExchange {
    /// The hello frame that was planned.
    #[must_use]
    pub const fn frame(&self) -> &LanFrame {
        &self.frame
    }

    /// The peer socket this exchange connects to.
    #[must_use]
    pub const fn peer_address(&self) -> SocketAddr {
        self.peer_address
    }

    /// Runs the bounded write/read round-trip. Must run without holding the protocol-state
    /// lock: a peer that accepts the socket and stalls must never freeze unrelated state.
    ///
    /// # Errors
    ///
    /// Network on connect/write/read or a socket deadline.
    pub fn exchange(&self) -> Result<LanFrame, LomoError> {
        let mut stream = connect_peer(
            self.peer_address,
            PAIRING_SOCKET_DEADLINE,
            pairing_deadlines()?,
        )?;
        stream.write_frame(&self.frame)?;
        stream.read_frame()
    }
}

/// A validated outbound session hello waiting for its network exchange; see
/// [`LanPairingExchange`] for the same plan/exchange/apply split.
pub struct LanSessionExchange {
    session_id: LanSessionId,
    local: LocalDeviceIdentity,
    ephemeral: EphemeralKey,
    hello: SessionHello,
    peer_device_id: DeviceId,
    peer_address: SocketAddr,
    frame: LanFrame,
}

impl LanSessionExchange {
    /// The hello frame that was planned.
    #[must_use]
    pub const fn frame(&self) -> &LanFrame {
        &self.frame
    }

    /// The peer socket this exchange connects to.
    #[must_use]
    pub const fn peer_address(&self) -> SocketAddr {
        self.peer_address
    }

    /// Runs the bounded write/read round-trip off-lock.
    ///
    /// # Errors
    ///
    /// Network on connect/write/read or a socket deadline.
    pub fn exchange(&self) -> Result<LanFrame, LomoError> {
        let mut stream = connect_peer(
            self.peer_address,
            PAIRING_SOCKET_DEADLINE,
            pairing_deadlines()?,
        )?;
        stream.write_frame(&self.frame)?;
        stream.read_frame()
    }
}

/// A planned batch prepare exchange: the sealed `BatchPrepare` is written off-lock and its
/// reply (authenticated status or sealed refusal) is applied back under the lock.
#[derive(Debug)]
pub struct LanBatchExchange {
    session_id: LanSessionId,
    batch_id: LanBatchId,
    address: SocketAddr,
    frame: LanFrame,
}

impl LanBatchExchange {
    /// The session the sealed control belongs to.
    #[must_use]
    pub const fn session_id(&self) -> &LanSessionId {
        &self.session_id
    }

    /// The batch the sealed control belongs to.
    #[must_use]
    pub const fn batch_id(&self) -> &LanBatchId {
        &self.batch_id
    }

    /// The sealed prepare frame that was planned.
    #[must_use]
    pub const fn frame(&self) -> &LanFrame {
        &self.frame
    }

    /// The peer socket this exchange connects to.
    #[must_use]
    pub const fn address(&self) -> SocketAddr {
        self.address
    }

    /// Runs the bounded write/read round-trip off-lock.
    ///
    /// # Errors
    ///
    /// Network on connect/write/read or a socket deadline.
    pub fn exchange(&self) -> Result<LanFrame, LomoError> {
        let mut stream = connect_peer(self.address, PAIRING_SOCKET_DEADLINE, pairing_deadlines()?)?;
        stream.write_frame(&self.frame)?;
        stream.read_frame()
    }
}

/// A validated one-way control send (pair/session confirm, approve, reject).
///
/// Durable state already moved when the token was planned where the protocol requires
/// durable-before-wire ordering (approve/reject); `deliver` only owns the bounded network
/// write, so it runs without holding the protocol-state lock.
#[derive(Debug)]
pub struct LanControlSend {
    address: SocketAddr,
    frame: LanFrame,
}

impl LanControlSend {
    /// The control frame that was planned.
    #[must_use]
    pub const fn frame(&self) -> &LanFrame {
        &self.frame
    }

    /// The peer socket this send connects to.
    #[must_use]
    pub const fn address(&self) -> SocketAddr {
        self.address
    }

    /// Delivers the frame under the bounded control deadline, off-lock.
    ///
    /// # Errors
    ///
    /// Network on connect/write or a socket deadline.
    pub fn deliver(&self) -> Result<(), LomoError> {
        let mut stream = connect_peer(self.address, PAIRING_SOCKET_DEADLINE, pairing_deadlines()?)?;
        stream.write_frame(&self.frame)
    }
}

#[derive(Debug, Eq, PartialEq)]
struct ChunkTransfer {
    receipt: ChunkReceipt,
    sealed: Vec<u8>,
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

fn encode_batch_control(value: &BatchControl) -> Vec<u8> {
    let mut bytes = Vec::new();
    push_wire_field(&mut bytes, value.session_id.as_str().as_bytes());
    push_wire_field(&mut bytes, value.batch_id.as_str().as_bytes());
    bytes.extend_from_slice(&value.sequence.to_be_bytes());
    bytes.extend_from_slice(&value.sealed);
    bytes
}

fn decode_batch_control(
    bytes: &[u8],
    frame_kind: FrameKind,
    control_kind: SessionControlKind,
) -> Result<BatchControl, LomoError> {
    let (session_id, cursor) = take_wire_field_with(bytes, 0, batch_wire_invalid)?;
    let (batch_id, cursor) = take_wire_field_with(bytes, cursor, batch_wire_invalid)?;
    let sequence = take_wire_u32_with(bytes, cursor, batch_wire_invalid)?;
    let sealed = bytes
        .get(cursor.saturating_add(4)..)
        .ok_or_else(batch_wire_invalid)?;
    if sealed.len() < crate::limits::AEAD_TAG_BYTES {
        return Err(authentication(
            "lan_control_open_failed",
            "session control ciphertext is shorter than the AEAD tag",
        ));
    }
    Ok(BatchControl {
        session_id: LanSessionId::parse(wire_utf8_with(session_id, batch_wire_invalid)?)?,
        batch_id: LanBatchId::parse(wire_utf8_with(batch_id, batch_wire_invalid)?)?,
        frame_kind,
        control_kind,
        sequence,
        sealed: sealed.to_vec(),
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

/// Parses the cleartext receipt prefix shared by chunks and chunk-channel responses, returning
/// the receipt and the offset where the sealed region begins.
fn take_chunk_receipt(bytes: &[u8]) -> Result<(ChunkReceipt, usize), LomoError> {
    let (session_id, cursor) = take_wire_field_with(bytes, 0, chunk_wire_invalid)?;
    let (batch_id, cursor) = take_wire_field_with(bytes, cursor, chunk_wire_invalid)?;
    let item_index = take_wire_u16_with(bytes, cursor, chunk_wire_invalid)?;
    let attachment_slot = take_wire_u16_with(bytes, cursor.saturating_add(2), chunk_wire_invalid)?;
    let chunk_index = take_wire_u32_with(bytes, cursor.saturating_add(4), chunk_wire_invalid)?;
    Ok((
        ChunkReceipt {
            session_id: LanSessionId::parse(wire_utf8_with(session_id, chunk_wire_invalid)?)?,
            batch_id: LanBatchId::parse(wire_utf8_with(batch_id, chunk_wire_invalid)?)?,
            item_index,
            attachment_slot,
            chunk_index,
        },
        cursor.saturating_add(8),
    ))
}

/// Parses one chunk-channel response payload (`receipt ∥ nonce ∥ sealed`). The cleartext
/// receipt is routing data only — authentication happens when the sealed region opens under
/// the reply direction's batch key.
pub fn decode_chunk_response(
    bytes: &[u8],
) -> Result<(ChunkReceipt, [u8; NONCE_BYTES], &[u8]), LomoError> {
    let (receipt, cursor) = take_chunk_receipt(bytes)?;
    let nonce_bytes = bytes
        .get(cursor..cursor.saturating_add(NONCE_BYTES))
        .ok_or_else(chunk_wire_invalid)?;
    let mut nonce = [0_u8; NONCE_BYTES];
    nonce.copy_from_slice(nonce_bytes);
    let sealed = bytes
        .get(cursor.saturating_add(NONCE_BYTES)..)
        .filter(|sealed| sealed.len() >= crate::limits::AEAD_TAG_BYTES)
        .ok_or_else(chunk_wire_invalid)?;
    Ok((receipt, nonce, sealed))
}

fn encode_chunk_transfer(value: &ChunkTransfer) -> Vec<u8> {
    let mut bytes = encode_chunk_receipt(&value.receipt);
    bytes.extend_from_slice(&value.sealed);
    bytes
}

fn decode_chunk_transfer(bytes: &[u8]) -> Result<ChunkTransfer, LomoError> {
    let (receipt, cursor) = take_chunk_receipt(bytes)?;
    let sealed = bytes
        .get(cursor..)
        .filter(|sealed| !sealed.is_empty())
        .ok_or_else(chunk_wire_invalid)?;
    Ok(ChunkTransfer {
        receipt,
        sealed: sealed.to_vec(),
    })
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

fn encode_error_reply(code: &str) -> Vec<u8> {
    let mut bytes = Vec::new();
    push_wire_field(&mut bytes, code.as_bytes());
    bytes
}

fn decode_error_reply(bytes: &[u8]) -> Result<String, LomoError> {
    let (code, cursor) = take_wire_field_with(bytes, 0, batch_wire_invalid)?;
    assert_wire_end_with(bytes, cursor, batch_wire_invalid)?;
    Ok(wire_utf8_with(code, batch_wire_invalid)?.to_owned())
}

/// A peer refusal is every domain refusal the receiver can meaningfully report so the sender
/// records a durable disposition. Local faults (storage, transport, internal) instead propagate
/// as errors on the receiver itself.
const fn error_is_peer_refusal(error: &LomoError) -> bool {
    !matches!(
        error.category(),
        ErrorCategory::Storage
            | ErrorCategory::Network
            | ErrorCategory::Internal
            | ErrorCategory::Corruption
    )
}

/// Refusal codes meaning the receiver lost the shared transfer context (session, batch binding,
/// or a still-recoverable approval window). The sender suspends its session so the next inbox
/// drives `NeedsRebind`; a re-prepare either rebinds durably or hits a terminal refusal.
fn refusal_needs_rebind(code: &str) -> bool {
    code.starts_with("lan_session")
        || code == "lan_chunk_session_mismatch"
        || code == "lan_batch_session_mismatch"
        || code == "lan_batch_unknown"
        || code == "lan_approval_expired"
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
