//! Reminder business state owner (P3-07).
//!
//! Owns next-trigger planning, DST floating-local policy, catch-up storm prevention,
//! mark-done/record-fired token mutation planning, and app-private snooze binding.
//! Canonical fired/done/recurrence rewrite targets Markdown via planned tokens; snooze never
//! rewrites the body and never enters `.lomo`/sync/archive.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use lomo_core::{ErrorCategory, InvalidationScope, LomoError, RetryDisposition};
use lomo_workspace::{ReminderTokenMutation, plan_reminder_token_mutation, reminder_token_facts};
use serde::{Deserialize, Serialize};

/// Platform zone transition (Kotlin `ZoneRules` candidate).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ZoneTransition {
    /// Instant (UTC ms) when the offset changes.
    pub transition_utc_ms: i64,
    /// Offset (seconds east of UTC) immediately before the transition.
    pub offset_before_secs: i32,
    /// Offset (seconds east of UTC) immediately after the transition.
    pub offset_after_secs: i32,
}

/// Number of reminder alarms the rolling window emits per plan.
///
/// The window refills on the next plan query after an occurrence fires or is cancelled; alarms
/// beyond the window are reported via [`ReminderPlan::dropped_count`] rather than silently
/// discarded.
pub const REMINDER_ROLLING_WINDOW: usize = 64;

/// Bounded time-zone context supplied by the platform adapter.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TimeZoneContext {
    pub zone_id: String,
    /// Offset in force before the first transition (and when no transitions apply).
    pub base_offset_secs: i32,
    /// Transitions sorted ascending by [`ZoneTransition::transition_utc_ms`].
    pub transitions: Vec<ZoneTransition>,
    /// Inclusive UTC-ms bound: resolution below this instant has no trustworthy offset.
    pub coverage_start_utc_ms: i64,
    /// Inclusive UTC-ms bound: resolution above this instant has no trustworthy offset.
    pub coverage_end_utc_ms: i64,
}

/// One reminder occurrence projected from Markdown/scan (typed facts, no regex authority here).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ReminderSessionInput {
    pub opaque_id: String,
    pub memo_identity: String,
    pub memo_revision: String,
    pub token: String,
    pub due_at_local: String,
    pub repeat_count: u32,
    pub fired_count: u32,
    pub done: bool,
    pub interval_minutes: u32,
    pub recurrence_code: String,
}

/// Query for a rolling-window reminder plan.
#[derive(Debug, Clone)]
pub struct ReminderQuery {
    pub now_utc_ms: i64,
    pub zone: TimeZoneContext,
    pub sessions: Vec<ReminderSessionInput>,
    /// Maximum alarms to emit across all sessions (rolling window).
    pub rolling_window: usize,
    /// Durable workspace generation (`WorkspaceGenerationId` hex) the plan is issued under.
    pub workspace_generation: String,
}

/// One platform alarm the adapter must schedule.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlannedAlarm {
    /// Identity of this single firing: `{generation}\u{1f}{opaque_id}\u{1f}{trigger_at_utc_ms}`.
    /// Distinct from the definition identity so cancellation/dedup never relies on a process
    /// cache or a bare 32-bit request-code hash.
    pub occurrence_id: String,
    pub opaque_id: String,
    pub memo_identity: String,
    pub trigger_at_utc_ms: i64,
    /// True when this fire recovers a missed moment (at most one per session per plan).
    pub is_catch_up: bool,
}

/// Platform schedule plan owned by Rust.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ReminderPlan {
    pub alarms: Vec<PlannedAlarm>,
    /// Future alarms omitted because the rolling window is full. Never silently dropped — a
    /// non-zero value means the platform must re-plan after the earliest in-window occurrence
    /// completes or is cancelled, which refills the window.
    pub dropped_count: u32,
    pub workspace_generation: String,
}

/// Commands that mutate reminder business state.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReminderCommand {
    MarkDone {
        session: ReminderSessionInput,
        expected_revision: String,
    },
    RecordFired {
        session: ReminderSessionInput,
        expected_revision: String,
    },
    Snooze {
        opaque_id: String,
        /// Durable workspace generation the binding is scoped to.
        workspace_generation: String,
        snooze_until_utc_ms: i64,
    },
    ClearSnooze {
        opaque_id: String,
        /// Durable workspace generation the binding is scoped to.
        workspace_generation: String,
    },
}

