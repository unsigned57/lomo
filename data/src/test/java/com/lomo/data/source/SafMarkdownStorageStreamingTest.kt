package com.lomo.data.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: SAF markdown metadata streaming.
 * - Owning layer: data storage backend.
 * - Priority tier: P0.
 * - Capability: expose SAF metadata through the streaming storage contract without requiring callers
 *   to fall back to whole-list metadata discovery.
 *
 * Scenarios:
 * - Given SAF root access is unavailable, when main metadata is streamed, then the stream completes
 *   as an empty storage state instead of using list-backed fallback behavior.
 * - Given the SAF trash directory is unavailable, when trash metadata is streamed, then the stream
 *   completes as an empty trash state instead of using list-backed fallback behavior.
 *
 * Observable outcomes:
 * - collected stream metadata entries.
 *
 * TDD proof:
 * - Fails before the fix because SafMarkdownStorageBackendDelegate inherits the list-backed
 *   streamMetadataWithIdsIn default instead of owning SAF streaming.
 *
 * Excludes:
 * - Android provider cursor mechanics, recursive SAF path resolution, and direct file storage.
 */
class SafMarkdownStorageStreamingTest : DataFunSpec() {
    init {
        afterTest {
            unmockkStatic(DocumentFile::class)
        }

        test("given unavailable saf root when main metadata streams then empty state is emitted without list fallback") {
            runTest {
                val context = mockk<Context>()
                val rootUri = mockk<Uri>()
                mockkStatic(DocumentFile::class)
                every { DocumentFile.fromTreeUri(context, rootUri) } returns null
                val backend =
                    SafMarkdownStorageBackendDelegate(
                        context = context,
                        rootUri = rootUri,
                        documentAccess = SafDocumentAccess(context, rootUri),
                    )

                backend.streamMetadataWithIdsIn(MemoDirectoryType.MAIN).toList() shouldBe emptyList()
            }
        }

        test("given unavailable saf trash when trash metadata streams then empty state is emitted without list fallback") {
            runTest {
                val context = mockk<Context>()
                val rootUri = mockk<Uri>()
                mockkStatic(DocumentFile::class)
                every { DocumentFile.fromTreeUri(context, rootUri) } returns null
                val backend =
                    SafMarkdownStorageBackendDelegate(
                        context = context,
                        rootUri = rootUri,
                        documentAccess = SafDocumentAccess(context, rootUri),
                    )

                backend.streamMetadataWithIdsIn(MemoDirectoryType.TRASH).toList() shouldBe emptyList()
            }
        }
    }
}
