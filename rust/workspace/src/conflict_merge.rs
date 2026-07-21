//! Owner identity-keyed merge for memo-shard sync conflicts.
//!
//! Kotlin may supply local/remote shard bytes and timestamps only; memo block segmentation and
//! identity ordinals are owner facts from [`crate::parse::parse_workspace_document`].
//!
//! Merged text preserves the winning side's dominant newline and inter-memo separator policy
//! (no hard-coded LF `"\n\n"` join; CRLF shards stay CRLF when uniform).

use std::collections::{HashMap, HashSet};

use lomo_core::LomoError;

use crate::document::{DocumentFormat, WorkspaceDocument, WorkspaceMemo};
use crate::limits::validation;
use crate::parse::parse_workspace_document;
use crate::source::{DominantNewline, NewlineKind, SourceBytes, SourceTextState};

/// Merges two Lomo/Thino memo-shard texts by owner memo identity `(time_part, ordinal)`.
///
/// When both sides share at least one identity, blocks are aligned and the newer file's version
/// wins for shared keys; older-only then newer-only blocks are emitted in that order. Declines
/// (`Ok(None)`) when either side is not Lomo/Thino, has a non-blank preamble before the first
/// memo, or the sides share no identity — callers may then fall back to non-identity text merge.
///
/// Inter-memo separators and newline kind follow the winning (newer) side's `SourceTextState` and
/// observed inter-memo gap. Mixed newlines fail closed rather than silently normalize.
///
/// # Errors
///
/// Returns validation/corruption when either source fails UTF-8 / document parse constraints, a
/// memo span cannot be sliced, or newline policy is mixed/ambiguous.
pub fn merge_memo_shard_by_identity(
    local_text: &str,
    remote_text: &str,
    local_last_modified: Option<i64>,
    remote_last_modified: Option<i64>,
) -> Result<Option<String>, LomoError> {
    let local_source = SourceBytes::try_from_str(local_text)?;
    let remote_source = SourceBytes::try_from_str(remote_text)?;
    // Synthetic stem: conflict merge keys on (time_part, ordinal) within each shard only.
    let local_doc = parse_workspace_document(&local_source, "conflict")?;
    let remote_doc = parse_workspace_document(&remote_source, "conflict")?;

    if local_doc.format() != DocumentFormat::LomoThino
        || remote_doc.format() != DocumentFormat::LomoThino
    {
        return Ok(None);
    }
    if has_nonblank_preamble(&local_doc)? || has_nonblank_preamble(&remote_doc)? {
        return Ok(None);
    }

    let local_blocks = blocks_from_document(&local_doc)?;
    let remote_blocks = blocks_from_document(&remote_doc)?;
    if local_blocks.is_empty() || remote_blocks.is_empty() {
        return Ok(None);
    }

    let local_keys: HashSet<MemoBlockKey> =
        local_blocks.iter().map(|block| block.key.clone()).collect();
    let remote_keys: HashSet<MemoBlockKey> = remote_blocks
        .iter()
        .map(|block| block.key.clone())
        .collect();
    if local_keys.is_disjoint(&remote_keys) {
        return Ok(None);
    }

    let local_is_newer = match (local_last_modified, remote_last_modified) {
        (None, _) | (_, None) => true,
        (Some(local), Some(remote)) => local >= remote,
    };
    let (older_blocks, newer_blocks, winning_source, winning_doc) = if local_is_newer {
        (&remote_blocks, &local_blocks, &local_source, &local_doc)
    } else {
        (&local_blocks, &remote_blocks, &remote_source, &remote_doc)
    };
    let newer_by_key: HashMap<MemoBlockKey, &str> = newer_blocks
        .iter()
        .map(|block| (block.key.clone(), block.text.as_str()))
        .collect();

    let separator = choose_inter_memo_separator(winning_source, winning_doc)?;
    let newline = choose_newline(winning_source.text_state())?;

    let mut emitted: HashSet<MemoBlockKey> = HashSet::new();
    let mut cores = Vec::new();
    for block in older_blocks {
        if emitted.insert(block.key.clone()) {
            let text = newer_by_key
                .get(&block.key)
                .copied()
                .unwrap_or(block.text.as_str());
            cores.push(text.to_owned());
        }
    }
    for block in newer_blocks {
        if emitted.insert(block.key.clone()) {
            cores.push(block.text.clone());
        }
    }

    let mut merged = cores.join(&separator);
    if winning_source.text_state().trailing().ends_with_newline()
        && !ends_with_newline_kind(&merged, newline)
    {
        merged.push_str(newline);
    }
    Ok(Some(merged))
}

