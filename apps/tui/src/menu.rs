//! Searchable command and context menus share one action catalogue with keyboard dispatch.
use crate::event::Command;
use crate::i18n::UiStrings;
use crate::model::{Picker, PickerKind, Screen};

#[derive(Clone, Debug)]
pub struct MenuEntry {
    pub label: String,
    pub command: Command,
}
#[must_use]
pub fn entries(tag_dictionary: &[String], picker: &Picker) -> Vec<MenuEntry> {
    let strings = UiStrings::detect();
    let mut rows = match &picker.kind {
        PickerKind::Functions => functions(&strings),
        PickerKind::Actions(memo) => actions(memo, &strings),
        PickerKind::Tags(scope) => tags(tag_dictionary, *scope, &strings),
        PickerKind::Dates => dates(&strings),
        PickerKind::Attachments(memo) => attachments(memo),
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
fn row(label: &str, command: Command) -> MenuEntry {
    MenuEntry {
        label: label.to_owned(),
        command,
    }
}
fn functions(s: &UiStrings) -> Vec<MenuEntry> {
    let mut rows = [
        Screen::Timeline,
        Screen::Tasks,
        Screen::Review,
        Screen::Statistics,
        Screen::Attachments,
        Screen::Trash,
        Screen::Settings,
    ]
    .into_iter()
    .map(|screen| row(s.screen_title(screen), Command::Goto(screen)))
    .collect::<Vec<_>>();
    rows.extend([
        row(s.text("New memo", "新建记录"), Command::Compose),
        row(s.text("Search", "搜索"), Command::Search),
        row(s.text("Tags", "标签"), Command::Tags),
        row(s.text("Date", "日期"), Command::Date),
        row(
            s.text("Toggle fulltext / fuzzy search", "切换全文／模糊搜索"),
            Command::ToggleSearchMode,
        ),
        row(s.text("Clear filters", "清空筛选"), Command::ClearFilters),
        row(
            s.text("Remove keyword filter", "移除关键词条件"),
            Command::RemoveKeyword,
        ),
        row(
            s.text("Remove date filter", "移除日期条件"),
            Command::RemoveDate,
        ),
        row(
            s.text("Last notification", "查看最近提示"),
            Command::ShowNotice,
        ),
        row(
            s.text("Import clipboard image", "导入剪贴板图片"),
            Command::ImportClipboard,
        ),
        row(
            s.text("View last saved memo", "查看刚保存的记录"),
            Command::ShowCreated,
        ),
        row(s.text("Refresh workspace", "刷新工作区"), Command::Refresh),
        row(
            s.text("Discard capture draft", "丢弃速记草稿"),
            Command::DiscardDraft,
        ),
        row(s.text("Help", "帮助"), Command::Help),
        row(s.text("Quit", "退出"), Command::Quit),
    ]);
    rows
}
fn actions(memo: &crate::model::MemoCard, s: &UiStrings) -> Vec<MenuEntry> {
    if memo.trashed {
        return vec![row(s.text("Restore", "恢复记录"), Command::Restore)];
    }
    vec![
        row(s.text("Read", "阅读全文"), Command::Accept),
        row(
            s.text("Edit externally", "使用外部编辑器编辑"),
            Command::ExternalEdit,
        ),
        row(s.text("Toggle pin", "切换置顶"), Command::Pin),
        row(s.text("Attachments", "附件"), Command::Attachments),
        row(s.text("History", "版本历史"), Command::History),
        row(s.text("Move to trash", "移入回收站"), Command::Delete),
    ]
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
