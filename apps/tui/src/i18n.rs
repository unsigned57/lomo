//! Locale-detected chrome strings. Search remains a filter label, never an editor.

use std::env;

use crate::model::Screen;

/// UI language detected from the process locale.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum UiLanguage {
    English,
    ChineseSimplified,
}

/// Owned labels for one language.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UiStrings {
    pub language: UiLanguage,
    pub title_timeline: String,
    pub title_search: String,
    pub title_todo: String,
    pub title_review: String,
    pub title_stats: String,
    pub title_attachments: String,
    pub title_trash: String,
    pub title_settings: String,
    pub title_preview: String,
    pub title_nav: String,
    pub title_commands: String,
    pub title_help: String,
    pub title_overdue: String,
    pub title_history: String,
    pub search_input: String,
    pub search_fulltext: String,
    pub search_fuzzy: String,
    pub hint_keys: String,
    pub hint_toggle_todo: String,
    pub hint_empty: String,
    pub header_stats: String,
    pub header_cycle: String,
    pub header_heatmap: String,
    pub header_flashback: String,
    pub label_total_notes: String,
    pub label_total_words: String,
    pub label_active_days: String,
    pub label_streak: String,
    pub label_days: String,
    pub hint_no_flashback: String,
    pub heatmap_less: String,
    pub heatmap_more: String,
    pub heatmap_weekdays: [&'static str; 7],
    pub heatmap_months: [&'static str; 12],
    pub confirm_delete_title: String,
    pub confirm_restore_title: String,
    pub confirm_delete_query: String,
    pub confirm_restore_query: String,
    pub confirm_yes: String,
    pub confirm_no: String,
    pub nav_labels: [&'static str; 7],
    pub palette_labels: [&'static str; 11],
    pub help_lines: [(&'static str, &'static str); 16],
}

impl UiStrings {
    /// Reads `LOMO_LANG`, then `LC_ALL` / `LC_MESSAGES` / `LANG`.
    #[must_use]
    pub fn detect() -> Self {
        Self::for_language(detect_language())
    }

    #[must_use]
    pub fn for_language(language: UiLanguage) -> Self {
        match language {
            UiLanguage::English => Self::english(),
            UiLanguage::ChineseSimplified => Self::chinese(),
        }
    }

    #[must_use]
    pub const fn screen_title(&self, screen: Screen) -> &str {
        match screen {
            Screen::Timeline => self.title_timeline.as_str(),
            Screen::Tasks => self.title_todo.as_str(),
            Screen::Review => self.title_review.as_str(),
            Screen::Statistics => self.title_stats.as_str(),
            Screen::Attachments => self.title_attachments.as_str(),
            Screen::Trash => self.title_trash.as_str(),
            Screen::Settings => self.title_settings.as_str(),
        }
    }

    #[must_use]
    pub fn list_title(&self, screen: Screen, count: usize, status: &str) -> String {
        let suffix = if status.is_empty() {
            String::new()
        } else {
            format!(" | {status}")
        };
        let chinese = self.language == UiLanguage::ChineseSimplified;
        match screen {
            Screen::Timeline if chinese => {
                format!(" [ {} | {count} 条{suffix} ] ", self.title_timeline)
            }
            Screen::Timeline => {
                format!(" [ {} | {count} entries{suffix} ] ", self.title_timeline)
            }
            Screen::Tasks => format!(" [ {} ({count}){suffix} ] ", self.title_todo),
            Screen::Review | Screen::Attachments | Screen::Trash => {
                format!(" [ {} | {count}{suffix} ] ", self.screen_title(screen))
            }
            Screen::Settings | Screen::Statistics => {
                format!(" [ {} ] ", self.screen_title(screen))
            }
        }
    }

    #[must_use]
    pub fn search_list_title(&self, count: usize, status: &str) -> String {
        let suffix = if status.is_empty() {
            String::new()
        } else {
            format!(" | {status}")
        };
        if self.language == UiLanguage::ChineseSimplified {
            format!(" [ {} | 命中 {count} 条{suffix} ] ", self.title_search)
        } else {
            format!(" [ {} | {count} hits{suffix} ] ", self.title_search)
        }
    }

    #[must_use]
    pub fn preview_title(&self, screen: Screen) -> String {
        format!(" [ {} ] {} ", self.screen_title(screen), self.title_preview)
    }

    fn english() -> Self {
        Self {
            language: UiLanguage::English,
            title_timeline: "Thought Stream".to_owned(),
            title_search: "Search".to_owned(),
            title_todo: "Todo".to_owned(),
            title_review: "Daily Review".to_owned(),
            title_stats: "Statistics".to_owned(),
            title_attachments: "Attachments".to_owned(),
            title_trash: "Trash".to_owned(),
            title_settings: "Settings".to_owned(),
            title_preview: "Preview".to_owned(),
            title_nav: "Navigate".to_owned(),
            title_commands: "Commands".to_owned(),
            title_help: " [ Keybindings ] ".to_owned(),
            title_overdue: " [ Overdue reminders ] ".to_owned(),
            title_history: " [ History ] ".to_owned(),
            search_input: " [ Search Input ] ".to_owned(),
            search_fulltext: "fulltext".to_owned(),
            search_fuzzy: "fuzzy".to_owned(),
            hint_keys: " j/k move  / search  n new  e edit  Ctrl+p commands  ? help  q quit "
                .to_owned(),
            hint_toggle_todo: " [ Space toggles state ] ".to_owned(),
            hint_empty: "Write something".to_owned(),
            header_stats: " [ Overview ] ".to_owned(),
            header_cycle: " [ Period Report ] ".to_owned(),
            header_heatmap: " [ Activity Heatmap ] ".to_owned(),
            header_flashback: " [ Flashback ] ".to_owned(),
            label_total_notes: "Total notes: ".to_owned(),
            label_total_words: "Total words: ".to_owned(),
            label_active_days: "Active days: ".to_owned(),
            label_streak: "Streak: ".to_owned(),
            label_days: "days".to_owned(),
            hint_no_flashback: "No historical notes yet — go write your first one!".to_owned(),
            heatmap_less: "Less ".to_owned(),
            heatmap_more: "More".to_owned(),
            heatmap_weekdays: ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"],
            heatmap_months: [
                "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
            ],
            confirm_delete_title: " [ Confirm Delete ] ".to_owned(),
            confirm_restore_title: " [ Confirm Restore ] ".to_owned(),
            confirm_delete_query: "Are you sure you want to delete this?".to_owned(),
            confirm_restore_query: "Restore the selected memo?".to_owned(),
            confirm_yes: "y confirm".to_owned(),
            confirm_no: "n cancel".to_owned(),
            nav_labels: [
                "Thought Stream",
                "Todo",
                "Daily Review",
                "Statistics",
                "Attachments",
                "Trash",
                "Settings",
            ],
            palette_labels: [
                "Thought Stream",
                "Todo",
                "Daily Review",
                "Statistics",
                "Attachments",
                "Trash",
                "Settings",
                "New memo",
                "Edit memo",
                "Toggle search mode",
                "Quit",
            ],
            help_lines: [
                ("?", "Show help"),
                ("q", "Quit"),
                ("Esc", "Cancel / back"),
                ("j / k", "Move up / down"),
                ("Tab", "Cycle focus"),
                ("/", "Search (not a body editor)"),
                ("Ctrl+f", "Fulltext / fuzzy"),
                ("n / e", "New / edit in $VISUAL"),
                ("Ctrl+p", "Command menu"),
                ("[", "Toggle navigation"),
                ("Space", "Toggle todo"),
                ("d / r", "Delete / restore"),
                ("h", "Version history"),
                ("m", "Pin selected"),
                ("p", "Import clipboard image"),
                ("a", "Play attachment"),
            ],
        }
    }

    fn chinese() -> Self {
        Self {
            language: UiLanguage::ChineseSimplified,
            title_timeline: "思维流".to_owned(),
            title_search: "全局搜索".to_owned(),
            title_todo: "待办事项".to_owned(),
            title_review: "每日回顾".to_owned(),
            title_stats: "统计图表".to_owned(),
            title_attachments: "附件".to_owned(),
            title_trash: "回收站".to_owned(),
            title_settings: "设置".to_owned(),
            title_preview: "预览".to_owned(),
            title_nav: "导航".to_owned(),
            title_commands: "命令".to_owned(),
            title_help: " [ 键盘快捷键 ] ".to_owned(),
            title_overdue: " [ 逾期提醒 ] ".to_owned(),
            title_history: " [ 版本历史 ] ".to_owned(),
            search_input: " [ 关键词输入 ] ".to_owned(),
            search_fulltext: "全文".to_owned(),
            search_fuzzy: "模糊".to_owned(),
            hint_keys: " j/k 移动  / 搜索  n 新建  e 编辑  Ctrl+p 命令  ? 帮助  q 退出 ".to_owned(),
            hint_toggle_todo: " [ 空格切换状态 ] ".to_owned(),
            hint_empty: "写点什么吧".to_owned(),
            header_stats: " [ 数据概览 ] ".to_owned(),
            header_cycle: " [ 周期报表 ] ".to_owned(),
            header_heatmap: " [ 活跃度热力图 ] ".to_owned(),
            header_flashback: " [ 历史回顾 ] ".to_owned(),
            label_total_notes: "总笔记数: ".to_owned(),
            label_total_words: "总字数:   ".to_owned(),
            label_active_days: "活跃天数: ".to_owned(),
            label_streak: "连续天数: ".to_owned(),
            label_days: "天".to_owned(),
            hint_no_flashback: "暂无历史笔记，快去写下第一条吧！".to_owned(),
            heatmap_less: "Less ".to_owned(),
            heatmap_more: "More".to_owned(),
            heatmap_weekdays: ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"],
            heatmap_months: [
                "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
            ],
            confirm_delete_title: " [ 确认删除 ] ".to_owned(),
            confirm_restore_title: " [ 确认恢复 ] ".to_owned(),
            confirm_delete_query: "是否确认删除？".to_owned(),
            confirm_restore_query: "是否恢复所选笔记？".to_owned(),
            confirm_yes: "y 确认".to_owned(),
            confirm_no: "n 取消".to_owned(),
            nav_labels: [
                "思维流",
                "待办事项",
                "每日回顾",
                "统计图表",
                "附件",
                "回收站",
                "设置",
            ],
            palette_labels: [
                "思维流",
                "待办事项",
                "每日回顾",
                "统计图表",
                "附件",
                "回收站",
                "设置",
                "新建笔记",
                "编辑笔记",
                "切换检索模式",
                "退出",
            ],
            help_lines: [
                ("?", "显示帮助"),
                ("q", "退出程序"),
                ("Esc", "取消/返回"),
                ("j / k", "上下移动"),
                ("Tab", "切换焦点"),
                ("/", "全局搜索（不是正文编辑器）"),
                ("Ctrl+f", "全文 / 模糊"),
                ("n / e", "外部编辑器新建 / 编辑"),
                ("Ctrl+p", "命令菜单"),
                ("[", "展开导航"),
                ("Space", "切换待办"),
                ("d / r", "删除 / 恢复"),
                ("h", "版本历史"),
                ("m", "置顶"),
                ("p", "导入剪贴板图片"),
                ("a", "外部播放附件"),
            ],
        }
    }
}

fn detect_language() -> UiLanguage {
    for key in ["LOMO_LANG", "LC_ALL", "LC_MESSAGES", "LANG"] {
        if let Ok(value) = env::var(key)
            && !value.trim().is_empty()
        {
            return language_from_locale(&value);
        }
    }
    UiLanguage::English
}

fn language_from_locale(value: &str) -> UiLanguage {
    let normalized = value.trim().to_ascii_lowercase();
    if normalized.starts_with("zh") || normalized == "cn" || normalized.contains("chinese") {
        UiLanguage::ChineseSimplified
    } else {
        UiLanguage::English
    }
}