/// Result of applying a reminder command (Markdown mutation is planned, not applied here).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReminderCommandResult {
    /// Replacement token for Markdown write-back. Always `None` for snooze.
    pub replacement_token: Option<String>,
    pub scopes: Vec<InvalidationScope>,
    /// True when the body must not be rewritten (snooze path).
    pub snooze_only: bool,
}

/// Maximum persisted snooze bindings; the store refuses new bindings past this bound.
const MAX_SNOOZE_ENTRIES: usize = 1024;

/// App-private snooze binding key as a single string map key (JSON object keys must be strings).
/// Format: `{workspace_generation}\u{1f}{opaque_id}`
fn snooze_key(workspace_generation: &str, opaque_id: &str) -> String {
    format!("{workspace_generation}\u{1f}{opaque_id}")
}

/// Deterministic occurrence identity for one planned firing.
fn occurrence_id(workspace_generation: &str, opaque_id: &str, trigger_at_utc_ms: i64) -> String {
    format!("{workspace_generation}\u{1f}{opaque_id}\u{1f}{trigger_at_utc_ms}")
}

/// App-private snooze state. Bound to workspace generation + reminder definition id.
///
/// A corrupt payload never blocks workspace reads/writes: the file is kept in place as evidence,
/// the store reports [`SnoozeStore::recovery_pending`], and planning/snooze commands fail closed
/// with `reminder_recovery_needed` until [`SnoozeStore::recover`] quarantines the evidence.
#[derive(Debug, Clone, Default)]
pub struct SnoozeStore {
    path: Option<PathBuf>,
    entries: BTreeMap<String, i64>,
    recovery_pending: bool,
}

