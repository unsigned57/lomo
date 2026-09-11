//! Source-version addresses are resolved once, then transformed by the workspace patch owner.

use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
use lomo_store::ScannedMemoProjection;
use lomo_workspace::{
    DocumentPatchCommand, MemoId, MemoIdentityChange, MemoIdentityMap, ReminderReference,
    SourceBytes, WorkspaceDocument, WorkspaceMemo, WorkspaceRelativePath, WorkspaceRootId,
    memo_identity_record_path, parse_workspace_document, plan_document_patch,
};

use crate::calendar::memo_chronology;
use crate::csprng::{generate_hex_token, mint_memo_id};
use crate::error::{conflict, validation};
use crate::transaction::PlannedFile;
use crate::workspace_io::{FileSnapshot, WorkspaceIo};

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
    pub fn load(io: &WorkspaceIo<'_>, path: RelativeWorkspacePath) -> Result<Self, LomoError> {
        let first = path.as_str().split('/').next();
        let markdown = path
            .as_str()
            .rsplit_once('.')
            .is_some_and(|(_, extension)| extension.eq_ignore_ascii_case("md"));
        if matches!(first, Some(".lomo" | ".git")) || !markdown {
            return Err(validation(
                "invalid_memo_document_path",
                "memo commands require a Markdown path outside control directories",
            ));
        }
        let logical_path = WorkspaceRelativePath::parse(path.as_str())?;
        let original = io.read(&path)?;
        let bytes = original
            .as_ref()
            .map_or_else(Vec::new, |snapshot| snapshot.bytes.clone());
        let document =
            parse_workspace_document(&SourceBytes::try_from_bytes(bytes)?, filename_stem(&path)?)?;
        let mapping_path = RelativeWorkspacePath::parse(
            memo_identity_record_path(io.config.root_id, &logical_path)?.as_str(),
        )?;
        let mapping_original = io.read(&mapping_path)?;
        let identities = if let Some(snapshot) = &mapping_original {
            MemoIdentityMap::decode(&snapshot.bytes)?
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
        let planned = plan_document_patch(&self.document, command)?;
        let identities =
            self.identities
                .apply_patch(operation, identity_change, &self.document, &planned)?;
        let document = parse_workspace_document(
            &SourceBytes::try_from_bytes(planned.result_bytes().to_vec())?,
            filename_stem(&self.path)?,
        )?;
        Ok(DocumentChange {
            document,
            identities,
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
    Ok(filename.strip_suffix(".md").unwrap_or(filename))
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
