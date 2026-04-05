package com.lomo.app.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.app.BuildConfig
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.InteractionPreferencesRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.SecurityPreferencesRepository
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class BenchmarkSetupReceiver : BroadcastReceiver() {
    @Inject lateinit var switchRootStorageUseCase: SwitchRootStorageUseCase

    @Inject lateinit var memoRepository: MemoRepository

    @Inject lateinit var memoVersionRepository: MemoVersionRepository

    @Inject lateinit var memoSnapshotPreferencesRepository: MemoSnapshotPreferencesRepository

    @Inject lateinit var securityPreferencesRepository: SecurityPreferencesRepository

    @Inject lateinit var interactionPreferencesRepository: InteractionPreferencesRepository

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != BenchmarkSetupContract.ACTION_PREPARE) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                if (!isBenchmarkSetupEnabled()) {
                    pendingResult.setResultCode(Activity.RESULT_CANCELED)
                    pendingResult.setResultData("benchmark setup disabled for build type ${BuildConfig.BUILD_TYPE}")
                    return@launch
                }

                val seedCount =
                    intent
                        .getIntExtra(
                            BenchmarkSetupContract.EXTRA_SEED_COUNT,
                            BenchmarkSetupContract.DEFAULT_SEED_COUNT,
                        ).coerceIn(MIN_SEED_COUNT, MAX_SEED_COUNT)
                val shouldReset = intent.getBooleanExtra(BenchmarkSetupContract.EXTRA_RESET, true)
                val rootPath =
                    intent.getStringExtra(BenchmarkSetupContract.EXTRA_ROOT_PATH).orEmpty().ifBlank {
                        File(context.filesDir, BenchmarkSetupContract.DEFAULT_ROOT_DIR_NAME).absolutePath
                    }

                val rootDir = File(rootPath)
                if (shouldReset && rootDir.exists()) {
                    rootDir.deleteRecursively()
                }
                rootDir.mkdirs()

                switchRootStorageUseCase.updateRootLocation(StorageLocation(rootDir.absolutePath))
                configureBenchmarkPrerequisites()
                memoVersionRepository.clearAllMemoSnapshots()
                val preparedState = seedMemos(seedCount)

                pendingResult.setResultCode(Activity.RESULT_OK)
                pendingResult.setResultData(
                    buildPreparedResult(
                        rootPath = rootDir.absolutePath,
                        preparedState = preparedState,
                    ),
                )
            }.onFailure { throwable ->
                pendingResult.setResultCode(Activity.RESULT_CANCELED)
                pendingResult.setResultData("error:${throwable.message ?: throwable.javaClass.simpleName}")
            }
            pendingResult.finish()
        }
    }

    private fun isBenchmarkSetupEnabled(): Boolean =
        BenchmarkBuildSupport.isEnabledBuildType(
            buildType = BuildConfig.BUILD_TYPE,
            isDebug = BuildConfig.DEBUG,
        )

    private suspend fun configureBenchmarkPrerequisites() {
        securityPreferencesRepository.setAppLockEnabled(false)
        securityPreferencesRepository.setCheckUpdatesOnStartup(false)
        interactionPreferencesRepository.setFreeTextCopyEnabled(false)
        memoSnapshotPreferencesRepository.setMemoSnapshotsEnabled(true)
        memoSnapshotPreferencesRepository.setMemoSnapshotMaxCount(BENCHMARK_SNAPSHOT_MAX_COUNT)
        memoSnapshotPreferencesRepository.setMemoSnapshotMaxAgeDays(BENCHMARK_SNAPSHOT_MAX_AGE_DAYS)
    }

    private suspend fun seedMemos(count: Int): PreparedBenchmarkState {
        val baseTime = System.currentTimeMillis() - (count + DEDICATED_MEMO_COUNT + 1) * MEMO_INTERVAL_MS
        repeat(count) { index ->
            memoRepository.saveMemo(
                content = buildGenericMemoContent(index),
                timestamp = baseTime + (index * MEMO_INTERVAL_MS),
            )
        }

        val tagMemoTimestamp = baseTime + (count * MEMO_INTERVAL_MS)
        memoRepository.saveMemo(
            content = buildTagTargetContent(),
            timestamp = tagMemoTimestamp,
        )

        val historyMemoTimestamp = tagMemoTimestamp + MEMO_INTERVAL_MS
        memoRepository.saveMemo(
            content = buildHistoryMemoContent(BenchmarkSetupContract.HISTORY_MEMO_OLD_MARKER),
            timestamp = historyMemoTimestamp,
        )
        val originalHistoryMemo = requireMemoWithMarker(BenchmarkSetupContract.HISTORY_MEMO_OLD_MARKER)
        memoRepository.updateMemo(
            memo = originalHistoryMemo,
            newContent = buildHistoryMemoContent(BenchmarkSetupContract.HISTORY_MEMO_MIDDLE_MARKER),
        )
        val middleHistoryMemo = requireMemoById(originalHistoryMemo.id)
        memoRepository.updateMemo(
            memo = middleHistoryMemo,
            newContent = buildHistoryMemoContent(BenchmarkSetupContract.HISTORY_MEMO_CURRENT_MARKER),
        )

        val deleteTargetTimestamp = historyMemoTimestamp + MEMO_INTERVAL_MS
        memoRepository.saveMemo(
            content = buildDeleteTargetContent(),
            timestamp = deleteTargetTimestamp,
        )

        memoRepository.refreshMemos()

        val preparedHistoryMemo = requireMemoById(originalHistoryMemo.id)
        val historyRevisions =
            memoVersionRepository
                .listMemoRevisions(
                    memo = preparedHistoryMemo,
                    cursor = null,
                    limit = HISTORY_REVISION_LIMIT,
                ).items
        val restoreRevisionId =
            historyRevisions
                .firstOrNull { revision ->
                    !revision.isCurrent &&
                        revision.memoContent.contains(BenchmarkSetupContract.HISTORY_MEMO_OLD_MARKER)
                }?.revisionId
                ?: error("Missing historical restore revision for benchmark history memo")
        val currentRevisionId =
            historyRevisions
                .firstOrNull { revision ->
                    revision.isCurrent &&
                        revision.memoContent.contains(BenchmarkSetupContract.HISTORY_MEMO_CURRENT_MARKER)
                }?.revisionId
                ?: error("Missing current revision for benchmark history memo")
        val deleteTargetMemo = requireMemoWithMarker(BenchmarkSetupContract.DELETE_TARGET_MARKER)

        return PreparedBenchmarkState(
            seedCount = count + DEDICATED_MEMO_COUNT,
            historyMemoId = preparedHistoryMemo.id,
            historyRestoreRevisionId = restoreRevisionId,
            historyCurrentRevisionId = currentRevisionId,
            deleteTargetMemoId = deleteTargetMemo.id,
        )
    }

    private suspend fun requireMemoById(memoId: String): Memo =
        memoRepository.getMemoById(memoId)
            ?: error("Benchmark setup memo not found: $memoId")

    private suspend fun requireMemoWithMarker(marker: String): Memo =
        memoRepository
            .getAllMemosList()
            .first()
            .firstOrNull { memo ->
                memo.content.contains(marker) || memo.rawContent.contains(marker)
            } ?: error("Benchmark setup memo marker not found: $marker")

    private companion object {
        const val MIN_SEED_COUNT = 0
        const val MAX_SEED_COUNT = 200
        const val MEMO_INTERVAL_MS = 60_000L
        const val PATH_VARIANT_COUNT = 5
        const val TAG_VARIANT_COUNT = 4
        const val DEDICATED_MEMO_COUNT = 3
        const val HISTORY_REVISION_LIMIT = 16
        const val BENCHMARK_SNAPSHOT_MAX_COUNT = 32
        const val BENCHMARK_SNAPSHOT_MAX_AGE_DAYS = 3650
    }
}

