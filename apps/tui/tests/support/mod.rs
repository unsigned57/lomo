use std::sync::Arc;

use lomo_tui::error::TuiError;
use lomo_tui::model::{
    AppModel, BodyState, FeedState, LoadStatus, MemoCard, PendingKind, Req, View,
};
use lomo_workspace::MemoId;

pub type TestResult = Result<(), Box<dyn std::error::Error>>;

/// # Errors
/// Propagates fixture, session or expected-view failures.
pub fn memo(id: &str, body: &str) -> Result<MemoCard, TuiError> {
    Ok(MemoCard {
        id: MemoId::parse(id)?,
        date: "2026-09-11".to_owned(),
        time: "12:00".to_owned(),
        summary: body.to_owned(),
        body: BodyState::Ready(Arc::new(lomo_tui::content::MemoBody::parse(
            body.to_owned(),
        )?)),
        tags: Vec::new(),
        attachments: Vec::new(),
        fingerprint: "version-1".to_owned(),
        revision: 1,
        pinned: false,
        trashed: false,
        excerpt: None,
    })
}

/// # Errors
/// Propagates fixture, session or expected-view failures.
pub fn model_with_memos(count: usize, width: u16, height: u16) -> Result<AppModel, TuiError> {
    let mut model = AppModel::new(width, height);
    if let View::Feed(feed) = &mut model.view {
        feed.memos = (0..count)
            .map(|index| memo(&format!("memo-{index}"), &format!("Body {index}: 阅读内容")))
            .collect::<Result<_, _>>()?;
        feed.load = LoadStatus::Ready;
        feed.total =
            Some(u64::try_from(count).map_err(|error| TuiError::config(error.to_string()))?);
        feed.reconcile();
    }
    Ok(model)
}

/// # Errors
/// Propagates fixture, session or expected-view failures.
pub fn feed(model: &AppModel) -> Result<&FeedState, &'static str> {
    if let View::Feed(feed) = &model.view {
        Ok(feed)
    } else {
        Err("expected the memo feed")
    }
}

/// # Errors
/// Propagates fixture, session or expected-view failures.
pub fn feed_mut(model: &mut AppModel) -> Result<&mut FeedState, &'static str> {
    if let View::Feed(feed) = &mut model.view {
        Ok(feed)
    } else {
        Err("expected the memo feed")
    }
}

/// Registers a live page request on the current feed — the state `reload_feed`
/// produces while its `Effect::Query` is in flight, without executing the query.
/// # Errors
/// Propagates the missing-feed fixture error.
pub fn pending_page(model: &mut AppModel) -> Result<Req, &'static str> {
    let req = model.request(PendingKind::FeedPage);
    feed_mut(model)?.pending_page = Some(req);
    Ok(req)
}

pub struct RuntimeFixture {
    pub root: tempfile::TempDir,
    pub runtime: lomo_tui::ops::TuiRuntime,
}

impl RuntimeFixture {
    /// # Errors
    /// Propagates fixture, session or expected-view failures.
    pub fn new() -> Result<Self, Box<dyn std::error::Error>> {
        let root = tempfile::tempdir()?;
        let runtime = runtime_at(root.path(), "notes")?;
        Ok(Self { root, runtime })
    }

    /// # Errors
    /// Propagates fixture, session or expected-view failures.
    pub fn seed(&self, count: usize) -> Result<(), Box<dyn std::error::Error>> {
        use std::fmt::Write;
        let mut text = String::new();
        for index in 0..count {
            write!(
                text,
                "- 10:{:02}:{:02}\nneedle {index} 八达岭长城 #reading/book\n\n",
                index / 60,
                index % 60
            )?;
        }
        std::fs::write(self.runtime.workspace.join("2026_09_11.md"), text)?;
        self.runtime.session.rebuild_projection()?;
        Ok(())
    }
}

/// # Errors
/// Propagates fixture, session or expected-view failures.
pub fn runtime_at(
    root: &std::path::Path,
    workspace: &str,
) -> Result<lomo_tui::ops::TuiRuntime, TuiError> {
    let paths = lomo_tui::xdg::RuntimePaths {
        config_dir: root.join("config"),
        state_dir: root.join("state"),
        cache_dir: root.join("cache"),
        runtime_dir: root.join("run"),
        drafts_dir: root.join("state/drafts"),
        exchange_dir: root.join("state/exchange"),
        default_workspace: None,
        home_dir: Some(root.join("home")),
    };
    let config = lomo_tui::config::AppConfig {
        workspace: root.join(workspace),
        media_dir: root.join(format!("{workspace}/media")),
        time_zone: "UTC".to_owned(),
        date_format: lomo_application::calendar::DateFormat::default(),
        editor: Some(vec!["scripted".to_owned()]),
        player: vec!["xdg-open".to_owned()],
    };
    // The fixture's explicit "create a library" step: opening must not create.
    std::fs::create_dir_all(&config.workspace).map_err(TuiError::from)?;
    lomo_tui::ops::open_runtime(paths, config)
}

/// A graphics verdict the way `StdioProber` answers on a capable terminal —
/// real font metrics with an explicit protocol — installed directly so tests
/// never touch stdio.
#[must_use]
pub fn ready_graphics(
    protocol: ratatui_image::picker::ProtocolType,
) -> lomo_tui::graphics::GraphicsVerdict {
    let mut picker = ratatui_image::picker::Picker::from_fontsize((8, 16));
    picker.set_protocol_type(protocol);
    lomo_tui::graphics::GraphicsVerdict::Ready(lomo_tui::graphics::SharedPicker::new(picker))
}

/// # Errors
/// Propagates fixture, session or expected-view failures.
pub fn run_effect(
    runtime: &lomo_tui::ops::TuiRuntime,
    model: &mut AppModel,
    effect: Option<lomo_tui::effects::Effect>,
) -> Result<(), TuiError> {
    let mut pending = effect;
    let (results, _inbox) = std::sync::mpsc::sync_channel(256);
    let outbox = lomo_tui::executor::Outbox::new(results);
    let token = lomo_tui::model::CancelToken::live();
    while let Some(effect) = pending {
        let reply = lomo_tui::ops::execute(runtime, &effect, &outbox, &token)?;
        pending = lomo_tui::messages::apply_message(model, reply);
    }
    Ok(())
}

/// # Errors
/// Propagates fixture, session or expected-view failures.
pub fn command(
    runtime: &lomo_tui::ops::TuiRuntime,
    model: &mut AppModel,
    command: lomo_tui::event::Command,
) -> Result<(), TuiError> {
    let effect = lomo_tui::update::apply_command(model, command);
    run_effect(runtime, model, effect)
}

/// # Errors
/// Propagates fixture, session or expected-view failures.
pub fn body_reply(card: MemoCard) -> Result<lomo_tui::effects::BodyReply, TuiError> {
    let version = card.version();
    if let BodyState::Ready(body) = card.body {
        Ok(lomo_tui::effects::BodyReply {
            version,
            result: Ok(lomo_tui::effects::LoadedBody {
                body,
                attachments: card.attachments,
            }),
        })
    } else {
        Err(TuiError::config("fixture must contain a loaded body"))
    }
}
