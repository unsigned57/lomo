//! One grouped command palette and its secondary pickers share one action catalogue.
//!
//! The catalogue is a projection of the capability table (I2): every entry
//! carries the `Availability` verdict `Command::availability` returns right
//! now — `Ready` rows dispatch on Enter, `Refused` rows stay listed greyed
//! and name their reason, and `Hidden` actions are never materialized into
//! rows at all.
use crate::event::{Availability, Command, MemoAction};
use crate::i18n::UiStrings;
use crate::model::{AppModel, PaletteItem, PaletteScope, Picker, PickerKind, Screen};

/// Palette groups in display order.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub enum PaletteGroup {
    Item,
    Filters,
    Pages,
    Global,
}

#[derive(Clone, Debug)]
pub struct MenuEntry {
    pub label: String,
    pub command: Command,
    pub group: PaletteGroup,
    /// The direct key that runs the same command while browsing — read off
    /// `KEY_BINDINGS`, so the column can never name a key nothing dispatches.
    pub key: Option<&'static str>,
    /// The capability verdict this row projects: `Ready` dispatch on Enter;
    /// `Refused` draws greyed and prints its reason.
    pub availability: Availability,
}

impl MenuEntry {
    /// An explicit key column for rows whose action the view's accept key
    /// performs indirectly — a task row's "Enter" toggles through `Accept`.
    /// The label must be a real `KEY_BINDINGS` label (the contract test
    /// enforces it), so even contextual keys are read off the binding table.
    const fn with_key(mut self, key: &'static str) -> Self {
        self.key = Some(key);
        self
    }
}

/// A rendered picker row: group headers are not selectable. `Tag` rows draw
/// straight from the shared dictionary name — no per-row label allocation.
#[derive(Clone, Debug)]
pub enum MenuRow {
    Header(String),
    Entry(MenuEntry),
    /// One tag dictionary name; the draw path writes `{marker} #{name}`.
    Tag(std::sync::Arc<str>),
}

/// Selectable rows in selection order, shared via `Arc` — the tag picker's
/// list is the same allocation every frame until its key inputs change.
#[must_use]
pub fn entries(model: &AppModel, picker: &Picker) -> std::sync::Arc<[MenuEntry]> {
    let strings = UiStrings::detect();
    let base: std::sync::Arc<[MenuEntry]> = match &picker.kind {
        PickerKind::Palette { item, scope } => {
            std::sync::Arc::from(palette(model, item, *scope, strings))
        }
        PickerKind::Tags(scope) => {
            // Tag rows are memoized projections of the dictionary: the
            // unfiltered list and each distinct filter text are each built
            // once per key change — the generic filter pass below never runs
            // on this path.
            let text = picker.text.text();
            return if text.is_empty() {
                model.tag_entries(*scope)
            } else {
                model.tag_entries_filtered(*scope, text)
            };
        }
        PickerKind::Dates => std::sync::Arc::from(dates(model, strings)),
        PickerKind::Attachments(memo) => std::sync::Arc::from(attachments(memo)),
        PickerKind::History { revisions, .. } => std::sync::Arc::from(history(revisions, strings)),
    };
    let query = picker.text.text().to_lowercase();
    // An open picker with an empty filter keeps every row — no per-label
    // lowercase pass unless the user is actually filtering.
    if query.is_empty() {
        return base;
    }
    let mut kept: Vec<MenuEntry> = base
        .iter()
        .filter(|entry| entry.label.to_lowercase().contains(&query))
        .cloned()
        .collect();
    if picker.kind == PickerKind::Dates {
        kept.push(row(
            strings.text("Use typed date or range", "使用输入的日期或范围"),
            Command::SetDate(picker.text.text().to_owned()),
        ));
    }
    std::sync::Arc::from(kept)
}

