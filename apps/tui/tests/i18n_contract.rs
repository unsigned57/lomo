//! Behavior Contract
//! Capability: English and Simplified Chinese cover the same reading and auxiliary flows.
//! Scenarios: seven page titles and current capture/reading help in both locales.
//! Observable outcomes: localized titles and shortcuts without obsolete focus instructions.
//! TDD proof: old pane-title assertions are superseded by the approved reading contract; behavior RED is in `reading_flow_contract`.
//! Excludes: mutating process-global locale state.

#[cfg(test)]
mod tests {
    use lomo_tui::{
        i18n::{UiLanguage, UiStrings},
        model::Screen,
    };

    #[test]
    fn both_locales_cover_all_pages_and_current_capture_help() {
        let en = UiStrings::for_language(UiLanguage::English);
        let zh = UiStrings::for_language(UiLanguage::ChineseSimplified);
        assert_eq!(en.screen_title(Screen::Timeline), "All memos");
        assert_eq!(zh.screen_title(Screen::Timeline), "全部记录");
        for screen in [
            Screen::Timeline,
            Screen::Tasks,
            Screen::Review,
            Screen::Statistics,
            Screen::Attachments,
            Screen::Trash,
            Screen::Settings,
        ] {
            assert!(!en.screen_title(screen).is_empty());
            assert!(!zh.screen_title(screen).is_empty());
            assert_ne!(en.screen_title(screen), zh.screen_title(screen));
        }
        for locale in [&en, &zh] {
            let help = lomo_tui::overlays::help(locale).join("\n");
            assert!(help.contains("Ctrl+S") && help.contains("Enter") && help.contains("Esc"));
            assert!(!help.contains("Cycle focus") && !help.contains("切换焦点"));
        }
    }
}
