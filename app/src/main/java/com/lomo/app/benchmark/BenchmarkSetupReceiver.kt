package com.lomo.app.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.app.BuildConfig
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class BenchmarkSetupReceiver : BroadcastReceiver() {
    @Inject lateinit var switchRootStorageUseCase: SwitchRootStorageUseCase
    @Inject lateinit var memoRepository: MemoRepository

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != BenchmarkSetupContract.ACTION_PREPARE) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
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
                        ).coerceIn(0, 200)
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
                seedMemos(seedCount)
                memoRepository.refreshMemos()

                pendingResult.setResultCode(Activity.RESULT_OK)
                pendingResult.setResultData("prepared:${rootDir.absolutePath}:seed=$seedCount")
            } catch (t: Throwable) {
                pendingResult.setResultCode(Activity.RESULT_CANCELED)
                pendingResult.setResultData("error:${t.message ?: t.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun isBenchmarkSetupEnabled(): Boolean =
        BuildConfig.DEBUG ||
            BuildConfig.BUILD_TYPE == "benchmark" ||
            BuildConfig.BUILD_TYPE == "nonMinifiedRelease"

    private suspend fun seedMemos(count: Int) {
        if (count <= 0) return

        val baseTime = System.currentTimeMillis() - count * 60_000L
        repeat(count) { index ->
            memoRepository.saveMemo(
                content =
                    buildString {
                        append("Benchmark memo #")
                        append(index + 1)
                        append("\n")
                        append("- [ ] perf-path-")
                        append(index % 5)
                        append("\n")
                        append("#tag")
                        append(index % 4)
                    },
                timestamp = baseTime + (index * 60_000L),
            )
        }
    }
}