/// The tag picker's memoized projections on the model — `entries` and `rows`
/// are separate materializations of the same `(tags_version, scope, language)`
/// key, and `filtered` holds the `(entries, rows)` pair for the current query
/// text. Each materialization is built at most once per key change.
#[derive(Default)]
pub(crate) struct TagMenuCache {
    pub(crate) entries: Option<(
        u64,
        lomo_application::TagSelectionMode,
        crate::i18n::UiLanguage,
        std::sync::Arc<[MenuEntry]>,
    )>,
    pub(crate) rows: Option<(
        u64,
        lomo_application::TagSelectionMode,
        crate::i18n::UiLanguage,
        std::sync::Arc<[MenuRow]>,
    )>,
    /// The one live filtered projection — the key includes the filter text
    /// itself, so a repaint never re-filters and a keystroke re-keys honestly.
    pub(crate) filtered: Option<FilteredTagMenu>,
}

/// The filtered tag menu: entries kept by the picker filter rule plus the rows
/// drawn from them — one build serves both surfaces.
pub(crate) struct FilteredTagMenu {
    pub(crate) version: u64,
    pub(crate) scope: lomo_application::TagSelectionMode,
    pub(crate) language: crate::i18n::UiLanguage,
    /// The raw filter text — part of the key, so stale rows can never answer
    /// for a query they were not built from.
    pub(crate) text: String,
    pub(crate) entries: std::sync::Arc<[MenuEntry]>,
    pub(crate) rows: std::sync::Arc<[MenuRow]>,
}

/// The tag picker's entries — one allocation per label and command payload.
pub(crate) fn tag_entries(
    model: &AppModel,
    scope: lomo_application::TagSelectionMode,
) -> Vec<MenuEntry> {
    tags(model, scope, UiStrings::detect())
}

/// The tag picker's drawn rows: two header entries, then one `Tag` row per
/// shared dictionary name — the only per-row work is cloning the `Arc`.
pub(crate) fn tag_row_list(
    model: &AppModel,
    scope: lomo_application::TagSelectionMode,
) -> Vec<MenuRow> {
    let s = UiStrings::detect();
    let scope_label = if scope == lomo_application::TagSelectionMode::Subtree {
        s.text("✓ Include child tags", "✓ 包含子标签")
    } else {
        s.text("□ Include child tags", "□ 包含子标签")
    };
    let names = model.tag_names();
    let mut rows = Vec::with_capacity(names.len() + 2);
    rows.push(MenuRow::Entry(row(
        s.text("All tags", "全部标签"),
        Command::SelectTag(None),
    )));
    rows.push(MenuRow::Entry(row(scope_label, Command::ToggleTagScope)));
    rows.extend(
        names
            .iter()
            .map(|tag| MenuRow::Tag(std::sync::Arc::clone(tag))),
    );
    rows
}

/// The filtered tag menu for one query text: entries kept by the same
/// lowercase-label rule the generic picker filter uses. Kept tag entries draw
/// as `MenuRow::Tag` so a filtered repaint clones no label strings.
pub(crate) fn filtered_tags(
    model: &AppModel,
    scope: lomo_application::TagSelectionMode,
    text: &str,
) -> (Vec<MenuEntry>, Vec<MenuRow>) {
    let query = text.to_lowercase();
    let kept: Vec<MenuEntry> = model
        .tag_entries(scope)
        .iter()
        .filter(|entry| entry.label.to_lowercase().contains(&query))
        .cloned()
        .collect();
    let rows = kept
        .iter()
        .map(|entry| {
            if let Command::SelectTag(Some(tag)) = &entry.command {
                MenuRow::Tag(std::sync::Arc::clone(tag))
            } else {
                MenuRow::Entry(entry.clone())
            }
        })
        .collect();
    (kept, rows)
}

