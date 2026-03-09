package com.lomo.data.git

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface GitMediaSyncStateStore {
    suspend fun read(): Map<String, GitMediaSyncMetadataEntry>

    suspend fun write(entries: Collection<GitMediaSyncMetadataEntry>)
}

@Singleton
class FileGitMediaSyncStateStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : GitMediaSyncStateStore {
        private val json = Json { ignoreUnknownKeys = true }

        override suspend fun read(): Map<String, GitMediaSyncMetadataEntry> =
            withContext(Dispatchers.IO) {
                val file = stateFile()
                if (!file.exists()) return@withContext emptyMap()
                runCatching {
                    json
                        .decodeFromString<GitMediaSyncMetadataSnapshot>(file.readText())
                        .entries
                        .associateBy { it.relativePath }
                }.getOrDefault(emptyMap())
            }

        override suspend fun write(entries: Collection<GitMediaSyncMetadataEntry>) {
            withContext(Dispatchers.IO) {
                val file = stateFile()
                file.parentFile?.mkdirs()
                val payload = GitMediaSyncMetadataSnapshot(entries.sortedBy { it.relativePath })
                file.writeText(json.encodeToString(GitMediaSyncMetadataSnapshot.serializer(), payload))
            }
        }

        private fun stateFile(): File = File(context.filesDir, "git_media_sync_state.json")
    }

@Serializable
data class GitMediaSyncMetadataSnapshot(
    val entries: List<GitMediaSyncMetadataEntry> = emptyList(),
)

@Serializable
data class GitMediaSyncMetadataEntry(
    val relativePath: String,
    val repoLastModified: Long?,
    val localLastModified: Long?,
    val lastSyncedAt: Long,
    val lastResolvedDirection: String,
    val lastResolvedReason: String,
) {
    companion object {
        const val NONE = "NONE"
        const val UNCHANGED = "UNCHANGED"
    }
}
