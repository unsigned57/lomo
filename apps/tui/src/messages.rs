//! Runtime replies change only what their request intended.
//!
//! A receipt first claims its `Req` out of `model.pending`. On a hit the stored
//! `PendingKind` decides where it may land; on a miss the receipt takes the
//! degradation path — a superseded reply can never land on whatever state
//! happens to be current, and a failure always leaves a status trace.
use crate::effects::{BodyReply, Effect, RuntimeMessage};
use crate::model::{
    AppModel, BadgeClass, BodyState, CardPosition, Composer, FeedState, InputMode, LoadStatus,
    MemoAnchor, MemoCard, Notice, ParkedReply, PendingKind, Req, SaveState, Severity, TextAnchor,
    View,
};

#[must_use]
pub fn apply_message(model: &mut AppModel, message: RuntimeMessage) -> Option<Effect> {
    // The parked queue only fills while another input owns the focus, so a
    // receipt applied while the model already sits at `Browse` can never be
    // its delivery point — `drain_parked` fires on the *return* to `Browse`,
    // not on the state. This also keeps a `RuntimeReady` install from
    // popping a queued receipt over the fresh shell in the same step.
    let was_busy = !matches!(model.input, InputMode::Browse);
    let effect = dispatch_message(model, message);
    if was_busy {
        model.drain_parked();
    }
    effect
}

/// The watcher-outage badge title — shared by the watcher thread's
/// `WatcherUnavailable` receipts and the host's spawn-failure path so both
/// outages wear the same mark (I9).
pub(crate) fn watcher_outage_title() -> String {
    crate::i18n::UiStrings::detect()
        .text(
            "File watching stopped — auto-refreshing",
            "文件监视已停止——自动刷新中",
        )
        .to_owned()
}

fn dispatch_message(model: &mut AppModel, message: RuntimeMessage) -> Option<Effect> {
    match message {
        // The observation channel: producer-initiated signals that carry no
        // request identity and are never pending replies.
        RuntimeMessage::FsChanged { observed } => {
            let req = model.request(PendingKind::Maintenance);
            Some(Effect::Reconcile { req, observed })
        }
        RuntimeMessage::ConfigChanged => {
            // A config.toml change observed on disk: re-read strictly, apply
            // hot fields, name restart-required ones. An invalid file answers
            // with a `Failed` receipt — the live config stays untouched.
            let req = model.request(PendingKind::ConfigReload);
            Some(Effect::ReloadConfig { req })
        }
        RuntimeMessage::ConfigWatchUnavailable { diagnostic } => {
            // Degraded but bounded: manual reload still works, so this is a
            // toast, not a badge — it never impersonates a workspace outage.
            model.present(Notice::toast(
                Severity::Warn,
                crate::i18n::UiStrings::detect()
                    .text("config watch unavailable", "配置监视不可用")
                    .to_owned(),
                vec![diagnostic],
            ));
            None
        }
        RuntimeMessage::BootPhase { phase } => {
            let strings = crate::i18n::UiStrings::detect();
            model.set_status(match phase {
                crate::effects::BootPhase::Workspace => {
                    strings.text("Opening workspace…", "正在打开工作区…")
                }
                crate::effects::BootPhase::Model => {
                    strings.text("Preparing memos…", "正在准备记录…")
                }
            });
            None
        }
        RuntimeMessage::WatcherReady => {
            model.watcher_active = true;
            // Observation is healthy again — the outage mark retires itself.
            model.clear_badges(BadgeClass::Watch);
            None
        }
        RuntimeMessage::WatcherUnavailable { diagnostic } => land_watcher_down(model, diagnostic),
        RuntimeMessage::PlayerFinished {
            success,
            diagnostic,
        } => land_player_exit(model, success, diagnostic),
        RuntimeMessage::GraphicsDetected { verdict } => land_graphics_verdict(model, verdict),
        // A supervised lane died unexpectedly — bootstrap severity: fail the
        // view closed and raise the terminal badge (F-06/I9).
        RuntimeMessage::WorkerDied { diagnostic, .. } => land_worker_died(model, diagnostic),
        // Every remaining variant is a receipt: it claims the request it
        // answers before it may land. Adding a `RuntimeMessage` variant forces
        // an explicit classification here — observation above, or receipt below.
        receipt @ (RuntimeMessage::QuitReady { .. }
        | RuntimeMessage::Image { .. }
        | RuntimeMessage::View { .. }
        | RuntimeMessage::Page { .. }
        | RuntimeMessage::Bodies { .. }
        | RuntimeMessage::ReadMemo { .. }
        | RuntimeMessage::MemoGone { .. }
        | RuntimeMessage::DraftStored { .. }
        | RuntimeMessage::Saved { .. }
        | RuntimeMessage::Tags { .. }
        | RuntimeMessage::Message { .. }
        | RuntimeMessage::History { .. }
        | RuntimeMessage::Date { .. }
        | RuntimeMessage::RuntimeReady { .. }
        | RuntimeMessage::Reconciled { .. }
        | RuntimeMessage::MediaSweepDone { .. }
        | RuntimeMessage::ConfigApplied { .. }
        | RuntimeMessage::Mutated { .. }
        | RuntimeMessage::Changed { .. }
        | RuntimeMessage::Failed { .. }) => {
            let req = receipt.req()?;
            let Some(intent) = model.pending.claim(req) else {
                return degrade(model, &receipt);
            };
            land(model, req, &intent, receipt)
        }
    }
}

