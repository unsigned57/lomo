use std::sync::Arc;

use lomo_core::{
    ActionId, DocumentKind, LomoError, PageSize, PlatformAction, PlatformActionBatch,
    PlatformActionExecutor, PlatformActionOutput, RelativeWorkspacePath, WorkspaceTarget,
};
use lomo_store::{
    RebuildResult, SafProjectionRebuild, ScannedMemoProjection, ScannedTrashProjection,
};
use lomo_workspace::{
    MemoIdentityMap, ReminderReference, SourceBytes, WorkspaceRelativePath, decode_trash_record,
    memo_identity_record_path, parse_workspace_document,
};

use crate::{
    calendar::memo_chronology,
    config::WorkspaceSessionConfig,
    csprng::{generate_hex_token, mint_memo_id},
    error::{corruption, storage, validation},
    workspace_io::{WorkspaceIo, deadline},
};

/// Rebuilds the entire SQLite query projection from Markdown and `.lomo` physical facts.
///
/// # Errors
/// Returns `Storage` or `Corruption` error if scanning or SQLite indexing fails.
pub fn rebuild_projection(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
) -> Result<RebuildResult, LomoError> {
    let all_files = list_recursive(config, executor)?;

    let mut markdown_files = Vec::new();
    let mut history_files = Vec::new();
    let mut state_files = Vec::new();
    let mut trash_files = Vec::new();

    for path in all_files {
        let path_str = path.as_str();
        if has_extension(path_str, "md") && !path_str.starts_with(".lomo/") {
            markdown_files.push(path);
        } else if path_str.starts_with(".lomo/history/") && has_extension(path_str, "rec") {
            history_files.push(path);
        } else if path_str.starts_with(".lomo/state/") && has_extension(path_str, "rec") {
            state_files.push(path);
        } else if path_str.starts_with(".lomo/trash/") && has_extension(path_str, "rec") {
            trash_files.push(path);
        }
    }

    let active_memos = scan_markdown_files(config, executor, &markdown_files, &mut history_files)?;
    history_files.sort_by(|left, right| left.as_str().cmp(right.as_str()));
    history_files.dedup();
    let trash_memos = scan_trash_files(config, executor, &trash_files)?;
    let history_revisions =
        crate::rebuild_records::history(&WorkspaceIo { config, executor }, &history_files)?;
    let pins = crate::rebuild_records::pins(&WorkspaceIo { config, executor }, &state_files)?;

    let mut rebuild = SafProjectionRebuild::begin(&config.cache_dir)?;
    for chunk in active_memos.chunks(256) {
        rebuild.append_page(chunk)?;
    }
    for chunk in trash_memos.chunks(256) {
        rebuild.append_trash_page(chunk)?;
    }
    for chunk in history_revisions.chunks(256) {
        rebuild.append_history_page(chunk)?;
    }
    for chunk in pins.chunks(256) {
        rebuild.append_pin_page(chunk)?;
    }

    rebuild.finish()
}

fn scan_markdown_files(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    markdown_files: &[RelativeWorkspacePath],
    history_files: &mut Vec<RelativeWorkspacePath>,
) -> Result<Vec<ScannedMemoProjection>, LomoError> {
    let io = WorkspaceIo { config, executor };
    let mut active_memos = Vec::new();
    for md_path in markdown_files {
        let snapshot = io.require(md_path)?;
        let source = SourceBytes::try_from_bytes(snapshot.bytes)?;
        let filename = md_path
            .as_str()
            .rsplit('/')
            .next()
            .ok_or_else(|| validation("invalid_workspace_path", "document needs a filename"))?;
        let stem = filename.strip_suffix(".md").unwrap_or(filename);
        let document = parse_workspace_document(&source, stem)?;
        let chronologies = document
            .memos()
            .iter()
            .map(|memo| memo_chronology(stem, memo.time_part(), &config.time_zone))
            .collect::<Result<Vec<_>, _>>()?;
        let path = WorkspaceRelativePath::parse(md_path.as_str())?;
        let identity_map = reconcile_identity(&io, &path, &document)?;
        for binding in identity_map.bindings() {
            let memo = binding
                .locator()
                .resolve(config.root_id, &path, &document)?;
            let chronology = *chronologies
                .get(binding.locator().block_index() as usize)
                .ok_or_else(|| {
                    corruption(
                        "memo_chronology_missing",
                        "validated block chronology is absent",
                    )
                })?;
            ensure_initial_history(&io, binding.memo_id(), memo, chronology, history_files)?;
            active_memos.push(ScannedMemoProjection {
                memo_id: binding.memo_id().as_str().to_owned(),
                source_path: md_path.as_str().to_owned(),
                file_fingerprint: source.fingerprint().as_str().to_owned(),
                chronology_epoch_ms: chronology,
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
            });
        }
    }
    Ok(active_memos)
}

