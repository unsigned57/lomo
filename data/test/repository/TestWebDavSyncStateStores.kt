package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: test-source WebDAV sync-state collaborators.
 * - Owning layer: data test boundary for WebDAV sync-state orchestration.
 * - Priority tier: P0 support.
 * - Capability: provide explicit test-only WebDAV collaborators after production fallback
 *   implementations are removed.
 *
 * Scenarios:
 * - Given a test needs disabled incremental-journal behavior, when it constructs a WebDAV sync component,
 *   then the disabled collaborator is supplied from test source explicitly.
 * - Given a test needs local fingerprint reuse, when it exercises WebDAV file bridge behavior, then the
 *   in-memory collaborator records and returns observable state.
 * - Given a test needs to ignore mutation recording, when it constructs a memo mutation collaborator, then
 *   the no-op recorder is explicit test source and cannot be imported by production.
 *
 * Observable outcomes:
 * - Production source cannot load the removed fallback class names, while tests compile only when they pass
 *   WebDAV sync-state dependencies explicitly.
 *
 * TDD proof:
 * - RED command: `./kotlin test --include-classes='com.lomo.data.repository.SyncStateGenerationIsolationTest'`.
 * - RED symptom: production `WebDavSyncExecutor` and `WebDavSyncFileBridge` exposed default constructors and
 *   production disabled/in-memory WebDAV state collaborators before this test-only boundary existed.
 *
 * Excludes:
 * - Room DAO behavior, remote transport behavior, and production DI bindings.
 */

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object TestDisabledWebDavLocalChangeJournalStore : WebDavLocalChangeJournalStore {
    override val incrementalSyncEnabled: Boolean = false

    override suspend fun read(): Map<String, WebDavLocalChangeJournalEntry> = emptyMap()

    override suspend fun upsert(entry: WebDavLocalChangeJournalEntry) = Unit

    override suspend fun remove(ids: Collection<String>) = Unit

    override suspend fun clear() = Unit
}

internal class TestInMemoryWebDavLocalFingerprintCache : WebDavLocalFingerprintCache {
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, TestWebDavLocalFingerprintEntry>()

    override suspend fun get(key: WebDavLocalFingerprintKey): String? =
        mutex.withLock {
            entries[key.path]
                ?.takeIf { entry ->
                    entry.lastModified == key.lastModified &&
                        entry.size == key.size
                }?.fingerprint
        }

    override suspend fun put(
        key: WebDavLocalFingerprintKey,
        fingerprint: String,
    ) {
        mutex.withLock {
            entries[key.path] =
                TestWebDavLocalFingerprintEntry(
                    lastModified = key.lastModified,
                    size = key.size,
                    fingerprint = fingerprint,
                )
        }
    }

    override suspend fun retain(validKeys: Set<WebDavLocalFingerprintKey>) {
        val validPaths = validKeys.mapTo(linkedSetOf(), WebDavLocalFingerprintKey::path)
        mutex.withLock {
            entries.keys.retainAll(validPaths)
        }
    }
}

internal object TestNoOpWebDavLocalChangeRecorder : WebDavLocalChangeRecorder {
    override suspend fun recordMemoUpsert(filename: String) = Unit

    override suspend fun recordMemoDelete(filename: String) = Unit

    override suspend fun recordImageUpsert(filename: String) = Unit

    override suspend fun recordImageDelete(filename: String) = Unit

    override suspend fun recordVoiceUpsert(filename: String) = Unit

    override suspend fun recordVoiceDelete(filename: String) = Unit
}

private data class TestWebDavLocalFingerprintEntry(
    val lastModified: Long,
    val size: Long?,
    val fingerprint: String,
)
