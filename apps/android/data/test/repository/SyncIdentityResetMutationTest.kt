package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: provider configuration mutation repositories × SyncIdentityResetPolicy.
 * - Owning layer: data (the mutation boundary is the last write surface before persistence).
 * - Priority tier: P0 (durable sync facts must not outlive the remote identity they were
 *   recorded under).
 * - Capability: a settings write that changes a canonical-identity input (Git remote/branch/
 *   author, S3 endpoint/region/bucket/prefix/access-key-id, WebDAV resolved endpoint/username)
 *   first disposes identity-scoped durable state — scheduler cancel → deferred-lock clear →
 *   workspace sync-state reset — then persists. Re-writing the stored value and non-identity
 *   settings (autosync toggles/intervals, tokens, path style, provider label) are no-ops for
 *   the durable tree.
 *
 * Scenarios:
 * - Given stored value X for an identity field, when the setter writes Y != X, then the disposal
 *   triple runs before the new value lands and the preference is updated.
 * - Given stored value X, when the setter writes X again, then no disposal runs.
 * - Given a non-identity setter, when it writes, then no disposal runs.
 * - Given an explicit WebDAV endpointUrl, when baseUrl changes, then no disposal runs (the
 *   canonical resolved endpoint never moved); when endpointUrl is cleared and resolution falls
 *   back to baseUrl, disposal runs because the resolved identity did move.
 *
 * Observable outcomes: recorded disposal order; final DataStore/credential-store values.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.repository.SyncIdentityResetMutationTest'
 * - RED: mutation repositories had no SyncIdentityResetPolicy wiring — identity writes persisted
 *   while queued work and the durable control tree still pointed at the old remote.
 *
 * Excludes: WorkManager/Keystore devices (fakes + in-memory secure store), Rust fence behavior
 * (covered by lomo-sync sync_session_contract / takeover_matrix_contract).
 */

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.Data
import com.lomo.data.git.GitCredentialStore
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.security.DefaultCredentialRepository
import com.lomo.data.security.SecureStringReadResult
import com.lomo.data.security.SecureStringStore
import com.lomo.data.sync.SyncIdentityResetPolicy
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.data.worker.DeferredLockWorkStore
import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.repository.SyncStateResetRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

private const val DISPOSED = "reset"

