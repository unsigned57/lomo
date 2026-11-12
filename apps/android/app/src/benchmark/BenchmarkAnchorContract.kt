package com.lomo.app.benchmark

import java.util.Locale

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
    const val DAILY_REVIEW_ROOT = "benchmark_daily_review_root"
    const val GALLERY_ROOT = "benchmark_gallery_root"
    const val STATISTICS_ROOT = "benchmark_statistics_root"

    const val SETTINGS_SYNC_CENTER = "benchmark_settings_sync_center"
    const val SETTINGS_SYNC_BACKUP = "benchmark_settings_sync_backup"
    const val SETTINGS_INTERACTION_SECURITY = "benchmark_settings_interaction_security"
    const val SETTINGS_QUICK_BALL = "benchmark_settings_quick_ball"
    const val QUICK_BALL_ROOT = "benchmark_quick_ball_root"
    const val QUICK_BALL_PREVIEW = "benchmark_quick_ball_preview"
    const val QUICK_BALL_RESET = "benchmark_quick_ball_reset"
    const val QUICK_BALL_RESET_CONFIRM = "benchmark_quick_ball_reset_confirm"
    const val QUICK_BALL_RESET_DIALOG = "benchmark_quick_ball_reset_dialog"

    const val DRAWER_SETTINGS = "benchmark_drawer_settings"
    const val DRAWER_TRASH = "benchmark_drawer_trash"
    const val DRAWER_DAILY_REVIEW = "benchmark_drawer_daily_review"
    const val DRAWER_GALLERY = "benchmark_drawer_gallery"
    const val DRAWER_STATISTICS = "benchmark_drawer_statistics"

    const val INPUT_SHEET_ROOT = "benchmark_input_sheet_root"
    const val INPUT_BACKFILL_BADGE = "benchmark_input_backfill_badge"
    const val INPUT_REMINDER_BADGE = "benchmark_input_reminder_badge"
    const val INPUT_EDITOR = "benchmark_input_editor"
    const val INPUT_EDITOR_EXPAND = "benchmark_input_editor_expand"
    const val INPUT_SUBMIT = "benchmark_input_submit"

    const val FILTER_SHEET_ROOT = "benchmark_filter_sheet_root"
    const val SORT_OPTION_CREATED_TIME = "benchmark_sort_option_created_time"
    const val SORT_OPTION_UPDATED_TIME = "benchmark_sort_option_updated_time"
    const val SORT_SELECTED_CREATED_TIME = "benchmark_sort_selected_created_time"
    const val SORT_SELECTED_UPDATED_TIME = "benchmark_sort_selected_updated_time"

    const val MEMO_MENU_ROOT = "benchmark_memo_menu_root"
    const val MEMO_ACTION_PIN = "benchmark_memo_action_pin"
    const val MEMO_ACTION_COPY = "benchmark_memo_action_copy"
    const val MEMO_ACTION_SHARE = "benchmark_memo_action_share"
    const val MEMO_ACTION_LAN_SHARE = "benchmark_memo_action_lan_share"
    const val MEMO_ACTION_HISTORY = "benchmark_memo_action_history"
    const val MEMO_ACTION_EDIT = "benchmark_memo_action_edit"
    const val MEMO_ACTION_DELETE = "benchmark_memo_action_delete"
    const val TRASH_ACTION_RESTORE = "benchmark_trash_action_restore"
    const val TRASH_ACTION_DELETE_PERMANENTLY = "benchmark_trash_action_delete_permanently"

    const val VERSION_HISTORY_ROOT = "benchmark_version_history_root"

    // Reel/image-viewer surface (GalleryReelScreen covers both gallery paging and the
    // single-memo image viewer — one surface, one anchor).
    const val GALLERY_REEL_ROOT = "benchmark_gallery_reel_root"

    // LAN share page (ShareScreen on NavRoute.Share).
    const val LAN_SHARE_ROOT = "benchmark_lan_share_root"
    const val LAN_DEVICE_NAME_INPUT = "benchmark_lan_device_name_input"
    const val LAN_DEVICE_NAME_SAVE = "benchmark_lan_device_name_save"
    const val LAN_PEER_NAME = "benchmark_lan_peer_name"
    const val LAN_PAIRING_ROOT = "benchmark_lan_pairing_root"
    const val LAN_PAIRING_CODE = "benchmark_lan_pairing_code"
    const val LAN_PAIRING_CONFIRM = "benchmark_lan_pairing_confirm"
    const val LAN_BATCH_ROOT = "benchmark_lan_batch_root"
    const val LAN_BATCH_APPROVE = "benchmark_lan_batch_approve"
    const val LAN_BATCH_REJECT = "benchmark_lan_batch_reject"
    const val LAN_TRANSFER_SUCCESS = "benchmark_lan_transfer_success"
    const val LAN_TRANSFER_ERROR = "benchmark_lan_transfer_error"

    // Sync Center — the pre-existing a11y ids used by the committed uitest flows are
    // kept verbatim; the constants centralize them for the device benchmark harness.
    const val SYNC_CENTER_ROOT = "sync_center_root"
    const val SYNC_CENTER_TAB_CONFLICTS = "sync_center_tab_conflicts"

    // Sync conflict resolution dialog root — pinned for provider-backed runs even though
    // the SAF workspace benchmark cannot synthesize a deterministic conflict.
    const val SYNC_CONFLICT_ROOT = "benchmark_sync_conflict_root"

    fun drawerTag(path: String): String = "benchmark_drawer_tag_${sanitizeToken(path)}"

    fun galleryCell(memoId: String): String = "benchmark_gallery_cell_${sanitizeToken(memoId)}"

    fun memoCard(memoId: String, isPending: Boolean): String = if (isPending) {
        "benchmark_memo_pending_${sanitizeToken(memoId)}"
    } else {
        "benchmark_memo_card_${sanitizeToken(memoId)}"
    }

    fun memoMenu(memoId: String): String = "benchmark_memo_menu_${sanitizeToken(memoId)}"

    fun versionHistoryCard(revisionId: String): String = "benchmark_version_history_card_${sanitizeToken(revisionId)}"

    fun versionHistoryRestore(revisionId: String): String =
        "benchmark_version_history_restore_${sanitizeToken(revisionId)}"

    private fun sanitizeToken(raw: String): String {
        val lowered = raw.lowercase(Locale.ROOT)
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
