//! Bounded outbound channel pool: one reusable framed connection per session.
//!
//! The pool carries no protocol state — it owns sockets, a receipt-bounded in-flight window and
//! connection lifecycle only. Planning (seal/coordinate) and receipt application stay in
//! [`crate::runtime::LanServiceManager`]; callers split those under their own lock and run the
//! network wait here without holding it.
//!
//! Lock domain: each session channel has its own mutex, so a stalled read on one session can
//! never block a send on another. The channel map itself is only touched for slot lookup and
//! bounded LRU eviction.

use std::collections::{BTreeMap, VecDeque};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use lomo_core::LomoError;

use crate::error::{authentication, internal, network, resource_limit, validation};
use crate::frame::{FrameKind, LanFrame};
use crate::limits::{MAX_INFLIGHT_CHUNKS, MAX_OUTBOUND_CHANNELS};
use crate::runtime::{ChunkSendPlan, decode_chunk_response};
use crate::session::LanSessionId;
use crate::transport::{FrameStream, LanDeadlines, connect_peer};

/// Socket deadline applied to pooled outbound channels (same envelope as control connects).
const CHANNEL_DEADLINE: Duration = Duration::from_secs(5);

/// One live channel slot: the wire state is per-session locked; the tick is lock-free so the
/// map can evict least-recently-used slots without waiting on a stalled send.
struct ChannelSlot {
    state: Mutex<ChannelState>,
    last_tick: AtomicU64,
}

struct ChannelState {
    /// `None` means the socket is closed or was never opened; it is (re)connected lazily.
    stream: Option<FrameStream<std::net::TcpStream>>,
    /// Chunks written but not yet acknowledged, oldest first. Bounded by
    /// [`MAX_INFLIGHT_CHUNKS`]; an acknowledgement may retire any entry (out-of-order tolerant).
    pending: VecDeque<ChunkSendPlan>,
}

/// Reusable, session-keyed outbound connection pool.
///
/// Connections grow with sessions, never with chunks. A channel that fails on the wire is
/// dropped and rebuilt once per send; a failed channel never retries indefinitely.
///
/// Internally synchronized: callers share one `&LanConnectionPool` across send workers.
#[derive(Default)]
pub struct LanConnectionPool {
    channels: Mutex<BTreeMap<LanSessionId, Arc<ChannelSlot>>>,
    /// Monotonic operation counter for LRU eviction.
    tick: AtomicU64,
    /// Total connections opened over the pool's lifetime (observability for the reuse contract).
    connects_made: AtomicU64,
}

impl LanConnectionPool {
    /// Number of currently open outbound channels.
    #[must_use]
    pub fn open_channels(&self) -> usize {
        self.channels.lock().map_or(0, |channels| {
            channels
                .values()
                .filter(|slot| slot.state.lock().is_ok_and(|state| state.stream.is_some()))
                .count()
        })
    }

    /// Total connections opened since the pool was created.
    #[must_use]
    pub fn connects_made(&self) -> u64 {
        self.connects_made.load(Ordering::SeqCst)
    }

    /// Drops every open channel (service stop).
    pub fn close_all(&self) {
        match self.channels.lock() {
            Ok(mut channels) => channels.clear(),
            Err(poisoned) => poisoned.into_inner().clear(),
        }
    }

    /// Drops the channel bound to one session (session end or terminal refusal).
    pub fn evict_session(&self, session_id: &LanSessionId) {
        match self.channels.lock() {
            Ok(mut channels) => {
                channels.remove(session_id);
            }
            Err(poisoned) => {
                poisoned.into_inner().remove(session_id);
            }
        }
    }

    /// Sends one planned chunk, appending every drained acknowledgement into `drained`,
    /// oldest first — always including this send's own acknowledgement.
    ///
    /// Equivalent to `send_chunks` with a single plan.
    ///
    /// # Errors
    ///
    /// Network on connect/write/read or deadline; authentication when the wire answers a
    /// receipt this channel never sent. Responses already drained stay in `drained` — a later
    /// socket error cannot retract an observed receipt.
    pub fn send_chunk(
        &self,
        plan: &ChunkSendPlan,
        drained: &mut Vec<(ChunkSendPlan, LanFrame)>,
    ) -> Result<(), LomoError> {
        self.send_chunks(std::slice::from_ref(plan), drained)
    }