/// The unified degradation path: a receipt whose request is no longer live —
/// superseded, cancelled, or foreign — never lands on current state.
/// Failures and user notices still surface in the status line; superseded
/// data replies settle quietly because the live request owns the UX.
fn degrade(model: &mut AppModel, receipt: &RuntimeMessage) -> Option<Effect> {
    // The loud receipts are the exceptions: failures and user notices still
    // surface in the status line.
    if let RuntimeMessage::Failed { diagnostic, .. } = receipt {
        model.set_status(diagnostic);
    } else if let RuntimeMessage::Message { title, lines, .. } = receipt {
        model.set_status(&format!("{title}: {}", lines.join(" · ")));
    }
    // behavior-contract: silent-result-ok: every other superseded data reply
    // carries no failure evidence; its replacement request owns the view it
    // was meant for.
    None
}

/// A claimed reply lands on exactly the target its intent names. When that
/// target has already gone away the reply settles quietly — the intent's
/// landing check, not the reply's arrival order, decides.
fn land(
    model: &mut AppModel,
    req: Req,
    intent: &PendingKind,
    message: RuntimeMessage,
) -> Option<Effect> {
    if let RuntimeMessage::Failed { diagnostic, .. } = &message {
        return land_failure(model, req, intent, diagnostic);
    }
    // A same-class success retires the badge its earlier failure raised —
    // the class, not the wording, decides what counts as recovered (I9).
    if let Some(class) = clears_badge(intent, &message) {
        model.clear_badges(class);
    }
    match intent {
        // View-shaped receipts install or refresh what is on screen.
        PendingKind::FeedPage
        | PendingKind::Navigate
        | PendingKind::RefreshView { .. }
        | PendingKind::OpenMemo
        | PendingKind::RefreshReader { .. }
        | PendingKind::Bodies => land_view(model, req, intent, message),
        // State-shaped receipts mutate model state other than the view stack.
        PendingKind::DraftPersist { .. }
        | PendingKind::DraftCommit { .. }
        | PendingKind::Tags
        | PendingKind::History { .. }
        | PendingKind::Date { .. }
        | PendingKind::Maintenance
        | PendingKind::Mutation
        | PendingKind::Attachment
        | PendingKind::ConfigReload
        | PendingKind::Quit
        | PendingKind::Bootstrap
        | PendingKind::Image => land_state(model, req, intent, message),
    }
}

/// Land the view-shaped receipts: page merges, placeholder installs and
/// in-place refreshes.
fn land_view(
    model: &mut AppModel,
    req: Req,
    intent: &PendingKind,
    message: RuntimeMessage,
) -> Option<Effect> {
    match (intent, message) {
        (
            PendingKind::FeedPage,
            RuntimeMessage::Page {
                append,
                cards,
                next,
                order,
                total,
                ..
            },
        ) => {
            apply_page(model, req, append, cards, next, &order, total);
            None
        }
        (PendingKind::Navigate, RuntimeMessage::View { view, .. }) => {
            land_placeholder(model, req, *view);
            None
        }
        (PendingKind::RefreshView { screen }, RuntimeMessage::View { view, .. }) => {
            if model.view.screen() == *screen {
                model.view = *view;
            }
            None
        }
        (PendingKind::OpenMemo, RuntimeMessage::ReadMemo { memo, .. }) => {
            land_placeholder(
                model,
                req,
                View::Reader {
                    memo: *memo,
                    anchor: TextAnchor::default(),
                },
            );
            None
        }
        (PendingKind::RefreshReader { id: target }, RuntimeMessage::ReadMemo { memo, .. }) => {
            refresh_reader(model, *memo, target);
            None
        }
        (PendingKind::Bodies, RuntimeMessage::Bodies { bodies, .. }) => {
            hydrate_all(model, &bodies);
            None
        }
        (PendingKind::OpenMemo, RuntimeMessage::MemoGone { id, .. }) => {
            land_memo_gone(model, req, &id);
            None
        }
        (PendingKind::RefreshReader { id: target }, RuntimeMessage::MemoGone { id, .. }) => {
            // The refresh's own target vanished — a miss for a memo the
            // request never named is a protocol violation, not evidence
            // about the open reader (09-F-04).
            if *target == id {
                land_memo_gone(model, req, &id);
            }
            None
        }
        (_, receipt) => {
            // A claimed intent whose receipt is the wrong shape is a protocol
            // violation — degrade instead of guessing where it belongs.
            degrade(model, &receipt)
        }
    }
}