fn ensure_initial_history(
    io: &WorkspaceIo<'_>,
    id: &lomo_workspace::MemoId,
    memo: &lomo_workspace::WorkspaceMemo,
    chronology: i64,
    inventory: &mut Vec<RelativeWorkspacePath>,
) -> Result<(), LomoError> {
    if crate::record_plan::history_tip(io, id)?.is_some() {
        return Ok(());
    }
    let prepared = crate::record_plan::history_files(
        io,
        &lomo_workspace::HistorySnapshotV1 {
            memo_id: id.as_str().to_owned(),
            revision: 1,
            content: memo.content().to_owned(),
            file_fingerprint: lomo_workspace::SourceFingerprint::of_bytes(
                memo.content().as_bytes(),
            )
            .as_str()
            .to_owned(),
            created_at_ms: chronology,
        },
    )?;
    for file in prepared.files {
        file.apply(io)?;
        inventory.push(file.path().clone());
    }
    Ok(())
}

fn reconcile_identity(
    io: &WorkspaceIo<'_>,
    path: &WorkspaceRelativePath,
    document: &lomo_workspace::WorkspaceDocument,
) -> Result<MemoIdentityMap, LomoError> {
    let record_path = memo_identity_record_path(io.config.root_id, path)?;
    let record_path = RelativeWorkspacePath::parse(record_path.as_str())?;
    let before = io.read(&record_path)?;
    let operation = lomo_core::OperationId::parse(&format!("scan-{}", generate_hex_token(16)?))?;
    let mut map = if let Some(snapshot) = &before {
        MemoIdentityMap::decode(&snapshot.bytes)?.reconcile_external(operation, document)?
    } else {
        let ids = document
            .memos()
            .iter()
            .map(|_| mint_memo_id())
            .collect::<Result<Vec<_>, _>>()?;
        MemoIdentityMap::initialize(operation, io.config.root_id, path.clone(), document, ids)?
    };
    if map.conflicts().is_empty() && !map.unbound().is_empty() {
        let ids = map
            .unbound()
            .iter()
            .map(|_| mint_memo_id())
            .collect::<Result<Vec<_>, _>>()?;
        map = map.assign_discovered(
            lomo_core::OperationId::parse(&format!("discover-{}", generate_hex_token(16)?))?,
            document,
            ids,
        )?;
    }
    let after = map.encode()?;
    if before
        .as_ref()
        .is_none_or(|snapshot| snapshot.bytes != after)
    {
        io.write(&record_path, before.as_ref(), &after)?;
    }
    if !map.conflicts().is_empty() {
        return Err(crate::error::conflict(
            "memo_identity_unresolved",
            "external document changes have ambiguous identities; evidence is preserved in .lomo",
        ));
    }
    Ok(map)
}

