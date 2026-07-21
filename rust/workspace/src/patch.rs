//! Pure document patch planner (append / replace / remove / toggle-task).
//!
//! Plans byte-level mutations against a parsed [`WorkspaceDocument`] without I/O. External edits are
//! rejected via expected fingerprint; missing unique targets fail closed.

use lomo_core::LomoError;

use crate::document::WorkspaceDocument;
use crate::header::parse_memo_header_line;
use crate::limits::{ResourceBudget, validation};
use crate::reminder::ReminderRef;
use crate::render::validate_reminder_token;
use crate::source::{
    ByteSpan, DominantNewline, NewlineKind, SourceBytes, SourceFingerprint, SourceTextState,
};
use crate::types::{MemoIdentity, WorkspaceRelativePath};

/// Document command accepted by the pure patch planner.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum DocumentPatchCommand {
    Append {
        path: WorkspaceRelativePath,
        expected_fingerprint: SourceFingerprint,
        time_part: String,
        content: String,
    },
    Replace {
        path: WorkspaceRelativePath,
        expected_fingerprint: SourceFingerprint,
        identity: MemoIdentity,
        content: String,
    },
    Remove {
        path: WorkspaceRelativePath,
        expected_fingerprint: SourceFingerprint,
        identity: MemoIdentity,
    },
    ToggleTask {
        path: WorkspaceRelativePath,
        expected_fingerprint: SourceFingerprint,
        /// Stable source identity for the task marker span (absolute byte offset into the file).
        source_identity: TaskSourceIdentity,
    },
    RewriteReminder {
        path: WorkspaceRelativePath,
        reminder: ReminderRef,
        replacement: String,
    },
}

/// Absolute byte span identity for a task list marker inside a workspace file.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TaskSourceIdentity {
    span: ByteSpan,
}

impl TaskSourceIdentity {
    /// Creates a task source identity from an absolute byte span.
    ///
    /// # Errors
    ///
    /// Returns validation when the span is inverted (end < start). Source bounds are checked at plan
    /// time against the live document.
    pub fn try_new(start: usize, end: usize) -> Result<Self, LomoError> {
        if end < start {
            return Err(validation(
                "invalid_task_source_identity",
                "task source identity span must satisfy start <= end",
            ));
        }
        Ok(Self {
            span: ByteSpan::try_new(start, end, end)?,
        })
    }

    #[must_use]
    pub const fn span(&self) -> ByteSpan {
        self.span
    }
}

/// Planned patch: exact replacement of one target span, with byte-stable prefix/suffix.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DocumentPatchPlan {
    path: WorkspaceRelativePath,
    expected_fingerprint: SourceFingerprint,
    target_span: ByteSpan,
    replacement: Vec<u8>,
    result_bytes: Vec<u8>,
    result_fingerprint: SourceFingerprint,
}

impl DocumentPatchPlan {
    #[must_use]
    pub const fn path(&self) -> &WorkspaceRelativePath {
        &self.path
    }

    #[must_use]
    pub const fn expected_fingerprint(&self) -> &SourceFingerprint {
        &self.expected_fingerprint
    }

    #[must_use]
    pub const fn target_span(&self) -> ByteSpan {
        self.target_span
    }

    #[must_use]
    pub fn replacement(&self) -> &[u8] {
        &self.replacement
    }

    #[must_use]
    pub fn result_bytes(&self) -> &[u8] {
        &self.result_bytes
    }

    #[must_use]
    pub const fn result_fingerprint(&self) -> &SourceFingerprint {
        &self.result_fingerprint
    }

    /// Prefix bytes that must remain identical.
    #[must_use]
    pub fn byte_prefix<'a>(&self, source: &'a [u8]) -> &'a [u8] {
        source.get(..self.target_span.start()).unwrap_or(&[])
    }

    /// Suffix bytes that must remain identical.
    #[must_use]
    pub fn byte_suffix<'a>(&self, source: &'a [u8]) -> &'a [u8] {
        source.get(self.target_span.end()..).unwrap_or(&[])
    }
}

