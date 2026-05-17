package com.lomo.data.repository


import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.webdav.WebDavClient
import com.lomo.data.webdav.WebDavRemoteFile
import com.lomo.data.webdav.WebDavRemoteResource
import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncReason
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Test Contract:
 * - Unit under test: WebDAV initial overlap classification.
 * - Behavior focus: when timestamps already prove which side is newer, initial sync should not download remote bytes just to compute a fingerprint.
 * - Observable outcomes: resolved action direction/reason and zero WebDavClient.get() calls.
 * - Red phase: Fails before the fix because overlapping files with local fingerprints always trigger a GET, even when timestamps are far outside the conflict tolerance window.
 * - Excludes: DAV transport internals, metadata persistence, and later upload/download execution.
 */
class WebDavInitialSyncClassificationTest : DataFunSpec() {
    init {
        test("classification skips remote fingerprint download when timestamps already pick local upload") { `classification skips remote fingerprint download when timestamps already pick local upload`() }

        test("classification downloads conflicting remote fingerprints concurrently") { `classification downloads conflicting remote fingerprints concurrently`() }

        test("classification trusts matching WebDAV etag fingerprint before downloading bytes") { `classification trusts matching WebDAV etag fingerprint before downloading bytes`() }

        test("classification keeps non memo overlap unresolved without downloading remote bytes") { `classification keeps non memo overlap unresolved without downloading remote bytes`() }
    }


    private fun `classification skips remote fingerprint download when timestamps already pick local upload`() =
        runTest {
            val path = "lomo/memos/note.md"
            val client =
                object : WebDavClient {
                    var getCalls = 0

                    override fun ensureDirectory(path: String) = Unit

                    override fun list(path: String): List<WebDavRemoteResource> = emptyList()

                    override fun get(path: String): WebDavRemoteFile {
                        getCalls += 1
                        error("classification should not download remote bytes when timestamps already pick upload")
                    }

                    override fun put(
                        path: String,
                        bytes: ByteArray,
                        contentType: String,
                        lastModifiedHint: Long?,
                        expectedEtag: String?,
                        requireAbsent: Boolean,
                    ) = Unit

                    override fun delete(
                        path: String,
                        expectedEtag: String?,
                    ) = Unit

                    override fun testConnection() = Unit
                }

            val classification =
                classifyWebDavInitialOverlaps(
                    localFiles =
                        mapOf(
                            path to
                                LocalWebDavFile(
                                    path = path,
                                    lastModified = 5_000L,
                                    localFingerprint = "local-md5",
                                ),
                        ),
                    remoteFiles =
                        mapOf(
                            path to
                                RemoteWebDavFile(
                                    path = path,
                                    etag = "etag-remote",
                                    lastModified = 1_000L,
                                ),
                        ),
                    metadataByPath = emptyMap(),
                    client = client,
                    layout = SyncDirectoryLayout(memoFolder = "memos", imageFolder = "images", voiceFolder = "voice", allSameDirectory = false),
                    timestampToleranceMs = 1_000L,
                )

            client.getCalls shouldBe 0
            classification.resolvedActionsByPath shouldBe mapOf(path to WebDavSyncAction(path, WebDavSyncDirection.UPLOAD, WebDavSyncReason.LOCAL_ONLY))
        }

    private fun `classification downloads conflicting remote fingerprints concurrently`() =
        runTest {
            val paths =
                listOf(
                    "lomo/memos/alpha.md",
                    "lomo/memos/bravo.md",
                    "lomo/memos/charlie.md",
                )
            val content = "same-content"
            val contentBytes = content.toByteArray()
            val getCalls = AtomicInteger(0)
            val inFlight = AtomicInteger(0)
            val peakConcurrency = AtomicInteger(0)
            val client =
                object : WebDavClient {
                    override fun ensureDirectory(path: String) = Unit

                    override fun list(path: String): List<WebDavRemoteResource> = emptyList()

                    override fun get(path: String): WebDavRemoteFile {
                        getCalls.incrementAndGet()
                        val concurrent = inFlight.incrementAndGet()
                        peakConcurrency.accumulateAndGet(concurrent, ::maxOf)
                        Thread.sleep(25)
                        inFlight.decrementAndGet()
                        return WebDavRemoteFile(path, contentBytes, "etag-$path", 1_000L)
                    }

                    override fun put(
                        path: String,
                        bytes: ByteArray,
                        contentType: String,
                        lastModifiedHint: Long?,
                        expectedEtag: String?,
                        requireAbsent: Boolean,
                    ) = Unit

                    override fun delete(
                        path: String,
                        expectedEtag: String?,
                    ) = Unit

                    override fun testConnection() = Unit
                }

            val classification =
                classifyWebDavInitialOverlaps(
                    localFiles =
                        paths.associateWith { path ->
                            LocalWebDavFile(
                                path = path,
                                lastModified = 1_000L,
                                localFingerprint = contentBytes.md5Hex(),
                            )
                        },
                    remoteFiles =
                        paths.associateWith { path ->
                            RemoteWebDavFile(
                                path = path,
                                etag = "etag-$path",
                                lastModified = 1_000L,
                            )
                        },
                    metadataByPath = emptyMap(),
                    client = client,
                    layout = SyncDirectoryLayout(memoFolder = "memos", imageFolder = "images", voiceFolder = "voice", allSameDirectory = false),
                    timestampToleranceMs = 1_000L,
                )

            getCalls.get() shouldBe paths.size
            classification.equivalentMetadataByPath.keys.toList() shouldBe paths
            withClue("Initial WebDAV overlap downloads should overlap for independent files instead of serializing all GET requests.") { (peakConcurrency.get() > 1).shouldBeTrue() }
        }

