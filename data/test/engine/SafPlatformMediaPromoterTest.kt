package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: SafPlatformMediaPromoter (promoteSafMediaToWorkspace).
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: promote draft-scoped staged media files into the capability-bound SAF tree before
 *   committing memo body mutations, mirroring `lomo_media::promote_staged`: satisfied-skip on
 *   identical digest, typed conflict on a different digest at the final path, verified streaming
 *   write otherwise. Memory stays bounded by the copy chunk, never by media size.
 *
 * Scenarios:
 * - Given a valid pending promote plan, when promote runs, then bytes are streamed to the SAF
 *   workspace at the requested relative path with WriteMode.CREATE and the staging temp file is
 *   cleaned up.
 * - Given a target that already exists in SAF with identical length and digest, when promote runs,
 *   then AlreadySatisfied succeeds without rewrite and the staging temp file is cleaned up.
 * - Given a target that already exists in SAF with a different digest, when promote runs, then
 *   validation fails closed with promote_final_path_conflict, no SAF write occurs, and the staging
 *   file is left for retry.
 * - Given a promote plan whose operationId does not match the command operationId, when promote
 *   runs, then validation fails closed with promote_operation_id_mismatch and no SAF write occurs.
 * - Given a promote plan whose staged file is missing, when promote runs, then validation fails
 *   closed with staged_media_missing (the draft media is unrecoverable, never retryable).
 * - Given a promote plan whose staged file length differs from the staged facts, when promote runs,
 *   then validation fails closed with staged_media_size_mismatch.
 * - Given a promote plan whose staged file digest differs from the staged facts, when promote runs,
 *   then validation fails closed with staged_media_digest_mismatch.
 * - Given a destination occupied by a directory, when promote runs, then conflict fails closed with
 *   media_target_not_file.
 * - Given an invalid or escaping destination path, when promote runs, then validation fails closed.
 * - Given two plans where the first promotes and the second has a missing staged file, when promote
 *   runs and is retried after the second staged file is restored, then the first plan replays as
 *   AlreadySatisfied without a second write and the second plan promotes.
 *
 * Observable outcomes:
 * - PlatformDocumentsGateway file writes with their WriteMode, deleted staging files, thrown
 *   EngineCommandFailureException.
 *
 * TDD proof:
 * - RED before fix because promoteSafMediaToWorkspace is missing or fails.
 * - RED on 2026-09-01 because a different-digest target was silently overwritten with
 *   WriteMode.REPLACE where Rust `promote_staged` fails closed with promote_final_path_conflict.
 *
 * Excludes:
 * - Direct workspace Rust promotion and SQLite projection indexing.
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.MediaPromotePlanDto
import com.lomo.nativebridge.MediaStagedDto
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class SafPlatformMediaPromoterTest : DataFunSpec() {
    private val treeUri = "content://com.example/tree/workspace"

    init {

    test("given valid promote plan when promote runs then bytes are written to SAF and staging file is deleted") {
        val gateway = FakePromoterDocumentsGateway()
        val tempDir = Files.createTempDirectory("lomo-stage-test").toFile()
        try {
            val fileBytes = "image-bytes-12345".toByteArray(Charsets.UTF_8)
            val digest = fileBytes.sha256Hex()
            val stagedFile = File(tempDir, "staged.bin").apply { writeBytes(fileBytes) }

            promoteSafMediaToWorkspace(
                treeUri = treeUri,
                documents = gateway,
                promotes = listOf(promotePlan(op = "op-1", staged = stagedFacts(fileBytes, stagedFile))),
                operationId = "op-1",
            )

            val written = gateway.files["media/photo.png"]
            written shouldBe fileBytes
            gateway.writeModes shouldBe listOf(WriteMode.CREATE)
            stagedFile.exists() shouldBe false
        } finally {
            tempDir.deleteRecursively()
        }
    }

    test("given target already exists in SAF with matching length and digest when promote runs then AlreadySatisfied without rewrite") {
        val gateway = FakePromoterDocumentsGateway()
        val tempDir = Files.createTempDirectory("lomo-stage-test").toFile()
        try {
            val fileBytes = "existing-image-bytes".toByteArray(Charsets.UTF_8)
            val digest = fileBytes.sha256Hex()
            gateway.seedFile("media/photo.png", fileBytes)
            val stagedFile = File(tempDir, "staged.bin").apply { writeBytes(fileBytes) }

            gateway.writeCount shouldBe 0
            promoteSafMediaToWorkspace(
                treeUri = treeUri,
                documents = gateway,
                promotes = listOf(promotePlan(op = "op-1", staged = stagedFacts(fileBytes, stagedFile))),
                operationId = "op-1",
            )

            gateway.writeCount shouldBe 0
            stagedFile.exists() shouldBe false
        } finally {
            tempDir.deleteRecursively()
        }
    }

    test("given target already exists with different digest when promote runs then conflict fails closed without overwrite") {
        val gateway = FakePromoterDocumentsGateway()
        val tempDir = Files.createTempDirectory("lomo-stage-test").toFile()
        try {
            val committedBytes = "committed-image-bytes".toByteArray(Charsets.UTF_8)
            gateway.seedFile("media/photo.png", committedBytes)
            val stagedFile = File(tempDir, "staged.bin").apply { writeBytes("other-image".toByteArray()) }

            val failure =
                shouldThrow<EngineCommandFailureException> {
                    promoteSafMediaToWorkspace(
                        treeUri = treeUri,
                        documents = gateway,
                        promotes = listOf(promotePlan(op = "op-1", staged = stagedFacts(stagedFile.readBytes(), stagedFile))),
                        operationId = "op-1",
                    )
                }

            failure.failure.code shouldBe "promote_final_path_conflict"
            failure.failure.category shouldBe EngineFailureCategory.VALIDATION
            failure.failure.retryDisposition shouldBe EngineRetryDisposition.NEVER
            gateway.files["media/photo.png"] shouldBe committedBytes
            gateway.writeCount shouldBe 0
            stagedFile.exists() shouldBe true
        } finally {
            tempDir.deleteRecursively()
        }
    }

    test("given mismatched operationId when promote runs then validation fails closed") {
        val gateway = FakePromoterDocumentsGateway()
        val plan = promotePlan(op = "op-mismatch", staged = stagedFacts(ByteArray(10), File("/tmp/fake")))

        val failure =
            shouldThrow<EngineCommandFailureException> {
                promoteSafMediaToWorkspace(
                    treeUri = treeUri,
                    documents = gateway,
                    promotes = listOf(plan),
                    operationId = "op-actual",
                )
            }
        failure.failure.code shouldBe "promote_operation_id_mismatch"
        failure.failure.category shouldBe EngineFailureCategory.VALIDATION
    }

    test("given missing staged file when promote runs then validation fails closed as unrecoverable") {
        val gateway = FakePromoterDocumentsGateway()
        val plan = promotePlan(op = "op-1", staged = stagedFacts(ByteArray(10), File("/nonexistent/path/staged.bin")))

        val failure =
            shouldThrow<EngineCommandFailureException> {
                promoteSafMediaToWorkspace(
                    treeUri = treeUri,
                    documents = gateway,
                    promotes = listOf(plan),
                    operationId = "op-1",
                )
            }
        failure.failure.code shouldBe "staged_media_missing"
        failure.failure.category shouldBe EngineFailureCategory.VALIDATION
        failure.failure.retryDisposition shouldBe EngineRetryDisposition.NEVER
    }

    test("given staged file with mismatched length when promote runs then validation fails closed") {
        val gateway = FakePromoterDocumentsGateway()
        val tempDir = Files.createTempDirectory("lomo-stage-test").toFile()
        try {
            val fileBytes = "actual-content".toByteArray(Charsets.UTF_8)
            val stagedFile = File(tempDir, "staged.bin").apply { writeBytes(fileBytes) }
            val plan =
                promotePlan(
                    op = "op-1",
                    staged = stagedFacts(fileBytes, stagedFile).copy(size = (fileBytes.size + 1).toULong()),
                )

            val failure =
                shouldThrow<EngineCommandFailureException> {
                    promoteSafMediaToWorkspace(
                        treeUri = treeUri,
                        documents = gateway,
                        promotes = listOf(plan),
                        operationId = "op-1",
                    )
                }
            failure.failure.code shouldBe "staged_media_size_mismatch"
            failure.failure.category shouldBe EngineFailureCategory.VALIDATION
            gateway.writeCount shouldBe 0
        } finally {
            tempDir.deleteRecursively()
        }
    }

    test("given mismatched staged file digest when promote runs then validation failure is thrown") {
        val gateway = FakePromoterDocumentsGateway()
        val tempDir = Files.createTempDirectory("lomo-stage-test").toFile()
        try {
            val fileBytes = "actual-content".toByteArray(Charsets.UTF_8)
            val stagedFile = File(tempDir, "staged.bin").apply { writeBytes(fileBytes) }
            val plan =
                promotePlan(
                    op = "op-1",
                    staged = stagedFacts(fileBytes, stagedFile).copy(digest = "wrong-digest"),
                )

            val failure =
                shouldThrow<EngineCommandFailureException> {
                    promoteSafMediaToWorkspace(
                        treeUri = treeUri,
                        documents = gateway,
                        promotes = listOf(plan),
                        operationId = "op-1",
                    )
                }
            failure.failure.code shouldBe "staged_media_digest_mismatch"
            failure.failure.category shouldBe EngineFailureCategory.VALIDATION
            gateway.writeCount shouldBe 0
        } finally {
            tempDir.deleteRecursively()
        }
    }

    test("given destination occupied by a directory when promote runs then conflict fails closed") {
        val gateway = FakePromoterDocumentsGateway()
        gateway.seedDirectory("media/photo.png")

        val failure =
            shouldThrow<EngineCommandFailureException> {
                promoteSafMediaToWorkspace(
                    treeUri = treeUri,
                    documents = gateway,
                    promotes = listOf(promotePlan(op = "op-1", staged = stagedFacts(ByteArray(10), File("/tmp/fake")))),
                    operationId = "op-1",
                )
            }
        failure.failure.code shouldBe "media_target_not_file"
        failure.failure.category shouldBe EngineFailureCategory.CONFLICT
    }

    test("given invalid escaping destination path when promote runs then validation fails closed") {
        val gateway = FakePromoterDocumentsGateway()
        val plan =
            promotePlan(
                op = "op-1",
                staged = stagedFacts(ByteArray(10), File("/tmp/fake")),
                finalRelativePath = "../escaping.png",
            )

        val failure =
            shouldThrow<EngineCommandFailureException> {
                promoteSafMediaToWorkspace(
                    treeUri = treeUri,
                    documents = gateway,
                    promotes = listOf(plan),
                    operationId = "op-1",
                )
            }
        failure.failure.code shouldBe "invalid_media_destination_path"
        failure.failure.category shouldBe EngineFailureCategory.VALIDATION
    }

    test("given partial multi-plan failure when promote is retried after staged file is restored then first plan replays satisfied and second promotes") {
        val gateway = FakePromoterDocumentsGateway()
        val tempDir = Files.createTempDirectory("lomo-stage-test").toFile()
        try {
            val firstBytes = "first-image".toByteArray()
            val secondBytes = "second-image".toByteArray()
            val firstStaged = File(tempDir, "first.bin").apply { writeBytes(firstBytes) }
            val secondStaged = File(tempDir, "second.bin")
            val firstPlan = promotePlan(op = "op-1", staged = stagedFacts(firstBytes, firstStaged))
            val secondPlan =
                promotePlan(
                    op = "op-1",
                    staged = stagedFacts(secondBytes, secondStaged),
                    finalRelativePath = "media/second.png",
                )

            shouldThrow<EngineCommandFailureException> {
                promoteSafMediaToWorkspace(
                    treeUri = treeUri,
                    documents = gateway,
                    promotes = listOf(firstPlan, secondPlan),
                    operationId = "op-1",
                )
            }.failure.code shouldBe "staged_media_missing"

            gateway.files["media/photo.png"] shouldBe firstBytes
            firstStaged.exists() shouldBe false
            gateway.writeCount shouldBe 1

            secondStaged.writeBytes(secondBytes)
            promoteSafMediaToWorkspace(
                treeUri = treeUri,
                documents = gateway,
                promotes = listOf(firstPlan, secondPlan),
                operationId = "op-1",
            )

            gateway.writeCount shouldBe 2
            gateway.files["media/second.png"] shouldBe secondBytes
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
}

private fun stagedFacts(
    bytes: ByteArray,
    stagedFile: File,
): MediaStagedDto =
    MediaStagedDto(
        digest = bytes.sha256Hex(),
        size = bytes.size.toULong(),
        mime = "image/png",
        stagingPath = stagedFile.absolutePath,
        humanNameHint = "photo.png",
        suggestedFinalRelativePath = "media/photo.png",
    )

private fun promotePlan(
    op: String,
    staged: MediaStagedDto,
    finalRelativePath: String = staged.suggestedFinalRelativePath,
): MediaPromotePlanDto =
    MediaPromotePlanDto(
        operationId = op,
        staged = staged,
        finalRelativePath = finalRelativePath,
    )

private class FakePromoterDocumentsGateway : PlatformDocumentsGateway {
    val files = mutableMapOf<String, ByteArray>()
    val kinds = mutableMapOf<String, DocumentKind>()
    val writeModes = mutableListOf<WriteMode>()
    var writeCount = 0

    fun seedFile(path: String, bytes: ByteArray) {
        files[path] = bytes.copyOf()
        kinds[path] = DocumentKind.FILE
    }

    fun seedDirectory(path: String) {
        kinds[path] = DocumentKind.DIRECTORY
    }

    override fun stat(treeUri: String, target: WorkspaceTarget): PlatformDocumentSnapshot? {
        val relPath = (target as? WorkspaceTarget.Relative)?.path ?: return null
        val kind = kinds[relPath] ?: return null
        val bytes = files[relPath]
        return PlatformDocumentSnapshot(
            target = target,
            kind = kind,
            mimeType = "image/png",
            length = (bytes?.size ?: 0).toULong(),
            lastModifiedEpochMillis = 1000L,
            documentId = "doc-$relPath",
            digest = bytes?.sha256Hex() ?: "",
        )
    }

    override fun listChildren(
        treeUri: String,
        target: WorkspaceTarget,
        cursor: String?,
        pageSize: UInt,
    ): PlatformMetadataPage = PlatformMetadataPage(emptyList(), null)

    override fun ensureDirectory(treeUri: String, path: String): PlatformDocumentSnapshot =
        PlatformDocumentSnapshot(
            target = WorkspaceTarget.Relative(path),
            kind = DocumentKind.DIRECTORY,
            mimeType = null,
            length = 0uL,
            lastModifiedEpochMillis = 1000L,
            documentId = "dir-$path",
            digest = "",
        )

    override fun openRead(treeUri: String, path: String): PlatformReadHandle {
        val bytes = files[path] ?: error("missing $path")
        return PlatformReadHandle(
            snapshot = stat(treeUri, WorkspaceTarget.Relative(path))!!,
            bytes = bytes,
        )
    }

    override fun openReadByHandle(treeUri: String, path: String, documentHandle: String): PlatformReadHandle =
        openRead(treeUri, path)

    override fun writeFromExchange(
        treeUri: String,
        path: String,
        bytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot = writeFromFile(treeUri, path, sourceBytes = bytes, mode = mode, mimeType = mimeType)

    override fun writeFromFile(
        treeUri: String,
        path: String,
        source: File,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot = writeFromFile(treeUri, path, sourceBytes = source.readBytes(), mode = mode, mimeType = mimeType)

    private fun writeFromFile(
        treeUri: String,
        path: String,
        sourceBytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot {
        writeCount += 1
        writeModes += mode
        if (mode == WriteMode.CREATE && kinds[path] != null) {
            error("Create refused over existing path: $path")
        }
        files[path] = sourceBytes.copyOf()
        kinds[path] = DocumentKind.FILE
        return stat(treeUri, WorkspaceTarget.Relative(path))!!
    }

    override fun move(treeUri: String, source: String, target: String): PlatformDocumentSnapshot {
        val bytes = files.remove(source) ?: error("missing $source")
        files[target] = bytes
        return stat(treeUri, WorkspaceTarget.Relative(target))!!
    }

    override fun delete(treeUri: String, path: String) {
        files.remove(path)
    }
}