/// Plans a pure in-memory document patch against the already-parsed document snapshot.
///
/// # Errors
///
/// - `stale_snapshot` when `expected_fingerprint` does not match the document source.
/// - validation when path/identity/task target is missing or non-unique, content exceeds limits,
///   or mixed newlines prevent a lossless insert decision.
pub fn plan_document_patch(
    document: &WorkspaceDocument,
    command: &DocumentPatchCommand,
) -> Result<DocumentPatchPlan, LomoError> {
    match command {
        DocumentPatchCommand::Append {
            path,
            expected_fingerprint,
            time_part,
            content,
        } => plan_append(document, path, expected_fingerprint, time_part, content),
        DocumentPatchCommand::Replace {
            path,
            expected_fingerprint,
            identity,
            content,
        } => plan_replace(document, path, expected_fingerprint, identity, content),
        DocumentPatchCommand::Remove {
            path,
            expected_fingerprint,
            identity,
        } => plan_remove(document, path, expected_fingerprint, identity),
        DocumentPatchCommand::ToggleTask {
            path,
            expected_fingerprint,
            source_identity,
        } => plan_toggle_task(document, path, expected_fingerprint, source_identity),
        DocumentPatchCommand::RewriteReminder {
            path,
            reminder,
            replacement,
        } => plan_rewrite_reminder(document, path, reminder, replacement),
    }
}

fn plan_rewrite_reminder(
    document: &WorkspaceDocument,
    path: &WorkspaceRelativePath,
    reminder: &ReminderRef,
    replacement: &str,
) -> Result<DocumentPatchPlan, LomoError> {
    ensure_fresh(document, reminder.revision())?;
    validate_reminder_token(replacement)?;
    let memo = find_unique_memo(document, reminder.memo_identity())?;
    if !memo
        .reminders()
        .iter()
        .any(|candidate| candidate.opaque_id() == reminder.opaque_id())
    {
        return Err(validation(
            "reminder_target_not_found",
            "reminder reference does not address an occurrence in the memo revision",
        ));
    }
    let source = document.source();
    let span = ByteSpan::try_new(
        reminder.source_span().start(),
        reminder.source_span().end(),
        source.len(),
    )?;
    let current = source.slice(span)?;
    if current != reminder.token()
        || SourceFingerprint::of_bytes(current.as_bytes()) != *reminder.token_fingerprint()
    {
        return Err(validation(
            "stale_snapshot",
            "reminder token bytes no longer match the bound source occurrence",
        ));
    }
    finish_plan(
        path,
        reminder.revision(),
        source,
        span.start(),
        span.end(),
        replacement.as_bytes().to_vec(),
    )
}

fn ensure_fresh(
    document: &WorkspaceDocument,
    expected: &SourceFingerprint,
) -> Result<(), LomoError> {
    if document.source().fingerprint().as_str() != expected.as_str() {
        return Err(validation(
            "stale_snapshot",
            "document fingerprint does not match expected snapshot",
        ));
    }
    Ok(())
}

fn plan_append(
    document: &WorkspaceDocument,
    path: &WorkspaceRelativePath,
    expected: &SourceFingerprint,
    time_part: &str,
    content: &str,
) -> Result<DocumentPatchPlan, LomoError> {
    ensure_fresh(document, expected)?;
    validate_time_part(time_part)?;
    ResourceBudget::check_editable_memo_chars(content.chars().count())?;

    let source = document.source();
    let newline = choose_newline(source.text_state())?;
    let insert_at = source.len();
    let mut block = format!("- {time_part}");
    if !content.is_empty() {
        block.push_str(newline);
        // Preserve content newlines as-is; only the block separator uses document newline policy.
        block.push_str(content);
    }
    if !block.ends_with('\n') && !block.ends_with('\r') {
        block.push_str(newline);
    }

    let mut replacement = Vec::new();
    if insert_at > 0 && !source_ends_with_newline(source.as_bytes()) {
        replacement.extend_from_slice(newline.as_bytes());
    }
    // Separate from previous memo with a blank line when the file already has content and does not
    // already end with a blank line. When the source already ends with a newline, this is the blank
    // separator; when it does not, the previous branch added the first newline and this adds the
    // second.
    if insert_at > 0 && !ends_with_blank_line(source) {
        replacement.extend_from_slice(newline.as_bytes());
    }
    replacement.extend_from_slice(block.as_bytes());

    finish_plan(path, expected, source, insert_at, insert_at, replacement)
}

