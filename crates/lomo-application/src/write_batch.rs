//! Composition of the application's frozen edit plans. The original pending journal entries
//! remain the recovery authority: no member is acknowledged until every composed file landed.

use crate::{
    error::{corruption, resource_limit},
    intent::{IntentJournal, OperationIntentRecord},
    resource::{MAX_TRANSACTION_BYTES, MAX_TRANSACTION_FILES},
    transaction::PlannedFile,
    workspace_io::WorkspaceIo,
};
use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
use lomo_store::SafProjectionMutationKind;
use std::collections::{BTreeMap, BTreeSet};

pub fn is_document_edit(record: &OperationIntentRecord) -> bool {
    record.mutations.len() == 1
        && record
            .mutations
            .iter()
            .all(|publication| publication.mutation.kind == SafProjectionMutationKind::Update)
        && record
            .files
            .iter()
            .all(|file| matches!(file, PlannedFile::Write { .. }))
}

pub fn retained_bytes(record: &OperationIntentRecord) -> Result<u64, LomoError> {
    record
        .files
        .iter()
        .flat_map(|file| file.before().into_iter().chain(file.after()))
        .try_fold(0_u64, |total, bytes| {
            total
                .checked_add(
                    u64::try_from(bytes.len()).map_err(|error| {
                        resource_limit("edit_batch_too_large", error.to_string())
                    })?,
                )
                .ok_or_else(|| resource_limit("edit_batch_too_large", "edit batch size overflow"))
        })
}

/// A false result is a batching boundary, not an invalid edit. A broken before/after chain
/// is corruption and is never made to look valid by flushing or replacing its baseline.
pub fn accepts<'a>(
    records: impl Iterator<Item = &'a OperationIntentRecord>,
    candidate: &OperationIntentRecord,
) -> Result<bool, LomoError> {
    let records = records.collect::<Vec<_>>();
    if !is_document_edit(candidate)
        || records
            .iter()
            .any(|record| !is_document_edit(record) || record.path != candidate.path)
    {
        return Ok(false);
    }
    if records
        .iter()
        .any(|record| record.operation_id == candidate.operation_id)
    {
        return Err(corruption(
            "invalid_edit_batch",
            "edit batch repeats an operation",
        ));
    }
    if records.len() >= MAX_TRANSACTION_FILES {
        return Ok(false);
    }
    let mut bytes = retained_bytes(candidate)?;
    let mut paths = candidate
        .files
        .iter()
        .map(PlannedFile::path)
        .collect::<BTreeSet<_>>();
    for record in &records {
        bytes = bytes
            .checked_add(retained_bytes(record)?)
            .ok_or_else(|| resource_limit("edit_batch_too_large", "edit batch size overflow"))?;
        paths.extend(record.files.iter().map(PlannedFile::path));
    }
    if bytes > MAX_TRANSACTION_BYTES || paths.len() > MAX_TRANSACTION_FILES {
        return Ok(false);
    }
    for next in &candidate.files {
        if let Some(previous) = records
            .iter()
            .rev()
            .find_map(|record| record.files.iter().find(|file| file.path() == next.path()))
            && next.before() != previous.after()
        {
            return Err(corruption(
                "invalid_edit_batch",
                "consecutive edits do not form one byte-exact file chain",
            ));
        }
    }
    Ok(true)
}

fn compose(records: &[OperationIntentRecord]) -> Result<Vec<PlannedFile>, LomoError> {
    let mut files: BTreeMap<RelativeWorkspacePath, (usize, PlannedFile)> = BTreeMap::new();
    let mut position = 0;
    for record in records {
        for file in &record.files {
            let PlannedFile::Write { after: next, .. } = file else {
                return Err(corruption(
                    "invalid_edit_batch",
                    "only byte writes can be composed",
                ));
            };
            match files.get_mut(file.path()) {
                Some((order, PlannedFile::Write { after, .. })) => {
                    after.clone_from(next);
                    *order = position;
                }
                Some(_) => {
                    return Err(corruption(
                        "invalid_edit_batch",
                        "non-write member reached composition",
                    ));
                }
                None => {
                    files.insert(file.path().clone(), (position, file.clone()));
                }
            }
            position += 1;
        }
    }
    let mut files = files.into_values().collect::<Vec<_>>();
    files.sort_by_key(|(position, _)| *position);
    Ok(files.into_iter().map(|(_, file)| file).collect())
}

