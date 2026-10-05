package com.lomo.data.local.datastore

/*
 * Behavior Contract:
 * - Unit under test: LegacyWebDavUsernameDrainMigration.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: the retired plaintext `webdav_username` key is drained into the credential
 *   store at first store access, and the plaintext copy is removed in the same transaction.
 *
 * Scenarios:
 * - Given a store file carrying the legacy key and an empty credential slot, when the store is
 *   opened, then the username lands in the credential store and the plaintext key is gone.
 * - Given the credential slot already holds a username, when the migration runs, then the
 *   credential wins and the plaintext key is still removed.
 * - Given a credential-store write failure, when the migration runs, then the legacy key is
 *   retained so a later launch retries the drain.
 * - Given no legacy key, when the store is opened, then nothing is written.
 *
 * Observable outcomes: persisted preference keys, fake credential-store contents.
 *
 * TDD proof:
 * - The drain writes through an in-memory SecureStringStore and a real Preferences file; the
 *   failure-retry scenario fails RED without the keep-on-error branch.
 *
 * Excludes:
 * - Keystore/encrypted storage internals and UI presentation of the migrated username.
 */

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.lomo.data.security.SecureStringReadResult
import com.lomo.data.security.SecureStringStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.webdav.WebDavCredentialStore
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

class LegacyWebDavUsernameDrainMigrationTest : DataFunSpec() {
    init {
        test("given legacy plaintext username when store opens then credential store receives it and plaintext is removed") {
            runTest {
                val backing = seedStoreFile(backgroundScope) { it[LomoDataStoreKeys.WEBDAV_USERNAME] = "alice" }
                val credentials = InMemorySecureStringStore()
                val store = migratedStore(backing, backgroundScope, credentials)

                // Any read triggers the migration transaction.
                store.data.first()

                credentials.strings["webdav_username"] shouldBe "alice"
                store.data.first()[LomoDataStoreKeys.WEBDAV_USERNAME].shouldBeNull()
            }
        }

        test("given credential already stored when migration runs then credential wins and plaintext is removed") {
            runTest {
                val backing = seedStoreFile(backgroundScope) { it[LomoDataStoreKeys.WEBDAV_USERNAME] = "stale-copy" }
                val credentials = InMemorySecureStringStore(mutableMapOf("webdav_username" to "current"))
                val store = migratedStore(backing, backgroundScope, credentials)

                store.data.first()

                credentials.strings["webdav_username"] shouldBe "current"
                store.data.first()[LomoDataStoreKeys.WEBDAV_USERNAME].shouldBeNull()
            }
        }

        test("given credential write failure when migration runs then legacy key is retained for retry") {
            runTest {
                val backing = seedStoreFile(backgroundScope) { it[LomoDataStoreKeys.WEBDAV_USERNAME] = "alice" }
                val credentials = InMemorySecureStringStore().apply { failWrites = true }
                val store = migratedStore(backing, backgroundScope, credentials)

                store.data.first()

                credentials.strings["webdav_username"].shouldBeNull()
                store.data.first()[LomoDataStoreKeys.WEBDAV_USERNAME] shouldBe "alice"
            }
        }

        test("given no legacy key when store opens then credential store stays untouched") {
            runTest {
                val backing = seedStoreFile(backgroundScope) { it[LomoDataStoreKeys.GIT_AUTHOR_NAME] = "lomo" }
                val credentials = InMemorySecureStringStore()
                val store = migratedStore(backing, backgroundScope, credentials)

                store.data.first()[LomoDataStoreKeys.GIT_AUTHOR_NAME] shouldBe "lomo"
                credentials.strings shouldBe emptyMap()
            }
        }
    }

    private suspend fun seedStoreFile(
        scope: CoroutineScope,
        seed: (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ): File {
        val seedFile = Files.createTempFile("legacy-drain-seed", ".preferences_pb").toFile()
        val seedStore =
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { seedFile },
            )
        seedStore.edit { prefs -> seed(prefs) }
        val backing = Files.createTempFile("legacy-drain", ".preferences_pb").toFile()
        seedFile.copyTo(backing, overwrite = true)
        return backing
    }

    private fun migratedStore(
        backing: File,
        scope: CoroutineScope,
        credentials: InMemorySecureStringStore,
    ) = PreferenceDataStoreFactory.create(
        scope = scope,
        migrations =
            listOf(
                LegacyWebDavUsernameDrainMigration(
                    credentialStoreFactory = { WebDavCredentialStore(credentials) },
                ),
            ),
        produceFile = { backing },
    )
}

private class InMemorySecureStringStore(
    val strings: MutableMap<String, String> = mutableMapOf(),
) : SecureStringStore {
    var failWrites: Boolean = false

    override fun readString(key: String): SecureStringReadResult =
        strings[key]?.let(SecureStringReadResult::Present) ?: SecureStringReadResult.Missing

    override fun putString(
        key: String,
        value: String?,
    ) {
        if (failWrites) error("credential write failure")
        if (value.isNullOrBlank()) {
            strings.remove(key)
        } else {
            strings[key] = value
        }
    }
}
