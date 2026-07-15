use std::collections::{BTreeMap, BTreeSet};

use crate::model::{
    Action, Backend, Direction, LocalSnapshot, MetadataSnapshot, Plan, ProtocolError, Reason,
    RemoteAbsenceVerification, RemoteSnapshot, Request,
};
use crate::validation::validate_request;

/// Builds a deterministic provider-neutral sync plan.
///
/// # Errors
///
/// Returns [`ProtocolError`] when the request violates the sync v1 boundary contract or the
/// pending-change count cannot be represented on the wire.
pub fn plan(request: &Request) -> Result<Plan, ProtocolError> {
    validate_request(request)?;
    let local = request
        .local
        .iter()
        .map(|item| (item.path.as_str(), item))
        .collect::<BTreeMap<_, _>>();
    let remote = request
        .remote
        .iter()
        .map(|item| (item.path.as_str(), item))
        .collect::<BTreeMap<_, _>>();
    let metadata = request
        .metadata
        .iter()
        .map(|item| (item.path.as_str(), item))
        .collect::<BTreeMap<_, _>>();
    let pre_resolved = request
        .pre_resolved
        .iter()
        .map(|item| (item.path.as_str(), item))
        .collect::<BTreeMap<_, _>>();
    let suppressed = request
        .suppressed
        .iter()
        .map(String::as_str)
        .collect::<BTreeSet<_>>();
    let missing = request
        .missing_remote_verification
        .iter()
        .map(|(path, verification)| (path.as_str(), verification))
        .collect::<BTreeMap<_, _>>();

    let mut paths = BTreeSet::new();
    paths.extend(local.keys().copied());
    paths.extend(remote.keys().copied());
    paths.extend(metadata.keys().copied());

    let mut actions = Vec::new();
    for path in paths {
        if suppressed.contains(path) {
            continue;
        }
        if let Some(action) = pre_resolved.get(path) {
            actions.push((*action).clone());
            continue;
        }
        let action = match (local.get(path), remote.get(path), metadata.get(path)) {
            (Some(local), Some(remote), metadata) => {
                both_present(request, path, local, remote, metadata)
            }
            (Some(local), None, metadata) => {
                local_only(request, path, local, metadata, missing.get(path).copied())
            }
            (None, Some(remote), metadata) => Some(remote_only(request, path, remote, metadata)),
            (None, None, _) => None,
        };
        if let Some(action) = action {
            actions.push(action);
        }
    }
    let pending_changes = actions
        .iter()
        .filter(|action| action.direction != Direction::None)
        .count();
    let pending_changes = u32::try_from(pending_changes)
        .map_err(|_conversion_error| ProtocolError::OutputOverflow)?;
    Ok(Plan {
        actions,
        pending_changes,
    })
}

fn both_present(
    request: &Request,
    path: &str,
    local: &LocalSnapshot,
    remote: &RemoteSnapshot,
    metadata: Option<&&MetadataSnapshot>,
) -> Option<Action> {
    let metadata = metadata?;
    let local_changed = local_changed(request, local, metadata);
    let remote_changed = remote_changed(request, remote, metadata);
    match (local_changed, remote_changed) {
        (false, false) => None,
        (true, false) => Some(action(path, Direction::Upload, Reason::LocalOnly)),
        (false, true) => Some(action(path, Direction::Download, Reason::RemoteOnly)),
        (true, true) => Some(action(path, Direction::Conflict, Reason::Conflict)),
    }
}

fn local_only(
    request: &Request,
    path: &str,
    local: &LocalSnapshot,
    metadata: Option<&&MetadataSnapshot>,
    missing_verification: Option<&RemoteAbsenceVerification>,
) -> Option<Action> {
    let Some(metadata) = metadata else {
        return Some(action(path, Direction::Upload, Reason::LocalOnly));
    };
    if !local_changed(request, local, metadata) {
        let verification = missing_verification
            .copied()
            .unwrap_or(request.default_missing_remote_verification);
        return (verification == RemoteAbsenceVerification::VerifiedAbsent)
            .then(|| action(path, Direction::DeleteLocal, Reason::RemoteDeleted));
    }
    let remote_reference = metadata
        .remote_last_modified
        .unwrap_or(metadata.last_synced_at);
    if local.last_modified >= remote_reference {
        Some(action(path, Direction::Upload, Reason::LocalNewer))
    } else {
        let verification = missing_verification
            .copied()
            .unwrap_or(request.default_missing_remote_verification);
        (verification == RemoteAbsenceVerification::VerifiedAbsent)
            .then(|| action(path, Direction::DeleteLocal, Reason::RemoteDeleted))
    }
}

fn remote_only(
    request: &Request,
    path: &str,
    remote: &RemoteSnapshot,
    metadata: Option<&&MetadataSnapshot>,
) -> Action {
    let Some(metadata) = metadata else {
        return action(path, Direction::Download, Reason::RemoteOnly);
    };
    if !remote_changed(request, remote, metadata) {
        return action(path, Direction::DeleteRemote, Reason::LocalDeleted);
    }
    let local_reference = metadata
        .local_last_modified
        .unwrap_or(metadata.last_synced_at);
    if remote.last_modified.unwrap_or(0) >= local_reference {
        action(path, Direction::Download, Reason::RemoteNewer)
    } else {
        action(path, Direction::DeleteRemote, Reason::LocalDeleted)
    }
}

fn local_changed(request: &Request, local: &LocalSnapshot, metadata: &MetadataSnapshot) -> bool {
    match request.backend {
        Backend::S3 => changed(
            request.timestamp_tolerance_ms,
            Some(local.last_modified),
            metadata.local_last_modified,
        ),
        Backend::WebDav => match (&local.fingerprint, &metadata.local_fingerprint) {
            (Some(current), Some(previous)) => current != previous,
            _ => changed(
                request.timestamp_tolerance_ms,
                Some(local.last_modified),
                metadata.local_last_modified,
            ),
        },
    }
}

fn remote_changed(request: &Request, remote: &RemoteSnapshot, metadata: &MetadataSnapshot) -> bool {
    match request.backend {
        Backend::S3 => {
            changed(
                request.timestamp_tolerance_ms,
                remote.last_modified,
                metadata.remote_last_modified,
            ) || remote.etag != metadata.etag
        }
        Backend::WebDav => match (&remote.etag, &metadata.etag) {
            (Some(current), Some(previous)) => current != previous,
            _ => changed(
                request.timestamp_tolerance_ms,
                remote.last_modified,
                metadata.remote_last_modified,
            ),
        },
    }
}

const fn changed(tolerance: i64, current: Option<i64>, previous: Option<i64>) -> bool {
    match (current, previous) {
        (None, None) => false,
        (None, Some(_)) | (Some(_), None) => true,
        (Some(current), Some(previous)) => current.abs_diff(previous) > tolerance.unsigned_abs(),
    }
}

fn action(path: &str, direction: Direction, reason: Reason) -> Action {
    Action {
        path: path.to_owned(),
        direction,
        reason,
    }
}
