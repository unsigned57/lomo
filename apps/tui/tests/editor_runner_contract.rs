//! Behavior Contract
//! Capability: external editor argv resolution and three-way draft commit.
//! Scenarios: missing editor is an error; empty create cancels; concurrent fingerprint mismatch keeps the draft.
//! Observable outcomes: `CommitDecision`, retained draft files, no vim default.
//! TDD proof: editor runner did not exist.
//! Excludes: real TTY suspend and a particular editor binary.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on missing drafts"
)]
mod tests {
    use std::fs;
    use std::os::unix::process::ExitStatusExt;
    use std::process::ExitStatus;

    use lomo_tui::drafts::{remove_draft, write_conflict_evidence};
    use lomo_tui::editor::{
        CommandRunner, CommitDecision, EditBaseline, EditKind, decide_commit, resolve_editor,
        run_editor,
    };
    use lomo_tui::error::TuiError;
    use tempfile::tempdir;

    struct ScriptedEditor {
        content: String,
    }

    impl CommandRunner for ScriptedEditor {
        fn run_foreground(
            &self,
            _program: &str,
            args: &[String],
        ) -> Result<ExitStatus, std::io::Error> {
            let path = args
                .last()
                .ok_or_else(|| std::io::Error::other("missing draft path"))?;
            fs::write(path, &self.content)?;
            Ok(ExitStatus::from_raw(0))
        }
    }

    #[test]
    fn missing_editor_does_not_assume_vim() {
        let error = resolve_editor(None, None, None).expect_err("must fail closed");
        assert_eq!(error, TuiError::EditorNotConfigured);
        assert!(error.to_string().contains("not assumed"));
    }

    #[test]
    fn config_wins_over_visual_and_editor() {
        let argv = resolve_editor(
            Some(&["kak".to_owned(), "-e".to_owned()]),
            Some("nano"),
            Some("vi"),
        )
        .expect("configured");
        assert_eq!(argv.program, "kak");
        assert_eq!(argv.args, vec!["-e".to_owned()]);
        let visual = resolve_editor(None, Some("nano -S"), Some("vi")).expect("visual");
        assert_eq!(visual.program, "nano");
        let editor_env = resolve_editor(None, None, Some("emacs")).expect("editor env");
        assert_eq!(editor_env.program, "emacs");
        assert_eq!(
            decide_commit(
                &EditKind::Update {
                    memo_id: "m_1".to_owned(),
                },
                "same",
                "same",
                &EditBaseline {
                    fingerprint: Some("aa".to_owned()),
                },
                Some("aa"),
            ),
            CommitDecision::Unchanged
        );
        assert_eq!(
            decide_commit(
                &EditKind::Create,
                "",
                "body",
                &EditBaseline { fingerprint: None },
                None,
            ),
            CommitDecision::Submit {
                content: "body".to_owned(),
            }
        );
    }

    #[test]
    fn empty_create_is_cancelled() {
        let decision = decide_commit(
            &EditKind::Create,
            "",
            "  \n",
            &EditBaseline { fingerprint: None },
            None,
        );
        assert_eq!(decision, CommitDecision::CancelledEmpty);
    }

    #[test]
    fn fingerprint_mismatch_keeps_draft_decision() {
        let decision = decide_commit(
            &EditKind::Update {
                memo_id: "m_1".to_owned(),
            },
            "old",
            "new",
            &EditBaseline {
                fingerprint: Some("aa".to_owned()),
            },
            Some("bb"),
        );
        match decision {
            CommitDecision::Conflict {
                draft_content,
                baseline,
                disk,
            } => {
                assert_eq!(draft_content, "new");
                assert_eq!(baseline, "aa");
                assert_eq!(disk, "bb");
            }
            CommitDecision::CancelledEmpty
            | CommitDecision::Unchanged
            | CommitDecision::Submit { .. } => panic!("expected conflict, got {decision:?}"),
        }
    }

    #[test]
    fn scripted_editor_round_trip_writes_and_can_retain_conflict_evidence() {
        let dir = tempdir().expect("tmpdir");
        let draft = dir.path().join("op-1.md");
        let argv = resolve_editor(Some(&["scripted".to_owned()]), None, None).expect("argv");
        let body = run_editor(
            &ScriptedEditor {
                content: "edited body".to_owned(),
            },
            &argv,
            &draft,
            "initial",
        )
        .expect("run");
        assert_eq!(body, "edited body");
        write_conflict_evidence(dir.path(), "op-1", "base", "disk", &body).expect("evidence");
        assert!(dir.path().join("op-1.conflict.txt").is_file());
        remove_draft(&draft).expect("remove");
        assert!(!draft.exists());
    }
}
