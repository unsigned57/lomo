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
    pub title_todo: String,
    pub title_review: String,
    pub title_stats: String,
    pub title_attachments: String,
    pub title_trash: String,
    pub title_settings: String,
    pub title_overdue: String,
    pub title_history: String,
    pub header_stats: String,
    pub header_cycle: String,
    pub header_heatmap: String,
    pub label_total_notes: String,
    pub label_total_words: String,
    pub label_active_days: String,
    pub label_streak: String,
    pub label_longest: String,
    pub heatmap_less: String,
    pub heatmap_more: String,
    pub heatmap_weekdays: [&'static str; 7],
    pub heatmap_months: [&'static str; 12],
}

impl UiStrings {
    #[must_use]
    pub const fn text<'a>(&self, english: &'a str, chinese: &'a str) -> &'a str {
        match self.language {
            UiLanguage::English => english,
            UiLanguage::ChineseSimplified => chinese,
        }
    }

    /// Reads `LOMO_LANG`, then `LC_ALL` / `LC_MESSAGES` / `LANG` — once per
    /// process. The language is process configuration, not per-frame input.
    #[must_use]
    pub fn detect() -> &'static Self {
        static STRINGS: std::sync::OnceLock<UiStrings> = std::sync::OnceLock::new();
        STRINGS.get_or_init(|| Self::for_language(detect_language()))
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

    fn english() -> Self {
        Self {
            language: UiLanguage::English,
            title_timeline: "All memos".to_owned(),
            title_todo: "Todo".to_owned(),
            title_review: "Daily Review".to_owned(),
            title_stats: "Statistics".to_owned(),
            title_attachments: "Attachments".to_owned(),
            title_trash: "Trash".to_owned(),
            title_settings: "Settings".to_owned(),
            title_overdue: "Overdue reminders".to_owned(),
            title_history: "History".to_owned(),
            header_stats: "Overview".to_owned(),
            header_cycle: "This period".to_owned(),
            header_heatmap: "Activity".to_owned(),
            label_total_notes: "Memos: ".to_owned(),
            label_total_words: "Words: ".to_owned(),
            label_active_days: "Active days: ".to_owned(),
            label_streak: "Streak: ".to_owned(),
            label_longest: "longest".to_owned(),
            heatmap_less: "Less ".to_owned(),
            heatmap_more: "More".to_owned(),
            heatmap_weekdays: ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"],
            heatmap_months: [
                "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
            ],
        }
    }

    fn chinese() -> Self {
        Self {
            language: UiLanguage::ChineseSimplified,
            title_timeline: "全部记录".to_owned(),
            title_todo: "待办事项".to_owned(),
            title_review: "每日回顾".to_owned(),
            title_stats: "统计图表".to_owned(),
            title_attachments: "附件".to_owned(),
            title_trash: "回收站".to_owned(),
            title_settings: "设置".to_owned(),
            title_overdue: "逾期提醒".to_owned(),
            title_history: "版本历史".to_owned(),
            header_stats: "数据概览".to_owned(),
            header_cycle: "本期".to_owned(),
            header_heatmap: "活跃度".to_owned(),
            label_total_notes: "记录数: ".to_owned(),
            label_total_words: "总字数: ".to_owned(),
            label_active_days: "活跃天数: ".to_owned(),
            label_streak: "连续天数: ".to_owned(),
            label_longest: "最长".to_owned(),
            heatmap_less: "少 ".to_owned(),
            heatmap_more: "多".to_owned(),
            heatmap_weekdays: ["日", "一", "二", "三", "四", "五", "六"],
            heatmap_months: [
                "1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月",
                "12月",
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
