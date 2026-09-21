//! Rust-owned LAN accept pump.
//!
//! Accept waits on a cloned listener *outside* the LAN mutex. Frame handling briefly reacquires
//! the mutex. Inbox waiters block on a generation condvar instead of polling JNI.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use lomo_lan::LanServiceManager;

use crate::EngineError;

pub struct LanInboxPump {
    lan: Arc<Mutex<LanServiceManager>>,
    stop: Arc<AtomicBool>,
    generation: Arc<AtomicU64>,
    signal: Arc<(Mutex<()>, Condvar)>,
    last_error: Arc<Mutex<Option<EngineError>>>,
    thread: Mutex<Option<JoinHandle<()>>>,
}

impl LanInboxPump {
    pub fn new(lan: Arc<Mutex<LanServiceManager>>) -> Self {
        Self {
            lan,
            stop: Arc::new(AtomicBool::new(true)),
            generation: Arc::new(AtomicU64::new(0)),
            signal: Arc::new((Mutex::new(()), Condvar::new())),
            last_error: Arc::new(Mutex::new(None)),
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
        self.stop.store(false, Ordering::SeqCst);
        let stop = Arc::clone(&self.stop);
        let lan = Arc::clone(&self.lan);
        let generation = Arc::clone(&self.generation);
        let signal = Arc::clone(&self.signal);
        let last_error = Arc::clone(&self.last_error);
        let handle = thread::Builder::new()
            .name("lomo-lan-listener".to_owned())
            .spawn(move || listener_loop(&stop, &lan, &generation, &signal, &last_error))
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
            // The pump thread already stopped; a panic is not recovered here.
        }
    }

    pub fn bump(&self) {
        self.generation.fetch_add(1, Ordering::SeqCst);
        if let Ok(guard) = self.signal.0.lock() {
            self.signal.1.notify_all();
            drop(guard);
        }
    }

    pub fn await_generation(&self, last_seen: u64, timeout: Duration) -> Result<u64, EngineError> {
        if let Some(error) = take_error(&self.last_error)? {
            return Err(error);
        }
        let started = Instant::now();
        let (lock, cvar) = &*self.signal;
        let mut guard = lock.lock().map_err(|_poisoned| pump_lock_poisoned())?;
        loop {
            let current = self.generation.load(Ordering::SeqCst);
            if current > last_seen {
                drop(guard);
                return Ok(current);
            }
            if let Some(error) = take_error(&self.last_error)? {
                drop(guard);
                return Err(error);
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
}

impl Drop for LanInboxPump {
    fn drop(&mut self) {
        self.stop();
    }
}

fn listener_loop(
    stop: &Arc<AtomicBool>,
    lan: &Arc<Mutex<LanServiceManager>>,
    generation: &Arc<AtomicU64>,
    signal: &Arc<(Mutex<()>, Condvar)>,
    last_error: &Arc<Mutex<Option<EngineError>>>,
) {
    while !stop.load(Ordering::SeqCst) {
        let cloned = match lan.lock() {
            Ok(manager) => manager.clone_listener(),
            Err(_poisoned) => {
                store_error(last_error, pump_lock_poisoned());
                notify(generation, signal);
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
                store_error(last_error, EngineError::from(error));
                notify(generation, signal);
                thread::sleep(Duration::from_millis(50));
                continue;
            }
        };
        match LanServiceManager::accept_one(&listener) {
            Ok(Some((stream, peer_address))) => {
                match lan.lock() {
                    Ok(mut manager) => {
                        if let Err(error) =
                            manager.handle_inbound(stream, peer_address, unix_now_ms())
                        {
                            store_error(last_error, EngineError::from(error));
                        }
                    }
                    Err(_poisoned) => store_error(last_error, pump_lock_poisoned()),
                }
                notify(generation, signal);
            }
            Ok(None) => {}
            Err(error) => {
                store_error(last_error, EngineError::from(error));
                notify(generation, signal);
            }
        }
    }
}

fn notify(generation: &Arc<AtomicU64>, signal: &Arc<(Mutex<()>, Condvar)>) {
    generation.fetch_add(1, Ordering::SeqCst);
    if let Ok(guard) = signal.0.lock() {
        signal.1.notify_all();
        drop(guard);
    }
}

fn take_error(slot: &Mutex<Option<EngineError>>) -> Result<Option<EngineError>, EngineError> {
    slot.lock()
        .map(|mut guard| guard.take())
        .map_err(|_poisoned| pump_lock_poisoned())
}

fn store_error(slot: &Arc<Mutex<Option<EngineError>>>, error: EngineError) {
    if let Ok(mut guard) = slot.lock() {
        *guard = Some(error);
    }
}

fn unix_now_ms() -> i64 {
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