    private fun `classification trusts matching WebDAV etag fingerprint before downloading bytes`() =
        runTest {
            val path = "lomo/memos/note.md"
            val contentBytes = "same-content".toByteArray()
            val client =
                object : WebDavClient {
                    var getCalls = 0

                    override fun ensureDirectory(path: String) = Unit

                    override fun list(path: String): List<WebDavRemoteResource> = emptyList()

                    override fun get(path: String): WebDavRemoteFile {
                        getCalls += 1
                        error("matching etag fingerprint should avoid downloading remote bytes")
                    }

                    override fun put(
                        path: String,
                        bytes: ByteArray,
                        contentType: String,
                        lastModifiedHint: Long?,
                        expectedEtag: String?,
                        requireAbsent: Boolean,
                    ) = Unit

                    override fun delete(
                        path: String,
                        expectedEtag: String?,
                    ) = Unit

                    override fun testConnection() = Unit
                }

            val classification =
                classifyWebDavInitialOverlaps(
                    localFiles =
                        mapOf(
                            path to
                                LocalWebDavFile(
                                    path = path,
                                    lastModified = 1_000L,
                                    size = contentBytes.size.toLong(),
                                    localFingerprint = contentBytes.md5Hex(),
                                ),
                        ),
                    remoteFiles =
                        mapOf(
                            path to
                                RemoteWebDavFile(
                                    path = path,
                                    etag = "\"${contentBytes.md5Hex()}\"",
                                    lastModified = 1_000L,
                                    size = contentBytes.size.toLong(),
                                ),
                        ),
                    metadataByPath = emptyMap(),
                    client = client,
                    layout = SyncDirectoryLayout(memoFolder = "memos", imageFolder = "images", voiceFolder = "voice", allSameDirectory = false),
                    timestampToleranceMs = 1_000L,
                )

            client.getCalls shouldBe 0
            classification.equivalentMetadataByPath.keys.toList() shouldBe listOf(path)
        }

    private fun `classification keeps non memo overlap unresolved without downloading remote bytes`() =
        runTest {
            val path = "lomo/images/cover.png"
            val client =
                object : WebDavClient {
                    var getCalls = 0

                    override fun ensureDirectory(path: String) = Unit

                    override fun list(path: String): List<WebDavRemoteResource> = emptyList()

                    override fun get(path: String): WebDavRemoteFile {
                        getCalls += 1
                        error("non-memo overlap fallback should not download full remote bytes")
                    }

                    override fun put(
                        path: String,
                        bytes: ByteArray,
                        contentType: String,
                        lastModifiedHint: Long?,
                        expectedEtag: String?,
                        requireAbsent: Boolean,
                    ) = Unit

                    override fun delete(
                        path: String,
                        expectedEtag: String?,
                    ) = Unit

                    override fun testConnection() = Unit
                }

            val classification =
                classifyWebDavInitialOverlaps(
                    localFiles =
                        mapOf(
                            path to
                                LocalWebDavFile(
                                    path = path,
                                    lastModified = 1_000L,
                                    size = 4L,
                                    localFingerprint = "local-md5",
                                ),
                        ),
                    remoteFiles =
                        mapOf(
                            path to
                                RemoteWebDavFile(
                                    path = path,
                                    etag = null,
                                    lastModified = 1_000L,
                                    size = 4L,
                                ),
                        ),
                    metadataByPath = emptyMap(),
                    client = client,
                    layout = SyncDirectoryLayout(memoFolder = "memos", imageFolder = "images", voiceFolder = "voice", allSameDirectory = false),
                    timestampToleranceMs = 1_000L,
                )

            client.getCalls shouldBe 0
            (classification.equivalentMetadataByPath.isEmpty()).shouldBeTrue()
            (classification.resolvedActionsByPath.isEmpty()).shouldBeTrue()
        }
}
