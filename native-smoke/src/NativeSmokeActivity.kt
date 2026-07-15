package com.lomo.nativesmoke

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.net.Uri
import android.util.Log
import com.lomo.rust.FeasibilityProbe
import com.lomo.rust.FeasibilityProbeListener
import com.lomo.rust.planSyncEnvelope
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Tooling-only smoke: durable FeasibilityProbe recovery across the SAF crash window.
 *
 * Phases (device-smoke relaunches on RESTART_REQUIRED):
 * 1. seed: cancel + submit batch, write SAF bytes, **do not** journal the action, kill.
 * 2. gap: at-least-once re-apply SAF if needed, journal apply_action, kill before confirm.
 * 3. recover: cancel still durable, action skipped, content digest verified, batch confirmed.
 *
 * Production app modules must not import FeasibilityProbe (architecture gate).
 */
class NativeSmokeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            check(planSyncEnvelope(EMPTY_S3_REQUEST).contentEquals(EMPTY_PLAN)) {
                "native planner returned unexpected sync v1 bytes"
            }
            if (runFeasibilityProbeSmoke()) {
                return
            }
            Log.i(LOG_TAG, "PASS")
            finish()
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "FAIL", error)
            throw error
        }
    }

    /**
     * @return true when this launch intentionally kills the process for recovery proof.
     */
    private fun runFeasibilityProbeSmoke(): Boolean {
        val journal = File(filesDir, "feasibility-journal.v1")
        val phase = File(filesDir, "feasibility-phase.txt")
        val phaseValue = if (phase.exists()) phase.readText().trim() else ""
        val actionMeta = File(filesDir, "saf-action-id.txt")
        val payload = "batch-smoke\n"
        val digest = sha256Hex(payload.toByteArray(Charsets.UTF_8))
        val actionId = "saf:batch-smoke.txt:$digest"
        val batchId = "batch-saf"

        when (phaseValue) {
            "", "seed" -> {
                val probe = FeasibilityProbe.open(journal.absolutePath)
                check(probe.revision() == 0UL)
                val lastRevision = AtomicLong(-1)
                probe.addListener(
                    object : FeasibilityProbeListener {
                        override fun onRevision(revision: ULong) {
                            lastRevision.set(revision.toLong())
                        }
                    },
                )
                check(probe.bumpRevision() == 1UL)
                check(lastRevision.get() == 1L) {
                    "Kotlin FeasibilityProbeListener must observe revision bumps"
                }
                check(probe.listPage(null, 100u).items.size == 32)

                probe.cancel("op-smoke")
                runCatching { probe.completeOperation("op-smoke") }
                    .onSuccess { error("cancelled operation must not complete before crash") }

                check(probe.submitPlatformBatch(batchId) == "accepted:$batchId")
                writeSafDocument(payload)
                // Dangerous window: platform side-effect exists, action not yet durable.
                actionMeta.writeText(actionId)
                probe.shutdown()
                phase.writeText("gap")
                Log.i(LOG_TAG, "RESTART_REQUIRED")
                finish()
                Process.killProcess(Process.myPid())
                return true
            }
            "gap" -> {
                val probe = FeasibilityProbe.open(journal.absolutePath)
                // Cancel must still be durable from seed.
                runCatching { probe.completeOperation("op-smoke") }
                    .onSuccess { error("cancel must remain durable across gap restart") }

                // At-least-once: ensure content exists and matches digest, then journal apply.
                ensureSafDocumentMatches(payload, digest)
                check(probe.submitPlatformBatch(batchId) == "accepted:$batchId")
                check(probe.applyAction(batchId, actionId) == "applied:$actionId") {
                    "first journal of action after gap must apply"
                }
                // Kill after journal, before batch confirm.
                probe.shutdown()
                phase.writeText("recover")
                Log.i(LOG_TAG, "RESTART_REQUIRED")
                finish()
                Process.killProcess(Process.myPid())
                return true
            }
            else -> {
                val recovered = FeasibilityProbe.open(journal.absolutePath)
                runCatching { recovered.completeOperation("op-smoke") }
                    .onSuccess { error("cancel must remain durable after process death") }
                    .onFailure { error ->
                        check(
                            error.message?.contains("cancelled", ignoreCase = true) == true ||
                                error.toString().contains("Cancelled"),
                        ) { "expected cancelled after reopen, got $error" }
                    }

                check(actionMeta.readText().trim() == actionId)
                check(recovered.applyAction(batchId, actionId) == "skipped:$actionId") {
                    "journaled action must not re-apply after death"
                }
                ensureSafDocumentMatches(payload, digest)
                check(recovered.submitPlatformBatch(batchId) == "accepted:$batchId")
                recovered.confirmPlatformBatch(batchId)
                recovered.shutdown()
                runCatching { recovered.listPage(null, 1u) }
                    .onSuccess { error("shutdown probe must reject calls") }
                // Provider CRUD surface (create/read already covered); replace/rename/move/delete + metadata.
                runSafCrudMatrix()
                phase.writeText("done")
                return false
            }
        }
    }

    private fun runSafCrudMatrix() {
        val createdId =
            DocumentsContract.createDocument(
                contentResolver,
                DocumentsContract.buildDocumentUri(
                    FeasibilityDocumentsProvider.AUTHORITY,
                    FeasibilityDocumentsProvider.ROOT_DOC_ID,
                ),
                "text/plain",
                "crud-smoke.txt",
            ) ?: error("createDocument returned null")
        val payload = "crud-smoke\n"
        contentResolver.openOutputStream(createdId, "wt")?.use { stream ->
            stream.write(payload.toByteArray(Charsets.UTF_8))
        } ?: error("unable to write crud document")
        val readBytes =
            contentResolver.openInputStream(createdId)?.use { it.readBytes() }
                ?: error("unable to read crud document")
        check(readBytes.toString(Charsets.UTF_8) == payload)

        // Replace via truncate write.
        contentResolver.openOutputStream(createdId, "wt")?.use { stream ->
            stream.write("replaced\n".toByteArray(Charsets.UTF_8))
        } ?: error("unable to replace document")
        val replaced =
            contentResolver.openInputStream(createdId)?.use { it.readBytes() }
                ?: error("unable to re-read replaced document")
        check(replaced.toString(Charsets.UTF_8) == "replaced\n")

        val renamedId =
            DocumentsContract.renameDocument(contentResolver, createdId, "crud-smoke-renamed.txt")
                ?: error("renameDocument returned null")
        val children =
            contentResolver.query(
                DocumentsContract.buildChildDocumentsUri(
                    FeasibilityDocumentsProvider.AUTHORITY,
                    FeasibilityDocumentsProvider.ROOT_DOC_ID,
                ),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            ) ?: error("child query returned null")
        children.use { cursor ->
            val ids = mutableListOf<String>()
            val index = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                ids += cursor.getString(index)
            }
            check(ids.any { it.endsWith("crud-smoke-renamed.txt") }) {
                "metadata page must list renamed document: $ids"
            }
        }

        val folderId =
            DocumentsContract.createDocument(
                contentResolver,
                DocumentsContract.buildDocumentUri(
                    FeasibilityDocumentsProvider.AUTHORITY,
                    FeasibilityDocumentsProvider.ROOT_DOC_ID,
                ),
                DocumentsContract.Document.MIME_TYPE_DIR,
                "move-target",
            ) ?: error("createDocument folder returned null")
        val movedId =
            DocumentsContract.moveDocument(
                contentResolver,
                renamedId,
                DocumentsContract.buildDocumentUri(
                    FeasibilityDocumentsProvider.AUTHORITY,
                    FeasibilityDocumentsProvider.ROOT_DOC_ID,
                ),
                folderId,
            ) ?: error("moveDocument returned null")
        val movedChildren =
            contentResolver.query(
                DocumentsContract.buildChildDocumentsUri(
                    FeasibilityDocumentsProvider.AUTHORITY,
                    DocumentsContract.getDocumentId(folderId),
                ),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            ) ?: error("folder child query returned null")
        movedChildren.use { cursor ->
            val ids = mutableListOf<String>()
            val index = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                ids += cursor.getString(index)
            }
            check(ids.any { it.endsWith("crud-smoke-renamed.txt") }) {
                "move target folder must list moved document: $ids"
            }
        }

        DocumentsContract.deleteDocument(contentResolver, movedId)
        DocumentsContract.deleteDocument(contentResolver, folderId)
        val afterDelete =
            contentResolver.query(
                DocumentsContract.buildChildDocumentsUri(
                    FeasibilityDocumentsProvider.AUTHORITY,
                    FeasibilityDocumentsProvider.ROOT_DOC_ID,
                ),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            ) ?: error("child query after delete returned null")
        afterDelete.use { cursor ->
            val ids = mutableListOf<String>()
            val index = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                ids += cursor.getString(index)
            }
            check(ids.none { it.endsWith("crud-smoke-renamed.txt") }) {
                "deleted document must leave metadata page: $ids"
            }
            check(ids.none { it.endsWith("move-target") || it.contains("/move-target") }) {
                "deleted move-target folder must leave metadata page: $ids"
            }
        }
    }

    private fun writeSafDocument(payload: String): Uri {
        val createdId =
            DocumentsContract.createDocument(
                contentResolver,
                DocumentsContract.buildDocumentUri(
                    FeasibilityDocumentsProvider.AUTHORITY,
                    FeasibilityDocumentsProvider.ROOT_DOC_ID,
                ),
                "text/plain",
                "batch-smoke.txt",
            ) ?: error("createDocument returned null")
        contentResolver.openOutputStream(createdId, "wt")?.use { stream ->
            stream.write(payload.toByteArray(Charsets.UTF_8))
        } ?: error("unable to open created document for write")
        return createdId
    }

    private fun ensureSafDocumentMatches(payload: String, expectedDigest: String) {
        val docUri = findBatchSmokeUri() ?: writeSafDocument(payload)
        val bytes =
            contentResolver.openInputStream(docUri)?.use { it.readBytes() }
                ?: error("unable to read SAF document for digest verification")
        val actual = sha256Hex(bytes)
        check(actual == expectedDigest) {
            "SAF document digest mismatch: expected=$expectedDigest actual=$actual"
        }
        check(bytes.toString(Charsets.UTF_8) == payload) {
            "SAF document content mismatch"
        }
    }

    private fun findBatchSmokeUri(): Uri? {
        val children =
            contentResolver.query(
                DocumentsContract.buildChildDocumentsUri(
                    FeasibilityDocumentsProvider.AUTHORITY,
                    FeasibilityDocumentsProvider.ROOT_DOC_ID,
                ),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            ) ?: return null
        children.use { cursor ->
            val index = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                val id = cursor.getString(index)
                if (id.endsWith("batch-smoke.txt")) {
                    return DocumentsContract.buildDocumentUri(
                        FeasibilityDocumentsProvider.AUTHORITY,
                        id,
                    )
                }
            }
        }
        return null
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val LOG_TAG = "LomoNativeSmoke"
        val EMPTY_S3_REQUEST =
            hex("4c4f4d4f010001000000000000000000000000000000000000000000000000000000000000000000")
        val EMPTY_PLAN = hex("4c4f4d4f01000000000000000000")

        fun hex(value: String): ByteArray =
            value.chunked(2).map { Integer.parseInt(it, 16).toByte() }.toByteArray()
    }
}
