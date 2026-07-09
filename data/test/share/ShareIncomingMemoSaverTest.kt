package com.lomo.data.share

import com.lomo.data.repository.MemoSynchronizer
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: ShareIncomingMemoSaver
 * - Owning layer: data/share
 * - Priority tier: P0
 * - Capability: Persist inbound LAN-shared memo content while remapping attachment filenames only inside Markdown link/image targets.
 *
 * Scenarios:
 * - Given inbound content contains image and link targets that match attachment filenames, when saved, then only those targets use stored filenames.
 * - Given inline Markdown targets include optional titles, when saved, then destinations are remapped and titles are preserved.
 * - Given reference-style Markdown image definitions target attachment filenames, when saved, then definition destinations are remapped and labels/titles are preserved.
 * - Given ordinary body text, fenced code, URL text, and visible link titles contain an attachment filename, when saved, then those characters are preserved.
 * - Given fenced code contains a four-space indented fence-looking line, when saved, then the line does not close the fence and later code text is preserved.
 * - Given a paragraph continues with a four-space indented Markdown target line, when saved, then that continuation target is remapped.
 * - Given a list item is followed by a blank line and a four-space indented continuation target, when saved, then the continuation target is remapped.
 * - Given a list item is followed by a blank line and a deeper indented code block, when saved, then the code text is preserved.
 * - Given a heading is followed by a top-level four-space indented Markdown-looking target, when saved, then the code text is preserved.
 * - Given top-level indented code blocks contain Markdown-looking attachment references, when saved, then code text is preserved.
 * - Given reference definitions use up to three leading spaces outside code, when saved, then destinations are still remapped.
 * - Given a mapping filename appears as part of another filename, when saved, then the larger filename is not partially rewritten.
 * - Given no attachment mappings are supplied, when saved, then content is persisted unchanged and media locations are refreshed.
 *
 * Observable outcomes:
 * - Captured content and timestamp passed to MemoSynchronizer.saveMemo.
 * - Recorded save/refresh side-effect ordering.
 *
 * TDD proof:
 * - RED command: ./kotlin test --include-classes='com.lomo.data.share.ShareIncomingMemoSaverTest'
 * - RED failure: the inline-title/reference-definition scenario saved photo.png instead of stored/photo_1.png.
 * - Why RED proves the behavior was missing: the pre-fix remapper treated the whole parenthesized body as the
 *   destination and did not rewrite reference definitions, so valid Markdown attachment references still pointed at
 *   sender-side filenames after persistence.
 * - RED failure: the indented-code scenario saved stored/photo_1.png inside four-space and tab-indented code lines.
 * - Why RED proves the behavior was missing: the pre-fix remapper skipped fenced code only, so it treated Markdown
 *   indented code block lines as normal link/reference-definition lines and destructively rewrote code samples.
 * - RED failure: the fenced-code context scenario remapped ![still code](photo.png) after a four-space indented
 *   fence-looking line inside a fence, and left a paragraph continuation line pointing at photo.png.
 * - Why RED proves the behavior was missing: the remapper accepted indented fence-looking content as a fence boundary
 *   and classified every four-space line as indented code without considering paragraph context.
 * - RED failure: the list/heading block-boundary scenario left a list continuation target pointing at photo.png and
 *   rewrote a heading-followed top-level indented code line to stored/photo_1.png.
 * - Why RED proves the behavior was missing: the remapper tracked only whether indented code could start, not the
 *   previous Markdown block context needed to distinguish container continuation from top-level code.
 * - RED failure: the list nested-code scenario rewrote a six-space list item code block target to stored/photo_1.png.
 * - Why RED proves the behavior was missing: the remapper kept list container context but did not track the list
 *   content indent needed to distinguish list continuation paragraphs from nested indented code blocks.
 *
 * Excludes:
 * - LAN transport protocol, attachment file storage implementation, full Markdown AST coverage, and UI state handling.
 */
class ShareIncomingMemoSaverTest : DataFunSpec() {
    private lateinit var synchronizer: MemoSynchronizer
    private lateinit var mediaRepository: RecordingMediaRepository
    private lateinit var saver: ShareIncomingMemoSaver
    private lateinit var savedMemos: MutableList<SavedMemo>
    private lateinit var sideEffects: MutableList<String>

