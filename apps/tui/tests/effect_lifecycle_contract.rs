// adversarial-audit: managed player lifecycle end-to-end (spawn → monitor →
// PlayerFinished), editor evidence retention on tool failure, and paste denial coverage
// for fieldless modes beyond Browse.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use super::support::{RuntimeFixture, model_with_memos};
    use lomo_tui::{
        edit_flow::complete_edit,
        editor::CommandRunner,
        effects::{EditTarget, Effect, RuntimeMessage},
        event::{Command, command_from_paste},
        model::{Confirmation, InputMode},
        ops::execute,
    };
    use std::{fs, process::ExitStatus, time::Duration};

    /// `player` is a hot field — set it through the same live-apply path the
    /// Settings save uses instead of poking the private slot.
    fn set_player(runtime: &lomo_tui::ops::TuiRuntime, player: Vec<String>) {
        let mut config = runtime.config();
        config.player = player;
        runtime.apply_config(config);
    }

    /// `OpenAttachment` stages, spawns and hands the child to a monitor thread; the
    /// worker returns immediately and the exit lands as `PlayerFinished`.
    #[test]
    fn player_exit_arrives_as_a_message_while_the_worker_returns() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let media = fixture.runtime.workspace.join("media");
        fs::create_dir_all(&media).expect("media dir");
        fs::write(media.join("note.txt"), b"attachment").expect("attachment");
        let path = lomo_core::RelativeWorkspacePath::parse("media/note.txt").expect("path");

        let (results, inbox) = std::sync::mpsc::sync_channel(256);
        let outbox = lomo_tui::executor::Outbox::new(results);
        let token = lomo_tui::model::CancelToken::live();
        set_player(&fixture.runtime, vec!["false".to_owned()]);
        let reply = execute(
            &fixture.runtime,
            &Effect::OpenAttachment {
                req: lomo_tui::model::Req(1),
                path: path.clone(),
            },
            &outbox,
            &token,
        )
        .expect("a spawned player replies immediately");
        assert!(
            matches!(reply, RuntimeMessage::Message { .. }),
            "the worker must not block on the player: {reply:?}"
        );
        let finished = inbox
            .recv_timeout(Duration::from_secs(10))
            .expect("the monitor thread reports the exit");
        assert!(
            matches!(
                finished,
                RuntimeMessage::PlayerFinished {
                    success: false,
                    diagnostic: Some(_)
                }
            ),
            "a failing player exit is a typed message: {finished:?}"
        );

        set_player(&fixture.runtime, vec!["true".to_owned()]);
        execute(
            &fixture.runtime,
            &Effect::OpenAttachment {
                req: lomo_tui::model::Req(1),
                path: path.clone(),
            },
            &outbox,
            &token,
        )
        .expect("spawn true");
        let finished = inbox
            .recv_timeout(Duration::from_secs(10))
            .expect("monitor reports success");
        assert!(
            matches!(
                finished,
                RuntimeMessage::PlayerFinished { success: true, .. }
            ),
            "a clean exit reports success: {finished:?}"
        );

        set_player(
            &fixture.runtime,
            vec!["lomo-no-such-player-7f3a".to_owned()],
        );
        let error = execute(
            &fixture.runtime,
            &Effect::OpenAttachment {
                req: lomo_tui::model::Req(1),
                path,
            },
            &outbox,
            &token,
        )
        .expect_err("a missing player binary is a typed spawn failure");
        assert!(
            error.to_string().contains("player"),
            "spawn failures keep their diagnostic: {error}"
        );
    }

    struct ExitOneRunner;
    impl CommandRunner for ExitOneRunner {
        fn run_foreground(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<ExitStatus, std::io::Error> {
            std::process::Command::new("false").status()
        }
        fn spawn_managed(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<Box<dyn lomo_tui::editor::ManagedChild>, std::io::Error> {
            Err(std::io::Error::other("unused in this probe"))
        }
    }

    /// A failed editor run must keep the draft and its evidence sidecar on disk and
    /// tell the user where they are.
    #[test]
    fn editor_failure_retains_draft_and_evidence() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let memo = model.selected_memo().expect("memo").clone();
        let before: usize =
            fs::read_dir(&fixture.runtime.paths.drafts_dir).map_or(0, Iterator::count);
        let target = EditTarget::Memo {
            id: memo.id.clone(),
            fingerprint: memo.fingerprint,
            body: "original body".to_owned(),
        };
        let outcome = complete_edit(
            &fixture.runtime,
            &mut model,
            &ExitOneRunner,
            &target,
            None,
            None,
        )
        .expect("editor failure is a retained draft, not a crash");
        assert!(
            outcome.is_none(),
            "a failed memo edit commits nothing: {outcome:?}"
        );
        assert!(
            model.notice.is_some(),
            "the retained-draft notice names the evidence path"
        );
        let mut drafts = fs::read_dir(&fixture.runtime.paths.drafts_dir)
            .expect("drafts dir")
            .map(|entry| {
                entry
                    .expect("entry")
                    .file_name()
                    .to_string_lossy()
                    .into_owned()
            })
            .collect::<Vec<_>>();
        drafts.sort_unstable();
        let new_files = drafts.len().saturating_sub(before);
        assert!(
            new_files >= 2
                && drafts.iter().any(|name| std::path::Path::new(name)
                    .extension()
                    .is_some_and(|ext| ext.eq_ignore_ascii_case("md")))
                && drafts.iter().any(|name| std::path::Path::new(name)
                    .extension()
                    .is_some_and(|ext| ext.eq_ignore_ascii_case("json"))),
            "a failed edit must retain both the draft body and its evidence sidecar: {drafts:?}"
        );
    }

    /// `command_from_paste` denies only `Browse`. Confirm/Message/Help own no text field
    /// yet still receive `Type`, which `input_update` then ignores — the silent swallow
    /// the fix was meant to delete, surviving in every fieldless non-Browse mode.
    #[test]
    fn paste_is_denied_wherever_no_text_field_owns_it() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        for input in [
            InputMode::Confirm(Confirmation::EmptyTrash { count: None }),
            InputMode::Message {
                title: "t".to_owned(),
                lines: vec!["l".to_owned()],
                scroll: 0,
            },
            InputMode::Help { scroll: 0 },
        ] {
            model.input = input;
            let command = command_from_paste("pasted".to_owned(), &model);
            assert_eq!(
                command,
                Some(Command::PasteDenied),
                "a paste with no owning text field must be denied, not dropped: {command:?}"
            );
        }
    }
}
