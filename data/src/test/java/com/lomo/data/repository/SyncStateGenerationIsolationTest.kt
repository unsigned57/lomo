package com.lomo.data.repository

import android.content.Context
import com.lomo.data.git.GitMediaSyncMetadataEntry
import com.lomo.data.git.GitMediaSyncMetadataSnapshot
import com.lomo.data.git.GitMediaSyncWorkspaceStateStore
import com.lomo.data.git.RawGitMediaSyncStateStore
import com.lomo.data.local.dao.WebDavLocalChangeJournalDao
import com.lomo.data.local.dao.WebDavLocalFingerprintDao
import com.lomo.data.local.dao.S3LocalChangeJournalDao
import com.lomo.data.local.dao.RawS3SyncMetadataDao
import com.lomo.data.local.dao.RawWebDavSyncMetadataDao
import com.lomo.data.local.dao.PendingSyncConflictDao
import com.lomo.data.local.dao.PendingSyncReviewDao
import com.lomo.data.local.dao.S3RemoteIndexDao
import com.lomo.data.local.dao.S3RemoteShardScheduleTelemetrySnapshot
import com.lomo.data.local.dao.S3RemoteShardStateDao
import com.lomo.data.local.dao.S3SyncPlannerMetadataSnapshot
import com.lomo.data.local.dao.S3SyncProtocolStateDao
import com.lomo.data.local.dao.S3SyncRemoteMetadataSnapshot
import com.lomo.data.local.entity.S3LocalChangeJournalEntity
import com.lomo.data.local.entity.PendingSyncConflictEntity
import com.lomo.data.local.entity.PendingSyncReviewEntity
import com.lomo.data.local.entity.S3RemoteIndexEntity
import com.lomo.data.local.entity.S3RemoteShardStateEntity
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.local.entity.S3SyncProtocolStateEntity
import com.lomo.data.local.entity.WebDavLocalChangeJournalEntity
import com.lomo.data.local.entity.WebDavLocalFingerprintEntity
import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewSessionKind
import com.lomo.domain.repository.WorkspaceSyncGeneration
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: workspace-scoped sync state persistence stores.
 * - Owning layer: data persistence boundary backed by the domain workspace sync generation contract.
 * - Priority tier: P0.
 * - Capability: bind pending review/conflict and provider sync state to the active workspace generation.
 *
 * Scenarios:
 * - Given pending conflict/review rows from an old workspace generation, when the active generation changes,
 *   then reads for the same backend return no stale session.
 * - Given pending conflict/review rows for the active generation, when read by backend, then the sessions are
 *   preserved and old-generation rows remain hidden.
 * - Given WebDAV/S3 metadata rows from an old workspace generation, when the active generation changes,
 *   then provider metadata stores return only current-generation rows.
 * - Given WebDAV local journal and fingerprint rows from an old workspace generation, when the active
 *   generation changes, then incremental sync state reads return empty until current-generation state is written.
 * - Given S3 protocol, remote index, and remote shard state from an old workspace generation, when the active
 *   generation changes, then provider state reads return empty state until current-generation state is written.
 * - Given unscoped legacy S3 sidecar protocol/journal JSON files, when S3 state stores read, then those files are
 *   discarded as untrusted and no active-generation Room rows are created from them.
 * - Given current-generation Room-backed S3 protocol/journal state plus stale sidecar JSON files, when stores read,
 *   then current-generation Room state is preserved and sidecars are discarded.
 * - Given production S3 operation/status/conflict/review constructors, when their bytecode constructors are
 *   inspected, then no Kotlin default-argument constructor exists that can omit generation-scoped stores.
 * - Given production WebDAV sync-state classes, when their bytecode surface is inspected, then sync-state
 *   collaborators cannot be omitted and disabled/in-memory fallback implementations are not loadable.
 * - Given Git media sync metadata written under one workspace generation, when the active generation changes,
 *   then reads return empty state until current-generation metadata is written.
 *
 * Observable outcomes:
 * - Pending stores return null for stale-generation rows and round-trip current-generation sessions.
 * - WebDAV/S3 metadata, WebDAV local cache/journal, and S3 provider state stores read only active-generation rows.
 * - Legacy S3 sidecar files are deleted without being attributed to the active workspace generation.
 * - Production S3 sync constructors expose no generated default-argument constructor for sync-state stores.
 * - Production WebDAV sync-state classes expose no default-argument bypass and no loadable disabled/in-memory
 *   WebDAV state collaborator.
 * - Git media state reads return only the snapshot whose generation matches the active workspace generation.
 *
 * TDD proof:
 * - RED command: `./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.repository.SyncStateGenerationIsolationTest'`.
 * - RED symptom: test compilation fails because `WorkspaceSyncGenerationProvider`, scoped pending DAO APIs,
 *   and `GitMediaSyncWorkspaceStateStore` do not exist before the production generation contract is added.
 * - RED symptom for the S3 sidecar tail: legacy unscoped `s3_sync_protocol_state.json` and
 *   `s3_local_change_journal.json` are imported into the active generation or left on disk when current Room
 *   state exists.
 * - RED symptom for the production fallback tail: `:data:compileDebugUnitTestKotlin` fails after removing
 *   constructor defaults until every S3 production/test call site supplies generation-scoped stores explicitly.
 * - RED symptom for the WebDAV fallback tail: the WebDAV constructor/fallback inspection test reports generated
 *   default constructors plus loadable disabled/in-memory production fallback classes.
 *
 * Excludes:
 * - UI presentation, remote transport behavior, reset-hook cleanup ordering, and conflict resolution choices.
 *
 * Test Change Justification:
 * - Reason category: Data layer module gained app update install persistence, migration archive staging workspace, settings preference repos, and strengthened sync conflict store contracts.
 * - Old behavior/assertion being replaced: previous data layer tests relied on older repository contracts and store implementations before these modules were restructured.
 * - Why old assertion is no longer correct: new modules introduce typed credential reads, positional memo identities, and staged migration/restore plans that change observable data behavior.
 * - Coverage preserved by: all existing repository scenarios retained; new scenarios added for install persistence, staging workspace, preference repos, and conflict store contracts.
 * - Why this is not fitting the test to the implementation: tests verify observable repository store outcomes, not internal implementation details.
 */
