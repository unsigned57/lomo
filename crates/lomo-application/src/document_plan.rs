//! Source-version addresses are resolved once, then transformed by the workspace patch owner.

use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
use lomo_store::ScannedMemoProjection;
use lomo_workspace::{
    DocumentPatchCommand, MemoId, MemoIdentityChange, MemoIdentityMap, ReminderReference,
    SourceBytes, WorkspaceDocument, WorkspaceMemo, WorkspaceRelativePath, WorkspaceRootId,
    memo_identity_record_path, parse_workspace_document, plan_document_patch,
};

use crate::{
    calendar::memo_chronology,
    csprng::{generate_hex_token, mint_memo_id},
    error::{conflict, validation},
    transaction::PlannedFile,
    workspace_io::{FileSnapshot, WorkspaceIo},
};

/// The caller's source knowledge determines the read schedule, never physical existence.
/// Destinations admit verified absence; mutations of an active memo require both sources.
#[derive(Clone, Copy)]
pub enum DocumentRead {
    UncataloguedDestination,
    CataloguedDestination,
    ActiveMemo,
}

pub struct LoadedDocument {
    pub path: RelativeWorkspacePath,
    pub logical_path: WorkspaceRelativePath,
    pub document: WorkspaceDocument,
    pub identities: MemoIdentityMap,
    pub original: Option<FileSnapshot>,
    root: WorkspaceRootId,
    mapping_path: RelativeWorkspacePath,
    mapping_original: Option<FileSnapshot>,
}

pub struct DocumentChange {
    pub document: WorkspaceDocument,
    pub identities: MemoIdentityMap,
}

impl LoadedDocument {
    /// Loads the actual source pair under the command's presence requirement.
    pub fn load(
        io: &WorkspaceIo<'_>,
        path: RelativeWorkspacePath,
        read: DocumentRead,
    ) -> Result<Self, LomoError> {
        crate::types::validate_document_path(&path)?;
        let mapping = Self::mapping_path(io, &path)?;
        let paths = [path.clone(), mapping];
        let sources = match read {
            // A fail-fast provider may stop at the first missing source. New destinations
            // probe separately so an unreported sibling never needs to be resubmitted.
            DocumentRead::UncataloguedDestination => vec![io.read(&paths[0])?, io.read(&paths[1])?],
            DocumentRead::CataloguedDestination => io.read_batch(&paths)?,
            DocumentRead::ActiveMemo => io
                .require_batch(&paths)
                .map_err(|error| {
                    if error.code() == "document_not_found" {
                        validation(
                            "memo_source_missing",
                            format!(
                                "active memo document or identity source is missing: {}",
                                path.as_str()
                            ),
                        )
                    } else {
                        error
                    }
                })?
                .into_iter()
                .map(Some)
                .collect(),
        };
        let [original, mapping_original]: [Option<FileSnapshot>; 2] =
            sources
                .try_into()
                .map_err(|sources: Vec<Option<FileSnapshot>>| {
                    crate::error::corruption(
                        "missing_document_sources",
                        format!(
                            "document source batch returned {} observations instead of two",
                            sources.len()
                        ),
                    )
                })?;
        Self::from_sources(io, path, original, mapping_original)
    }

    fn mapping_path(
        io: &WorkspaceIo<'_>,
        path: &RelativeWorkspacePath,
    ) -> Result<RelativeWorkspacePath, LomoError> {
        RelativeWorkspacePath::parse(
            memo_identity_record_path(
                io.config.root_id,
                &WorkspaceRelativePath::parse(path.as_str())?,
            )?
            .as_str(),
        )
    }

    fn from_sources(
        io: &WorkspaceIo<'_>,
        path: RelativeWorkspacePath,
        original: Option<FileSnapshot>,
        mapping_original: Option<FileSnapshot>,
    ) -> Result<Self, LomoError> {
        let logical_path = WorkspaceRelativePath::parse(path.as_str())?;
        let bytes = original
            .as_ref()
            .map_or_else(Vec::new, |snapshot| snapshot.bytes.clone());
        let document =
            parse_workspace_document(&SourceBytes::try_from_bytes(bytes)?, filename_stem(&path)?)?;
        let mapping_path = RelativeWorkspacePath::parse(
            memo_identity_record_path(io.config.root_id, &logical_path)?.as_str(),
        )?;
        let identities = if let Some(snapshot) = &mapping_original {
            let map = MemoIdentityMap::decode(&snapshot.bytes)?;
            if original.is_none() {
                map.retire_missing_document(OperationId::parse(&format!(
                    "absent-{}",
                    generate_hex_token(16)?
                ))?)?
            } else {
                map
            }
        } else {
            let ids = document
                .memos()
                .iter()
                .map(|_| mint_memo_id())
                .collect::<Result<Vec<_>, _>>()?;
            MemoIdentityMap::initialize(
                OperationId::parse(&format!("init-{}", generate_hex_token(16)?))?,
                io.config.root_id,
                logical_path.clone(),
                &document,
                ids,
            )?
        };
        Ok(Self {
            path,
            logical_path,
            document,
            identities,
            original,
            root: io.config.root_id,
            mapping_path,
            mapping_original,
        })
    }