#[derive(Clone, Debug, Eq, Hash, PartialEq)]
struct MemoBlockKey {
    time_part: String,
    ordinal: u32,
}

#[derive(Clone, Debug)]
struct MemoBlock {
    key: MemoBlockKey,
    /// Core memo block text (raw `memo_span` without trailing inter-memo newline sequences).
    text: String,
}

fn blocks_from_document(document: &WorkspaceDocument) -> Result<Vec<MemoBlock>, LomoError> {
    document
        .memos()
        .iter()
        .map(|memo| {
            Ok(MemoBlock {
                key: MemoBlockKey {
                    time_part: memo.time_part().to_owned(),
                    ordinal: memo.identity().ordinal(),
                },
                text: core_memo_text(document, memo)?,
            })
        })
        .collect()
}

fn core_memo_text(document: &WorkspaceDocument, memo: &WorkspaceMemo) -> Result<String, LomoError> {
    let raw = document.source().slice(memo.memo_span())?;
    Ok(trim_trailing_newline_sequences(raw).to_owned())
}

fn trim_trailing_newline_sequences(text: &str) -> &str {
    text.trim_end_matches(['\n', '\r'])
}

fn choose_inter_memo_separator(
    winning_source: &SourceBytes,
    winning_doc: &WorkspaceDocument,
) -> Result<String, LomoError> {
    let memos = winning_doc.memos();
    if memos.len() >= 2 {
        let first = memos.first().ok_or_else(|| {
            validation(
                "conflict_merge_memo_missing",
                "winning document claimed multiple memos but first memo is missing",
            )
        })?;
        let first_raw = winning_source.slice(first.memo_span())?;
        let core = trim_trailing_newline_sequences(first_raw);
        if let Some(trailing) = first_raw.get(core.len()..)
            && !trailing.is_empty()
        {
            return Ok(trailing.to_owned());
        }
    }
    // Single-memo winning shard (or empty gap): use one dominant newline, not an invented blank line.
    Ok(choose_newline(winning_source.text_state())?.to_owned())
}

fn choose_newline(state: SourceTextState) -> Result<&'static str, LomoError> {
    match state.dominant_newline() {
        DominantNewline::None | DominantNewline::Uniform(NewlineKind::Lf) => Ok("\n"),
        DominantNewline::Uniform(NewlineKind::Crlf) => Ok("\r\n"),
        DominantNewline::Uniform(NewlineKind::Cr) => Ok("\r"),
        DominantNewline::Mixed => Err(validation(
            "mixed_newline_ambiguous",
            "mixed newlines prevent a lossless conflict-merge newline decision",
        )),
    }
}

fn ends_with_newline_kind(text: &str, newline: &str) -> bool {
    text.ends_with(newline)
        || (newline == "\n" && text.ends_with('\n'))
        || (newline == "\r" && text.ends_with('\r') && !text.ends_with("\r\n"))
}

fn has_nonblank_preamble(document: &WorkspaceDocument) -> Result<bool, LomoError> {
    let Some(first) = document.memos().first() else {
        return Ok(false);
    };
    let preamble_span =
        crate::source::ByteSpan::try_new(0, first.memo_span().start(), document.source().len())?;
    let preamble = document.source().slice(preamble_span)?;
    Ok(preamble.chars().any(|ch| !ch.is_whitespace()))
}
