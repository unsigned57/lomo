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



import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.worker.GitSyncScheduler
import com.lomo.data.worker.S3SyncScheduler
import com.lomo.data.worker.WebDavSyncScheduler
import com.lomo.domain.model.SyncBackendType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope

/*
 * Behavior Contract:
 * - Unit under test: SyncPolicyRepositoryImpl
 * - Behavior focus: backend preference mapping and scheduler enable/cancel policy.
 * - Observable outcomes: observed SyncBackendType values, atomic datastore flag mutation, and scheduler calls.
 * - TDD proof: Fails before the fix because the S3 backend cannot be persisted or rescheduled, so remote sync policy never activates the S3 scheduler.
 * - Excludes: WorkManager request construction and Android context-driven worker registration.
 */
class SyncPolicyRepositoryImplTest : DataFunSpec() {
    init {
        test("observeRemoteSyncBackend maps persisted preference and falls back to NONE") {
            runTest {
                val context = setUpTest()
                context.dataStore.updateSyncBackendType("unexpected")

                context.repository.observeRemoteSyncBackend().first() shouldBe SyncBackendType.NONE
            }
        }

        test("setRemoteSyncBackend writes all four keys atomically for git backend") {
            runTest {
                val context = setUpTest()

                context.repository.setRemoteSyncBackend(SyncBackendType.GIT)

                context.dataStore.syncBackendType.first() shouldBe "git"
                context.dataStore.gitSyncEnabled.first() shouldBe true
                context.dataStore.webDavSyncEnabled.first() shouldBe false
                context.dataStore.s3SyncEnabled.first() shouldBe false
            }
        }

        test("setRemoteSyncBackend writes all four keys atomically for NONE backend") {
            runTest {
                val context = setUpTest()

                context.repository.setRemoteSyncBackend(SyncBackendType.NONE)

                context.dataStore.syncBackendType.first() shouldBe "none"
                context.dataStore.gitSyncEnabled.first() shouldBe false
                context.dataStore.webDavSyncEnabled.first() shouldBe false
                context.dataStore.s3SyncEnabled.first() shouldBe false
            }
        }

        test("setRemoteSyncBackend writes all four keys atomically for s3 backend") {
            runTest {
                val context = setUpTest()

                context.repository.setRemoteSyncBackend(SyncBackendType.S3)

                context.dataStore.syncBackendType.first() shouldBe "s3"
                context.dataStore.gitSyncEnabled.first() shouldBe false
                context.dataStore.webDavSyncEnabled.first() shouldBe false
                context.dataStore.s3SyncEnabled.first() shouldBe true
            }
        }

        test("applyRemoteSyncPolicy cancels both schedulers when backend is NONE") {
            runTest {
                val context = setUpTest()
                context.dataStore.updateSyncBackendType("none")

                context.repository.applyRemoteSyncPolicy()

                verify(exactly = 1) { context.gitSyncScheduler.cancel() }
                verify(exactly = 1) { context.webDavSyncScheduler.cancel() }
                verify(exactly = 1) { context.s3SyncScheduler.cancel() }
                coVerify(exactly = 0) { context.gitSyncScheduler.reschedule() }
                coVerify(exactly = 0) { context.webDavSyncScheduler.reschedule() }
                coVerify(exactly = 0) { context.s3SyncScheduler.reschedule() }
            }
        }

        test("applyRemoteSyncPolicy reschedules git and cancels webdav when backend is GIT") {
            runTest {
                val context = setUpTest()
                context.dataStore.updateSyncBackendType("git")

                context.repository.applyRemoteSyncPolicy()

                verify(exactly = 1) { context.webDavSyncScheduler.cancel() }
                verify(exactly = 1) { context.s3SyncScheduler.cancel() }
                coVerify(exactly = 1) { context.gitSyncScheduler.reschedule() }
                coVerify(exactly = 0) { context.webDavSyncScheduler.reschedule() }
                coVerify(exactly = 0) { context.s3SyncScheduler.reschedule() }
            }
        }

        test("applyRemoteSyncPolicy reschedules webdav and cancels git when backend is WEBDAV") {
            runTest {
                val context = setUpTest()
                context.dataStore.updateSyncBackendType("webdav")

                context.repository.applyRemoteSyncPolicy()

                verify(exactly = 1) { context.gitSyncScheduler.cancel() }
                verify(exactly = 1) { context.s3SyncScheduler.cancel() }
                coVerify(exactly = 1) { context.webDavSyncScheduler.reschedule() }
                coVerify(exactly = 0) { context.gitSyncScheduler.reschedule() }
            }
        }

        test("applyRemoteSyncPolicy reschedules s3 and cancels git webdav when backend is S3") {
            runTest {
                val context = setUpTest()
                context.dataStore.updateSyncBackendType("s3")

                context.repository.applyRemoteSyncPolicy()

                verify(exactly = 1) { context.gitSyncScheduler.cancel() }
                verify(exactly = 1) { context.webDavSyncScheduler.cancel() }
                coVerify(exactly = 1) { context.s3SyncScheduler.reschedule() }
                coVerify(exactly = 0) { context.gitSyncScheduler.reschedule() }
                coVerify(exactly = 0) { context.webDavSyncScheduler.reschedule() }
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.setUpTest(): TestContext {
        val context = mockk<Context>(relaxed = true)
        val dataStore = createLomoDataStore(backgroundScope)
        val gitSyncScheduler = mockk<GitSyncScheduler>(relaxed = true)
        val webDavSyncScheduler = mockk<WebDavSyncScheduler>(relaxed = true)
        val s3SyncScheduler = mockk<S3SyncScheduler>(relaxed = true)

        val repository = SyncPolicyRepositoryImpl(
            context = context,
            dataStore = dataStore,
            gitSyncScheduler = gitSyncScheduler,
            webDavSyncScheduler = webDavSyncScheduler,
            s3SyncScheduler = s3SyncScheduler,
        )
        return TestContext(context, dataStore, gitSyncScheduler, webDavSyncScheduler, s3SyncScheduler, repository)
    }

    private data class TestContext(
        val context: Context,
        val dataStore: LomoDataStore,
        val gitSyncScheduler: GitSyncScheduler,
        val webDavSyncScheduler: WebDavSyncScheduler,
        val s3SyncScheduler: S3SyncScheduler,
        val repository: SyncPolicyRepositoryImpl
    )

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
