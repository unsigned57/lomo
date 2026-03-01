package com.lomo.data.share

import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.FileDataSource
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ShareServiceManagerTest {
    @MockK(relaxed = true)
    private lateinit var context: Context

    @MockK(relaxed = true)
    private lateinit var dataSource: FileDataSource

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    private lateinit var attachmentStorage: ShareAttachmentStorage

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        every { dataStore.lanShareE2eEnabled } returns flowOf(true)
        every { dataStore.lanSharePairingKeyHex } returns flowOf(null)
        every { dataStore.lanShareDeviceName } returns flowOf("Lomo Device")
        every { dataStore.rootDirectory } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(null)
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.voiceUri } returns flowOf(null)
        attachmentStorage = ShareAttachmentStorage(context, dataSource, dataStore)
    }

    @Test
    fun `resolveAvailableAttachmentFilename appends suffix for existing audio file`() =
        runTest {
            val tempDir = Files.createTempDirectory("lomo-share-audio").toFile()
            File(tempDir, "voice.m4a").writeText("existing")
            every { dataStore.voiceDirectory } returns flowOf(tempDir.absolutePath)

            val resolved = attachmentStorage.resolveAvailableAttachmentFilename(type = "audio", preferredName = "voice.m4a")

            assertEquals("voice_1.m4a", resolved)
        }
}