class SyncStateGenerationIsolationTest : DataFunSpec() {
    init {
        test("given stale generation pending conflict when backend is read then stale session is hidden") {
            runTest {
                val generationProvider = FakeWorkspaceSyncGenerationProvider("workspace-current")
                val conflictDao = FakeGenerationPendingSyncConflictDao()
                val reviewDao = FakeGenerationPendingSyncReviewDao()
                val conflictStore = RoomPendingSyncConflictStore(conflictDao, generationProvider)
                val reviewStore = RoomPendingSyncReviewStore(reviewDao, generationProvider)

                generationProvider.current = WorkspaceSyncGeneration("workspace-old")
                conflictStore.write(conflictSession(SyncBackendType.S3, path = "old/conflict.md"))
                reviewStore.write(reviewSession(SyncBackendType.WEBDAV, path = "old/review.md"))
                generationProvider.current = WorkspaceSyncGeneration("workspace-current")

                conflictStore.readDescriptor(SyncBackendType.S3).shouldBeNull()
                reviewStore.readDescriptor(SyncBackendType.WEBDAV).shouldBeNull()
            }
        }

        test("given current generation pending state when read then current sessions are preserved") {
            runTest {
                val generationProvider = FakeWorkspaceSyncGenerationProvider("workspace-current")
                val conflictStore = RoomPendingSyncConflictStore(FakeGenerationPendingSyncConflictDao(), generationProvider)
                val reviewStore = RoomPendingSyncReviewStore(FakeGenerationPendingSyncReviewDao(), generationProvider)
                val conflict = conflictSession(SyncBackendType.S3, path = "current/conflict.md")
                val review = reviewSession(SyncBackendType.WEBDAV, path = "current/review.md")

                conflictStore.write(conflict)
                reviewStore.write(review)

                val conflictDescriptor = requireNotNull(conflictStore.readDescriptor(SyncBackendType.S3))
                val reviewDescriptor = requireNotNull(reviewStore.readDescriptor(SyncBackendType.WEBDAV))
                conflictDescriptor.source shouldBe conflict.source
                conflictDescriptor.files.single().relativePath shouldBe conflict.files.single().relativePath
                reviewDescriptor.source shouldBe review.source
                reviewDescriptor.items.single().relativePath shouldBe review.items.single().relativePath
            }
        }

        test("given stale generation git media state when active workspace changes then stale snapshot is hidden") {
            runTest {
                val rawStore = InMemoryRawGitMediaSyncStateStore()
                val generationProvider = FakeWorkspaceSyncGenerationProvider("workspace-old")
                val store = GitMediaSyncWorkspaceStateStore(rawStore, generationProvider)
                val oldEntry = gitEntry("images/old.jpg")

                store.write(listOf(oldEntry))
                generationProvider.current = WorkspaceSyncGeneration("workspace-current")

                store.read() shouldBe emptyMap()

                val currentEntry = gitEntry("images/current.jpg")
                store.write(listOf(currentEntry))

                store.read() shouldBe mapOf("images/current.jpg" to currentEntry)
            }
        }

        test("given stale generation WebDAV and S3 metadata when active workspace changes then stale metadata is hidden") {
            runTest {
                val generationProvider = FakeWorkspaceSyncGenerationProvider("workspace-old")
                val webDavStore =
                    RoomBackedWebDavSyncMetadataStore(
                        dao = InMemoryRawWebDavSyncMetadataDao(),
                        generationProvider = generationProvider,
                    )
                val s3Store =
                    RoomBackedS3SyncMetadataStore(
                        dao = InMemoryRawS3SyncMetadataDao(),
                        generationProvider = generationProvider,
                    )
                val oldWebDav = webDavMetadata("old/webdav.md")
                val oldS3 = s3Metadata("old/s3.md")

                webDavStore.upsertAll(listOf(oldWebDav))
                s3Store.upsertAll(listOf(oldS3))
                generationProvider.current = WorkspaceSyncGeneration("workspace-current")

                webDavStore.getAll() shouldBe emptyList()
                s3Store.getAll() shouldBe emptyList()

                val currentWebDav = webDavMetadata("current/webdav.md")
                val currentS3 = s3Metadata("current/s3.md")
                webDavStore.upsertAll(listOf(currentWebDav))
                s3Store.upsertAll(listOf(currentS3))

                webDavStore.getAll() shouldBe listOf(currentWebDav.copy(workspaceGeneration = "workspace-current"))
                s3Store.getAll() shouldBe listOf(currentS3.copy(workspaceGeneration = "workspace-current"))
            }
        }

        test("given stale generation WebDAV local journal and fingerprints when active workspace changes then stale cache state is hidden") {
            runTest {
                val generationProvider = FakeWorkspaceSyncGenerationProvider("workspace-old")
                val journalStore =
                    RoomBackedWebDavLocalChangeJournalStore(
                        dao = InMemoryWebDavLocalChangeJournalDao(),
                        generationProvider = generationProvider,
                    )
                val fingerprintCache =
                    RoomBackedWebDavLocalFingerprintCache(
                        dao = InMemoryWebDavLocalFingerprintDao(),
                        generationProvider = generationProvider,
                    )
                val oldJournal =
                    WebDavLocalChangeJournalEntry(
                        id = "MEMO:old.md",
                        kind = WebDavLocalChangeKind.MEMO,
                        filename = "old.md",
                        changeType = WebDavLocalChangeType.UPSERT,
                        updatedAt = 10L,
                    )
                val oldFingerprint =
                    WebDavLocalFingerprintKey(
                        path = "lomo/memos/old.md",
                        lastModified = 20L,
                        size = 30L,
                    )

                journalStore.upsert(oldJournal)
                fingerprintCache.put(oldFingerprint, "old-fingerprint")
                generationProvider.current = WorkspaceSyncGeneration("workspace-current")

                journalStore.read() shouldBe emptyMap()
                fingerprintCache.get(oldFingerprint).shouldBeNull()

                val currentJournal = oldJournal.copy(id = "MEMO:current.md", filename = "current.md")
                val currentFingerprint = oldFingerprint.copy(path = "lomo/memos/current.md")
                journalStore.upsert(currentJournal)
                fingerprintCache.put(currentFingerprint, "current-fingerprint")

                journalStore.read() shouldBe mapOf(currentJournal.id to currentJournal)
                fingerprintCache.get(currentFingerprint) shouldBe "current-fingerprint"
            }
        }

        test("given stale generation S3 provider state when active workspace changes then stale state is hidden") {
            runTest {
                val generationProvider = FakeWorkspaceSyncGenerationProvider("workspace-old")
                val protocolStore =
                    RoomBackedS3SyncProtocolStateStore(
                        dao = InMemoryS3SyncProtocolStateDao(),
                        context = testContext(),
                        generationProvider = generationProvider,
                    )
                val indexStore =
                    RoomBackedS3RemoteIndexStore(
                        dao = InMemoryS3RemoteIndexDao(),
                        generationProvider = generationProvider,
                    )
                val shardStore =
                    RoomBackedS3RemoteShardStateStore(
                        dao = InMemoryS3RemoteShardStateDao(),
                        generationProvider = generationProvider,
                    )
                val oldProtocol =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 10L,
                        indexedLocalFileCount = 1,
                        indexedRemoteFileCount = 1,
                        localModeFingerprint = "old-workspace",
                    )
                val oldIndex = s3RemoteIndex("old/index.md")
                val oldShard = s3RemoteShard("old-bucket")

                protocolStore.write(oldProtocol)
                indexStore.upsert(listOf(oldIndex))
                shardStore.upsert(listOf(oldShard))
                generationProvider.current = WorkspaceSyncGeneration("workspace-current")

                protocolStore.read() shouldBe null
                indexStore.readAllRelativePaths() shouldBe emptyList()
                shardStore.readAll() shouldBe emptyList()

                val currentProtocol =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 20L,
                        indexedLocalFileCount = 2,
                        indexedRemoteFileCount = 3,
                        localModeFingerprint = "current-workspace",
                    )
                val currentIndex = s3RemoteIndex("current/index.md")
                val currentShard = s3RemoteShard("current-bucket")
                protocolStore.write(currentProtocol)
                indexStore.upsert(listOf(currentIndex))
                shardStore.upsert(listOf(currentShard))

                protocolStore.read() shouldBe currentProtocol
                indexStore.readAllRelativePaths() shouldBe listOf("current/index.md")
                shardStore.readAll() shouldBe listOf(currentShard)
            }
        }

        test("given production WebDAV sync-state surface when inspected then fallback stores cannot be loaded or omitted") {
            val hiddenDefaultConstructors =
                hiddenDefaultConstructorsFor(
                    WebDavSyncOperationRepositoryImpl::class.java,
                    WebDavConflictResolver::class.java,
                    WebDavSyncExecutor::class.java,
                    WebDavSyncFileBridge::class.java,
                )
            val loadableFallbackClasses =
                WEB_DAV_PRODUCTION_FALLBACK_CLASS_NAMES
                    .filter { className -> runCatching { Class.forName(className) }.isSuccess }
            val attachmentCleanerDefaults =
                Class
                    .forName("com.lomo.data.repository.AttachmentOrphanCleanerKt")
                    .declaredMethods
                    .filter { method -> method.name == "deleteOrphanAttachments\$default" }
                    .map { method -> method.toString() }

            hiddenDefaultConstructors shouldBe emptyList()
            loadableFallbackClasses shouldBe emptyList()
            attachmentCleanerDefaults shouldBe emptyList()
        }

        test("given unscoped S3 sidecars when stores read then sidecars are discarded without active generation rows") {
            runTest {
                val filesDir = Files.createTempDirectory("sync-generation-sidecars").toFile()
                legacyProtocolSidecar(filesDir).writeText(legacyProtocolJson())
                legacyJournalSidecar(filesDir).writeText(legacyJournalJson())
                val generationProvider = FakeWorkspaceSyncGenerationProvider("workspace-current")
                val protocolDao = InMemoryS3SyncProtocolStateDao()
                val journalDao = InMemoryS3LocalChangeJournalDao()
                val protocolStore =
                    RoomBackedS3SyncProtocolStateStore(
                        dao = protocolDao,
                        context = testContext(filesDir),
                        generationProvider = generationProvider,
                    )
                val journalStore =
                    RoomBackedS3LocalChangeJournalStore(
                        dao = journalDao,
                        context = testContext(filesDir),
                        generationProvider = generationProvider,
                    )

                protocolStore.read().shouldBeNull()
                journalStore.read() shouldBe emptyMap()

                protocolDao.getById("workspace-current").shouldBeNull()
                journalDao.getAll("workspace-current") shouldBe emptyList()
                legacyProtocolSidecar(filesDir).exists() shouldBe false
                legacyJournalSidecar(filesDir).exists() shouldBe false
            }
        }

        test("given current S3 Room state and stale sidecars when read then current state is preserved") {
            runTest {
                val filesDir = Files.createTempDirectory("sync-generation-current-sidecars").toFile()
                legacyProtocolSidecar(filesDir).writeText(legacyProtocolJson())
                legacyJournalSidecar(filesDir).writeText(legacyJournalJson())
                val generationProvider = FakeWorkspaceSyncGenerationProvider("workspace-current")
                val protocolDao = InMemoryS3SyncProtocolStateDao()
                val journalDao = InMemoryS3LocalChangeJournalDao()
                val currentProtocol =
                    S3SyncProtocolState(
                        lastSuccessfulSyncAt = 20L,
                        indexedLocalFileCount = 2,
                        indexedRemoteFileCount = 3,
                        localModeFingerprint = "current-room",
                    )
                val currentJournal =
                    S3LocalChangeJournalEntry(
                        id = "MEMO:current.md",
                        kind = S3LocalChangeKind.MEMO,
                        filename = "current.md",
                        changeType = S3LocalChangeType.UPSERT,
                        updatedAt = 200L,
                    )
                protocolDao.upsert(s3ProtocolEntity("workspace-current", currentProtocol))
                journalDao.upsert(s3JournalEntity("workspace-current", currentJournal))
                val protocolStore =
                    RoomBackedS3SyncProtocolStateStore(
                        dao = protocolDao,
                        context = testContext(filesDir),
                        generationProvider = generationProvider,
                    )
                val journalStore =
                    RoomBackedS3LocalChangeJournalStore(
                        dao = journalDao,
                        context = testContext(filesDir),
                        generationProvider = generationProvider,
                    )

                protocolStore.read() shouldBe currentProtocol
                journalStore.read() shouldBe mapOf(currentJournal.id to currentJournal)

                legacyProtocolSidecar(filesDir).exists() shouldBe false
                legacyJournalSidecar(filesDir).exists() shouldBe false
            }
        }

        test("given production S3 sync constructors when inspected then sync-state stores cannot be omitted") {
            val hiddenDefaultConstructors =
                hiddenDefaultConstructorsFor(
                    S3SyncOperationRepositoryImpl::class.java,
                    S3SyncStatusTester::class.java,
                    S3ConflictResolver::class.java,
                    S3ReviewResolver::class.java,
                )

            hiddenDefaultConstructors shouldBe emptyList()
        }
    }
}

