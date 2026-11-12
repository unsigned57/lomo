package com.lomo.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceRootTransitionCorruptionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/*
 * Behavior Contract:
 * Capability: selected workspace and attachment locations commit only after candidate verification; owner: data; priority: P0.
 * Scenarios: prepared and activated journals preserve prior selections; commit publishes candidate bindings;
 * recovery discards uncommitted candidates; stale media baselines reject activation.
 * Observable outcomes: real DataStore locations, decoded journal values and typed transaction failures.
 * TDD proof: target/lomo/reports/manual/directory-selection/transaction-red.log observes premature media publication;
 * verification-green.log in that directory verifies durable prepare/activate/commit/recover behavior.
 * Test Change Justification:
 * Reason category: unified directory transaction protocol.
 * Old behavior/assertion being replaced: root-only preparation calls supplied a candidate without attachment binding information.
 * Why old assertion is no longer correct: the same journal also prepares attachment rebinds; root-only calls explicitly supply no rebind.
 * Coverage preserved by: all root persistence, rollback, activation and display assertions remain unchanged; new media transaction cases extend them.
 * Why this is not fitting the test to the implementation: settings must never publish a directory whose indexing has not succeeded.
 * - Unit under test: DirectorySettingsRepositoryImpl
 * - Behavior focus: storage-area to root-type mapping, current location from the config source, and display/apply delegation.
 * - Observable outcomes: observed StorageLocation raw values, null fallthrough, and setRoot arguments.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: filesystem/SAF validity checks and DataStore persistence internals.
 */
class DirectorySettingsRepositoryImplTest : DataFunSpec() {
    init {
        test(
            "given media selection changes outside a prepared transition when activated then the stale transaction is rejected",
        ) {
            runTest {
                val (_, store, repository) = setUpTest()
                val root = StorageLocation("/workspace")
                val old = StorageLocation("/old-images")
                store.updateRootDirectory(root.raw)
                store.updateImageDirectory(old.raw)
                val transition = repository.prepareRootTransition(
                    root,
                    com.lomo.domain.model.MediaDirectoryChange(StorageArea.IMAGE, old, StorageLocation("/new-images")),
                )
                store.updateImageDirectory("/external-selection")

                shouldThrow<WorkspaceRootTransitionCorruptionException> {
                    repository.markRootTransitionActivated(transition.id)
                }
                repository.currentLocation(StorageArea.IMAGE) shouldBe StorageLocation("/external-selection")
            }
        }
        for (area in listOf(StorageArea.IMAGE, StorageArea.VOICE)) {
            test(
                "given a prepared $area binding when activated and committed then its selection publishes only at commit",
            ) {
                runTest {
                    val (_, store, repository) = setUpTest()
                    val root = StorageLocation("/workspace")
                    val previous = StorageLocation("/old-${area.name}")
                    val candidate = StorageLocation("/new-${area.name}")
                    store.updateRootDirectory(root.raw)
                    when (area) {
                        StorageArea.IMAGE -> store.updateImageDirectory(previous.raw)
                        StorageArea.VOICE -> store.updateVoiceDirectory(previous.raw)
                        else -> error("Unexpected test area")
                    }
                    val change = com.lomo.domain.model.MediaDirectoryChange(area, previous, candidate)
                    val transition = repository.prepareRootTransition(root, change)
                    repository.pendingRootTransition()?.mediaChange shouldBe change
                    repository.currentLocation(area) shouldBe previous
                    repository.markRootTransitionActivated(transition.id)
                    repository.currentLocation(area) shouldBe previous
                    repository.commitRootTransition(transition.id)

                    repository.currentRootLocation() shouldBe root
                    repository.currentLocation(area) shouldBe candidate
                    repository.pendingRootTransition() shouldBe null
                }
            }
            test(
                "given an activated but uncommitted first $area selection when recovered then its candidate is discarded",
            ) {
                runTest {
                    val (_, store, repository) = setUpTest()
                    val root = StorageLocation("/workspace")
                    store.updateRootDirectory(root.raw)
                    val change = com.lomo.domain.model.MediaDirectoryChange(area, null, StorageLocation("/candidate"))
                    val transition = repository.prepareRootTransition(root, change)
                    repository.markRootTransitionActivated(transition.id)

                    repository.recoverRootLocation() shouldBe root
                    repository.currentLocation(area) shouldBe null
                    repository.pendingRootTransition() shouldBe null
                }
            }
        }
        test("observeLocation maps ROOT area to MAIN root flow and wraps raw location") {
            runTest {
                val (dataSource, _, repository) = setUpTest()
                dataSource.setRoot(StorageRootType.MAIN, "/vault/root")

                val location = repository.observeLocation(StorageArea.ROOT).first()

                requireNotNull(location)
                location.raw shouldBe "/vault/root"
            }
        }

        test("observeLocation returns null when underlying flow emits null") {
            runTest {
                val (_, _, repository) = setUpTest()

                repository.observeLocation(StorageArea.IMAGE).first().shouldBeNull()
            }
        }

        test("currentLocation reads the stored location for each storage area") {
            runTest {
                val (_, dataStore, repository) = setUpTest()
                dataStore.updateRootUri("content://root")
                dataStore.updateImageUri("content://images")
                dataStore.updateVoiceDirectory("/voice-only")

                repository.currentLocation(StorageArea.ROOT)?.raw shouldBe "content://root"
                repository.currentLocation(StorageArea.IMAGE)?.raw shouldBe "content://images"
                repository.currentLocation(StorageArea.VOICE)?.raw shouldBe "/voice-only"
            }
        }

        test("writing a path after a uri replaces the stored location") {
            runTest {
                val (_, dataStore, repository) = setUpTest()
                dataStore.updateRootUri("content://root")
                dataStore.updateRootDirectory("/root")

                repository.currentLocation(StorageArea.ROOT)?.raw shouldBe "/root"
            }
        }

        test("observeDisplayName maps area to matching root type") {
            runTest {
                val (dataSource, _, repository) = setUpTest()
                dataSource.setRoot(StorageRootType.VOICE, "Voice Dir")

                repository.observeDisplayName(StorageArea.VOICE).first() shouldBe "Voice Dir"
            }
        }

        test("applyLocation delegates update using mapped storage root type") {
            runTest {
                val (dataSource, _, repository) = setUpTest()
                val update = StorageAreaUpdate(StorageArea.IMAGE, StorageLocation("content://tree/images"))

                repository.applyLocation(update)

                dataSource.getRootFlow(StorageRootType.IMAGE).first() shouldBe "content://tree/images"
            }
        }

        test("pending root transition never publishes candidate and cold recovery keeps committed root") {
            runTest {
                val (_, dataStore, repository) = setUpTest()
                dataStore.updateRootDirectory("/workspace-a")

                repository.prepareRootTransition(StorageLocation("/workspace-b"), mediaChange = null)

                repository.currentRootLocation() shouldBe StorageLocation("/workspace-a")
                repository.recoverRootLocation() shouldBe StorageLocation("/workspace-a")
                repository.pendingRootTransition().shouldBeNull()
            }
        }

        test("activated root transition atomically publishes candidate") {
            runTest {
                val (_, dataStore, repository) = setUpTest()
                dataStore.updateRootDirectory("/workspace-a")
                val transition = repository.prepareRootTransition(
                    StorageLocation("content://workspace-b"),
                    mediaChange = null,
                )

                repository.markRootTransitionActivated(transition.id)
                repository.commitRootTransition(transition.id)

                repository.currentRootLocation() shouldBe StorageLocation("content://workspace-b")
                repository.pendingRootTransition().shouldBeNull()
            }
        }

        test("activated but uncommitted transition recovers previous root after restart") {
            runTest {
                val (_, dataStore, repository) = setUpTest()
                dataStore.updateRootDirectory("/workspace-a")
                val transition = repository.prepareRootTransition(StorageLocation("/workspace-b"), mediaChange = null)
                repository.markRootTransitionActivated(transition.id)

                repository.recoverRootLocation() shouldBe StorageLocation("/workspace-a")
                repository.currentRootLocation() shouldBe StorageLocation("/workspace-a")
                repository.pendingRootTransition().shouldBeNull()
            }
        }

        test("prepared transition cannot commit candidate") {
            runTest {
                val (_, dataStore, repository) = setUpTest()
                dataStore.updateRootDirectory("/workspace-a")
                val transition = repository.prepareRootTransition(StorageLocation("/workspace-b"), mediaChange = null)

                val failure = runCatching { repository.commitRootTransition(transition.id) }.exceptionOrNull()

                requireNotNull(failure)
                repository.currentRootLocation() shouldBe StorageLocation("/workspace-a")
                repository.pendingRootTransition() shouldBe transition
            }
        }
    }

