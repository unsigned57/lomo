//! Durable sync-cycle record (`cycle_state.rec`) and cancel request (`cancel_request.rec`).
//!
//! Invariant: every production composed cycle leaves a durable record under `.lomo/sync/v1`.
//! The record is the sole authority for sync status observed by hosts — enqueue receipts and
//! in-memory flows are never authoritative. A record left in [`SyncCyclePhase::Running`] only
//! tells the truth while its writer lives; the next [`begin_sync_cycle`] repairs it to
//! `Failed(sync_cycle_interrupted)` so process death never silently drops the last result.
//!
//! Cancellation is a durable fact (`cancel_request.rec` bound to `cycle_id`) checked between
//! publication pages — after the cancel point no unauthorized publish may proceed.
//!
//! `state_stamp` is a strictly monotonic per-workspace marker; hosts drop late writes whose
//! stamp is older than the one already observed.

use std::fs;

use serde::{Deserialize, Serialize};

use crate::durable::SyncSession;
use crate::durable::{SyncPaths, read_sync_record, write_sync_record_atomic};
use crate::error::{corrupt_state, storage, validation};
use crate::limits::{MAX_DURABLE_RECORD_BYTES, SYNC_DURABLE_SCHEMA};
use crate::machine::{SyncBackendKind, SyncCyclePlanSummary};
use lomo_core::LomoError;

/// Terminal/running phase of one durable cycle record (wire: `snake_case`).
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SyncCyclePhase {
    /// Cycle accepted and executing (planning / applying). Truthful only while writer lives.
    #[default]
    Running,
    /// Cycle finished; disposition tells whether follow-up user action is needed.
    Completed,
    /// Cycle terminated by an owner error (code + message persisted).
    Failed,
    /// Cycle terminated by a durable cancel request; `pages_applied` is the cancellation point.
    Cancelled,
}

/// One durable cycle record (`cycle_state.rec`).
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
pub struct SyncCycleRecord {
    pub schema: u32,
    /// Monotonic cycle sequence for this workspace (survives restarts).
    pub cycle_seq: u64,
    /// Stable identity of this cycle (`cycle-<seq>`).
    pub cycle_id: String,
    /// Identity fence the cycle ran under (`generation|dataset|remote-identity`).
    pub fence_key: String,
    /// `hermetic_fake` | `webdav` | `s3` | `git`.
    pub backend_kind: String,
    pub session_id: String,
    /// True when the cycle was authorized to publish remote mutations.
    pub apply_remote: bool,
    pub phase: SyncCyclePhase,
    /// Execution stage the record was last persisted at (`planning` | `applying` | `finished`).
    pub stage: String,
    pub ensure_present_count: u32,
    pub ensure_absent_count: u32,
    pub pull_present_count: u32,
    pub open_conflict_count: u32,
    pub hold_count: u32,
    /// Local projection entries the cycle observed.
    pub local_entry_count: u32,
    /// Remote listing entries the cycle observed across all pages.
    pub remote_listed_count: u32,
    /// Baseline entries present when the cycle ran.
    pub baseline_entry_count: u32,
    /// Intent pages published + verified before terminal (cancellation point on `cancelled`).
    pub pages_applied: u32,
    pub baseline_advanced: bool,
    /// `never` | `after_user_action` | `transient`; empty while running.
    pub retry_disposition: String,
    pub failure_code: Option<String>,
    pub failure_message: Option<String>,
    /// Durable cancel request was observed for this cycle.
    pub cancel_requested: bool,
    pub started_at_ms: i64,
    pub updated_at_ms: i64,
    pub finished_at_ms: Option<i64>,
    /// Sticky: last apply-cycle that completed without a transient/failure outcome.
    pub last_successful_at_ms: Option<i64>,
    /// Strictly monotonic freshness marker (epoch for host-side late-result rejection).
    pub state_stamp: u64,
}

