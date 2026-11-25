//! Behavior Contract:
//! Capability: bounded nonempty previews for code-first memos; owner lomo-store; priority P1.
//! Scenarios:
//! - Given a long opening fence, when a preview is materialized, then its leading content remains visible.
//! - Given Unicode and a short opening paragraph, when bounded, then useful following text survives within the character budget.
//!
//! Test Change Justification:
//! Reason category: schema fixture maintenance.
//! Old behavior/assertion being replaced: the v14 fixture retained later aggregate triggers and partial indexes.
//! Why old assertion is no longer correct: the triggers and partial indexes were introduced in v16 and v17 respectively.
//! Coverage preserved by: preview, URL flag and schema version assertions are unchanged.
//! Why this is not fitting the test to the implementation: the old-version fixture must omit newer schema objects.
//! Observable outcomes: source-prefix bytes, character bound and render IR plain text.
//!
//! TDD proof: reports/manual/interaction-fixes/preview-{red,green}.log.
//!
//! Excludes: Compose height measurement and document writes.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use lomo_store::{BODY_PREVIEW_MAX_CHARS, body_preview};
    use lomo_workspace::{SourceBytes, render_markdown};

    #[test]
    fn v14_projection_upgrade_repairs_empty_preview_and_bare_url_flag() {
        let directory = tempfile::tempdir().unwrap_or_else(|error| panic!("tempdir: {error}"));
        let opened = lomo_store::open_store(directory.path())
            .unwrap_or_else(|error| panic!("open: {error}"));
        let body = format!(
            "```text\nvisible first line\n{}\n```\n\nhttps://example.com",
            "x".repeat(1400)
        );
        opened.connection.execute(
            "INSERT INTO memo(memo_id,source_path,file_fingerprint,created_at_ms,updated_at_ms,body,body_preview,has_url) VALUES('memo','memo.md','fp',1,1,?1,'',0)",
            [&body],
        ).unwrap_or_else(|error| panic!("seed: {error}"));
        opened.connection.execute_batch("DROP TRIGGER memo_stats_insert; DROP TRIGGER memo_stats_delete;
            DROP TRIGGER memo_stats_update; DROP TRIGGER tag_stats_insert; DROP TRIGGER tag_stats_delete; DROP INDEX idx_memo_pending_operation; DROP INDEX idx_memo_reminders; DROP INDEX idx_memo_gallery; DROP INDEX idx_memo_empty_time_part; ALTER TABLE memo DROP COLUMN image_urls_json; ALTER TABLE memo DROP COLUMN time_part;")
            .unwrap_or_else(|error| panic!("historical triggers: {error}"));
        opened
            .connection
            .pragma_update(None, "user_version", 14)
            .unwrap_or_else(|error| panic!("old version: {error}"));
        drop(opened);
        let upgraded = lomo_store::open_store(directory.path())
            .unwrap_or_else(|error| panic!("upgrade: {error}"));
        let (preview, has_url): (String, bool) = upgraded
            .connection
            .query_row(
                "SELECT body_preview,has_url FROM memo WHERE memo_id='memo'",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap_or_else(|error| panic!("read: {error}"));
        assert!(preview.contains("visible first line"));
        assert!(
            has_url,
            "existing URL filters must receive the new semantic facts"
        );
        assert_eq!(upgraded.user_version, lomo_store::STORE_SCHEMA_VERSION);
    }

    #[test]
    fn long_code_first_memo_keeps_a_visible_bounded_prefix() {
        let content = format!(
            "```text\nvisible first line\n{}\n```",
            "code content ".repeat(300)
        );
        let preview = body_preview(&content);
        assert!(
            preview.contains("visible first line"),
            "preview discarded the first code block: {preview:?}"
        );
        assert!(content.starts_with(&preview));
        assert!(preview.chars().count() <= BODY_PREVIEW_MAX_CHARS);
        let source = SourceBytes::try_from_str(&preview)
            .unwrap_or_else(|error| panic!("preview source: {error}"));
        let rendered =
            render_markdown(&source).unwrap_or_else(|error| panic!("preview render: {error}"));
        assert!(rendered.plain_text().contains("visible first line"));
    }

    #[test]
    fn short_heading_does_not_discard_following_long_unicode_paragraph() {
        let content = format!("# Heading\n\n{}", "中文内容".repeat(1000));
        let preview = body_preview(&content);
        assert!(
            preview.contains("中文内容"),
            "preview must contain useful body text"
        );
        assert!(content.starts_with(&preview));
        assert!(preview.chars().count() <= BODY_PREVIEW_MAX_CHARS);
    }
}
