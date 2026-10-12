//! Reminder business state owner (P3-07).
//!
//! Owns next-trigger planning, DST floating-local policy, catch-up storm prevention,
//! mark-done/record-fired token mutation planning, and app-private snooze binding.
//! Canonical fired/done/recurrence rewrite targets Markdown via planned tokens; snooze never
//! rewrites the body and never enters `.lomo`/sync/archive.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use jiff::tz::{AmbiguousOffset, TimeZone};
use lomo_core::{ErrorCategory, InvalidationScope, LomoError, RetryDisposition};
use lomo_workspace::{
    ReminderRecurrence, ReminderTokenMutation, plan_reminder_token_mutation, reminder_token_facts,
};
use serde::{Deserialize, Serialize};

/// Number of reminder alarms the rolling window emits per plan.
///
/// The window refills on the next plan query after an occurrence fires or is cancelled; alarms
/// beyond the window are reported via [`ReminderPlan::dropped_count`] rather than silently
/// discarded.
pub const REMINDER_ROLLING_WINDOW: usize = 64;

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
    /// Owner recurrence vocabulary; serialized as the grammar code so payloads keep the
    /// string shape while planning compares a closed enum.
    #[serde(rename = "recurrence_code")]
    pub recurrence: ReminderRecurrence,
}

/// Query for a rolling-window reminder plan.
#[derive(Debug, Clone)]
pub struct ReminderQuery {
    pub now_utc_ms: i64,
    /// Zone the floating `due_at_local` walls are resolved against. The full IANA database
    /// entry (not a platform-supplied transition slice) so resolution coverage is the zone's
    /// entire history, not a bounded window.
    pub zone: TimeZone,
    pub sessions: Vec<ReminderSessionInput>,
    /// Maximum alarms to emit across all sessions (rolling window).
    pub rolling_window: usize,
    /// Durable workspace generation (`WorkspaceGenerationId` hex) the plan is issued under.
    pub workspace_generation: String,
}

/// One platform alarm the adapter must schedule.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlannedAlarm {
    /// Identity of this single firing:
    /// `{generation}\u{1f}{opaque_id}\u{1f}{occurrence instant}` where the occurrence instant
    /// is the scheduled trigger for future alarms and the *missed* trigger instant for
    /// catch-ups, so re-planning an overdue moment reproduces the same identity. Distinct
    /// from the definition identity so cancellation/dedup never relies on a process cache or
    /// a bare 32-bit request-code hash.
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
            // behavior-contract: identity-mint-ok: this file owns the snooze namespace; the
            // stamp is a quarantine-filename uniqueness suffix minted by its owner, never a
            // generation/fence/identity claim.
            let stamp = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_err(|_error| {
                    reminder_storage(
                        "snooze_quarantine_clock_failed",
                        "system clock is before the unix epoch",
                    )
                })?
                .as_millis();
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
            // The budget bounds live bindings only. An entry whose deadline passed can never
            // suppress or emit an alarm again, so capacity pressure reclaims it instead of
            // turning lifetime snooze count into a durable refusal.
            let now = wall_now_utc_ms()?;
            self.entries.retain(|_, until| *until > now);
            if self.entries.len() >= MAX_SNOOZE_ENTRIES {
                return Err(reminder_validation(
                    "snooze_budget_exhausted",
                    "snooze entry budget exhausted",
                ));
            }
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
    path.components()
        .any(|c| c.as_os_str() == lomo_workspace::LOMO_DIR_NAME)
}

/// Owner wall clock used to judge snooze-binding liveness when reclaiming expired entries.
fn wall_now_utc_ms() -> Result<i64, LomoError> {
    let millis = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|_error| {
            reminder_storage(
                "snooze_clock_failed",
                "system clock is before the unix epoch",
            )
        })?
        .as_millis();
    i64::try_from(millis)
        .map_err(|_error| reminder_storage("snooze_clock_failed", "system clock exceeds i64 ms"))
}

/// Resolves floating local wall time (`yyyy-MM-dd-HH:mm`) to a UTC instant.
///
/// Policy:
/// - **DST gap** → first valid instant after the gap (the transition instant itself).
/// - **DST overlap** → earlier (first) instant.
///
/// All civil-calendar and DST mathematics is delegated to jiff's bundled zone database; the
/// owner layer only parses the strict grammar and applies the ambiguity policy.
///
/// # Errors
///
/// Validation when the local wall string is malformed or has no valid instant in the zone.
pub fn resolve_floating_local_to_utc_ms(
    due_at_local: &str,
    zone: &TimeZone,
) -> Result<i64, LomoError> {
    let wall = parse_ymd_hm(due_at_local)?;
    match ambiguity_policy(zone, wall) {
        GapPolicy::Compatible(timestamp) => Ok(timestamp.as_millisecond()),
        GapPolicy::Reject => Err(reminder_validation(
            "unresolvable_local_time",
            "floating local time has no valid instant in zone",
        )),
    }
}

/// Owner decision for a floating-local wall that does not map to exactly one instant.
///
/// `Compatible` resolves under the established reminder policy — a gap clamps to the first
/// valid instant after it (the transition instant) and a fold selects the earlier instant —
/// carrying the resolved timestamp. `Reject` means the wall cannot resolve to any instant and
/// the caller fails closed rather than guessing an offset.
enum GapPolicy {
    /// Instant resolved under the compatible reminder policy.
    Compatible(jiff::Timestamp),
    /// The wall has no valid instant in this zone.
    Reject,
}

