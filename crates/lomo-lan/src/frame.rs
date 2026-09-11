//! Versioned length-prefixed LAN v2 frame codec.
//!
//! Header layout (12 bytes, big-endian):
//!
//! ```text
//! magic(4) | protocol_version(2) | frame_kind(2) | payload_len(4)
//! ```
//!
//! Every header field is validated before the declared payload length is reserved, so a hostile
//! peer cannot make the receiver allocate an arbitrary buffer. Control frames and sealed chunk
//! frames carry separate ceilings; a control kind may never borrow the chunk ceiling.
//!
//! There is no v1 decoder. The Kotlin HTTP wire is not a compatible protocol and is not accepted.

use crate::error::{resource_limit, validation};
use crate::limits::{MAX_CONTROL_PAYLOAD_BYTES, MAX_SEALED_CHUNK_PAYLOAD_BYTES};
use lomo_core::LomoError;

/// Frame magic identifying the Lomo LAN wire.
pub const LAN_FRAME_MAGIC: [u8; 4] = *b"LMLN";

/// Only supported protocol version. v1 (Kotlin HTTP) has no decoder.
pub const LAN_PROTOCOL_VERSION: u16 = 2;

/// Fixed frame header length in bytes.
pub const LAN_FRAME_HEADER_BYTES: usize = 12;

/// Frame kinds on the LAN v2 wire.
///
/// Control kinds carry identity, transcripts, previews and acknowledgements. [`FrameKind::Chunk`]
/// is the only kind allowed to reach the sealed chunk ceiling.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub enum FrameKind {
    /// Pairing initiator: protocol version, device public key, display name, ephemeral public key.
    PairHello,
    /// Pairing responder: device public key, display name, ephemeral public key.
    PairAccept,
    /// Pairing confirmation: device signature over the canonical pairing transcript.
    PairConfirm,
    /// Session opener: session id, expected peer device id, ephemeral public key.
    SessionHello,
    /// Session responder: ephemeral public key plus signature over the session transcript.
    SessionAccept,
    /// Bounded batch preview shown before approval (never full bodies or attachments).
    BatchPrepare,
    /// Receiver approved the batch.
    BatchApprove,
    /// Receiver rejected the batch.
    BatchReject,
    /// Sealed body/attachment chunk.
    Chunk,
    /// Confirmed chunk range acknowledgement (drives resume).
    ChunkAck,
    /// Per-item commit results and batch completion state.
    BatchComplete,
    /// Structured error code (never secrets or bodies).
    Error,
    /// Session confirmation: device signature over the canonical session transcript.
    SessionConfirm,
}

impl FrameKind {
    /// Every frame kind, in wire-code order.
    pub const ALL: [Self; 13] = [
        Self::PairHello,
        Self::PairAccept,
        Self::PairConfirm,
        Self::SessionHello,
        Self::SessionAccept,
        Self::BatchPrepare,
        Self::BatchApprove,
        Self::BatchReject,
        Self::Chunk,
        Self::ChunkAck,
        Self::BatchComplete,
        Self::Error,
        Self::SessionConfirm,
    ];

    /// Stable wire code for this kind.
    #[must_use]
    pub const fn code(self) -> u16 {
        match self {
            Self::PairHello => 1,
            Self::PairAccept => 2,
            Self::PairConfirm => 3,
            Self::SessionHello => 4,
            Self::SessionAccept => 5,
            Self::BatchPrepare => 6,
            Self::BatchApprove => 7,
            Self::BatchReject => 8,
            Self::Chunk => 9,
            Self::ChunkAck => 10,
            Self::BatchComplete => 11,
            Self::Error => 12,
            Self::SessionConfirm => 13,
        }
    }

    /// Parses a wire code, rejecting unknown kinds before any allocation.
    ///
    /// # Errors
    ///
    /// Validation when the code is not a known LAN v2 frame kind.
    pub fn parse(code: u16) -> Result<Self, LomoError> {
        let kind = match code {
            1 => Self::PairHello,
            2 => Self::PairAccept,
            3 => Self::PairConfirm,
            4 => Self::SessionHello,
            5 => Self::SessionAccept,
            6 => Self::BatchPrepare,
            7 => Self::BatchApprove,
            8 => Self::BatchReject,
            9 => Self::Chunk,
            10 => Self::ChunkAck,
            11 => Self::BatchComplete,
            12 => Self::Error,
            13 => Self::SessionConfirm,
            _ => {
                return Err(validation(
                    "lan_frame_unknown_kind",
                    "frame kind is not a known LAN v2 kind",
                ));
            }
        };
        Ok(kind)
    }

    /// Maximum payload bytes this kind may declare.
    #[must_use]
    pub const fn max_payload_bytes(self) -> usize {
        match self {
            Self::Chunk => MAX_SEALED_CHUNK_PAYLOAD_BYTES,
            Self::PairHello
            | Self::PairAccept
            | Self::PairConfirm
            | Self::SessionHello
            | Self::SessionAccept
            | Self::SessionConfirm
            | Self::BatchPrepare
            | Self::BatchApprove
            | Self::BatchReject
            | Self::ChunkAck
            | Self::BatchComplete
            | Self::Error => MAX_CONTROL_PAYLOAD_BYTES,
        }
    }
}

/// One decoded or outbound LAN v2 frame.
///
/// The constructor enforces the same ceiling as the decoder, so an in-process bug cannot emit a
/// frame the peer must reject.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanFrame {
    kind: FrameKind,
    payload: Vec<u8>,
}