private const val DEFAULT_CONSTRUCTOR_MARKER = "kotlin.jvm.internal.DefaultConstructorMarker"

private val WEB_DAV_PRODUCTION_FALLBACK_CLASS_NAMES =
    listOf(
        "com.lomo.data.repository.DisabledWebDavLocalChangeJournalStore",
        "com.lomo.data.repository.DisabledWebDavLocalFingerprintCache",
        "com.lomo.data.repository.InMemoryWebDavLocalFingerprintCache",
        "com.lomo.data.repository.NoOpWebDavLocalChangeRecorder",
    )

private fun hiddenDefaultConstructorsFor(vararg types: Class<*>): List<String> =
    types.flatMap { type ->
        type.declaredConstructors
            .filter { constructor ->
                constructor.parameterTypes.any { parameter -> parameter.name == DEFAULT_CONSTRUCTOR_MARKER }
            }.map { constructor -> "${type.simpleName}${constructor.parameterTypes.toList()}" }
    }

private fun testContext(filesDir: File = Files.createTempDirectory("sync-generation-state").toFile()): Context {
    val context = mockk<Context>()
    every { context.filesDir } returns filesDir
    return context
}

private class FakeWorkspaceSyncGenerationProvider(initialGeneration: String) : WorkspaceSyncGenerationProvider {
    var current = WorkspaceSyncGeneration(initialGeneration)