/// The probe verdict is the terminal's answer, not an env guess (D-03):
/// install it, surface a probe failure once on the status line, and kick
/// hydration so a live reader starts decoding immediately.
///
/// The verdict machine lifts the stdin gate exactly once (F-IMG-3), and
/// landing is decided by the verdict's *provenance* — the only identity the
/// seam can see is which diagnostics the loop itself can synthesize
/// (11-T-01, 13-T-01):
///
/// - `Probing` is the only in-flight state: the first non-`Probing` verdict
///   lands on it, and a `Probing` echo is a no-op — a rewind would re-arm
///   the watchdog into a bogus `Unsupported` on the next tick.
/// - The watchdog's `PROBE_EXPIRED_DIAGNOSTIC` answer is absorbing: the
///   gate already lifted for text-only operation, so a wedged probe's late
///   report degrades instead of reopening the image pipeline.
/// - `Outbox::drop`'s `PROBE_DIED_DIAGNOSTIC` stands in for a first answer
///   that may never arrive — ANY holder's panic fabricates it — so it is
///   provisional: it lands fail-closed on a `Probing` gate but yields to
///   the surviving probe's real answer.
/// - The probe's real answer is terminal: the probe reports exactly once,
///   so every `GraphicsDetected` that still arrives after it landed is a
///   fabrication — it degrades to a trace instead of demoting the
///   capability verdict, tombstoning the image registry, falsifying the
///   recorded diagnostic or re-firing the unavailable status.
fn land_graphics_verdict(
    model: &mut AppModel,
    verdict: crate::graphics::GraphicsVerdict,
) -> Option<Effect> {
    use crate::graphics::GraphicsVerdict;
    let stale = match &model.graphics {
        // The gate is still open — every first answer lands; a `Probing`
        // echo is the one inert message.
        GraphicsVerdict::Probing => matches!(verdict, GraphicsVerdict::Probing),
        // The dying declaration stands in for a missing first answer —
        // only the probe's real answer supersedes it; a repeat declaration
        // or the deadline answer carries no truth it does not already hold.
        GraphicsVerdict::Unsupported { .. } if model.graphics.is_dying_declaration() => {
            !verdict.is_probe_answer()
        }
        // A landed real answer is as terminal as the loop's own deadline
        // answer: the probe reports exactly once, so whatever still arrives
        // is fabricated by an outbox holder's panic.
        GraphicsVerdict::Ready(_) | GraphicsVerdict::Unsupported { .. } => true,
    };
    if stale {
        tracing::debug!(
            landed = ?model.graphics,
            ignored = ?verdict,
            "graphics verdict arrived after the gate's terminal answer"
        );
        return None;
    }
    let unsupported = match &verdict {
        GraphicsVerdict::Unsupported { diagnostic } => Some(diagnostic.clone()),
        GraphicsVerdict::Probing | GraphicsVerdict::Ready(_) => None,
    };
    model.graphics = verdict;
    if let Some(diagnostic) = unsupported {
        model.set_status(crate::i18n::UiStrings::detect().text(
            "Terminal graphics unavailable — image placeholders only",
            "终端不支持图形——仅显示图片占位符",
        ));
        tracing::debug!(%diagnostic, "graphics probe found no image protocol");
    }
    crate::graphics::hydrate_images(model)
}

/// The watcher observation died: persistent badge, not a toast — it stays
/// until acknowledged or a `WatcherReady` retires it (I9).
fn land_watcher_down(model: &mut AppModel, diagnostic: String) -> Option<Effect> {
    model.watcher_active = false;
    model.present(Notice::badge(
        Severity::Warn,
        BadgeClass::Watch,
        watcher_outage_title(),
        vec![diagnostic],
    ));
    None
}

/// The external player's exit verdict: a clean exit retires the badge the
/// open path may have raised; an error exit raises it (I9).
fn land_player_exit(
    model: &mut AppModel,
    success: bool,
    diagnostic: Option<String>,
) -> Option<Effect> {
    if success {
        model.clear_badges(BadgeClass::Player);
    } else {
        model.present(Notice::badge(
            Severity::Warn,
            BadgeClass::Player,
            crate::i18n::UiStrings::detect()
                .text("The player exited with an error", "播放器异常退出")
                .to_owned(),
            vec![diagnostic.unwrap_or_else(|| "player failed".to_owned())],
        ));
    }
    None
}

/// A lane's queued work was already answered with `Failed` receipts before
/// this observation was reported (F-06); the `Worker` badge can only be
/// acknowledged, because no same-class success exists to retire it (I9).
fn land_worker_died(model: &mut AppModel, diagnostic: String) -> Option<Effect> {
    model.raise_badge(Severity::Error, BadgeClass::Worker, diagnostic.clone());
    model.set_status(&diagnostic);
    model.view = View::Failed {
        screen: model.view.screen(),
        diagnostic,
    };
    None
}

/// Which badge class a successful receipt retires — the intent names the
/// family whose earlier failure it disproves (I9).
const fn clears_badge(intent: &PendingKind, receipt: &RuntimeMessage) -> Option<BadgeClass> {
    match (intent, receipt) {
        (
            PendingKind::Mutation,
            RuntimeMessage::Mutated { .. } | RuntimeMessage::Changed { .. },
        ) => Some(BadgeClass::Action),
        (PendingKind::Attachment, RuntimeMessage::Message { .. }) => Some(BadgeClass::Player),
        (
            PendingKind::Maintenance,
            RuntimeMessage::Reconciled { .. }
            | RuntimeMessage::MediaSweepDone { .. }
            | RuntimeMessage::Changed { .. },
        )
        | (PendingKind::ConfigReload, RuntimeMessage::ConfigApplied { .. }) => {
            Some(BadgeClass::Sync)
        }
        (PendingKind::DraftPersist { .. }, RuntimeMessage::DraftStored { .. })
        | (PendingKind::DraftCommit { .. }, RuntimeMessage::Saved { .. }) => {
            Some(BadgeClass::Draft)
        }
        _ => None,
    }
}

