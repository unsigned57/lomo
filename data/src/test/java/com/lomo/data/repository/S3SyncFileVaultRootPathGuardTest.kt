package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.KotestTemporaryFolder
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.File

/*
 * Behavior Contract:
 * - Unit under test: S3 vault-root path resolution and bridge raw-string file operations.
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: map remote vault-root keys to local files only after raw-string validation and canonical workspace-root validation.
 *
 * Scenarios:
 * - Given traversal, absolute, NUL, empty-segment, and Windows drive-prefixed remote paths, when vault-root resolution runs, then no local file target is produced.
 * - Given unsafe raw remote paths, when bridge write/read/delete/import operations run, then outside files are untouched, reads return null, and no vault file is created.
 * - Given a normal vault path, when vault-root write/read/delete runs, then the file is created, read, and removed inside the root.
 *
 * Observable outcomes:
 * - Resolved vault path values, bridge returned bytes/text, and persisted filesystem state under and outside the vault root.
 *
 * TDD proof:
 * - RED: Fails before the fix because VaultRootPath.from("C:escape.md") is accepted and bridge writeLocalBytes("C:escape.md") creates a root file.
 * - GREEN: After the fix, Windows drive-prefixed first segments are rejected and bridge raw-string operations ignore unsafe paths.
 *
 * Excludes:
 * - S3 transport, encryption, metadata DAO persistence, SAF provider internals, and sync conflict planning.
 */
class S3SyncFileVaultRootPathGuardTest : DataFunSpec() {
    private lateinit var tempFolder: KotestTemporaryFolder

    init {
        beforeTest {
            tempFolder = KotestTemporaryFolder()
        }

        afterTest {
            tempFolder.cleanup()
        }

        test("given unsafe remote paths when vault-root resolution runs then files outside root are untouched") {
            runTest {
                val root = tempFolder.newFolder("vault")
                val outsideFile = File(tempFolder.root, "escape.md").apply { writeText("outside") }
                val rootParent = requireNotNull(root.parentFile)
                val mode = fileVaultRoot(root)
                val layout = syncDirectoryLayoutForTest
                val unsafePaths =
                    listOf(
                        "../escape.md",
                        "safe/../../escape.md",
                        "${rootParent.absolutePath}/escape.md",
                        "/absolute.md",
                        "notes/./gap.md",
                        "notes//gap.md",
                        "notes/\u0000gap.md",
                        "C:escape.md",
                        "C:/escape.md",
                        "C:\\escape.md",
                    )

                unsafePaths.forEach { unsafePath ->
                    VaultRootPath.from(unsafePath) shouldBe null
                    resolveVaultRootPath(unsafePath, layout, mode) shouldBe null
                }

                outsideFile.exists() shouldBe true
                outsideFile.readText() shouldBe "outside"
                root.walkTopDown().map { file -> file.relativeTo(root).path }.toList() shouldBe listOf("")
            }
        }

        test("given unsafe raw paths when bridge operations run then they cannot bypass vault-root guards") {
            runTest {
                val root = tempFolder.newFolder("vault")
                val outsideFile = File(tempFolder.root, "escape.md").apply { writeText("outside") }
                val sourceFile = File(tempFolder.root, "source.md").apply { writeText("imported") }
                val rootParent = requireNotNull(root.parentFile)
                val mode = fileVaultRoot(root)
                val layout = syncDirectoryLayoutForTest
                val scope =
                    S3SyncFileBridgeScope(
                        runtime = mockk(),
                        encodingSupport = S3SyncEncodingSupport(),
                        safTreeAccess = UnsupportedS3SafTreeAccess,
                        mode = mode,
                    )
                val unsafePaths =
                    listOf(
                        "../escape.md",
                        "safe/../../escape.md",
                        "${rootParent.absolutePath}/escape.md",
                        "/absolute.md",
                        "notes/./gap.md",
                        "notes//gap.md",
                        "notes/\u0000gap.md",
                        "C:escape.md",
                        "C:/escape.md",
                        "C:\\escape.md",
                    )

                unsafePaths.forEach { unsafePath ->
                    scope.writeLocalBytes(unsafePath, "changed".toByteArray(), layout)
                    assertUnsafeOperationDidNotChangeFiles(root, outsideFile)

                    scope.importLocalFile(unsafePath, sourceFile, layout)
                    assertUnsafeOperationDidNotChangeFiles(root, outsideFile)

                    scope.readLocalText(unsafePath, layout) shouldBe null
                    scope.readLocalBytes(unsafePath, layout) shouldBe null

                    scope.deleteLocalFile(unsafePath, layout)
                    outsideFile.exists() shouldBe true
                    outsideFile.readText() shouldBe "outside"
                    rootRelativePaths(root) shouldBe listOf("")
                }
                sourceFile.exists() shouldBe true
                sourceFile.readText() shouldBe "imported"
                rootRelativePaths(root) shouldBe listOf("")
            }
        }

        test("given a normal remote path when direct vault-root ops run then the file stays under root") {
            runTest {
                val root = tempFolder.newFolder("vault")
                val mode = fileVaultRoot(root)
                val path = requireNotNull(VaultRootPath.from("notes/a.md"))

                writeFileVaultRootBytes(mode, path, "inside".toByteArray())

                readFileVaultRootText(mode, path) shouldBe "inside"
                listFileVaultRootLocalFiles(mode).keys shouldBe setOf("notes/a.md")

                deleteFileVaultRootFile(mode, path)

                readFileVaultRootText(mode, path) shouldBe null
                File(root, "notes/a.md").exists() shouldBe false
            }
        }
    }

    private fun fileVaultRoot(root: File): S3LocalSyncMode.FileVaultRoot =
        S3LocalSyncMode.FileVaultRoot(
            rootDir = root,
            memoRelativeDir = null,
            imageRelativeDir = null,
            voiceRelativeDir = null,
            legacyRemoteCompatibility = false,
        )

    private fun assertUnsafeOperationDidNotChangeFiles(
        root: File,
        outsideFile: File,
    ) {
        outsideFile.exists() shouldBe true
        outsideFile.readText() shouldBe "outside"
        rootRelativePaths(root) shouldBe listOf("")
    }

    private fun rootRelativePaths(root: File): List<String> =
        root.walkTopDown().map { file -> file.relativeTo(root).path }.toList()

    private companion object {
        val syncDirectoryLayoutForTest =
            com.lomo.data.sync.SyncDirectoryLayout(
                memoFolder = "memo",
                imageFolder = "images",
                voiceFolder = "voice",
                allSameDirectory = false,
            )
    }
}