fn plan_replace(
    document: &WorkspaceDocument,
    path: &WorkspaceRelativePath,
    expected: &SourceFingerprint,
    identity: &MemoIdentity,
    content: &str,
) -> Result<DocumentPatchPlan, LomoError> {
    ensure_fresh(document, expected)?;
    ResourceBudget::check_editable_memo_chars(content.chars().count())?;
    let memo = find_unique_memo(document, identity)?;
    let source = document.source();
    let newline = choose_newline(source.text_state())?;

    // Replace the entire memo span with a rebuilt header+body using the same time_part.
    let header_line = format!("- {}", memo.time_part());
    let mut block = header_line;
    if !content.is_empty() {
        block.push_str(newline);
        let normalized = content.replace('\r', "");
        // Rewrite content lines with the document newline when content uses \n only.
        let body = if newline == "\n" {
            normalized
        } else {
            normalized.split('\n').collect::<Vec<_>>().join(newline)
        };
        block.push_str(&body);
    }
    // Preserve whether the original memo span ended with a newline by matching neighborhood.
    let span = memo.memo_span();
    let original = source
        .as_bytes()
        .get(span.start()..span.end())
        .unwrap_or(&[]);
    if original_ends_with_newline(original) && !block.ends_with('\n') && !block.ends_with('\r') {
        block.push_str(newline);
    }

    finish_plan(
        path,
        expected,
        source,
        span.start(),
        span.end(),
        block.into_bytes(),
    )
}

fn plan_remove(
    document: &WorkspaceDocument,
    path: &WorkspaceRelativePath,
    expected: &SourceFingerprint,
    identity: &MemoIdentity,
) -> Result<DocumentPatchPlan, LomoError> {
    ensure_fresh(document, expected)?;
    let memo = find_unique_memo(document, identity)?;
    let source = document.source();
    let span = memo.memo_span();
    // Expand removal to absorb one surrounding blank separator when present so the file does not
    // accumulate double blank lines, while never touching other memos' bytes.
    let (start, end) = removal_span(source.as_bytes(), span);
    finish_plan(path, expected, source, start, end, Vec::new())
}

fn plan_toggle_task(
    document: &WorkspaceDocument,
    path: &WorkspaceRelativePath,
    expected: &SourceFingerprint,
    source_identity: &TaskSourceIdentity,
) -> Result<DocumentPatchPlan, LomoError> {
    ensure_fresh(document, expected)?;
    let source = document.source();
    let span = ByteSpan::try_new(
        source_identity.span().start(),
        source_identity.span().end(),
        source.len(),
    )?;
    let marker = source.slice(span)?;
    let replacement = match marker {
        "[ ]" => b"[x]".to_vec(),
        "[x]" | "[X]" => b"[ ]".to_vec(),
        _ => {
            return Err(validation(
                "task_target_not_found",
                "task source identity does not address a unique task marker",
            ));
        }
    };
    finish_plan(
        path,
        expected,
        source,
        span.start(),
        span.end(),
        replacement,
    )
}

fn finish_plan(
    path: &WorkspaceRelativePath,
    expected: &SourceFingerprint,
    source: &SourceBytes,
    start: usize,
    end: usize,
    replacement: Vec<u8>,
) -> Result<DocumentPatchPlan, LomoError> {
    // UTF-8 BOM is document metadata, not memo body. Keep it outside replace/remove targets so
    // non-target bytes (including BOM) stay identical.
    let start = if start == 0
        && source.as_bytes().starts_with(&[0xEF, 0xBB, 0xBF])
        && end >= 3
        && !replacement.starts_with(&[0xEF, 0xBB, 0xBF])
    {
        3
    } else {
        start
    };
    let target_span = ByteSpan::try_new(start, end, source.len())?;
    let mut result_bytes = Vec::with_capacity(source.len() - target_span.len() + replacement.len());
    let source_bytes = source.as_bytes();
    let prefix = source_bytes.get(..start).ok_or_else(|| {
        validation(
            "patch_span_out_of_range",
            "patch start is outside the source buffer",
        )
    })?;
    let suffix = source_bytes.get(end..).ok_or_else(|| {
        validation(
            "patch_span_out_of_range",
            "patch end is outside the source buffer",
        )
    })?;
    result_bytes.extend_from_slice(prefix);
    result_bytes.extend_from_slice(&replacement);
    result_bytes.extend_from_slice(suffix);
    // Result must remain valid UTF-8 (source is UTF-8; replacement is UTF-8).
    if std::str::from_utf8(&result_bytes).is_err() {
        return Err(validation(
            "patch_not_utf8",
            "patch result must remain valid UTF-8",
        ));
    }
    let result_fingerprint = SourceFingerprint::of_bytes(&result_bytes);
    Ok(DocumentPatchPlan {
        path: path.clone(),
        expected_fingerprint: expected.clone(),
        target_span,
        replacement,
        result_bytes,
        result_fingerprint,
    })
}