class SyncIdentityResetMutationTest : FunSpec({

    // ---------- Git identity fields ----------

    test("git setRemoteUrl to a different remote disposes durable state then persists") {
        runTest {
            val fixture = IdentityFixture(backgroundScope)
            fixture.dataStore.updateGitRemoteUrl("https://example.com/old.git")

            fixture.git.setRemoteUrl("https://example.com/new.git")

            fixture.events shouldBe listOf("cancel", "deferred-clear", DISPOSED)
            fixture.dataStore.gitRemoteUrl.first() shouldBe "https://example.com/new.git"
        }
    }

    test("git setRemoteUrl re-writing the same remote is a durable-state no-op") {
        runTest {
            val fixture = IdentityFixture(backgroundScope)
            fixture.dataStore.updateGitRemoteUrl("https://example.com/repo.git")

            fixture.git.setRemoteUrl("https://example.com/repo.git")

            fixture.events.shouldBeEmpty()
        }
    }

    test("git setBranch disposes only when the branch actually changes") {
        runTest {
            val fixture = IdentityFixture(backgroundScope)
            fixture.dataStore.updateGitBranch("main")

            fixture.git.setBranch("main")
            fixture.events.shouldBeEmpty()

            var branchAtReset: String? = null
            fixture.onReset = { branchAtReset = fixture.dataStore.gitBranch.first() }
            fixture.git.setBranch("dev")

            fixture.events shouldBe listOf("cancel", "deferred-clear", DISPOSED)
            // Disposal observed the pre-write value: reset runs before the mutation lands.
            branchAtReset shouldBe "main"
            fixture.dataStore.gitBranch.first() shouldBe "dev"
        }
    }

    test("git setAuthorInfo disposes once when either author field changes") {
        runTest {
            val fixture = IdentityFixture(backgroundScope)
            fixture.dataStore.updateGitAuthorName("Lomo")
            fixture.dataStore.updateGitAuthorEmail("git@lomo.local")

            fixture.git.setAuthorInfo("Lomo", "git@lomo.local")
            fixture.events.shouldBeEmpty()

            fixture.git.setAuthorInfo("Lomo", "sync@lomo.local")
            fixture.events shouldBe listOf("cancel", "deferred-clear", DISPOSED)
            fixture.dataStore.gitAuthorEmail.first() shouldBe "sync@lomo.local"
        }
    }

    test("git token and autosync writes never touch durable sync state") {
        runTest {
            val fixture = IdentityFixture(backgroundScope)

            fixture.git.setToken("ghp_secret")
            fixture.git.setAutoSyncEnabled(true)
            fixture.git.setAutoSyncInterval("15m")
            fixture.git.setSyncOnRefreshEnabled(true)

            fixture.events.shouldBeEmpty()
        }
    }

    // ---------- S3 identity fields ----------

    test("s3 endpoint/region/bucket/prefix writes dispose only on real change") {
        runTest {
            val fixture = IdentityFixture(backgroundScope)
            fixture.dataStore.updateS3EndpointUrl("https://s3.example")
            fixture.dataStore.updateS3Region("us-east-1")
            fixture.dataStore.updateS3Bucket("bucket-a")
            fixture.dataStore.updateS3Prefix("lomo")

            // Unchanged values: no disposal.
            fixture.s3.setEndpointUrl("https://s3.example")
            fixture.s3.setRegion("us-east-1")
            fixture.s3.setBucket("bucket-a")
            fixture.s3.setPrefix("lomo")
            fixture.events.shouldBeEmpty()

            fixture.s3.setEndpointUrl("https://s3-b.example")
            fixture.s3.setRegion("eu-west-1")
            fixture.s3.setBucket("bucket-b")
            fixture.s3.setPrefix("media")

            // Each identity write contributes one ordered cancel→clear→reset triple.
            fixture.events shouldBe
                buildList {
                    repeat(4) {
                        add("cancel")
                        add("deferred-clear")
                        add(DISPOSED)
                    }
                }
            fixture.dataStore.s3Bucket.first() shouldBe "bucket-b"
            fixture.dataStore.s3Prefix.first() shouldBe "media"
        }
    }

    test("s3 setAccessKeyId compares against the credential store and disposes on change") {
        runTest {
            val fixture = IdentityFixture(backgroundScope)
            fixture.s3CredentialStore.setSecret(CredentialField.S3_ACCESS_KEY_ID, "AKIA_OLD")

            fixture.s3.setAccessKeyId("AKIA_OLD")
            fixture.events.shouldBeEmpty()

            fixture.s3.setAccessKeyId("AKIA_NEW")
            fixture.events shouldBe listOf("cancel", "deferred-clear", DISPOSED)
            fixture.s3CredentialStore.getSecret(CredentialField.S3_ACCESS_KEY_ID) shouldBe "AKIA_NEW"
        }
    }

    test("s3 secret tokens, path style, encryption and autosync writes never touch durable state") {
        runTest {
            val fixture = IdentityFixture(backgroundScope)

            fixture.s3.setSecretAccessKey("secret")
            fixture.s3.setSessionToken("session")
            fixture.s3.setEncryptionPassword("pw")
            fixture.s3.setEncryptionPassword2("pw2")
            fixture.s3.setLocalSyncDirectory("/vault")
            fixture.s3.setPathStyle(S3PathStyle.VIRTUAL_HOSTED)
            fixture.s3.setAutoSyncEnabled(true)
            fixture.s3.setAutoSyncInterval("1h")
            fixture.s3.setSyncOnRefreshEnabled(true)

            fixture.events.shouldBeEmpty()
        }
    }

    // ---------- WebDAV identity fields ----------

    test("webdav endpoint writes dispose on resolved-endpoint change only") {
        runTest {
            val fixture = IdentityFixture(backgroundScope)
            fixture.dataStore.updateWebDavBaseUrl("https://dav.example/base")

            // baseUrl is the resolved endpoint while no explicit endpointUrl exists.
            fixture.webDav.setBaseUrl("https://dav.example/other")
            fixture.events shouldBe listOf("cancel", "deferred-clear", DISPOSED)

            // With an explicit endpointUrl set, baseUrl is shadowed — no identity change.
            fixture.dataStore.updateWebDavEndpointUrl("https://dav.example/endpoint")
            fixture.events.clear()
            fixture.webDav.setBaseUrl("https://dav.example/shadowed")
            fixture.events.shouldBeEmpty()

            // Clearing the endpointUrl falls back to baseUrl — resolved endpoint moved.
            fixture.webDav.setEndpointUrl("")
            fixture.events shouldBe listOf("cancel", "deferred-clear", DISPOSED)
        }
    }

    test("webdav username writes dispose on change; password and provider never do") {
        runTest {
            val fixture = IdentityFixture(backgroundScope)
            fixture.webDavCredentialStore.setUsername("alice")

            fixture.webDav.setUsername("alice")
            fixture.events.shouldBeEmpty()

            fixture.webDav.setUsername("bob")
            fixture.events shouldBe listOf("cancel", "deferred-clear", DISPOSED)
            fixture.webDavCredentialStore.getUsername() shouldBe "bob"

            fixture.events.clear()
            fixture.webDav.setPassword("p@ss")
            fixture.webDav.setProvider(WebDavProvider.NEXTCLOUD)
            fixture.webDav.setAutoSyncEnabled(true)
            fixture.events.shouldBeEmpty()
        }
    }
})