private fun buildPreparedResult(
    rootPath: String,
    preparedState: PreparedBenchmarkState,
): String =
    buildString {
        append(BenchmarkSetupContract.RESULT_PREFIX)
        append(BenchmarkSetupContract.RESULT_DELIMITER)
        appendKeyValue(BenchmarkSetupContract.RESULT_KEY_ROOT_PATH, rootPath)
        appendKeyValue(BenchmarkSetupContract.RESULT_KEY_SEED_COUNT, preparedState.seedCount.toString())
        appendKeyValue(BenchmarkSetupContract.RESULT_KEY_TAG_PATH, BenchmarkSetupContract.BENCHMARK_TAG_PATH)
        appendKeyValue(
            BenchmarkSetupContract.RESULT_KEY_HISTORY_MEMO_ID,
            preparedState.historyMemoId,
        )
        appendKeyValue(
            BenchmarkSetupContract.RESULT_KEY_HISTORY_RESTORE_REVISION_ID,
            preparedState.historyRestoreRevisionId,
        )
        appendKeyValue(
            BenchmarkSetupContract.RESULT_KEY_HISTORY_CURRENT_REVISION_ID,
            preparedState.historyCurrentRevisionId,
        )
        appendKeyValue(
            BenchmarkSetupContract.RESULT_KEY_HISTORY_RESTORE_MARKER,
            BenchmarkSetupContract.HISTORY_MEMO_OLD_MARKER,
        )
        appendKeyValue(
            BenchmarkSetupContract.RESULT_KEY_HISTORY_CURRENT_MARKER,
            BenchmarkSetupContract.HISTORY_MEMO_CURRENT_MARKER,
        )
        appendKeyValue(
            BenchmarkSetupContract.RESULT_KEY_DELETE_TARGET_MEMO_ID,
            preparedState.deleteTargetMemoId,
        )
        appendKeyValue(
            BenchmarkSetupContract.RESULT_KEY_DELETE_TARGET_MARKER,
            BenchmarkSetupContract.DELETE_TARGET_MARKER,
        )
    }

