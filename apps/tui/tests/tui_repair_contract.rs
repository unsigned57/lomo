//! Adversarial re-verification of the TUI repair surface: tea/command/
//! cache/render/image/config seams re-probed round over round. Merged from
//! the numbered re-audit rounds.

#[cfg(test)]
pub mod support;

#[cfg(test)]
mod tests {

    // adversarial-reaudit round 2: independent re-verification of the TUI repair
    // surface after the first-round evidence suites (tea/command/cache/render/
    // image/config reaudit) all went green.
    //
    // # Behavior Contract
    //
    // Capability: proves (or refutes) that the verdict/identity/degradation and
    // geometry fixes actually hold under second-order interleavings the first
    // round did not probe —
    //
    // - A. TEA: the graphics verdict is *terminal* once landed (the watchdog's
    //   fail-closed `Unsupported` must survive a probe answer that arrives after
    //   `PROBE_BUDGET`); `Pending` tombstones bar a revoked request from the lane
    //   door AND from landing; `RuntimeReady` merges into live shell state;
    //   request identity (`RefreshReader { id }`, `Date { dialog }`) decides what
    //   a receipt may touch; parked receipts drain only on a real transition.
    // - B. Render/input: the setup wizard's hardware cursor sits on the visual
    //   row the wrapped paragraph actually drew — logical `field_row` is not a
    //   visual row once any earlier line wraps; the feed's `select_visible`
    //   repair honors the same no-Gap rule its check enforces.
    // - C. Media: only image-kind attachments enter the decode queue; zero-geometry
    //   readers mint no image work; player stderr is drained to EOF with bounded
    //   retention.
    // - D. Config/first-run: hostile argv spellings round-trip the registry's
    //   render→parse cycle and its display→edit cycle; a config deleted mid-edit
    //   or mid-save fails closed and loud; the `initialized` marker is honest.
    //
    // Owning layer: `messages.rs` (verdict landing), `model.rs` (install merge),
    // `executor.rs` (lane admission), `overlays.rs` (cursor geometry),
    // `graphics.rs`/`media.rs` (image pipeline), `config.rs`/`xdg.rs`/`drafts.rs`.
    //
    // TDD proof: evidence-first, not change-driven — assertions state the CORRECT
    // contract; a RED failure is genuine residual-defect evidence kept RED
    // deliberately and mapped into `audit/11-再复审-TUI修复面.md`. Production code
    // is not modified by this audit.
    //
    // Exclusions: real stdin/terminal probing (`StdioProber` is untouched), the
    // host-private `deliver`/`tick` internals (the race is provable through the
    // public `messages::apply_message` seam they delegate to), the Kotlin side.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures must be constructed successfully before probing; \
                  a failed expectation is itself audit evidence"
    )]
    mod repair_surface {
        use crate::support::{
            RuntimeFixture, feed, feed_mut, memo, model_with_memos, ready_graphics,
        };
        use lomo_tui::{
            config::{
                ConfigProbe, ConfigProposal, FieldValue, SettingsField, config_file,
                parse_config_toml, probe_config, save_config,
            },
            drafts::{load_capture, persist_capture},
            edit_flow::complete_edit,
            editor::{CommandRunner, ManagedChild},
            effects::{EditTarget, Effect, RuntimeMessage},
            event::Command,
            executor::{Outbox, Runner, Scheduler, Submit},
            feed_layout,
            graphics::{self, GraphicsVerdict, ImageState},
            input::TextBuffer,
            media::drain_player_stderr,
            messages::apply_message,
            model::{
                AppModel, BadgeClass, CancelToken, CardPosition, InputMode, ParkedReply, Pending,
                PendingKind, Picker, PickerKind, Req, RevisionRow, SetupState, Severity,
                TextAnchor, View,
            },
            ops::{self, RuntimeSlot},
            update::apply_command,
            xdg::{INITIALIZED_MARKER, RuntimePaths},
        };
        use lomo_workspace::MemoId;
        use ratatui::{
            Terminal,
            backend::{Backend, TestBackend},
            buffer::Buffer,
        };
        use ratatui_image::picker::ProtocolType;
        #[cfg(unix)]
        use std::os::unix::process::ExitStatusExt;
        #[cfg(windows)]
        use std::os::windows::process::ExitStatusExt;
        use std::{
            io::Read,
            path::PathBuf,
            process::ExitStatus,
            sync::{
                Arc, Mutex,
                atomic::{AtomicUsize, Ordering},
                mpsc::sync_channel,
            },
            time::Duration,
        };

        fn id(raw: &str) -> MemoId {
            MemoId::parse(raw).expect("fixture id")
        }

        fn attachment(raw: &str) -> lomo_core::RelativeWorkspacePath {
            lomo_core::RelativeWorkspacePath::parse(raw).expect("fixture path")
        }

        fn revisions() -> Vec<RevisionRow> {
            vec![RevisionRow {
                revision: 1,
                stamp: "2026-09-11 12:00:00".to_owned(),
                preview: "body".to_owned(),
            }]
        }

        fn success() -> ExitStatus {
            #[cfg(unix)]
            return ExitStatus::from_raw(0);
            #[cfg(windows)]
            return ExitStatus::from_raw(0);
            #[cfg(not(any(unix, windows)))]
            unreachable!("scripted exit status has no portable constructor")
        }

        /// Consume a dispatch's effect — `Option<Effect>` is `#[must_use]`; the
        /// probes that never execute effects sink them explicitly.
        fn sink(_: Option<Effect>) {}

        fn draw(model: &AppModel) -> Terminal<TestBackend> {
            let mut terminal = Terminal::new(TestBackend::new(model.width, model.height))
                .expect("fixture backend must build");
            terminal
                .draw(|frame| lomo_tui::ui::draw(frame, model))
                .expect("frame must draw");
            terminal
        }

        /// The per-row cell text of a drawn frame.
        fn rows(buffer: &Buffer) -> Vec<String> {
            (buffer.area.top()..buffer.area.bottom())
                .map(|y| {
                    (buffer.area.left()..buffer.area.right())
                        .map(|x| buffer[(x, y)].symbol())
                        .collect()
                })
                .collect()
        }

        // =====================================================================
        // A. TEA — verdict terminality, request identity, tombstones, merge
        // =====================================================================

        /// F-IMG-3 round 2: the watchdog answers a wedged probe with a fail-closed
        /// `Unsupported` verdict. The probe thread is still alive and its real
        /// `Ready` verdict lands LATE — a verdict that landed must be terminal, or
        /// the "exactly one verdict lifts the gate" contract (host.rs:787) is a lie
        /// and the image pipeline re-arms against a terminal the loop already
        /// stopped gating stdin for.
        #[test]
        fn a_landed_unsupported_verdict_is_terminal_against_a_late_probe() {
            let mut card = memo("img-memo", "body with an attachment").expect("memo");
            card.attachments = vec![attachment("media/pic.png")];
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            model.view = View::Reader {
                memo: card,
                anchor: TextAnchor::default(),
            };
            assert!(
                matches!(model.graphics, GraphicsVerdict::Probing),
                "the session starts gated while the probe is in flight"
            );

            // The watchdog's fail-closed answer lands first — the gate opens for
            // text-only operation.
            sink(apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: GraphicsVerdict::Unsupported {
                        diagnostic: "graphics probe did not answer in time".to_owned(),
                    },
                },
            ));
            assert!(
                matches!(model.graphics, GraphicsVerdict::Unsupported { .. }),
                "the watchdog verdict must land"
            );

            // The wedged probe's real answer arrives late.
            let effect = apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: ready_graphics(ProtocolType::Halfblocks),
                },
            );
            assert!(
                matches!(model.graphics, GraphicsVerdict::Unsupported { .. }),
                "a landed verdict is terminal — the late probe answer must not \
                 reopen the capability gate, got {:?}",
                model.graphics
            );
            assert!(
                effect.is_none(),
                "a terminal verdict must not re-arm image hydration: {effect:?}"
            );
        }

        /// The same terminality contract in the other direction: a landed `Ready`
        /// must never regress to `Probing` — a synthetic/late message carrying the
        /// in-flight state would re-gate stdin and re-arm the watchdog into a
        /// bogus `Unsupported` on the next tick.
        #[test]
        fn a_landed_verdict_never_regresses_to_probing() {
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            sink(apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: ready_graphics(ProtocolType::Halfblocks),
                },
            ));
            assert!(
                matches!(model.graphics, GraphicsVerdict::Ready(_)),
                "the real probe answer must land"
            );

            sink(apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: GraphicsVerdict::Probing,
                },
            ));
            assert!(
                matches!(model.graphics, GraphicsVerdict::Ready(_)),
                "no message may rewind a landed verdict to in-flight: {:?}",
                model.graphics
            );
        }

        /// The flip side that does hold: when the reader's image set retires —
        /// the view leaves the reader, so `hydrate_images` runs the registry's
        /// retire arm — every in-flight decode is tombstoned and its late reply
        /// degrades instead of landing.
        ///
        /// This arm used to ride a `Ready → Unsupported` verdict flip, but a
        /// landed `Ready` is terminal (13-T-01): the probe reports exactly once,
        /// so the only `GraphicsDetected` that could still arrive is a fabricated
        /// death notice — and it degrades. Navigating back to the feed is the
        /// reachable trigger for the same `retire_images` machinery.
        #[test]
        fn a_retired_reader_view_tombs_its_in_flight_image_requests() {
            let mut card = memo("img-memo", "body").expect("memo");
            card.attachments = vec![attachment("media/pic.png")];
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            let feed_view = std::mem::replace(
                &mut model.view,
                View::Reader {
                    memo: card,
                    anchor: TextAnchor::default(),
                },
            );
            model.graphics = ready_graphics(ProtocolType::Halfblocks);

            let Some(Effect::LoadImage { req, request }) = graphics::hydrate_images(&mut model)
            else {
                panic!("a ready reader with an image attachment must mint decode work");
            };
            assert!(model.pending.contains(req), "the decode intent is live");

            // The reader leaves mid-decode: queued decodes are revoked before the
            // lane could pick them up.
            model.view = feed_view;
            assert_eq!(
                graphics::hydrate_images(&mut model),
                None,
                "a non-reader view mints no image work"
            );
            assert!(
                model.images.is_empty(),
                "the image set retires with the reader"
            );
            assert!(
                model
                    .pending
                    .token(req)
                    .is_some_and(|token| token.is_cancelled()),
                "the in-flight decode request must be tombstoned, not just removed"
            );

            // The late decode reply settles quietly — it may not resurrect state.
            let status_before = model.status.clone();
            assert_eq!(
                apply_message(
                    &mut model,
                    RuntimeMessage::Image {
                        req,
                        request,
                        result: Err("decode finished too late".to_owned()),
                    }
                ),
                None,
                "a dead decode's reply issues no further work"
            );
            assert!(
                model.images.is_empty(),
                "a dead decode's reply must not rewrite the image set"
            );
            assert_eq!(
                model.status, status_before,
                "a superseded data reply settles quietly"
            );
        }

        /// `RuntimeReady` installs what bootstrap prepared — the view stack, tags,
        /// prepared modal input — and keeps what the shell owned: pending intent,
        /// parked receipts, typed draft, feedback, the landed verdict, watcher
        /// state and the terminal geometry.
        #[test]
        fn runtime_ready_merges_into_the_live_shell() {
            let mut model = AppModel::new(80, 24);
            let live_req = model.request(PendingKind::Tags);
            let shell_marker = model.next_req();
            model.draft.text = TextBuffer::new("typed while booting".to_owned());
            model.draft.revision = 1;
            model.set_status("boot-phase toast");
            model.raise_badge(Severity::Warn, BadgeClass::Watch, "watch down".to_owned());
            model.graphics = ready_graphics(ProtocolType::Halfblocks);
            model.watcher_active = true;
            model.parked.push_back(ParkedReply::History {
                id: id("memo-0"),
                revisions: revisions(),
            });

            let mut prepared = model_with_memos(2, 120, 40).expect("prepared model");
            let prepared_marker = prepared.next_req();
            prepared.set_tags(vec!["boot/tag".to_owned()]);
            prepared.status = Some("prepared toast".to_owned());
            prepared.input = InputMode::Message {
                title: "overdue".to_owned(),
                lines: vec!["one task is overdue".to_owned()],
                scroll: 0,
            };

            let boot_req = model.request(PendingKind::Bootstrap);
            assert_eq!(
                apply_message(
                    &mut model,
                    RuntimeMessage::RuntimeReady {
                        req: boot_req,
                        model: Box::new(prepared),
                    }
                ),
                None
            );

            // The prepared product landed:
            let prepared_feed = feed(&model).expect("the prepared feed view installs");
            assert_eq!(prepared_feed.memos.len(), 2);
            assert_eq!(model.tags(), &["boot/tag".to_owned()]);
            assert!(
                matches!(model.input, InputMode::Message { .. }),
                "a prepared modal takes the idle Browse focus"
            );
            // The shell's live state survived:
            assert!(
                model.pending.contains(live_req),
                "a live in-flight intent must survive the install"
            );
            assert_eq!(model.parked.len(), 1, "parked receipts survive the install");
            assert_eq!(
                model.draft.text.text(),
                "typed while booting",
                "user text typed during bootstrap outranks the recovered draft"
            );
            assert_eq!(
                model.status.as_deref(),
                Some("boot-phase toast"),
                "the shell's fresher feedback wins over the prepared toast"
            );
            assert!(
                model
                    .badges
                    .iter()
                    .any(|badge| badge.class == BadgeClass::Watch),
                "the live badge survives the install"
            );
            assert!(
                matches!(model.graphics, GraphicsVerdict::Ready(_)),
                "the landed probe verdict stays the shell's"
            );
            assert!(model.watcher_active, "watcher liveness stays the shell's");
            assert_eq!((model.width, model.height), (80, 24), "geometry is live");
            let after = model.next_req();
            assert!(
                after > shell_marker && after > prepared_marker,
                "request identity is session-monotone across the install"
            );
        }

        /// 09-F-03: a request cancelled between issue and dispatch is dropped at
        /// lane admission — the tombstone, not a post-hoc result discard, is the
        /// mechanism; the runner must never observe it.
        #[test]
        fn a_revoked_request_never_reaches_the_lane() {
            let fixture = RuntimeFixture::new().expect("fixture");
            let slot = Arc::new(RuntimeSlot::ready(Arc::new(fixture.runtime)));
            let ran = Arc::new(Mutex::new(Vec::new()));
            let runner: Runner = {
                let ran = Arc::clone(&ran);
                Arc::new(move |_runtime, effect, _outbox, _token| {
                    ran.lock().expect("ran").push(effect.req());
                    Ok(RuntimeMessage::Changed {
                        req: effect.req(),
                        status: "ran".to_owned(),
                    })
                })
            };
            let scheduler = Scheduler::spawn_with(&slot, &runner).expect("scheduler");

            let mut pending = Pending::default();
            let req = Req(77);
            pending.register(req, PendingKind::Mutation);
            pending.cancel(req);
            assert_eq!(
                scheduler.submit(&mut pending, Effect::Quit { req }),
                Submit::Dropped,
                "a revoked request is dropped at admission, not queued"
            );
            scheduler
                .finish(Duration::from_secs(5))
                .expect("lanes join");
            assert!(
                ran.lock().expect("ran").is_empty(),
                "the runner must never observe a revoked request"
            );
        }

        /// A-10 round 2: editing the date text retires the in-flight resolution's
        /// verdict AND revokes the request — its late `Date` reply cannot bind a
        /// filter the user overwrote, while its `Failed` reply still leaves a
        /// status trace on the shared toast channel (never re-opening the dialog's
        /// error the user just cleared).
        #[test]
        fn an_edited_date_dialog_outlives_its_cancelled_resolution() {
            let mut model = model_with_memos(3, 80, 24).expect("fixture");
            sink(apply_command(&mut model, Command::CustomDate));
            sink(apply_command(
                &mut model,
                Command::Type("garbage".to_owned()),
            ));
            let Some(Effect::Date { req, text }) = apply_command(&mut model, Command::Accept)
            else {
                panic!("accepting the dialog must issue its resolution");
            };
            assert_eq!(text, "garbage");

            // The resolution's own failure lands on the still-awaiting dialog.
            sink(apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: "unparsable date".to_owned(),
                },
            ));
            let InputMode::Date { error, .. } = &model.input else {
                panic!("the dialog stays open while the request resolves");
            };
            assert_eq!(error.as_deref(), Some("unparsable date"));

            // A real edit retires the stale verdict…
            sink(apply_command(&mut model, Command::Type("!".to_owned())));
            let InputMode::Date { error, .. } = &model.input else {
                panic!("the dialog is still open");
            };
            assert_eq!(error, &None, "the edit clears the stale error");

            // …and the next resolution's own lifecycle is cancellable: its reply
            // degrades instead of binding a filter the user overwrote.
            let Some(Effect::Date { req: second, .. }) = apply_command(&mut model, Command::Accept)
            else {
                panic!("the re-accepted dialog issues a fresh resolution");
            };
            sink(apply_command(&mut model, Command::Type("?".to_owned())));
            assert!(
                model
                    .pending
                    .token(second)
                    .is_some_and(|token| token.is_cancelled()),
                "editing must tombstone the in-flight resolution"
            );

            assert_eq!(
                apply_message(
                    &mut model,
                    RuntimeMessage::Date {
                        req: second,
                        from: 1,
                        until: 2,
                        label: "late".to_owned(),
                    }
                ),
                None,
                "a superseded date reply issues no requery"
            );
            let feed = feed(&model).expect("feed");
            assert!(
                feed.query.filters.date_from_inclusive_ms.is_none()
                    && feed.query.date_label.is_none(),
                "the abandoned resolution must not bind a filter"
            );
            let InputMode::Date { error, .. } = &model.input else {
                panic!("the dialog is still open");
            };
            assert!(
                error.is_none(),
                "a superseded data reply leaves the field alone"
            );

            sink(apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req: second,
                    diagnostic: "resolver blew up".to_owned(),
                },
            ));
            assert_eq!(
                model.status.as_deref(),
                Some("resolver blew up"),
                "a superseded failure still leaves a status trace"
            );
            let InputMode::Date { error, .. } = &model.input else {
                panic!("the dialog is still open");
            };
            assert!(
                error.is_none(),
                "a superseded failure must not resurrect the dialog's error"
            );
        }

        /// 09-F-04: `RefreshReader { id }` pins the landing slot AND the receipt
        /// payload must name the same memo — a reply carrying a different memo's
        /// body or a different memo's `MemoGone` is a protocol violation, never
        /// evidence about the open reader.
        #[test]
        fn a_refresh_reader_receipt_must_name_its_own_memo() {
            let target = id("memo-x");
            let other = id("memo-y");
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            model.view = View::Reader {
                memo: memo("memo-x", "the open body").expect("memo"),
                anchor: TextAnchor::default(),
            };

            // A ReadMemo for a different memo under this intent: ignored.
            let req = model.request(PendingKind::RefreshReader { id: target.clone() });
            assert_eq!(
                apply_message(
                    &mut model,
                    RuntimeMessage::ReadMemo {
                        req,
                        memo: Box::new(memo("memo-y", "someone else's body").expect("memo")),
                    }
                ),
                None
            );
            let View::Reader { memo: shown, .. } = &model.view else {
                panic!("the reader must still be open");
            };
            assert_eq!(shown.id, target, "a foreign body may never land");
            assert_eq!(shown.summary, "the open body");

            // A MemoGone for a different memo under this intent: ignored — no
            // toast, no view churn.
            let req = model.request(PendingKind::RefreshReader { id: target.clone() });
            let status_before = model.status.clone();
            assert_eq!(
                apply_message(&mut model, RuntimeMessage::MemoGone { req, id: other }),
                None
            );
            assert!(
                matches!(model.view, View::Reader { .. }),
                "a miss for another memo never destroys the open reader"
            );
            assert_eq!(
                model.status, status_before,
                "a foreign miss leaves no evidence on the open reader"
            );

            // A MemoGone for the refresh's own memo: the miss is real evidence —
            // it toasts (the reader keeps its last-known body rather than
            // vanishing mid-read).
            let req = model.request(PendingKind::RefreshReader { id: target.clone() });
            assert_eq!(
                apply_message(&mut model, RuntimeMessage::MemoGone { req, id: target }),
                None
            );
            assert!(
                model.status.is_some(),
                "the own-memo miss leaves the miss toast"
            );
        }

        /// A claimed request whose receipt is the wrong shape is a protocol
        /// violation: it degrades quietly instead of mutating whatever state is
        /// current.
        #[test]
        fn a_wrong_shaped_receipt_never_lands() {
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            let req = model.request(PendingKind::Tags);
            assert_eq!(
                apply_message(
                    &mut model,
                    RuntimeMessage::Bodies {
                        req,
                        bodies: Vec::new(),
                    }
                ),
                None,
                "a wrong-shape receipt issues no follow-up work"
            );
            assert!(
                model.tags().is_empty(),
                "the tag dictionary must not be touched by a mis-shaped receipt"
            );
            assert!(
                !model.pending.contains(req),
                "the claim is still consumed — no wedged intent"
            );
        }

        /// A-14/I5 round 2: a reply parked while a dialog owns the focus survives
        /// a `RuntimeReady` install (the queue is shell state, not prepared
        /// state), and it is delivered only when the model returns to `Browse` —
        /// the install itself does not pop it over the still-open input.
        #[test]
        fn a_parked_receipt_survives_runtime_install_until_the_next_transition() {
            let mut model = model_with_memos(2, 80, 24).expect("fixture");
            model.input = InputMode::Picker(Picker {
                kind: PickerKind::Dates,
                text: TextBuffer::default(),
                selected: 0,
                identity: None,
            });

            // A History receipt arrives while the picker owns the focus.
            let hist_req = model.request(PendingKind::History { id: id("memo-0") });
            sink(apply_message(
                &mut model,
                RuntimeMessage::History {
                    req: hist_req,
                    id: id("memo-0"),
                    revisions: revisions(),
                },
            ));
            assert_eq!(
                model.parked.len(),
                1,
                "the reply parks rather than stealing the picker"
            );

            // Bootstrap lands carrying a modal of its own: the busy input keeps
            // focus, the prepared modal becomes the unread Notice badge, and the
            // parked queue is carried over — not drained over the picker.
            let mut prepared = model_with_memos(2, 80, 24).expect("prepared model");
            prepared.input = InputMode::Message {
                title: "overdue".to_owned(),
                lines: vec!["task due".to_owned()],
                scroll: 0,
            };
            let boot = model.request(PendingKind::Bootstrap);
            sink(apply_message(
                &mut model,
                RuntimeMessage::RuntimeReady {
                    req: boot,
                    model: Box::new(prepared),
                },
            ));
            assert!(
                matches!(model.input, InputMode::Picker(_)),
                "the install must not replace the live input"
            );
            assert_eq!(
                model.parked.len(),
                1,
                "the install must not drain the parked queue over a live input"
            );
            assert!(
                model
                    .badges
                    .iter()
                    .any(|badge| badge.class == BadgeClass::Notice),
                "the prepared modal is registered as unread, not dropped"
            );

            // Returning to Browse is the real delivery point.
            sink(apply_command(&mut model, Command::Back));
            let InputMode::Picker(picker) = &model.input else {
                panic!("the parked history receipt must open its picker on Browse");
            };
            assert!(
                matches!(picker.kind, PickerKind::History { .. }),
                "the parked receipt delivers its own picker, not a fresh one"
            );
        }

        /// The wizard's Enter mints `Effect::SetupConfirmed` — the host rewrites
        /// it into `Bootstrap { Mint }` before the lane ever sees it. If that
        /// rewrite is ever bypassed, `ops::execute` refuses loudly and the failure
        /// lands back on the wizard as an editable error, not a dead view.
        #[test]
        fn a_leaked_setup_confirmation_fails_closed_back_into_the_wizard() {
            let fixture = RuntimeFixture::new().expect("fixture");
            let mut model = AppModel::new(80, 24);
            let req = model.request(PendingKind::Bootstrap);
            model.input = InputMode::Setup(SetupState::new(
                config_file(&fixture.runtime.paths),
                ConfigProposal {
                    workspace: fixture.runtime.workspace.clone(),
                    time_zone: "UTC".to_owned(),
                    previously_initialized: false,
                    recorded_workspace: None,
                },
                req,
                None,
            ));

            let Some(effect) = apply_command(&mut model, Command::Accept) else {
                panic!("the wizard's confirmation must issue an effect");
            };
            assert!(
                matches!(model.input, InputMode::Setup(ref setup) if setup.awaiting),
                "a dispatched confirmation locks the wizard while it runs"
            );

            // The leak path: the confirmation reaching the worker unrewritten.
            let (sender, _replies) = sync_channel(4);
            let outbox = Outbox::new(sender);
            let error = ops::execute(&fixture.runtime, &effect, &outbox, &CancelToken::live())
                .expect_err("a raw SetupConfirmed must be refused by the worker");
            assert!(
                error.to_string().contains("runtime slot")
                    || error.to_string().contains("bootstrap"),
                "the refusal names the boundary it protects: {error}"
            );
            assert!(
                !config_file(&fixture.runtime.paths).exists(),
                "the refused path minted nothing"
            );

            // The lane reports the refusal — the wizard gets its editable error.
            sink(apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: error.to_string(),
                },
            ));
            let InputMode::Setup(setup) = &model.input else {
                panic!("the failure must return to the wizard, not a dead view");
            };
            assert!(
                !setup.awaiting,
                "a failed confirmation unlocks the wizard for retry"
            );
            assert!(
                setup.error.as_deref().is_some_and(|text| !text.is_empty()),
                "the wizard carries the diagnostic"
            );
        }

        /// The symmetric leak class: `Effect::Edit` is a foreground handoff — the
        /// host suspends the terminal and never lets a lane see it; its `req` is
        /// minted but deliberately NEVER registered (effects.rs:172). If it ever
        /// reached `ops::execute` the refusal must fail loudly on the shared
        /// status channel instead of touching state.
        #[test]
        fn a_leaked_foreground_edit_effect_is_refused_loud() {
            let fixture = RuntimeFixture::new().expect("fixture");
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            let req = model.next_req(); // the handoff's unregistered identity
            let (sender, _replies) = sync_channel(4);
            let outbox = Outbox::new(sender);
            let error = ops::execute(
                &fixture.runtime,
                &Effect::Edit {
                    req,
                    target: EditTarget::Capture,
                },
                &outbox,
                &CancelToken::live(),
            )
            .expect_err("a foreground handoff must be refused on a worker");
            assert!(
                error.to_string().contains("foreground"),
                "the refusal names the boundary it protects: {error}"
            );

            sink(apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: error.to_string(),
                },
            ));
            assert_eq!(
                model.status.as_deref(),
                Some(error.to_string().as_str()),
                "the unregistered request's failure still leaves a status trace"
            );
        }

        // =====================================================================
        // B. Render/input — the setup cursor vs the wrapped paragraph
        // =====================================================================

        /// 09-I6-06 made `field_row` a derived logical index — but the paragraph
        /// renders `wrapped_lines(&lines, inner.width)`: once ANY earlier logical
        /// line wraps (a long recorded-workspace path, the config file path at a
        /// narrow width), the field row's VISUAL index exceeds `field_row` and the
        /// cursor lands inside wrapped intro text instead of on the field being
        /// edited.
        #[test]
        fn the_setup_cursor_tracks_the_row_the_wrap_actually_drew() {
            let mut model = AppModel::new(40, 22);
            let recorded: PathBuf = format!("/recorded/workspace/{}", "segment-".repeat(16)).into();
            let file: PathBuf = format!("/cfg/lomo/{}/config.toml", "d".repeat(24)).into();
            model.input = InputMode::Setup(SetupState::new(
                file,
                ConfigProposal {
                    workspace: PathBuf::from("/zz/aQ"),
                    time_zone: "UTC".to_owned(),
                    previously_initialized: true,
                    recorded_workspace: Some(recorded),
                },
                Req(0),
                None,
            ));

            let mut terminal = draw(&model);
            let position = terminal
                .backend_mut()
                .get_cursor_position()
                .expect("the wizard draws a cursor");
            let drawn = rows(terminal.backend().buffer());
            let (field_row, field_text) = drawn
                .iter()
                .enumerate()
                .find(|(_, row)| row.contains('▎') && row.contains("workspace"))
                .map(|(index, row)| (index, row.as_str()))
                .expect("the wrapped paragraph drew the focused workspace row");
            // The cursor rests one cell past the value's end on the row the wrap
            // actually drew (byte offsets in the row string map to cells because
            // every glyph left of the value is single-width).
            let expected_x = field_text
                .find("/zz/aQ")
                .map(|byte| {
                    field_text
                        .get(..byte)
                        .expect("the needle lands on a char boundary")
                        .chars()
                        .count()
                        + "/zz/aQ".len()
                })
                .expect("the value text is on the drawn field row");
            assert_eq!(
                (position.x as usize, position.y as usize),
                (expected_x, field_row),
                "the cursor must sit on the field row the wrap actually drew, \
                 one cell past the value's end"
            );
        }

        /// The companion column case: the field row itself wraps once. The drawn
        /// continuation starts at column 0 of the inner area, but the cursor math
        /// wraps the value by `inner.width - SETUP_LABEL_COLUMNS` and re-adds the
        /// label offset — the caret lands 13 cells too far right of the glyph it
        /// should follow.
        #[test]
        fn the_setup_cursor_tracks_the_wrapped_value_column() {
            let mut model = AppModel::new(40, 22);
            // 30-cell value → the 13+30=43-cell field line wraps at the ~34-cell
            // inner width; the sentinel tail lands on the continuation row.
            let workspace = TextBuffer::new(format!("{}zz-end", "a".repeat(24)));
            let mut setup = SetupState::new(
                PathBuf::from("/cfg/lomo/config.toml"),
                ConfigProposal {
                    workspace: PathBuf::from("/unused"),
                    time_zone: "UTC".to_owned(),
                    previously_initialized: false,
                    recorded_workspace: None,
                },
                Req(0),
                None,
            );
            setup.workspace = workspace;
            model.input = InputMode::Setup(setup);

            let mut terminal = draw(&model);
            let position = terminal
                .backend_mut()
                .get_cursor_position()
                .expect("the wizard draws a cursor");
            let drawn = rows(terminal.backend().buffer());
            // The field's continuation row is the one the sentinel tail *ends*:
            // the read-only `media_dir` preview echoes the same workspace text
            // further down ("…zz-end/media"), so a bare `find` would match a row
            // the caret can never sit on. And the border glyph `│` is three
            // bytes wide — the cell column comes from a char count, never a byte
            // index (fixture correction — the earlier byte-offset read measured
            // the preview's echo row two cells wide).
            let (row, expected_x) = drawn
                .iter()
                .enumerate()
                .find_map(|(index, text)| {
                    let inside = text.trim_end_matches([' ', '│']);
                    inside
                        .ends_with("zz-end")
                        .then(|| (index, inside.chars().count()))
                })
                .expect("the sentinel tail is drawn");
            let expected_x = u16::try_from(expected_x).expect("column fits");
            assert_eq!(
                (position.x, position.y as usize),
                (expected_x, row),
                "the cursor must rest one cell past the value's drawn tail on \
                 the wrapped continuation row"
            );
        }

        /// Below the six-row threshold `draw_setup` collapses to
        /// `draw_setup_compact` — the focused field keeps the whole interior
        /// width and `draw_field` computes the caret against the same wrapped
        /// geometry it renders, so the cursor stays glued to the value's drawn
        /// tail even when the value wraps inside the strip.
        #[test]
        fn the_compact_setup_field_keeps_the_cursor_on_its_drawn_tail() {
            let mut model = AppModel::new(40, 8);
            // 40 cells > the 34-cell interior: the value wraps and the caret row
            // draws only the tail.
            let workspace = TextBuffer::new(format!("{}zz-end", "a".repeat(34)));
            let mut setup = SetupState::new(
                PathBuf::from("/cfg/lomo/config.toml"),
                ConfigProposal {
                    workspace: PathBuf::from("/unused"),
                    time_zone: "UTC".to_owned(),
                    previously_initialized: false,
                    recorded_workspace: None,
                },
                Req(0),
                None,
            );
            setup.workspace = workspace;
            model.input = InputMode::Setup(setup);

            let mut terminal = draw(&model);
            let position = terminal
                .backend_mut()
                .get_cursor_position()
                .expect("the compact wizard still draws a cursor");
            let drawn = rows(terminal.backend().buffer());
            let (row, text) = drawn
                .iter()
                .enumerate()
                .find(|(_, text)| text.contains("zz-end"))
                .expect("the compact field draws the wrapped tail row");
            let byte = text.find("zz-end").expect("the tail is drawn");
            let expected_x = text
                .get(..byte)
                .expect("the needle lands on a char boundary")
                .chars()
                .count()
                + "zz-end".len();
            assert_eq!(
                (position.x as usize, position.y as usize),
                (expected_x, row),
                "the caret must sit one cell past the drawn tail on the compact field row"
            );
        }

        /// 09-I6-03's repair is two-sided: the CHECK refuses to count a `Gap`
        /// spacer as its card being visibly selected, but the PICK reads the
        /// band's first row raw — spacers included. When a scroll parks the
        /// viewport's top edge on card0's trailing `Gap`, the heal re-selects
        /// the very card it just judged invisible, and the mark sits on a blank
        /// spacer while the next real card renders unmarked below.
        #[test]
        fn the_gap_row_the_check_rejects_cannot_win_the_repair_pick() {
            let mut model = model_with_memos(20, 60, 24).expect("fixture");
            let feed = feed_mut(&mut model).expect("feed");
            let first = feed.memos.first().expect("a card").id.clone();
            assert_eq!(
                feed.selected.as_ref(),
                Some(&first),
                "the feed opens with the first card selected"
            );

            // A two-row scroll from (card0, Time) lands the anchor on card0's
            // trailing Gap; the window's first visible row is that spacer.
            feed_layout::scroll_feed(feed, 60, 4, 2);
            let window = feed_layout::feed_window(feed, 60, 4);
            assert_eq!(
                window.rows.get(window.top).map(|row| row.position),
                Some(CardPosition::Gap),
                "the scroll parked the viewport's top edge on a Gap spacer"
            );

            let selected = feed.selected.as_ref().expect("a selection survives");
            let visible = window
                .rows
                .iter()
                .skip(window.top)
                .take(4)
                .any(|row| &row.id == selected && row.position != CardPosition::Gap);
            assert!(
                visible,
                "the healed selection must be a card with content in view — \
                 {selected:?} only contributes its Gap spacer to the band"
            );
        }

        // =====================================================================
        // C. Image/media — partition, geometry gate, stderr bound
        // =====================================================================

        /// The decode queue is built from the reader's attachment list filtered to
        /// `MediaKind::Image` — an audio attachment never becomes image work, and
        /// each image mints exactly one request in attachment order.
        #[test]
        fn only_image_kind_attachments_enter_the_decode_queue() {
            let mut card = memo("img-memo", "mixed").expect("memo");
            card.attachments = vec![
                attachment("media/pic.png"),
                attachment("media/song.mp3"),
                attachment("media/blob"),
            ];
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            model.view = View::Reader {
                memo: card,
                anchor: TextAnchor::default(),
            };
            model.graphics = ready_graphics(ProtocolType::Halfblocks);

            let first = graphics::hydrate_images(&mut model);
            assert_eq!(model.images.len(), 2, "the mp3 attachment never queues");
            let Some(Effect::LoadImage {
                req: req1,
                request: first_req,
            }) = first
            else {
                panic!("the first image attachment must mint a decode");
            };
            assert_eq!(first_req.path.as_str(), "media/pic.png");
            assert!(
                matches!(model.pending.get(req1), Some(PendingKind::Image)),
                "the minted request is registered under the Image intent"
            );
            assert!(
                matches!(
                    model.images.first().map(|image| &image.state),
                    Some(ImageState::Loading(_))
                ),
                "the issued request marks its image in flight"
            );

            let Some(Effect::LoadImage {
                request: second_req,
                ..
            }) = graphics::hydrate_images(&mut model)
            else {
                panic!("the second image attachment must mint a decode");
            };
            assert_eq!(second_req.path.as_str(), "media/blob");
            assert_eq!(
                graphics::hydrate_images(&mut model),
                None,
                "with both images in flight nothing more is issued"
            );
        }

        /// A reader squeezed so small the fitted image box has a zero axis mints
        /// no decode at all — the box is clamped BEFORE a request exists.
        #[test]
        fn a_zero_geometry_reader_never_mints_image_work() {
            let mut card = memo("img-memo", "tiny").expect("memo");
            card.attachments = vec![attachment("media/pic.png")];
            let mut model = model_with_memos(1, 80, 6).expect("fixture");
            model.view = View::Reader {
                memo: card,
                anchor: TextAnchor::default(),
            };
            model.graphics = ready_graphics(ProtocolType::Halfblocks);
            assert_eq!(
                graphics::hydrate_images(&mut model),
                None,
                "a zero-cell image box issues no work"
            );
            assert!(model.images.is_empty());
        }

        /// F-IMG-4 round 2: `drain_player_stderr` must READ the pipe to EOF — the
        /// bounded 16 KiB is a retention cap on the diagnostic, never an early
        /// close that hands a chatty player SIGPIPE.
        #[test]
        fn player_stderr_is_drained_to_eof_not_just_bounded() {
            struct Counting {
                inner: std::io::Cursor<Vec<u8>>,
                read: Arc<AtomicUsize>,
            }
            impl Read for Counting {
                fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
                    let n = self.inner.read(buf)?;
                    self.read.fetch_add(n, Ordering::Relaxed);
                    Ok(n)
                }
            }

            let total = 128 * 1024;
            let seen = Arc::new(AtomicUsize::new(0));
            let payload = "x".repeat(total);
            let retained = drain_player_stderr(Some(Box::new(Counting {
                inner: std::io::Cursor::new(payload.clone().into_bytes()),
                read: Arc::clone(&seen),
            })));
            assert_eq!(
                seen.load(Ordering::Relaxed),
                total,
                "the pipe is drained to EOF — never closed early at the cap"
            );
            assert_eq!(
                retained.len(),
                16 * 1024,
                "only the diagnostic prefix is retained"
            );
            assert_eq!(
                retained,
                payload.get(..16 * 1024).expect("the prefix exists"),
                "the retained prefix is verbatim"
            );
        }

        // =====================================================================
        // D. Config / first-run — argv round-trip, deleted-file races, marker
        // =====================================================================

        /// The registry is the single projection: `render_config_toml` must emit
        /// exactly what `parse_config_toml` accepts, and the Settings display
        /// (`display_value`) must re-parse through `parse_edit` — for the worst
        /// argv spellings users actually type.
        #[test]
        fn hostile_command_argvs_round_trip_through_the_registry() {
            let fixture = RuntimeFixture::new().expect("fixture");
            let mut failures = Vec::new();
            for argv in [
                vec!["mpv".to_owned(), "--really-quiet".to_owned()],
                vec!["code".to_owned(), "--wait".to_owned()],
                vec!["it's \"quoted\"".to_owned()],
                vec!["back\\slash".to_owned(), "semi;colon --x".to_owned()],
                vec!["uni-你好世界".to_owned(), "tab\tchar".to_owned()],
                vec!["eq=sign".to_owned(), "line\nbreak".to_owned()],
                // The renderer escapes control chars as \uXXXX — the TOML side
                // may refuse to read them back (C0 controls are disallowed).
                vec!["ctrl\u{1}char".to_owned(), "del\u{7f}char".to_owned()],
            ] {
                for field in [SettingsField::Editor, SettingsField::Player] {
                    let mut config = fixture.runtime.config();
                    if matches!(field, SettingsField::Editor) {
                        config.editor = Some(argv.clone());
                    } else {
                        config.player = argv.clone();
                    }
                    let rendered = lomo_tui::config::render_config_toml(&config);
                    match parse_config_toml(
                        &rendered,
                        None,
                        fixture.runtime.paths.home_dir.as_deref(),
                    ) {
                        Ok(parsed) if parsed == config => {}
                        Ok(parsed) => failures.push(format!(
                            "{field:?} {argv:?}: render→parse drifted: {parsed:?}"
                        )),
                        Err(error) => {
                            failures
                                .push(format!("{field:?} {argv:?}: render→parse refused: {error}"));
                        }
                    }
                    // The same argv through the Settings edit surface: the shown
                    // text must re-parse to the shown value (config.rs:123).
                    match field.parse_edit(&field.display_value(&config), None) {
                        Ok(value) if value == field.value(&config) => {}
                        Ok(value) => failures.push(format!(
                            "{field:?} {argv:?}: display→edit drifted: {value:?}"
                        )),
                        Err(error) => {
                            failures
                                .push(format!("{field:?} {argv:?}: display→edit refused: {error}"));
                        }
                    }
                }
            }
            assert!(
                failures.is_empty(),
                "argv round-trip failures: {failures:?}"
            );
        }

        /// config.toml deleted while `$EDITOR` is open on its draft: the install
        /// must refuse, keep the draft, and say so — never silently mint a file
        /// the user never re-confirmed against the new on-disk truth.
        #[test]
        fn a_deleted_config_during_external_edit_refuses_the_install() {
            struct DeletingEditor {
                config: PathBuf,
                patch: String,
            }
            impl CommandRunner for DeletingEditor {
                fn run_foreground(
                    &self,
                    _program: &str,
                    args: &[String],
                ) -> Result<ExitStatus, std::io::Error> {
                    std::fs::write(
                        args.last()
                            .ok_or_else(|| std::io::Error::other("missing draft"))?,
                        &self.patch,
                    )?;
                    std::fs::remove_file(&self.config)?;
                    Ok(success())
                }
                fn spawn_managed(
                    &self,
                    _program: &str,
                    _args: &[String],
                ) -> Result<Box<dyn ManagedChild>, std::io::Error> {
                    Err(std::io::Error::other("never spawns"))
                }
            }

            let fixture = RuntimeFixture::new().expect("fixture");
            let file = config_file(&fixture.runtime.paths);
            let config = fixture.runtime.config();
            std::fs::create_dir_all(file.parent().expect("parent")).expect("cfg dir");
            save_config(&file, &config).expect("seed config");
            let baseline = std::fs::read_to_string(&file).expect("seeded bytes");
            let mut model = model_with_memos(1, 80, 24).expect("fixture");

            let editor = DeletingEditor {
                config: file.clone(),
                patch: format!("{baseline}\n# edited while open\n"),
            };
            let effect = complete_edit(
                &fixture.runtime,
                &mut model,
                &editor,
                &EditTarget::Config,
                None,
                None,
            )
            .expect("a refused install is a decision, not a crash");
            assert!(
                effect.is_none(),
                "a baseline-deleted file must refuse the install — no reload minted"
            );
            let InputMode::Message { lines, .. } = &model.input else {
                panic!("the refusal presents a modal the user must read");
            };
            assert!(!file.exists(), "the refused install recreates nothing");
            let draft_path = lines
                .iter()
                .map(PathBuf::from)
                .find(|path| path.extension().is_some_and(|ext| ext == "toml"))
                .expect("the modal names the retained draft");
            assert!(
                draft_path.exists(),
                "the user's edit survives as the retained draft"
            );
            assert!(
                std::fs::read_to_string(&draft_path)
                    .expect("draft bytes")
                    .contains("# edited while open"),
                "the retained draft holds the user's edit verbatim"
            );
        }

        /// `SaveSetting` rebases on the file truth at commit time: a config that
        /// vanished underneath the session fails loudly (Sync badge + Failed
        /// receipt), never silently rewrites the file from a stale snapshot.
        #[test]
        fn a_save_setting_on_a_vanished_config_fails_loud() {
            let fixture = RuntimeFixture::new().expect("fixture");
            let file = config_file(&fixture.runtime.paths);
            std::fs::create_dir_all(file.parent().expect("parent")).expect("cfg dir");
            save_config(&file, &fixture.runtime.config()).expect("seed config");
            std::fs::remove_file(&file).expect("the file vanished");

            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            let req = model.request(PendingKind::ConfigReload);
            let (sender, _replies) = sync_channel(4);
            let outbox = Outbox::new(sender);
            let error = ops::execute(
                &fixture.runtime,
                &Effect::SaveSetting {
                    req,
                    field: SettingsField::TimeZone,
                    value: FieldValue::TimeZone("Asia/Shanghai".to_owned()),
                },
                &outbox,
                &CancelToken::live(),
            )
            .expect_err("a vanished config must fail the save, not resurrect it");
            assert!(
                !file.exists(),
                "the failed save never rewrites a file it could not read"
            );
            assert_eq!(
                fixture.runtime.config().time_zone,
                "UTC",
                "the live config is untouched by the failed save"
            );

            sink(apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: error.to_string(),
                },
            ));
            assert!(
                model
                    .badges
                    .iter()
                    .any(|badge| badge.class == BadgeClass::Sync),
                "the failed save leaves the persistent Sync mark"
            );
        }

        /// A corrupt `initialized` marker must not masquerade as a fresh install:
        /// unreadable means "cannot tell", which fails the probe rather than
        /// quietly greeting a first run.
        #[test]
        fn an_unreadable_initialized_marker_is_not_a_fresh_install() {
            let dir = tempfile::tempdir().expect("tempdir");
            let paths = RuntimePaths {
                config_dir: dir.path().join("config"),
                state_dir: dir.path().join("state"),
                cache_dir: dir.path().join("cache"),
                runtime_dir: dir.path().join("run"),
                drafts_dir: dir.path().join("state/drafts"),
                exchange_dir: dir.path().join("state/exchange"),
                default_workspace: Some(dir.path().join("notes")),
                home_dir: Some(dir.path().join("home")),
            };
            // The marker exists but is not a readable file.
            std::fs::create_dir_all(paths.state_dir.join(INITIALIZED_MARKER)).expect("marker dir");
            assert!(
                probe_config(&paths, None).is_err(),
                "an unreadable marker is a diagnostic, never a silent first run"
            );
        }

        /// A deleted config on an initialized install recovers the recorded
        /// workspace as the wizard's proposal — the marker's memory outranks the
        /// fresh-install default so recreation lands where the user already was.
        #[test]
        fn a_recorded_workspace_anchors_the_recovery_proposal() {
            let dir = tempfile::tempdir().expect("tempdir");
            let recorded = dir.path().join("where-i-was/notes");
            let paths = RuntimePaths {
                config_dir: dir.path().join("config"),
                state_dir: dir.path().join("state"),
                cache_dir: dir.path().join("cache"),
                runtime_dir: dir.path().join("run"),
                drafts_dir: dir.path().join("state/drafts"),
                exchange_dir: dir.path().join("state/exchange"),
                default_workspace: Some(dir.path().join("fresh-default")),
                home_dir: Some(dir.path().join("home")),
            };
            std::fs::create_dir_all(&paths.state_dir).expect("state dir");
            lomo_tui::xdg::mark_initialized(&paths, &recorded).expect("marker");

            let ConfigProbe::FirstRun { proposal, .. } =
                probe_config(&paths, None).expect("missing config is a first run")
            else {
                panic!("a missing config.toml must propose the wizard");
            };
            assert!(proposal.previously_initialized, "the marker is honored");
            assert_eq!(
                proposal.workspace,
                recorded.canonicalize().unwrap_or_else(|_| recorded.clone()),
                "the proposal anchors to the recorded workspace, not the default"
            );
            assert_eq!(
                proposal.recorded_workspace.as_deref(),
                Some(recorded.as_path())
            );
        }

        /// The capture journal's revision guard: a same-revision write with
        /// different content is a conflict, a lower revision is stale, an
        /// identical rewrite is an idempotent replay — never a last-writer-wins
        /// clobber.
        #[test]
        fn conflicting_and_stale_capture_revisions_are_rejected() {
            let fixture = RuntimeFixture::new().expect("fixture");
            persist_capture(&fixture.runtime, 2, "alpha").expect("baseline write");

            assert!(
                persist_capture(&fixture.runtime, 2, "beta").is_err(),
                "a same-revision different-content write is a conflict"
            );
            assert!(
                persist_capture(&fixture.runtime, 1, "older").is_err(),
                "a stale revision cannot regress the journal"
            );
            persist_capture(&fixture.runtime, 2, "alpha")
                .expect("an identical rewrite is an idempotent replay");
            persist_capture(&fixture.runtime, 3, "beta").expect("the next revision advances");

            let loaded = load_capture(&fixture.runtime).expect("reload");
            assert_eq!(loaded.composer.text.text(), "beta");
            assert_eq!(loaded.composer.revision, 3);
        }
    }

    // adversarial-reaudit round 3: independent re-verification of the P3-F2 fixes
    // recorded in `audit/12-修复-TUI修复面残留.md` against the three residual
    // defects from `audit/11-再复审-TUI修复面.md`.
    //
    // # Behavior Contract
    //
    // Capability: proves (or refutes) that the second-round repairs are actually
    // closed under the producers that share each seam —
    //
    // - A. Verdict machine (11-T-01): `land_graphics_verdict` makes the watchdog's
    //   `PROBE_EXPIRED_DIAGNOSTIC` verdict absorbing and bars `Probing` rewinds —
    //   but `GraphicsDetected` has THREE producers (the probe's `report`, the
    //   watchdog's `deliver`, and `Outbox::drop`'s dying declaration at
    //   executor.rs:117-127). The drop diagnostic is deliberately NOT the expired
    //   constant: it is synthesized by ANY outbox holder's panic — the probe
    //   itself, a lane worker's unwinding closure (executor.rs:489-512), the
    //   watcher (host.rs:218+), the detached player monitor (ops.rs:731). A landed
    //   probe answer is the terminal's word — a synthesized death notice that
    //   arrives after it must degrade, not demote.
    // - B. Setup cursor (11-T-02): the caret derives from the same `wrap_lines`
    //   product the paragraph paints — anchors must hold when a *sibling* field
    //   wraps (the focused row is `field_row + 1` logically but two visual rows
    //   below the wrapped sibling), when a pasted newline splits the field's
    //   logical line, when the value ends exactly on the wrap boundary, and when
    //   the label itself wraps at a sub-13-cell interior.
    // - C. Feed repair (11-T-03): the `find`/`rfind` pick arms both reuse the
    //   no-Gap predicate — verified in both departure directions, for selections
    //   outside the materialized window entirely, for a selection naming a card
    //   that no longer exists, and for a degenerate band holding only a spacer.
    //
    // Owning layer: `messages.rs` (verdict landing), `executor.rs`/`ops.rs`/
    // `host.rs` (outbox holders), `overlays.rs` (cursor geometry),
    // `feed_layout.rs` (selection repair).
    //
    // TDD proof: evidence-first — assertions state the CORRECT contract; a RED
    // failure is genuine residual-defect evidence kept RED deliberately and
    // mapped into `audit/13-再复审-TUI修复面.md`. Production code is not modified
    // by this audit.
    //
    // Exclusions: real stdio probing (`StdioProber` untouched), host-private
    // `deliver`/`tick` internals (the race is provable through the
    // `apply_message` seam they delegate to), the Kotlin side.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures must be constructed successfully before probing; \
                  a failed expectation is itself audit evidence"
    )]
    mod residual_defects {
        use crate::support::{feed_mut, memo, model_with_memos, ready_graphics};
        use lomo_tui::{
            config::ConfigProposal,
            effects::{Effect, RuntimeMessage},
            feed_layout,
            graphics::{self, GraphicsVerdict},
            input::TextBuffer,
            messages::apply_message,
            model::{
                AppModel, CardPosition, InputMode, Req, SetupFocus, SetupState, TextAnchor, View,
            },
        };
        use lomo_workspace::MemoId;
        use ratatui::{
            Terminal,
            backend::{Backend, TestBackend},
        };
        use ratatui_image::picker::ProtocolType;
        use std::path::PathBuf;

        fn id(raw: &str) -> MemoId {
            MemoId::parse(raw).expect("fixture id")
        }

        fn attachment(raw: &str) -> lomo_core::RelativeWorkspacePath {
            lomo_core::RelativeWorkspacePath::parse(raw).expect("fixture path")
        }

        /// Consume a dispatch's effect — `Option<Effect>` is `#[must_use]`; the
        /// probes that never execute effects sink them explicitly.
        fn sink(_: Option<Effect>) {}

        /// The exact payload `Outbox::drop` emits while a thread unwinds a panic
        /// (executor.rs:120-124) — reproduced verbatim so the seam sees the bytes
        /// production actually sends. Its holders include the probe thread, every
        /// lane worker's spawn-closure tail, the watcher and the detached player
        /// monitor — a panic in ANY of them fabricates this verdict.
        fn dying_declaration() -> GraphicsVerdict {
            GraphicsVerdict::Unsupported {
                diagnostic: "graphics probe died before reporting a verdict".to_owned(),
            }
        }

        /// A wizard whose workspace value is driven verbatim — `insert`
        /// normalizes `\r\n` to `\n`, so pasted text can carry real newlines
        /// (input.rs:40); `TextBuffer::new` places the caret at the end.
        fn wizard(workspace: &str, time_zone: &str) -> SetupState {
            let mut setup = SetupState::new(
                PathBuf::from("/cfg/lomo/config.toml"),
                ConfigProposal {
                    workspace: PathBuf::from("/unused"),
                    time_zone: "UTC".to_owned(),
                    previously_initialized: false,
                    recorded_workspace: None,
                },
                Req(0),
                None,
            );
            setup.workspace = TextBuffer::new(workspace.to_owned());
            setup.time_zone = TextBuffer::new(time_zone.to_owned());
            setup
        }

        /// Draw one frame and return `(painted rows, backend cursor cell)`.
        fn drawn_frame(model: &AppModel) -> (Vec<String>, (u16, u16)) {
            let mut terminal = Terminal::new(TestBackend::new(model.width, model.height))
                .expect("fixture backend must build");
            terminal
                .draw(|frame| lomo_tui::ui::draw(frame, model))
                .expect("frame must draw");
            let position = terminal
                .backend_mut()
                .get_cursor_position()
                .expect("the backend always answers — (0,0) means nothing placed");
            let drawn = (0..terminal.backend().buffer().area.bottom())
                .map(|y| {
                    (0..terminal.backend().buffer().area.right())
                        .map(|x| terminal.backend().buffer()[(x, y)].symbol())
                        .collect()
                })
                .collect();
            (drawn, (position.x, position.y))
        }

        /// Cell column one past `needle`'s end inside `row` — cells, not bytes:
        /// the border glyphs are multi-byte, so positions come from char counts.
        fn column_past(row: &str, needle: &str) -> usize {
            let byte = row.find(needle).expect("the needle is drawn");
            row.get(..byte)
                .expect("the needle lands on a char boundary")
                .chars()
                .count()
                + needle.chars().count()
        }

        // =====================================================================
        // A. 11-T-01 — the verdict seam beyond the watchdog's deadline answer
        // =====================================================================

        /// The outbox's dying declaration is NOT the expired verdict — it carries
        /// no finality, so while the gate is still `Probing` it lands fail-closed
        /// (the probe may genuinely be dead). This is the control arm: the
        /// declaration is a *stand-in for a missing first answer*, and a later
        /// real verdict still supersedes it.
        #[test]
        fn the_dying_declaration_still_lifts_a_probing_gate() {
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            assert!(
                matches!(model.graphics, GraphicsVerdict::Probing),
                "the session starts gated while the probe is in flight"
            );

            sink(apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: dying_declaration(),
                },
            ));
            assert!(
                matches!(model.graphics, GraphicsVerdict::Unsupported { .. }),
                "a dying declaration while probing must land fail-closed"
            );

            // The probe was actually alive (some OTHER holder panicked): its real
            // answer supersedes the provisional death notice.
            sink(apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: ready_graphics(ProtocolType::Halfblocks),
                },
            ));
            assert!(
                matches!(model.graphics, GraphicsVerdict::Ready(_)),
                "the surviving probe's real answer must still land"
            );
        }

        /// The expired verdict is absorbing through the whole storm of late
        /// traffic: a synthesized death notice AND the wedged probe's real answer
        /// both degrade after the gate already lifted text-only. The seam is the
        /// diagnostic STRING — a probe-reported `Unsupported` colliding on that
        /// exact text would be absorbed just the same (the sole identifier is
        /// `PROBE_EXPIRED_DIAGNOSTIC`, shared by emitter and recognizer).
        #[test]
        fn the_expired_gate_absorbs_every_later_verdict() {
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            sink(apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: GraphicsVerdict::Unsupported {
                        diagnostic: graphics::PROBE_EXPIRED_DIAGNOSTIC.to_owned(),
                    },
                },
            ));
            for verdict in [
                dying_declaration(),
                ready_graphics(ProtocolType::Halfblocks),
                GraphicsVerdict::Unsupported {
                    diagnostic: "probe failed".to_owned(),
                },
            ] {
                let effect =
                    apply_message(&mut model, RuntimeMessage::GraphicsDetected { verdict });
                assert!(effect.is_none(), "an absorbed verdict issues no work");
                assert!(
                    matches!(&model.graphics, GraphicsVerdict::Unsupported { diagnostic }
                        if diagnostic == graphics::PROBE_EXPIRED_DIAGNOSTIC),
                    "the expired verdict must stay absorbing: {:?}",
                    model.graphics
                );
            }
        }

        /// A landed `Ready` IS the probe's answer — the probe thread reported and
        /// exited, so no honest verdict can follow. Any `GraphicsDetected` that
        /// still arrives is a synthesized death notice from an unrelated panicking
        /// outbox holder (the player monitor, a lane tail, the watcher). Under
        /// audit-11's repair direction — "Ready/Unsupported 之后的任何
        /// `GraphicsDetected` 一律降级" — and under this file's own round-2 claim
        /// that a landed verdict is terminal, such a notice must degrade, not
        /// demote a working capability verdict, tombstone the image registry and
        /// post a false "unavailable" status.
        #[test]
        fn a_landed_ready_verdict_is_terminal_against_a_dying_declaration() {
            let mut card = memo("img-memo", "body with an attachment").expect("memo");
            card.attachments = vec![attachment("media/pic.png")];
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            model.view = View::Reader {
                memo: card,
                anchor: TextAnchor::default(),
            };

            let effect = apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: ready_graphics(ProtocolType::Halfblocks),
                },
            );
            assert!(
                matches!(model.graphics, GraphicsVerdict::Ready(_)),
                "the real probe answer lands"
            );
            let Some(Effect::LoadImage { req, .. }) = effect else {
                panic!("the landed Ready verdict must hydrate the reader's image");
            };

            // A panic in an outbox-holding thread fabricates the death notice.
            sink(apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: dying_declaration(),
                },
            ));

            assert!(
                matches!(model.graphics, GraphicsVerdict::Ready(_)),
                "a landed Ready must be terminal — a spurious death notice may not \
                 demote the capability verdict, got {:?}",
                model.graphics
            );
            assert!(
                !model.images.is_empty(),
                "a spurious death notice must not tombstone the live image registry"
            );
            assert!(
                model.pending.contains(req),
                "the in-flight decode must survive a spurious death notice"
            );
        }

        /// The same hole at the `Unsupported` face: a probe-*reported* failure is
        /// the terminal's true answer ("stdio is not a terminal"). A later
        /// synthesized death notice is not "the same probe's later answer" — the
        /// probe reports exactly once (host.rs:492-494), so no honest verdict can
        /// follow either; the seam must not let fabricated noise rewrite the
        /// recorded truth nor re-fire the unavailable status for a probe that
        /// never died.
        #[test]
        fn a_landed_probe_failure_is_not_rewritten_by_a_dying_declaration() {
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            sink(apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: GraphicsVerdict::Unsupported {
                        diagnostic: "stdio is not a terminal".to_owned(),
                    },
                },
            ));

            sink(apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: dying_declaration(),
                },
            ));

            assert!(
                matches!(&model.graphics, GraphicsVerdict::Unsupported { diagnostic }
                    if diagnostic == "stdio is not a terminal"),
                "a landed probe answer's diagnostic must not be falsified by a \
                 synthesized death notice, got {:?}",
                model.graphics
            );
        }

        /// The symmetric cover for the seam's retained supersession arm — in its
        /// only reachable shape: a synthesized death notice lands while `Probing`
        /// (some OTHER outbox holder panicked), then the surviving probe's real
        /// `Ready` still supersedes it. The original fixture led with a
        /// probe-*reported* `Unsupported` instead, but the probe reports exactly
        /// once (host.rs:492-494) — `U{probe} → Ready` cannot occur in
        /// production, and under the 13-T-01 provenance rule a landed real
        /// answer is terminal, so that sequence now degrades. Re-based onto the
        /// placeholder the same way `image_dest_contract`'s
        /// `the_probing_gate_holds_until_a_verdict_lands` was; kept GREEN so the
        /// finding above reads as "synthesized notices may not supersede a
        /// landed answer", not "the seam must seal entirely".
        #[test]
        fn a_synthesized_death_notice_still_yields_to_the_real_answer() {
            let mut card = memo("img-memo", "body").expect("memo");
            card.attachments = vec![attachment("media/pic.png")];
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            model.view = View::Reader {
                memo: card,
                anchor: TextAnchor::default(),
            };
            sink(apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: dying_declaration(),
                },
            ));
            let effect = apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: ready_graphics(ProtocolType::Kitty),
                },
            );
            assert!(
                matches!(model.graphics, GraphicsVerdict::Ready(_)),
                "a synthesized placeholder still yields to the real answer"
            );
            assert!(
                matches!(effect, Some(Effect::LoadImage { .. })),
                "the superseding Ready still hydrates: {effect:?}"
            );
        }

        /// A `Probing` echo on an already-probing gate is the one inert message
        /// the machine still accepts: no rewind is possible and no work is minted.
        #[test]
        fn a_probing_echo_is_a_noop_not_a_regression() {
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            let effect = apply_message(
                &mut model,
                RuntimeMessage::GraphicsDetected {
                    verdict: GraphicsVerdict::Probing,
                },
            );
            assert!(
                matches!(model.graphics, GraphicsVerdict::Probing),
                "the echo lands harmlessly"
            );
            assert!(effect.is_none(), "an in-flight echo mints no work");
        }

        // =====================================================================
        // B. 11-T-02 — the setup cursor vs the materialized wrap
        // =====================================================================

        /// The anchor must survive a WRAPPED SIBLING field, not just wrapped
        /// intro lines: with a workspace value that wraps once, the time-zone
        /// field's logical row is `field_row + 1` but its visual row is two below
        /// the workspace field's first row — the caret must follow the drawn row.
        #[test]
        fn the_setup_cursor_follows_focus_past_a_wrapped_sibling_field() {
            let mut model = AppModel::new(40, 22);
            // 40-wide model → inner width 34; 13 label columns + 30-cell value =
            // 43 cells → the workspace field emits two visual rows, pushing the
            // focused time-zone row one visual row below its logical index.
            let mut setup = wizard(&format!("{}zz", "a".repeat(28)), "Asia/Shanghai");
            setup.focus = SetupFocus::TimeZone;
            model.input = InputMode::Setup(setup);

            let (drawn, position) = drawn_frame(&model);
            let (row, expected_x) = drawn
                .iter()
                .enumerate()
                .find_map(|(index, text)| {
                    text.contains("Asia/Shanghai")
                        .then(|| (index, column_past(text, "Asia/Shanghai")))
                })
                .expect("the time-zone field drew its value");
            assert_eq!(
                (usize::from(position.0), usize::from(position.1)),
                (expected_x, row),
                "the caret must sit on the time-zone row the wrap drew — a \
                 wrapped sibling field must not shift it off target"
            );
            // The wrap really happened: the workspace field's continuation row —
            // ending in the sentinel "zz" — sits directly above the caret's row.
            assert!(
                drawn
                    .get(row.saturating_sub(1))
                    .is_some_and(|text| text.trim_end_matches([' ', '│']).ends_with('z')),
                "the workspace field's wrapped tail row sits directly above: {drawn:?}"
            );
        }

        /// A value ending exactly on the wrap boundary reports the caret at the
        /// start of the NEXT visual row — `cursor_position`'s `(row + 1, 0)`
        /// convention. Only the LAST logical line materializes an empty caret row
        /// (`text_layout.rs:118`), so mid-document the caret borrows the next
        /// line's first cell — the same cell the next typed grapheme will occupy
        /// once the wrap re-flows content down.
        #[test]
        fn the_setup_cursor_rests_on_the_next_row_at_an_exact_boundary() {
            // 40-wide model → inner width 34; `▎ ` + 11-cell key = 13 label
            // columns, so a 21-cell value ends the field row exactly on the edge.
            let mut model = AppModel::new(40, 22);
            model.input = InputMode::Setup(wizard(&"b".repeat(21), "UTC"));

            let (drawn, position) = drawn_frame(&model);
            let field_row = drawn
                .iter()
                .position(|text| text.contains('▎') && text.contains("workspace"))
                .expect("the focused workspace row is drawn");
            let left_edge = drawn
                .get(field_row + 1)
                .and_then(|text| text.chars().position(|cell| cell == '│'))
                .map(|border| border + 1)
                .expect("the row below has an interior left edge");
            assert_eq!(
                (usize::from(position.0), usize::from(position.1)),
                (left_edge, field_row + 1),
                "an exact-boundary value puts the caret on the next visual row's \
                 first cell — where the wrap will place the next typed grapheme"
            );
        }

        /// A pasted newline is a real buffer state (`insert` maps `\r\n`→`\n`):
        /// the field's logical line emits two visual rows under one anchor, and
        /// the caret must land on the drawn continuation row, past the tail.
        #[test]
        fn a_pasted_newline_in_a_setup_value_keeps_the_caret_on_the_drawn_tail() {
            let mut model = AppModel::new(40, 22);
            model.input = InputMode::Setup(wizard("head\ntailzz", "UTC"));

            let (drawn, position) = drawn_frame(&model);
            // The read-only media_dir preview echoes `<workspace>/media`, which
            // embeds the same newline: its row ends with "tailzz/media", so the
            // caret row is the one whose trimmed content ends exactly "tailzz".
            let (row, expected_x) = drawn
                .iter()
                .enumerate()
                .find_map(|(index, text)| {
                    let inside = text.trim_end_matches([' ', '│']);
                    inside
                        .ends_with("tailzz")
                        .then(|| (index, inside.chars().count()))
                })
                .expect("the field's post-newline row is drawn");
            assert_eq!(
                (usize::from(position.0), usize::from(position.1)),
                (expected_x, row),
                "the caret must rest one cell past the drawn tail on the \
                 newline-split continuation row"
            );
        }

        /// An empty value puts the caret one cell past the 13-column label — the
        /// insertion point of a field that drew `▎ workspace   ` and nothing more.
        #[test]
        fn the_setup_cursor_sits_after_the_label_on_an_empty_value() {
            let mut model = AppModel::new(40, 22);
            model.input = InputMode::Setup(wizard("", "UTC"));

            let (drawn, position) = drawn_frame(&model);
            let (row, text) = drawn
                .iter()
                .enumerate()
                .find(|(_, text)| text.contains('▎') && text.contains("workspace"))
                .expect("the focused workspace row is drawn");
            let expected_x = text
                .chars()
                .position(|cell| cell == '▎')
                .map(|marker| marker + 13)
                .expect("the marker cell is drawn");
            assert_eq!(
                (usize::from(position.0), usize::from(position.1)),
                (expected_x, row),
                "an empty field's caret sits right after the label column"
            );
        }

        /// The multi-wrap case: a value spanning three continuation rows — the
        /// caret must ride the LAST one, not the second.
        #[test]
        fn the_setup_cursor_tracks_a_multi_wrap_value() {
            let mut model = AppModel::new(40, 22);
            // 13 + 86 = 99 cells at inner width 34 → three wrapped rows; the
            // sentinel tail lands on the third.
            let value = format!("{}zz-end", "a".repeat(80));
            model.input = InputMode::Setup(wizard(&value, "UTC"));

            let (drawn, position) = drawn_frame(&model);
            let (row, expected_x) = drawn
                .iter()
                .enumerate()
                .find_map(|(index, text)| {
                    let inside = text.trim_end_matches([' ', '│']);
                    // The media_dir preview echoes the value plus "/media" — a
                    // bare `contains` would match a preview row the caret can
                    // never sit on; the sentinel must be the row's tail (the
                    // fixture correction audit-12 recorded for the round-2 test).
                    inside
                        .ends_with("zz-end")
                        .then(|| (index, inside.chars().count()))
                })
                .expect("the field's last continuation row is drawn");
            assert_eq!(
                (usize::from(position.0), usize::from(position.1)),
                (expected_x, row),
                "the caret must rest one cell past the value's tail on the third \
                 continuation row"
            );
        }

        /// An interior narrower than the 13-column label wraps the label itself:
        /// the caret replay must still agree with the wrap that drew the row —
        /// the marker, key and value split across visual rows under one anchor.
        #[test]
        fn the_setup_cursor_stays_consistent_when_the_label_itself_wraps() {
            // 18-wide model → overlay 14 → inner width 12 < 13 label columns;
            // 15-tall model → inner height 9, so the field's continuation row
            // stays inside the painted interior.
            let mut model = AppModel::new(18, 15);
            model.input = InputMode::Setup(wizard("v", "UTC"));

            let (drawn, position) = drawn_frame(&model);
            let field_row = drawn
                .iter()
                .position(|text| text.contains('▎'))
                .expect("the focused field's first row is drawn");
            let continuation = drawn
                .get(field_row + 1)
                .expect("the label wrapped onto a continuation row");
            let expected_x = continuation.trim_end_matches([' ', '│']).chars().count();
            assert_eq!(
                (usize::from(position.0), usize::from(position.1)),
                (expected_x, field_row + 1),
                "the caret sits one cell past the value on the label's \
                 continuation row: {drawn:?}"
            );
        }

        /// Edge enforcement: when the wrapped intro pushes the focused field's
        /// first visual row below `inner`'s bottom edge, no cursor is placed —
        /// a caret must never float over a row the paragraph clipped away. (The
        /// wizard owns no scroll, so the field is genuinely off-view while it
        /// still edits; that blind-edit gap is recorded in the report, not
        /// asserted away here.)
        #[test]
        fn a_field_row_clipped_below_the_box_places_no_cursor() {
            // 40×12 model → overlay 36×8 → inner 34×6 (still the full form).
            // The recovery preamble wraps the field row past the sixth row.
            let mut model = AppModel::new(40, 12);
            let recorded: PathBuf = format!("/recorded/{}", "segment-".repeat(10)).into();
            model.input = InputMode::Setup(SetupState::new(
                PathBuf::from("/cfg/lomo/config.toml"),
                ConfigProposal {
                    workspace: PathBuf::from("/zz"),
                    time_zone: "UTC".to_owned(),
                    previously_initialized: true,
                    recorded_workspace: Some(recorded),
                },
                Req(0),
                None,
            ));

            let (_drawn, position) = drawn_frame(&model);
            assert_eq!(
                position,
                (0, 0),
                "no caret may be placed while the focused field's row is clipped: \
                 the backend position stays at its untouched origin"
            );
        }

        // =====================================================================
        // C. 11-T-03 — the repair pick in both directions and degenerate bands
        // =====================================================================

        /// Direction symmetry — departure BELOW the viewport: the pick must take
        /// the band's LAST non-Gap row (the nearest visible card in the departure
        /// direction), never a trailing spacer and never the topmost row.
        #[test]
        fn the_repair_pick_walks_down_to_the_last_visible_content_row() {
            let mut model = model_with_memos(20, 60, 24).expect("fixture");
            let feed = feed_mut(&mut model).expect("feed");
            feed.selected = Some(id("memo-10")); // sits below the visible band
            // Park the band's top on card1's Time row: [T1, B1, Gap1, T2].
            feed.anchor = None;
            feed_layout::scroll_feed(feed, 60, 4, 3);
            let window = feed_layout::feed_window(feed, 60, 4);
            assert_eq!(
                window.rows.get(window.top).map(|row| row.position),
                Some(CardPosition::Time),
                "the scroll parked the band's top on card1's first row"
            );
            assert_eq!(
                window.rows.get(window.top + 2).map(|row| row.position),
                Some(CardPosition::Gap),
                "the band's third row is card1's trailing spacer"
            );

            let selected = feed.selected.as_ref().expect("a selection survives");
            assert_eq!(
                *selected,
                id("memo-2"),
                "the departed-below selection lands on the bottommost card with \
                 visible content — the nearest in the departure direction"
            );
            let visible = window
                .rows
                .iter()
                .skip(window.top)
                .take(4)
                .any(|row| &row.id == selected && row.position != CardPosition::Gap);
            assert!(visible, "the healed selection has content rows in the band");
        }

        /// Direction symmetry — departure ABOVE the viewport on a clean top edge:
        /// the pick takes the FIRST non-Gap row (the nearest visible card in the
        /// departure direction), mirroring the gap-at-top case round 2 proved.
        #[test]
        fn the_repair_pick_walks_up_to_the_first_visible_content_row() {
            let mut model = model_with_memos(20, 60, 24).expect("fixture");
            let feed = feed_mut(&mut model).expect("feed");
            feed.selected = Some(id("memo-0")); // its rows sit above the band
            feed.anchor = None;
            feed_layout::scroll_feed(feed, 60, 4, 3); // band [T1, B1, Gap1, T2]

            let selected = feed.selected.as_ref().expect("a selection survives");
            assert_eq!(
                *selected,
                id("memo-1"),
                "the departed-above selection lands on the topmost visible card"
            );
            let window = feed_layout::feed_window(feed, 60, 4);
            let visible = window
                .rows
                .iter()
                .skip(window.top)
                .take(4)
                .any(|row| &row.id == selected && row.position != CardPosition::Gap);
            assert!(visible, "the healed selection has content rows in the band");
        }

        /// A selection naming a card outside the materialized window entirely —
        /// below the lookahead: `position` misses, the card index outranks
        /// `window.cards.end`, and the pick still lands on visible content.
        #[test]
        fn a_selection_beyond_the_materialized_window_repairs_to_the_band_edge() {
            let mut model = model_with_memos(40, 60, 24).expect("fixture");
            let feed = feed_mut(&mut model).expect("feed");
            feed.selected = Some(id("memo-39")); // far below the materialized range
            feed.anchor = None;
            feed_layout::scroll_feed(feed, 60, 4, 3); // band [T1, B1, Gap1, T2]
            let window = feed_layout::feed_window(feed, 60, 4);
            assert!(
                window.rows.iter().all(|row| row.id != id("memo-39")),
                "the selected card is not materialized in this window"
            );

            let selected = feed.selected.as_ref().expect("a selection survives");
            assert_eq!(
                *selected,
                id("memo-2"),
                "the nearest visible card below the band wins"
            );
            let visible = window
                .rows
                .iter()
                .skip(window.top)
                .take(4)
                .any(|row| &row.id == selected && row.position != CardPosition::Gap);
            assert!(visible, "the healed selection has content rows in the band");
        }

        /// A selection naming a memo that no longer exists: `index_of` misses and
        /// `below` resolves false — the pick falls to the band's first content
        /// row rather than leaving a dangling id selected.
        #[test]
        fn a_selection_naming_a_missing_card_repairs_to_visible_content() {
            let mut model = model_with_memos(20, 60, 24).expect("fixture");
            let feed = feed_mut(&mut model).expect("feed");
            feed.selected = Some(id("memo-ghost"));
            feed.anchor = None;
            feed_layout::scroll_feed(feed, 60, 4, 3); // band [T1, B1, Gap1, T2]

            let selected = feed.selected.as_ref().expect("a selection survives");
            assert_eq!(
                *selected,
                id("memo-1"),
                "a dangling selection heals to the first visible content card"
            );
        }

        /// Degenerate band: a one-row viewport parked on a spacer holds no
        /// content row at all — there is nothing the pick could honestly claim,
        /// so the selection is kept rather than moved onto an invisible card or
        /// onto the gap's owner for want of alternatives.
        #[test]
        fn a_one_row_band_parked_on_a_gap_keeps_the_selection() {
            let mut model = model_with_memos(20, 60, 24).expect("fixture");
            let feed = feed_mut(&mut model).expect("feed");
            let first = feed.memos.first().expect("a card").id.clone();
            feed_layout::scroll_feed(feed, 60, 1, 2); // anchor on card0's Gap
            let window = feed_layout::feed_window(feed, 60, 1);
            assert_eq!(
                window.rows.get(window.top).map(|row| row.position),
                Some(CardPosition::Gap),
                "the single visible row is card0's trailing spacer"
            );
            assert_eq!(
                feed.selected.as_ref(),
                Some(&first),
                "with no content row in the band the selection is retained — \
                 the only honest outcome for an all-spacer viewport"
            );
        }

        /// The fixed arm under the same shape: a selection parked exactly ON the
        /// band's trailing-gap edge while its owner is the topmost visible card —
        /// the check arm accepts it (content rows are in the band) and no repair
        /// fires at all.
        #[test]
        fn a_selection_with_content_in_the_band_is_not_repaired() {
            let mut model = model_with_memos(20, 60, 24).expect("fixture");
            let feed = feed_mut(&mut model).expect("feed");
            feed.selected = Some(id("memo-1"));
            feed.anchor = None;
            feed_layout::scroll_feed(feed, 60, 4, 3); // band [T1, B1, Gap1, T2]
            assert_eq!(
                feed.selected.as_ref(),
                Some(&id("memo-1")),
                "a card with visible content keeps its selection"
            );
        }
    }

    // adversarial-reaudit round 4: independent re-verification of the P4-F2 fix
    // recorded in `audit/14-修复-TUI-verdict终态.md` against the 13-T-01 residual
    // defect from `audit/13-再复审-TUI修复面.md`.
    //
    // # Behavior Contract
    //
    // Capability: proves (or refutes) that the provenance-keyed verdict machine
    // is closed under everything its producers can actually emit —
    //
    // - Emitter truth (new this round): `Outbox::drop`'s dying declaration is
    //   driven through a REAL panicking thread and a REAL channel, not a copied
    //   literal — the emitter and the recognizer (`PROBE_DIED_DIAGNOSTIC`,
    //   graphics.rs) are pinned byte-identical end-to-end. The complementary
    //   edges are pinned too: an ordinary drop fabricates nothing, a panic
    //   caught while the outbox is only *borrowed* fabricates nothing, and an
    //   outbox owned inside a caught scope DOES declare death — the predicate
    //   is unwind-scoped, not death-scoped, which is exactly why every
    //   production consumer passes `&Outbox`.
    // - Terminal arm (13-T-01 closed arm): after a landed real answer —
    //   `Ready` or a probe-reported `Unsupported` — every later
    //   `GraphicsDetected` degrades with ZERO observable side effect: the
    //   verdict, the status toast, the image registry and the pending
    //   registry are all bit-identical, and no `Effect` is minted.
    // - Provisional arm (`U{died}`): the placeholder lifts the gate fail-
    //   closed, never seals (repeat declarations absorb), yields only to the
    //   probe's real answer, and cannot be rewound by a `Probing` echo or
    //   sealed by a stray deadline literal.
    // - Trust boundary (documented, not a defect): the seam's only identity
    //   is the diagnostic string — an `Unsupported` whose diagnostic is
    //   neither loop literal is trusted as the probe's real answer. Verified
    //   impossible today for the real probe (pinned ratatui-image 8.0.1 emits
    //   only fixed or prefixed diagnostics) and for every fabricator (the two
    //   constants only); this suite pins the boundary behavior explicitly so
    //   a future third fabricator fails loudly here.
    //
    // Owning layer: `messages.rs` (verdict machine), `executor.rs` (the
    // `Outbox::drop` emitter), `host.rs` (probe/watchdog/spawn producers),
    // `graphics.rs` (verdict identity + retire path).
    //
    // TDD proof: evidence-first — assertions state the CORRECT contract; a RED
    // failure is genuine residual-defect evidence mapped into
    // `audit/15-再复审-TUI-verdict终态.md`. Production code is not modified.
    //
    // Exclusions: real stdio probing (`StdioProber` untouched), host-private
    // `deliver`/`tick` internals (reachable orderings are re-derived through
    // the `apply_message` seam they delegate to), the Kotlin side.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures must be constructed successfully before probing; \
                  a failed expectation is itself audit evidence"
    )]
    mod verdict_terminal {
        use crate::support::{memo, model_with_memos, ready_graphics};
        use lomo_tui::{
            effects::{Effect, RuntimeMessage},
            executor::Outbox,
            graphics::{self, GraphicsVerdict, ImageState},
            messages::apply_message,
            model::{AppModel, PendingKind, TextAnchor, View},
        };
        use ratatui_image::picker::ProtocolType;
        use std::{
            panic::{AssertUnwindSafe, catch_unwind},
            sync::mpsc,
            thread,
            time::Duration,
        };

        fn attachment(raw: &str) -> lomo_core::RelativeWorkspacePath {
            lomo_core::RelativeWorkspacePath::parse(raw).expect("fixture path")
        }

        /// Consume a dispatch's effect — `Option<Effect>` is `#[must_use]`; the
        /// probes that never execute effects sink them explicitly.
        fn sink(_: Option<Effect>) {}

        /// The loop's own deadline-answer verdict — what `tick`'s `PROBE_BUDGET`
        /// watchdog delivers (host.rs:795-799).
        fn expired_answer() -> GraphicsVerdict {
            GraphicsVerdict::Unsupported {
                diagnostic: graphics::PROBE_EXPIRED_DIAGNOSTIC.to_owned(),
            }
        }

        /// The shared dying-declaration literal via the constant, for probes that
        /// stage the placeholder without standing up a real panic.
        fn dying_declaration() -> GraphicsVerdict {
            GraphicsVerdict::Unsupported {
                diagnostic: graphics::PROBE_DIED_DIAGNOSTIC.to_owned(),
            }
        }

        /// A probe-*reported* failure diagnostic — verbatim
        /// `ratatui_image::errors::Errors::NoStdinResponse` (pinned 8.0.1), the
        /// real answer a wedged-but-alive probe delivers on timeout.
        fn probe_reported_failure() -> GraphicsVerdict {
            GraphicsVerdict::Unsupported {
                diagnostic: "No response from stdin".to_owned(),
            }
        }

        /// Produce a REAL dying declaration the way production does: a thread
        /// holding a real `Outbox` panics, the runtime unwinds it, `Drop` sees
        /// `thread::panicking()` and emits. Returns `(channel, produced message)`
        /// — the message is whatever the emitter actually sent, never a copy.
        fn real_death_notice() -> (mpsc::Receiver<RuntimeMessage>, RuntimeMessage) {
            let (tx, rx) = mpsc::sync_channel::<RuntimeMessage>(8);
            let outbox = Outbox::new(tx);
            let handle = thread::Builder::new()
                .name("reaudit4-dead-holder".to_owned())
                .spawn(move || {
                    let _held = outbox;
                    panic!("injected holder death");
                })
                .expect("death-notice thread spawns");
            assert!(
                handle.join().is_err(),
                "the injected panic must kill the holder thread"
            );
            let message = rx
                .recv_timeout(Duration::from_secs(2))
                .expect("the dying declaration must arrive");
            (rx, message)
        }

        /// A reader-open model on a live `Probing` gate with one image
        /// attachment — the shape every verdict-lifecycle probe needs.
        fn reader_model() -> AppModel {
            let mut card = memo("img-memo", "body with an attachment").expect("memo");
            card.attachments = vec![attachment("media/pic.png")];
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            model.view = View::Reader {
                memo: card,
                anchor: TextAnchor::default(),
            };
            model
        }

        fn detected(verdict: GraphicsVerdict) -> RuntimeMessage {
            RuntimeMessage::GraphicsDetected { verdict }
        }

        // =====================================================================
        // A. Emitter truth — the REAL Outbox::drop, not a copied literal
        // =====================================================================

        /// The fixture copies of the dying declaration (`tui_repair_contract`'s
        /// `dying_declaration()`, `image_dest_contract`) are drift sentinels by
        /// duplication — they cannot prove the EMITTER still sends that text.
        /// This probe stands up the real path: a thread holding a real `Outbox`
        /// panics, and the channel's bytes must be exactly
        /// `GraphicsDetected { U{PROBE_DIED_DIAGNOSTIC} }`.
        #[test]
        fn a_real_thread_death_delivers_exactly_the_shared_died_literal() {
            let (_rx, message) = real_death_notice();
            let RuntimeMessage::GraphicsDetected { verdict } = message else {
                panic!("the only thing a dying outbox may send is the verdict, got {message:?}");
            };
            let GraphicsVerdict::Unsupported { diagnostic } = &verdict else {
                panic!("the dying declaration must be Unsupported, got {verdict:?}");
            };
            assert_eq!(
                diagnostic,
                graphics::PROBE_DIED_DIAGNOSTIC,
                "emitter and recognizer must share the literal byte-for-byte"
            );
            // And the recognizer must actually recognize what the emitter sent.
            assert!(
                verdict.is_dying_declaration(),
                "the emitted verdict must classify as the placeholder, not a real answer"
            );
            assert!(
                !verdict.is_probe_answer(),
                "the fabricated declaration must never count as the probe's answer"
            );
        }

        /// The `thread::panicking()` gate is the whole contract: an Outbox
        /// dropped outside a panic — clone teardown, worker exit, shutdown —
        /// fabricates nothing. If this over-fired, every outbox holder's
        /// ordinary exit would spam false death notices.
        #[test]
        fn an_outbox_dropped_outside_a_panic_fabricates_nothing() {
            let (tx, rx) = mpsc::sync_channel::<RuntimeMessage>(8);
            let outbox = Outbox::new(tx);
            // The probe's own shape: report once, then drop the clone normally.
            let reporter = outbox.clone();
            let handle = thread::Builder::new()
                .name("reaudit4-clean-exit".to_owned())
                .spawn(move || {
                    let _delivered = reporter.reply(RuntimeMessage::WatcherReady);
                    // `reporter` drops here with no panic in flight.
                })
                .expect("clean-exit thread spawns");
            handle.join().expect("a clean exit never panics");
            drop(outbox); // the master clone drops normally too
            let mut saw = Vec::new();
            while let Ok(message) = rx.recv_timeout(Duration::from_millis(200)) {
                saw.push(message);
            }
            assert!(
                saw.iter()
                    .all(|message| !matches!(message, RuntimeMessage::GraphicsDetected { .. })),
                "no GraphicsDetected may be fabricated outside a panic: {saw:?}"
            );
            assert!(
                saw.iter()
                    .any(|message| matches!(message, RuntimeMessage::WatcherReady)),
                "the explicit report is the only thing the channel may carry"
            );
        }

        /// The watcher/lane-worker shape: the owned `Outbox` lives OUTSIDE the
        /// `catch_unwind`, which only borrows it. A panic caught inside that
        /// scope unwinds only the borrow — `thread::panicking()` is already
        /// false when the owned outbox drops at closure end, so no death is
        /// declared. This is the invariant that keeps job panics (which report
        /// `WorkerDied`/`WatcherUnavailable` instead) from also fabricating
        /// graphics verdicts.
        #[test]
        fn a_panic_caught_while_the_outbox_is_only_borrowed_fabricates_nothing() {
            let (tx, rx) = mpsc::sync_channel::<RuntimeMessage>(8);
            let outbox = Outbox::new(tx);
            // Keep one clone alive on the test side so a quiet channel reads as
            // `Timeout` rather than `Disconnected` — silence, not sender teardown,
            // is the assertion.
            let _keep_alive = outbox.clone();
            let handle = thread::Builder::new()
                .name("reaudit4-caught-panic".to_owned())
                .spawn(move || {
                    // host.rs:229 / executor.rs:503 shape: the owned outbox is
                    // outside the catch; the closure only borrows it.
                    let ran = catch_unwind(AssertUnwindSafe(|| {
                        let _borrowed = &outbox;
                        panic!("caught job panic");
                    }));
                    assert!(ran.is_err(), "the inner panic is caught");
                    // The owned outbox drops at closure end — not panicking.
                })
                .expect("borrowed-outbox thread spawns");
            handle.join().expect("the outer thread survives");
            match rx.recv_timeout(Duration::from_millis(300)) {
                Err(mpsc::RecvTimeoutError::Timeout) => {}
                other => {
                    panic!("a caught panic with a borrowed outbox must declare no death: {other:?}")
                }
            }
        }

        /// The boundary of the same predicate, pinned honestly: an `Outbox`
        /// OWNED INSIDE a `catch_unwind` scope still declares death on unwind —
        /// the panic is caught, the thread survives, but `Drop` saw
        /// `thread::panicking() == true`. The emitter's provenance is
        /// unwind-scoped, not death-scoped; production stays correct only
        /// because every consumer passes `&Outbox` and no owned clone lives
        /// inside any catch scope (verified by grep: probe host.rs:490, lane
        /// executor.rs:497, watcher host.rs:218, monitor ops.rs:731).
        #[test]
        fn an_outbox_owned_inside_a_caught_unwind_still_declares_death() {
            let (tx, rx) = mpsc::sync_channel::<RuntimeMessage>(8);
            let outbox = Outbox::new(tx);
            let handle = thread::Builder::new()
                .name("reaudit4-owned-catch".to_owned())
                .spawn(move || {
                    let ran = catch_unwind(AssertUnwindSafe(move || {
                        let _owned = outbox;
                        panic!("panic the catch will swallow");
                    }));
                    assert!(ran.is_err(), "the panic is caught — the thread lives");
                })
                .expect("owned-outbox thread spawns");
            handle
                .join()
                .expect("the caught panic leaves the thread alive");
            let message = rx
                .recv_timeout(Duration::from_secs(2))
                .expect("an owned outbox unwound inside a catch still fires");
            let RuntimeMessage::GraphicsDetected { verdict } = message else {
                panic!(
                    "an owned outbox unwound inside a catch still fires the verdict: {message:?}"
                );
            };
            assert!(
                verdict.is_dying_declaration(),
                "the unwind-scoped declaration is the dying literal: {verdict:?}"
            );
        }

        // =====================================================================
        // B. Terminal arm — a landed real answer absorbs with ZERO side effects
        // =====================================================================

        /// The closed 13-T-01 arm end-to-end: `Ready` lands and mints the
        /// reader's decode, then a REAL thread death delivers its declaration —
        /// every observable must be untouched: verdict, status, registry,
        /// pending intent, and no effect minted.
        #[test]
        fn a_landed_ready_absorbs_a_real_death_notice_with_zero_side_effects() {
            let mut model = reader_model();
            let effect = apply_message(
                &mut model,
                detected(ready_graphics(ProtocolType::Halfblocks)),
            );
            let Some(Effect::LoadImage { req, .. }) = effect else {
                panic!("the landed Ready verdict must hydrate the reader's image");
            };
            let status_before = model.status.clone();
            let images_before = model.images.len();

            let (_rx, message) = real_death_notice();
            let effect = apply_message(&mut model, message);
            assert!(
                effect.is_none(),
                "an absorbed notice mints no work: {effect:?}"
            );
            assert!(
                matches!(model.graphics, GraphicsVerdict::Ready(_)),
                "a landed Ready is terminal — fabricated noise may not demote it: {:?}",
                model.graphics
            );
            assert_eq!(
                model.status, status_before,
                "an absorbed verdict must not re-fire or rewrite the status"
            );
            assert_eq!(
                model.images.len(),
                images_before,
                "an absorbed verdict must not tombstone the registry"
            );
            assert!(
                model.pending.contains(req),
                "the in-flight decode intent survives the fabrication"
            );
            assert!(
                model
                    .images
                    .iter()
                    .all(|image| matches!(image.state, ImageState::Loading(_))),
                "in-flight decodes keep their Loading state: {:?}",
                model.images
            );
        }

        /// The same absorb arm on the `Unsupported` face, for the full storm:
        /// a probe-reported failure is the terminal's true answer — after it,
        /// the died literal, the expired literal, a `Probing` echo, an
        /// unrelated `Unsupported` and a second `Ready` all degrade with the
        /// verdict and the recorded diagnostic untouched.
        #[test]
        fn a_landed_probe_answer_absorbs_the_full_late_storm() {
            let mut model = reader_model();
            sink(apply_message(
                &mut model,
                detected(GraphicsVerdict::Unsupported {
                    diagnostic: "stdio is not a terminal".to_owned(),
                }),
            ));
            let status_before = model.status.clone();

            for verdict in [
                dying_declaration(),
                expired_answer(),
                GraphicsVerdict::Probing,
                GraphicsVerdict::Unsupported {
                    diagnostic: "some other fabricated diagnostic".to_owned(),
                },
                ready_graphics(ProtocolType::Kitty),
            ] {
                let effect = apply_message(&mut model, detected(verdict));
                assert!(effect.is_none(), "a terminal verdict absorbs: {effect:?}");
                assert!(
                    matches!(&model.graphics, GraphicsVerdict::Unsupported { diagnostic }
                        if diagnostic == "stdio is not a terminal"),
                    "the landed diagnostic is the record of truth: {:?}",
                    model.graphics
                );
                assert_eq!(
                    model.status, status_before,
                    "absorbed traffic must not re-fire the toast"
                );
            }
        }

        /// The decode receipt for a request that survived a death notice still
        /// lands normally: the fabrication changed nothing about the pending
        /// lifecycle — claimed, applied, and the next image queues itself.
        #[test]
        fn the_decode_receipt_lands_after_surviving_a_death_notice() {
            let mut model = reader_model();
            let effect = apply_message(
                &mut model,
                detected(ready_graphics(ProtocolType::Halfblocks)),
            );
            let Some(Effect::LoadImage { req, request }) = effect else {
                panic!("the landed Ready verdict must mint the decode");
            };

            sink(apply_message(&mut model, detected(dying_declaration())));
            assert!(model.pending.contains(req), "the intent survived");

            let effect = apply_message(
                &mut model,
                RuntimeMessage::Image {
                    req,
                    request,
                    result: Err("decode finished under fire".to_owned()),
                },
            );
            assert_eq!(effect, None, "no further image waits behind this one");
            assert!(
                model.images.iter().any(
                    |image| matches!(&image.state, ImageState::Failed(diagnostic)
                        if diagnostic == "decode finished under fire")
                ),
                "the live receipt landed its real outcome: {:?}",
                model.images
            );
        }

        // =====================================================================
        // C. Provisional arm — `U{died}` yields only to the real answer
        // =====================================================================

        /// The placeholder never seals: repeat declarations absorb without
        /// re-firing the toast, and the surviving probe's real answer still
        /// supersedes — after which a third declaration degrades on the
        /// now-terminal gate.
        #[test]
        fn the_placeholder_never_seals_and_always_yields_to_the_real_answer() {
            let mut model = reader_model();

            sink(apply_message(&mut model, detected(dying_declaration())));
            assert!(
                model.graphics.is_dying_declaration(),
                "the first declaration lifts the gate fail-closed"
            );
            let status_after_first = model.status.clone();

            // A second holder's panic carries no new truth — absorbed, no
            // toast re-fire, and the gate stays provisional rather than
            // accumulating into a terminal state.
            let effect = apply_message(&mut model, detected(dying_declaration()));
            assert!(effect.is_none(), "a repeat declaration mints nothing");
            assert!(
                model.graphics.is_dying_declaration(),
                "the placeholder is unchanged, not sealed"
            );
            assert_eq!(model.status, status_after_first, "no toast re-fire");

            // The wedged-but-alive probe's real answer still supersedes.
            let effect = apply_message(&mut model, detected(probe_reported_failure()));
            assert!(effect.is_none(), "a real failure mints no image work");
            assert!(
                matches!(&model.graphics, GraphicsVerdict::Unsupported { diagnostic }
                    if diagnostic == "No response from stdin"),
                "the probe's real answer replaced the placeholder: {:?}",
                model.graphics
            );

            // …and the now-terminal gate absorbs the next fabricated notice.
            let effect = apply_message(&mut model, detected(dying_declaration()));
            assert!(effect.is_none());
            assert!(
                matches!(&model.graphics, GraphicsVerdict::Unsupported { diagnostic }
                    if diagnostic == "No response from stdin"),
                "the real answer is terminal after supersession"
            );
        }

        /// A `Probing` echo on the placeholder must absorb, not rewind: a
        /// rewind would re-arm the `PROBE_BUDGET` watchdog (host.rs:788 checks
        /// `matches!(graphics, Probing)`) into a bogus deadline verdict on the
        /// next tick.
        #[test]
        fn a_probing_echo_cannot_rewind_the_placeholder() {
            let mut model = reader_model();
            sink(apply_message(&mut model, detected(dying_declaration())));
            assert!(model.graphics.is_dying_declaration());

            let effect = apply_message(&mut model, detected(GraphicsVerdict::Probing));
            assert!(effect.is_none(), "the echo is inert");
            assert!(
                model.graphics.is_dying_declaration(),
                "the placeholder must not rewind to in-flight: {:?}",
                model.graphics
            );
        }

        /// The documented convention (production-unreachable): on a
        /// `U{died}` gate the watchdog's `expired` literal absorbs too — the
        /// watchdog can never actually fire there (host.rs:788 arms only on
        /// `Probing`, and the drain runs before `tick`), but if it ever did,
        /// the placeholder stays provisional rather than sealing.
        #[test]
        fn a_stray_deadline_literal_cannot_seal_the_placeholder() {
            let mut model = reader_model();
            sink(apply_message(&mut model, detected(dying_declaration())));

            let effect = apply_message(&mut model, detected(expired_answer()));
            assert!(
                effect.is_none(),
                "the deadline literal absorbs on a placeholder gate"
            );
            assert!(
                model.graphics.is_dying_declaration(),
                "the provisional placeholder survives: {:?}",
                model.graphics
            );

            // Recovery stays open either way.
            let effect = apply_message(&mut model, detected(ready_graphics(ProtocolType::Kitty)));
            assert!(
                matches!(effect, Some(Effect::LoadImage { .. })),
                "the real answer still lands and hydrates: {effect:?}"
            );
        }

        /// The spawn-failure verdict (host.rs:501) bypasses the seam by
        /// necessity — no probe thread exists to answer — and the machine must
        /// treat it as terminal like any real answer: a later fabrication
        /// degrades instead of rewriting the recorded diagnostic.
        #[test]
        fn the_spawn_failure_verdict_is_terminal_like_a_real_answer() {
            let mut model = reader_model();
            // host.rs:501's shape: a direct write, not a message.
            model.graphics = GraphicsVerdict::Unsupported {
                diagnostic: "graphics probe could not start: spawn denied".to_owned(),
            };

            let effect = apply_message(&mut model, detected(dying_declaration()));
            assert!(effect.is_none());
            assert!(
                matches!(&model.graphics, GraphicsVerdict::Unsupported { diagnostic }
                    if diagnostic == "graphics probe could not start: spawn denied"),
                "the only verdict a dead spawn can hold must not be falsified: {:?}",
                model.graphics
            );
        }

        // =====================================================================
        // D. Trust boundary — documented, pinned, not a defect today
        // =====================================================================

        /// The seam's identity is the diagnostic string: an `Unsupported`
        /// carrying neither loop literal is trusted as the probe's real answer.
        /// Today that is sound — the only fabricators emit exactly
        /// `PROBE_DIED_DIAGNOSTIC` (executor.rs:130) and
        /// `PROBE_EXPIRED_DIAGNOSTIC` (host.rs:797), and pinned
        /// ratatui-image 8.0.1's `Errors` display is a fixed set or prefixed
        /// strings that cannot equal either literal. This probe pins the
        /// boundary itself: if a third fabricator with a distinct literal is
        /// ever added, this is the probe whose semantics change.
        #[test]
        fn an_unrecognized_literal_is_trusted_as_the_probes_real_answer() {
            let mut model = reader_model();
            sink(apply_message(&mut model, detected(dying_declaration())));

            let effect = apply_message(
                &mut model,
                detected(GraphicsVerdict::Unsupported {
                    diagnostic: "a diagnostic no producer today can emit".to_owned(),
                }),
            );
            assert!(
                effect.is_none(),
                "an unrecognized failure mints no image work"
            );
            assert!(
                matches!(&model.graphics, GraphicsVerdict::Unsupported { diagnostic }
                    if diagnostic == "a diagnostic no producer today can emit"),
                "an unrecognized literal supersedes the placeholder — the seam \
                 cannot tell it from the probe's word: {:?}",
                model.graphics
            );
            // …and as a "real answer" it is terminal.
            let effect = apply_message(&mut model, detected(dying_declaration()));
            assert!(effect.is_none(), "the trusted verdict absorbs fabrications");
        }

        /// `RuntimeReady`'s `install_runtime` merge keeps the shell's landed
        /// verdict (model.rs:1425-1433): the prepared model arrives `Probing`
        /// by construction and must not rewind a terminal answer — the same
        /// boundary the new machine's terminality relies on.
        #[test]
        fn install_runtime_keeps_the_terminal_verdict_through_the_merge() {
            let mut model = reader_model();
            sink(apply_message(
                &mut model,
                detected(ready_graphics(ProtocolType::Halfblocks)),
            ));
            assert!(matches!(model.graphics, GraphicsVerdict::Ready(_)));

            let prepared = model_with_memos(2, 120, 40).expect("prepared model");
            assert!(
                matches!(prepared.graphics, GraphicsVerdict::Probing),
                "the prepared bootstrap model never observed the terminal"
            );
            let boot_req = model.request(PendingKind::Bootstrap);
            sink(apply_message(
                &mut model,
                RuntimeMessage::RuntimeReady {
                    req: boot_req,
                    model: Box::new(prepared),
                },
            ));
            assert!(
                matches!(model.graphics, GraphicsVerdict::Ready(_)),
                "the merge must not rewind the landed verdict"
            );

            let effect = apply_message(&mut model, detected(dying_declaration()));
            assert!(effect.is_none(), "the merged shell stays terminal");
            assert!(matches!(model.graphics, GraphicsVerdict::Ready(_)));
        }

        /// Documented residual, pinned as observable truth: the "unavailable"
        /// toast is point-in-time feedback fired when the placeholder landed —
        /// a superseding `Ready` restores capability but does NOT rewrite the
        /// status line (feedback is last-write-wins until acknowledged or
        /// replaced, model.rs:1204/1350). Capability truth lives in
        /// `model.graphics`, not the toast; the staleness is cosmetic and
        /// bounded by the next feedback write.
        #[test]
        fn the_unavailable_toast_is_point_in_time_feedback_not_a_capability_mirror() {
            let mut model = reader_model();
            sink(apply_message(&mut model, detected(dying_declaration())));
            assert!(
                model.status.is_some(),
                "the placeholder landing surfaces its toast"
            );

            let effect = apply_message(&mut model, detected(ready_graphics(ProtocolType::Kitty)));
            assert!(matches!(effect, Some(Effect::LoadImage { .. })));
            assert!(
                matches!(model.graphics, GraphicsVerdict::Ready(_)),
                "the real answer restored capability"
            );
            assert!(
                model.status.is_some(),
                "the fired toast persists until the next feedback write — \
                 it is not a live mirror of model.graphics"
            );
        }
    }
}