/// Rows as drawn: the palette inserts a header before each group that survived filtering.
#[must_use]
pub fn rows(model: &AppModel, picker: &Picker) -> std::sync::Arc<[MenuRow]> {
    // Tag pickers — filtered or not — share the memoized projections verbatim.
    if let PickerKind::Tags(scope) = picker.kind {
        let text = picker.text.text();
        return if text.is_empty() {
            model.tag_menu_rows(scope)
        } else {
            model.tag_menu_rows_filtered(scope, text)
        };
    }
    let strings = UiStrings::detect();
    let grouped = matches!(picker.kind, PickerKind::Palette { .. });
    let entries = entries(model, picker);
    let mut out = Vec::with_capacity(entries.len() + 8);
    let mut current = None;
    for entry in entries.iter() {
        if grouped && current != Some(entry.group) {
            current = Some(entry.group);
            out.push(MenuRow::Header(
                group_title(entry.group, strings).to_owned(),
            ));
        }
        out.push(MenuRow::Entry(entry.clone()));
    }
    std::sync::Arc::from(out)
}

/// Display row of the entry the selection names.
///
/// The picker's `identity` anchors the highlight across list rebuilds,
/// `selected` is the positional fallback — Enter (`Picker::entry_index`)
/// and the mark share this rule, so the row that lights up is always the
/// row Enter runs.
#[must_use]
pub fn selected_row(rows: &[MenuRow], picker: &Picker) -> usize {
    let mut selectable_count = 0usize;
    let mut positional = 0usize;
    for (index, row) in rows.iter().enumerate() {
        if !row.selectable() {
            continue;
        }
        if let Some(identity) = &picker.identity
            && row.names(identity)
        {
            return index;
        }
        if selectable_count == picker.selected {
            positional = index;
        }
        selectable_count += 1;
    }
    // `selected` beyond the surviving list clamps to the last selectable row;
    // an identity whose command vanished falls back to the same rule.
    if selectable_count != 0 && picker.selected >= selectable_count {
        return rows
            .iter()
            .enumerate()
            .rev()
            .find(|(_, row)| row.selectable())
            .map_or(0, |(index, _)| index);
    }
    positional
}

/// Entry index of a display row, if it is selectable.
#[must_use]
pub fn entry_at(rows: &[MenuRow], row: usize) -> Option<usize> {
    if !rows.get(row).is_some_and(MenuRow::selectable) {
        return None;
    }
    Some(rows.iter().take(row).filter(|row| row.selectable()).count())
}

impl MenuRow {
    /// Whether the row maps to an entry index — headers are spacers only.
    const fn selectable(&self) -> bool {
        matches!(self, Self::Entry(_) | Self::Tag(_))
    }

    /// The command identity this row answers to — a `Tag` row names
    /// `SelectTag(name)` without materializing an entry.
    fn names(&self, command: &Command) -> bool {
        match self {
            Self::Entry(entry) => &entry.command == command,
            Self::Tag(tag) => {
                matches!(command, Command::SelectTag(Some(name)) if name == tag)
            }
            Self::Header(_) => false,
        }
    }
}

const fn group_title(group: PaletteGroup, s: &UiStrings) -> &'static str {
    match group {
        PaletteGroup::Item => s.text("This item", "当前项"),
        PaletteGroup::Filters => s.text("Filters", "筛选"),
        PaletteGroup::Pages => s.text("Pages", "页面"),
        PaletteGroup::Global => s.text("Everything else", "全局"),
    }
}

/// A picker item row: selectable, always `Ready` — picker items are
/// in-context by construction (a history row exists only because the
/// revision does). Action rows that need a verdict go through `keyed`.
fn row(label: &str, command: Command) -> MenuEntry {
    MenuEntry {
        label: label.to_owned(),
        command,
        group: PaletteGroup::Global,
        key: None,
        availability: Availability::Ready,
    }
}

/// An action row carrying its live capability verdict; the key column is
/// derived from the binding table, never written by hand.
fn keyed(
    label: &str,
    command: Command,
    group: PaletteGroup,
    availability: Availability,
) -> MenuEntry {
    let key = command.browse_key_label();
    MenuEntry {
        label: label.to_owned(),
        command,
        group,
        key,
        availability,
    }
}