    init {
        beforeTest {
            savedMemos = mutableListOf()
            sideEffects = mutableListOf()
            synchronizer = mockk()
            mediaRepository = RecordingMediaRepository(sideEffects)
            coEvery { synchronizer.saveMemo(any(), any(), any()) } coAnswers {
                val savedContent: String = firstArg()
                val savedTimestamp: Long = secondArg()
                savedMemos += SavedMemo(savedContent, savedTimestamp)
                sideEffects += "save"
                Memo(
                    id = "shared-$savedTimestamp",
                    timestamp = savedTimestamp,
                    content = savedContent,
                    rawContent = savedContent,
                    dateKey = "",
                )
            }
            saver =
                ShareIncomingMemoSaver(
                    synchronizer = synchronizer,
                    mediaRepository = mediaRepository,
                )
        }

        test("given inbound attachment mappings when memo is saved then markdown targets are remapped without rewriting text") {
            runTest {
                val content =
                    """
                    photo.png should stay as ordinary body text.
                    `photo.png` inline code should stay.
                    ```text
                    photo.png in a code fence should stay.
                    ```
                    ![photo.png visible alt stays](photo.png)
                    [audio visible voice.m4a stays](voice.m4a)
                    [relative image](attachments/photo.png)
                    [space path](media/my photo (final).png)
                    Plain URL text https://example.test/photo.png should stay.
                    Partial filename my-photo.png.backup should stay.
                    """.trimIndent()
                val expectedContent =
                    """
                    photo.png should stay as ordinary body text.
                    `photo.png` inline code should stay.
                    ```text
                    photo.png in a code fence should stay.
                    ```
                    ![photo.png visible alt stays](stored/photo_1.png)
                    [audio visible voice.m4a stays](stored/voice_2.m4a)
                    [relative image](stored/photo_1.png)
                    [space path](stored/my photo (final)_1.png)
                    Plain URL text https://example.test/photo.png should stay.
                    Partial filename my-photo.png.backup should stay.
                    """.trimIndent()

                saver.saveReceivedMemo(
                    content = content,
                    timestamp = 123L,
                    attachmentMappings =
                        mapOf(
                            "photo.png" to "stored/photo_1.png",
                            "voice.m4a" to "stored/voice_2.m4a",
                            "my photo (final).png" to "stored/my photo (final)_1.png",
                        ),
                )

                assertSoftly {
                    savedMemos shouldBe listOf(SavedMemo(expectedContent, 123L))
                    sideEffects shouldBe listOf("save", "refresh")
                    mediaRepository.refreshCount shouldBe 1
                }
            }
        }

        test("given markdown targets include titles and reference definitions when memo is saved then only destinations are remapped") {
            runTest {
                val content =
                    """
                    ![inline alt](photo.png "inline caption")
                    ![referenced alt][photo-ref]
                    [photo-ref]: photo.png "reference caption"
                    `![code alt](photo.png "code caption")`
                    ```markdown
                    ![fenced alt](photo.png "fenced caption")
                    [photo-ref]: photo.png "fenced caption"
                    ```
                    """.trimIndent()
                val expectedContent =
                    """
                    ![inline alt](stored/photo_1.png "inline caption")
                    ![referenced alt][photo-ref]
                    [photo-ref]: stored/photo_1.png "reference caption"
                    `![code alt](photo.png "code caption")`
                    ```markdown
                    ![fenced alt](photo.png "fenced caption")
                    [photo-ref]: photo.png "fenced caption"
                    ```
                    """.trimIndent()

                saver.saveReceivedMemo(
                    content = content,
                    timestamp = 789L,
                    attachmentMappings = mapOf("photo.png" to "stored/photo_1.png"),
                )

                assertSoftly {
                    savedMemos shouldBe listOf(SavedMemo(expectedContent, 789L))
                    sideEffects shouldBe listOf("save", "refresh")
                    mediaRepository.refreshCount shouldBe 1
                }
            }
        }

        test("given indented code contains markdown attachment syntax when memo is saved then code text is preserved and body links remap") {
            runTest {
                val content =
                    listOf(
                        "Outside inline: ![outside](photo.png)",
                        "Outside referenced: ![outside ref][outside-ref]",
                        "   [outside-ref]: photo.png \"outside caption\"",
                        "",
                        "    ![sample](photo.png)",
                        "    [photo-ref]: photo.png \"caption\"",
                        "\t![tab sample](photo.png)",
                        "\t[tab-photo-ref]: photo.png \"caption\"",
                    ).joinToString("\n")
                val expectedContent =
                    listOf(
                        "Outside inline: ![outside](stored/photo_1.png)",
                        "Outside referenced: ![outside ref][outside-ref]",
                        "   [outside-ref]: stored/photo_1.png \"outside caption\"",
                        "",
                        "    ![sample](photo.png)",
                        "    [photo-ref]: photo.png \"caption\"",
                        "\t![tab sample](photo.png)",
                        "\t[tab-photo-ref]: photo.png \"caption\"",
                    ).joinToString("\n")

                saver.saveReceivedMemo(
                    content = content,
                    timestamp = 890L,
                    attachmentMappings = mapOf("photo.png" to "stored/photo_1.png"),
                )

                assertSoftly {
                    savedMemos shouldBe listOf(SavedMemo(expectedContent, 890L))
                    sideEffects shouldBe listOf("save", "refresh")
                    mediaRepository.refreshCount shouldBe 1
                }
            }
        }

        test("given markdown block context when memo is saved then fences and indented continuation lines are remapped by block semantics") {
            runTest {
                val content =
                    listOf(
                        "```markdown",
                        "    ```",
                        "![still code](photo.png)",
                        "```",
                        "Paragraph continues here",
                        "    ![continuation](photo.png)",
                        "Outside referenced: ![outside ref][outside-ref]",
                        "   [outside-ref]: photo.png \"outside caption\"",
                        "",
                        "    ![top-level code](photo.png)",
                    ).joinToString("\n")
                val expectedContent =
                    listOf(
                        "```markdown",
                        "    ```",
                        "![still code](photo.png)",
                        "```",
                        "Paragraph continues here",
                        "    ![continuation](stored/photo_1.png)",
                        "Outside referenced: ![outside ref][outside-ref]",
                        "   [outside-ref]: stored/photo_1.png \"outside caption\"",
                        "",
                        "    ![top-level code](photo.png)",
                    ).joinToString("\n")

                saver.saveReceivedMemo(
                    content = content,
                    timestamp = 901L,
                    attachmentMappings = mapOf("photo.png" to "stored/photo_1.png"),
                )

                assertSoftly {
                    savedMemos shouldBe listOf(SavedMemo(expectedContent, 901L))
                    sideEffects shouldBe listOf("save", "refresh")
                    mediaRepository.refreshCount shouldBe 1
                }
            }
        }

        test("given list continuation and heading followed by indented code when memo is saved then block context matches markdown parser") {
            runTest {
                val content =
                    listOf(
                        "- item",
                        "",
                        "    ![continuation](photo.png)",
                        "",
                        "- code item",
                        "",
                        "      ![nested code](photo.png)",
                        "",
                        "# Example",
                        "    ![sample](photo.png)",
                    ).joinToString("\n")
                val expectedContent =
                    listOf(
                        "- item",
                        "",
                        "    ![continuation](stored/photo_1.png)",
                        "",
                        "- code item",
                        "",
                        "      ![nested code](photo.png)",
                        "",
                        "# Example",
                        "    ![sample](photo.png)",
                    ).joinToString("\n")

                saver.saveReceivedMemo(
                    content = content,
                    timestamp = 902L,
                    attachmentMappings = mapOf("photo.png" to "stored/photo_1.png"),
                )

                assertSoftly {
                    savedMemos shouldBe listOf(SavedMemo(expectedContent, 902L))
                    sideEffects shouldBe listOf("save", "refresh")
                    mediaRepository.refreshCount shouldBe 1
                }
            }
        }

        test("given no attachment mappings when memo is saved then content remains unchanged") {
            runTest {
                saver.saveReceivedMemo(
                    content = "plain incoming memo",
                    timestamp = 456L,
                    attachmentMappings = emptyMap(),
                )

                assertSoftly {
                    savedMemos shouldBe listOf(SavedMemo("plain incoming memo", 456L))
                    sideEffects shouldBe listOf("save", "refresh")
                    mediaRepository.refreshCount shouldBe 1
                }
            }
        }
    }

    private data class SavedMemo(
        val content: String,
        val timestamp: Long,
    )

    private class RecordingMediaRepository(
        private val sideEffects: MutableList<String>,
    ) : MediaRepository {
        var refreshCount: Int = 0
            private set

        override suspend fun refreshImageLocations() {
            refreshCount += 1
            sideEffects += "refresh"
        }

        override suspend fun importImage(source: StorageLocation): StorageLocation = unsupported()

        override suspend fun removeImage(entryId: MediaEntryId) = unsupported()

        override fun observeImageLocations(): Flow<Map<MediaEntryId, StorageLocation>> = emptyFlow()

        override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? = unsupported()

        override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation = unsupported()

        override suspend fun removeVoiceCapture(entryId: MediaEntryId) = unsupported()

        private fun unsupported(): Nothing = error("Not used by ShareIncomingMemoSaver")
    }
}
