//! Temporary `UniFFI` feasibility surface (feature `feasibility-probe` only).
//!
//! P0-10 journal invariants:
//! - **Atomic commit**: candidate state is computed, persisted, then published to memory. On persist
//!   failure memory is unchanged.
//! - **Constrained ids**: reject whitespace/control/newline so line-oriented journal cannot be injected.
//! - **Batch → actions**: applied actions are recorded under a batch id; confirm moves the batch only.
//! - **Fail closed**: unknown schema, duplicates, and U∩C conflicts reject open.

use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
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

/// Listener callback for revision events (tooling recovery).
#[uniffi::export(with_foreign)]
pub trait FeasibilityProbeListener: Send + Sync {
    fn on_revision(&self, revision: u64);
}

const JOURNAL_SCHEMA_VERSION: u32 = 1;
const JOURNAL_HEADER: &str = "# lomo feasibility batch journal v1";
const MAX_ID_LEN: usize = 128;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct JournalState {
    unconfirmed_batches: BTreeSet<String>,
    confirmed_batches: BTreeSet<String>,
    /// Actions applied under each batch (`batch_id → action_ids`).
    actions: BTreeMap<String, BTreeSet<String>>,
    cancelled_ops: BTreeSet<String>,
}

/// Long-lived probe object used only by tooling/native-smoke.
#[derive(uniffi::Object)]
pub struct FeasibilityProbe {
    closed: AtomicBool,
    revision: AtomicU64,
    journal_path: Mutex<Option<PathBuf>>,
    journal: Mutex<JournalState>,
    listeners: Mutex<Vec<Arc<dyn FeasibilityProbeListener>>>,
}

