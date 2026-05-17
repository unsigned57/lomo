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
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Test Contract:
 * - Unit under test: DirectorySettingsRepositoryImpl
 * - Behavior focus: storage-area to root-type mapping, uri-vs-path precedence for current location, and display/apply delegation.
 * - Observable outcomes: observed StorageLocation raw values, null fallthrough, selected datastore field priority, and setRoot arguments.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: filesystem/SAF validity checks and DataStore persistence internals.
 */
class DirectorySettingsRepositoryImplTest : DataFunSpec() {
    init {
        test("observeLocation maps ROOT area to MAIN root flow and wraps raw location") { `observeLocation maps ROOT area to MAIN root flow and wraps raw location`() }

        test("observeLocation returns null when underlying flow emits null") { `observeLocation returns null when underlying flow emits null`() }

        test("currentLocation prefers uri over directory for each storage area") { `currentLocation prefers uri over directory for each storage area`() }

        test("currentLocation falls back to directory when uri is null") { `currentLocation falls back to directory when uri is null`() }

        test("observeDisplayName maps area to matching root type") { `observeDisplayName maps area to matching root type`() }

        test("applyLocation delegates update using mapped storage root type") { `applyLocation delegates update using mapped storage root type`() }
    }


    private val dataSource: WorkspaceConfigSource = mockk(relaxed = true)
    private val dataStore: LomoDataStore = mockk(relaxed = true)

    private val repository =
        DirectorySettingsRepositoryImpl(
            dataSource = dataSource,
            dataStore = dataStore,
        )

    private fun `observeLocation maps ROOT area to MAIN root flow and wraps raw location`() =
        runTest {
            every { dataSource.getRootFlow(StorageRootType.MAIN) } returns flowOf("/vault/root")

            val location = repository.observeLocation(StorageArea.ROOT).first()

            requireNotNull(location)
            location.raw shouldBe "/vault/root"
        }

    private fun `observeLocation returns null when underlying flow emits null`() =
        runTest {
            every { dataSource.getRootFlow(StorageRootType.IMAGE) } returns flowOf(null)

            repository.observeLocation(StorageArea.IMAGE).first().shouldBeNull()
        }

    private fun `currentLocation prefers uri over directory for each storage area`() =
        runTest {
            every { dataStore.rootUri } returns flowOf("content://root")
            every { dataStore.rootDirectory } returns flowOf("/root")
            every { dataStore.imageUri } returns flowOf("content://images")
            every { dataStore.imageDirectory } returns flowOf("/images")
            every { dataStore.voiceUri } returns flowOf("content://voice")
            every { dataStore.voiceDirectory } returns flowOf("/voice")

            repository.currentLocation(StorageArea.ROOT)?.raw shouldBe "content://root"
            repository.currentLocation(StorageArea.IMAGE)?.raw shouldBe "content://images"
            repository.currentLocation(StorageArea.VOICE)?.raw shouldBe "content://voice"
        }

    private fun `currentLocation falls back to directory when uri is null`() =
        runTest {
            every { dataStore.rootUri } returns flowOf(null)
            every { dataStore.rootDirectory } returns flowOf("/root-only")
            every { dataStore.imageUri } returns flowOf(null)
            every { dataStore.imageDirectory } returns flowOf("/images-only")
            every { dataStore.voiceUri } returns flowOf(null)
            every { dataStore.voiceDirectory } returns flowOf("/voice-only")

            repository.currentLocation(StorageArea.ROOT)?.raw shouldBe "/root-only"
            repository.currentLocation(StorageArea.IMAGE)?.raw shouldBe "/images-only"
            repository.currentLocation(StorageArea.VOICE)?.raw shouldBe "/voice-only"
        }

    private fun `observeDisplayName maps area to matching root type`() =
        runTest {
            every { dataSource.getRootDisplayNameFlow(StorageRootType.VOICE) } returns flowOf("Voice Dir")

            repository.observeDisplayName(StorageArea.VOICE).first() shouldBe "Voice Dir"
        }

    private fun `applyLocation delegates update using mapped storage root type`() =
        runTest {
            val update = StorageAreaUpdate(StorageArea.IMAGE, StorageLocation("content://tree/images"))
            coEvery { dataSource.setRoot(StorageRootType.IMAGE, "content://tree/images") } returns Unit

            repository.applyLocation(update)

            coVerify(exactly = 1) { dataSource.setRoot(StorageRootType.IMAGE, "content://tree/images") }
        }
}

