package com.lomo.data.sync

import android.content.Context
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.repository.SyncConflictBackupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncConflictBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncConflictBackupRepository {
    private val backupRoot: File
        get() = File(context.filesDir, "sync_conflict_backups")

    override suspend fun backupFiles(
        files: List<SyncConflictFile>,
        localFileReader: suspend (String) -> ByteArray?,
    ) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sessionDir = File(backupRoot, timestamp)
        sessionDir.mkdirs()
        for (file in files) {
            val target = File(sessionDir, file.relativePath)
            target.parentFile?.mkdirs()
            val content = file.localContent?.toByteArray(Charsets.UTF_8)
                ?: localFileReader(file.relativePath)
            if (content != null) {
                target.writeBytes(content)
            }
        }
        cleanupOldBackups()
    }

    fun cleanupOldBackups(keepLast: Int = 5) {
        val dirs = backupRoot.listFiles()?.sortedByDescending { it.name } ?: return
        dirs.drop(keepLast).forEach { it.deleteRecursively() }
    }
}