/// The memo a request named is gone: pop an `OpenMemo` placeholder back to
/// its source context (or keep the reader's last-known body on a refresh)
/// and toast the miss — a lookup miss is context, never a `Failed` screen
/// or a `config:` diagnostic (I9).
fn land_memo_gone(model: &mut AppModel, req: Req, id: &lomo_workspace::MemoId) {
    if matches!(&model.view, View::Loading { req: live, .. } if *live == req)
        && let Some(view) = model.history.pop()
    {
        model.view = view;
    }
    let s = crate::i18n::UiStrings::detect();
    model.present(Notice::toast(
        Severity::Warn,
        s.text("That memo no longer exists", "该记录已不存在")
            .to_owned(),
        vec![id.as_str().to_owned()],
    ));
}

/// Land the state-shaped receipts: draft lifecycle, tags, history, dates and
/// the maintenance/mutation/attachment/bootstrap handshakes.
fn land_state(
    model: &mut AppModel,
    req: Req,
    intent: &PendingKind,
    message: RuntimeMessage,
) -> Option<Effect> {
    match (intent, message) {
        (PendingKind::DraftPersist { .. }, RuntimeMessage::DraftStored { revision, .. }) => {
            // Persisted revisions are monotone: a stored revision behind the
            // current draft is already superseded by its own write.
            if revision <= model.draft.revision {
                model.draft.persisted_revision = model.draft.persisted_revision.max(revision);
            }
            None
        }
        (
            PendingKind::DraftCommit { revision: expected },
            RuntimeMessage::Saved { revision, id, .. },
        ) => land_commit(model, *expected, revision, id),
        (PendingKind::Tags, RuntimeMessage::Tags { tags, .. }) => {
            model.set_tags(tags);
            None
        }
        (PendingKind::History { id: expected }, RuntimeMessage::History { id, revisions, .. }) => {
            if *expected == id {
                land_history(model, id, revisions);
            }
            None
        }
        (
            PendingKind::Date { dialog },
            RuntimeMessage::Date {
                from, until, label, ..
            },
        ) => apply_date(model, req, *dialog, from, until, label),
        (PendingKind::Maintenance, RuntimeMessage::Reconciled { changed, .. }) => {
            if changed {
                refresh_current(model)
            } else {
                None
            }
        }
        (
            PendingKind::Maintenance,
            RuntimeMessage::MediaSweepDone {
                moved,
                purged,
                failures,
                ..
            },
        ) => {
            land_media_sweep(model, moved, purged, failures);
            None
        }
        (PendingKind::Mutation, RuntimeMessage::Mutated { outcome, .. }) => {
            // The receipt names the store outcome — "Pinned", "Moved to
            // trash" — never a generic "Saved" (I2).
            model.set_status(&outcome.status());
            refresh_current(model)
        }
        (
            PendingKind::Mutation | PendingKind::Maintenance,
            RuntimeMessage::Changed { status, .. },
        ) => {
            model.set_status(&status);
            refresh_current(model)
        }
        (PendingKind::Mutation, RuntimeMessage::Message { title, lines, .. }) => {
            // A mutation answered with a user notice carries content to read
            // — the retained draft's path and the conflict diagnostic — so it
            // is a modal; under a busy input `present` parks it as the unread
            // `Notice` badge instead of seizing focus (I9).
            model.present(Notice::modal(Severity::Warn, title, lines));
            None
        }
        (PendingKind::Attachment, RuntimeMessage::Message { title, lines, .. }) => {
            // "Attachment opened" is a toast — a modal notice would seize
            // input for a purely informational receipt (A-08/I9).
            model.present(Notice::toast(Severity::Info, title, lines));
            None
        }
        (
            PendingKind::ConfigReload,
            RuntimeMessage::ConfigApplied {
                config,
                applied,
                restart_pending,
                ..
            },
        ) => {
            land_config_applied(model, &config, &applied, &restart_pending);
            None
        }
        (PendingKind::Quit, RuntimeMessage::QuitReady { .. }) => None,
        (
            PendingKind::Bootstrap,
            RuntimeMessage::RuntimeReady {
                model: prepared, ..
            },
        ) => {
            // Merge, never replace: the shell owns request identity, live
            // pending intent, parked receipts, feedback and the landed
            // probe verdict — the reply installs only what the bootstrap
            // prepared (09-F-01).
            model.install_runtime(*prepared);
            None
        }
        (
            PendingKind::Image,
            RuntimeMessage::Image {
                request, result, ..
            },
        ) => {
            crate::graphics::apply_image(model, &request, result);
            // More images may have queued behind the one that just landed.
            crate::graphics::hydrate_images(model)
        }
        (_, receipt) => {
            // A claimed intent whose receipt is the wrong shape is a protocol
            // violation — degrade instead of guessing where it belongs.
            degrade(model, &receipt)
        }
    }
}