impl SnoozeStore {
    /// In-memory store (unit tests / ephemeral).
    #[must_use]
    #[expect(
        clippy::missing_const_for_fn,
        reason = "BTreeMap::new is not usable in const fn on this toolchain without unstable traits"
    )]
    pub fn memory() -> Self {
        Self {
            path: None,
            entries: BTreeMap::new(),
            recovery_pending: false,
        }
    }

    /// Opens or creates snooze state under an **application-private** directory (never `.lomo`).
    ///
    /// # Errors
    ///
    /// Returns storage errors when the path cannot be read/created. A corrupt payload does not
    /// error: it marks the store `recovery_pending` instead so scheduling pauses until
    /// [`SnoozeStore::recover`] is invoked explicitly.
    pub fn open_app_private(app_private_dir: impl AsRef<Path>) -> Result<Self, LomoError> {
        let dir = app_private_dir.as_ref();
        if dir_is_under_lomo(dir) {
            return Err(reminder_validation(
                "snooze_in_lomo_forbidden",
                "snooze state must not live under .lomo",
            ));
        }
        fs::create_dir_all(dir).map_err(|err| {
            reminder_storage(
                "snooze_dir_create_failed",
                &format!("create snooze dir: {err}"),
            )
        })?;
        let path = dir.join("reminder_snooze.v1.json");
        let mut store = Self {
            path: Some(path.clone()),
            entries: BTreeMap::new(),
            recovery_pending: false,
        };
        if path.exists() {
            let bytes = fs::read(&path).map_err(|err| {
                reminder_storage("snooze_read_failed", &format!("read snooze file: {err}"))
            })?;
            match serde_json::from_slice::<BTreeMap<String, i64>>(&bytes) {
                Ok(decoded) => store.entries = decoded,
                // Corrupt bytes stay in place as evidence; recovery is an explicit entry point.
                Err(_) => store.recovery_pending = true,
            }
        }
        Ok(store)
    }

    /// Opens snooze state and immediately quarantines a corrupt payload, returning a ready store.
    ///
    /// This is the explicit recovery entry: the corrupt file is renamed aside (preserved), then a
    /// fresh empty store is persisted. It is never invoked implicitly by planning.
    ///
    /// # Errors
    ///
    /// Storage errors when the path cannot be read/created/quarantined.
    pub fn recover_app_private(app_private_dir: impl AsRef<Path>) -> Result<Self, LomoError> {
        let mut store = Self::open_app_private(app_private_dir)?;
        store.recover()?;
        Ok(store)
    }

    /// True when a corrupt payload was found and scheduling must pause until [`Self::recover`].
    #[must_use]
    pub const fn recovery_pending(&self) -> bool {
        self.recovery_pending
    }

    /// Quarantines the corrupt payload file and persists a fresh empty store.
    ///
    /// # Errors
    ///
    /// Storage errors when the evidence rename or the fresh persist fails.
    pub fn recover(&mut self) -> Result<(), LomoError> {
        if !self.recovery_pending {
            return Ok(());
        }
        if let Some(path) = &self.path
            && path.exists()
        {
            let name = path.file_name().map_or_else(
                || "reminder_snooze.v1.json".to_owned(),
                |n| n.to_string_lossy().into_owned(),
            );
            let stamp = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_or(0, |d| d.as_millis());
            let quarantine = path.with_file_name(format!("{name}.corrupt-{stamp}"));
            fs::rename(path, &quarantine).map_err(|err| {
                reminder_storage(
                    "snooze_quarantine_failed",
                    &format!("quarantine corrupt snooze file: {err}"),
                )
            })?;
        }
        self.entries.clear();
        self.recovery_pending = false;
        self.persist()
    }

    /// Lookup snooze-until UTC ms when binding matches.
    #[must_use]
    pub fn snooze_until(&self, workspace_generation: &str, opaque_id: &str) -> Option<i64> {
        self.entries
            .get(&snooze_key(workspace_generation, opaque_id))
            .copied()
    }

    fn ensure_writable(&self) -> Result<(), LomoError> {
        if self.recovery_pending {
            return Err(reminder_recovery_needed(
                "snooze state is quarantined pending explicit recovery",
            ));
        }
        Ok(())
    }

    fn put(
        &mut self,
        workspace_generation: &str,
        opaque_id: &str,
        until: i64,
    ) -> Result<(), LomoError> {
        self.ensure_writable()?;
        let key = snooze_key(workspace_generation, opaque_id);
        if !self.entries.contains_key(&key) && self.entries.len() >= MAX_SNOOZE_ENTRIES {
            return Err(reminder_validation(
                "snooze_budget_exhausted",
                "snooze entry budget exhausted",
            ));
        }
        self.entries.insert(key, until);
        self.persist()
    }

    fn remove(&mut self, workspace_generation: &str, opaque_id: &str) -> Result<(), LomoError> {
        self.ensure_writable()?;
        self.entries
            .remove(&snooze_key(workspace_generation, opaque_id));
        self.persist()
    }

    fn persist(&self) -> Result<(), LomoError> {
        let Some(path) = &self.path else {
            return Ok(());
        };
        if let Some(parent) = path.parent()
            && dir_is_under_lomo(parent)
        {
            return Err(reminder_validation(
                "snooze_in_lomo_forbidden",
                "snooze state must not live under .lomo",
            ));
        }
        let bytes = serde_json::to_vec_pretty(&self.entries).map_err(|err| {
            reminder_storage("snooze_encode_failed", &format!("encode snooze: {err}"))
        })?;
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, &bytes).map_err(|err| {
            reminder_storage("snooze_write_failed", &format!("write snooze tmp: {err}"))
        })?;
        fs::rename(&tmp, path).map_err(|err| {
            reminder_storage("snooze_rename_failed", &format!("rename snooze: {err}"))
        })?;
        Ok(())
    }
}

fn dir_is_under_lomo(path: &Path) -> bool {
    path.components().any(|c| c.as_os_str() == ".lomo")
}