/// Recovery checks each frozen publication against its own document and identity version,
/// before a later version is allowed to stand in for its physical write.
fn validate_edit(io: &WorkspaceIo<'_>, record: &OperationIntentRecord) -> Result<(), LomoError> {
    let publication = record
        .mutations
        .first()
        .ok_or_else(|| corruption("invalid_edit_batch", "edit publication is missing"))?;
    publication.validate()?;
    let projection = publication
        .mutation
        .projection
        .as_ref()
        .ok_or_else(|| corruption("invalid_edit_batch", "edit projection is missing"))?;
    if projection.source_path != record.path.as_str()
        || publication.mutation.memo_id != record.memo_id.as_str()
    {
        return Err(corruption(
            "invalid_edit_batch",
            "edit publication names a different source or identity",
        ));
    }
    let source = record
        .files
        .iter()
        .find(|file| file.path() == &record.path)
        .and_then(PlannedFile::after)
        .ok_or_else(|| corruption("invalid_edit_batch", "edit document bytes are missing"))?;
    let logical = lomo_workspace::WorkspaceRelativePath::parse(record.path.as_str())?;
    let identity_path = lomo_workspace::memo_identity_record_path(io.config.root_id, &logical)?;
    let identities = record
        .files
        .iter()
        .find(|file| file.path().as_str() == identity_path.as_str())
        .and_then(PlannedFile::after)
        .ok_or_else(|| corruption("invalid_edit_batch", "edit identity bytes are missing"))?;
    let document = lomo_workspace::parse_workspace_document(
        &lomo_workspace::SourceBytes::try_from_bytes(source.to_vec())?,
        crate::document_plan::filename_stem(&record.path)?,
    )?;
    let identities = lomo_workspace::MemoIdentityMap::decode(identities)?;
    let memo =
        identities
            .locator(&record.memo_id)?
            .resolve(io.config.root_id, &logical, &document)?;
    if document.source().fingerprint().as_str() != projection.file_fingerprint
        || memo.content() != projection.body
    {
        return Err(corruption(
            "invalid_edit_batch",
            "edit source bytes do not attest the frozen projection",
        ));
    }
    Ok(())
}

/// Applies the bounded, consecutive prefix recoverable from the existing logical journal.
/// Write-only composition needs no issued-delete witness: each target proves its base or final
/// bytes independently. All logical records stay pending until every target is satisfied.
pub fn apply_pending_prefix(
    io: &WorkspaceIo<'_>,
    journal: &IntentJournal,
    ids: &[OperationId],
) -> Result<usize, LomoError> {
    let first = ids
        .first()
        .ok_or_else(|| corruption("invalid_edit_batch", "empty pending prefix"))?;
    let first = journal
        .pending_record(first)?
        .ok_or_else(|| corruption("operation_plan_missing", "pending edit disappeared"))?;
    if !is_document_edit(&first) {
        crate::write_behind::apply_intent_files(io, journal, &first)?;
        return Ok(1);
    }
    validate_edit(io, &first)?;
    let mut records = vec![first];
    for id in ids.iter().skip(1) {
        let next = journal
            .pending_record(id)?
            .ok_or_else(|| corruption("operation_plan_missing", "pending edit disappeared"))?;
        let first = records
            .first()
            .ok_or_else(|| corruption("invalid_edit_batch", "empty edit chain"))?;
        if !is_document_edit(&next) || next.path != first.path {
            break;
        }
        if !accepts(records.iter(), &next)? {
            return Err(resource_limit(
                "edit_batch_too_large",
                "pending edit chain exceeds its admission budget",
            ));
        }
        validate_edit(io, &next)?;
        records.push(next);
    }
    apply_edits(io, &records)?;
    Ok(records.len())
}

fn apply_edits(io: &WorkspaceIo<'_>, records: &[OperationIntentRecord]) -> Result<(), LomoError> {
    let files = compose(records)?;
    let states = crate::write_behind::prepare_files(io, &files, 0)?;
    for (file, state) in files.iter().zip(&states) {
        if !file.is_satisfied(state) {
            file.apply(io, state)?;
        }
    }
    Ok(())
}

/// Live queue records were frozen by the validated document planner before publication. The
/// queue owns those immutable bytes; reloading and parsing them again would repeat that boundary.
/// A non-edit still reloads issued progress because deletes need its durable witness.
pub fn apply_queued_prefix(
    io: &WorkspaceIo<'_>,
    journal: &IntentJournal,
    records: &[OperationIntentRecord],
) -> Result<usize, LomoError> {
    let first = records
        .first()
        .ok_or_else(|| corruption("invalid_edit_batch", "empty queued prefix"))?;
    if !is_document_edit(first) {
        let current = journal
            .pending_record(&first.operation_id)?
            .ok_or_else(|| corruption("operation_plan_missing", "queued intent disappeared"))?;
        crate::write_behind::apply_intent_files(io, journal, &current)?;
        return Ok(1);
    }
    let mut count = 1;
    for record in records.iter().skip(1) {
        if !is_document_edit(record) || record.path != first.path {
            break;
        }
        if !accepts(records.iter().take(count), record)? {
            return Err(resource_limit(
                "edit_batch_too_large",
                "queued edit chain exceeds its admission budget",
            ));
        }
        count += 1;
    }
    apply_edits(
        io,
        records
            .get(..count)
            .ok_or_else(|| corruption("invalid_edit_batch", "queued prefix is incomplete"))?,
    )?;
    Ok(count)
}
