package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.MemoVersion
import com.lomo.domain.repository.GitSyncVersionHistoryRepository
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class GitSyncVersionHistoryRepositoryImpl
    @Inject
    constructor(
        private val runtime: GitSyncRepositoryContext,
        private val support: GitSyncRepositorySupport,
    ) : GitSyncVersionHistoryRepository {
        override suspend fun getMemoVersionHistory(
            dateKey: String,
            memoTimestamp: Long,
        ): List<MemoVersion> {
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            val rootDir = resolveVersionHistoryRootDir(layout)
            return if (rootDir == null) {
                emptyList()
            } else {
                val filename = buildMemoVersionFilename(layout, dateKey)
                val history =
                    support.runGitIo {
                        runtime.gitSyncQueryCoordinator.getFileHistory(rootDir, filename)
                    }
                val distinctVersions =
                    history
                        .mapNotNull { entry ->
                            runtime.markdownParser
                                .parseContent(entry.fileContent, dateKey)
                                .firstOrNull { memo ->
                                    abs(memo.timestamp - memoTimestamp) <= TIMESTAMP_TOLERANCE_MS
                                }?.let { memo ->
                                    MemoVersion(
                                        commitHash = entry.commitHash,
                                        commitTime = entry.commitTime,
                                        commitMessage = entry.commitMessage,
                                        memoContent = memo.content,
                                        isCurrent = false,
                                    )
                                }
                        }.distinctBy(MemoVersion::memoContent)
                toCurrentAwareVersions(
                    versions = distinctVersions,
                    rootDir = rootDir,
                    layout = layout,
                    dateKey = dateKey,
                    memoTimestamp = memoTimestamp,
                )
            }
        }

        private suspend fun resolveVersionHistoryRootDir(layout: SyncDirectoryLayout): File? {
            val directRootDir = support.resolveRootDir()
            val safRootUri = support.resolveSafRootUri()
            return when {
                directRootDir != null -> support.resolveGitRepoDir(directRootDir, layout)
                safRootUri.isNullOrBlank() -> null
                !layout.allSameDirectory -> support.resolveGitRepoDirForUri(safRootUri)
                else ->
                    runNonFatalCatching {
                        support.prepareSafMirror(safRootUri)
                    }.getOrElse { error ->
                        Timber.w(error, "Failed to resolve SAF mirror for version history")
                        null
                    }
            }
        }

        private fun buildMemoVersionFilename(
            layout: SyncDirectoryLayout,
            dateKey: String,
        ): String = if (layout.allSameDirectory) "$dateKey.md" else "${layout.memoFolder}/$dateKey.md"

        private fun toCurrentAwareVersions(
            versions: List<MemoVersion>,
            rootDir: File,
            layout: SyncDirectoryLayout,
            dateKey: String,
            memoTimestamp: Long,
        ): List<MemoVersion> {
            if (versions.isEmpty()) {
                return emptyList()
            }

            val currentMemoContent = readCurrentMemoContent(rootDir, layout, dateKey, memoTimestamp)
            return if (currentMemoContent.isNullOrBlank()) {
                versions.mapIndexed { index, version -> version.copy(isCurrent = index == 0) }
            } else {
                versions.map { version ->
                    version.copy(isCurrent = version.memoContent == currentMemoContent)
                }
            }
        }

        private fun readCurrentMemoContent(
            rootDir: File,
            layout: SyncDirectoryLayout,
            dateKey: String,
            memoTimestamp: Long,
        ): String? =
            runCatching {
                val memoFileInRepo = File(rootDir, buildMemoVersionFilename(layout, dateKey))
                if (!memoFileInRepo.exists()) {
                    null
                } else {
                    runtime.markdownParser
                        .parseFile(memoFileInRepo)
                        .firstOrNull { memo ->
                            abs(memo.timestamp - memoTimestamp) <= TIMESTAMP_TOLERANCE_MS
                        }?.content
                }
            }.getOrNull()
    }