/// Routine quiet sweeps stay silent; any movement or refusal is housekeeping
/// evidence the operator should see once (D-12).
fn land_media_sweep(model: &mut AppModel, moved: u64, purged: u64, failures: u64) {
    let s = crate::i18n::UiStrings::detect();
    let status = if failures > 0 {
        Some(s.text(
            "Media sweep: some entries could not be checked",
            "媒体清理：部分条目无法检查",
        ))
    } else if moved > 0 || purged > 0 {
        Some(s.text(
            "Media sweep collected orphaned attachments",
            "媒体清理已回收孤立附件",
        ))
    } else {
        None
    };
    if let Some(text) = status {
        model.set_status(text);
    }
}

/// A reload's outcome on the model: the open Settings view re-projects from
/// the new snapshot and the status line names exactly which fields applied
/// live and which wait for restart — an honest report, never a silent apply.
fn land_config_applied(
    model: &mut AppModel,
    config: &crate::config::AppConfig,
    applied: &[crate::config::SettingsField],
    restart_pending: &[crate::config::SettingsField],
) {
    if let View::Settings(settings) = &mut model.view {
        settings.refresh(config);
    }
    let strings = &crate::i18n::UiStrings::detect();
    let mut parts: Vec<String> = Vec::new();
    if !applied.is_empty() {
        parts.push(format!(
            "{}{}",
            strings.text("applied: ", "已应用："),
            applied
                .iter()
                .map(|field| field.key())
                .collect::<Vec<_>>()
                .join(", ")
        ));
    }
    if !restart_pending.is_empty() {
        parts.push(format!(
            "{}{}",
            strings.text("takes effect on restart: ", "重启后生效："),
            restart_pending
                .iter()
                .map(|field| field.key())
                .collect::<Vec<_>>()
                .join(", ")
        ));
    }
    if parts.is_empty() {
        model.set_status(strings.text("Config unchanged", "配置无变化"));
    } else {
        model.set_status(&parts.join(" · "));
    }
}

/// A view placeholder answers exactly one request: the loaded view replaces it
/// only while `Loading` still awaits that exact request.
fn land_placeholder(model: &mut AppModel, req: Req, view: View) {
    if matches!(&model.view, View::Loading { req: live, .. } if *live == req) {
        model.view = view;
    }
}

/// A refresh issued for `target` lands only while that exact memo is still
/// the open reader — a reply arriving after the user moved on settles
/// quietly instead of swapping in a memo they never opened (09-F-04). The
/// receipt's own payload must name the target too; the anchor survives
/// because the identity match already pins the slot.
fn refresh_reader(model: &mut AppModel, memo: MemoCard, target: &lomo_workspace::MemoId) {
    if let View::Reader { memo: old, anchor } = &model.view
        && old.id == *target
        && memo.id == *target
    {
        let anchor = *anchor;
        model.view = View::Reader { memo, anchor };
    }
}

/// A `Saved` receipt lands only while it still names the submission the
/// composer tracks — intent revision and live `Submitting` marker together.
fn land_commit(
    model: &mut AppModel,
    expected: u64,
    revision: u64,
    id: lomo_workspace::MemoId,
) -> Option<Effect> {
    if expected == revision && model.draft.submitting_revision() == Some(revision) {
        return saved(model, revision, id);
    }
    None
}

/// A live history request whose receipt arrives while another input owns the
/// focus is parked, not dropped — `drain_parked` delivers it on `Browse` (A-14).
fn land_history(
    model: &mut AppModel,
    id: lomo_workspace::MemoId,
    revisions: Vec<crate::model::RevisionRow>,
) {
    if model.input == InputMode::Browse {
        open_history(model, id, revisions);
    } else {
        model
            .parked
            .push_back(ParkedReply::History { id, revisions });
        model.set_status(crate::i18n::UiStrings::detect().text(
            "Version history ready — it opens when the dialog closes",
            "版本历史已就绪——关闭当前对话框后打开",
        ));
    }
}