/// Durable cancel request bound to one cycle id (`cancel_request.rec`).
///
/// Deserialization shares [`SyncCancelRequest::validate`]: an empty cycle binding or non-positive
/// timestamp cannot become a durable cancel fact. The `schema` field stays reader-checked
/// (`sync_unknown_schema`) because it is envelope version negotiation, not field validity.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(try_from = "SyncCancelRequestJson")]
pub struct SyncCancelRequest {
    pub schema: u32,
    pub cycle_id: String,
    pub requested_at_ms: i64,
}

#[derive(Deserialize)]
struct SyncCancelRequestJson {
    schema: u32,
    cycle_id: String,
    requested_at_ms: i64,
}

impl TryFrom<SyncCancelRequestJson> for SyncCancelRequest {
    type Error = LomoError;

    fn try_from(json: SyncCancelRequestJson) -> Result<Self, LomoError> {
        let request = Self {
            schema: json.schema,
            cycle_id: json.cycle_id,
            requested_at_ms: json.requested_at_ms,
        };
        request.validate()?;
        Ok(request)
    }
}

impl SyncCancelRequest {
    /// Validates the durable cancel request fields.
    ///
    /// # Errors
    ///
    /// Corruption when `cycle_id` is empty or `requested_at_ms` is non-positive.
    pub fn validate(&self) -> Result<(), LomoError> {
        if self.cycle_id.is_empty() || self.requested_at_ms <= 0 {
            return Err(corrupt_state(
                "cancel_request_payload_invalid",
                "cancel request requires a non-empty cycle id and a positive timestamp",
            ));
        }
        Ok(())
    }
}

/// Error code emitted when a durable cancel request aborts publication between pages.
pub const CYCLE_CANCELLED_CODE: &str = "sync_cycle_cancelled";

/// Error code persisted when a `Running` record is repaired after writer death.
pub const CYCLE_INTERRUPTED_CODE: &str = "sync_cycle_interrupted";

/// Wire name for `lomo_core::RetryDisposition` (`never` | `after_user_action` | `transient`).
const fn retry_disposition_name(disposition: lomo_core::RetryDisposition) -> &'static str {
    match disposition {
        lomo_core::RetryDisposition::Never => "never",
        lomo_core::RetryDisposition::AfterUserAction => "after_user_action",
        lomo_core::RetryDisposition::Transient => "transient",
    }
}

fn now_unix_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |duration| {
            i64::try_from(duration.as_millis()).unwrap_or(i64::MAX)
        })
}

fn encode_record<T: Serialize>(code: &'static str, value: &T) -> Result<String, LomoError> {
    serde_json::to_string(value).map_err(|err| {
        corrupt_state(
            code,
            &format!("cannot serialize sync record payload: {err}"),
        )
    })
}

fn decode_record<'a, T: Deserialize<'a>>(
    code: &'static str,
    body: &'a str,
) -> Result<T, LomoError> {
    serde_json::from_str(body)
        .map_err(|err| corrupt_state(code, &format!("cannot decode sync record payload: {err}")))
}

/// Loads the current cycle record; missing file → `None` (not corruption).
///
/// # Errors
///
/// Corruption when present bytes fail decode; storage on read failure.
pub fn read_cycle_state(paths: &SyncPaths) -> Result<Option<SyncCycleRecord>, LomoError> {
    if !paths.cycle_state.exists() {
        return Ok(None);
    }
    let (_schema, body) = read_sync_record(&paths.cycle_state)?;
    let record: SyncCycleRecord = decode_record("cycle_state_payload_invalid", &body)?;
    if record.schema != SYNC_DURABLE_SCHEMA {
        return Err(corrupt_state(
            "sync_unknown_schema",
            "cycle state schema does not match SYNC_DURABLE_SCHEMA",
        ));
    }
    Ok(Some(record))
}