fn palette(
    model: &AppModel,
    item: &PaletteItem,
    scope: PaletteScope,
    s: &UiStrings,
) -> Vec<MenuEntry> {
    let mut rows = match item {
        PaletteItem::None => Vec::new(),
        PaletteItem::Memo(memo) => memo_actions(model, memo, s),
        PaletteItem::Task(task) => task_actions(model, task, s),
        PaletteItem::Attachment(attachment) => attachment_actions(attachment, s),
    };
    if scope == PaletteScope::Item {
        return rows;
    }
    rows.extend(filter_actions(model, s));
    rows.extend(
        [
            Screen::Timeline,
            Screen::Tasks,
            Screen::Review,
            Screen::Statistics,
            Screen::Attachments,
            Screen::Trash,
            Screen::Settings,
        ]
        .into_iter()
        .map(|screen| {
            let command = Command::Goto(screen);
            let verdict = command.availability(model);
            keyed(
                s.screen_title(screen),
                command,
                PaletteGroup::Pages,
                verdict,
            )
        }),
    );
    rows.extend(global_actions(model, s));
    rows
}

/// The item's action rows: every command the memo understands, in display
/// order. A `Refused` verdict stays listed (greyed, naming its reason — the
/// row itself answers "why can't I pin this?"); a `Hidden` action makes no
/// sense in this state and is never emitted, so the row count itself is a
/// capability projection.
fn memo_actions(model: &AppModel, memo: &crate::model::MemoCard, s: &UiStrings) -> Vec<MenuEntry> {
    let g = PaletteGroup::Item;
    let mut rows = vec![
        keyed(
            s.text("Read", "阅读全文"),
            Command::Accept,
            g,
            MemoAction::Read.availability(model, memo),
        ),
        keyed(
            s.text("Restore", "恢复记录"),
            Command::Restore,
            g,
            MemoAction::Restore.availability(model, memo),
        ),
        keyed(
            if memo.pinned {
                s.text("Unpin", "取消置顶")
            } else {
                s.text("Pin", "置顶")
            },
            Command::Pin,
            g,
            MemoAction::Pin.availability(model, memo),
        ),
        keyed(
            s.text("Edit externally", "使用外部编辑器编辑"),
            Command::ExternalEdit,
            g,
            MemoAction::Edit.availability(model, memo),
        ),
        keyed(
            s.text("Attachments", "附件"),
            Command::Attachments,
            g,
            MemoAction::Attachments.availability(model, memo),
        ),
        keyed(
            s.text("History", "版本历史"),
            Command::History,
            g,
            MemoAction::History.availability(model, memo),
        ),
        if memo.trashed {
            keyed(
                s.text("Delete permanently", "永久删除"),
                Command::DeleteForever,
                g,
                MemoAction::DeleteForever.availability(model, memo),
            )
        } else {
            keyed(
                s.text("Move to trash", "移入回收站"),
                Command::Delete,
                g,
                MemoAction::Delete.availability(model, memo),
            )
        },
        keyed(
            s.text("Empty trash", "清空回收站"),
            Command::EmptyTrash,
            g,
            Command::EmptyTrash.availability(model),
        ),
    ];
    rows.retain(|entry| entry.availability != Availability::Hidden);
    rows
}

fn task_actions(model: &AppModel, task: &crate::model::TaskRow, s: &UiStrings) -> Vec<MenuEntry> {
    let g = PaletteGroup::Item;
    vec![
        keyed(
            if task.done {
                s.text("Mark as open", "标记为未完成")
            } else {
                s.text("Mark as done", "标记为已完成")
            },
            Command::ToggleTask,
            g,
            Command::ToggleTask.availability(model),
        )
        // The task view's Enter dispatches `Accept`, which toggles the
        // selected row — the same action, so the hint names that key.
        .with_key("Enter"),
        keyed(
            s.text("Open memo", "打开所在记录"),
            Command::OpenMemo(task.memo_id.clone()),
            g,
            Command::OpenMemo(task.memo_id.clone()).availability(model),
        ),
    ]
}

