package com.lomo.data.share


import com.lomo.data.repository.MemoSynchronizer
import com.lomo.domain.repository.MediaRepository
import io.mockk.MockKAnnotations
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec

/*
 * Test Contract:
 * - Unit under test: ShareIncomingMemoSaver
 * - Behavior focus: attachment reference remapping and incoming memo persistence side effects after legacy capture removal.
 * - Observable outcomes: saved memo content/timestamp and media refresh execution.
 * - Red phase: Fails before the fix because ShareIncomingMemoSaver still requires legacy capture infrastructure that is being removed from the incoming share path.
 * - Excludes: LAN transport protocol, attachment file storage implementation, and UI state handling.
 */
class ShareIncomingMemoSaverTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("saveReceivedMemo saves remapped incoming memo") { `saveReceivedMemo saves remapped incoming memo`() }

        test("saveReceivedMemo saves plain incoming memo") { `saveReceivedMemo saves plain incoming memo`() }
    }


    @MockK(relaxed = true)
    private lateinit var synchronizer: MemoSynchronizer

    @MockK(relaxed = true)
    private lateinit var mediaRepository: MediaRepository

    private lateinit var saver: ShareIncomingMemoSaver

    private fun setUp() {
        MockKAnnotations.init(this)
        saver =
            ShareIncomingMemoSaver(
                synchronizer = synchronizer,
                mediaRepository = mediaRepository,
            )
    }

    private fun `saveReceivedMemo saves remapped incoming memo`() =
        runTest {
            val content = "memo with ![img](photo.png) and ![audio](voice.m4a)"
            val attachmentMappings = mapOf("photo.png" to "photo_1.png", "voice.m4a" to "voice_2.m4a")

            saver.saveReceivedMemo(content, timestamp = 123L, attachmentMappings = attachmentMappings)

            coVerifyOrder {
                synchronizer.saveMemo(
                    "memo with ![img](photo_1.png) and ![audio](voice_2.m4a)",
                    123L,
                )
                mediaRepository.refreshImageLocations()
            }
        }

    private fun `saveReceivedMemo saves plain incoming memo`() =
        runTest {
            saver.saveReceivedMemo(
                content = "plain incoming memo",
                timestamp = 456L,
                attachmentMappings = emptyMap(),
            )

            coVerifyOrder {
                synchronizer.saveMemo("plain incoming memo", 456L)
                mediaRepository.refreshImageLocations()
            }
        }
}
