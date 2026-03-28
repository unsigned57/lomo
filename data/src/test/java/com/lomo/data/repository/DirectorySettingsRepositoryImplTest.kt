package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: DirectorySettingsRepositoryImpl
 * - Behavior focus: storage-area to root-type mapping, uri-vs-path precedence for current location, and display/apply delegation.
 * - Observable outcomes: observed StorageLocation raw values, null fallthrough, selected datastore field priority, and setRoot arguments.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: filesystem/SAF validity checks and DataStore persistence internals.
 */
class DirectorySettingsRepositoryImplTest {
    private val dataSource: WorkspaceConfigSource = mockk(relaxed = true)
    private val dataStore: LomoDataStore = mockk(relaxed = true)

    private val repository =
        DirectorySettingsRepositoryImpl(
            dataSource = dataSource,
            dataStore = dataStore,
        )

    @Test
    fun `observeLocation maps ROOT area to MAIN root flow and wraps raw location`() =
        runTest {
            every { dataSource.getRootFlow(StorageRootType.MAIN) } returns flowOf("/vault/root")

            val location = repository.observeLocation(StorageArea.ROOT).first()

            requireNotNull(location)
            assertEquals("/vault/root", location.raw)
        }

    @Test
    fun `observeLocation returns null when underlying flow emits null`() =
        runTest {
            every { dataSource.getRootFlow(StorageRootType.IMAGE) } returns flowOf(null)

            assertNull(repository.observeLocation(StorageArea.IMAGE).first())
        }

    @Test
    fun `currentLocation prefers uri over directory for each storage area`() =
        runTest {
            every { dataStore.rootUri } returns flowOf("content://root")
            every { dataStore.rootDirectory } returns flowOf("/root")
            every { dataStore.imageUri } returns flowOf("content://images")
            every { dataStore.imageDirectory } returns flowOf("/images")
            every { dataStore.voiceUri } returns flowOf("content://voice")
            every { dataStore.voiceDirectory } returns flowOf("/voice")

            assertEquals("content://root", repository.currentLocation(StorageArea.ROOT)?.raw)
            assertEquals("content://images", repository.currentLocation(StorageArea.IMAGE)?.raw)
            assertEquals("content://voice", repository.currentLocation(StorageArea.VOICE)?.raw)
        }

    @Test
    fun `currentLocation falls back to directory when uri is null`() =
        runTest {
            every { dataStore.rootUri } returns flowOf(null)
            every { dataStore.rootDirectory } returns flowOf("/root-only")
            every { dataStore.imageUri } returns flowOf(null)
            every { dataStore.imageDirectory } returns flowOf("/images-only")
            every { dataStore.voiceUri } returns flowOf(null)
            every { dataStore.voiceDirectory } returns flowOf("/voice-only")

            assertEquals("/root-only", repository.currentLocation(StorageArea.ROOT)?.raw)
            assertEquals("/images-only", repository.currentLocation(StorageArea.IMAGE)?.raw)
            assertEquals("/voice-only", repository.currentLocation(StorageArea.VOICE)?.raw)
        }

    @Test
    fun `observeDisplayName maps area to matching root type`() =
        runTest {
            every { dataSource.getRootDisplayNameFlow(StorageRootType.VOICE) } returns flowOf("Voice Dir")

            assertEquals("Voice Dir", repository.observeDisplayName(StorageArea.VOICE).first())
        }

    @Test
    fun `applyLocation delegates update using mapped storage root type`() =
        runTest {
            val update = StorageAreaUpdate(StorageArea.IMAGE, StorageLocation("content://tree/images"))
            coEvery { dataSource.setRoot(StorageRootType.IMAGE, "content://tree/images") } returns Unit

            repository.applyLocation(update)

            coVerify(exactly = 1) { dataSource.setRoot(StorageRootType.IMAGE, "content://tree/images") }
        }
}

