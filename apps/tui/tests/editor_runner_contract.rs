//! Behavior Contract
//! Capability: external editor configuration, private drafts and failure recovery.
//! Scenarios: config overrides environment; absent editor errors; successful and failed editor writes survive.
//! Observable outcomes: argv, error classifications, exact draft bytes and 0600 permissions.
//! TDD proof: `run_editor` creates drafts with 0644. Version-baseline behavior is covered by `session_ops_contract`.
//! Excludes: a physical terminal and a particular editor binary.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use lomo_tui::{
        editor::{CommandRunner, resolve_editor, run_editor},
        error::TuiError,
    };
    use std::{
        fs,
        os::unix::{fs::PermissionsExt, process::ExitStatusExt},
        process::ExitStatus,
    };

    struct Editor {
        content: &'static str,
        exit: i32,
    }
    impl CommandRunner for Editor {
        fn run_foreground(
            &self,
            _program: &str,
            args: &[String],
        ) -> Result<ExitStatus, std::io::Error> {
            fs::write(
                args.last()
                    .ok_or_else(|| std::io::Error::other("missing draft"))?,
                self.content,
            )?;
            Ok(ExitStatus::from_raw(self.exit))
        }

        fn spawn_managed(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<Box<dyn lomo_tui::editor::ManagedChild>, std::io::Error> {
            Err(std::io::Error::other("the editor fake never spawns"))
        }
    }

    #[test]
    fn editor_resolution_is_explicit_and_configuration_wins() {
        assert_eq!(
            resolve_editor(None, None, None),
            Err(TuiError::EditorNotConfigured)
        );
        let argv = resolve_editor(
            Some(&["kak".to_owned(), "-e".to_owned()]),
            Some("nano"),
            Some("vi"),
        )
        .expect("fixture and operation must succeed");
        assert_eq!(argv.program, "kak");
        assert_eq!(argv.args, ["-e"]);
        assert_eq!(
            resolve_editor(None, Some("nano -S"), Some("vi"))
                .expect("fixture and operation must succeed")
                .args,
            ["-S"]
        );
        assert_eq!(
            resolve_editor(None, None, Some("emacs"))
                .expect("fixture and operation must succeed")
                .program,
            "emacs"
        );
    }

    #[test]
    fn editor_draft_is_private_and_nonzero_exit_preserves_exact_text() {
        let root = tempfile::tempdir().expect("fixture and operation must succeed");
        let path = root.path().join("draft.md");
        let argv = resolve_editor(Some(&["scripted".to_owned()]), None, None)
            .expect("fixture and operation must succeed");
        assert_eq!(
            run_editor(
                &Editor {
                    content: "edited",
                    exit: 0
                },
                &argv,
                &path,
                "initial"
            )
            .expect("fixture and operation must succeed"),
            "edited"
        );
        assert_eq!(
            fs::metadata(&path)
                .expect("fixture and operation must succeed")
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
        let error = run_editor(
            &Editor {
                content: "still recoverable",
                exit: 256,
            },
            &argv,
            &path,
            "initial",
        )
        .err()
        .ok_or("expected failed editor")
        .expect("fixture and operation must succeed");
        assert!(error.to_string().contains("draft kept"));
        assert_eq!(
            fs::read_to_string(&path).expect("fixture and operation must succeed"),
            "still recoverable"
        );
    }
}
