//! One grouped command palette and its secondary pickers share one action catalogue.
use crate::event::Command;
use crate::i18n::UiStrings;
use crate::model::{
    AppModel, FeedKind, FeedQuery, PaletteItem, PaletteScope, Picker, PickerKind, Screen, View,
};

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
    /// The direct key that runs the same command while browsing, shown beside the label.
    pub key: Option<&'static str>,
}

/// A rendered picker row: group headers are not selectable.
#[derive(Clone, Debug)]
pub enum MenuRow {
    Header(String),
    Entry(MenuEntry),
}

/// Selectable rows in selection order.
#[must_use]
pub fn entries(model: &AppModel, picker: &Picker) -> Vec<MenuEntry> {
    let strings = UiStrings::detect();
    let mut rows = match &picker.kind {
        PickerKind::Palette { item, scope } => palette(model, item, *scope, strings),
        PickerKind::Tags(scope) => tags(&model.tags, *scope, strings),
        PickerKind::Dates => dates(strings),
        PickerKind::Attachments(memo) => attachments(memo),
        PickerKind::History { revisions, .. } => history(revisions, strings),
    };
    let query = picker.text.text().to_lowercase();
    rows.retain(|row| row.label.to_lowercase().contains(&query));
    if picker.kind == PickerKind::Dates && !query.is_empty() {
        rows.push(row(
            strings.text("Use typed date or range", "使用输入的日期或范围"),
            Command::SetDate(picker.text.text().to_owned()),
        ));
    }
    rows
}

/// Rows as drawn: the palette inserts a header before each group that survived filtering.
#[must_use]
pub fn rows(model: &AppModel, picker: &Picker) -> Vec<MenuRow> {
    let strings = UiStrings::detect();
    let grouped = matches!(picker.kind, PickerKind::Palette { .. });
    let mut out = Vec::new();
    let mut current = None;
    for entry in entries(model, picker) {
        if grouped && current != Some(entry.group) {
            current = Some(entry.group);
            out.push(MenuRow::Header(
                group_title(entry.group, strings).to_owned(),
            ));
        }
        out.push(MenuRow::Entry(entry));
    }
    out
}

/// Display row of the selected entry.
#[must_use]
pub fn selected_row(rows: &[MenuRow], selected: usize) -> usize {
    rows.iter()
        .enumerate()
        .filter(|(_, row)| matches!(row, MenuRow::Entry(_)))
        .nth(selected)
        .map_or(0, |(index, _)| index)
}

/// Entry index of a display row, if it is selectable.
#[must_use]
pub fn entry_at(rows: &[MenuRow], row: usize) -> Option<usize> {
    if !matches!(rows.get(row), Some(MenuRow::Entry(_))) {
        return None;
    }
    Some(
        rows.iter()
            .take(row)
            .filter(|row| matches!(row, MenuRow::Entry(_)))
            .count(),
    )
}

const fn group_title(group: PaletteGroup, s: &UiStrings) -> &'static str {
    match group {
        PaletteGroup::Item => s.text("This item", "当前项"),
        PaletteGroup::Filters => s.text("Filters", "筛选"),
        PaletteGroup::Pages => s.text("Pages", "页面"),
        PaletteGroup::Global => s.text("Everything else", "全局"),
    }
}

fn row(label: &str, command: Command) -> MenuEntry {
    MenuEntry {
        label: label.to_owned(),
        command,
        group: PaletteGroup::Global,
        key: None,
    }
}

fn keyed(
    label: &str,
    command: Command,
    group: PaletteGroup,
    key: Option<&'static str>,
) -> MenuEntry {
    MenuEntry {
        label: label.to_owned(),
        command,
        group,
        key,
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
        PaletteItem::Memo(memo) => memo_actions(memo, s),
        PaletteItem::Task(task) => task_actions(task, s),
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
            keyed(
                s.screen_title(screen),
                Command::Goto(screen),
                PaletteGroup::Pages,
                None,
            )
        }),
    );
    rows.extend(global_actions(model, s));
    rows
}

fn memo_actions(memo: &crate::model::MemoCard, s: &UiStrings) -> Vec<MenuEntry> {
    let g = PaletteGroup::Item;
    if memo.trashed {
        return vec![
            keyed(
                s.text("Read", "阅读全文"),
                Command::Accept,
                g,
                Some("Enter"),
            ),
            keyed(s.text("Restore", "恢复记录"), Command::Restore, g, None),
            keyed(
                s.text("Delete permanently", "永久删除"),
                Command::DeleteForever,
                g,
                Some("d"),
            ),
            keyed(
                s.text("Empty trash", "清空回收站"),
                Command::EmptyTrash,
                g,
                None,
            ),
        ];
    }
    vec![
        keyed(
            s.text("Read", "阅读全文"),
            Command::Accept,
            g,
            Some("Enter"),
        ),
        keyed(
            s.text("Edit externally", "使用外部编辑器编辑"),
            Command::ExternalEdit,
            g,
            Some("e"),
        ),
        keyed(s.text("Toggle pin", "切换置顶"), Command::Pin, g, Some("m")),
        keyed(s.text("Attachments", "附件"), Command::Attachments, g, None),
        keyed(s.text("History", "版本历史"), Command::History, g, None),
        keyed(
            s.text("Move to trash", "移入回收站"),
            Command::Delete,
            g,
            Some("d"),
        ),
    ]
}

fn task_actions(task: &crate::model::TaskRow, s: &UiStrings) -> Vec<MenuEntry> {
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
            Some("Enter"),
        ),
        keyed(
            s.text("Open memo", "打开所在记录"),
            Command::OpenMemo(task.memo_id.clone()),
            g,
            None,
        ),
    ]
}

