//! Rust-owned LAN accept pump.
//!
//! Accept waits on a cloned listener *outside* the LAN mutex. Each accepted connection gets its
//! own bounded worker: frame reads and reply writes run off-lock, only validation/durable
//! admission reacquires the mutex. Inbox waiters block on a generation condvar instead of
//! polling JNI.
//!
//! Failure model: a service-level fault (listener/accept/storage/corruption/internal) is recorded
//! as a sticky epoch-tagged pump failure that every observer sees until the service restarts.
//! A connection-scoped rejection (malformed frame, authentication, refused input) never kills the
//! pump; it is counted with a diagnostic so the platform can observe hostile input.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use lomo_core::ErrorCategory;
use lomo_lan::{FrameStream, LanServiceManager};

use crate::{EngineError, EngineFailure};

/// One sticky pump failure. A service restart clears it, so a failure stored by a previous
/// service epoch can never be confused with a new one.
#[derive(Clone, Debug)]
struct PumpFailure {
    failure: EngineFailure,
}

pub struct LanInboxPump {
    lan: Arc<Mutex<LanServiceManager>>,
    stop: Arc<AtomicBool>,
    generation: Arc<AtomicU64>,
    signal: Arc<(Mutex<()>, Condvar)>,
    failure: Arc<Mutex<Option<PumpFailure>>>,
    rejected_connections: Arc<AtomicU64>,
    last_rejection: Arc<Mutex<Option<String>>>,
    thread: Mutex<Option<JoinHandle<()>>>,
}

impl LanInboxPump {
    pub fn new(lan: Arc<Mutex<LanServiceManager>>) -> Self {
        Self {
            lan,
            stop: Arc::new(AtomicBool::new(true)),
            generation: Arc::new(AtomicU64::new(0)),
            signal: Arc::new((Mutex::new(()), Condvar::new())),
            failure: Arc::new(Mutex::new(None)),
            rejected_connections: Arc::new(AtomicU64::new(0)),
            last_rejection: Arc::new(Mutex::new(None)),
            thread: Mutex::new(None),
        }
    }

    pub fn start(&self) -> Result<(), EngineError> {
        let mut thread = self
            .thread
            .lock()
            .map_err(|_poisoned| pump_lock_poisoned())?;
        if thread.as_ref().is_some_and(|handle| !handle.is_finished()) {
            drop(thread);
            return Ok(());
        }
        // A restart supersedes the previous epoch's failure before the worker starts.
        self.clear_failure();
        self.stop.store(false, Ordering::SeqCst);
        let context = Arc::new(ListenerContext {
            stop: Arc::clone(&self.stop),
            lan: Arc::clone(&self.lan),
            generation: Arc::clone(&self.generation),
            signal: Arc::clone(&self.signal),
            failure: Arc::clone(&self.failure),
            rejected_connections: Arc::clone(&self.rejected_connections),
            last_rejection: Arc::clone(&self.last_rejection),
            workers: Arc::new(AtomicUsize::new(0)),
        });
        let handle = thread::Builder::new()
            .name("lomo-lan-listener".to_owned())
            .spawn(move || listener_loop(&context))
            .map_err(|_error| {
                crate::lan_pump_boundary_error(
                    "lan_listener_pump_spawn_failed",
                    "LAN listener pump thread could not start",
                )
            })?;
        *thread = Some(handle);
        drop(thread);
        self.bump();
        Ok(())
    }

    pub fn stop(&self) {
        self.stop.store(true, Ordering::SeqCst);
        self.bump();
        let handle = match self.thread.lock() {
            Ok(mut thread) => thread.take(),
            Err(_poisoned) => None,
        };
        if let Some(handle) = handle
            && handle.join().is_err()
        {
            // A panicked worker must still leave a visible failure for observers that arrive
            // before the next start clears it.
            self.record_failure(crate::lan_pump_boundary_error(
                "lan_listener_pump_panicked",
                "LAN listener pump thread exited with a panic",
            ));
        }
    }

    pub fn bump(&self) {
        self.generation.fetch_add(1, Ordering::SeqCst);
        if let Ok(guard) = self.signal.0.lock() {
            self.signal.1.notify_all();
            drop(guard);
        }
    }

    /// Connection-scoped rejection telemetry for the current pump lifetime.
    pub fn rejection_stats(&self) -> (u64, Option<String>) {
        let count = self.rejected_connections.load(Ordering::SeqCst);
        let last = match self.last_rejection.lock() {
            Ok(guard) => guard.clone(),
            Err(_poisoned) => None,
        };
        (count, last)
    }