    /// Sends a batch of planned chunks through their session channels under the bounded
    /// sliding window, appending every drained acknowledgement in wire order to `drained`.
    ///
    /// Writes pipeline up to [`MAX_INFLIGHT_CHUNKS`] unacknowledged chunks per session; a full
    /// window drains the oldest receipts first (backpressure). A dead channel is dropped and
    /// rebuilt exactly once per call; a second failure surfaces without further retry.
    /// Responses are routed by their cleartext receipt prefix, so out-of-order and duplicate
    /// answers are handled on their own; authentication is the caller's job in
    /// `apply_chunk_receipt`. `drained` is an out-parameter by contract: whatever the wire
    /// already answered survives a later read/write error.
    ///
    /// # Errors
    ///
    /// Validation for an empty plan batch or a malformed response frame; network on
    /// connect/write/read or deadline; authentication when the wire answers a receipt this
    /// channel never sent.
    pub fn send_chunks(
        &self,
        plans: &[ChunkSendPlan],
        drained: &mut Vec<(ChunkSendPlan, LanFrame)>,
    ) -> Result<(), LomoError> {
        if plans.is_empty() {
            return Err(validation(
                "lan_send_batch_empty",
                "a chunk send batch must carry at least one plan",
            ));
        }
        // Write phase: pipeline up to the window; the window drains oldest-first when full.
        for plan in plans {
            let slot = self.slot_for(plan.session_id())?;
            let mut state = slot.state.lock().map_err(|_poisoned| slot_poisoned())?;
            self.write_plan(&mut state, plan, drained)?;
            drop(state);
        }
        // Drain phase: read until every pushed receipt retired, whichever order they arrive.
        for plan in plans {
            let slot = self.slot_for(plan.session_id())?;
            let mut state = slot.state.lock().map_err(|_poisoned| slot_poisoned())?;
            while state
                .pending
                .iter()
                .any(|pending| pending.receipt() == plan.receipt())
            {
                match Self::read_ack(&mut state) {
                    Ok(pair) => drained.push(pair),
                    Err(error) => {
                        // A dead channel loses every outstanding acknowledgement; unconfirmed
                        // durable state is what retransmits them, not a zombie window. Receipts
                        // already pushed to `drained` remain the caller's fact.
                        state.stream = None;
                        state.pending.clear();
                        return Err(error);
                    }
                }
            }
            drop(state);
        }
        Ok(())
    }

    /// Writes one plan on its channel, draining the window first when full.
    ///
    /// A dead socket is dropped and the channel rebuilt once; any second wire failure
    /// surfaces. Drained acknowledgements stay in `drained` even when a later write fails.
    fn write_plan(
        &self,
        state: &mut ChannelState,
        plan: &ChunkSendPlan,
        drained: &mut Vec<(ChunkSendPlan, LanFrame)>,
    ) -> Result<(), LomoError> {
        let mut rebuilt = false;
        loop {
            if let Err(error) = self.transmit_once(state, plan, drained) {
                // A dead channel loses every outstanding acknowledgement; unconfirmed durable
                // state is what retransmits them, not a zombie window.
                state.stream = None;
                state.pending.clear();
                if rebuilt {
                    return Err(error);
                }
                rebuilt = true;
                continue;
            }
            return Ok(());
        }
    }

    /// One write attempt: ensure the socket, drain a full window, then write the frame.
    fn transmit_once(
        &self,
        state: &mut ChannelState,
        plan: &ChunkSendPlan,
        drained: &mut Vec<(ChunkSendPlan, LanFrame)>,
    ) -> Result<(), LomoError> {
        if state.stream.is_none() {
            state.stream = Some(connect_peer(
                plan.address(),
                CHANNEL_DEADLINE,
                channel_deadlines()?,
            )?);
            self.connects_made.fetch_add(1, Ordering::SeqCst);
        }
        while state.pending.len() >= MAX_INFLIGHT_CHUNKS {
            let pair = Self::read_ack(state)?;
            drained.push(pair);
        }
        let stream = state.stream.as_mut().ok_or_else(conn_missing)?;
        stream.write_frame(plan.frame())?;
        state.pending.push_back(plan.clone());
        Ok(())
    }