fn attachment_actions(item: &crate::model::AttachmentRow, s: &UiStrings) -> Vec<MenuEntry> {
    let g = PaletteGroup::Item;
    let mut rows = vec![keyed(
        s.text("Open with player", "用播放器打开"),
        Command::OpenAttachment(item.path.clone()),
        g,
        Some("Enter"),
    )];
    for owner in &item.owners {
        if let Ok(id) = lomo_workspace::MemoId::parse(owner) {
            rows.push(keyed(
                &format!("{} {owner}", s.text("Open memo", "打开记录")),
                Command::OpenMemo(id),
                g,
                None,
            ));
        }
    }
    rows
}

/// The timeline query the filter rows describe: the current feed, or the one under an auxiliary page.
fn timeline_query(model: &AppModel) -> Option<&FeedQuery> {
    std::iter::once(&model.view)
        .chain(model.history.iter().rev())
        .find_map(|view| match view {
            View::Feed(feed) if feed.kind == FeedKind::Timeline => Some(&feed.query),
            View::Feed(_)
            | View::Reader { .. }
            | View::Tasks(_)
            | View::Statistics(_)
            | View::Attachments(_)
            | View::Settings(_)
            | View::Loading(_)
            | View::Failed { .. } => None,
        })
}

fn filter_actions(model: &AppModel, s: &UiStrings) -> Vec<MenuEntry> {
    let g = PaletteGroup::Filters;
    let mut rows = vec![
        keyed(s.text("Search", "搜索"), Command::Search, g, Some("/")),
        keyed(
            s.text("Filter by tag", "按标签筛选"),
            Command::Tags,
            g,
            None,
        ),
        keyed(
            s.text("Filter by date", "按日期筛选"),
            Command::Date,
            g,
            None,
        ),
    ];
    let Some(query) = timeline_query(model) else {
        return rows;
    };
    if !query.text.is_empty() {
        rows.push(keyed(
            s.text("Toggle fulltext / fuzzy search", "切换全文／模糊搜索"),
            Command::ToggleSearchMode,
            g,
            None,
        ));
        rows.push(keyed(
            s.text("Remove keyword filter", "移除关键词条件"),
            Command::RemoveKeyword,
            g,
            None,
        ));
    }
    if query.date_label.is_some() {
        rows.push(keyed(
            s.text("Remove date filter", "移除日期条件"),
            Command::RemoveDate,
            g,
            None,
        ));
    }
    if query.is_filtered() {
        rows.push(keyed(
            s.text("Clear filters", "清空筛选"),
            Command::ClearFilters,
            g,
            None,
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
            Some("n"),
        ),
        keyed(
            s.text("Import clipboard image", "导入剪贴板图片"),
            Command::ImportClipboard,
            g,
            None,
        ),
    ];
    if model.last_created.is_some() {
        rows.push(keyed(
            s.text("View last saved memo", "查看刚保存的记录"),
            Command::ShowCreated,
            g,
            None,
        ));
    }
    if model.notice.is_some() {
        rows.push(keyed(
            s.text("Last notification", "查看最近提示"),
            Command::ShowNotice,
            g,
            None,
        ));
    }
    rows.push(keyed(
        s.text("Refresh workspace", "刷新工作区"),
        Command::Refresh,
        g,
        Some("F5"),
    ));
    if !model.draft.text.text().trim().is_empty() {
        rows.push(keyed(
            s.text("Discard capture draft", "丢弃速记草稿"),
            Command::DiscardDraft,
            g,
            None,
        ));
    }
    rows.push(keyed(s.text("Help", "帮助"), Command::Help, g, Some("?")));
    rows.push(keyed(s.text("Quit", "退出"), Command::Quit, g, Some("q")));
    rows
}

fn tags(
    dictionary: &[String],
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
    rows.extend(
        tag_names(dictionary)
            .into_iter()
            .map(|tag| row(&format!("#{tag}"), Command::SelectTag(Some(tag)))),
    );
    rows
}

#[must_use]
pub fn tag_names(dictionary: &[String]) -> Vec<String> {
    let mut names = std::collections::BTreeSet::new();
    for tag in dictionary {
        names.insert(tag.clone());
        for (index, _) in tag.match_indices('/') {
            if let Some(parent) = tag.get(..index).map(|parent| parent.trim_end_matches('/'))
                && !parent.is_empty()
            {
                names.insert(parent.to_owned());
            }
        }
    }
    names.into_iter().collect()
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
    revisions
        .iter()
        .map(|item| {
            let label = if item.stamp.is_empty() {
                format!("r{}  {}", item.revision, item.preview)
            } else {
                format!("r{}  {}  {}", item.revision, item.stamp, item.preview)
            };
            row(&label, Command::RestoreRevision(item.revision))
        })
        .chain(std::iter::once(row(s.text("Close", "关闭"), Command::Back)))
        .collect()
}

fn attachments(memo: &crate::model::MemoCard) -> Vec<MenuEntry> {
    memo.attachments
        .iter()
        .map(|path| row(path.as_str(), Command::OpenAttachment(path.clone())))
        .collect()
}

fn dates(s: &UiStrings) -> Vec<MenuEntry> {
    vec![
        row(
            s.text("Today", "今天"),
            Command::SetDate("today".to_owned()),
        ),
        row(
            s.text("Yesterday", "昨天"),
            Command::SetDate("yesterday".to_owned()),
        ),
        row(
            s.text("This week", "本周"),
            Command::SetDate("week".to_owned()),
        ),
        row(
            s.text("This month", "本月"),
            Command::SetDate("month".to_owned()),
        ),
        row(
            s.text("Custom date range", "自定义日期范围"),
            Command::CustomDate,
        ),
        row(s.text("All dates", "全部日期"), Command::RemoveDate),
    ]
}