/// Persists a cycle record (framed atomic write).
///
/// # Errors
///
/// Encoding / storage / record-size errors.
pub fn write_cycle_state(paths: &SyncPaths, record: &SyncCycleRecord) -> Result<(), LomoError> {
    paths.ensure_layout()?;
    let body = encode_record("cycle_state_encode_failed", record)?;
    if body.len() > MAX_DURABLE_RECORD_BYTES {
        return Err(crate::error::resource_limit(
            "cycle_state_record_too_large",
            "cycle state record exceeds the durable record byte limit",
        ));
    }
    write_sync_record_atomic(&paths.cycle_state, SYNC_DURABLE_SCHEMA, &body)
}

/// Reads the cancel request file; missing → `None`.
///
/// # Errors
///
/// Corruption when present bytes fail decode; storage on read failure.
pub fn read_cancel_request(paths: &SyncPaths) -> Result<Option<SyncCancelRequest>, LomoError> {
    if !paths.cycle_cancel.exists() {
        return Ok(None);
    }
    let (_schema, body) = read_sync_record(&paths.cycle_cancel)?;
    let request: SyncCancelRequest = decode_record("cancel_request_payload_invalid", &body)?;
    if request.schema != SYNC_DURABLE_SCHEMA {
        return Err(corrupt_state(
            "sync_unknown_schema",
            "cancel request schema does not match SYNC_DURABLE_SCHEMA",
        ));
    }
    Ok(Some(request))
}

fn write_cancel_request(paths: &SyncPaths, request: &SyncCancelRequest) -> Result<(), LomoError> {
    paths.ensure_layout()?;
    let body = encode_record("cancel_request_encode_failed", request)?;
    write_sync_record_atomic(&paths.cycle_cancel, SYNC_DURABLE_SCHEMA, &body)
}

fn remove_stale_cancel_request(paths: &SyncPaths) -> Result<(), LomoError> {
    match fs::remove_file(&paths.cycle_cancel) {
        Ok(()) => Ok(()),
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(err) => Err(storage(
            "cancel_request_remove_failed",
            &format!("cannot remove stale cancel request: {err}"),
        )),
    }
}

/// Stamp one ahead of everything durably observed (monotonic across writers in one process
/// because every mutation is a read-modify-write of the same file under the cycle lock).
fn fresh_stamp(record: &SyncCycleRecord, on_disk: Option<&SyncCycleRecord>) -> u64 {
    on_disk
        .map_or(record.state_stamp, |disk| disk.state_stamp)
        .max(record.state_stamp)
        .saturating_add(1)
}

/// Begins a new durable cycle and persists the `Running` record.
///
/// Repairs a stale `Running` predecessor (writer died before terminal write) into
/// `Failed(sync_cycle_interrupted)` — the last result is never silently lost. Clears any
/// cancel request left over from the dead cycle so a stale request cannot abort the new one.
///
/// # Errors
///
/// Storage/corruption for durable reads and writes.
pub fn begin_sync_cycle(
    paths: &SyncPaths,
    session: &SyncSession,
    backend_kind: SyncBackendKind,
    apply_remote: bool,
) -> Result<SyncCycleRecord, LomoError> {
    let now = now_unix_ms();
    let previous = read_cycle_state(paths)?;
    let mut stamp_floor = 0u64;
    if let Some(prev) = &previous
        && prev.phase == SyncCyclePhase::Running
    {
        // Process death mid-cycle: the record was the last result — repair it, don't lose it.
        let mut stale = prev.clone();
        stale.phase = SyncCyclePhase::Failed;
        "interrupted".clone_into(&mut stale.stage);
        stale.failure_code = Some(CYCLE_INTERRUPTED_CODE.to_owned());
        stale.failure_message =
            Some("cycle writer terminated before the terminal record was persisted".to_owned());
        stale.finished_at_ms = Some(now);
        stale.updated_at_ms = now;
        stale.state_stamp = stale.state_stamp.saturating_add(1);
        stamp_floor = stale.state_stamp;
        write_cycle_state(paths, &stale)?;
    }
    remove_stale_cancel_request(paths)?;

    let (seq, stamp, last_successful) = previous.as_ref().map_or((1u64, 1u64, None), |prev| {
        (
            prev.cycle_seq.saturating_add(1),
            prev.state_stamp.max(stamp_floor).saturating_add(1),
            prev.last_successful_at_ms,
        )
    });
    let record = SyncCycleRecord {
        schema: SYNC_DURABLE_SCHEMA,
        cycle_seq: seq,
        cycle_id: format!("cycle-{seq:06}"),
        fence_key: session.fence.stable_key(),
        backend_kind: backend_kind.wire_name().to_owned(),
        session_id: session.session_id.clone(),
        apply_remote,
        phase: SyncCyclePhase::Running,
        stage: "planning".to_owned(),
        cancel_requested: false,
        started_at_ms: now,
        updated_at_ms: now,
        last_successful_at_ms: last_successful,
        state_stamp: stamp,
        ..SyncCycleRecord::default()
    };
    write_cycle_state(paths, &record)?;
    Ok(record)
}

