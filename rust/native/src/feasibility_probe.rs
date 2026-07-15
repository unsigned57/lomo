//! Temporary `UniFFI` feasibility surface (feature `feasibility-probe` only).

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};

/// Probe-level errors mapped across FFI without throwing platform exceptions.
#[derive(Debug, uniffi::Error)]
pub enum FeasibilityProbeError {
    Closed { reason: String },
    Cancelled { operation_id: String },
    Invalid { reason: String },
}

impl std::fmt::Display for FeasibilityProbeError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Closed { reason } => write!(formatter, "probe closed: {reason}"),
            Self::Cancelled { operation_id } => {
                write!(formatter, "operation cancelled: {operation_id}")
            }
            Self::Invalid { reason } => write!(formatter, "invalid probe request: {reason}"),
        }
    }
}

impl std::error::Error for FeasibilityProbeError {}

/// Bounded page for collection size enforcement across FFI.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct FeasibilityPage {
    pub items: Vec<String>,
    pub next_cursor: Option<String>,
}

/// Long-lived probe object used only by tooling/native-smoke.
#[derive(uniffi::Object)]
pub struct FeasibilityProbe {
    closed: AtomicBool,
    revision: AtomicU64,
    cancelled: Mutex<Vec<String>>,
    last_batch_id: Mutex<Option<String>>,
}

// UniFFI requires owned `String` parameters on exported methods.
#[allow(
    clippy::needless_pass_by_value,
    reason = "UniFFI exported methods require owned String parameters"
)]
#[uniffi::export]
impl FeasibilityProbe {
    /// Create a new open probe.
    #[uniffi::constructor]
    #[must_use]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            closed: AtomicBool::new(false),
            revision: AtomicU64::new(0),
            cancelled: Mutex::new(Vec::new()),
            last_batch_id: Mutex::new(None),
        })
    }

    /// Monotonic revision for listener recovery tests.
    ///
    /// # Errors
    ///
    /// Returns [`FeasibilityProbeError::Closed`] after [`Self::shutdown`].
    pub fn revision(&self) -> Result<u64, FeasibilityProbeError> {
        ensure_open(self)?;
        Ok(self.revision.load(Ordering::SeqCst))
    }

    /// Advance revision by one (mutation event).
    ///
    /// # Errors
    ///
    /// Returns [`FeasibilityProbeError::Closed`] after [`Self::shutdown`].
    pub fn bump_revision(&self) -> Result<u64, FeasibilityProbeError> {
        ensure_open(self)?;
        Ok(self.revision.fetch_add(1, Ordering::SeqCst) + 1)
    }

    /// Return a bounded page; `page_size` is clamped to 1..=32.
    ///
    /// # Errors
    ///
    /// Returns [`FeasibilityProbeError::Closed`] after [`Self::shutdown`].
    pub fn list_page(
        &self,
        cursor: Option<String>,
        page_size: u32,
    ) -> Result<FeasibilityPage, FeasibilityProbeError> {
        ensure_open(self)?;
        let size = page_size.clamp(1, 32) as usize;
        let start = cursor
            .as_deref()
            .map_or(0, |value| value.parse::<usize>().unwrap_or(0));
        let items = (start..start + size)
            .map(|index| format!("item-{index}"))
            .collect::<Vec<_>>();
        let next_cursor = Some((start + size).to_string());
        Ok(FeasibilityPage { items, next_cursor })
    }

    /// Mark an operation cancelled; later completion must stay cancelled.
    ///
    /// # Errors
    ///
    /// Returns closed/invalid errors when the probe is unusable or the id is empty.
    pub fn cancel(&self, operation_id: String) -> Result<(), FeasibilityProbeError> {
        ensure_open(self)?;
        if operation_id.trim().is_empty() {
            return Err(FeasibilityProbeError::Invalid {
                reason: "empty operation id".to_owned(),
            });
        }
        lock_cancelled(self)?.push(operation_id);
        Ok(())
    }

    /// Record or replay a platform batch id for crash-recovery tests.
    ///
    /// # Errors
    ///
    /// Returns closed/invalid errors when the probe is unusable or the id is empty.
    pub fn submit_platform_batch(&self, batch_id: String) -> Result<String, FeasibilityProbeError> {
        ensure_open(self)?;
        if batch_id.trim().is_empty() {
            return Err(FeasibilityProbeError::Invalid {
                reason: "empty batch id".to_owned(),
            });
        }
        let mut guard =
            self.last_batch_id
                .lock()
                .map_err(|_poison| FeasibilityProbeError::Invalid {
                    reason: "batch lock poisoned".to_owned(),
                })?;
        let outcome = if guard.as_ref() == Some(&batch_id) {
            format!("replayed:{batch_id}")
        } else {
            *guard = Some(batch_id.clone());
            format!("accepted:{batch_id}")
        };
        drop(guard);
        Ok(outcome)
    }

    /// Complete an operation unless it was cancelled first.
    ///
    /// # Errors
    ///
    /// Returns cancelled when the operation was cancelled first, or closed when shut down.
    pub fn complete_operation(
        &self,
        operation_id: String,
    ) -> Result<String, FeasibilityProbeError> {
        ensure_open(self)?;
        let was_cancelled = lock_cancelled(self)?.contains(&operation_id);
        if was_cancelled {
            return Err(FeasibilityProbeError::Cancelled { operation_id });
        }
        Ok(format!("completed:{operation_id}"))
    }

    /// Shut down the probe; subsequent calls fail.
    ///
    /// Named `shutdown` (not `close`) so generated Kotlin does not collide with
    /// `UniFFI` `AutoCloseable.close()` / object destruction.
    ///
    /// # Errors
    ///
    /// Currently always succeeds; signature stays fallible for stable FFI.
    pub fn shutdown(&self) -> Result<(), FeasibilityProbeError> {
        self.closed.store(true, Ordering::SeqCst);
        Ok(())
    }
}

fn ensure_open(probe: &FeasibilityProbe) -> Result<(), FeasibilityProbeError> {
    if probe.closed.load(Ordering::SeqCst) {
        Err(FeasibilityProbeError::Closed {
            reason: "probe is closed".to_owned(),
        })
    } else {
        Ok(())
    }
}

fn lock_cancelled(
    probe: &FeasibilityProbe,
) -> Result<MutexGuard<'_, Vec<String>>, FeasibilityProbeError> {
    probe
        .cancelled
        .lock()
        .map_err(|_poison| FeasibilityProbeError::Invalid {
            reason: "cancel lock poisoned".to_owned(),
        })
}