/// Resolves floating local wall time (`yyyy-MM-dd-HH:mm`) to a UTC instant.
///
/// Policy:
/// - **DST gap** → first valid instant after the gap (transition UTC).
/// - **DST overlap** → earlier (first) instant.
///
/// # Errors
///
/// Validation when the local wall string is malformed or zone data is unusable.
pub fn resolve_floating_local_to_utc_ms(
    due_at_local: &str,
    zone: &TimeZoneContext,
) -> Result<i64, LomoError> {
    let (year, month, day, hour, minute) = parse_ymd_hm(due_at_local)?;
    let naive_ms = civil_to_epoch_ms(year, month, day, hour, minute);
    let mut candidates = candidate_offsets(zone);
    candidates.sort_unstable();
    candidates.dedup();

    let mut valid: Vec<i64> = Vec::new();
    for offset in candidates {
        let utc = naive_ms.saturating_sub(i64::from(offset).saturating_mul(1_000));
        if offset_at_utc(zone, utc) == offset {
            valid.push(utc);
        }
    }
    valid.sort_unstable();
    valid.dedup();

    match valid.as_slice() {
        [] => first_valid_after_gap(naive_ms, zone),
        [one] => require_zone_coverage(zone, *one),
        many => require_zone_coverage(zone, *many.iter().min().unwrap_or(&0)),
    }
}

/// Naive epoch-ms reading of a `due_at_local` wall time (components treated as UTC).
///
/// This is a coverage-bound estimate for transition-table sizing, not a zone resolution — callers
/// must still resolve the instant through [`resolve_floating_local_to_utc_ms`].
///
/// # Errors
///
/// Validation when the wall string is malformed.
pub fn naive_local_epoch_ms(due_at_local: &str) -> Result<i64, LomoError> {
    let (year, month, day, hour, minute) = parse_ymd_hm(due_at_local)?;
    Ok(civil_to_epoch_ms(year, month, day, hour, minute))
}

/// Rejects instants outside the supplied zone coverage instead of guessing an offset.
fn require_zone_coverage(zone: &TimeZoneContext, utc_ms: i64) -> Result<i64, LomoError> {
    if utc_ms < zone.coverage_start_utc_ms || utc_ms > zone.coverage_end_utc_ms {
        return Err(reminder_validation(
            "need_zone_context",
            "reminder instant lies outside the supplied time-zone coverage",
        ));
    }
    Ok(utc_ms)
}

/// Builds a rolling-window reminder plan.
///
/// Missed moments yield **at most one catch-up** fire per session, then the next future trigger.
/// Snooze, when bound to generation+opaque id, overrides the next trigger until cleared.
///
/// # Errors
///
/// Validation for empty window, invalid tokens/sessions, or unresolvable local times.
pub fn query_reminder_plan(
    query: &ReminderQuery,
    snooze: &SnoozeStore,
) -> Result<ReminderPlan, LomoError> {
    if query.rolling_window == 0 {
        return Err(reminder_validation(
            "invalid_rolling_window",
            "rolling_window must be positive",
        ));
    }
    if snooze.recovery_pending() {
        return Err(reminder_recovery_needed(
            "snooze state is quarantined pending explicit recovery",
        ));
    }
    let mut alarms: Vec<PlannedAlarm> = Vec::new();
    for session in &query.sessions {
        if session.done {
            continue;
        }
        if let Some(until) = snooze.snooze_until(&query.workspace_generation, &session.opaque_id)
            && until > query.now_utc_ms
        {
            alarms.push(PlannedAlarm {
                occurrence_id: occurrence_id(
                    &query.workspace_generation,
                    &session.opaque_id,
                    until,
                ),
                opaque_id: session.opaque_id.clone(),
                memo_identity: session.memo_identity.clone(),
                trigger_at_utc_ms: until,
                is_catch_up: false,
            });
            continue;
        }
        let planned = plan_session_triggers(
            session,
            &query.zone,
            query.now_utc_ms,
            &query.workspace_generation,
        )?;
        alarms.extend(planned);
    }
    alarms.sort_by_key(|a| (a.trigger_at_utc_ms, a.opaque_id.clone()));
    let dropped_count = alarms.len().saturating_sub(query.rolling_window);
    alarms.truncate(query.rolling_window);
    Ok(ReminderPlan {
        alarms,
        dropped_count: u32::try_from(dropped_count).unwrap_or(u32::MAX),
        workspace_generation: query.workspace_generation.clone(),
    })
}

