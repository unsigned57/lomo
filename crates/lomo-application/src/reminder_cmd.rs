//! Reminder planning and Markdown token advancement through shared memo updates.

use lomo_core::LomoError;
use lomo_store::{
    REMINDER_ROLLING_WINDOW, ReminderCommand, ReminderPlan, ReminderQuery, ReminderSessionInput,
    SnoozeStore, apply_reminder_command, query_reminder_plan,
};
use lomo_workspace::ReminderReference;

use crate::{
    error::{storage, validation},
    paging::{NEVER_CANCELLED, collect_summaries, default_query},
    session::WorkspaceSession,
    workspace_io::epoch_millis,
};

/// Snooze option vocabulary the platform may offer and apply — the complete value domain
/// for durable snooze durations (B-05/P-25, R-08).
///
/// A snooze duration is never an arbitrary millisecond count: the owner option set decides
/// which intervals exist, and every FFI/UI surface passes the enum rather than re-encoding
/// or re-validating milliseconds.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SnoozeInterval {
    OneMinute,
    ThreeMinutes,
    FiveMinutes,
    FifteenMinutes,
    ThirtyMinutes,
    SixtyMinutes,
}

impl SnoozeInterval {
    /// The complete option set, declaration order — the only legal snooze durations.
    pub const ALL: [Self; 6] = [
        Self::OneMinute,
        Self::ThreeMinutes,
        Self::FiveMinutes,
        Self::FifteenMinutes,
        Self::ThirtyMinutes,
        Self::SixtyMinutes,
    ];

    /// Interval applied when the platform holds no stored preference.
    pub const DEFAULT: Self = Self::FiveMinutes;

    /// Duration in whole milliseconds.
    #[must_use]
    pub const fn millis(self) -> i64 {
        match self {
            Self::OneMinute => 60_000,
            Self::ThreeMinutes => 180_000,
            Self::FiveMinutes => 300_000,
            Self::FifteenMinutes => 900_000,
            Self::ThirtyMinutes => 1_800_000,
            Self::SixtyMinutes => 3_600_000,
        }
    }
}

impl WorkspaceSession {
    /// Plans process-period reminder alarms from projected Markdown tokens.
    ///
    /// # Errors
    /// Zone resolution, token, and projection failures.
    pub fn reminder_plan(&self, now_utc_ms: Option<i64>) -> Result<ReminderPlan, LomoError> {
        let now = match now_utc_ms {
            Some(value) => value,
            None => epoch_millis()?,
        };
        let snooze = SnoozeStore::open_app_private(&self.config.state_dir)?;
        let sessions = reminder_sessions(self)?;
        let query = ReminderQuery {
            now_utc_ms: now,
            zone: time_zone(&self.config.time_zone)?,
            sessions,
            rolling_window: REMINDER_ROLLING_WINDOW,
            workspace_generation: self.config.workspace_generation.as_str().to_owned(),
        };
        query_reminder_plan(&query, &snooze)
    }

    /// Writes a durable app-private snooze binding (no Markdown rewrite). The platform passes a
    /// [`SnoozeInterval`] owner option; the deadline instant is computed from the owner clock
    /// here so durations outside the vocabulary are unrepresentable.
    ///
    /// # Errors
    /// Snooze storage, recovery-pending, or entry-budget failures.
    pub fn snooze_reminder(
        &self,
        opaque_id: &str,
        interval: SnoozeInterval,
    ) -> Result<(), LomoError> {
        let snooze_until_utc_ms = epoch_millis()?.saturating_add(interval.millis());
        let mut snooze = SnoozeStore::open_app_private(&self.config.state_dir)?;
        apply_reminder_command(
            &ReminderCommand::Snooze {
                opaque_id: opaque_id.to_owned(),
                workspace_generation: self.config.workspace_generation.as_str().to_owned(),
                snooze_until_utc_ms,
            },
            &mut snooze,
        )?;
        Ok(())
    }

    /// Explicitly recovers corrupt durable snooze state: quarantines the unreadable payload and
    /// persists a fresh empty store. Never invoked implicitly by planning.
    ///
    /// # Errors
    /// Storage failures opening/quarantining/persisting snooze state.
    pub fn recover_reminder_snooze(&self) -> Result<(), LomoError> {
        SnoozeStore::recover_app_private(&self.config.state_dir)?;
        Ok(())
    }
}

/// Reminder sessions are materialized projection fields — a pure function of
/// the same summary walk tasks and review consume. The extracted set is keyed
/// by the walk's own revision, so re-planning without a commit reuses it and a
/// committed write invalidates by key.
fn reminder_sessions(session: &WorkspaceSession) -> Result<Vec<ReminderSessionInput>, LomoError> {
    let revision = session.with_store(|store| Ok(store.high_water_revision()))?;
    let cached = session
        .query_projection
        .lock()
        .map_err(|error| storage("query_projection_poisoned", error.to_string()))?
        .reminder_sessions_at(revision);
    if let Some(sessions) = cached {
        return Ok(sessions.to_vec());
    }

    let walk = collect_summaries(session, &default_query(), NEVER_CANCELLED)?;
    let mut sessions = Vec::new();
    for summary in walk.items.iter() {
        for reminder in &summary.reminders {
            sessions.push(session_input(reminder));
        }
    }
    // Extraction is pure — no reads happen between collect and here — so the
    // set is exactly `walk.revision`'s; the entry keys by that revision, never
    // the probe read (a commit between the two would otherwise poison the key).
    let sessions: std::sync::Arc<[ReminderSessionInput]> = std::sync::Arc::from(sessions);
    session
        .query_projection
        .lock()
        .map_err(|error| storage("query_projection_poisoned", error.to_string()))?
        .record_reminder_sessions(walk.revision, std::sync::Arc::clone(&sessions));
    Ok(sessions.to_vec())
}

fn session_input(reminder: &ReminderReference) -> ReminderSessionInput {
    ReminderSessionInput {
        opaque_id: reminder.opaque_id.clone(),
        memo_identity: reminder.memo_identity.clone(),
        memo_revision: reminder.revision.clone(),
        token: reminder.token.clone(),
        due_at_local: reminder.due_at_local.clone(),
        repeat_count: reminder.repeat_count,
        fired_count: reminder.fired_count,
        done: reminder.done,
        interval_minutes: reminder.interval_minutes,
        recurrence: reminder.recurrence,
    }
}

/// Resolves the configured IANA zone id. The full bundled zone database is handed to the
/// reminder owner — resolution coverage is the zone's entire history, so no platform-supplied
/// transition window or coverage bound is constructed here.
fn time_zone(zone_id: &str) -> Result<jiff::tz::TimeZone, LomoError> {
    if zone_id == "UTC" || zone_id == "Etc/UTC" {
        return Ok(jiff::tz::TimeZone::UTC);
    }
    let zone = jiff::tz::TimeZone::get(zone_id)
        .map_err(|error| validation("unknown_time_zone", error.to_string()))?;
    if zone.is_unknown() {
        return Err(validation(
            "unknown_time_zone",
            format!("IANA timezone is unknown: {zone_id}"),
        ));
    }
    Ok(zone)
}
