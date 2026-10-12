//! Bounded leases for read-only projection connections; no writer or platform-I/O capability.

use crate::{
    StoreReader,
    error::{storage, validation},
    open::IntegrityWitness,
};
use lomo_core::LomoError;
use std::{
    path::PathBuf,
    sync::{
        Condvar, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};

#[derive(Clone, Copy, Debug)]
pub struct ReaderPoolOptions {
    capacity: usize,
    wait: Duration,
}

impl Default for ReaderPoolOptions {
    fn default() -> Self {
        Self {
            capacity: 4,
            wait: Duration::from_secs(5),
        }
    }
}

impl ReaderPoolOptions {
    /// Constructs an explicit bounded checkout policy. Zero wait means immediate rejection.
    ///
    /// # Errors
    /// Rejects capacity outside 1..=16 and waits above five seconds.
    pub fn new(capacity: usize, wait: Duration) -> Result<Self, LomoError> {
        if !(1..=16).contains(&capacity) || wait > Duration::from_secs(5) {
            return Err(validation(
                "invalid_reader_pool_options",
                "reader capacity must be 1..=16 and wait at most five seconds",
            ));
        }
        Ok(Self { capacity, wait })
    }
}

#[derive(Clone, Copy, Eq, PartialEq)]
enum Phase {
    Open,
    Closed,
}

struct PoolState {
    idle: Vec<StoreReader>,
    total: usize,
    epoch: u64,
    phase: Phase,
    /// Database file token already verified for this pool — seeded by the owning `Store`'s
    /// open-time `quick_check`, or recorded from a reader's own scan. One database generation
    /// pays exactly one integrity check; `clear_idle` drops it with the generation.
    verified: Option<IntegrityWitness>,
}

pub struct StoreReaderPool {
    root: PathBuf,
    options: ReaderPoolOptions,
    state: Mutex<PoolState>,
    available: Condvar,
    /// Reader-side `PRAGMA quick_check` executions — the startup-duplication probe: a witnessed
    /// generation contributes zero.
    integrity_checks: AtomicU64,
}

pub struct StoreReaderLease<'a> {
    pool: &'a StoreReaderPool,
    reader: Option<StoreReader>,
    epoch: u64,
}

impl StoreReaderPool {
    #[must_use]
    pub const fn new(root: PathBuf, options: ReaderPoolOptions) -> Self {
        Self {
            root,
            options,
            state: Mutex::new(PoolState {
                idle: Vec::new(),
                total: 0,
                epoch: 0,
                phase: Phase::Open,
                verified: None,
            }),
            available: Condvar::new(),
            integrity_checks: AtomicU64::new(0),
        }
    }

    /// Seeds the verdict of an integrity check already performed on this exact database
    /// generation — typically the owning `Store`'s open-time `quick_check`. A token that does
    /// not match the live file at open time can never skip the reader-side scan.
    ///
    /// # Errors
    /// Poisoned/closed pools are surfaced.
    pub fn mark_verified(&self, witness: IntegrityWitness) -> Result<(), LomoError> {
        let mut state = self
            .state
            .lock()
            .map_err(|_error| pool_error("store_reader_pool_poisoned"))?;
        if state.phase == Phase::Closed {
            return Err(pool_error("store_reader_pool_closed"));
        }
        state.verified = Some(witness);
        drop(state);
        Ok(())
    }

    /// Reader-side `PRAGMA quick_check` executions since pool creation.
    #[must_use]
    pub fn integrity_checks(&self) -> u64 {
        self.integrity_checks.load(Ordering::SeqCst)
    }

