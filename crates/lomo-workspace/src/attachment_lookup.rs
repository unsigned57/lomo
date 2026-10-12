//! Bare attachment basenames resolve through the managed directory vocabulary.
//!
//! A qualified destination (`/` inside) is an identity: it reads verbatim and
//! never consumes directory evidence. A bare basename (`![](pic.png)`) names a
//! file somewhere under the managed roots in [`ATTACHMENT_LOOKUP_DIRS`] or at
//! the workspace root itself — the resolver collects every candidate and fails
//! closed the moment two distinct paths claim the same name.
//!
//! This module owns the *rule*; the executor-backed directory enumeration that
//! feeds it lives in `lomo-application` (`attachment_lookup`). The unique-name
//! law mirrors `attachment_remap`'s unique-basename evidence and the Android
//! resolvers' pooled-claim verdict: one claimant resolves, zero misses, many
//! refuse.

use lomo_core::RelativeWorkspacePath;

use crate::layout::{IMAGES_ROOT_NAME, MEDIA_ROOT_NAME, VOICE_ROOT_NAME};

/// Managed attachment roots a bare basename may claim, in claim order.
///
/// `media` is the canonical committed root; `images` and `voice` are the
/// platform-configured attachment roots (`IMAGES_ROOT_NAME`/`VOICE_ROOT_NAME`).
/// Files at the workspace root are implicitly candidates as well — the bare
/// spelling *is* the root-relative spelling — so a root file always counts.
/// Non-managed directories (`assets/`, `docs/`, memo roots) never participate:
/// the vocabulary, not the filesystem, bounds basename claims.
pub const ATTACHMENT_LOOKUP_DIRS: &[&str] = &[MEDIA_ROOT_NAME, IMAGES_ROOT_NAME, VOICE_ROOT_NAME];

/// The resolution of one canonical attachment destination against directory
/// evidence.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AttachmentResolution {
    /// The destination is qualified, or exactly one candidate claims it.
    Found(RelativeWorkspacePath),
    /// No candidate carries the basename — the authored spelling stays so the
    /// downstream read reports `document_not_found` on the name the memo wrote.
    Missing,
    /// Two or more distinct paths claim the basename. The hits are sorted and
    /// complete; consumers must surface them, never pick the first.
    Ambiguous(Vec<RelativeWorkspacePath>),
}

/// Resolves `destination` against the set of paths that actually exist.
///
/// `existing` is the caller's enumeration evidence: for a bare basename it must
/// cover the workspace root (basename-only check) plus every file under
/// [`ATTACHMENT_LOOKUP_DIRS`]; for a qualified destination it is ignored —
/// qualified spellings are identities, not lookup requests.
#[must_use]
pub fn resolve_attachment_listing(
    destination: &RelativeWorkspacePath,
    existing: impl IntoIterator<Item = RelativeWorkspacePath>,
) -> AttachmentResolution {
    let name = destination.as_str();
    if name.contains('/') {
        return AttachmentResolution::Found(destination.clone());
    }
    let mut hits: Vec<RelativeWorkspacePath> = existing
        .into_iter()
        .filter(|candidate| candidate_is_basename_claim(candidate, name))
        .collect();
    hits.sort_unstable_by(|a, b| a.as_str().cmp(b.as_str()));
    hits.dedup();
    match hits.len() {
        0 => AttachmentResolution::Missing,
        1 => AttachmentResolution::Found(hits.remove(0)),
        _ => AttachmentResolution::Ambiguous(hits),
    }
}

/// A candidate claims `basename` when it is the root file itself or a
/// same-named file at any depth under a managed attachment root.
fn candidate_is_basename_claim(candidate: &RelativeWorkspacePath, basename: &str) -> bool {
    let text = candidate.as_str();
    if text == basename {
        return true;
    }
    let Some((dir, file)) = text.rsplit_once('/') else {
        return false;
    };
    if file != basename {
        return false;
    }
    let root = dir.split('/').next().unwrap_or(dir);
    ATTACHMENT_LOOKUP_DIRS.contains(&root)
}
