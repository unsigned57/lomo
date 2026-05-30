package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.usecase.MigrationPasswordException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * Behavior Contract:
 * - Unit under test: MigrationArchiveRepositoryImpl
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: persist and restore complete note archives plus encrypted settings migration files.
 *
 * Scenarios:
 * - Given main notes, trash notes, image media, and voice media, when exporting all notes, then the zip contains stable entries for each logical bucket.
 * - Given workspace media is exported, when the archive is built, then media is listed by filename and copied through stream readers without byte-array library listing.
 * - Given a migration zip, when importing all notes, then markdown and media files are restored to the current workspace buckets.
 * - Given direct stream restore fails after partial output, when the write is aborted, then the existing destination remains intact and staged bytes are removed.
 * - Given direct media writes or deletes receive path-like filenames, when the access boundary validates them, then files outside the workspace are not changed.
 * - Given a direct workspace has a requested media file plus an unreadable sibling, when reading by filename, then only the requested file is read.
 * - Given migration media is imported into a stream-capable workspace backend, when zip entries are restored, then media bytes are streamed through the backend output.
 * - Given a migration zip is missing a manifest or has manifest counts that do not match payload entries, when importing all notes, then restore fails before workspace files are written.
 * - Given dry-run manifest validation receives unsupported versions, duplicate entries, unsafe names, unsupported prefixes, or media type mismatches, when inspection or import runs, then validation fails before workspace writes.
 * - Given a migration zip exceeds manifest, entry-count, archive-size, uncompressed-size, markdown-text, or compression-ratio budgets, when inspection or import runs, then validation fails before workspace writes.
 * - Given archive commit fails after staging notes or media, when import aborts, then previously staged files are rolled back from the live workspace.
 * - Given settings with sensitive values, when exporting and importing with a password, then bytes are encrypted and the same snapshot is restored.
 * - Given encrypted settings and a wrong password, when importing, then the password failure is observable and no settings are restored.
 * - Given decrypted settings fail dry-run validation, when importing, then restore is not committed.
 *
 * Observable outcomes:
 * - ZIP entry names/content, streamed export reads, direct file bytes, preserved destination bytes, rejected escaped paths, restored fake storage state, stream write path, rejected invalid manifests/resource budgets with unchanged storage, encrypted payload text, restored settings snapshot, and thrown exception type.
 *
 * TDD proof:
 * - RED: stream-first export fails before WorkspaceMediaAccess exposes streamed reads and archive export stops using byte-array media listing.
 * - RED: atomic direct stream restore fails before media writes stage to a temporary sibling and commit after source success.
 * - RED: escaped direct write/delete tests fail before WorkspaceMediaAccess validates filenames at the media boundary.
 * - RED: manifest validation tests fail before archive import parses the manifest and validates payload counts before writes.
 * - RED: media payload mismatch dry-run fails before archive validation rejects image entries without image extensions and voice entries without audio extensions.
 * - RED: ZIP budget tests fail before archive import has an injectable resource budget and bounded manifest/markdown readers.
 * - RED: import rollback test fails before archive commit stages all note/media writes and removes staged writes after a later commit failure.
 * - RED: settings dry-run test fails before MigrationSettingsStore exposes pre-commit validation and import calls it before restore.
 *
 * Excludes:
 * - Android document picker UI, SAF permission persistence, database rebuild orchestration, and cryptographic algorithm conformance beyond round-trip/password failure.
 */
class MigrationArchiveRepositoryImplTest : DataFunSpec() {
    init {
        test("given workspace files when all notes are exported then zip contains notes trash images and voice") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                markdownStorage.files.getValue(MemoDirectoryType.MAIN)["2026-05-20.md"] = "- 10:00 main"
                markdownStorage.files.getValue(MemoDirectoryType.TRASH)["2026-05-19.md"] = "- 09:00 trash"
                val mediaAccess = FakeWorkspaceMediaAccess()
                mediaAccess.files.getValue(WorkspaceMediaCategory.IMAGE)["cover.png"] = "image-bytes".toByteArray()
                mediaAccess.files.getValue(WorkspaceMediaCategory.VOICE)["voice.m4a"] = "voice-bytes".toByteArray()
                val repository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = markdownStorage,
                        workspaceMediaAccess = mediaAccess,
                        settingsStore = FakeMigrationSettingsStore(),
                        importBudgets = testArchiveBudgets(),
                    )
                val output = ByteArrayOutputStream()

                val summary = repository.exportAllNotesArchive(output)