fn scan_trash_files(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    trash_files: &[RelativeWorkspacePath],
) -> Result<Vec<ScannedTrashProjection>, LomoError> {
    let mut trash_memos = Vec::new();
    for trash_path in trash_files {
        let bytes = read_workspace_file(config, executor, trash_path)?;
        let trash = decode_trash_record(&bytes)?;
        {
            trash_memos.push(ScannedTrashProjection {
                memo: ScannedMemoProjection {
                    memo_id: trash.memo_id,
                    source_path: trash.source_path,
                    file_fingerprint: trash.source_fingerprint,
                    chronology_epoch_ms: trash.chronology_epoch_ms,
                    body: trash.body,
                    tags: trash.tags,
                    attachment_paths: trash.attachments,
                    has_todo: trash.has_todo,
                    has_url: trash.has_url,
                    reminders: trash.reminders,
                },
                trashed_at_ms: trash.trashed_at_ms,
            });
        }
    }
    Ok(trash_memos)
}

fn list_recursive(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
) -> Result<Vec<RelativeWorkspacePath>, LomoError> {
    let mut file_paths = Vec::new();
    let mut dirs_to_visit = vec![WorkspaceTarget::Root];

    while let Some(target) = dirs_to_visit.pop() {
        let mut cursor = None;
        loop {
            let action_id = ActionId::parse(&format!("list-{}", generate_hex_token(8)?))?;
            let page_size = PageSize::new(256)?;
            let action = match &target {
                WorkspaceTarget::Root => PlatformAction::list_root(
                    action_id,
                    config.capability.clone(),
                    cursor,
                    page_size,
                ),
                WorkspaceTarget::Relative(rel) => PlatformAction::list_children(
                    action_id,
                    config.capability.clone(),
                    rel.clone(),
                    cursor,
                    page_size,
                ),
            };

            let job_id = lomo_core::JobId::parse(&format!("job-list-{}", generate_hex_token(8)?))?;
            let batch_id =
                lomo_core::BatchId::parse(&format!("batch-list-{}", generate_hex_token(8)?))?;
            let batch = PlatformActionBatch::new(job_id, batch_id, 1, deadline()?, vec![action])?;

            let result = executor.execute(&batch)?;
            result.validate_against(&batch)?;

            let first_res = result
                .action_results()
                .first()
                .ok_or_else(|| storage("list_empty_result", "missing action result for list"))?;

            let output = match first_res.outcome() {
                lomo_core::ActionOutcome::Applied(out)
                | lomo_core::ActionOutcome::AlreadySatisfied(out) => out,
                lomo_core::ActionOutcome::Failed(err) => return Err(err.clone()),
            };

            let page = match output {
                PlatformActionOutput::Listed { page } => page,
                PlatformActionOutput::Stat { .. }
                | PlatformActionOutput::DirectoryReady { .. }
                | PlatformActionOutput::ReadToExchange { .. }
                | PlatformActionOutput::WriteComplete { .. }
                | PlatformActionOutput::MoveComplete { .. }
                | PlatformActionOutput::DeleteComplete { .. } => {
                    return Err(corruption("invalid_list_output", "expected Listed output"));
                }
            };

            for item in page.items() {
                if matches!(item.target(), WorkspaceTarget::Relative(path) if matches!(path.as_str(), ".git" | ".lomo/local"))
                {
                    continue;
                }
                match item.kind() {
                    DocumentKind::File => {
                        if let WorkspaceTarget::Relative(path) = item.target() {
                            file_paths.push(path.clone());
                        }
                    }
                    DocumentKind::Directory => {
                        dirs_to_visit.push(item.target().clone());
                    }
                }
            }

            cursor = page.next_cursor().map(|c| c.as_str().to_owned());
            if cursor.is_none() {
                break;
            }
        }
    }

    Ok(file_paths)
}

/// Reads a workspace file via platform action and returns its byte contents.
///
/// # Errors
/// Returns `Storage` or `Corruption` error if read fails.
pub fn read_workspace_file(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    path: &RelativeWorkspacePath,
) -> Result<Vec<u8>, LomoError> {
    Ok(WorkspaceIo { config, executor }.require(path)?.bytes)
}

fn has_extension(path: &str, ext: &str) -> bool {
    std::path::Path::new(path)
        .extension()
        .is_some_and(|e| e.eq_ignore_ascii_case(ext))
}
