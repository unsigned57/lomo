package com.lomo.app.benchmark

object BenchmarkSetupContract {
    const val ACTION_PREPARE = "com.lomo.app.action.BENCHMARK_PREPARE"
    const val EXTRA_ROOT_PATH = "extra_root_path"
    const val EXTRA_SEED_COUNT = "extra_seed_count"
    const val EXTRA_RESET = "extra_reset"

    const val DEFAULT_SEED_COUNT = 24
    const val DEFAULT_ROOT_DIR_NAME = "benchmark_memos"

    const val BENCHMARK_TAG_PATH = "benchmark_anchor_tag"
    const val BENCHMARK_TAG_MEMO_MARKER = "BPTAGTARGET"
    const val HISTORY_MEMO_OLD_MARKER = "BPHISTORY_OLD"
    const val HISTORY_MEMO_MIDDLE_MARKER = "BPHISTORY_MID"
    const val HISTORY_MEMO_CURRENT_MARKER = "BPHISTORY_CURRENT"
    const val DELETE_TARGET_MARKER = "BPDELETE_TARGET"

    const val RESULT_PREFIX = "prepared"
    const val RESULT_DELIMITER = ";"
    const val RESULT_KEY_ROOT_PATH = "root"
    const val RESULT_KEY_SEED_COUNT = "seed"
    const val RESULT_KEY_TAG_PATH = "tag"
    const val RESULT_KEY_HISTORY_MEMO_ID = "historyMemoId"
    const val RESULT_KEY_HISTORY_RESTORE_REVISION_ID = "historyRestoreRevisionId"
    const val RESULT_KEY_HISTORY_CURRENT_REVISION_ID = "historyCurrentRevisionId"
    const val RESULT_KEY_HISTORY_RESTORE_MARKER = "historyRestoreMarker"
    const val RESULT_KEY_HISTORY_CURRENT_MARKER = "historyCurrentMarker"
    const val RESULT_KEY_DELETE_TARGET_MEMO_ID = "deleteTargetMemoId"
    const val RESULT_KEY_DELETE_TARGET_MARKER = "deleteTargetMarker"
}