    pub fn await_generation(&self, last_seen: u64, timeout: Duration) -> Result<u64, EngineError> {
        if let Some(error) = self.current_failure()? {
            return Err(error);
        }
        let started = Instant::now();
        let (lock, cvar) = &*self.signal;
        let mut guard = lock.lock().map_err(|_poisoned| pump_lock_poisoned())?;
        loop {
            // A sticky failure wins over a generation bump: a fault recorded with its notify must
            // never be masked by the same wake-up.
            if let Some(error) = self.current_failure()? {
                drop(guard);
                return Err(error);
            }
            let current = self.generation.load(Ordering::SeqCst);
            if current > last_seen {
                drop(guard);
                return Ok(current);
            }
            if self.worker_exited() {
                let failure = crate::lan_pump_boundary_error(
                    "lan_listener_pump_exited",
                    "LAN listener pump thread exited without recording a reason",
                );
                self.record_failure(failure);
                drop(guard);
                return Err(crate::lan_pump_boundary_error(
                    "lan_listener_pump_exited",
                    "LAN listener pump thread exited without recording a reason",
                ));
            }
            let remaining = timeout.saturating_sub(started.elapsed());
            if remaining.is_zero() {
                drop(guard);
                return Ok(current);
            }
            let (next_guard, timed_out) = cvar
                .wait_timeout(guard, remaining)
                .map_err(|_poisoned| pump_lock_poisoned())?;
            guard = next_guard;
            if timed_out.timed_out() {
                let current = self.generation.load(Ordering::SeqCst);
                drop(guard);
                return Ok(current);
            }
        }
    }

    /// The sticky failure for this epoch: every observer sees it until a restart clears it.
    fn current_failure(&self) -> Result<Option<EngineError>, EngineError> {
        self.failure
            .lock()
            .map(|guard| {
                guard.as_ref().map(|failure| EngineError::Failure {
                    failure: failure.failure.clone(),
                })
            })
            .map_err(|_poisoned| pump_lock_poisoned())
    }

    fn clear_failure(&self) {
        if let Ok(mut guard) = self.failure.lock() {
            *guard = None;
        }
    }

    fn record_failure(&self, error: EngineError) {
        if let Ok(mut guard) = self.failure.lock() {
            *guard = Some(PumpFailure {
                failure: engine_failure(error),
            });
        }
    }

    /// The worker finished without the stop flag being raised: an unrecorded exit is still a
    /// pump failure observers must see.
    fn worker_exited(&self) -> bool {
        if self.stop.load(Ordering::SeqCst) {
            return false;
        }
        match self.thread.lock() {
            Ok(thread) => thread.as_ref().is_some_and(JoinHandle::is_finished),
            Err(_poisoned) => false,
        }
    }
}

