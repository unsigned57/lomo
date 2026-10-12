//! Behavior Contract
//! Capability: reliable quick capture with Unicode editing, private recovery and idempotent submission.
//! Scenarios: multi-line paste/undo; collapse/reopen; workspace isolation; save failure; repeated submit;
//!   the capture journal's directory isolation from the session's `drafts/` evidence store (P-55).
//! Observable outcomes: draft bytes, file permissions, memo identity, preserved browsing state,
//!   evidence-scan results and sweep receipts.
//! TDD proof: repeated commit creates two memos, capture files are world-readable, and draft failures mark the feed failed.
//! Excludes: a physical IME, external editor behavior and OS crash injection.

#[cfg(test)]
pub mod support;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use super::support::{RuntimeFixture, feed, model_with_memos, runtime_at};
    use lomo_tui::{
        drafts::{capture_path, commit_capture, load_capture, persist_capture},
        effects::{Effect, RuntimeMessage},
        event::{Command, TextEdit},
        input::TextBuffer,
        messages::apply_message,
        model::{AppModel, InputMode, LoadStatus, PendingKind, SaveState},
        update::apply_command,
    };
    use std::os::unix::fs::PermissionsExt;

    #[test]
    fn mixed_width_multiline_paste_undo_and_grapheme_deletion_preserve_text() {
        let mut text = TextBuffer::default();
        text.insert("中文 A👩\u{200d}💻\r\n第二行 e\u{301}");
        assert_eq!(text.text(), "中文 A👩\u{200d}💻\n第二行 e\u{301}");
        text.backspace();
        assert_eq!(text.text(), "中文 A👩\u{200d}💻\n第二行 ");
        text.undo();
        assert!(text.text().ends_with("e\u{301}"));
        text.undo();
        assert_eq!(text.text(), "");
        text.redo();
        assert!(text.text().contains("👩\u{200d}💻\n"));
    }

    #[test]
    fn collapse_reopens_the_draft_without_losing_the_browsing_anchor() {
        let mut model = model_with_memos(20, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Move(12)), None);
        let before = feed(&model)
            .expect("fixture and operation must succeed")
            .clone();
        let _effect = apply_command(&mut model, Command::Compose);
        assert_eq!(
            apply_command(&mut model, Command::Type("记下来".to_owned())),
            None
        );
        assert_eq!(
            apply_command(&mut model, Command::Edit(TextEdit::Newline)),
            None
        );
        assert_eq!(
            apply_command(&mut model, Command::Type("第二行".to_owned())),
            None
        );
        let _effect = apply_command(&mut model, Command::Back);
        assert_eq!(model.input, InputMode::Browse);
        let _effect = apply_command(&mut model, Command::Compose);
        assert_eq!(model.draft.text.text(), "记下来\n第二行");
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .anchor,
            before.anchor
        );
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .selected,
            before.selected
        );
    }

    #[test]
    fn capture_recovers_privately_and_is_isolated_by_workspace() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        persist_capture(&fixture.runtime, 7, "private thought")
            .expect("fixture and operation must succeed");
        assert_eq!(
            load_capture(&fixture.runtime)
                .expect("fixture and operation must succeed")
                .composer
                .text
                .text(),
            "private thought"
        );
        assert_eq!(
            std::fs::metadata(
                capture_path(&fixture.runtime).expect("fixture and operation must succeed")
            )
            .expect("fixture and operation must succeed")
            .permissions()
            .mode()
                & 0o777,
            0o600
        );
        let other = runtime_at(fixture.root.path(), "other-notes")
            .expect("fixture and operation must succeed");
        assert_eq!(
            load_capture(&other)
                .expect("fixture and operation must succeed")
                .composer
                .text
                .text(),
            ""
        );
        persist_capture(&other, 1, "other thought").expect("fixture and operation must succeed");
        assert_eq!(
            load_capture(&fixture.runtime)
                .expect("fixture and operation must succeed")
                .composer
                .text
                .text(),
            "private thought"
        );
    }

    #[test]
    fn a_retried_submission_has_one_durable_memo_identity() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        let first = commit_capture(&fixture.runtime, 1, "saved once")
            .expect("fixture and operation must succeed");
        let retried = commit_capture(&fixture.runtime, 1, "saved once")
            .expect("fixture and operation must succeed");
        assert_eq!(first, retried);
        let query = lomo_application::MemoQuery {
            search_text: None,
            filters: lomo_application::MemoFilters::default(),
            sort: lomo_application::MemoSort::default(),
        };
        assert_eq!(
            fixture
                .runtime
                .session
                .query_count(&query)
                .expect("fixture and operation must succeed"),
            1
        );
        assert_eq!(
            load_capture(&fixture.runtime)
                .expect("fixture and operation must succeed")
                .composer
                .text
                .text(),
            ""
        );
    }

    #[test]
    fn failed_capture_retains_text_without_corrupting_the_feed_load_state() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        let _effect = apply_command(&mut model, Command::Compose);
        assert_eq!(
            apply_command(&mut model, Command::Type("keep me".to_owned())),
            None
        );
        let effect = apply_command(&mut model, Command::Commit);
        let Some(Effect::CommitDraft { req, .. }) = effect else {
            panic!("commit request");
        };
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: "disk full".to_owned()
                }
            ),
            None
        );
        assert_eq!(model.draft.text.text(), "keep me");
        assert!(matches!(model.draft.save, SaveState::Failed { .. }));
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .load,
            LoadStatus::Ready
        );
        assert!(
            model
                .status
                .as_ref()
                .is_some_and(|status| status.contains("disk full"))
        );
    }

    /// P-55 drain: pre-relocation installs hold the TUI's `capture-*` journal
    /// files and `op-*` editor drafts inside `state_dir/drafts`. The
    /// marker-gated migration moves exactly those into `capture_dir`, leaves
    /// every `*.json` the evidence store owns untouched, and never re-runs.
    #[test]
    fn the_legacy_drain_vacates_tui_files_from_drafts_exactly_once() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        let paths = &fixture.runtime.paths;
        let legacy = paths.state_dir.join("drafts");
        // `open_runtime` already committed the marker for this fixture — reset
        // it to reenact the first launch after the relocation shipped.
        std::fs::remove_file(paths.state_dir.join("capture-migrated"))
            .expect("clear the migration gate");
        std::fs::create_dir_all(&legacy).expect("legacy drafts");
        for (name, bytes) in [
            ("capture-aaaa.json", &b"{\"schema\":1}"[..]),
            ("capture-aaaa.json.corrupt", b"forensics"),
            ("op-deadbeef.md", b"editor draft"),
            ("op-deadbeef.toml", b"config draft"),
        ] {
            std::fs::write(legacy.join(name), bytes).expect("seed legacy file");
        }
        // `op-*.json` is the evidence mask itself: the session writes real
        // conflict evidence under the same operation-id stem a memo edit
        // uses, so no name rule may relocate it.
        std::fs::write(
            legacy.join("op-aaaa.json"),
            b"{\"operation_id\":\"op-aaaa\",\"draft_content\":\"keep\"}",
        )
        .expect("seed evidence");
        std::fs::write(legacy.join("stray.txt"), b"foreign").expect("seed foreign");

        lomo_tui::drafts::migrate_capture_journal(paths).expect("drain");

        for name in [
            "capture-aaaa.json",
            "capture-aaaa.json.corrupt",
            "op-deadbeef.md",
            "op-deadbeef.toml",
        ] {
            assert!(
                paths.capture_dir.join(name).exists(),
                "{name} moved into capture_dir"
            );
            assert!(!legacy.join(name).exists(), "{name} left drafts/");
        }
        for name in ["op-aaaa.json", "stray.txt"] {
            assert!(
                legacy.join(name).exists(),
                "{name} is not provably TUI-owned and stays in drafts/"
            );
            assert!(!paths.capture_dir.join(name).exists());
        }
        assert!(
            paths.state_dir.join("capture-migrated").exists(),
            "the commit marker lands only after the drain"
        );
        let store = lomo_application::draft::DraftStore::new(
            &lomo_workspace::SessionStateLayout::for_state_dir(&paths.state_dir),
        )
        .expect("the session's evidence store opens");
        let evidence = store
            .list_draft_evidence()
            .expect("the drain leaves one evidence token");
        assert_eq!(
            evidence.len(),
            1,
            "the real evidence record survived the drain in place"
        );
        store
            .read_draft_body(&evidence.first().expect("surviving record").name)
            .expect("only decodable evidence remains");

        // The gate is one-shot: a `capture-*` file appearing after the marker
        // is left to the evidence scan's own verdict — the drain is a
        // migration, not a per-launch pass.
        std::fs::write(legacy.join("capture-late.json"), b"{}").expect("late file");
        lomo_tui::drafts::migrate_capture_journal(paths).expect("second run");
        assert!(
            legacy.join("capture-late.json").exists(),
            "the marker keeps the drain one-shot — no per-launch re-scan"
        );
    }

    /// The migrated record keeps its bytes and filename, so `load_capture`
    /// resumes the same workspace's in-flight draft from the moved file.
    #[test]
    fn a_drained_capture_record_still_loads_as_the_composer_draft() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        persist_capture(&fixture.runtime, 1, "carried over").expect("persist");
        let live = capture_path(&fixture.runtime).expect("capture path");
        let name = live.file_name().expect("capture name").to_owned();
        // Re-stage the record at its pre-relocation location and reopen the
        // migration gate — the shape a legacy install presents on first launch.
        let legacy = fixture.runtime.paths.state_dir.join("drafts");
        std::fs::create_dir_all(&legacy).expect("legacy drafts");
        std::fs::rename(&live, legacy.join(&name)).expect("stage legacy record");
        std::fs::remove_file(fixture.runtime.paths.state_dir.join("capture-migrated"))
            .expect("clear the migration gate");

        lomo_tui::drafts::migrate_capture_journal(&fixture.runtime.paths).expect("drain");

        assert!(!legacy.join(&name).exists(), "the record left drafts/");
        assert!(live.exists(), "the record landed back at the live path");
        let loaded = load_capture(&fixture.runtime).expect("load");
        assert_eq!(loaded.composer.text.text(), "carried over");
    }

    /// P-55 (`audit/tui-ui-defects.md` defect 2): the capture journal is
    /// TUI-private state. It must never share `state_dir/drafts` — the
    /// session's conflict-evidence store — where the evidence listing enumerates
    /// every `*.json` by file token and `read_draft_body` decodes fail-closed as
    /// `DraftBodyFacts`. One `capture-*.json` inside that directory still aborts
    /// the media protection scan outright.
    #[test]
    fn a_persisted_capture_never_enters_the_evidence_drafts_dir() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        persist_capture(&fixture.runtime, 1, "uncommitted draft")
            .expect("fixture and operation must succeed");
        let path = capture_path(&fixture.runtime).expect("fixture and operation must succeed");
        assert!(
            !path.starts_with(fixture.runtime.paths.state_dir.join("drafts")),
            "the capture journal must not share the evidence store's directory: {path:?}"
        );
        let store = lomo_application::draft::DraftStore::new(
            &lomo_workspace::SessionStateLayout::for_state_dir(&fixture.runtime.paths.state_dir),
        )
        .expect("the session's evidence store opens");
        assert_eq!(
            store
                .list_draft_evidence()
                .expect("a TUI capture record must never fail the evidence scan"),
            Vec::new(),
            "capture journal records are not conflict evidence"
        );
    }

    /// The defect's production path end to end: with a composer draft
    /// persisted, the periodic media orphan sweep must still answer
    /// `MediaSweepDone` — never abort with `draft_evidence_corrupt`.
    #[test]
    fn a_persisted_capture_never_stalls_the_media_sweep() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        persist_capture(&fixture.runtime, 1, "held draft")
            .expect("fixture and operation must succeed");
        let mut model = AppModel::new(80, 24);
        let req = model.request(PendingKind::Sweep);
        let (results, _inbox) = std::sync::mpsc::sync_channel(4);
        let outbox = lomo_tui::executor::Outbox::new(results);
        let reply = lomo_tui::ops::execute(
            &fixture.runtime,
            &Effect::MediaSweep {
                req,
                drafts: Vec::new(),
            },
            &outbox,
            &lomo_tui::model::CancelToken::live(),
        )
        .expect("the sweep must not fail on a TUI draft");
        assert!(
            matches!(reply, RuntimeMessage::MediaSweepDone { .. }),
            "expected MediaSweepDone, got {reply:?}"
        );
    }

    #[test]
    fn tag_completion_respects_tag_boundaries_and_can_be_undone() {
        let url = TextBuffer::new("https://example.com/#reading".to_owned());
        assert_eq!(url.tag_prefix(), None);
        let mut text = TextBuffer::new("想法\n#read".to_owned());
        text.complete_tag("reading/book");
        assert_eq!(text.text(), "想法\n#reading/book ");
        text.undo();
        assert_eq!(text.text(), "想法\n#read");
    }
    #[test]
    fn undo_history_is_bounded_and_drops_the_oldest_checkpoint() {
        let mut text = TextBuffer::default();
        for index in 0..120 {
            text.insert(&index.to_string());
        }
        let mut steps = 0;
        loop {
            let before = text.text().to_owned();
            text.undo();
            if text.text() == before {
                break;
            }
            steps += 1;
            assert!(steps <= 120);
        }
        assert_eq!(
            steps, 100,
            "undo history must be bounded at the retained checkpoint cap"
        );
        assert_eq!(
            text.text(),
            "012345678910111213141516171819",
            "evicted checkpoints leave the earliest retained state (before insert 20)"
        );
    }
}