/// True when a durable cancel request targets the currently-running cycle.
///
/// Stale requests (mismatched cycle id, or the record already terminal) do not authorize abort.
///
/// # Errors
///
/// Corruption/storage for unreadable durable state.
pub fn sync_cycle_cancel_requested(paths: &SyncPaths) -> Result<bool, LomoError> {
    let Some(request) = read_cancel_request(paths)? else {
        return Ok(false);
    };
    let Some(record) = read_cycle_state(paths)? else {
        return Ok(false);
    };
    Ok(record.phase == SyncCyclePhase::Running && record.cycle_id == request.cycle_id)
}

/// Persists the `applying` stage transition before the first publication page.
///
/// # Errors
///
/// Storage when the record cannot be persisted.
pub fn note_sync_cycle_applying(paths: &SyncPaths) -> Result<(), LomoError> {
    let Some(mut record) = read_cycle_state(paths)? else {
        return Ok(());
    };
    if record.phase != SyncCyclePhase::Running {
        return Ok(());
    }
    "applying".clone_into(&mut record.stage);
    record.cancel_requested = sync_cycle_cancel_requested(paths)?;
    record.updated_at_ms = now_unix_ms();
    record.state_stamp = fresh_stamp(&record, None);
    write_cycle_state(paths, &record)
}

/// Requests cancellation of the currently-running cycle.
///
/// Writes the durable cancel request bound to the running `cycle_id`, then rewrites the record
/// with `cancel_requested = true` so observers see the request even before the cycle reaches its
/// next cancellation point.
///
/// # Errors
///
/// Validation when no cycle is running; storage/corruption for durable writes.
pub fn request_sync_cycle_cancel(paths: &SyncPaths) -> Result<SyncCycleRecord, LomoError> {
    let Some(mut record) = read_cycle_state(paths)? else {
        return Err(validation(
            "sync_cycle_not_running",
            "no durable sync cycle exists for this workspace",
        ));
    };
    if record.phase != SyncCyclePhase::Running {
        return Err(validation(
            "sync_cycle_not_running",
            "the latest sync cycle is already terminal",
        ));
    }
    let now = now_unix_ms();
    write_cancel_request(
        paths,
        &SyncCancelRequest {
            schema: SYNC_DURABLE_SCHEMA,
            cycle_id: record.cycle_id.clone(),
            requested_at_ms: now,
        },
    )?;
    record.cancel_requested = true;
    record.updated_at_ms = now;
    record.state_stamp = record.state_stamp.saturating_add(1);
    write_cycle_state(paths, &record)?;
    Ok(record)
}