                summary.noteCount shouldBe 1
                summary.trashCount shouldBe 1
                summary.imageCount shouldBe 1
                summary.voiceCount shouldBe 1
                val entries = readZipEntries(output.toByteArray())
                entries.keys shouldContainAll
                    listOf(
                        "manifest.json",
                        "notes/main/2026-05-20.md",
                        "notes/trash/2026-05-19.md",
                        "media/images/cover.png",
                        "media/voice/voice.m4a",
                    )
                entries["notes/main/2026-05-20.md"]?.toString(Charsets.UTF_8) shouldBe "- 10:00 main"
                entries["notes/trash/2026-05-19.md"]?.toString(Charsets.UTF_8) shouldBe "- 09:00 trash"
                entries["media/images/cover.png"]?.toString(Charsets.UTF_8) shouldBe "image-bytes"
                entries["media/voice/voice.m4a"]?.toString(Charsets.UTF_8) shouldBe "voice-bytes"
            }
        }

        test("given workspace media when all notes are exported then media files are streamed into the archive") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                markdownStorage.files.getValue(MemoDirectoryType.MAIN)["2026-05-20.md"] = "- 10:00 main"
                val mediaAccess = StreamReadableWorkspaceMediaAccess()
                mediaAccess.files.getValue(WorkspaceMediaCategory.IMAGE)["cover.png"] = "image-bytes".toByteArray()
                mediaAccess.files.getValue(WorkspaceMediaCategory.VOICE)["voice.m4a"] = "voice-bytes".toByteArray()
                val repository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = markdownStorage,
                        workspaceMediaAccess = mediaAccess,
                        settingsStore = FakeMigrationSettingsStore(),
                        importBudgets = testArchiveBudgets(),
                    )
                val output = ByteArrayOutputStream()

                val summary = repository.exportAllNotesArchive(output)

                summary.noteCount shouldBe 1
                summary.trashCount shouldBe 0
                summary.imageCount shouldBe 1
                summary.voiceCount shouldBe 1
                mediaAccess.legacyListAttempted shouldBe false
                mediaAccess.streamedReads shouldBe
                    listOf(
                        WorkspaceMediaCategory.IMAGE to "cover.png",
                        WorkspaceMediaCategory.VOICE to "voice.m4a",
                    )
                val entries = readZipEntries(output.toByteArray())
                entries["media/images/cover.png"]?.toString(Charsets.UTF_8) shouldBe "image-bytes"
                entries["media/voice/voice.m4a"]?.toString(Charsets.UTF_8) shouldBe "voice-bytes"
            }
        }

        test("given migration zip when imported then workspace buckets receive matching files") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                val mediaAccess = FakeWorkspaceMediaAccess()
                val repository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = markdownStorage,
                        workspaceMediaAccess = mediaAccess,
                        settingsStore = FakeMigrationSettingsStore(),
                        importBudgets = testArchiveBudgets(),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":1,"trashCount":1,"imageCount":1,"voiceCount":1}
                            """.trimIndent().toByteArray(),
                        "notes/main/imported.md" to "- 11:00 imported".toByteArray(),
                        "notes/trash/deleted.md" to "- 12:00 deleted".toByteArray(),
                        "media/images/imported.png" to "image".toByteArray(),
                        "media/voice/imported.m4a" to "voice".toByteArray(),
                    )

                val summary = repository.importAllNotesArchive(ByteArrayInputStream(archive))

                summary.noteCount shouldBe 1
                summary.trashCount shouldBe 1
                summary.imageCount shouldBe 1
                summary.voiceCount shouldBe 1
                markdownStorage.files.getValue(MemoDirectoryType.MAIN) shouldContainExactly
                    mapOf("imported.md" to "- 11:00 imported")
                markdownStorage.files.getValue(MemoDirectoryType.TRASH) shouldContainExactly
                    mapOf("deleted.md" to "- 12:00 deleted")
                mediaAccess.files.getValue(WorkspaceMediaCategory.IMAGE).keys shouldBe setOf("imported.png")
                mediaAccess.files.getValue(WorkspaceMediaCategory.IMAGE).getValue("imported.png")
                    .toString(Charsets.UTF_8) shouldBe "image"
                mediaAccess.files.getValue(WorkspaceMediaCategory.VOICE).keys shouldBe setOf("imported.m4a")
                mediaAccess.files.getValue(WorkspaceMediaCategory.VOICE).getValue("imported.m4a")
                    .toString(Charsets.UTF_8) shouldBe "voice"
            }
        }

        test("given direct workspace root when reading one media file then unreadable siblings are not loaded") {
            runTest {
                val imageRoot = Files.createTempDirectory("workspace-media-direct-read").toFile()
                File(imageRoot, "target.png").writeText("target-bytes")
                val unreadableSibling = File(imageRoot, "blocked.png").apply {
                    writeText("should-not-be-read")
                    setReadable(false, false)
                }
                val configSource =
                    FakeWorkspaceConfigSource(
                        roots = mapOf(StorageRootType.IMAGE to imageRoot.absolutePath),
                    )
                val mediaAccess =
                    DefaultWorkspaceMediaAccess(
                        context = mockk(),
                        workspaceConfigSource = configSource,
                    )

                val destination = ByteArrayOutputStream()
                val found =
                    mediaAccess.readFileToStream(
                        WorkspaceMediaCategory.IMAGE,
                        "target.png",
                        destination,
                    )

                found shouldBe true
                destination.toString(Charsets.UTF_8) shouldBe "target-bytes"
                unreadableSibling.canRead().shouldBeFalse()
            }
        }

        test("given direct stream restore fails after partial media output then the existing file remains intact") {
            runTest {
                val voiceRoot = Files.createTempDirectory("workspace-media-direct-atomic").toFile()
                val existing = File(voiceRoot, "voice.m4a").apply { writeText("original-voice") }
                val mediaAccess =
                    DefaultWorkspaceMediaAccess(
                        context = mockk(),
                        workspaceConfigSource =
                            FakeWorkspaceConfigSource(
                                roots = mapOf(StorageRootType.VOICE to voiceRoot.absolutePath),
                            ),
                    )

                shouldThrow<IOException> {
                    mediaAccess.writeFileFromStream(
                        category = WorkspaceMediaCategory.VOICE,
                        filename = "voice.m4a",
                    ) { output ->
                        output.write("partial".toByteArray())
                        throw IOException("truncated archive entry")
                    }
                }

                existing.readText() shouldBe "original-voice"
                voiceRoot.listFiles()?.map { it.name }?.sorted() shouldBe listOf("voice.m4a")
            }
        }

        test("given direct media write receives an escaped filename then files outside the workspace are not changed") {
            runTest {
                val imageRoot = Files.createTempDirectory("workspace-media-direct-write-boundary").toFile()
                val outside = File(imageRoot.parentFile, "${imageRoot.name}-outside.png").apply { writeText("outside") }
                val mediaAccess =
                    DefaultWorkspaceMediaAccess(
                        context = mockk(),
                        workspaceConfigSource =
                            FakeWorkspaceConfigSource(
                                roots = mapOf(StorageRootType.IMAGE to imageRoot.absolutePath),
                            ),
                    )

                shouldThrow<IllegalArgumentException> {
                    mediaAccess.writeFileFromStream(
                        category = WorkspaceMediaCategory.IMAGE,
                        filename = "../${outside.name}",
                    ) { output -> output.write("replacement".toByteArray()) }
                }

                outside.readText() shouldBe "outside"
                imageRoot.listFiles()?.toList().orEmpty() shouldBe emptyList()
            }
        }

        test("given direct media delete receives an escaped filename then files outside the workspace are not changed") {
            runTest {
                val imageRoot = Files.createTempDirectory("workspace-media-direct-delete-boundary").toFile()
                val outside = File(imageRoot.parentFile, "${imageRoot.name}-outside.png").apply { writeText("outside") }
                val mediaAccess =
                    DefaultWorkspaceMediaAccess(
                        context = mockk(),
                        workspaceConfigSource =
                            FakeWorkspaceConfigSource(
                                roots = mapOf(StorageRootType.IMAGE to imageRoot.absolutePath),
                            ),
                    )

                shouldThrow<IllegalArgumentException> {
                    mediaAccess.deleteFile(
                        category = WorkspaceMediaCategory.IMAGE,
                        filename = "../${outside.name}",
                    )
                }

                outside.readText() shouldBe "outside"
            }
        }

        test("given migration zip with media when imported then workspace media is streamed without legacy byte-array write") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                val mediaAccess = StreamOnlyWorkspaceMediaAccess()
                val repository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = markdownStorage,
                        workspaceMediaAccess = mediaAccess,
                        settingsStore = FakeMigrationSettingsStore(),
                        importBudgets = testArchiveBudgets(),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":0,"trashCount":0,"imageCount":1,"voiceCount":1}
                            """.trimIndent().toByteArray(),
                        "media/images/imported.png" to "image".toByteArray(),
                        "media/voice/imported.m4a" to "voice".toByteArray(),
                    )

                val summary = repository.importAllNotesArchive(ByteArrayInputStream(archive))

                summary.noteCount shouldBe 0
                summary.trashCount shouldBe 0
                summary.imageCount shouldBe 1
                summary.voiceCount shouldBe 1
                mediaAccess.files.getValue(WorkspaceMediaCategory.IMAGE).getValue("imported.png")
                    .toString(Charsets.UTF_8) shouldBe "image"
                mediaAccess.files.getValue(WorkspaceMediaCategory.VOICE).getValue("imported.m4a")
                    .toString(Charsets.UTF_8) shouldBe "voice"
            }
        }

        test("given migration zip without manifest when imported then workspace files remain unchanged") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                markdownStorage.files.getValue(MemoDirectoryType.MAIN)["existing.md"] = "existing"
                val mediaAccess = FakeWorkspaceMediaAccess()
                mediaAccess.files.getValue(WorkspaceMediaCategory.IMAGE)["existing.png"] = "existing-image".toByteArray()
                val repository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = markdownStorage,
                        workspaceMediaAccess = mediaAccess,
                        settingsStore = FakeMigrationSettingsStore(),
                        importBudgets = testArchiveBudgets(),
                    )
                val archive =
                    buildZip(
                        "notes/main/imported.md" to "- 11:00 imported".toByteArray(),
                        "media/images/imported.png" to "image".toByteArray(),
                    )

                shouldThrow<IllegalArgumentException> {
                    repository.importAllNotesArchive(ByteArrayInputStream(archive))
                }

                markdownStorage.files.getValue(MemoDirectoryType.MAIN) shouldContainExactly
                    mapOf("existing.md" to "existing")
                mediaAccess.files.getValue(WorkspaceMediaCategory.IMAGE).keys shouldBe setOf("existing.png")
                mediaAccess.files.getValue(WorkspaceMediaCategory.IMAGE).getValue("existing.png")
                    .toString(Charsets.UTF_8) shouldBe "existing-image"
            }
        }

        test("given migration zip with manifest count mismatch when imported then workspace files remain unchanged") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                markdownStorage.files.getValue(MemoDirectoryType.TRASH)["existing.md"] = "existing trash"
                val mediaAccess = FakeWorkspaceMediaAccess()
                val repository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = markdownStorage,
                        workspaceMediaAccess = mediaAccess,
                        settingsStore = FakeMigrationSettingsStore(),
                        importBudgets = testArchiveBudgets(),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":2,"trashCount":0,"imageCount":0,"voiceCount":0}
                            """.trimIndent().toByteArray(),
                        "notes/main/imported.md" to "- 11:00 imported".toByteArray(),
                    )

                shouldThrow<IllegalArgumentException> {
                    repository.importAllNotesArchive(ByteArrayInputStream(archive))
                }

                markdownStorage.files.getValue(MemoDirectoryType.MAIN) shouldBe emptyMap()
                markdownStorage.files.getValue(MemoDirectoryType.TRASH) shouldContainExactly
                    mapOf("existing.md" to "existing trash")
                mediaAccess.files.getValue(WorkspaceMediaCategory.IMAGE) shouldBe emptyMap()
                mediaAccess.files.getValue(WorkspaceMediaCategory.VOICE) shouldBe emptyMap()
            }
        }

        test("given migration zip with unsupported manifest version when inspected then validation fails") {
            runTest {
                val repository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = FakeMarkdownStorageDataSource(),
                        workspaceMediaAccess = FakeWorkspaceMediaAccess(),
                        settingsStore = FakeMigrationSettingsStore(),
                        importBudgets = testArchiveBudgets(),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":999,"noteCount":0,"trashCount":0,"imageCount":0,"voiceCount":0}
                            """.trimIndent().toByteArray(),
                    )

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        repository.inspectAllNotesArchive(ByteArrayInputStream(archive))
                    }

                failure.message.orEmpty() shouldContain "Unsupported migration archive version"
            }
        }

        test("given migration zip with oversized manifest when inspected then manifest budget rejects archive") {
            runTest {
                val repository =
                    migrationRepositoryWithBudgets(
                        markdownStorage = FakeMarkdownStorageDataSource(),
                        mediaAccess = FakeWorkspaceMediaAccess(),
                        budgets = testArchiveBudgets(maxManifestBytes = 64),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":0,"trashCount":0,"imageCount":0,"voiceCount":0,"padding":"${"x".repeat(128)}"}
                            """.trimIndent().toByteArray(),
                    )

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        repository.inspectAllNotesArchive(ByteArrayInputStream(archive))
                    }

                failure.message.orEmpty() shouldContain "manifest"
            }
        }

        test("given migration zip with too many entries when imported then workspace files remain unchanged") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                val repository =
                    migrationRepositoryWithBudgets(
                        markdownStorage = markdownStorage,
                        mediaAccess = FakeWorkspaceMediaAccess(),
                        budgets = testArchiveBudgets(maxEntries = 2),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":2,"trashCount":0,"imageCount":0,"voiceCount":0}
                            """.trimIndent().toByteArray(),
                        "notes/main/one.md" to "one".toByteArray(),
                        "notes/main/two.md" to "two".toByteArray(),
                    )

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        repository.importAllNotesArchive(ByteArrayInputStream(archive))
                    }

                failure.message.orEmpty() shouldContain "entry count"
                markdownStorage.files.getValue(MemoDirectoryType.MAIN) shouldBe emptyMap()
            }
        }

        test("given migration zip with oversized compressed archive when inspected then archive byte budget rejects it") {
            runTest {
                val repository =
                    migrationRepositoryWithBudgets(
                        markdownStorage = FakeMarkdownStorageDataSource(),
                        mediaAccess = FakeWorkspaceMediaAccess(),
                        budgets = testArchiveBudgets(maxCompressedArchiveBytes = 64),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":0,"trashCount":0,"imageCount":0,"voiceCount":0}
                            """.trimIndent().toByteArray(),
                    )

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        repository.inspectAllNotesArchive(ByteArrayInputStream(archive))
                    }

                failure.message.orEmpty() shouldContain "compressed"
            }
        }

        test("given migration zip exceeds total uncompressed budget when imported then workspace files remain unchanged") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                val repository =
                    migrationRepositoryWithBudgets(
                        markdownStorage = markdownStorage,
                        mediaAccess = FakeWorkspaceMediaAccess(),
                        budgets =
                            testArchiveBudgets(
                                maxUncompressedBytes = 128,
                                maxMarkdownEntryBytes = 512,
                                maxCompressionRatio = 1_000,
                            ),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":1,"trashCount":0,"imageCount":0,"voiceCount":0}
                            """.trimIndent().toByteArray(),
                        "notes/main/large.md" to "x".repeat(256).toByteArray(),
                    )

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        repository.importAllNotesArchive(ByteArrayInputStream(archive))
                    }

                failure.message.orEmpty() shouldContain "uncompressed"
                markdownStorage.files.getValue(MemoDirectoryType.MAIN) shouldBe emptyMap()
            }
        }

        test("given migration zip exceeds markdown entry budget when imported then workspace files remain unchanged") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                val repository =
                    migrationRepositoryWithBudgets(
                        markdownStorage = markdownStorage,
                        mediaAccess = FakeWorkspaceMediaAccess(),
                        budgets =
                            testArchiveBudgets(
                                maxUncompressedBytes = 1_024,
                                maxMarkdownEntryBytes = 64,
                                maxCompressionRatio = 1_000,
                            ),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":1,"trashCount":0,"imageCount":0,"voiceCount":0}
                            """.trimIndent().toByteArray(),
                        "notes/main/large.md" to "x".repeat(128).toByteArray(),
                    )

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        repository.importAllNotesArchive(ByteArrayInputStream(archive))
                    }

                failure.message.orEmpty() shouldContain "markdown"
                markdownStorage.files.getValue(MemoDirectoryType.MAIN) shouldBe emptyMap()
            }
        }

        test("given migration zip exceeds compression ratio budget when imported then workspace files remain unchanged") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                val repository =
                    migrationRepositoryWithBudgets(
                        markdownStorage = markdownStorage,
                        mediaAccess = FakeWorkspaceMediaAccess(),
                        budgets =
                            testArchiveBudgets(
                                maxUncompressedBytes = 64 * 1024,
                                maxMarkdownEntryBytes = 64 * 1024,
                                maxCompressionRatio = 1,
                            ),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":1,"trashCount":0,"imageCount":0,"voiceCount":0}
                            """.trimIndent().toByteArray(),
                        "notes/main/repeated.md" to "a".repeat(16 * 1024).toByteArray(),
                    )

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        repository.importAllNotesArchive(ByteArrayInputStream(archive))
                    }

                failure.message.orEmpty() shouldContain "compression ratio"
                markdownStorage.files.getValue(MemoDirectoryType.MAIN) shouldBe emptyMap()
            }
        }

        test("given migration zip with duplicate payload entries when imported then workspace files remain unchanged") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                val mediaAccess = FakeWorkspaceMediaAccess()
                val repository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = markdownStorage,
                        workspaceMediaAccess = mediaAccess,
                        settingsStore = FakeMigrationSettingsStore(),
                        importBudgets = testArchiveBudgets(),
                    )
                val archive =
                    buildZipWithLocalEntries(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":2,"trashCount":0,"imageCount":0,"voiceCount":0}
                            """.trimIndent().toByteArray(),
                        "notes/main/duplicate.md" to "first".toByteArray(),
                        "notes/main/duplicate.md" to "second".toByteArray(),
                    )

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        repository.importAllNotesArchive(ByteArrayInputStream(archive))
                    }

                failure.message.orEmpty() shouldContain "Duplicate migration archive entry"
                markdownStorage.files.getValue(MemoDirectoryType.MAIN) shouldBe emptyMap()
            }
        }

        test("given migration zip with unsafe archive name when imported then workspace files remain unchanged") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                val repository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = markdownStorage,
                        workspaceMediaAccess = FakeWorkspaceMediaAccess(),
                        settingsStore = FakeMigrationSettingsStore(),
                        importBudgets = testArchiveBudgets(),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":1,"trashCount":0,"imageCount":0,"voiceCount":0}
                            """.trimIndent().toByteArray(),
                        "notes/main/../escaped.md" to "escaped".toByteArray(),
                    )

                shouldThrow<IllegalArgumentException> {
                    repository.importAllNotesArchive(ByteArrayInputStream(archive))
                }

                markdownStorage.files.getValue(MemoDirectoryType.MAIN) shouldBe emptyMap()
            }
        }

        test("given migration zip with unsupported entry prefix when imported then workspace files remain unchanged") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                val repository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = markdownStorage,
                        workspaceMediaAccess = FakeWorkspaceMediaAccess(),
                        settingsStore = FakeMigrationSettingsStore(),
                        importBudgets = testArchiveBudgets(),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":0,"trashCount":0,"imageCount":0,"voiceCount":0}
                            """.trimIndent().toByteArray(),
                        "settings/plain.json" to "{}".toByteArray(),
                    )

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        repository.importAllNotesArchive(ByteArrayInputStream(archive))
                    }

                failure.message.orEmpty() shouldContain "Unsupported migration archive entry"
                markdownStorage.files.getValue(MemoDirectoryType.MAIN) shouldBe emptyMap()
            }
        }

        test("given migration image entry with non image extension when inspected then media payload type is rejected") {
            runTest {
                val repository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = FakeMarkdownStorageDataSource(),
                        workspaceMediaAccess = FakeWorkspaceMediaAccess(),
                        settingsStore = FakeMigrationSettingsStore(),
                        importBudgets = testArchiveBudgets(),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":0,"trashCount":0,"imageCount":1,"voiceCount":0}
                            """.trimIndent().toByteArray(),
                        "media/images/not-image.txt" to "not image".toByteArray(),
                    )

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        repository.inspectAllNotesArchive(ByteArrayInputStream(archive))
                    }

                failure.message.orEmpty() shouldContain "image"
            }
        }

        test("given migration voice entry with non audio extension when inspected then media payload type is rejected") {
            runTest {
                val repository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = FakeMarkdownStorageDataSource(),
                        workspaceMediaAccess = FakeWorkspaceMediaAccess(),
                        settingsStore = FakeMigrationSettingsStore(),
                        importBudgets = testArchiveBudgets(),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":0,"trashCount":0,"imageCount":0,"voiceCount":1}
                            """.trimIndent().toByteArray(),
                        "media/voice/not-audio.txt" to "not audio".toByteArray(),
                    )

                val failure =
                    shouldThrow<IllegalArgumentException> {
                        repository.inspectAllNotesArchive(ByteArrayInputStream(archive))
                    }

                failure.message.orEmpty() shouldContain "voice"
            }
        }

        test("given archive commit fails after staging files when imported then staged workspace files are rolled back") {
            runTest {
                val markdownStorage = FakeMarkdownStorageDataSource()
                val mediaAccess =
                    FailingAfterWriteWorkspaceMediaAccess(
                        failingCategory = WorkspaceMediaCategory.VOICE,
                        failingFilename = "broken.m4a",
                    )
                val repository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = markdownStorage,
                        workspaceMediaAccess = mediaAccess,
                        settingsStore = FakeMigrationSettingsStore(),
                        importBudgets = testArchiveBudgets(),
                    )
                val archive =
                    buildZip(
                        "manifest.json" to
                            """
                            {"version":1,"noteCount":1,"trashCount":0,"imageCount":1,"voiceCount":1}
                            """.trimIndent().toByteArray(),
                        "notes/main/imported.md" to "imported note".toByteArray(),
                        "media/images/imported.png" to "image".toByteArray(),
                        "media/voice/broken.m4a" to "voice".toByteArray(),
                    )

                shouldThrow<IOException> {
                    repository.importAllNotesArchive(ByteArrayInputStream(archive))
                }

                markdownStorage.files.getValue(MemoDirectoryType.MAIN) shouldBe emptyMap()
                mediaAccess.files.getValue(WorkspaceMediaCategory.IMAGE) shouldBe emptyMap()
                mediaAccess.files.getValue(WorkspaceMediaCategory.VOICE) shouldBe emptyMap()
            }
        }

        test("given settings snapshot when exported and imported with password then encrypted bytes restore snapshot") {
            runTest {
                val sourceStore =
                    FakeMigrationSettingsStore(
                        snapshot =
                            MigrationSettingsSnapshot(
                                preferences =
                                    mapOf(
                                        "themeMode" to "dark",
                                        "dateFormat" to "yyyy/MM/dd",
                                    ),
                                sensitive =
                                    mapOf(
                                        "gitToken" to "secret-token",
                                        "webDavPassword" to "dav-secret",
                                    ),
                            ),
                    )
                val exportRepository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = FakeMarkdownStorageDataSource(),
                        workspaceMediaAccess = FakeWorkspaceMediaAccess(),
                        settingsStore = sourceStore,
                        importBudgets = testArchiveBudgets(),
                    )
                val output = ByteArrayOutputStream()

                val exportSummary = exportRepository.exportEncryptedSettings(output, "correct-password")

                exportSummary.settingCount shouldBe 4
                exportSummary.sensitiveSettingCount shouldBe 2
                val encryptedText = output.toString(Charsets.UTF_8.name())
                encryptedText shouldNotContain "secret-token"
                encryptedText shouldNotContain "dav-secret"
                encryptedText shouldNotContain "themeMode"

                val restoreStore = FakeMigrationSettingsStore()
                val importRepository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = FakeMarkdownStorageDataSource(),
                        workspaceMediaAccess = FakeWorkspaceMediaAccess(),
                        settingsStore = restoreStore,
                        importBudgets = testArchiveBudgets(),
                    )
                val importSummary =
                    importRepository.importEncryptedSettings(
                        input = ByteArrayInputStream(output.toByteArray()),
                        password = "correct-password",
                    )

                importSummary shouldBe exportSummary
                restoreStore.restoredSnapshot shouldBe sourceStore.snapshot
            }
        }

        test("given decrypted settings fail dry-run validation when imported then settings restore is not committed") {
            runTest {
                val sourceStore =
                    FakeMigrationSettingsStore(
                        snapshot =
                            MigrationSettingsSnapshot(
                                preferences = mapOf("themeMode" to "unsupported"),
                                sensitive = mapOf("gitToken" to "secret-token"),
                            ),
                    )
                val sourceRepository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = FakeMarkdownStorageDataSource(),
                        workspaceMediaAccess = FakeWorkspaceMediaAccess(),
                        settingsStore = sourceStore,
                        importBudgets = testArchiveBudgets(),
                    )
                val output = ByteArrayOutputStream()
                sourceRepository.exportEncryptedSettings(output, "correct-password")
                val restoreStore =
                    FakeMigrationSettingsStore(
                        validationFailure = IllegalArgumentException("invalid migration setting"),
                    )
                val restoreRepository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = FakeMarkdownStorageDataSource(),
                        workspaceMediaAccess = FakeWorkspaceMediaAccess(),
                        settingsStore = restoreStore,
                        importBudgets = testArchiveBudgets(),
                    )

                shouldThrow<IllegalArgumentException> {
                    restoreRepository.importEncryptedSettings(
                        input = ByteArrayInputStream(output.toByteArray()),
                        password = "correct-password",
                    )
                }

                restoreStore.validatedSnapshots shouldBe listOf(sourceStore.snapshot)
                restoreStore.restoreCallCount shouldBe 0
                restoreStore.restoredSnapshot shouldBe null
            }
        }

        test("given encrypted settings when password is wrong then no settings are restored") {
            runTest {
                val sourceStore =
                    FakeMigrationSettingsStore(
                        snapshot =
                            MigrationSettingsSnapshot(
                                preferences = mapOf("themeMode" to "dark"),
                                sensitive = mapOf("gitToken" to "secret-token"),
                            ),
                    )
                val sourceRepository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = FakeMarkdownStorageDataSource(),
                        workspaceMediaAccess = FakeWorkspaceMediaAccess(),
                        settingsStore = sourceStore,
                        importBudgets = testArchiveBudgets(),
                    )
                val output = ByteArrayOutputStream()
                sourceRepository.exportEncryptedSettings(output, "correct-password")
                val restoreStore = FakeMigrationSettingsStore()
                val restoreRepository =
                    MigrationArchiveRepositoryImpl(
                        markdownStorageDataSource = FakeMarkdownStorageDataSource(),
                        workspaceMediaAccess = FakeWorkspaceMediaAccess(),
                        settingsStore = restoreStore,
                        importBudgets = testArchiveBudgets(),
                    )

                shouldThrow<MigrationPasswordException> {
                    restoreRepository.importEncryptedSettings(
                        input = ByteArrayInputStream(output.toByteArray()),
                        password = "wrong-password",
                    )
                }

                restoreStore.restoredSnapshot shouldBe null
            }
        }
    }
}

private class FakeMarkdownStorageDataSource : MarkdownStorageDataSource {
    val files: Map<MemoDirectoryType, LinkedHashMap<String, String>> =
        MemoDirectoryType.entries.associateWith { linkedMapOf<String, String>() }

    override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> =
        files.getValue(directory).keys.map { filename ->
            FileMetadata(filename = filename, lastModified = 1L, size = files.getValue(directory).getValue(filename).length.toLong())
        }

    override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> =
        files.getValue(directory).keys.map { filename ->
            FileMetadataWithId(filename = filename, lastModified = 1L, documentId = filename)
        }

    override fun streamMetadataWithIdsIn(directory: MemoDirectoryType): Flow<FileMetadataWithId> =
        flow {
            listMetadataWithIdsIn(directory).forEach { metadata -> emit(metadata) }
        }

    override suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String? = files.getValue(directory)[documentId]

    override suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String? = files.getValue(directory)[filename]

    override suspend fun readFile(uri: Uri): String? = null

    override suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean,
        uri: Uri?,
    ): String? {
        files.getValue(directory)[filename] =
            if (append) {
                files.getValue(directory)[filename].orEmpty() + content
            } else {
                content
            }
        return null
    }

    override suspend fun deleteFileIn(
        directory: MemoDirectoryType,
        filename: String,
        uri: Uri?,
    ) {
        files.getValue(directory).remove(filename)
    }

    override suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): FileMetadata? =
        files.getValue(directory)[filename]?.let { content ->
            FileMetadata(filename = filename, lastModified = 1L, size = content.length.toLong())
        }
}

private open class FakeWorkspaceMediaAccess : WorkspaceMediaAccess {
    val files: Map<WorkspaceMediaCategory, LinkedHashMap<String, ByteArray>> =
        WorkspaceMediaCategory.entries.associateWith { linkedMapOf<String, ByteArray>() }

    override suspend fun listFiles(category: WorkspaceMediaCategory): List<WorkspaceMediaDescriptor> =
        files.getValue(category).map { (filename, bytes) ->
            WorkspaceMediaDescriptor(filename = filename, sizeBytes = bytes.size.toLong())
        }

    override suspend fun listFilenames(category: WorkspaceMediaCategory): List<String> =
        files.getValue(category).keys.toList()

    override suspend fun readFileToStream(
        category: WorkspaceMediaCategory,
        filename: String,
        destination: OutputStream,
    ): Boolean {
        val bytes = files.getValue(category)[filename] ?: return false
        destination.write(bytes)
        return true
    }

    override suspend fun writeFileFromStream(
        category: WorkspaceMediaCategory,
        filename: String,
        source: suspend (OutputStream) -> Unit,
    ) {
        val output = ByteArrayOutputStream()
        source(output)
        files.getValue(category)[filename] = output.toByteArray()
    }

    override suspend fun deleteFile(
        category: WorkspaceMediaCategory,
        filename: String,
    ) {
        files.getValue(category).remove(filename)
    }
}

private class StreamOnlyWorkspaceMediaAccess : FakeWorkspaceMediaAccess() {
    override suspend fun writeFileFromStream(
        category: WorkspaceMediaCategory,
        filename: String,
        source: suspend (OutputStream) -> Unit,
    ) {
        val output = ByteArrayOutputStream()
        source(output)
        files.getValue(category)[filename] = output.toByteArray()
    }
}

private class StreamReadableWorkspaceMediaAccess : FakeWorkspaceMediaAccess() {
    var legacyListAttempted = false
    val streamedReads = mutableListOf<Pair<WorkspaceMediaCategory, String>>()

    override suspend fun listFiles(category: WorkspaceMediaCategory): List<WorkspaceMediaDescriptor> {
        legacyListAttempted = true
        return super.listFiles(category)
    }

    override suspend fun readFileToStream(
        category: WorkspaceMediaCategory,
        filename: String,
        destination: OutputStream,
    ): Boolean {
        val bytes = files.getValue(category)[filename] ?: return false
        streamedReads += category to filename
        destination.write(bytes)
        return true
    }
}

private class FailingAfterWriteWorkspaceMediaAccess(
    private val failingCategory: WorkspaceMediaCategory,
    private val failingFilename: String,
) : FakeWorkspaceMediaAccess() {
    override suspend fun writeFileFromStream(
        category: WorkspaceMediaCategory,
        filename: String,
        source: suspend (OutputStream) -> Unit,
    ) {
        super.writeFileFromStream(category, filename, source)
        if (category == failingCategory && filename == failingFilename) {
            throw IOException("simulated restore failure")
        }
    }
}

private class FakeWorkspaceConfigSource(
    roots: Map<StorageRootType, String>,
) : WorkspaceConfigSource {
    private val roots = MutableStateFlow(roots)

    override suspend fun setRoot(
        type: StorageRootType,
        pathOrUri: String,
    ) {
        roots.value = roots.value + (type to pathOrUri)
    }

    override fun getRootFlow(type: StorageRootType): Flow<String?> =
        roots.map { it[type] }

    override fun getRootDisplayNameFlow(type: StorageRootType): Flow<String?> =
        roots.map { it[type]?.substringAfterLast('/') }

    override suspend fun createDirectory(name: String): String = name
}

private class FakeMigrationSettingsStore(
    val snapshot: MigrationSettingsSnapshot = MigrationSettingsSnapshot(),
    private val validationFailure: Throwable? = null,
) : MigrationSettingsStore,
    MigrationSettingsRestoreValidator {
    var restoredSnapshot: MigrationSettingsSnapshot? = null
    val validatedSnapshots = mutableListOf<MigrationSettingsSnapshot>()
    var restoreCallCount = 0

    override suspend fun snapshot(): MigrationSettingsSnapshot = snapshot

    override suspend fun validateRestore(snapshot: MigrationSettingsSnapshot) {
        validatedSnapshots += snapshot
        validationFailure?.let { throw it }
    }

    override suspend fun restore(snapshot: MigrationSettingsSnapshot) {
        restoreCallCount += 1
        restoredSnapshot = snapshot
    }
}

private fun migrationRepositoryWithBudgets(
    markdownStorage: MarkdownStorageDataSource,
    mediaAccess: WorkspaceMediaAccess,
    budgets: MigrationArchiveImportBudgets,
): MigrationArchiveRepositoryImpl =
    MigrationArchiveRepositoryImpl(
        markdownStorageDataSource = markdownStorage,
        workspaceMediaAccess = mediaAccess,
        settingsStore = FakeMigrationSettingsStore(),
        importBudgets = budgets,
    )

private fun testArchiveBudgets(
    maxCompressedArchiveBytes: Long = 512 * 1024,
    maxEntries: Int = 32,
    maxUncompressedBytes: Long = 512 * 1024,
    maxCompressionRatio: Int = 1_000,
    maxManifestBytes: Int = 4 * 1024,
    maxMarkdownEntryBytes: Int = 256 * 1024,
): MigrationArchiveImportBudgets =
    MigrationArchiveImportBudgets(
        maxCompressedArchiveBytes = maxCompressedArchiveBytes,
        maxEntries = maxEntries,
        maxUncompressedBytes = maxUncompressedBytes,
        maxCompressionRatio = maxCompressionRatio,
        maxManifestBytes = maxManifestBytes,
        maxMarkdownEntryBytes = maxMarkdownEntryBytes,
    )

private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val entries = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zipInput ->
        generateSequence { zipInput.nextEntry }.forEach { entry ->
            if (!entry.isDirectory) {
                entries[entry.name] = zipInput.readBytes()
            }
            zipInput.closeEntry()
        }
    }
    return entries
}

private fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zipOutput ->
        entries.forEach { (name, bytes) ->
            zipOutput.putNextEntry(ZipEntry(name))
            zipOutput.write(bytes)
            zipOutput.closeEntry()
        }
    }
    return output.toByteArray()
}

private fun buildZipWithLocalEntries(vararg entries: Pair<String, ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    entries.forEach { (name, bytes) ->
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(bytes) }.value
        output.writeLittleEndianInt(LOCAL_FILE_HEADER_SIGNATURE)
        output.writeLittleEndianShort(ZIP_VERSION_NEEDED)
        output.writeLittleEndianShort(0)
        output.writeLittleEndianShort(0)
        output.writeLittleEndianShort(0)
        output.writeLittleEndianShort(0)
        output.writeLittleEndianInt(crc.toInt())
        output.writeLittleEndianInt(bytes.size)
        output.writeLittleEndianInt(bytes.size)
        output.writeLittleEndianShort(nameBytes.size)
        output.writeLittleEndianShort(0)
        output.write(nameBytes)
        output.write(bytes)
    }
    return output.toByteArray()
}

private fun ByteArrayOutputStream.writeLittleEndianShort(value: Int) {
    write(value and BYTE_MASK)
    write((value ushr Byte.SIZE_BITS) and BYTE_MASK)
}

private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
    write(value and BYTE_MASK)
    write((value ushr Byte.SIZE_BITS) and BYTE_MASK)
    write((value ushr (Byte.SIZE_BITS * 2)) and BYTE_MASK)
    write((value ushr (Byte.SIZE_BITS * 3)) and BYTE_MASK)
}

private const val BYTE_MASK = 0xff
private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50
private const val ZIP_VERSION_NEEDED = 20