fn find_unique_memo<'a>(
    document: &'a WorkspaceDocument,
    identity: &MemoIdentity,
) -> Result<&'a crate::document::WorkspaceMemo, LomoError> {
    let matches: Vec<_> = document
        .memos()
        .iter()
        .filter(|memo| memo.identity() == identity)
        .collect();
    match matches.as_slice() {
        [memo] => Ok(*memo),
        [] => Err(validation(
            "memo_target_not_found",
            "memo identity does not address a unique target",
        )),
        _ => Err(validation(
            "memo_target_not_unique",
            "memo identity is not unique",
        )),
    }
}

fn validate_time_part(time_part: &str) -> Result<(), LomoError> {
    if time_part.is_empty()
        || time_part.contains('_')
        || time_part.chars().any(char::is_control)
        || parse_memo_header_line(&format!("- {time_part}")).is_none()
    {
        return Err(validation(
            "invalid_append_time_part",
            "append time_part must be a supported memo timestamp token",
        ));
    }
    Ok(())
}

fn choose_newline(state: SourceTextState) -> Result<&'static str, LomoError> {
    match state.dominant_newline() {
        DominantNewline::None | DominantNewline::Uniform(NewlineKind::Lf) => Ok("\n"),
        DominantNewline::Uniform(NewlineKind::Crlf) => Ok("\r\n"),
        DominantNewline::Uniform(NewlineKind::Cr) => Ok("\r"),
        DominantNewline::Mixed => Err(validation(
            "mixed_newline_ambiguous",
            "mixed newlines prevent a lossless patch newline decision",
        )),
    }
}

const fn source_ends_with_newline(bytes: &[u8]) -> bool {
    matches!(bytes.last(), Some(b'\n' | b'\r'))
}

const fn original_ends_with_newline(bytes: &[u8]) -> bool {
    source_ends_with_newline(bytes)
}

fn ends_with_blank_line(source: &SourceBytes) -> bool {
    if source.is_empty() {
        return true;
    }
    // blank line means two consecutive newline sequences at end, or empty file.
    let text = source.as_str();
    text.ends_with("\n\n")
        || text.ends_with("\r\n\r\n")
        || text.ends_with("\r\r")
        || text == "\n"
        || text == "\r\n"
        || text == "\r"
}

fn removal_span(bytes: &[u8], span: ByteSpan) -> (usize, usize) {
    let mut start = span.start();
    let mut end = span.end();
    // If the memo is followed by an extra blank line (newline after span), keep file tidy by
    // absorbing one additional newline sequence when the previous content also ends with newline.
    if end < bytes.len() {
        if let Some(extra) = match_newline_at(bytes, end) {
            let next = end + extra;
            // Only absorb when it creates a blank separator (another newline or EOF after).
            if next == bytes.len() || match_newline_at(bytes, next).is_some() || start > 0 {
                // Absorb one separating newline after the memo when the next memo would otherwise
                // keep a double blank; for simplicity absorb exactly one newline sequence after.
                end = next;
            }
        }
    } else if start > 0 {
        // Removing last memo: absorb one preceding blank separator if present.
        if let Some(prev) = match_newline_before(bytes, start) {
            let before = start - prev;
            if before == 0 || match_newline_before(bytes, before).is_some() {
                start = before;
            }
        }
    }
    (start, end)
}

fn match_newline_at(bytes: &[u8], index: usize) -> Option<usize> {
    if bytes.get(index) == Some(&b'\r') && bytes.get(index + 1) == Some(&b'\n') {
        Some(2)
    } else if matches!(bytes.get(index), Some(b'\n' | b'\r')) {
        Some(1)
    } else {
        None
    }
}

fn match_newline_before(bytes: &[u8], index: usize) -> Option<usize> {
    if index >= 2 && bytes.get(index - 2) == Some(&b'\r') && bytes.get(index - 1) == Some(&b'\n') {
        Some(2)
    } else if index >= 1 && matches!(bytes.get(index - 1).copied(), Some(b'\n' | b'\r')) {
        Some(1)
    } else {
        None
    }
}
