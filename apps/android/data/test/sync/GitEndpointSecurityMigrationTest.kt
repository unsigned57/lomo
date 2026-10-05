package com.lomo.data.sync

/*
 * Behavior Contract:
 * - Unit under test: GitEndpointSecurityMigration.migrateIfNeeded (B06/T41 one-time cleanup).
 * - Owning layer: data. Mounted in SyncPolicyRepositoryImpl.applyRemoteSyncPolicy at startup.
 * - Priority tier: P0 (credential material on disk).
 * - Capability: a persisted Git remote carrying URL userinfo is split — username/token move into
 *   the Keystore-backed credential store, the sanitized https URL replaces the poisoned one, and
 *   every surface that could have journaled the endpoint (queued work, deferred lock blob, sync
 *   control tree, git mirror config) is purged. An endpoint that is not safely parseable
 *   (SCP-like SSH, broken authority) fails closed by dropping the remote.
 *
 * Scenarios:
 * - Given https://user:pass@host/repo.git, when the migration runs, then the persisted remote is
 *   https://host/repo.git, GIT_USERNAME=user and GIT_TOKEN=pass were written, and purge ran.
 * - Given a username-only userinfo URL, then only GIT_USERNAME is written and the token is left
 *   untouched.
 * - Given percent-encoded userinfo, then stored credentials are UTF-8 decoded.
 * - Given git@host:path/repo.git (SCP-like), then the remote is dropped and nothing is written.
 * - Given a clean https remote, then the migration is a no-op (no writes, no purge).
 * - Given a migrated remote, when the migration runs again, then it stays a no-op.
 *
 * Observable outcomes: DataStore git_remote_url, recorded credential writes, purge event order.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.sync.GitEndpointSecurityMigrationTest'
 * - RED: no migration existed — poisoned remotes reached the scheduler unchanged.
 *
 * Excludes: real Keystore/WorkManager devices; Rust endpoint parsing (covered in lomo-git).
 *
 * Test Change Justification:
 * - Reason category: SCP-like endpoint detection widened beyond userinfo forms.
 * - Old behavior/assertion being replaced: only `git@host:path` spelled SCP-like; host:path and
 *   host:port/path forms without `@` slipped through as candidate remotes.
 * - Why old assertion is no longer correct: any authority:path shape whose pre-colon segment
 *   carries no `/` is SSH transport; a host-only or host:port form would persist an unparsable
 *   remote instead of failing closed.
 * - Coverage preserved by: the original SCP-like drop scenario plus new host:path, host:port and
 *   path-prefix non-SCP arms.
 * - Why this is not fitting the test to the implementation: new arms assert the same observable
 *   drop-and-purge outcome, extended to the widened input shapes.
 */

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.Data
import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.repository.TestCredentialRepository
import com.lomo.data.worker.DeferredLockWorkStore
import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.model.CredentialField
import com.lomo.domain.repository.SyncStateResetRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class GitEndpointSecurityMigrationTest : FunSpec({
    test("user+password userinfo moves into credential store and url is sanitized") {
        runTest {
            val fixture = MigrationFixture(backgroundScope)
            fixture.dataStore.updateGitRemoteUrl("https://alice:s3cret@example.com/repo.git")
            val mirror = fixture.mirrorDir().apply { mkdirs() }
            java.io.File(mirror, "config").writeText("legacy")

            fixture.migration.migrateIfNeeded()

            fixture.dataStore.gitRemoteUrl.first() shouldBe "https://example.com/repo.git"
            fixture.credentials.writes shouldBe
                listOf(
                    CredentialField.GIT_TOKEN to "s3cret",
                    CredentialField.GIT_USERNAME to "alice",
                )
            fixture.events shouldBe listOf("cancel", "deferred-clear", "reset")
            mirror.exists() shouldBe false
        }
    }

    test("username-only userinfo writes only the username and preserves the token") {
        runTest {
            val fixture = MigrationFixture(backgroundScope)
            fixture.dataStore.updateGitRemoteUrl("https://alice@example.com/repo.git")

            fixture.migration.migrateIfNeeded()

            fixture.dataStore.gitRemoteUrl.first() shouldBe "https://example.com/repo.git"
            fixture.credentials.writes shouldBe listOf(CredentialField.GIT_USERNAME to "alice")
        }
    }

    test("percent-encoded userinfo is decoded before storing") {
        runTest {
            val fixture = MigrationFixture(backgroundScope)
            fixture.dataStore.updateGitRemoteUrl(
                "https://user%40corp.com:p%3Ass%E4%B8%AD@example.com/repo.git",
            )

            fixture.migration.migrateIfNeeded()

            fixture.dataStore.gitRemoteUrl.first() shouldBe "https://example.com/repo.git"
            fixture.credentials.writes shouldBe
                listOf(
                    CredentialField.GIT_TOKEN to "p:ss中",
                    CredentialField.GIT_USERNAME to "user@corp.com",
                )
        }
    }

    test("scp-like endpoint fails closed by dropping the remote") {
        runTest {
            val fixture = MigrationFixture(backgroundScope)
            fixture.dataStore.updateGitRemoteUrl("git@example.com:alice/repo.git")

            fixture.migration.migrateIfNeeded()

            fixture.dataStore.gitRemoteUrl.first() shouldBe null
            fixture.credentials.writes.shouldBeEmpty()
            fixture.events shouldBe listOf("cancel", "deferred-clear", "reset")
        }
    }

    test("host:path scp-like endpoint without userinfo is dropped") {
        runTest {
            val fixture = MigrationFixture(backgroundScope)
            fixture.dataStore.updateGitRemoteUrl("example.com:alice/repo.git")

            fixture.migration.migrateIfNeeded()

            fixture.dataStore.gitRemoteUrl.first() shouldBe null
            fixture.credentials.writes.shouldBeEmpty()
            fixture.events shouldBe listOf("cancel", "deferred-clear", "reset")
        }
    }

    test("host:port scp-like endpoint is dropped") {
        runTest {
            val fixture = MigrationFixture(backgroundScope)
            fixture.dataStore.updateGitRemoteUrl("example.com:2222/alice/repo.git")

            fixture.migration.migrateIfNeeded()

            fixture.dataStore.gitRemoteUrl.first() shouldBe null
            fixture.credentials.writes.shouldBeEmpty()
            fixture.events shouldBe listOf("cancel", "deferred-clear", "reset")
        }
    }

    test("path prefix before a colon is not scp-like and stays untouched") {
        runTest {
            val fixture = MigrationFixture(backgroundScope)
            // A `dir/name:rest` shape has no scheme and a `/` before the colon — not SSH.
            fixture.dataStore.updateGitRemoteUrl("relative/dir:repo.git")

            fixture.migration.migrateIfNeeded()

            fixture.dataStore.gitRemoteUrl.first() shouldBe "relative/dir:repo.git"
            fixture.credentials.writes.shouldBeEmpty()
            fixture.events.shouldBeEmpty()
        }
    }

    test("clean https remote is a no-op") {
        runTest {
            val fixture = MigrationFixture(backgroundScope)
            fixture.dataStore.updateGitRemoteUrl("https://example.com/repo.git")
            val mirror = fixture.mirrorDir().apply { mkdirs() }

            fixture.migration.migrateIfNeeded()

            fixture.dataStore.gitRemoteUrl.first() shouldBe "https://example.com/repo.git"
            fixture.credentials.writes.shouldBeEmpty()
            fixture.events.shouldBeEmpty()
            mirror.exists() shouldBe true
        }
    }

    test("second run after migration stays a no-op") {
        runTest {
            val fixture = MigrationFixture(backgroundScope)
            fixture.dataStore.updateGitRemoteUrl("https://alice:s3cret@example.com/repo.git")

            fixture.migration.migrateIfNeeded()
            val writesAfterFirst = fixture.credentials.writes.toList()
            val eventsAfterFirst = fixture.events.toList()
            fixture.migration.migrateIfNeeded()

            fixture.credentials.writes shouldBe writesAfterFirst
            fixture.events shouldBe eventsAfterFirst
        }
    }

    test("empty remote is a no-op") {
        runTest {
            val fixture = MigrationFixture(backgroundScope)

            fixture.migration.migrateIfNeeded()

            fixture.credentials.writes.shouldBeEmpty()
            fixture.events.shouldBeEmpty()
        }
    }
})

private class MigrationFixture(
    scope: CoroutineScope,
) {
    val events = mutableListOf<String>()
    val dataStore: LomoDataStore = createMigrationDataStore(scope)
    val credentials = TestCredentialRepository(mutableMapOf())
    private val workspaceDir = Files.createTempDirectory("lomo-git-migration").toFile()
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
                events += "reset"
            }
        }
    val migration =
        GitEndpointSecurityMigration(
            dataStore = dataStore,
            credentialRepository = credentials,
            identityReset = SyncIdentityResetPolicy(scheduler, deferredStore, reset),
            workspaceRoot = WorkspaceFilesystemRoot { workspaceDir.absolutePath },
        )

    fun mirrorDir() = java.io.File(workspaceDir, ".lomo/sync/v1/git-mirror")
}

private fun createMigrationDataStore(scope: CoroutineScope): LomoDataStore {
    val backingFile =
        Files.createTempFile("lomo-git-migration", ".preferences_pb").toFile().apply {
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
