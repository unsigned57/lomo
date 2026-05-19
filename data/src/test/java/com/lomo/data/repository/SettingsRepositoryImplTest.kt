package com.lomo.data.repository

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope

/*
 * Behavior Contract:
 * - Unit under test: SettingsRepositoryImpl
 * - Behavior focus: workspace-location writes, failure propagation, and datastore-backed preference delegation.
 * - Observable outcomes: repository return values, thrown exceptions, and collaborator interactions.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Test Change Justification: reason category = pure refactor preserved behavior; removed the unshipped pinned-tag preference dependency from test setup because the production preferences aggregate no longer exposes that repository, while the retained assertions still cover workspace writes, failure propagation, and delegated preference mutations.
 * - Excludes: concrete datastore implementation details and Compose/UI rendering.
 */
class SettingsRepositoryImplTest : DataFunSpec() {
    init {
        test("applyRootLocation updates root workspace location") {
            runTest {
                val (dataSource, _, repository) = setUpTest()
                repository.applyRootLocation(StorageLocation("/tmp/lomo"))
                dataSource.getRootFlow(StorageRootType.MAIN).first() shouldBe "/tmp/lomo"
            }
        }

        test("applyLocation propagates update failure") {
            runTest {
                val (dataSource, _, repository) = setUpTest()
                val update = StorageAreaUpdate(area = StorageArea.ROOT, location = StorageLocation("content://lomo/root"))
                dataSource.throwOnSetRoot = true

                val exception = runCatching { repository.applyLocation(update) }.exceptionOrNull()

                (exception is IllegalStateException).shouldBeTrue()
            }
        }

        test("isAppLockEnabled delegates to datastore") {
            runTest {
                val (_, dataStore, repository) = setUpTest()
                dataStore.updateAppLockEnabled(true)

                val enabled = repository.isAppLockEnabled().first()

                enabled shouldBe true
            }
        }

        test("setAppLockEnabled delegates to datastore") {
            runTest {
                val (_, dataStore, repository) = setUpTest()
                repository.setAppLockEnabled(true)

                dataStore.appLockEnabled.first() shouldBe true
            }
        }

        test("applyLocation updates sync inbox workspace location") {
            runTest {
                val (dataSource, _, repository) = setUpTest()
                repository.applyLocation(StorageAreaUpdate(StorageArea.SYNC_INBOX, StorageLocation("/tmp/inbox")))

                dataSource.getRootFlow(StorageRootType.SYNC_INBOX).first() shouldBe "/tmp/inbox"
            }
        }

        test("setSyncInboxEnabled delegates to datastore") {
            runTest {
                val (_, dataStore, repository) = setUpTest()
                repository.setSyncInboxEnabled(true)

                dataStore.syncInboxEnabled.first() shouldBe true
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.setUpTest(): Triple<SettingsFakeWorkspaceConfigSource, LomoDataStore, SettingsRepositoryImpl> {
        val dataSource = SettingsFakeWorkspaceConfigSource()
        val dataStore = createLomoDataStore(backgroundScope)
        val repository =
            SettingsRepositoryImpl(
                directoryRepository = DirectorySettingsRepositoryImpl(dataSource, dataStore),
                preferencesRepository =
                    PreferencesRepositoryImpl(
                        dateTimePreferencesRepository = DateTimePreferencesRepositoryImpl(dataStore),
                        storagePreferencesRepository = StoragePreferencesRepositoryImpl(dataStore),
                        interactionPreferencesRepository = InteractionPreferencesRepositoryImpl(dataStore),
                        interactionBehaviorPreferencesRepository =
                            InteractionBehaviorPreferencesRepositoryImpl(dataStore),
                        memoActionPreferencesRepository = MemoActionPreferencesRepositoryImpl(dataStore),
                        inputToolbarPreferencesRepository = InputToolbarPreferencesRepositoryImpl(dataStore),
                        securityPreferencesRepository = SecurityPreferencesRepositoryImpl(dataStore),
                        shareCardPreferencesRepository = ShareCardPreferencesRepositoryImpl(dataStore),
                        syncInboxPreferencesRepository = SyncInboxPreferencesRepositoryImpl(dataStore),
                        draftPreferencesRepository = DraftPreferencesRepositoryImpl(dataStore),
                        typographyPreferencesRepository = TypographyPreferencesRepositoryImpl(dataStore),
                        sidebarTagOrderPreferencesRepository = SidebarTagOrderPreferencesRepositoryImpl(dataStore),
                    ),
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
        val constructor = LomoDataStore::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(realDataStore)
    }
}

private class SettingsFakeWorkspaceConfigSource : WorkspaceConfigSource {
    private val roots = MutableStateFlow<Map<StorageRootType, String>>(emptyMap())
    var throwOnSetRoot = false

    override suspend fun setRoot(type: StorageRootType, pathOrUri: String) {
        if (throwOnSetRoot) {
            throw IllegalStateException("setRoot failed")
        }
        roots.value = roots.value + (type to pathOrUri)
    }

    override fun getRootFlow(type: StorageRootType): Flow<String?> =
        roots.map { it[type] }

    override fun getRootDisplayNameFlow(type: StorageRootType): Flow<String?> =
        roots.map { it[type]?.substringAfterLast('/') }

    override suspend fun createDirectory(name: String): String = name
}