    override suspend fun activeGeneration(): WorkspaceSyncGeneration = current
}

private class FakeGenerationPendingSyncConflictDao : PendingSyncConflictDao {
    private val entries = linkedMapOf<Pair<String, String>, PendingSyncConflictEntity>()

    override suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncConflictEntity? = entries[workspaceGeneration to backend]

    override suspend fun upsert(entity: PendingSyncConflictEntity) {
        entries[entity.workspaceGeneration to entity.backend] = entity
    }

    override suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    ) {
        entries.remove(workspaceGeneration to backend)
    }
}

private class FakeGenerationPendingSyncReviewDao : PendingSyncReviewDao {
    private val entries = linkedMapOf<Pair<String, String>, PendingSyncReviewEntity>()

    override suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncReviewEntity? = entries[workspaceGeneration to backend]

    override suspend fun upsert(entity: PendingSyncReviewEntity) {
        entries[entity.workspaceGeneration to entity.backend] = entity
    }

    override suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    ) {
        entries.remove(workspaceGeneration to backend)
    }
}

private class InMemoryRawGitMediaSyncStateStore : RawGitMediaSyncStateStore {
    var snapshot = GitMediaSyncMetadataSnapshot()

    override suspend fun readSnapshot(): GitMediaSyncMetadataSnapshot = snapshot