    /// Reads one acknowledgement and retires the matching window entry.
    fn read_ack(state: &mut ChannelState) -> Result<(ChunkSendPlan, LanFrame), LomoError> {
        let stream = state.stream.as_mut().ok_or_else(conn_missing)?;
        let response = stream.read_frame()?;
        let index = match_pending(state, &response)?;
        let confirmed = state.pending.remove(index).ok_or_else(|| {
            validation(
                "lan_pending_window_invalid",
                "acknowledged receipt is missing from the send window",
            )
        })?;
        Ok((confirmed, response))
    }

    /// Returns the slot for a session, creating it and evicting the least-recently-used slot
    /// when the pool is at capacity.
    fn slot_for(&self, session_id: &LanSessionId) -> Result<Arc<ChannelSlot>, LomoError> {
        let tick = self.tick.fetch_add(1, Ordering::SeqCst);
        let mut channels = match self.channels.lock() {
            Ok(channels) => channels,
            Err(poisoned) => poisoned.into_inner(),
        };
        if let Some(slot) = channels.get(session_id) {
            slot.last_tick.store(tick, Ordering::SeqCst);
            return Ok(Arc::clone(slot));
        }
        if channels.len() >= MAX_OUTBOUND_CHANNELS {
            let lru = channels
                .iter()
                .min_by_key(|(_id, slot)| slot.last_tick.load(Ordering::SeqCst))
                .map(|(id, _slot)| id.clone())
                .ok_or_else(|| {
                    resource_limit(
                        "lan_outbound_channel_capacity",
                        "outbound channel pool is full and has no evictable channel",
                    )
                })?;
            channels.remove(&lru);
        }
        let slot = Arc::new(ChannelSlot {
            state: Mutex::new(ChannelState {
                stream: None,
                pending: VecDeque::new(),
            }),
            last_tick: AtomicU64::new(tick),
        });
        channels.insert(session_id.clone(), Arc::clone(&slot));
        drop(channels);
        Ok(slot)
    }
}

/// Routes a response frame to the channel's send window. Every response carries its
/// `receipt ∥ nonce ∥ sealed` shape: the cleartext receipt prefix selects the pending send it
/// retires, and `apply_chunk_receipt` decides later whether the sealed body authenticates. A
/// response that names a receipt this channel never sent — including a forged cleartext
/// acknowledgement — kills the send instead of retiring anything.
fn match_pending(state: &ChannelState, response: &LanFrame) -> Result<usize, LomoError> {
    match response.kind() {
        FrameKind::ChunkAck | FrameKind::Error => {
            let (receipt, _nonce, _sealed) = decode_chunk_response(response.payload())?;
            state
                .pending
                .iter()
                .position(|plan| plan.receipt() == &receipt)
                .ok_or_else(|| {
                    authentication(
                        "lan_error_frame_unsolicited",
                        "receiver answered a receipt this channel never sent",
                    )
                })
        }
        FrameKind::PairHello
        | FrameKind::PairAccept
        | FrameKind::PairConfirm
        | FrameKind::SessionHello
        | FrameKind::SessionAccept
        | FrameKind::BatchPrepare
        | FrameKind::BatchApprove
        | FrameKind::BatchReject
        | FrameKind::Chunk
        | FrameKind::BatchComplete
        | FrameKind::SessionConfirm => Err(authentication(
            "lan_channel_frame_foreign",
            "data channel received a frame outside the chunk acknowledgement contract",
        )),
    }
}

fn conn_missing() -> LomoError {
    network(
        "lan_channel_missing",
        "outbound channel vanished mid-send",
        lomo_core::RetryDisposition::Transient,
    )
}

fn slot_poisoned() -> LomoError {
    internal(
        "lan_channel_lock_poisoned",
        "channel state lock was poisoned by a prior panic",
    )
}

fn channel_deadlines() -> Result<LanDeadlines, LomoError> {
    LanDeadlines::new(CHANNEL_DEADLINE, CHANNEL_DEADLINE)
}