/// Marks the running cycle `Cancelled` at the current cancellation point.
///
/// Called by the apply loop when it observes the durable cancel request between pages; the
/// terminal write records `pages_applied` so committed pages are never claimed rolled back.
///
/// # Errors
///
/// Storage when the record cannot be persisted.
pub fn mark_sync_cycle_cancelled(paths: &SyncPaths, pages_applied: u32) -> Result<(), LomoError> {
    let Some(mut record) = read_cycle_state(paths)? else {
        return Ok(());
    };
    if record.phase != SyncCyclePhase::Running {
        return Ok(());
    }
    let now = now_unix_ms();
    record.phase = SyncCyclePhase::Cancelled;
    "cancelled".clone_into(&mut record.stage);
    record.cancel_requested = true;
    record.pages_applied = pages_applied;
    record.failure_code = Some(CYCLE_CANCELLED_CODE.to_owned());
    record.failure_message =
        Some("durable cancel request observed between publication pages".to_owned());
    record.updated_at_ms = now;
    record.finished_at_ms = Some(now);
    record.state_stamp = fresh_stamp(&record, None);
    write_cycle_state(paths, &record)
}

/// Terminal write for a cycle that finished without an owner error.
///
/// Counts/disposition come from the computed summary — never re-derived. `last_successful_at_ms`
/// advances only for apply cycles whose disposition is not `transient`.
///
/// # Errors
///
/// Storage when the record cannot be persisted.
pub fn complete_sync_cycle(
    paths: &SyncPaths,
    record: &mut SyncCycleRecord,
    summary: &SyncCyclePlanSummary,
) -> Result<(), LomoError> {
    let on_disk = read_cycle_state(paths)?;
    let now = now_unix_ms();
    let cancelled = on_disk
        .as_ref()
        .is_some_and(|disk| disk.phase == SyncCyclePhase::Cancelled);
    if cancelled {
        return Ok(());
    }
    record.phase = SyncCyclePhase::Completed;
    "finished".clone_into(&mut record.stage);
    record.ensure_present_count = summary.ensure_present_count;
    record.ensure_absent_count = summary.ensure_absent_count;
    record.pull_present_count = summary.pull_present_count;
    record.open_conflict_count = summary.open_conflict_count;
    record.hold_count = summary.hold_count;
    record.local_entry_count = summary.local_entry_count;
    record.remote_listed_count = summary.remote_listed_count;
    record.baseline_entry_count = summary.baseline_entry_count;
    record.pages_applied = summary.pages_applied;
    record.baseline_advanced = summary.baseline_advanced;
    summary
        .retry_disposition
        .clone_into(&mut record.retry_disposition);
    record.cancel_requested = on_disk.as_ref().is_some_and(|disk| disk.cancel_requested);
    record.updated_at_ms = now;
    record.finished_at_ms = Some(now);
    if record.apply_remote && summary.retry_disposition != "transient" {
        record.last_successful_at_ms = Some(now);
    }
    record.state_stamp = fresh_stamp(record, on_disk.as_ref());
    write_cycle_state(paths, record)
}

/// Terminal write for a cycle that terminated with an owner error.
///
/// `sync_cycle_cancelled` is handled by the apply loop's own terminal write — this path is for
/// genuine failures only.
///
/// # Errors
///
/// Storage when the record cannot be persisted.
pub fn fail_sync_cycle(
    paths: &SyncPaths,
    record: &mut SyncCycleRecord,
    error: &LomoError,
) -> Result<(), LomoError> {
    let on_disk = read_cycle_state(paths)?;
    if on_disk
        .as_ref()
        .is_some_and(|disk| disk.phase == SyncCyclePhase::Cancelled)
    {
        return Ok(());
    }
    let now = now_unix_ms();
    record.phase = SyncCyclePhase::Failed;
    "failed".clone_into(&mut record.stage);
    record.cancel_requested = on_disk.as_ref().is_some_and(|disk| disk.cancel_requested);
    record.failure_code = Some(error.code().to_owned());
    record.failure_message = Some(error.diagnostic().to_owned());
    retry_disposition_name(error.retry_disposition()).clone_into(&mut record.retry_disposition);
    record.updated_at_ms = now;
    record.finished_at_ms = Some(now);
    record.state_stamp = fresh_stamp(record, on_disk.as_ref());
    write_cycle_state(paths, record)
}