private class IdentityFixture(
    scope: CoroutineScope,
) {
    val events = mutableListOf<String>()
    var onReset: (suspend () -> Unit)? = null

    val dataStore: LomoDataStore = createIdentityDataStore(scope)

    private val gitPrefs = RecordingSecureStringStore()
    private val webDavPrefs = RecordingSecureStringStore()
    private val s3Prefs = RecordingSecureStringStore()

    val webDavCredentialStore = WebDavCredentialStore(webDavPrefs)
    val s3CredentialStore = S3CredentialStore(s3Prefs)

    private val credentials =
        DefaultCredentialRepository(
            gitCredentialStore = GitCredentialStore(gitPrefs),
            webDavCredentialStore = webDavCredentialStore,
            s3CredentialStore = s3CredentialStore,
        )

    private val deferredStore =
        object : DeferredLockWorkStore {
            override fun save(input: Data) = error("not used")

            override fun take(): Data? = null

            override fun clear() {
                events += "deferred-clear"
            }
        }

    private val scheduler =
        mockk<RustSyncScheduler>().also {
            every { it.cancel() } answers { events += "cancel" }
        }

    private val reset =
        object : SyncStateResetRepository {
            override suspend fun resetWorkspaceScopedSyncState() {
                onReset?.invoke()
                events += DISPOSED
            }
        }

    private val policy = SyncIdentityResetPolicy(scheduler, deferredStore, reset)

    val git = GitSyncConfigurationMutationRepositoryImpl(dataStore, credentials, policy)
    val s3 =
        S3SyncConfigurationMutationRepositoryImpl(
            dataStore,
            credentials,
            s3CredentialStore,
            policy,
        )
    val webDav =
        WebDavSyncConfigurationMutationRepositoryImpl(
            dataStore,
            webDavCredentialStore,
            credentials,
            policy,
        )
}

private class RecordingSecureStringStore : SecureStringStore {
    private val stored = mutableMapOf<String, String?>()

    override fun readString(key: String): SecureStringReadResult =
        stored[key]?.let(SecureStringReadResult::Present) ?: SecureStringReadResult.Missing

    override fun putString(
        key: String,
        value: String?,
    ) {
        stored[key] = value
    }
}

private fun createIdentityDataStore(scope: CoroutineScope): LomoDataStore {
    val backingFile =
        Files.createTempFile("lomo-identity-reset", ".preferences_pb").toFile().apply {
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
