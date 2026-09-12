//! Global attachment references across active bodies, trash, history, and drafts.

use lomo_core::{LomoError, RelativeWorkspacePath};
use lomo_media::{AttachmentRef, ContentDigest, ReferenceSource, build_refcounts};
use lomo_store::{MemoFilters, MemoQuery, project_content_facts};

use crate::{error::validation, paging::collect_summaries, session::WorkspaceSession};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AttachmentObservation {
    pub relative_path: String,
    pub digest: Option<ContentDigest>,
    pub source: ReferenceSource,
    pub owner_key: String,
}

impl WorkspaceSession {
    /// Collects attachment paths from active memos, trash, in-window history, and private drafts.
    ///
    /// # Errors
    /// Projection and I/O failures.
    pub fn observe_attachments(&self) -> Result<Vec<AttachmentObservation>, LomoError> {
        let mut out = Vec::new();
        let query = MemoQuery {
            search_text: None,
            filters: MemoFilters {
                include_trash: true,
                ..MemoFilters::default()
            },
            sort: lomo_store::MemoSort::default(),
        };
        for summary in collect_summaries(self, &query)? {
            let snapshot = self
                .with_store(|store| store.get_projected_memo(&summary.memo_id))?
                .ok_or_else(|| validation("memo_not_found", "attachment owner disappeared"))?;
            let source = if summary.is_trashed {
                ReferenceSource::TrashMemo
            } else {
                ReferenceSource::CurrentMemo
            };
            push_from_body(self, &mut out, &snapshot.body, source, &summary.memo_id)?;
            append_history(self, &mut out, &summary.memo_id)?;
        }
        Ok(out)
    }

    /// True when any live, trash, or in-window history body still names this relative path.
    ///
    /// # Errors
    /// Projection failures.
    pub fn attachment_is_protected(&self, relative_path: &str) -> Result<bool, LomoError> {
        Ok(self
            .observe_attachments()?
            .iter()
            .any(|item| item.relative_path == relative_path))
    }

    /// Digest refcounts for files that can still be opened through the platform executor.
    ///
    /// # Errors
    /// Digest and I/O failures.
    pub fn attachment_refcounts(
        &self,
    ) -> Result<std::collections::BTreeMap<ContentDigest, lomo_media::DigestRefcount>, LomoError>
    {
        let mut refs = Vec::new();
        for item in self.observe_attachments()? {
            if let Some(digest) = item.digest {
                refs.push(AttachmentRef {
                    digest,
                    source: item.source,
                    owner_key: item.owner_key,
                });
            }
        }
        Ok(build_refcounts(&refs))
    }
}

fn append_history(
    session: &WorkspaceSession,
    out: &mut Vec<AttachmentObservation>,
    memo_id: &str,
) -> Result<(), LomoError> {
    let page = session.with_store(|store| store.list_memo_history(memo_id, None, 20))?;
    for revision in page.items {
        push_from_body(
            session,
            out,
            &revision.content,
            ReferenceSource::HistoryVersion,
            &format!("{}@r{}", memo_id, revision.revision),
        )?;
    }
    Ok(())
}

fn push_from_body(
    session: &WorkspaceSession,
    out: &mut Vec<AttachmentObservation>,
    body: &str,
    source: ReferenceSource,
    owner_key: &str,
) -> Result<(), LomoError> {
    let facts = project_content_facts(body)?;
    for relative_path in facts.attachment_paths {
        let digest = read_digest(session, &relative_path)?;
        out.push(AttachmentObservation {
            relative_path,
            digest,
            source,
            owner_key: owner_key.to_owned(),
        });
    }
    Ok(())
}

fn read_digest(
    session: &WorkspaceSession,
    relative_path: &str,
) -> Result<Option<ContentDigest>, LomoError> {
    let Ok(path) = RelativeWorkspacePath::parse(relative_path) else {
        return Ok(None);
    };
    match session.io().read(&path)? {
        Some(snapshot) => Ok(Some(ContentDigest::of_slice(&snapshot.bytes))),
        None => Ok(None),
    }
}