fn attachment_actions(item: &crate::model::AttachmentRow, s: &UiStrings) -> Vec<MenuEntry> {
    let g = PaletteGroup::Item;
    let mut rows = vec![
        keyed(
        s.text("Open with player", "用播放器打开"),
        Command::OpenAttachment(item.path.clone()),
        g,
        Availability::Ready,
    )
    // Enter on the attachments screen opens the selected row's path.
    .with_key("Enter"),
    ];
    for owner in &item.owners {
        if let Ok(id) = lomo_workspace::MemoId::parse(owner) {
            rows.push(keyed(
                &format!("{} {owner}", s.text("Open memo", "打开记录")),
                Command::OpenMemo(id),
                g,
                Availability::Ready,
            ));
        }
    }
    rows
}

fn filter_actions(model: &AppModel, s: &UiStrings) -> Vec<MenuEntry> {
    let g = PaletteGroup::Filters;
    let mut rows = vec![
        keyed(
            s.text("Search", "搜索"),
            Command::Search,
            g,
            Command::Search.availability(model),
        ),
        keyed(
            s.text("Filter by tag", "按标签筛选"),
            Command::Tags,
            g,
            Command::Tags.availability(model),
        ),
        keyed(
            s.text("Filter by date", "按日期筛选"),
            Command::Date,
            g,
            Command::Date.availability(model),
        ),
    ];
    let Some(query) = model.timeline_query() else {
        return rows;
    };
    if !query.text.is_empty() {
        rows.push(keyed(
            s.text("Toggle fulltext / fuzzy search", "切换全文／模糊搜索"),
            Command::ToggleSearchMode,
            g,
            Command::ToggleSearchMode.availability(model),
        ));
        rows.push(keyed(
            s.text("Remove keyword filter", "移除关键词条件"),
            Command::RemoveKeyword,
            g,
            Command::RemoveKeyword.availability(model),
        ));
    }
    if query.date_label.is_some() {
        rows.push(keyed(
            s.text("Remove date filter", "移除日期条件"),
            Command::RemoveDate,
            g,
            Command::RemoveDate.availability(model),
        ));
    }
    if query.is_filtered() {
        rows.push(keyed(
            s.text("Clear filters", "清空筛选"),
            Command::ClearFilters,
            g,
            Command::ClearFilters.availability(model),
        ));
    }
    rows
}

fn global_actions(model: &AppModel, s: &UiStrings) -> Vec<MenuEntry> {
    let g = PaletteGroup::Global;
    let mut rows = vec![
        keyed(
            s.text("New memo", "新建记录"),
            Command::Compose,
            g,
            Command::Compose.availability(model),
        ),
        keyed(
            s.text("Import clipboard image", "导入剪贴板图片"),
            Command::ImportClipboard,
            g,
            Command::ImportClipboard.availability(model),
        ),
    ];
    if model.last_created.is_some() {
        rows.push(keyed(
            s.text("View last saved memo", "查看刚保存的记录"),
            Command::ShowCreated,
            g,
            Command::ShowCreated.availability(model),
        ));
    }
    if model.notice.is_some() {
        rows.push(keyed(
            s.text("Last notification", "查看最近提示"),
            Command::ShowNotice,
            g,
            Command::ShowNotice.availability(model),
        ));
    }
    rows.push(keyed(
        s.text("Refresh workspace", "刷新工作区"),
        Command::Refresh,
        g,
        Command::Refresh.availability(model),
    ));
    if !model.draft.text.text().trim().is_empty() {
        rows.push(keyed(
            s.text("Discard capture draft", "丢弃速记草稿"),
            Command::DiscardDraft,
            g,
            Command::DiscardDraft.availability(model),
        ));
    }
    rows.push(keyed(
        s.text("Help", "帮助"),
        Command::Help,
        g,
        Command::Help.availability(model),
    ));
    rows.push(keyed(
        s.text("Quit", "退出"),
        Command::Quit,
        g,
        Command::Quit.availability(model),
    ));
    rows
}

