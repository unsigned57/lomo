//! Blocking LAN v2 transport: framed reads/writes with deadlines over any byte stream.
//!
//! Deliberately blocking `std::net` rather than a second async runtime: Stage 5 shipped its network
//! stack on blocking I/O plus `lomo-core`'s bounded worker pool, and LAN transfer is a handful of
//! peers, not a connection fleet. See `fixtures/contracts/lan.md`.
//!
//! The read path is the security-critical part. It reads exactly the fixed header, validates magic,
//! protocol version, frame kind and the per-kind ceiling **before** reserving anything, and only
//! then reads exactly the declared payload. A hostile peer therefore cannot make the receiver
//! allocate an arbitrary buffer, and a short read can never be mistaken for a complete frame.

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::thread;
use std::time::Duration;
use std::time::Instant;

use lomo_core::{LomoError, RetryDisposition};

use crate::error::{network, resource_limit, validation};
use crate::frame::{
    LAN_FRAME_HEADER_BYTES, LanFrame, decode_frame, encode_frame, peek_declared_payload_len,
};
use crate::limits::MAX_SEALED_CHUNK_PAYLOAD_BYTES;

/// Socket deadlines applied to every LAN endpoint.
///
/// A stalled or silent peer must release the worker rather than pin it forever.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct LanDeadlines {
    read: Duration,
    write: Duration,
}

impl LanDeadlines {
    /// Builds deadlines, rejecting a zero duration (which means "block forever" to the OS).
    ///
    /// # Errors
    ///
    /// Validation when either duration is zero.
    pub fn new(read: Duration, write: Duration) -> Result<Self, LomoError> {
        if read.is_zero() || write.is_zero() {
            return Err(validation(
                "lan_deadline_invalid",
                "LAN socket deadlines must be non-zero; zero means block forever",
            ));
        }
        Ok(Self { read, write })
    }

    #[must_use]
    pub const fn read(self) -> Duration {
        self.read
    }

    #[must_use]
    pub const fn write(self) -> Duration {
        self.write
    }
}

/// A framed LAN v2 stream over any blocking byte stream.
///
/// Generic over the stream so hermetic contracts can drive both a real `TcpStream` and an in-memory
/// duplex without a second code path.
#[derive(Debug)]
pub struct FrameStream<S> {
    stream: S,
}

impl<S: Read + Write> FrameStream<S> {
    /// Wraps a byte stream.
    pub const fn new(stream: S) -> Self {
        Self { stream }
    }

    /// Returns the wrapped stream.
    pub fn into_inner(self) -> S {
        self.stream
    }

    /// Writes one frame.
    ///
    /// # Errors
    ///
    /// Network when the write fails or the deadline fires.
    pub fn write_frame(&mut self, frame: &LanFrame) -> Result<(), LomoError> {
        let bytes = encode_frame(frame);
        self.stream
            .write_all(&bytes)
            .map_err(|error| io_error("lan_frame_write_failed", &error))?;
        self.stream
            .flush()
            .map_err(|error| io_error("lan_frame_flush_failed", &error))
    }

    /// Reads exactly one frame.
    ///
    /// Validates the header before reserving the declared payload, so an oversized or foreign
    /// header never becomes an allocation.
    ///
    /// # Errors
    ///
    /// Validation for a malformed header or a stream that ends mid-frame; resource-limit when the
    /// declared length exceeds the ceiling for its kind; network on I/O failure or deadline.
    pub fn read_frame(&mut self) -> Result<LanFrame, LomoError> {
        let mut header = [0_u8; LAN_FRAME_HEADER_BYTES];
        self.read_exact_or_incomplete(&mut header)?;

        // Validates magic, protocol version, kind and the per-kind ceiling. Nothing is reserved
        // until this returns Ok, so a hostile declared length cannot drive an allocation.
        let declared = peek_declared_payload_len(&header)?;
        if declared > MAX_SEALED_CHUNK_PAYLOAD_BYTES {
            return Err(resource_limit(
                "lan_frame_payload_too_large",
                "declared frame payload exceeds the wire ceiling",
            ));
        }

        let mut bytes = header.to_vec();
        bytes.resize(LAN_FRAME_HEADER_BYTES.saturating_add(declared), 0);
        let payload = bytes
            .get_mut(LAN_FRAME_HEADER_BYTES..)
            .ok_or_else(|| validation("lan_frame_incomplete", "frame payload buffer is short"))?;
        self.read_exact_or_incomplete(payload)?;
        decode_frame(&bytes)
    }