/// Applies a reminder command: Markdown mutation planning for done/fired; app-private for snooze.
///
/// # Errors
///
/// - `stale_snapshot` when expected revision does not match the session.
/// - validation for invalid tokens/commands.
/// - storage for snooze persist failures.
pub fn apply_reminder_command(
    command: &ReminderCommand,
    snooze: &mut SnoozeStore,
) -> Result<ReminderCommandResult, LomoError> {
    match command {
        ReminderCommand::MarkDone {
            session,
            expected_revision,
        } => {
            ensure_revision(session, expected_revision)?;
            let replacement =
                plan_reminder_token_mutation(&session.token, ReminderTokenMutation::MarkDone)
                    .map_err(map_workspace_err)?;
            Ok(ReminderCommandResult {
                replacement_token: Some(replacement),
                scopes: vec![InvalidationScope::Reminder, InvalidationScope::MemoList],
                snooze_only: false,
            })
        }
        ReminderCommand::RecordFired {
            session,
            expected_revision,
        } => {
            ensure_revision(session, expected_revision)?;
            let replacement =
                plan_reminder_token_mutation(&session.token, ReminderTokenMutation::RecordFired)
                    .map_err(map_workspace_err)?;
            Ok(ReminderCommandResult {
                replacement_token: Some(replacement),
                scopes: vec![InvalidationScope::Reminder],
                snooze_only: false,
            })
        }
        ReminderCommand::Snooze {
            opaque_id,
            workspace_generation,
            snooze_until_utc_ms,
        } => {
            snooze.put(workspace_generation, opaque_id, *snooze_until_utc_ms)?;
            Ok(ReminderCommandResult {
                replacement_token: None,
                scopes: vec![InvalidationScope::Reminder],
                snooze_only: true,
            })
        }
        ReminderCommand::ClearSnooze {
            opaque_id,
            workspace_generation,
        } => {
            snooze.remove(workspace_generation, opaque_id)?;
            Ok(ReminderCommandResult {
                replacement_token: None,
                scopes: vec![InvalidationScope::Reminder],
                snooze_only: true,
            })
        }
    }
}

/// Next theoretical base trigger for a session (due + `fired_count` * interval), resolved to UTC.
///
/// # Errors
///
/// Validation for malformed due-at or zone resolution failure.
pub fn session_base_trigger_utc_ms(
    session: &ReminderSessionInput,
    zone: &TimeZoneContext,
) -> Result<i64, LomoError> {
    let base = resolve_floating_local_to_utc_ms(&session.due_at_local, zone)?;
    if session.repeat_count > 1 && session.fired_count > 0 {
        let step = i64::from(session.interval_minutes).saturating_mul(60_000);
        let add = i64::from(session.fired_count).saturating_mul(step);
        Ok(base.saturating_add(add))
    } else {
        Ok(base)
    }
}

fn plan_session_triggers(
    session: &ReminderSessionInput,
    zone: &TimeZoneContext,
    now_utc_ms: i64,
    workspace_generation: &str,
) -> Result<Vec<PlannedAlarm>, LomoError> {
    // Validate token facts match session payload (fail closed on drift).
    let facts = reminder_token_facts(&session.token).map_err(map_workspace_err)?;
    if facts.due_at_local != session.due_at_local
        || facts.repeat_count != session.repeat_count
        || facts.fired_count != session.fired_count
        || facts.done != session.done
        || facts.interval_minutes != session.interval_minutes
        || facts.recurrence_code != session.recurrence_code
    {
        return Err(reminder_validation(
            "reminder_session_fact_mismatch",
            "session typed facts do not match canonical token",
        ));
    }

    let mut out = Vec::new();
    let mut working = session.clone();
    let mut catch_up_emitted = false;

    // Emit at most one catch-up, then one future (storm prevention).
    for _ in 0..2 {
        if working.done {
            break;
        }
        let trigger = session_base_trigger_utc_ms(&working, zone)?;
        if trigger <= now_utc_ms {
            if catch_up_emitted {
                // Already used the single catch-up slot; advance state without more catch-ups.
                working = advance_after_fire(&working)?;
                continue;
            }
            out.push(PlannedAlarm {
                occurrence_id: occurrence_id(workspace_generation, &session.opaque_id, now_utc_ms),
                opaque_id: session.opaque_id.clone(),
                memo_identity: session.memo_identity.clone(),
                trigger_at_utc_ms: now_utc_ms,
                is_catch_up: true,
            });
            catch_up_emitted = true;
            working = advance_after_fire(&working)?;
        } else {
            out.push(PlannedAlarm {
                occurrence_id: occurrence_id(workspace_generation, &session.opaque_id, trigger),
                opaque_id: session.opaque_id.clone(),
                memo_identity: session.memo_identity.clone(),
                trigger_at_utc_ms: trigger,
                is_catch_up: false,
            });
            break;
        }
    }
    Ok(out)
}