/// Maps jiff's ambiguity classification onto the owner [`GapPolicy`]. This adapter is the only
/// DST-policy surface in the crate; the underlying offset/transition lookup is jiff's.
fn ambiguity_policy(zone: &TimeZone, wall: jiff::civil::DateTime) -> GapPolicy {
    let ambiguous = zone.to_ambiguous_timestamp(wall);
    match ambiguous.offset() {
        AmbiguousOffset::Gap { after, .. } => {
            // jiff's `earlier` resolves the wall under the post-gap offset, which lands strictly
            // before the transition that closes the gap. Walk forward to the record that
            // actually installs the post-gap offset — its instant is the first valid instant
            // after the gap. Skipping intervening records matters: tzif files contain
            // offset-preserving rule records that are not the gap boundary. This also covers
            // the exact gap-start wall, which the legacy scan excluded through a strict
            // lower-bound comparison.
            let Ok(before_gap) = ambiguous.earlier() else {
                return GapPolicy::Reject;
            };
            zone.following(before_gap)
                .find(|transition| transition.offset() == after)
                .map_or(GapPolicy::Reject, |transition| {
                    GapPolicy::Compatible(transition.timestamp())
                })
        }
        // Unambiguous resolves trivially.
        AmbiguousOffset::Unambiguous { .. } => ambiguous
            .earlier()
            .map_or(GapPolicy::Reject, GapPolicy::Compatible),
        AmbiguousOffset::Fold { .. } => {
            // Fold → the earlier occurrence. The tzif record pair jiff reports is exact in
            // the normal case, but zones whose offset regime was skipped mid-history can name
            // a fold whose earlier "occurrence" has an offset that was never in force
            // (America/Godthab 2023: permanent -02 was adopted in March, yet the October
            // record pair still implies {-01,-02}). Verify the earlier instant actually
            // displays the wall; the only real occurrence is then the later one.
            if let Ok(timestamp) = ambiguous.earlier()
                && displays_wall(zone, timestamp, wall)
            {
                return GapPolicy::Compatible(timestamp);
            }
            match ambiguous.later() {
                Ok(timestamp) if displays_wall(zone, timestamp, wall) => {
                    GapPolicy::Compatible(timestamp)
                }
                _ => GapPolicy::Reject,
            }
        }
    }
}

/// Whether `ts` displays `wall` under the offset actually in force at `ts` — a round-trip
/// check that filters out phantom ambiguity occurrences (see the Fold arm above).
fn displays_wall(zone: &TimeZone, ts: jiff::Timestamp, wall: jiff::civil::DateTime) -> bool {
    ts.to_zoned(zone.clone()).datetime() == wall
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
        if session.done || session.fired_count >= session.repeat_count {
            // Terminal either way: an explicit `.done`, or a fully consumed count that the
            // strict grammar still accepts without the flag. Neither may emit an alarm, and
            // neither may consult a live snooze binding left behind for the definition.
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
    zone: &TimeZone,
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
    zone: &TimeZone,
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
        || facts.recurrence != session.recurrence
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
        if working.done || working.fired_count >= working.repeat_count {
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
                // The occurrence identity anchors to the missed trigger instant, not the plan
                // clock: re-planning the same overdue moment must reproduce the same identity
                // so the platform ledger does not cancel/reschedule the PendingIntent.
                occurrence_id: occurrence_id(workspace_generation, &session.opaque_id, trigger),
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
        recurrence: facts.recurrence,
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

/// Strict `yyyy-MM-dd-HH:mm` grammar gate → jiff civil datetime.
///
/// The owner grammar owns the shape (width, separators, decimal components). Component ranges,
/// month lengths, and leap days are validated by jiff's checked [`civil::DateTime`](jiff::civil::DateTime)
/// constructor — no hand-rolled calendar table remains in this crate.
fn parse_ymd_hm(text: &str) -> Result<jiff::civil::DateTime, LomoError> {
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
    // Fixed-width grammar keeps byte indices equal to char indices.
    let year = slice_ascii(bytes, 0, 4)?.parse::<i16>().map_err(|_e| {
        reminder_validation("invalid_due_at_local", "due_at_local year is not decimal")
    })?;
    let component = |start: usize, end: usize| -> Result<i8, LomoError> {
        slice_ascii(bytes, start, end)?.parse::<i8>().map_err(|_e| {
            reminder_validation("invalid_due_at_local", "due_at_local is not decimal")
        })
    };
    jiff::civil::DateTime::new(
        year,
        component(5, 7)?,
        component(8, 10)?,
        component(11, 13)?,
        component(14, 16)?,
        0,
        0,
    )
    .map_err(|_e| {
        reminder_validation(
            "invalid_due_at_local",
            "due_at_local components out of range",
        )
    })
}

fn slice_ascii(bytes: &[u8], start: usize, end: usize) -> Result<&str, LomoError> {
    let slice = bytes.get(start..end).ok_or_else(|| {
        reminder_validation("invalid_due_at_local", "due_at_local slice out of range")
    })?;
    std::str::from_utf8(slice)
        .map_err(|_e| reminder_validation("invalid_due_at_local", "due_at_local slice is not utf8"))
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
