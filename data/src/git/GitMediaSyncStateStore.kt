package com.lomo.data.git

import android.content.Context

import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File


interface GitMediaSyncStateStore {
    suspend fun read(): Map<String, GitMediaSyncMetadataEntry>

    suspend fun write(entries: Collection<GitMediaSyncMetadataEntry>)

    suspend fun clear()
}

interface RawGitMediaSyncStateStore {
    suspend fun readSnapshot(): GitMediaSyncMetadataSnapshot

    suspend fun writeSnapshot(snapshot: GitMediaSyncMetadataSnapshot)

    suspend fun clear()
}

class FileGitMediaSyncStateStore(
    private val context: Context,
) : RawGitMediaSyncStateStore {
        private val json = Json { ignoreUnknownKeys = true }

        override suspend fun readSnapshot(): GitMediaSyncMetadataSnapshot =
            withContext(Dispatchers.IO) {
                val file = stateFile()
                if (!file.exists()) return@withContext GitMediaSyncMetadataSnapshot()
                // behavior-contract: silent-result-ok: corrupt state file → empty map; next write rebuilds it
                runCatching {
                    json
                        .decodeFromString<GitMediaSyncMetadataSnapshot>(file.readText())
                }.getOrDefault(GitMediaSyncMetadataSnapshot())
            }

        override suspend fun writeSnapshot(snapshot: GitMediaSyncMetadataSnapshot) {
            withContext(Dispatchers.IO) {
                val file = stateFile()
                file.parentFile?.mkdirs()
                val payload = snapshot.copy(entries = snapshot.entries.sortedBy { it.relativePath })
                file.writeText(json.encodeToString(GitMediaSyncMetadataSnapshot.serializer(), payload))
            }
        }

        override suspend fun clear() {
            withContext(Dispatchers.IO) {
                stateFile().delete()
            }
        }

        private fun stateFile(): File = File(context.filesDir, "git_media_sync_state.json")
    }

class GitMediaSyncWorkspaceStateStore(
    private val rawStore: RawGitMediaSyncStateStore,
    private val generationProvider: WorkspaceSyncGenerationProvider,
) : GitMediaSyncStateStore {
        override suspend fun read(): Map<String, GitMediaSyncMetadataEntry> {
            val generation = activeGeneration()
            val snapshot = rawStore.readSnapshot()
            return if (snapshot.workspaceGeneration == generation) {
                snapshot.entries.associateBy { entry -> entry.relativePath }
            } else {
                emptyMap()
            }
        }

        override suspend fun write(entries: Collection<GitMediaSyncMetadataEntry>) {
            rawStore.writeSnapshot(
                GitMediaSyncMetadataSnapshot(
                    workspaceGeneration = activeGeneration(),
                    entries = entries.sortedBy { entry -> entry.relativePath },
                ),
            )
        }

        override suspend fun clear() {
            rawStore.clear()
        }

        private suspend fun activeGeneration(): String = generationProvider.activeGeneration().value
    }

@Serializable
data class GitMediaSyncMetadataSnapshot(
    val workspaceGeneration: String? = null,
    val entries: List<GitMediaSyncMetadataEntry> = emptyList(),
)

@Serializable
data class GitMediaSyncMetadataEntry(
    val relativePath: String,
    val repoLastModified: Long?,
    val localLastModified: Long?,
    val repoSize: Long? = null,
    val localSize: Long? = null,
    val repoFingerprint: String? = null,
    val localFingerprint: String? = null,
    val lastSyncedAt: Long,
    val lastResolvedDirection: String,
    val lastResolvedReason: String,
) {
    companion object {
        const val NONE = "NONE"
        const val UNCHANGED = "UNCHANGED"
    }
}