/// Advances session facts as if one fire was recorded (for plan catch-up storm prevention).
fn advance_after_fire(session: &ReminderSessionInput) -> Result<ReminderSessionInput, LomoError> {
    let new_token =
        plan_reminder_token_mutation(&session.token, ReminderTokenMutation::RecordFired)
            .map_err(map_workspace_err)?;
    let facts = reminder_token_facts(&new_token).map_err(map_workspace_err)?;
    Ok(ReminderSessionInput {
        opaque_id: session.opaque_id.clone(),
        memo_identity: session.memo_identity.clone(),
        memo_revision: session.memo_revision.clone(),
        token: new_token,
        due_at_local: facts.due_at_local,
        repeat_count: facts.repeat_count,
        fired_count: facts.fired_count,
        done: facts.done,
        interval_minutes: facts.interval_minutes,
        recurrence_code: facts.recurrence_code,
    })
}

fn ensure_revision(session: &ReminderSessionInput, expected: &str) -> Result<(), LomoError> {
    if session.memo_revision != expected {
        return Err(crate::error::conflict(
            "stale_snapshot",
            "reminder command holds a stale memo revision",
        ));
    }
    Ok(())
}

fn offset_at_utc(zone: &TimeZoneContext, utc_ms: i64) -> i32 {
    let mut offset = zone.base_offset_secs;
    for transition in &zone.transitions {
        if utc_ms >= transition.transition_utc_ms {
            offset = transition.offset_after_secs;
        }
    }
    offset
}

fn candidate_offsets(zone: &TimeZoneContext) -> Vec<i32> {
    let mut out = vec![zone.base_offset_secs];
    for t in &zone.transitions {
        out.push(t.offset_before_secs);
        out.push(t.offset_after_secs);
    }
    out
}

fn first_valid_after_gap(naive_ms: i64, zone: &TimeZoneContext) -> Result<i64, LomoError> {
    // Prefer the spring-forward transition whose local gap covers the intended wall time.
    for transition in &zone.transitions {
        if transition.offset_after_secs <= transition.offset_before_secs {
            continue; // not a gap (spring forward increases algebraic offset toward UTC for west zones? 
            // EST -18000 → EDT -14400: offset_after > offset_before. Fall: -14400 → -18000.
        }
        // Local time at transition under old offset:
        let local_before = transition
            .transition_utc_ms
            .saturating_add(i64::from(transition.offset_before_secs).saturating_mul(1_000));
        let local_after = transition
            .transition_utc_ms
            .saturating_add(i64::from(transition.offset_after_secs).saturating_mul(1_000));
        // Gap is (local_before, local_after) in local timeline for spring-forward (local time
        // jumps forward). Fall-back overlaps have two valid instants handled by the caller.
        if naive_ms > local_before && naive_ms < local_after {
            return require_zone_coverage(zone, transition.transition_utc_ms);
        }
    }
    Err(reminder_validation(
        "unresolvable_local_time",
        "floating local time has no valid instant in zone context",
    ))
}