    override suspend fun writeSnapshot(snapshot: GitMediaSyncMetadataSnapshot) {
        this.snapshot = snapshot
    }

    override suspend fun clear() {
        snapshot = GitMediaSyncMetadataSnapshot()
    }
}

private class InMemoryRawWebDavSyncMetadataDao : RawWebDavSyncMetadataDao {
    private val entities = linkedMapOf<Pair<String, String>, WebDavSyncMetadataEntity>()

    override suspend fun getAll(workspaceGeneration: String): List<WebDavSyncMetadataEntity> =
        entities.values.filter { entity -> entity.workspaceGeneration == workspaceGeneration }

    override suspend fun getByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ): List<WebDavSyncMetadataEntity> =
        relativePaths.mapNotNull { relativePath -> entities[workspaceGeneration to relativePath] }

    override suspend fun upsertAll(entities: List<WebDavSyncMetadataEntity>) {
        entities.forEach { entity ->
            this.entities[entity.workspaceGeneration to entity.relativePath] = entity
        }
    }

    override suspend fun deleteByRelativePath(
        relativePath: String,
        workspaceGeneration: String,
    ) {
        entities.remove(workspaceGeneration to relativePath)
    }

    override suspend fun deleteByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ) {
        relativePaths.forEach { relativePath -> entities.remove(workspaceGeneration to relativePath) }
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }
}

private class InMemoryWebDavLocalChangeJournalDao : WebDavLocalChangeJournalDao {
    private val entities = linkedMapOf<Pair<String, String>, WebDavLocalChangeJournalEntity>()

    override suspend fun getAll(workspaceGeneration: String): List<WebDavLocalChangeJournalEntity> =
        entities.values.filter { entity -> entity.workspaceGeneration == workspaceGeneration }

