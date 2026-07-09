package com.lomo.app.benchmark

object BenchmarkAnchorContract {
    const val APP_ROOT = "benchmark_app_root"
    const val MAIN_ROOT = "benchmark_main_root"
    const val MAIN_DRAWER_BUTTON = "benchmark_main_drawer_button"
    const val MAIN_SEARCH_BUTTON = "benchmark_main_search_button"
    const val MAIN_FILTER_BUTTON = "benchmark_main_filter_button"
    const val MAIN_CREATE_FAB = "benchmark_main_create_fab"

    const val SETTINGS_ROOT = "benchmark_settings_root"
    const val SETTINGS_BACK_BUTTON = "benchmark_settings_back_button"

    const val SEARCH_ROOT = "benchmark_search_root"
    const val SEARCH_INPUT = "benchmark_search_input"
    const val SEARCH_CLEAR = "benchmark_search_clear"

    const val TAG_ROOT = "benchmark_tag_root"
    const val TRASH_ROOT = "benchmark_trash_root"

    const val DRAWER_SETTINGS = "benchmark_drawer_settings"
    const val DRAWER_TRASH = "benchmark_drawer_trash"

    const val INPUT_SHEET_ROOT = "benchmark_input_sheet_root"
    const val INPUT_EDITOR = "benchmark_input_editor"
    const val INPUT_SUBMIT = "benchmark_input_submit"

    const val FILTER_SHEET_ROOT = "benchmark_filter_sheet_root"
    const val SORT_OPTION_CREATED_TIME = "benchmark_sort_option_created_time"
    const val SORT_OPTION_UPDATED_TIME = "benchmark_sort_option_updated_time"
    const val SORT_SELECTED_CREATED_TIME = "benchmark_sort_selected_created_time"
    const val SORT_SELECTED_UPDATED_TIME = "benchmark_sort_selected_updated_time"

    const val MEMO_MENU_ROOT = "benchmark_memo_menu_root"
    const val MEMO_ACTION_HISTORY = "benchmark_memo_action_history"
    const val MEMO_ACTION_EDIT = "benchmark_memo_action_edit"
    const val MEMO_ACTION_DELETE = "benchmark_memo_action_delete"
    const val TRASH_ACTION_RESTORE = "benchmark_trash_action_restore"
    const val TRASH_ACTION_DELETE_PERMANENTLY = "benchmark_trash_action_delete_permanently"

    const val VERSION_HISTORY_ROOT = "benchmark_version_history_root"

    fun drawerTag(path: String): String = "benchmark_drawer_tag_${sanitizeToken(path)}"

    fun memoCard(memoId: String): String = "benchmark_memo_card_${sanitizeToken(memoId)}"

    fun memoMenu(memoId: String): String = "benchmark_memo_menu_${sanitizeToken(memoId)}"

    fun versionHistoryCard(revisionId: String): String =
        "benchmark_version_history_card_${sanitizeToken(revisionId)}"

    fun versionHistoryRestore(revisionId: String): String =
        "benchmark_version_history_restore_${sanitizeToken(revisionId)}"

    private fun sanitizeToken(raw: String): String {
        val lowered = raw.lowercase()
        val sanitized =
            buildString(lowered.length) {
                lowered.forEach { char ->
                    append(
                        when {
                            char.isLetterOrDigit() -> char
                            else -> '_'
                        },
                    )
                }
            }.trim('_')
                .replace(Regex("_+"), "_")
        return sanitized.ifBlank { "blank" }
    }
}
