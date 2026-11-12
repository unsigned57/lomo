//! Reminder planning and Markdown token advancement through shared memo updates.

use lomo_core::{LomoError, RelativeWorkspacePath};
use lomo_store::{
    REMINDER_ROLLING_WINDOW, ReminderCommand, ReminderPlan, ReminderQuery, ReminderSessionInput,
    SnoozeStore, apply_reminder_command, query_reminder_plan,
};
use lomo_workspace::{
    DocumentPatchCommand, MemoId, ReminderReference, parse_workspace_document, plan_document_patch,
};

use crate::{
    document_plan::{LoadedDocument, filename_stem},
    error::{storage, validation},
    session::WorkspaceSession,
    workspace_io::epoch_millis,
};

/// Validated reminder edit, committed by the ordinary session update transaction.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ReminderRewritePlan {
    pub memo_id: String,
    pub expected_revision: u64,
    pub expected_fingerprint: String,
    pub content: String,
}

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
    /// Plans a token edit without writing Markdown, identity records, history or the projection.
    /// The caller submits the returned baseline/content through the normal memo update command.
    ///
    /// # Errors
    /// Missing/stale memo or reminder facts, invalid replacement tokens, or source failures.
    pub fn plan_reminder_rewrite(
        &self,
        memo_id: &MemoId,
        reminder_id: &str,
        expected_token: &str,
        replacement: &str,
    ) -> Result<ReminderRewritePlan, LomoError> {
        let current = self.current_memo(memo_id)?;
        let path = RelativeWorkspacePath::parse(&current.summary.source_path)?;
        let loaded = LoadedDocument::load(
            &self.io(),
            path.clone(),
            crate::document_plan::DocumentRead::ActiveMemo,
        )?;
        if loaded.document.source().fingerprint().as_str() != current.summary.file_fingerprint {
            return Err(crate::error::conflict(
                "stale_snapshot",
                "reminder source changed after projection",
            ));
        }
        let memo = loaded.memo(memo_id)?;
        let reference = memo
            .reminders()
            .iter()
            .find(|item| item.opaque_id() == reminder_id)
            .ok_or_else(|| {
                crate::error::conflict(
                    "stale_snapshot",
                    "reminder is no longer present in the memo",
                )
            })?;
        if reference.token() != expected_token {
            return Err(crate::error::conflict(
                "stale_snapshot",
                "reminder token changed before rewrite planning",
            ));
        }
        let edit = plan_document_patch(
            &loaded.document,
            &DocumentPatchCommand::RewriteReminder {
                path: loaded.logical_path.clone(),
                reminder: reference.clone(),
                replacement: replacement.to_owned(),
            },
        )?;
        let changed = parse_workspace_document(edit.result_source(), filename_stem(&path)?)?;
        let updated = changed
            .memos()
            .iter()
            .find(|item| item.identity() == memo.identity())
            .ok_or_else(|| {
                validation(
                    "reminder_target_not_found",
                    "reminder edit changed its memo address",
                )
            })?;
        Ok(ReminderRewritePlan {
            memo_id: memo_id.as_str().to_owned(),
            expected_revision: current.summary.content_revision,
            expected_fingerprint: current.summary.file_fingerprint,
            content: updated.content().to_owned(),
        })
    }

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

/// Reminder sessions come from the indexed active reminder partition, keyed by its
/// own SQLite snapshot revision. Unrelated memos are never hydrated or traversed.
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

    let (revision, projections) =
        session.with_reader(lomo_store::StoreReader::reminder_projections)?;
    let mut sessions = Vec::new();
    for summary in &projections {
        for reminder in &summary.reminders {
            sessions.push(session_input(&summary.memo_id, reminder));
        }
    }
    // Cache under the revision captured with the facts, never the earlier probe.
    let sessions: std::sync::Arc<[ReminderSessionInput]> = std::sync::Arc::from(sessions);
    session
        .query_projection
        .lock()
        .map_err(|error| storage("query_projection_poisoned", error.to_string()))?
        .record_reminder_sessions(revision, std::sync::Arc::clone(&sessions));
    Ok(sessions.to_vec())
}

fn session_input(memo_id: &str, reminder: &ReminderReference) -> ReminderSessionInput {
    ReminderSessionInput {
        opaque_id: reminder.opaque_id.clone(),
        // Alarm consumers query the durable projection key. The reference's memo_identity
        // is a source locator for Markdown patches and cannot address that projection.
        memo_identity: memo_id.to_owned(),
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