fn tags(
    model: &AppModel,
    scope: lomo_application::TagSelectionMode,
    s: &UiStrings,
) -> Vec<MenuEntry> {
    let scope_label = if scope == lomo_application::TagSelectionMode::Subtree {
        s.text("✓ Include child tags", "✓ 包含子标签")
    } else {
        s.text("□ Include child tags", "□ 包含子标签")
    };
    let mut rows = vec![
        row(s.text("All tags", "全部标签"), Command::SelectTag(None)),
        row(scope_label, Command::ToggleTagScope),
    ];
    // The flattened dictionary is memoized on the model — only a tag reply
    // rebuilds it; picker repaints share the `Arc`. Labels allocate once;
    // commands clone the shared name.
    rows.extend(model.tag_names().iter().map(|tag| {
        let mut label = String::with_capacity(tag.len() + 1);
        label.push('#');
        label.push_str(tag);
        MenuEntry {
            label,
            command: Command::SelectTag(Some(std::sync::Arc::clone(tag))),
            group: PaletteGroup::Global,
            key: None,
            availability: Availability::Ready,
        }
    }));
    rows
}

#[must_use]
pub fn tag_names(dictionary: &[String]) -> Vec<std::sync::Arc<str>> {
    // Borrowed slices until dedup — the dictionary expansion allocates one
    // `Arc<str>` per unique name, not per occurrence or B-tree node, and every
    // picker row clones the pointer.
    let mut names: Vec<&str> = Vec::with_capacity(dictionary.len() * 2);
    for tag in dictionary {
        names.push(tag.as_str());
        for (index, _) in tag.match_indices('/') {
            if let Some(parent) = tag.get(..index).map(|parent| parent.trim_end_matches('/'))
                && !parent.is_empty()
            {
                names.push(parent);
            }
        }
    }
    names.sort_unstable();
    names.dedup();
    names.into_iter().map(std::sync::Arc::from).collect()
}

/// The first non-empty, non-timestamp line of a revision, clipped for one picker row.
#[must_use]
pub fn preview(content: &str) -> String {
    const MAX: usize = 60;
    let line = content
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty() && !line.starts_with("- ") || line.starts_with("- ["))
        .unwrap_or("");
    let mut out: String = line.chars().take(MAX).collect();
    if line.chars().count() > MAX {
        out.push('…');
    }
    out
}

fn history(revisions: &[crate::model::RevisionRow], s: &UiStrings) -> Vec<MenuEntry> {
    let mut entries: Vec<MenuEntry> = revisions
        .iter()
        .map(|item| {
            let label = if item.stamp.is_empty() {
                format!("r{}  {}", item.revision, item.preview)
            } else {
                format!("r{}  {}  {}", item.revision, item.stamp, item.preview)
            };
            row(&label, Command::RestoreRevision(item.revision))
        })
        .collect();
    if !revisions.is_empty() {
        // The close row dismisses the overlay only — `DismissPicker` never
        // reaches the navigation stack a real `Back` would pop (A-02). An
        // empty history shows the "No earlier revisions" empty state; a
        // permanently parked Close row would mask it (A-15).
        entries.push(row(s.text("Close", "关闭"), Command::DismissPicker));
    }
    entries
}

fn attachments(memo: &crate::model::MemoCard) -> Vec<MenuEntry> {
    memo.attachments
        .iter()
        .map(|path| row(path.as_str(), Command::OpenAttachment(path.clone())))
        .collect()
}

fn dates(model: &AppModel, s: &UiStrings) -> Vec<MenuEntry> {
    let mut rows: Vec<MenuEntry> = [
        (s.text("Today", "今天"), "today"),
        (s.text("Yesterday", "昨天"), "yesterday"),
        (s.text("This week", "本周"), "week"),
        (s.text("This month", "本月"), "month"),
    ]
    .into_iter()
    .map(|(label, preset)| row(label, Command::SetDate(preset.to_owned())))
    .collect();
    rows.push(row(
        s.text("Custom date range", "自定义日期范围"),
        Command::CustomDate,
    ));
    // "All dates" removes the date filter — refused (greyed, reason named)
    // when no date filter is set rather than silently re-querying.
    let mut clear = row(s.text("All dates", "全部日期"), Command::RemoveDate);
    clear.availability = Command::RemoveDate.availability(model);
    rows.push(clear);
    rows
}
