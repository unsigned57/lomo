//! Behavior Contract
//! Capability: TUI chrome strings follow Think titles in English and zh-CN.
//! Scenarios: both languages format timeline/search/preview titles and nav labels.
//! Observable outcomes: formatted titles contain Think names, not generic `Nav`/`Timeline`.
//! TDD proof: chrome strings were Debug-printed English pane names.
//! Excludes: locale environment detection races.

#[cfg(test)]
mod tests {
    use lomo_tui::i18n::{UiLanguage, UiStrings};
    use lomo_tui::model::Screen;

    #[test]
    fn english_and_chinese_titles_match_think_chrome() {
        let en = UiStrings::for_language(UiLanguage::English);
        assert_eq!(en.screen_title(Screen::Timeline), "Thought Stream");
        assert!(
            en.list_title(Screen::Timeline, 3, "ready")
                .contains("Thought Stream")
        );
        assert!(en.search_list_title(2, "").contains("Search"));
        assert!(en.preview_title(Screen::Timeline).contains("Preview"));
        assert!(en.list_title(Screen::Tasks, 1, "").contains("Todo"));
        assert_eq!(en.nav_labels.first().copied(), Some("Thought Stream"));

        let zh = UiStrings::for_language(UiLanguage::ChineseSimplified);
        assert_eq!(zh.screen_title(Screen::Timeline), "思维流");
        assert!(zh.list_title(Screen::Timeline, 3, "").contains("思维流"));
        assert!(zh.search_list_title(2, "ok").contains("全局搜索"));
        assert!(zh.preview_title(Screen::Tasks).contains("预览"));
        assert!(zh.list_title(Screen::Review, 1, "").contains("每日回顾"));
        assert!(zh.list_title(Screen::Settings, 0, "").contains("设置"));
        assert_eq!(zh.nav_labels.get(1).copied(), Some("待办事项"));
        let detected = UiStrings::detect();
        assert!(!detected.title_timeline.is_empty());
    }
}