// UniFFI requires owned `String` parameters on exported methods.
#[allow(
    clippy::needless_pass_by_value,
    reason = "UniFFI exported methods require owned String parameters"
)]
#[uniffi::export]
impl FeasibilityProbe {
    /// Create a new open probe with in-memory journal only (unit tests).
    #[uniffi::constructor]
    #[must_use]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            closed: AtomicBool::new(false),
            revision: AtomicU64::new(0),
            journal_path: Mutex::new(None),
            journal: Mutex::new(JournalState::default()),
            listeners: Mutex::new(Vec::new()),
        })
    }

    /// Open or reopen a probe with a durable journal file path.
    ///
    /// # Errors
    ///
    /// Returns [`FeasibilityProbeError::Invalid`] when the path is empty, unreadable, corrupt, or
    /// fails schema/consistency checks.
    #[uniffi::constructor]
    pub fn open(journal_path: String) -> Result<Arc<Self>, FeasibilityProbeError> {
        if journal_path.trim().is_empty() {
            return Err(FeasibilityProbeError::Invalid {
                reason: "empty journal path".to_owned(),
            });
        }
        let path = PathBuf::from(&journal_path);
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|error| FeasibilityProbeError::Invalid {
                reason: format!("journal parent: {error}"),
            })?;
        }
        let state = load_journal(&path)?;
        Ok(Arc::new(Self {
            closed: AtomicBool::new(false),
            revision: AtomicU64::new(0),
            journal_path: Mutex::new(Some(path)),
            journal: Mutex::new(state),
            listeners: Mutex::new(Vec::new()),
        }))
    }

    /// Register a revision listener.
    ///
    /// # Errors
    ///
    /// Returns closed when shut down.
    pub fn add_listener(
        &self,
        listener: Arc<dyn FeasibilityProbeListener>,
    ) -> Result<(), FeasibilityProbeError> {
        ensure_open(self)?;
        lock_listeners(self)?.push(listener);
        Ok(())
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

    /// Advance revision by one (mutation event) and notify listeners.
    ///
    /// Listeners are snapshotted under the mutex and invoked after release so a re-entrant
    /// listener (add/bump) cannot deadlock on `listeners`.
    ///
    /// # Errors
    ///
    /// Returns [`FeasibilityProbeError::Closed`] after [`Self::shutdown`].
    pub fn bump_revision(&self) -> Result<u64, FeasibilityProbeError> {
        ensure_open(self)?;
        let next = self.revision.fetch_add(1, Ordering::SeqCst) + 1;
        let listeners: Vec<Arc<dyn FeasibilityProbeListener>> = lock_listeners(self)?.clone();
        for listener in listeners {
            listener.on_revision(next);
        }
        Ok(next)
    }

    /// Return a bounded page; `page_size` is clamped to 1..=32.
    ///
    /// # Errors
    ///
    /// Returns closed when shut down, or invalid when `cursor` is non-empty but not a decimal index
    /// (unparseable cursors fail closed — they are never reset to page zero), or when
    /// `start + size` would overflow.
    pub fn list_page(
        &self,
        cursor: Option<String>,
        page_size: u32,
    ) -> Result<FeasibilityPage, FeasibilityProbeError> {
        ensure_open(self)?;
        let size = page_size.clamp(1, 32) as usize;
        let start = match cursor {
            None => 0,
            Some(value) => {
                if value.is_empty() {
                    return Err(FeasibilityProbeError::Invalid {
                        reason: "empty page cursor".to_owned(),
                    });
                }
                value
                    .parse::<usize>()
                    .map_err(|error| FeasibilityProbeError::Invalid {
                        reason: format!("unparseable page cursor: {error}"),
                    })?
            }
        };
        let end = start
            .checked_add(size)
            .ok_or_else(|| FeasibilityProbeError::Invalid {
                reason: "page range overflow".to_owned(),
            })?;
        let items = (start..end)
            .map(|index| format!("item-{index}"))
            .collect::<Vec<_>>();
        let next_cursor = Some(end.to_string());
        Ok(FeasibilityPage { items, next_cursor })
    }

    /// Mark an operation cancelled (durable when a journal path is set).
    ///
    /// # Errors
    ///
    /// Returns closed/invalid when shut down or the id is illegal.
    pub fn cancel(&self, operation_id: String) -> Result<(), FeasibilityProbeError> {
        ensure_open(self)?;
        let operation_id = validate_id("operation_id", &operation_id)?;
        commit(self, |state| {
            if state.cancelled_ops.contains(&operation_id) {
                return Ok(((), None));
            }
            let mut candidate = state.clone();
            candidate.cancelled_ops.insert(operation_id);
            Ok(((), Some(candidate)))
        })
    }

    /// Submit a platform batch. Confirmed batches replay; unconfirmed re-accept after crash.
    ///
    /// # Errors
    ///
    /// Returns closed/invalid when shut down or the id is illegal.
    pub fn submit_platform_batch(&self, batch_id: String) -> Result<String, FeasibilityProbeError> {
        ensure_open(self)?;
        let batch_id = validate_id("batch_id", &batch_id)?;
        commit(self, |state| {
            if state.confirmed_batches.contains(&batch_id) {
                return Ok((format!("replayed:{batch_id}"), None));
            }
            if state.unconfirmed_batches.contains(&batch_id) {
                return Ok((format!("accepted:{batch_id}"), None));
            }
            let mut candidate = state.clone();
            candidate.unconfirmed_batches.insert(batch_id.clone());
            Ok((format!("accepted:{batch_id}"), Some(candidate)))
        })
    }

    /// Confirm a previously accepted batch so restarts replay without re-applying its actions.
    ///
    /// # Errors
    ///
    /// Returns closed/invalid when the batch was never accepted or has no applied actions yet
    /// (empty confirmation is rejected — a confirmed batch must own ≥1 action for the smoke contract).
    pub fn confirm_platform_batch(
        &self,
        batch_id: String,
    ) -> Result<String, FeasibilityProbeError> {
        ensure_open(self)?;
        let batch_id = validate_id("batch_id", &batch_id)?;
        commit(self, |state| {
            if state.confirmed_batches.contains(&batch_id) {
                return Ok((format!("confirmed:{batch_id}"), None));
            }
            if !state.unconfirmed_batches.contains(&batch_id) {
                return Err(FeasibilityProbeError::Invalid {
                    reason: format!("unknown batch id {batch_id}"),
                });
            }
            let action_count = state.actions.get(&batch_id).map_or(0, BTreeSet::len);
            if action_count == 0 {
                return Err(FeasibilityProbeError::Invalid {
                    reason: format!(
                        "batch {batch_id} has no applied actions; refuse empty confirm"
                    ),
                });
            }
            let mut candidate = state.clone();
            candidate.unconfirmed_batches.remove(&batch_id);
            candidate.confirmed_batches.insert(batch_id.clone());
            Ok((format!("confirmed:{batch_id}"), Some(candidate)))
        })
    }

    /// Apply a side-effect action under an open batch. Applied actions never re-apply after recovery.
    ///
    /// The host performs the real platform write first (or re-applies on recovery), then journals
    /// success with this call. Action ids must be constrained (no whitespace/control).
    ///
    /// # Errors
    ///
    /// Returns closed/invalid when ids are illegal, the batch is missing/confirmed, or shut down.
    pub fn apply_action(
        &self,
        batch_id: String,
        action_id: String,
    ) -> Result<String, FeasibilityProbeError> {
        ensure_open(self)?;
        let batch_id = validate_id("batch_id", &batch_id)?;
        let action_id = validate_id("action_id", &action_id)?;
        commit(self, |state| {
            if state.confirmed_batches.contains(&batch_id) {
                if state
                    .actions
                    .get(&batch_id)
                    .is_some_and(|set| set.contains(&action_id))
                {
                    return Ok((format!("skipped:{action_id}"), None));
                }
                return Err(FeasibilityProbeError::Invalid {
                    reason: format!("batch {batch_id} already confirmed; cannot apply new action"),
                });
            }
            if !state.unconfirmed_batches.contains(&batch_id) {
                return Err(FeasibilityProbeError::Invalid {
                    reason: format!("unknown batch id {batch_id}; submit first"),
                });
            }
            if state
                .actions
                .get(&batch_id)
                .is_some_and(|set| set.contains(&action_id))
            {
                return Ok((format!("skipped:{action_id}"), None));
            }
            let mut candidate = state.clone();
            candidate
                .actions
                .entry(batch_id)
                .or_default()
                .insert(action_id.clone());
            Ok((format!("applied:{action_id}"), Some(candidate)))
        })
    }

    /// Complete an operation unless it was cancelled first (including durable cancels).
    ///
    /// # Errors
    ///
    /// Returns cancelled when the operation was cancelled first, or closed when shut down.
    pub fn complete_operation(
        &self,
        operation_id: String,
    ) -> Result<String, FeasibilityProbeError> {
        ensure_open(self)?;
        let operation_id = validate_id("operation_id", &operation_id)?;
        let was_cancelled = {
            let journal = lock_journal(self)?;
            journal.cancelled_ops.contains(&operation_id)
        };
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

/// Validate and normalize an identifier for journal safety.
///
/// Rejects empty, overlong, and any whitespace/control characters so a single journal line cannot
/// smuggle additional records.
fn validate_id(field: &str, raw: &str) -> Result<String, FeasibilityProbeError> {
    if raw.is_empty() {
        return Err(FeasibilityProbeError::Invalid {
            reason: format!("empty {field}"),
        });
    }
    if raw.len() > MAX_ID_LEN {
        return Err(FeasibilityProbeError::Invalid {
            reason: format!("{field} exceeds {MAX_ID_LEN} bytes"),
        });
    }
    if raw.chars().any(|ch| ch.is_whitespace() || ch.is_control()) {
        return Err(FeasibilityProbeError::Invalid {
            reason: format!("{field} must not contain whitespace or control characters"),
        });
    }
    // Restrict to a conservative printable set used by smoke digests and batch names.
    if !raw.bytes().all(|b| {
        b.is_ascii_alphanumeric()
            || matches!(b, b'.' | b'_' | b'-' | b':' | b'+' | b'/' | b'=' | b'@')
    }) {
        return Err(FeasibilityProbeError::Invalid {
            reason: format!("{field} contains unsupported characters"),
        });
    }
    Ok(raw.to_owned())
}

/// Candidate → durable commit → publish. Memory is unchanged if persist fails.
fn commit<T>(
    probe: &FeasibilityProbe,
    mutate: impl FnOnce(&JournalState) -> Result<(T, Option<JournalState>), FeasibilityProbeError>,
) -> Result<T, FeasibilityProbeError> {
    let mut guard = lock_journal(probe)?;
    let (outcome, candidate) = mutate(&guard)?;
    let Some(candidate) = candidate else {
        drop(guard);
        return Ok(outcome);
    };
    // Hold the lock across persist so concurrent readers cannot observe half-written domain state.
    persist_journal_locked(probe, &candidate)?;
    *guard = candidate;
    drop(guard);
    Ok(outcome)
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

fn lock_journal(
    probe: &FeasibilityProbe,
) -> Result<MutexGuard<'_, JournalState>, FeasibilityProbeError> {
    probe
        .journal
        .lock()
        .map_err(|_poison| FeasibilityProbeError::Invalid {
            reason: "journal lock poisoned".to_owned(),
        })
}

fn lock_listeners(
    probe: &FeasibilityProbe,
) -> Result<MutexGuard<'_, Vec<Arc<dyn FeasibilityProbeListener>>>, FeasibilityProbeError> {
    probe
        .listeners
        .lock()
        .map_err(|_poison| FeasibilityProbeError::Invalid {
            reason: "listener lock poisoned".to_owned(),
        })
}

fn load_journal(path: &Path) -> Result<JournalState, FeasibilityProbeError> {
    // Missing path = first open (empty domain). Existing path must be a well-formed journal.
    if !path.exists() {
        return Ok(JournalState::default());
    }
    let text = fs::read_to_string(path).map_err(|error| FeasibilityProbeError::Invalid {
        reason: format!("read journal: {error}"),
    })?;
    if text.trim().is_empty() {
        return Err(FeasibilityProbeError::Invalid {
            reason: "empty journal file (truncated or zero-length); refuse fail-open reset"
                .to_owned(),
        });
    }
    parse_journal_text(&text)
}

fn parse_journal_text(text: &str) -> Result<JournalState, FeasibilityProbeError> {
    if text.trim().is_empty() {
        return Err(FeasibilityProbeError::Invalid {
            reason: "empty journal text".to_owned(),
        });
    }
    let mut lines = text.lines().enumerate();
    let Some((_, first)) = lines.next() else {
        return Err(FeasibilityProbeError::Invalid {
            reason: "empty journal text".to_owned(),
        });
    };
    if first.trim() != JOURNAL_HEADER {
        return Err(FeasibilityProbeError::Invalid {
            reason: format!("missing journal header `{JOURNAL_HEADER}`"),
        });
    }
    let mut schema_seen = false;
    let mut state = JournalState::default();
    for (line_no, raw) in lines {
        apply_journal_line(&mut state, &mut schema_seen, line_no + 1, raw.trim())?;
    }
    // Schema is mandatory for any on-disk journal body (including header-only empty state).
    if !schema_seen {
        return Err(FeasibilityProbeError::Invalid {
            reason: "journal missing schema version line".to_owned(),
        });
    }
    validate_state_consistency(&state)?;
    Ok(state)
}

fn apply_journal_line(
    state: &mut JournalState,
    schema_seen: &mut bool,
    line_no: usize,
    line: &str,
) -> Result<(), FeasibilityProbeError> {
    if line.is_empty() || line.starts_with('#') {
        return Ok(());
    }
    if let Some(version) = line.strip_prefix("schema ") {
        let parsed: u32 =
            version
                .trim()
                .parse()
                .map_err(|error| FeasibilityProbeError::Invalid {
                    reason: format!("corrupt journal line {line_no}: bad schema ({error})"),
                })?;
        if parsed != JOURNAL_SCHEMA_VERSION {
            return Err(FeasibilityProbeError::Invalid {
                reason: format!(
                    "unsupported journal schema {parsed}, expected {JOURNAL_SCHEMA_VERSION}"
                ),
            });
        }
        *schema_seen = true;
        return Ok(());
    }
    let Some((kind, rest)) = line.split_once(' ') else {
        return Err(FeasibilityProbeError::Invalid {
            reason: format!("corrupt journal line {line_no}: missing kind/payload"),
        });
    };
    match kind {
        "U" => {
            let id = validate_id("batch_id", rest)?;
            if !state.unconfirmed_batches.insert(id.clone()) {
                return Err(FeasibilityProbeError::Invalid {
                    reason: format!("duplicate unconfirmed batch {id}"),
                });
            }
        }
        "C" => {
            let id = validate_id("batch_id", rest)?;
            if !state.confirmed_batches.insert(id.clone()) {
                return Err(FeasibilityProbeError::Invalid {
                    reason: format!("duplicate confirmed batch {id}"),
                });
            }
        }
        "A" => {
            let Some((batch_id, action_id)) = rest.split_once(' ') else {
                return Err(FeasibilityProbeError::Invalid {
                    reason: format!(
                        "corrupt journal line {line_no}: action requires batch_id action_id"
                    ),
                });
            };
            let batch_id = validate_id("batch_id", batch_id)?;
            let action_id = validate_id("action_id", action_id)?;
            if !state
                .actions
                .entry(batch_id)
                .or_default()
                .insert(action_id.clone())
            {
                return Err(FeasibilityProbeError::Invalid {
                    reason: format!("duplicate action {action_id}"),
                });
            }
        }
        "X" => {
            let id = validate_id("operation_id", rest)?;
            if !state.cancelled_ops.insert(id.clone()) {
                return Err(FeasibilityProbeError::Invalid {
                    reason: format!("duplicate cancel {id}"),
                });
            }
        }
        other => {
            return Err(FeasibilityProbeError::Invalid {
                reason: format!("corrupt journal line {line_no}: unknown kind `{other}`"),
            });
        }
    }
    Ok(())
}

fn validate_state_consistency(state: &JournalState) -> Result<(), FeasibilityProbeError> {
    let overlap: Vec<_> = state
        .unconfirmed_batches
        .intersection(&state.confirmed_batches)
        .cloned()
        .collect();
    if !overlap.is_empty() {
        return Err(FeasibilityProbeError::Invalid {
            reason: format!("batch both unconfirmed and confirmed: {overlap:?}"),
        });
    }
    for batch_id in state.actions.keys() {
        if !state.unconfirmed_batches.contains(batch_id)
            && !state.confirmed_batches.contains(batch_id)
        {
            return Err(FeasibilityProbeError::Invalid {
                reason: format!("action references unknown batch {batch_id}"),
            });
        }
    }
    for batch_id in &state.confirmed_batches {
        let action_count = state.actions.get(batch_id).map_or(0, BTreeSet::len);
        if action_count == 0 {
            return Err(FeasibilityProbeError::Invalid {
                reason: format!("confirmed batch {batch_id} has no applied actions"),
            });
        }
    }
    Ok(())
}

fn persist_journal_locked(
    probe: &FeasibilityProbe,
    state: &JournalState,
) -> Result<(), FeasibilityProbeError> {
    let path = {
        let path_guard =
            probe
                .journal_path
                .lock()
                .map_err(|_poison| FeasibilityProbeError::Invalid {
                    reason: "journal path lock poisoned".to_owned(),
                })?;
        path_guard.clone()
    };
    let Some(path) = path else {
        // In-memory probe: durable commit is a no-op; publish is the only step.
        return Ok(());
    };
    validate_state_consistency(state)?;
    let mut body = format!("{JOURNAL_HEADER}\nschema {JOURNAL_SCHEMA_VERSION}\n");
    for id in &state.unconfirmed_batches {
        body.push_str("U ");
        body.push_str(id);
        body.push('\n');
    }
    for id in &state.confirmed_batches {
        body.push_str("C ");
        body.push_str(id);
        body.push('\n');
    }
    for (batch_id, actions) in &state.actions {
        for action_id in actions {
            body.push_str("A ");
            body.push_str(batch_id);
            body.push(' ');
            body.push_str(action_id);
            body.push('\n');
        }
    }
    for id in &state.cancelled_ops {
        body.push_str("X ");
        body.push_str(id);
        body.push('\n');
    }
    let temporary = path.with_extension("tmp");
    {
        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(&temporary)
            .map_err(|error| FeasibilityProbeError::Invalid {
                reason: format!("open journal tmp: {error}"),
            })?;
        file.write_all(body.as_bytes())
            .map_err(|error| FeasibilityProbeError::Invalid {
                reason: format!("write journal: {error}"),
            })?;
        file.sync_all()
            .map_err(|error| FeasibilityProbeError::Invalid {
                reason: format!("sync journal: {error}"),
            })?;
    }
    fs::rename(&temporary, path).map_err(|error| FeasibilityProbeError::Invalid {
        reason: format!("rename journal: {error}"),
    })?;
    Ok(())
}