    override suspend fun upsert(entity: WebDavLocalChangeJournalEntity) {
        entities[entity.workspaceGeneration to entity.id] = entity
    }

    override suspend fun deleteByIds(
        ids: Collection<String>,
        workspaceGeneration: String,
    ) {
        ids.forEach { id -> entities.remove(workspaceGeneration to id) }
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }
}

private class InMemoryWebDavLocalFingerprintDao : WebDavLocalFingerprintDao {
    private val entities = linkedMapOf<Pair<String, String>, WebDavLocalFingerprintEntity>()

    override suspend fun getByPath(
        path: String,
        workspaceGeneration: String,
    ): WebDavLocalFingerprintEntity? = entities[workspaceGeneration to path]

    override suspend fun upsert(entity: WebDavLocalFingerprintEntity) {
        entities[entity.workspaceGeneration to entity.path] = entity
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }

    override suspend fun deleteByExcludedPaths(
        paths: Collection<String>,
        workspaceGeneration: String,
    ) {
        entities.entries.removeIf { (key, _) ->
            key.first == workspaceGeneration && key.second !in paths
        }
    }
}

private class InMemoryRawS3SyncMetadataDao : RawS3SyncMetadataDao {
    private val entities = linkedMapOf<Pair<String, String>, S3SyncMetadataEntity>()

    override suspend fun getAll(workspaceGeneration: String): List<S3SyncMetadataEntity> =
        entities.values.filter { entity -> entity.workspaceGeneration == workspaceGeneration }

    override suspend fun getAllPlannerMetadataSnapshots(workspaceGeneration: String): List<S3SyncPlannerMetadataSnapshot> =
        getAll(workspaceGeneration).map { entity ->
            S3SyncPlannerMetadataSnapshot(
                relativePath = entity.relativePath,
                remotePath = entity.remotePath,
                etag = entity.etag,
                remoteLastModified = entity.remoteLastModified,
                localLastModified = entity.localLastModified,
                localSize = entity.localSize,
                remoteSize = entity.remoteSize,
                localFingerprint = entity.localFingerprint,
                lastSyncedAt = entity.lastSyncedAt,
                lastResolvedDirection = entity.lastResolvedDirection,
                lastResolvedReason = entity.lastResolvedReason,
            )
        }

    override suspend fun getAllRemoteMetadataSnapshots(workspaceGeneration: String): List<S3SyncRemoteMetadataSnapshot> =
        getAll(workspaceGeneration).map { entity ->
            S3SyncRemoteMetadataSnapshot(
                relativePath = entity.relativePath,
                remotePath = entity.remotePath,
                etag = entity.etag,
                remoteLastModified = entity.remoteLastModified,
            )
        }

    override suspend fun getByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ): List<S3SyncMetadataEntity> =
        relativePaths.mapNotNull { relativePath -> entities[workspaceGeneration to relativePath] }

    override suspend fun getLocalAuditPage(
        afterRelativePath: String?,
        limit: Int,
        workspaceGeneration: String,
    ): List<S3SyncMetadataEntity> =
        getAll(workspaceGeneration)
            .filter { entity -> afterRelativePath == null || entity.relativePath > afterRelativePath }
            .sortedBy(S3SyncMetadataEntity::relativePath)
            .take(limit)

    override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) {
        entities.forEach { entity ->
            this.entities[entity.workspaceGeneration to entity.relativePath] = entity
        }
    }

    override suspend fun deleteByRelativePath(
        relativePath: String,
        workspaceGeneration: String,
    ) {
        entities.remove(workspaceGeneration to relativePath)
    }

    override suspend fun deleteByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ) {
        relativePaths.forEach { relativePath -> entities.remove(workspaceGeneration to relativePath) }
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }
}

private class InMemoryS3SyncProtocolStateDao : S3SyncProtocolStateDao {
    private val entities = linkedMapOf<Pair<String, Int>, S3SyncProtocolStateEntity>()

    override suspend fun getById(
        workspaceGeneration: String,
        id: Int,
    ): S3SyncProtocolStateEntity? = entities[workspaceGeneration to id]

    override suspend fun upsert(entity: S3SyncProtocolStateEntity) {
        entities[entity.workspaceGeneration to entity.id] = entity
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }
}

private class InMemoryS3LocalChangeJournalDao : S3LocalChangeJournalDao {
    private val entities = linkedMapOf<Pair<String, String>, S3LocalChangeJournalEntity>()

    override suspend fun getAll(workspaceGeneration: String): List<S3LocalChangeJournalEntity> =
        entities.values.filter { entity -> entity.workspaceGeneration == workspaceGeneration }

    override suspend fun upsert(entity: S3LocalChangeJournalEntity) {
        entities[entity.workspaceGeneration to entity.id] = entity
    }