/// A failure lands by the request's intent, not by where it happens to hit —
/// and it always leaves a trace in the status line, even when its target has
/// already moved on (F-02/F-11: no silent drops).
fn land_failure(
    model: &mut AppModel,
    req: Req,
    intent: &PendingKind,
    diagnostic: &str,
) -> Option<Effect> {
    match intent {
        PendingKind::FeedPage => {
            if let Some(feed) = awaiting_feed(&mut model.view, &mut model.history, req) {
                feed.load = LoadStatus::Failed(diagnostic.to_owned());
                feed.pending_page = None;
            }
        }
        PendingKind::Navigate => {
            if matches!(&model.view, View::Loading { req: live, .. } if *live == req) {
                model.view = View::Failed {
                    screen: model.view.screen(),
                    diagnostic: diagnostic.to_owned(),
                };
            }
        }
        PendingKind::OpenMemo => {
            land_open_memo_failure(model, req, diagnostic);
            return None;
        }
        PendingKind::RefreshView { screen } => {
            if model.view.screen() == *screen {
                model.view = View::Failed {
                    screen: *screen,
                    diagnostic: diagnostic.to_owned(),
                };
            }
        }
        PendingKind::RefreshReader { id: target } => {
            // Only the reader still showing the refresh's own memo fails
            // visibly — a failure for a memo the user already left must not
            // destroy the reader they are in (09-F-04). The status line
            // below still carries the trace either way.
            if let View::Reader { memo, .. } = &model.view
                && memo.id == *target
            {
                model.view = View::Failed {
                    screen: model.view.screen(),
                    diagnostic: diagnostic.to_owned(),
                };
            }
        }
        PendingKind::DraftPersist { revision } => {
            if *revision == model.draft.revision && model.draft.submitting_revision().is_none() {
                model.draft.save = SaveState::Failed {
                    diagnostic: diagnostic.to_owned(),
                };
            }
        }
        PendingKind::DraftCommit { revision } => {
            if *revision == model.draft.revision {
                model.draft.save = SaveState::Failed {
                    diagnostic: diagnostic.to_owned(),
                };
            }
        }
        PendingKind::Date { dialog: true } => {
            if let InputMode::Date {
                req: live, error, ..
            } = &mut model.input
                && *live == Some(req)
            {
                *error = Some(diagnostic.to_owned());
            }
        }
        PendingKind::Bootstrap => {
            // While the first-run wizard is open, a mint/open failure returns
            // to it as an editable error — the wizard, not a dead view, owns
            // the failure so the user can fix a bad path and retry.
            if let InputMode::Setup(setup) = &mut model.input {
                setup.awaiting = false;
                setup.error = Some(diagnostic.to_owned());
            } else {
                model.view = View::Failed {
                    screen: model.view.screen(),
                    diagnostic: diagnostic.to_owned(),
                };
            }
        }
        PendingKind::Bodies
        | PendingKind::History { .. }
        | PendingKind::Tags
        | PendingKind::Date { dialog: false }
        | PendingKind::Mutation
        | PendingKind::Maintenance
        | PendingKind::Attachment
        | PendingKind::ConfigReload
        | PendingKind::Image
        | PendingKind::Quit => {}
    }
    if let Some((class, title)) = failure_badge(intent) {
        model.present(Notice::badge(
            Severity::Warn,
            class,
            title.to_owned(),
            vec![diagnostic.to_owned()],
        ));
    } else {
        model.set_status(diagnostic);
    }
    None
}

/// Opening one memo failed: the Loading placeholder pops back to the view
/// underneath and a friendly toast names the miss — a dead link is feedback
/// in context, not a `Failed` screen that destroys the reading stack (A-13/I9).
fn land_open_memo_failure(model: &mut AppModel, req: Req, diagnostic: &str) {
    if matches!(&model.view, View::Loading { req: live, .. } if *live == req)
        && let Some(view) = model.history.pop()
    {
        model.view = view;
    }
    let s = crate::i18n::UiStrings::detect();
    model.present(Notice::toast(
        Severity::Warn,
        s.text("Could not open that memo", "无法打开这条记录")
            .to_owned(),
        vec![diagnostic.to_owned()],
    ));
}

/// Intents whose failure has no in-place surface raise a persistent badge in
/// their class: an operation failure outlives the toast until it is
/// acknowledged or a same-class success retires it (I9). Lookup and refresh
/// misses keep the plain status trace — they are context, not operations.
fn failure_badge(intent: &PendingKind) -> Option<(BadgeClass, &'static str)> {
    let s = crate::i18n::UiStrings::detect();
    let badge = match intent {
        PendingKind::DraftPersist { .. } | PendingKind::DraftCommit { .. } => (
            BadgeClass::Draft,
            s.text("Draft save failed", "草稿保存失败"),
        ),
        PendingKind::Mutation | PendingKind::Quit => {
            (BadgeClass::Action, s.text("Operation failed", "操作失败"))
        }
        PendingKind::Attachment => (
            BadgeClass::Player,
            s.text("Could not open the attachment", "无法打开附件"),
        ),
        PendingKind::Maintenance => (BadgeClass::Sync, s.text("Refresh failed", "刷新失败")),
        PendingKind::ConfigReload => (
            BadgeClass::Sync,
            s.text("Config reload failed", "配置重载失败"),
        ),
        PendingKind::FeedPage
        | PendingKind::Navigate
        | PendingKind::OpenMemo
        | PendingKind::RefreshView { .. }
        | PendingKind::RefreshReader { .. }
        | PendingKind::Bodies
        | PendingKind::History { .. }
        | PendingKind::Tags
        | PendingKind::Date { .. }
        | PendingKind::Bootstrap
        | PendingKind::Image => return None,
    };
    Some(badge)
}

/// The feed that still awaits exactly this page request — slot identity, not
/// an epoch shared with every other feed (F-12). Takes field projections so
/// the caller keeps disjoint model fields usable while the feed is borrowed.
fn awaiting_feed<'a>(
    view: &'a mut View,
    history: &'a mut [View],
    req: Req,
) -> Option<&'a mut FeedState> {
    std::iter::once(view)
        .chain(history.iter_mut().rev())
        .find_map(|view| {
            if let View::Feed(feed) = view
                && feed.pending_page == Some(req)
            {
                Some(&mut **feed)
            } else {
                None
            }
        })
}