    pub fn memo(&self, id: &MemoId) -> Result<&WorkspaceMemo, LomoError> {
        self.identities
            .locator(id)?
            .resolve(self.root, &self.logical_path, &self.document)
    }

    pub fn apply(
        &self,
        operation: OperationId,
        identity_change: MemoIdentityChange,
        command: &DocumentPatchCommand,
    ) -> Result<DocumentChange, LomoError> {
        // The identity record's retained tail is the cross-device witness for a recent
        // operation id once the journal's bounded retired-epoch window has evicted it:
        // `replay` returned `None`, the projection's committed-operation index did not
        // know the id either, yet the durable tail still does — so the command was
        // consumed and must expire instead of falling through to a fresh
        // `record_pending` that would re-execute it.
        if self
            .identities
            .operations()
            .iter()
            .any(|previous| previous.operation_id() == &operation)
        {
            return Err(crate::error::expired(
                "operation_expired",
                "operation was retired by a closed epoch and is not re-executed",
            ));
        }
        let planned = plan_document_patch(&self.document, command)?;
        let outcome =
            self.identities
                .apply_patch(operation, identity_change, &self.document, &planned)?;
        // `apply_patch` returns the result parse it already proved the transition
        // against: re-parsing `result_bytes` here would bill a second whole-document
        // parse for every memo-level patch.
        Ok(DocumentChange {
            document: outcome.document,
            identities: outcome.map,
        })
    }

    pub fn files(&self, change: &DocumentChange) -> Result<Vec<PlannedFile>, LomoError> {
        Ok(vec![
            PlannedFile::new(
                self.path.clone(),
                self.original.as_ref(),
                change.document.source().as_bytes().to_vec(),
            ),
            PlannedFile::new(
                self.mapping_path.clone(),
                self.mapping_original.as_ref(),
                change.identities.encode()?,
            ),
        ])
    }

    pub fn changed_memo<'a>(
        &self,
        change: &'a DocumentChange,
        id: &MemoId,
    ) -> Result<&'a WorkspaceMemo, LomoError> {
        change
            .identities
            .locator(id)?
            .resolve(self.root, &self.logical_path, &change.document)
    }

    pub fn fingerprint(&self) -> &str {
        self.document.source().fingerprint().as_str()
    }

    pub fn check_baseline(&self, expected: &str) -> Result<(), LomoError> {
        lomo_workspace::SourceFingerprint::parse(expected)?;
        if self.original.is_none() || self.fingerprint() != expected {
            return Err(conflict(
                "stale_document_baseline",
                "source bytes changed since the editing baseline",
            ));
        }
        Ok(())
    }
}

pub fn filename_stem(path: &RelativeWorkspacePath) -> Result<&str, LomoError> {
    let filename = path
        .as_str()
        .rsplit('/')
        .next()
        .ok_or_else(|| validation("invalid_document_path", "missing filename"))?;
    Ok(match filename.rsplit_once('.') {
        Some((stem, extension)) if extension.eq_ignore_ascii_case("md") => stem,
        _ => filename,
    })
}

pub fn project_memo(
    path: &RelativeWorkspacePath,
    id: &MemoId,
    fingerprint: &str,
    memo: &WorkspaceMemo,
    time_zone: &str,
) -> Result<ScannedMemoProjection, LomoError> {
    Ok(ScannedMemoProjection {
        memo_id: id.as_str().to_owned(),
        source_path: path.as_str().to_owned(),
        time_part: memo.time_part().to_owned(),
        file_fingerprint: fingerprint.to_owned(),
        chronology_epoch_ms: memo_chronology(filename_stem(path)?, memo.time_part(), time_zone)?,
        body: memo.content().to_owned(),
        tags: memo.tags().to_vec(),
        attachment_paths: memo.attachments().to_vec(),
        has_todo: memo.has_todo(),
        has_url: memo.has_url(),
        reminders: memo
            .reminders()
            .iter()
            .map(ReminderReference::from)
            .collect(),
    })
}