private fun StringBuilder.appendKeyValue(
    key: String,
    value: String,
) {
    append(key)
    append('=')
    append(value)
    append(BenchmarkSetupContract.RESULT_DELIMITER)
}

private fun buildGenericMemoContent(index: Int): String =
    buildString {
        append("Benchmark memo #")
        append(index + 1)
        append("\n")
        append("- [ ] perf-path-")
        append(index % GENERIC_PATH_VARIANT_COUNT)
        append("\n")
        append("#tag")
        append(index % GENERIC_TAG_VARIANT_COUNT)
    }

private fun buildTagTargetContent(): String =
    buildString {
        append(BenchmarkSetupContract.BENCHMARK_TAG_MEMO_MARKER)
        append("\n")
        append("Sidebar anchor tag target")
        append("\n")
        append('#')
        append(BenchmarkSetupContract.BENCHMARK_TAG_PATH)
    }

private fun buildHistoryMemoContent(marker: String): String =
    buildString {
        append(marker)
        append("\n")
        append("Baseline history target")
        append("\n")
        append("- [ ] revision-proof")
    }

private fun buildDeleteTargetContent(): String =
    buildString {
        append(BenchmarkSetupContract.DELETE_TARGET_MARKER)
        append("\n")
        append("Trash delete target")
        append("\n")
        append("- [ ] restore-path")
    }

private data class PreparedBenchmarkState(
    val seedCount: Int,
    val historyMemoId: String,
    val historyRestoreRevisionId: String,
    val historyCurrentRevisionId: String,
    val deleteTargetMemoId: String,
)

private const val GENERIC_PATH_VARIANT_COUNT = 5
private const val GENERIC_TAG_VARIANT_COUNT = 4