fn parse_ymd_hm(text: &str) -> Result<(i32, u32, u32, u32, u32), LomoError> {
    let bytes = text.as_bytes();
    if bytes.len() != 16
        || bytes.get(4).copied() != Some(b'-')
        || bytes.get(7).copied() != Some(b'-')
        || bytes.get(10).copied() != Some(b'-')
        || bytes.get(13).copied() != Some(b':')
    {
        return Err(reminder_validation(
            "invalid_due_at_local",
            "due_at_local must be yyyy-MM-dd-HH:mm",
        ));
    }
    // ASCII digits only after length/separator check — byte indices equal char indices.
    let year = parse_i32(slice_ascii(bytes, 0, 4)?)?;
    let month = parse_u32(slice_ascii(bytes, 5, 7)?)?;
    let day = parse_u32(slice_ascii(bytes, 8, 10)?)?;
    let hour = parse_u32(slice_ascii(bytes, 11, 13)?)?;
    let minute = parse_u32(slice_ascii(bytes, 14, 16)?)?;
    if !(1..=12).contains(&month) || hour > 23 || minute > 59 {
        return Err(reminder_validation(
            "invalid_due_at_local",
            "due_at_local components out of range",
        ));
    }
    let dim = days_in_month(year, month)?;
    if day == 0 || day > dim {
        return Err(reminder_validation(
            "invalid_due_at_local",
            "due_at_local day out of range",
        ));
    }
    Ok((year, month, day, hour, minute))
}

fn parse_u32(text: &str) -> Result<u32, LomoError> {
    text.parse::<u32>()
        .map_err(|_e| reminder_validation("invalid_due_at_local", "due_at_local is not decimal"))
}

fn parse_i32(text: &str) -> Result<i32, LomoError> {
    text.parse::<i32>().map_err(|_e| {
        reminder_validation("invalid_due_at_local", "due_at_local year is not decimal")
    })
}

/// Civil date-time as milliseconds since Unix epoch treating components as UTC (naive).
fn civil_to_epoch_ms(year: i32, month: u32, day: u32, hour: u32, minute: u32) -> i64 {
    let days = days_from_civil(year, month, day);
    let secs = days
        .saturating_mul(86_400)
        .saturating_add(i64::from(hour) * 3_600)
        .saturating_add(i64::from(minute) * 60);
    secs.saturating_mul(1_000)
}

/// Howard Hinnant civil-from-days inverse: days since Unix epoch for Y-M-D.
fn days_from_civil(year: i32, month: u32, day: u32) -> i64 {
    let mut y = i64::from(year);
    let m = i64::from(month);
    let d = i64::from(day);
    y -= i64::from(m <= 2);
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = if m > 2 { m - 3 } else { m + 9 };
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn slice_ascii(bytes: &[u8], start: usize, end: usize) -> Result<&str, LomoError> {
    let slice = bytes.get(start..end).ok_or_else(|| {
        reminder_validation("invalid_due_at_local", "due_at_local slice out of range")
    })?;
    std::str::from_utf8(slice)
        .map_err(|_e| reminder_validation("invalid_due_at_local", "due_at_local slice is not utf8"))
}

fn days_in_month(year: i32, month: u32) -> Result<u32, LomoError> {
    Ok(match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if is_leap(year) => 29,
        2 => 28,
        _ => {
            return Err(reminder_validation(
                "invalid_due_at_local",
                "month out of range",
            ));
        }
    })
}

const fn is_leap(year: i32) -> bool {
    (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
}

const fn map_workspace_err(err: LomoError) -> LomoError {
    err
}

fn reminder_validation(code: &str, diagnostic: &str) -> LomoError {
    match LomoError::from_platform_boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
        None,
        None,
        diagnostic,
    ) {
        Ok(error) | Err(error) => error,
    }
}

fn reminder_storage(code: &str, diagnostic: &str) -> LomoError {
    match LomoError::from_platform_boundary(
        ErrorCategory::Storage,
        code,
        RetryDisposition::Never,
        None,
        None,
        diagnostic,
    ) {
        Ok(error) | Err(error) => error,
    }
}

/// Corrupt snooze state requires an explicit recovery action before scheduling resumes.
fn reminder_recovery_needed(diagnostic: &str) -> LomoError {
    match LomoError::from_platform_boundary(
        ErrorCategory::Corruption,
        "reminder_recovery_needed",
        RetryDisposition::AfterUserAction,
        None,
        None,
        diagnostic,
    ) {
        Ok(error) | Err(error) => error,
    }
}
