//! Behavior Contract:
//! - Unit under test: `resolve_attachment_listing` / `ATTACHMENT_LOOKUP_DIRS`
//! - Owning layer: lomo-workspace
//! - Priority tier: P0
//! - Capability: bare Markdown attachment basenames resolve through the managed
//!   directory vocabulary with unique-name semantics; qualified destinations read
//!   verbatim and ambiguity always fails closed.
//!
//! Scenarios:
//! - Given a qualified destination and any candidate set, when resolved, then the
//!   destination answers verbatim without consuming candidates.
//! - Given a bare basename and one file at workspace root, when resolved, then the
//!   root hit answers.
//! - Given a bare basename and one file nested under `media/`, `images/` or
//!   `voice/`, when resolved, then that canonical path answers.
//! - Given a bare basename with files of the same name in two different managed
//!   locations, when resolved, then `Ambiguous` names every hit — never a default.
//! - Given a bare basename with files of the same name at root and under `media/`,
//!   when resolved, then `Ambiguous` names both.
//! - Given a bare basename present only under a non-managed directory, when
//!   resolved, then `Missing` — the managed vocabulary bounds the search.
//! - Given a bare basename with no candidates, when resolved, then `Missing`.
//!
//! Observable outcomes: `AttachmentResolution::{Found, Missing, Ambiguous}`.
//!
//! Excludes: platform IO (the caller lists directories), remote URL destinations
//!   (canonicalization rejects them before resolution), media byte loading.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_core::RelativeWorkspacePath;
    use lomo_workspace::{
        ATTACHMENT_LOOKUP_DIRS, AttachmentResolution, resolve_attachment_listing,
    };

    fn path(raw: &str) -> RelativeWorkspacePath {
        RelativeWorkspacePath::parse(raw).expect("fixture path")
    }

    fn paths(raws: &[&str]) -> Vec<RelativeWorkspacePath> {
        raws.iter().map(|raw| path(raw)).collect()
    }

    #[test]
    fn managed_lookup_dirs_name_the_workspace_attachment_roots() {
        assert_eq!(ATTACHMENT_LOOKUP_DIRS, ["media", "images", "voice"]);
    }

    #[test]
    fn qualified_destination_reads_verbatim_without_directory_search() {
        // Given a qualified destination and a same-named file elsewhere,
        // when resolved, then the destination answers verbatim — the `/`
        // makes it an identity, never a lookup request.
        let resolution = resolve_attachment_listing(
            &path("media/photos/pic.png"),
            paths(&["images/pic.png", "pic.png"]),
        );
        match resolution {
            AttachmentResolution::Found(found) => {
                assert_eq!(found.as_str(), "media/photos/pic.png");
            }
            resolution @ (AttachmentResolution::Missing | AttachmentResolution::Ambiguous(_)) => {
                panic!("qualified destination must read verbatim, got {resolution:?}")
            }
        }
    }

    #[test]
    fn bare_basename_resolves_file_at_workspace_root() {
        let resolution =
            resolve_attachment_listing(&path("pic.png"), paths(&["pic.png", "media/notes.txt"]));
        match resolution {
            AttachmentResolution::Found(found) => assert_eq!(found.as_str(), "pic.png"),
            resolution @ (AttachmentResolution::Missing | AttachmentResolution::Ambiguous(_)) => {
                panic!("root file must resolve, got {resolution:?}")
            }
        }
    }

    #[test]
    fn bare_basename_resolves_unique_nested_managed_hit() {
        // Given a bare basename and exactly one same-named file nested under a
        // managed root, when resolved, then that canonical path answers —
        // nested directories stop breaking bare destinations.
        for nested in [
            "media/photos/2026/pic.png",
            "images/pic.png",
            "voice/pic.png",
        ] {
            let resolution =
                resolve_attachment_listing(&path("pic.png"), paths(&[nested, "memos/index.md"]));
            match resolution {
                AttachmentResolution::Found(found) => assert_eq!(found.as_str(), nested),
                resolution @ (AttachmentResolution::Missing
                | AttachmentResolution::Ambiguous(_)) => {
                    panic!("unique nested managed hit must resolve, got {resolution:?}")
                }
            }
        }
    }

    #[test]
    fn same_basename_in_two_managed_locations_is_ambiguous() {
        // Given one basename claimed by two managed locations, when resolved,
        // then Ambiguous names every hit — the resolver never picks one.
        let resolution = resolve_attachment_listing(
            &path("pic.png"),
            paths(&["images/pic.png", "media/photos/pic.png", "memos/index.md"]),
        );
        match resolution {
            AttachmentResolution::Ambiguous(hits) => {
                assert_eq!(
                    hits.iter()
                        .map(RelativeWorkspacePath::as_str)
                        .collect::<Vec<_>>(),
                    ["images/pic.png", "media/photos/pic.png"]
                );
            }
            resolution @ (AttachmentResolution::Found(_) | AttachmentResolution::Missing) => {
                panic!("two managed hits must be Ambiguous, got {resolution:?}")
            }
        }
    }

    #[test]
    fn same_basename_at_root_and_under_media_is_ambiguous() {
        let resolution =
            resolve_attachment_listing(&path("pic.png"), paths(&["media/pic.png", "pic.png"]));
        match resolution {
            AttachmentResolution::Ambiguous(hits) => {
                assert_eq!(
                    hits.iter()
                        .map(RelativeWorkspacePath::as_str)
                        .collect::<Vec<_>>(),
                    ["media/pic.png", "pic.png"]
                );
            }
            resolution @ (AttachmentResolution::Found(_) | AttachmentResolution::Missing) => {
                panic!("root + managed hits must be Ambiguous, got {resolution:?}")
            }
        }
    }

    #[test]
    fn bare_basename_outside_managed_vocabulary_is_missing() {
        // Given the same-named file lives only under a user directory that is
        // not a managed attachment root, when resolved, then Missing — the
        // vocabulary bounds basename claims.
        let resolution = resolve_attachment_listing(
            &path("pic.png"),
            paths(&["assets/pic.png", "docs/pic.png", "memos/pic.png"]),
        );
        assert!(
            matches!(resolution, AttachmentResolution::Missing),
            "non-managed directories never satisfy a bare basename"
        );
    }

    #[test]
    fn bare_basename_with_no_candidates_is_missing() {
        let resolution =
            resolve_attachment_listing(&path("pic.png"), paths(&["media/photo.png", "index.md"]));
        assert!(matches!(resolution, AttachmentResolution::Missing));
    }
}
