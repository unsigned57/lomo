package com.lomo.data.local.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: S3ConnectionStoreImpl
 * - Behavior focus: persisted rclone crypt configuration must survive round-trips for filename strategy, secondary naming rules, and no-data-encryption toggles.
 * - Observable outcomes: DataStore-backed flow values after update operations.
 * - Red phase: Fails before the fix because the S3 DataStore layer has no rclone crypt setting keys beyond the coarse encryption mode field.
 * - Excludes: Android Context wiring, repository consumers, and keystore-backed credentials.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LomoS3DataStoreStrongCompatTest {
    @Test
    fun `s3 connection store persists rclone crypt advanced settings`() =
        runTest {
            val dataStore = newDataStore(backgroundScope)
            val connectionStore = S3ConnectionStoreImpl(dataStore)

            connectionStore.updateS3RcloneFilenameEncryption("obfuscate")
            connectionStore.updateS3RcloneFilenameEncoding("base32768")
            connectionStore.updateS3RcloneDirectoryNameEncryption(false)
            connectionStore.updateS3RcloneDataEncryptionEnabled(false)
            connectionStore.updateS3RcloneEncryptedSuffix("none")

            assertEquals("obfuscate", connectionStore.s3RcloneFilenameEncryption.first())
            assertEquals("base32768", connectionStore.s3RcloneFilenameEncoding.first())
            assertFalse(connectionStore.s3RcloneDirectoryNameEncryption.first())
            assertFalse(connectionStore.s3RcloneDataEncryptionEnabled.first())
            assertEquals("none", connectionStore.s3RcloneEncryptedSuffix.first())
        }

    private fun newDataStore(scope: CoroutineScope): androidx.datastore.core.DataStore<Preferences> {
        val backingFile = tempPreferencesFile()
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { backingFile },
        )
    }

    private fun tempPreferencesFile(): File =
        Files.createTempFile("lomo-s3-datastore", ".preferences_pb").toFile().apply {
            deleteOnExit()
        }
}
