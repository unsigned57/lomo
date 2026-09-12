//! Reminder planning and Markdown token advancement through shared memo updates.

use lomo_core::{LomoError, OperationId};
use lomo_store::{
    ReminderCommand, ReminderPlan, ReminderQuery, ReminderSessionInput, SnoozeStore,
    TimeZoneContext, apply_reminder_command, query_reminder_plan,
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
        let query = ReminderQuery {
            now_utc_ms: now,
            zone: zone_context(&self.config.time_zone)?,
            sessions: reminder_sessions(self)?,
            rolling_window: 64,
            workspace_generation: 0,
        };
        query_reminder_plan(&query, &snooze)
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

fn zone_context(zone_id: &str) -> Result<TimeZoneContext, LomoError> {
    Ok(TimeZoneContext {
        zone_id: zone_id.to_owned(),
        base_offset_secs: offset_secs(zone_id)?,
        transitions: Vec::new(),
    })
}

fn offset_secs(zone_id: &str) -> Result<i32, LomoError> {
    if zone_id == "UTC" || zone_id == "Etc/UTC" {
        return Ok(0);
    }
    let zone = jiff::tz::TimeZone::get(zone_id)
        .map_err(|error| validation("unknown_time_zone", error.to_string()))?;
    if zone.is_unknown() {
        return Err(validation(
            "unknown_time_zone",
            format!("IANA timezone is unknown: {zone_id}"),
        ));
    }
    Ok(jiff::Timestamp::now().to_zoned(zone).offset().seconds())
}