impl Drop for LanInboxPump {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Shared accept-loop context: every field is a pump-owned handle cloned per connection worker.
struct ListenerContext {
    stop: Arc<AtomicBool>,
    lan: Arc<Mutex<LanServiceManager>>,
    generation: Arc<AtomicU64>,
    signal: Arc<(Mutex<()>, Condvar)>,
    failure: Arc<Mutex<Option<PumpFailure>>>,
    rejected_connections: Arc<AtomicU64>,
    last_rejection: Arc<Mutex<Option<String>>>,
    /// Live connection workers; bounded by `MAX_INBOUND_CONNECTIONS`.
    workers: Arc<AtomicUsize>,
}

/// Decrements the live-worker count when a connection worker exits for any reason.
struct WorkerSlot {
    workers: Arc<AtomicUsize>,
}

impl WorkerSlot {
    fn new(context: &Arc<ListenerContext>) -> Self {
        Self {
            workers: Arc::clone(&context.workers),
        }
    }
}

impl Drop for WorkerSlot {
    fn drop(&mut self) {
        self.workers.fetch_sub(1, Ordering::SeqCst);
    }
}

fn listener_loop(context: &Arc<ListenerContext>) {
    while !context.stop.load(Ordering::SeqCst) {
        // A sticky service fault stops accepting: workers would only hit the same fault.
        if let Ok(guard) = context.failure.lock()
            && guard.is_some()
        {
            break;
        }
        let cloned = match context.lan.lock() {
            Ok(manager) => manager.clone_listener(),
            Err(_poisoned) => {
                store_failure(&context.failure, pump_lock_poisoned());
                notify(&context.generation, &context.signal);
                break;
            }
        };
        let listener = match cloned {
            Ok(Some(listener)) => listener,
            Ok(None) => {
                thread::sleep(Duration::from_millis(50));
                continue;
            }
            Err(error) => {
                store_failure(&context.failure, EngineError::from(error));
                notify(&context.generation, &context.signal);
                break;
            }
        };
        match LanServiceManager::accept_one(&listener) {
            Ok(Some((stream, peer_address))) => {
                // Accept alone carries no inbox content: the worker notifies once the
                // connection produced a handled frame, a rejection or a service fault.
                spawn_connection(context, stream, peer_address);
            }
            Ok(None) => {}
            Err(error) => {
                store_failure(&context.failure, EngineError::from(error));
                notify(&context.generation, &context.signal);
                break;
            }
        }
    }
}

/// Admits one accepted connection under the inbound bound, then hands it to its own worker.
fn spawn_connection(
    context: &Arc<ListenerContext>,
    stream: FrameStream<std::net::TcpStream>,
    peer_address: SocketAddr,
) {
    let live = context.workers.fetch_add(1, Ordering::SeqCst);
    if live >= lomo_lan::MAX_INBOUND_CONNECTIONS {
        context.workers.fetch_sub(1, Ordering::SeqCst);
        record_rejection(
            context,
            &lomo_lan::lan_resource_limit(
                "lan_inbound_capacity",
                "inbound connection workers are at the bounded capacity",
            ),
        );
        return;
    }
    let worker = Arc::clone(context);
    let spawned = thread::Builder::new()
        .name("lomo-lan-conn".to_owned())
        .spawn(move || connection_loop(&worker, stream, peer_address));
    if spawned.is_err() {
        context.workers.fetch_sub(1, Ordering::SeqCst);
        store_failure(
            &context.failure,
            crate::lan_pump_boundary_error(
                "lan_connection_worker_spawn_failed",
                "LAN connection worker thread could not start",
            ),
        );
        notify(&context.generation, &context.signal);
    }
}

/// One connection worker: read off-lock, validate/apply under the manager lock, write the reply
/// off-lock. The loop ends on close, stop, a protocol rejection or a service fault.
fn connection_loop(
    context: &Arc<ListenerContext>,
    mut stream: FrameStream<std::net::TcpStream>,
    peer_address: SocketAddr,
) {
    let _slot = WorkerSlot::new(context);
    let mut handled = 0_usize;
    loop {
        if context.stop.load(Ordering::SeqCst) {
            return;
        }
        let frame = match stream.read_frame() {
            Ok(frame) => frame,
            Err(error) => {
                // Peer close and idle-deadline recycles are connection lifecycle, not
                // hostility; every other wire error is a connection-scoped rejection.
                if !is_connection_close(&error, handled) {
                    record_rejection(context, &error);
                }
                return;
            }
        };
        let reply = match context.lan.lock() {
            Ok(mut manager) => manager.handle_inbound_frame(peer_address, &frame, unix_now_ms()),
            Err(_poisoned) => {
                store_failure(&context.failure, pump_lock_poisoned());
                notify(&context.generation, &context.signal);
                return;
            }
        };
        match reply {
            Ok(Some(reply)) => {
                if let Err(error) = stream.write_frame(&reply) {
                    record_rejection(context, &error);
                    return;
                }
            }
            Ok(None) => {}
            Err(error) => {
                if error_is_service_fault(&error) {
                    store_failure(&context.failure, EngineError::from(error));
                    notify(&context.generation, &context.signal);
                } else {
                    record_rejection(context, &error);
                }
                return;
            }
        }
        handled += 1;
        notify(&context.generation, &context.signal);
    }
}

/// A peer that closes after real work and an idle-channel deadline are ordinary connection
/// lifecycle. A connection that never produced one valid frame — port scans, truncated
/// garbage, zero-byte probes — is a connection-scoped rejection the platform can observe.
fn is_connection_close(error: &lomo_core::LomoError, handled: usize) -> bool {
    match error.code() {
        "lan_deadline_exceeded" => true,
        "lan_frame_incomplete" => handled > 0,
        _ => false,
    }
}

fn notify(generation: &Arc<AtomicU64>, signal: &Arc<(Mutex<()>, Condvar)>) {
    generation.fetch_add(1, Ordering::SeqCst);
    if let Ok(guard) = signal.0.lock() {
        signal.1.notify_all();
        drop(guard);
    }
}

fn store_failure(slot: &Arc<Mutex<Option<PumpFailure>>>, error: EngineError) {
    if let Ok(mut guard) = slot.lock() {
        *guard = Some(PumpFailure {
            failure: engine_failure(error),
        });
    }
}

/// A rejection is inbox-visible telemetry: waiters must wake only after the count and
/// diagnostic are stored, never between accept and classification.
fn record_rejection(context: &Arc<ListenerContext>, error: &lomo_core::LomoError) {
    context.rejected_connections.fetch_add(1, Ordering::SeqCst);
    if let Ok(mut guard) = context.last_rejection.lock() {
        *guard = Some(format!("{}: {}", error.code(), error.diagnostic()));
    }
    notify(&context.generation, &context.signal);
}

/// A connection-scoped rejection is any inbound error that does not damage the service itself.
/// Storage, corruption and internal faults are service-level: they become the sticky pump
/// failure instead of a per-connection count.
const fn error_is_service_fault(error: &lomo_core::LomoError) -> bool {
    matches!(
        error.category(),
        ErrorCategory::Storage | ErrorCategory::Internal | ErrorCategory::Corruption
    )
}

fn engine_failure(error: EngineError) -> EngineFailure {
    match error {
        EngineError::Failure { failure } => failure,
    }
}

pub fn unix_now_ms() -> i64 {
    match SystemTime::now().duration_since(UNIX_EPOCH) {
        Ok(duration) => i64::try_from(duration.as_millis()).unwrap_or(i64::MAX),
        Err(_earlier_than_epoch) => 0,
    }
}

fn pump_lock_poisoned() -> EngineError {
    crate::lan_pump_boundary_error(
        "lan_listener_pump_lock_poisoned",
        "LAN listener pump lock was poisoned by a prior panic",
    )
}
