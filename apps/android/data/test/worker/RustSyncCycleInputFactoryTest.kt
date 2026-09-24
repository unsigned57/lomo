package com.lomo.data.worker

/*
 * Behavior Contract:
 * - Unit under test: RustSyncCycleInputFactory.resolveCycleInput (B06/T41 scheduler boundary).
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: the persisted Git backend configuration — remote URL, branch, author name/email —
 *   becomes explicit per-kind WorkManager input. A persisted remote still carrying URL userinfo
 *   fails closed (null) so a poisoned endpoint can never reach the worker again; a clean remote
 *   yields `gitBranch` exactly as persisted.
 *
 * Scenarios:
 * - Given remote + branch=master + author configured, when GIT input resolves, then the decoded
 *   request carries backendKind=git, endpoint, branch=master, and the author pair.
 * - Given a remote with userinfo, when GIT input resolves, then the result is null.
 * - Given a blank remote or blank branch, when GIT input resolves, then the result is null.
 * - Given stored git username material, then the identity field key is GIT_USERNAME; without it
 *   the key is absent and the token secret field key is still present.
 *
 * Observable outcomes: decoded RustSyncWorkRequest fields; null refusal.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.worker.RustSyncCycleInputFactoryTest'
 * - RED: git input construction hard-coded branch "main" and accepted poisoned endpoints.
 *
 * Excludes: WorkManager enqueue; Rust-side plan (covered by FFI contracts).
 */

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.lomo.data.engine.sync.SecretMaterialSource
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.fakes.MemorySecretMaterialSource
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.SyncBackendType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class RustSyncCycleInputFactoryTest : FunSpec({
    test("persisted git branch reaches the work request") {
        runTest {
            val dataStore = createFactoryDataStore(backgroundScope)
            dataStore.updateGitRemoteUrl("https://example.com/repo.git")
            dataStore.updateGitBranch("master")
            dataStore.updateGitAuthorName("Alice")
            dataStore.updateGitAuthorEmail("alice@example.com")
            val factory = RustSyncCycleInputFactory(dataStore, identityMaterial = MemorySecretMaterialSource())

            val data = factory.resolveCycleInput(SyncBackendType.GIT, "/ws")
            val request = RustSyncWorker.resolveWorkRequest(data!!)

            request.backendKind shouldBe "git"
            request.endpointUrl shouldBe "https://example.com/repo.git"
            request.gitBranch shouldBe "master"
            request.gitAuthorName shouldBe "Alice"
            request.gitAuthorEmail shouldBe "alice@example.com"
            request.secretFieldKey shouldBe CredentialField.GIT_TOKEN.name
        }
    }

    test("git remote with userinfo fails closed") {
        runTest {
            val dataStore = createFactoryDataStore(backgroundScope)
            dataStore.updateGitRemoteUrl("https://alice:s3cret@example.com/repo.git")
            dataStore.updateGitBranch("main")
            val factory = RustSyncCycleInputFactory(dataStore, identityMaterial = MemorySecretMaterialSource())

            factory.resolveCycleInput(SyncBackendType.GIT, "/ws").shouldBeNull()
        }
    }

    test("blank remote or blank branch fails closed") {
        runTest {
            val dataStore = createFactoryDataStore(backgroundScope)
            val factory = RustSyncCycleInputFactory(dataStore, identityMaterial = MemorySecretMaterialSource())

            factory.resolveCycleInput(SyncBackendType.GIT, "/ws").shouldBeNull()

            dataStore.updateGitRemoteUrl("https://example.com/repo.git")
            dataStore.updateGitBranch("   ")
            factory.resolveCycleInput(SyncBackendType.GIT, "/ws").shouldBeNull()
        }
    }

    test("git username material advertises the identity field key") {
        runTest {
            val dataStore = createFactoryDataStore(backgroundScope)
            dataStore.updateGitRemoteUrl("https://example.com/repo.git")
            dataStore.updateGitBranch("main")
            val material =
                object : SecretMaterialSource {
                    override fun readSecretBytes(fieldKey: String): ByteArray? =
                        if (fieldKey == CredentialField.GIT_USERNAME.name) {
                            "alice".toByteArray()
                        } else {
                            null
                        }

                    override fun hasMaterial(fieldKey: String): Boolean =
                        fieldKey == CredentialField.GIT_USERNAME.name
                }
            val factory = RustSyncCycleInputFactory(dataStore, material)

            val data = factory.resolveCycleInput(SyncBackendType.GIT, "/ws")
            val request = RustSyncWorker.resolveWorkRequest(data!!)

            request.identityFieldKey shouldBe CredentialField.GIT_USERNAME.name
        }
    }
})

private fun createFactoryDataStore(scope: CoroutineScope): LomoDataStore {
    val backingFile =
        Files.createTempFile("lomo-cycle-input", ".preferences_pb").toFile().apply {
            deleteOnExit()
        }
    val realDataStore =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { backingFile },
        )
    val constructor =
        LomoDataStore::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(realDataStore)
}