fn apply_page(
    model: &mut AppModel,
    req: Req,
    append: bool,
    mut cards: Vec<MemoCard>,
    next: Option<lomo_application::PageCursor>,
    order: &[lomo_workspace::MemoId],
    total: Option<u64>,
) {
    let Some(feed) = awaiting_feed(&mut model.view, &mut model.history, req) else {
        // The feed that requested this page moved on; the reply settles quietly.
        return;
    };
    feed.pending_page = None;
    if append {
        // Membership is indexed: a page merge never scans the loaded feed per card.
        let mut known: std::collections::BTreeSet<lomo_workspace::MemoId> =
            feed.memos.iter().map(|memo| memo.id.clone()).collect();
        for card in cards {
            if known.insert(card.id.clone()) {
                feed.memos.push(card);
            }
        }
    } else {
        let following_head = feed.memos.first().is_some_and(|first| {
            feed.selected.as_ref() == Some(&first.id)
                && feed.anchor.as_ref().is_some_and(|anchor| {
                    anchor.id == first.id && anchor.position == CardPosition::Time
                })
        });
        // Reuse a resident body only when the memo identity AND version both match —
        // indexed once, not re-scanned per incoming card.
        let mut retained: std::collections::BTreeMap<
            &lomo_workspace::MemoId,
            (u64, &str, &std::sync::Arc<crate::content::MemoBody>),
        > = std::collections::BTreeMap::new();
        for memo in &feed.memos {
            if let BodyState::Ready(body) = &memo.body {
                retained.insert(&memo.id, (memo.revision, memo.fingerprint.as_str(), body));
            }
        }
        for card in &mut cards {
            if let Some(&(revision, fingerprint, body)) = retained.get(&card.id)
                && card.revision == revision
                && card.fingerprint == fingerprint
            {
                card.body = BodyState::Ready(std::sync::Arc::clone(body));
            }
        }
        if following_head
            && cards
                .first()
                .is_some_and(|first| Some(&first.id) == model.last_created.as_ref())
        {
            feed.selected = cards.first().map(|first| first.id.clone());
            feed.anchor = feed.selected.clone().map(|id| MemoAnchor {
                id,
                position: CardPosition::Time,
            });
        }
        let previous = merge_window(feed, cards, order);
        if feed.reconcile_replacement(&previous) {
            model.status = Some(
                crate::i18n::UiStrings::detect()
                    .text(
                        "The previous memo left this view; selected its nearest neighbor",
                        "原记录已离开当前视图，已定位到相邻记录",
                    )
                    .to_owned(),
            );
        }
    }
    // The reply's `next` is already the live frontier for either arm: a
    // refresh mints it at the retained tail's own live position (or at the
    // window's edge when nothing survived below), so it supersedes the feed's
    // pre-refresh cursor wholesale — dead revision or not.
    feed.next_cursor = next;
    // The query total is bound to the first page; append replies carry none
    // and must not erase the established count.
    if total.is_some() {
        feed.total = total;
    }
    feed.load = LoadStatus::Ready;
    feed.reconcile();
}

/// Replays a non-append reply's `order` evidence over `feed.memos`, returning
/// the displaced cards for `reconcile_replacement`.
///
/// `order` is the live-rank id sequence over the union of the reply window
/// and the still-live loaded set: reply members contribute their fresh card,
/// loaded survivors keep their resident card, and a loaded card absent from
/// `order` left the result set — the only eviction evidence. No position is
/// inferred from a stale loading-side slot; every survivor lands at the rank
/// the query reported.
fn merge_window(
    feed: &mut FeedState,
    cards: Vec<MemoCard>,
    order: &[lomo_workspace::MemoId],
) -> Vec<MemoCard> {
    let previous = std::mem::take(&mut feed.memos);
    let mut replies: std::collections::BTreeMap<lomo_workspace::MemoId, MemoCard> = cards
        .into_iter()
        .map(|card| (card.id.clone(), card))
        .collect();
    let mut slots: std::collections::BTreeMap<&lomo_workspace::MemoId, &MemoCard> =
        previous.iter().map(|memo| (&memo.id, memo)).collect();
    let mut merged = Vec::with_capacity(order.len());
    for id in order {
        if let Some(card) = replies.remove(id) {
            merged.push(card);
        } else if let Some(memo) = slots.remove(id) {
            merged.push(memo.clone());
        }
        // `order` derives from `known ∪ reply` — an id naming neither is a
        // protocol violation by the producer, and pushing a phantom card
        // would be worse than landing nothing for it.
    }
    feed.memos = merged;
    previous
}

/// Every copy of a memo (views, history and the picker it was opened on) receives its body.
fn hydrate_all(model: &mut AppModel, replies: &[BodyReply]) {
    if replies.is_empty() {
        return;
    }
    hydrate(&mut model.view, replies);
    if let InputMode::Picker(picker) = &mut model.input {
        match &mut picker.kind {
            crate::model::PickerKind::Palette {
                item: crate::model::PaletteItem::Memo(memo),
                ..
            }
            | crate::model::PickerKind::Attachments(memo) => hydrate_memo(memo, replies),
            crate::model::PickerKind::Palette { .. }
            | crate::model::PickerKind::Tags(_)
            | crate::model::PickerKind::Dates
            | crate::model::PickerKind::History { .. } => {}
        }
    }
    for view in &mut model.history {
        hydrate(view, replies);
    }
}