    override suspend fun deleteByIds(
        ids: Collection<String>,
        workspaceGeneration: String,
    ) {
        ids.forEach { id -> entities.remove(workspaceGeneration to id) }
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }
}

private class InMemoryS3RemoteIndexDao : S3RemoteIndexDao {
    private val entities = linkedMapOf<Pair<String, String>, S3RemoteIndexEntity>()

    override suspend fun getAll(workspaceGeneration: String): List<S3RemoteIndexEntity> =
        entities.values.filter { entity -> entity.workspaceGeneration == workspaceGeneration }

    override suspend fun getAllRelativePaths(workspaceGeneration: String): List<String> =
        getAll(workspaceGeneration).map(S3RemoteIndexEntity::relativePath)

    override suspend fun getPresentCount(workspaceGeneration: String): Int =
        getAll(workspaceGeneration).count { entity -> !entity.missingOnLastScan }

    override suspend fun getByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity> =
        relativePaths.mapNotNull { relativePath -> entities[workspaceGeneration to relativePath] }

    override suspend fun getByRelativePrefix(
        relativePrefix: String,
        descendantPattern: String,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity> =
        getAll(workspaceGeneration).filter { entity ->
            entity.relativePath == relativePrefix || entity.relativePath.startsWith("$relativePrefix/")
        }

    override suspend fun getOutsideScanBuckets(
        excludedBuckets: List<String>,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity> =
        getAll(workspaceGeneration).filterNot { entity -> entity.scanBucket in excludedBuckets }

    override suspend fun getReconcileCandidates(
        limit: Int,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity> =
        getAll(workspaceGeneration).take(limit)

    override suspend fun upsertAll(entities: List<S3RemoteIndexEntity>) {
        entities.forEach { entity ->
            this.entities[entity.workspaceGeneration to entity.relativePath] = entity
        }
    }

    override suspend fun deleteByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ) {
        relativePaths.forEach { relativePath -> entities.remove(workspaceGeneration to relativePath) }
    }

    override suspend fun deleteOutsideScanEpoch(
        scanEpoch: Long,
        workspaceGeneration: String,
    ) {
        entities.entries.removeIf { (key, entity) ->
            key.first == workspaceGeneration && entity.scanEpoch != scanEpoch
        }
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }
}

private class InMemoryS3RemoteShardStateDao : S3RemoteShardStateDao {
    private val entities = linkedMapOf<Pair<String, String>, S3RemoteShardStateEntity>()

    override suspend fun getAll(workspaceGeneration: String): List<S3RemoteShardStateEntity> =
        entities.values.filter { entity -> entity.workspaceGeneration == workspaceGeneration }

    override suspend fun getByBucketId(
        bucketId: String,
        workspaceGeneration: String,
    ): S3RemoteShardStateEntity? = entities[workspaceGeneration to bucketId]

    override suspend fun getByBucketIds(
        bucketIds: List<String>,
        workspaceGeneration: String,
    ): List<S3RemoteShardStateEntity> =
        bucketIds.mapNotNull { bucketId -> entities[workspaceGeneration to bucketId] }

    override suspend fun getMostSpecificAncestor(
        relativePrefix: String,
        workspaceGeneration: String,
    ): S3RemoteShardStateEntity? =
        getAll(workspaceGeneration)
            .filter { entity ->
                val candidate = entity.relativePrefix ?: return@filter false
                relativePrefix == candidate || relativePrefix.startsWith("$candidate/")
            }.maxByOrNull { entity -> entity.relativePrefix?.length ?: 0 }

    override suspend fun getScheduleTelemetry(
        workspaceGeneration: String,
        now: Long,
        recentChangeWindowMs: Long,
        uncertaintyWindowMs: Long,
        changePressureThreshold: Double,
        verificationFailureThreshold: Double,
        minUncertaintyAttempts: Int,
        minUncertaintyFailures: Int,
    ): S3RemoteShardScheduleTelemetrySnapshot =
        S3RemoteShardScheduleTelemetrySnapshot(
            shardCount = getAll(workspaceGeneration).size,
            oldestScanAt = getAll(workspaceGeneration).minOfOrNull(S3RemoteShardStateEntity::lastScannedAt),
            hasElevatedChangePressure = 0,
            hasHighVerificationUncertainty = 0,
        )

    override suspend fun upsertAll(entities: List<S3RemoteShardStateEntity>) {
        entities.forEach { entity ->
            this.entities[entity.workspaceGeneration to entity.bucketId] = entity
        }
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }
}

private fun conflictSession(
    source: SyncBackendType,
    path: String,
): SyncConflictSet =
    SyncConflictSet(
        source = source,
        files =
            listOf(
                SyncConflictFile(
                    relativePath = path,
                    localContent = "local",
                    remoteContent = "remote",
                    isBinary = false,
                ),
            ),
        timestamp = 123L,
    )

private fun reviewSession(
    source: SyncBackendType,
    path: String,
): SyncReviewSession =
    SyncReviewSession(
        source = source,
        items =
            listOf(
                SyncReviewItem(
                    relativePath = path,
                    localContent = "local",
                    incomingContent = "incoming",
                    isBinary = false,
                    localLastModified = 10L,
                    incomingLastModified = 20L,
                    state = SyncReviewItemState.READY_TO_IMPORT,
                    message = "ready",
                ),
            ),
        timestamp = 456L,
        kind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
    )

private fun gitEntry(path: String): GitMediaSyncMetadataEntry =
    GitMediaSyncMetadataEntry(
        relativePath = path,
        repoLastModified = 10L,
        localLastModified = 20L,
        lastSyncedAt = 30L,
        lastResolvedDirection = GitMediaSyncMetadataEntry.UNCHANGED,
        lastResolvedReason = GitMediaSyncMetadataEntry.NONE,
    )

private fun webDavMetadata(relativePath: String): WebDavSyncMetadataEntity =
    WebDavSyncMetadataEntity(
        relativePath = relativePath,
        remotePath = "remote/$relativePath",
        etag = "etag-$relativePath",
        remoteLastModified = 10L,
        localLastModified = 20L,
        localFingerprint = "fingerprint-$relativePath",
        lastSyncedAt = 30L,
        lastResolvedDirection = WebDavSyncMetadataEntity.UNCHANGED,
        lastResolvedReason = WebDavSyncMetadataEntity.NONE,
    )

private fun s3Metadata(relativePath: String): S3SyncMetadataEntity =
    S3SyncMetadataEntity(
        relativePath = relativePath,
        remotePath = "remote/$relativePath",
        etag = "etag-$relativePath",
        remoteLastModified = 10L,
        localLastModified = 20L,
        localSize = 100L,
        remoteSize = 100L,
        localFingerprint = "fingerprint-$relativePath",
        lastSyncedAt = 30L,
        lastResolvedDirection = S3SyncMetadataEntity.UNCHANGED,
        lastResolvedReason = S3SyncMetadataEntity.NONE,
    )

private fun s3RemoteIndex(relativePath: String): S3RemoteIndexEntry =
    S3RemoteIndexEntry(
        relativePath = relativePath,
        remotePath = "remote/$relativePath",
        etag = "etag-$relativePath",
        remoteLastModified = 10L,
        size = 100L,
        lastSeenAt = 20L,
        lastVerifiedAt = 30L,
        scanBucket = "bucket",
    )

private fun s3RemoteShard(bucketId: String): S3RemoteShardState =
    S3RemoteShardState(
        bucketId = bucketId,
        relativePrefix = "lomo/memos",
        lastScannedAt = 40L,
        lastObjectCount = 1,
    )

private fun legacyProtocolSidecar(filesDir: File): File = File(filesDir, "s3_sync_protocol_state.json")

private fun legacyJournalSidecar(filesDir: File): File = File(filesDir, "s3_local_change_journal.json")

private fun legacyProtocolJson(): String =
    """
    {
      "lastSuccessfulSyncAt": 999,
      "indexedLocalFileCount": 8,
      "indexedRemoteFileCount": 9,
      "localModeFingerprint": "legacy-sidecar"
    }
    """.trimIndent()

private fun legacyJournalJson(): String =
    """
    {
      "entries": [
        {
          "id": "MEMO:legacy.md",
          "kind": "MEMO",
          "filename": "legacy.md",
          "changeType": "UPSERT",
          "updatedAt": 999
        }
      ]
    }
    """.trimIndent()

private fun s3ProtocolEntity(
    workspaceGeneration: String,
    state: S3SyncProtocolState,
): S3SyncProtocolStateEntity =
    S3SyncProtocolStateEntity(
        workspaceGeneration = workspaceGeneration,
        protocolVersion = state.protocolVersion,
        lastSuccessfulSyncAt = state.lastSuccessfulSyncAt,
        lastFastSyncAt = state.lastFastSyncAt,
        lastReconcileAt = state.lastReconcileAt,
        lastFullRemoteScanAt = state.lastFullRemoteScanAt,
        indexedLocalFileCount = state.indexedLocalFileCount,
        indexedRemoteFileCount = state.indexedRemoteFileCount,
        localModeFingerprint = state.localModeFingerprint,
        remoteScanCursor = state.remoteScanCursor,
        scanEpoch = state.scanEpoch,
    )

private fun s3JournalEntity(
    workspaceGeneration: String,
    entry: S3LocalChangeJournalEntry,
): S3LocalChangeJournalEntity =
    S3LocalChangeJournalEntity(
        workspaceGeneration = workspaceGeneration,
        id = entry.id,
        kind = entry.kind.name,
        filename = entry.filename,
        changeType = entry.changeType.name,
        updatedAt = entry.updatedAt,
    )