    /// Leases one read connection without holding the pool mutex while querying or opening SQLite.
    ///
    /// # Errors
    /// Closed, poisoned, exhausted pools and projection-open failures return explicit errors.
    pub fn checkout(&self) -> Result<StoreReaderLease<'_>, LomoError> {
        let deadline = Instant::now() + self.options.wait;
        let mut state = self
            .state
            .lock()
            .map_err(|_error| pool_error("store_reader_pool_poisoned"))?;
        loop {
            if state.phase == Phase::Closed {
                return Err(pool_error("store_reader_pool_closed"));
            }
            if let Some(reader) = state.idle.pop() {
                return Ok(StoreReaderLease {
                    pool: self,
                    reader: Some(reader),
                    epoch: state.epoch,
                });
            }
            if state.total < self.options.capacity {
                state.total += 1;
                let epoch = state.epoch;
                let witnessed = state.verified;
                drop(state);
                return self.open_reserved(epoch, witnessed);
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(pool_error("store_reader_pool_exhausted"));
            }
            state = self
                .available
                .wait_timeout(state, remaining)
                .map_err(|_error| pool_error("store_reader_pool_poisoned"))?
                .0;
        }
    }

    fn open_reserved(
        &self,
        epoch: u64,
        witnessed: Option<IntegrityWitness>,
    ) -> Result<StoreReaderLease<'_>, LomoError> {
        match StoreReader::open_shared(self.root.clone(), witnessed) {
            Ok(opened) => {
                if opened.checked {
                    self.integrity_checks.fetch_add(1, Ordering::SeqCst);
                }
                let lease = StoreReaderLease {
                    pool: self,
                    reader: Some(opened.reader),
                    epoch,
                };
                let mut state = self
                    .state
                    .lock()
                    .map_err(|_error| pool_error("store_reader_pool_poisoned"))?;
                let valid = state.phase == Phase::Open && state.epoch == epoch;
                if valid && opened.checked {
                    // A reader that had to scan itself verified the live file;
                    // propagate the token so the rest of this generation skips.
                    if opened.token.is_some() {
                        state.verified = opened.token;
                    }
                }
                drop(state);
                if !valid {
                    return Err(pool_error("store_reader_pool_changed"));
                }
                Ok(lease)
            }
            Err(error) => {
                let mut state = self
                    .state
                    .lock()
                    .map_err(|_error| pool_error("store_reader_pool_poisoned"))?;
                state.total = state
                    .total
                    .checked_sub(1)
                    .ok_or_else(|| pool_error("store_reader_pool_inconsistent"))?;
                drop(state);
                self.available.notify_one();
                Err(error)
            }
        }
    }

    /// Retires idle connections after projection replacement. Outstanding leases cannot return
    /// a connection from the previous database generation to the new pool.
    ///
    /// # Errors
    /// Poisoned/closed pools and epoch exhaustion are surfaced.
    pub fn clear_idle(&self) -> Result<(), LomoError> {
        let mut state = self
            .state
            .lock()
            .map_err(|_error| pool_error("store_reader_pool_poisoned"))?;
        if state.phase == Phase::Closed {
            return Err(pool_error("store_reader_pool_closed"));
        }
        state.epoch = state
            .epoch
            .checked_add(1)
            .ok_or_else(|| pool_error("store_reader_pool_epoch_exhausted"))?;
        state.total = state
            .total
            .checked_sub(state.idle.len())
            .ok_or_else(|| pool_error("store_reader_pool_inconsistent"))?;
        state.idle.clear();
        // The verdict belonged to the retiring generation; the replacement is verified
        // again by its opener.
        state.verified = None;
        drop(state);
        self.available.notify_all();
        Ok(())
    }

    /// Rejects new and waiting readers; active snapshots may finish and then release their handles.
    ///
    /// # Errors
    /// Returns the pool poison/invariant error instead of hiding resource loss.
    pub fn close(&self) -> Result<(), LomoError> {
        let mut state = self
            .state
            .lock()
            .map_err(|_error| pool_error("store_reader_pool_poisoned"))?;
        state.phase = Phase::Closed;
        state.total = state
            .total
            .checked_sub(state.idle.len())
            .ok_or_else(|| pool_error("store_reader_pool_inconsistent"))?;
        state.idle.clear();
        drop(state);
        self.available.notify_all();
        Ok(())
    }
}

impl StoreReaderLease<'_> {
    /// Returns the live read capability for this lexical lease.
    ///
    /// # Errors
    /// A consumed lease cannot issue another query.
    pub fn reader(&self) -> Result<&StoreReader, LomoError> {
        self.reader
            .as_ref()
            .ok_or_else(|| pool_error("store_reader_lease_consumed"))
    }
}

impl Drop for StoreReaderLease<'_> {
    fn drop(&mut self) {
        let Some(reader) = self.reader.take() else {
            return;
        };
        // Returning a resource must also work during unwinding. The mutex remains poisoned, so
        // later checkouts surface the failure; this only restores connection accounting.
        let mut state = self
            .pool
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.phase == Phase::Open && state.epoch == self.epoch {
            state.idle.push(reader);
        } else {
            state.total -= 1;
        }
        drop(state);
        self.pool.available.notify_one();
    }
}

fn pool_error(code: &str) -> LomoError {
    storage(code, "bounded projection reader lease is unavailable")
}
