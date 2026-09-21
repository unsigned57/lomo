//! Reminder planning and Markdown token advancement through shared memo updates.

use lomo_core::{LomoError, OperationId};
use lomo_store::{
    REMINDER_ROLLING_WINDOW, ReminderCommand, ReminderPlan, ReminderQuery, ReminderSessionInput,
    SnoozeStore, TimeZoneContext, apply_reminder_command, query_reminder_plan,
};
use lomo_workspace::{MemoId, ReminderReference};
use serde::{Deserialize, Serialize};

use crate::{
    error::validation,
    paging::{collect_summaries, default_query},
    session::WorkspaceSession,
    types::{UpdateMemoRequest, UpdateMemoResult},
    workspace_io::epoch_millis,
};

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct FireReminderRequest {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
    pub opaque_id: String,
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
            zone: zone_context(&self.config.time_zone, now, &sessions)?,
            sessions,
            rolling_window: REMINDER_ROLLING_WINDOW,
            workspace_generation: self.config.workspace_generation.as_str().to_owned(),
        };
        query_reminder_plan(&query, &snooze)
    }

    /// Writes a durable app-private snooze binding (no Markdown rewrite). The platform supplies a
    /// validated duration; the deadline instant is computed from the owner clock here.
    ///
    /// # Errors
    /// Validation for non-positive durations; snooze storage, recovery-pending, or entry-budget
    /// failures.
    pub fn snooze_reminder(
        &self,
        opaque_id: &str,
        snooze_duration_ms: i64,
    ) -> Result<(), LomoError> {
        if snooze_duration_ms <= 0 {
            return Err(validation(
                "invalid_snooze_duration",
                "snooze duration must be a positive millisecond count",
            ));
        }
        let snooze_until_utc_ms = epoch_millis()?.saturating_add(snooze_duration_ms);
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

    /// Clears a durable app-private snooze binding.
    ///
    /// # Errors
    /// Snooze storage or recovery-pending failures.
    pub fn clear_reminder_snooze(&self, opaque_id: &str) -> Result<(), LomoError> {
        let mut snooze = SnoozeStore::open_app_private(&self.config.state_dir)?;
        apply_reminder_command(
            &ReminderCommand::ClearSnooze {
                opaque_id: opaque_id.to_owned(),
                workspace_generation: self.config.workspace_generation.as_str().to_owned(),
            },
            &mut snooze,
        )?;
        Ok(())
    }

    /// True when durable snooze state is quarantined and scheduling is paused pending
    /// [`Self::recover_reminder_snooze`].
    ///
    /// # Errors
    /// Storage failures opening the snooze directory.
    pub fn reminder_snooze_recovery_pending(&self) -> Result<bool, LomoError> {
        Ok(SnoozeStore::open_app_private(&self.config.state_dir)?.recovery_pending())
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

    /// Advances one reminder token (`.1`, `.done`, next due) without rewriting other bytes.
    ///
    /// # Errors
    /// Missing reminders, token planning, and shared write failures.
    pub fn record_reminder_fired(
        &self,
        request: FireReminderRequest,
    ) -> Result<UpdateMemoResult, LomoError> {
        let current = self.current_memo(&request.memo_id)?;
        let reminder = current
            .summary
            .reminders
            .iter()
            .find(|item| item.opaque_id == request.opaque_id)
            .cloned()
            .ok_or_else(|| validation("reminder_missing", "memo has no matching reminder"))?;
        let mut snooze = SnoozeStore::open_app_private(&self.config.state_dir)?;
        let planned = apply_reminder_command(
            &ReminderCommand::RecordFired {
                session: session_input(&reminder),
                expected_revision: reminder.revision.clone(),
            },
            &mut snooze,
        )?;
        let replacement = planned.replacement_token.ok_or_else(|| {
            validation("reminder_token_missing", "record-fired must rewrite token")
        })?;
        if !current.body.contains(&reminder.token) {
            return Err(validation(
                "reminder_token_missing",
                "reminder token is not present in the memo body",
            ));
        }
        self.update_memo(UpdateMemoRequest {
            operation_id: request.operation_id,
            memo_id: request.memo_id,
            content: current.body.replacen(&reminder.token, &replacement, 1),
            expected_document_fingerprint: current.summary.file_fingerprint,
            pending_promotes: Vec::new(),
        })
    }
}

fn reminder_sessions(session: &WorkspaceSession) -> Result<Vec<ReminderSessionInput>, LomoError> {
    let mut sessions = Vec::new();
    for summary in collect_summaries(session, &default_query())? {
        for reminder in summary.reminders {
            sessions.push(session_input(&reminder));
        }
    }
    Ok(sessions)
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
        recurrence_code: reminder.recurrence_code.clone(),
    }
}