    private fun TestScope.setUpTest(): Triple<
        DirectoryFakeWorkspaceConfigSource,
        LomoDataStore,
        DirectorySettingsRepositoryImpl,
        > {
        val dataSource = DirectoryFakeWorkspaceConfigSource()
        val dataStore = createLomoDataStore(backgroundScope)
        val repository =
            DirectorySettingsRepositoryImpl(
                dataSource = dataSource,
                dataStore = dataStore,
            )
        return Triple(dataSource, dataStore, repository)
    }

    private fun createLomoDataStore(scope: CoroutineScope): LomoDataStore {
        val backingFile = Files.createTempFile("lomo-datastore", ".preferences_pb").toFile().apply {
            deleteOnExit()
        }
        val realDataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { backingFile },
        )
        val constructor = LomoDataStore::class.java.getDeclaredConstructor(
            androidx.datastore.core.DataStore::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(realDataStore)
    }
}

private class DirectoryFakeWorkspaceConfigSource : WorkspaceConfigSource {
    private val roots = MutableStateFlow<Map<StorageRootType, String>>(emptyMap())
    var throwOnSetRoot = false

    override suspend fun setRoot(type: StorageRootType, pathOrUri: String) {
        if (throwOnSetRoot) {
            throw IllegalStateException("setRoot failed")
        }
        roots.value = roots.value + (type to pathOrUri)
    }

    override fun getRootFlow(type: StorageRootType): Flow<String?> = roots.map { it[type] }

    override fun getRootDisplayNameFlow(type: StorageRootType): Flow<String?> =
        roots.map { it[type]?.substringAfterLast('/') }

    override suspend fun createDirectory(name: String): String = name
}