impl LanFrame {
    /// Builds a frame after validating the payload against the kind ceiling.
    ///
    /// # Errors
    ///
    /// Resource-limit when the payload exceeds the ceiling for `kind`.
    pub fn new(kind: FrameKind, payload: Vec<u8>) -> Result<Self, LomoError> {
        if payload.len() > kind.max_payload_bytes() {
            return Err(payload_too_large());
        }
        Ok(Self { kind, payload })
    }

    #[must_use]
    pub const fn kind(&self) -> FrameKind {
        self.kind
    }

    #[must_use]
    pub fn payload(&self) -> &[u8] {
        &self.payload
    }

    /// Consumes the frame and returns the payload bytes.
    #[must_use]
    pub fn into_payload(self) -> Vec<u8> {
        self.payload
    }

    /// Total wire length of this frame (header + payload).
    #[must_use]
    pub const fn wire_len(&self) -> usize {
        LAN_FRAME_HEADER_BYTES + self.payload.len()
    }
}

/// Encodes a frame to its wire bytes.
#[must_use]
pub fn encode_frame(frame: &LanFrame) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(frame.wire_len());
    bytes.extend_from_slice(&LAN_FRAME_MAGIC);
    bytes.extend_from_slice(&LAN_PROTOCOL_VERSION.to_be_bytes());
    bytes.extend_from_slice(&frame.kind.code().to_be_bytes());
    // A validated payload is always within `MAX_SEALED_CHUNK_PAYLOAD_BYTES`, far below u32::MAX.
    let declared = u32::try_from(frame.payload.len()).unwrap_or(u32::MAX);
    bytes.extend_from_slice(&declared.to_be_bytes());
    bytes.extend_from_slice(&frame.payload);
    bytes
}

/// Validates the header and returns the declared payload length **without** reserving it.
///
/// A reader uses this to decide how many bytes to read next. Magic, version, kind and ceiling are
/// all checked first, so an oversized or foreign header never becomes an allocation.
///
/// # Errors
///
/// Validation for short/foreign/unsupported headers; resource-limit when the declared length
/// exceeds the ceiling for the decoded kind.
pub fn peek_declared_payload_len(bytes: &[u8]) -> Result<usize, LomoError> {
    let header = header_slice(bytes)?;
    let magic = header
        .get(0..4)
        .ok_or_else(|| validation("lan_frame_incomplete", "frame header is truncated"))?;
    if magic != LAN_FRAME_MAGIC {
        return Err(validation(
            "lan_frame_bad_magic",
            "frame magic is not the Lomo LAN wire",
        ));
    }
    let version = be_u16(header, 4)?;
    if version != LAN_PROTOCOL_VERSION {
        return Err(validation(
            "lan_frame_unsupported_version",
            "only LAN protocol v2 is accepted; there is no legacy decoder",
        ));
    }
    let kind = FrameKind::parse(be_u16(header, 6)?)?;
    let declared = be_u32(header, 8)? as usize;
    if declared > kind.max_payload_bytes() {
        return Err(payload_too_large());
    }
    Ok(declared)
}

/// Decodes exactly one frame from `bytes`.
///
/// # Errors
///
/// `lan_frame_incomplete` when fewer than header+payload bytes are present; otherwise the same
/// header rejections as [`peek_declared_payload_len`].
pub fn decode_frame(bytes: &[u8]) -> Result<LanFrame, LomoError> {
    let declared = peek_declared_payload_len(bytes)?;
    let end = LAN_FRAME_HEADER_BYTES
        .checked_add(declared)
        .ok_or_else(payload_too_large)?;
    let payload = bytes
        .get(LAN_FRAME_HEADER_BYTES..end)
        .ok_or_else(|| validation("lan_frame_incomplete", "frame payload is truncated"))?;
    let header = header_slice(bytes)?;
    let kind = FrameKind::parse(be_u16(header, 6)?)?;
    LanFrame::new(kind, payload.to_vec())
}

fn header_slice(bytes: &[u8]) -> Result<&[u8], LomoError> {
    bytes
        .get(0..LAN_FRAME_HEADER_BYTES)
        .ok_or_else(|| validation("lan_frame_incomplete", "frame header is truncated"))
}

fn be_u16(header: &[u8], offset: usize) -> Result<u16, LomoError> {
    let slice = header
        .get(offset..offset.saturating_add(2))
        .ok_or_else(|| validation("lan_frame_incomplete", "frame header is truncated"))?;
    let bytes: [u8; 2] = slice
        .try_into()
        .map_err(|_error| validation("lan_frame_incomplete", "frame header is truncated"))?;
    Ok(u16::from_be_bytes(bytes))
}

fn be_u32(header: &[u8], offset: usize) -> Result<u32, LomoError> {
    let slice = header
        .get(offset..offset.saturating_add(4))
        .ok_or_else(|| validation("lan_frame_incomplete", "frame header is truncated"))?;
    let bytes: [u8; 4] = slice
        .try_into()
        .map_err(|_error| validation("lan_frame_incomplete", "frame header is truncated"))?;
    Ok(u32::from_be_bytes(bytes))
}

fn payload_too_large() -> LomoError {
    resource_limit(
        "lan_frame_payload_too_large",
        "declared frame payload exceeds the ceiling for its frame kind",
    )
}