/// Zone transition coverage generated around `now` for reminder planning. The demand-driven
/// bounds below extend it to every wall instant the planner can resolve: each session's pending
/// `due_at_local`, its accumulated repeat firings, and one further repeat/recurrence step.
const ZONE_COVERAGE_LOOKBACK_MS: i64 = 370 * 86_400_000;
const ZONE_COVERAGE_LOOKAHEAD_MS: i64 = 370 * 86_400_000;
/// Slack beyond the naive wall estimate covering the largest possible UTC offset (±14h) and DST
/// shifts when converting the coverage estimate.
const ZONE_COVERAGE_MARGIN_MS: i64 = 2 * 86_400_000;
/// Largest single recurrence step the token grammar permits (`w` = 7 days), plus slack.
const ZONE_RECURRENCE_STEP_MAX_MS: i64 = 8 * 86_400_000;
const MAX_ZONE_TRANSITIONS: usize = 32;

fn zone_context(
    zone_id: &str,
    now_utc_ms: i64,
    sessions: &[ReminderSessionInput],
) -> Result<TimeZoneContext, LomoError> {
    let zone = time_zone(zone_id)?;
    let mut coverage_start = now_utc_ms.saturating_sub(ZONE_COVERAGE_LOOKBACK_MS);
    let mut coverage_end = now_utc_ms.saturating_add(ZONE_COVERAGE_LOOKAHEAD_MS);
    for session in sessions {
        let due_est = lomo_store::naive_local_epoch_ms(&session.due_at_local)?;
        coverage_start = coverage_start.min(due_est.saturating_sub(ZONE_COVERAGE_MARGIN_MS));
        let repeat_span = i64::from(session.fired_count)
            .saturating_add(1)
            .saturating_mul(i64::from(session.interval_minutes))
            .saturating_mul(60_000);
        let demand_end = due_est
            .saturating_add(repeat_span)
            .saturating_add(ZONE_RECURRENCE_STEP_MAX_MS)
            .saturating_add(ZONE_COVERAGE_MARGIN_MS);
        coverage_end = coverage_end.max(demand_end);
    }
    let start = jiff::Timestamp::from_millisecond(coverage_start)
        .map_err(|error| validation("zone_coverage_invalid", error.to_string()))?;
    let end = jiff::Timestamp::from_millisecond(coverage_end)
        .map_err(|error| validation("zone_coverage_invalid", error.to_string()))?;
    let base_offset_secs = zone.to_offset(start).seconds();
    let mut transitions = Vec::new();
    let mut offset_before = base_offset_secs;
    for transition in zone.following(start) {
        let timestamp = transition.timestamp();
        if timestamp > end {
            break;
        }
        if transitions.len() >= MAX_ZONE_TRANSITIONS {
            // The next real transition is unrecorded, so the last recorded offset is not
            // authoritative past it. Clamp coverage to just before that instant (fail closed).
            coverage_end = timestamp.as_millisecond().saturating_sub(1);
            break;
        }
        let offset_after = transition.offset().seconds();
        transitions.push(lomo_store::ZoneTransition {
            transition_utc_ms: timestamp.as_millisecond(),
            offset_before_secs: offset_before,
            offset_after_secs: offset_after,
        });
        offset_before = offset_after;
    }
    Ok(TimeZoneContext {
        zone_id: zone_id.to_owned(),
        base_offset_secs,
        transitions,
        coverage_start_utc_ms: coverage_start,
        coverage_end_utc_ms: coverage_end,
    })
}

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