    /// Fills `buffer` exactly, mapping a short stream to `lan_frame_incomplete` rather than to a
    /// generic I/O error, so a peer that closes mid-frame is diagnosable.
    fn read_exact_or_incomplete(&mut self, buffer: &mut [u8]) -> Result<(), LomoError> {
        match self.stream.read_exact(buffer) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => Err(validation(
                "lan_frame_incomplete",
                "peer closed the stream in the middle of a frame",
            )),
            Err(error) => Err(io_error("lan_frame_read_failed", &error)),
        }
    }
}

/// Binds a LAN listener on `address` with the supplied deadlines.
///
/// # Errors
///
/// Network when the address cannot be bound.
pub fn bind_listener(address: impl ToSocketAddrs) -> Result<TcpListener, LomoError> {
    TcpListener::bind(address).map_err(|error| io_error("lan_listener_bind_failed", &error))
}

/// Accepts one peer connection and applies the deadlines.
///
/// # Errors
///
/// Network when accept fails or the deadlines cannot be applied.
pub fn accept_peer(
    listener: &TcpListener,
    deadlines: LanDeadlines,
) -> Result<(FrameStream<TcpStream>, SocketAddr), LomoError> {
    let (stream, address) = listener
        .accept()
        .map_err(|error| io_error("lan_listener_accept_failed", &error))?;
    apply_deadlines(&stream, deadlines)?;
    Ok((FrameStream::new(stream), address))
}

/// Waits for at most `timeout` for one connection on a non-blocking listener.
pub fn poll_peer(
    listener: &TcpListener,
    timeout: Duration,
    deadlines: LanDeadlines,
) -> Result<Option<(FrameStream<TcpStream>, SocketAddr)>, LomoError> {
    let started = Instant::now();
    loop {
        match listener.accept() {
            Ok((stream, address)) => {
                apply_deadlines(&stream, deadlines)?;
                return Ok(Some((FrameStream::new(stream), address)));
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                if started.elapsed() >= timeout {
                    return Ok(None);
                }
                thread::sleep(Duration::from_millis(5));
            }
            Err(error) => return Err(io_error("lan_listener_accept_failed", &error)),
        }
    }
}

/// Connects to a peer with a connect deadline and applies the stream deadlines.
///
/// # Errors
///
/// Network when the connection or deadline application fails.
pub fn connect_peer(
    address: SocketAddr,
    connect_timeout: Duration,
    deadlines: LanDeadlines,
) -> Result<FrameStream<TcpStream>, LomoError> {
    let stream = TcpStream::connect_timeout(&address, connect_timeout)
        .map_err(|error| io_error("lan_connect_failed", &error))?;
    apply_deadlines(&stream, deadlines)?;
    Ok(FrameStream::new(stream))
}

fn apply_deadlines(stream: &TcpStream, deadlines: LanDeadlines) -> Result<(), LomoError> {
    stream
        .set_read_timeout(Some(deadlines.read()))
        .map_err(|error| io_error("lan_deadline_apply_failed", &error))?;
    stream
        .set_write_timeout(Some(deadlines.write()))
        .map_err(|error| io_error("lan_deadline_apply_failed", &error))?;
    stream
        .set_nodelay(true)
        .map_err(|error| io_error("lan_nodelay_failed", &error))
}

/// Maps an I/O error to a typed LAN network error, distinguishing a fired deadline.
fn io_error(code: &str, error: &std::io::Error) -> LomoError {
    let timed_out = matches!(
        error.kind(),
        std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
    );
    if timed_out {
        return network(
            "lan_deadline_exceeded",
            "LAN socket deadline fired before the peer completed the operation",
            RetryDisposition::Transient,
        );
    }
    network(
        code,
        "LAN socket operation failed",
        RetryDisposition::Transient,
    )
}
