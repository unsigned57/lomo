package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var dataSource: WorkspaceConfigSource

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    private lateinit var repository: SettingsRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repository = SettingsRepositoryImpl(dataSource, dataStore)
    }

    @Test
    fun `applyRootLocation updates root workspace location`() =
        runTest {
            coEvery { dataSource.setRoot(type = StorageRootType.MAIN, pathOrUri = "/tmp/lomo") } just runs

            repository.applyRootLocation(StorageLocation("/tmp/lomo"))

            coVerify(exactly = 1) {
                dataSource.setRoot(type = StorageRootType.MAIN, pathOrUri = "/tmp/lomo")
            }
        }

    @Test
    fun `applyLocation propagates update failure`() =
        runTest {
            val update = StorageAreaUpdate(area = StorageArea.ROOT, location = StorageLocation("content://lomo/root"))
            coEvery {
                dataSource.setRoot(type = StorageRootType.MAIN, pathOrUri = "content://lomo/root")
            } throws IllegalStateException("setRoot failed")

            val exception = runCatching { repository.applyLocation(update) }.exceptionOrNull()

            assertTrue(exception is IllegalStateException)
            coVerify(exactly = 1) {
                dataSource.setRoot(type = StorageRootType.MAIN, pathOrUri = "content://lomo/root")
            }
        }

    @Test
    fun `isAppLockEnabled delegates to datastore`() =
        runTest {
            every { dataStore.appLockEnabled } returns flowOf(true)

            val enabled = repository.isAppLockEnabled().first()

            assertEquals(true, enabled)
        }

    @Test
    fun `setAppLockEnabled delegates to datastore`() =
        runTest {
            coEvery { dataStore.updateAppLockEnabled(true) } just runs

            repository.setAppLockEnabled(true)

            coVerify(exactly = 1) { dataStore.updateAppLockEnabled(true) }
        }
}