fn hydrate(view: &mut View, replies: &[BodyReply]) {
    match view {
        View::Feed(feed) => {
            // The reply stream drives the landing: one id→slot index per feed
            // per batch — no per-card `version()` snapshot and no per-reply
            // scan of the loaded list. The index borrows `memos`, so the
            // mutations wait for the second pass.
            if feed.memos.is_empty() {
                return;
            }
            let landings: Vec<(usize, &BodyReply)> = {
                let slots: std::collections::HashMap<&lomo_workspace::MemoId, usize> = feed
                    .memos
                    .iter()
                    .enumerate()
                    .map(|(index, memo)| (&memo.id, index))
                    .collect();
                replies
                    .iter()
                    .filter_map(|reply| slots.get(&reply.version.id).map(|&index| (index, reply)))
                    .collect()
            };
            for (index, reply) in landings {
                if let Some(memo) = feed.memos.get_mut(index) {
                    land_body(memo, reply);
                }
            }
        }
        View::Reader { memo, .. } => hydrate_memo(memo, replies),
        View::Tasks(_)
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Loading { .. }
        | View::Failed { .. } => {}
    }
}

fn hydrate_memo(memo: &mut MemoCard, replies: &[BodyReply]) {
    for reply in replies {
        land_body(memo, reply);
    }
}

/// A reply lands only on a card still holding the exact version it names —
/// identity plus revision plus fingerprint. A card that refreshed past this
/// version rejects the body entirely; it re-requests under its new identity.
fn land_body(memo: &mut MemoCard, reply: &BodyReply) {
    let version = &reply.version;
    if version.id != memo.id
        || version.revision != memo.revision
        || version.fingerprint != memo.fingerprint
    {
        return;
    }
    match &reply.result {
        Ok(loaded) => {
            memo.body = BodyState::Ready(std::sync::Arc::clone(&loaded.body));
            memo.attachments.clone_from(&loaded.attachments);
        }
        Err(error) => memo.body = BodyState::Failed(error.clone()),
    }
}

fn open_history(
    model: &mut AppModel,
    id: lomo_workspace::MemoId,
    revisions: Vec<crate::model::RevisionRow>,
) {
    let mut picker = crate::model::Picker {
        kind: crate::model::PickerKind::History { id, revisions },
        text: crate::input::TextBuffer::default(),
        selected: 0,
        identity: None,
    };
    // Stamp the selection identity — Enter and the highlight then name the
    // same revision row (I2).
    picker.rebind(&crate::menu::entries(model, &picker));
    model.input = InputMode::Picker(picker);
}

/// A date reply lands only where its intent pointed: a dialog reply needs the
/// dialog still awaiting this exact request; a preset reply applies the filter
/// to the timeline directly — it was never bound to a visible input (A-10).
fn apply_date(
    model: &mut AppModel,
    req: Req,
    dialog: bool,
    from: i64,
    until: i64,
    label: String,
) -> Option<Effect> {
    if dialog {
        if !matches!(&model.input, InputMode::Date { req: live, .. } if *live == Some(req)) {
            return None;
        }
        model.input = InputMode::Browse;
    }
    crate::update::ensure_feed(model);
    if let View::Feed(feed) = &mut model.view {
        feed.remember_unfiltered();
        feed.query.filters.date_from_inclusive_ms = Some(from);
        feed.query.filters.date_until_exclusive_ms = Some(until);
        feed.query.date_label = Some(label);
        feed.mark_requery();
    }
    crate::update::requery(model)
}

/// Re-read what is on screen after external or mutation evidence.
///
/// History feeds go stale (their superseded page requests are cancelled), the
/// current feed reloads in place, and other views refresh through their own
/// intent.
#[must_use]
pub fn refresh_current(model: &mut AppModel) -> Option<Effect> {
    let mut retired = Vec::new();
    for view in &mut model.history {
        if let View::Feed(feed) = view {
            if let Some(req) = feed.pending_page.take() {
                retired.push(req);
            }
            feed.load = LoadStatus::Stale;
        }
    }
    for req in retired {
        model.pending.cancel(req);
    }
    if matches!(model.view, View::Feed(_)) {
        return crate::update::reload_feed(model);
    }
    if let View::Reader { memo, .. } = &model.view {
        let id = memo.id.clone();
        let req = model.request(PendingKind::RefreshReader { id: id.clone() });
        return Some(Effect::ReadMemo { req, id });
    }
    let screen = model.view.screen();
    let req = model.request(PendingKind::RefreshView { screen });
    Some(Effect::Navigate { req, screen })
}

fn saved(model: &mut AppModel, revision: u64, id: lomo_workspace::MemoId) -> Option<Effect> {
    // Landing is only reached with a live `Submitting` marker for this
    // revision — a discarded draft already cancelled the request, so its stale
    // receipt never reaches here at all.
    model.draft = Composer {
        revision: revision.saturating_add(1),
        persisted_revision: revision.saturating_add(1),
        ..Composer::default()
    };
    if model.input == InputMode::Compose {
        model.input = InputMode::Browse;
    }
    model.last_created = Some(id);
    model.set_status(crate::i18n::UiStrings::detect().text(
        "Saved · commands: view last saved memo",
        "已保存 · 功能菜单可查看刚保存的记录",
    ));
    refresh_current(model)
}
