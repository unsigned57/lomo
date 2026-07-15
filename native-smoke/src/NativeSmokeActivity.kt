package com.lomo.nativesmoke

import android.app.Activity
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import com.lomo.rust.FeasibilityProbe
import com.lomo.rust.planSyncEnvelope
import java.io.File
import java.security.MessageDigest

/**
 * Tooling-only smoke: sync v1 planner, FeasibilityProbe lifecycle, and SAF DocumentsProvider.
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
            runFeasibilityProbeSmoke()
            runSafDocumentsProviderSmoke()
            Log.i(LOG_TAG, "PASS")
            finish()
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "FAIL", error)
            throw error
        }
    }

    private fun runFeasibilityProbeSmoke() {
        val probe = FeasibilityProbe()
        check(probe.revision() == 0UL) { "probe revision must start at 0" }
        check(probe.bumpRevision() == 1UL) { "probe revision must advance" }
        val page = probe.listPage(null, 100u)
        check(page.items.size == 32) { "page must be bounded to 32 items" }
        probe.cancel("op-smoke")
        runCatching { probe.completeOperation("op-smoke") }
            .onSuccess { error("cancelled operation must not complete") }
        val first = probe.submitPlatformBatch("batch-smoke")
        check(first == "accepted:batch-smoke") { "first batch must accept" }
        val second = probe.submitPlatformBatch("batch-smoke")
        check(second == "replayed:batch-smoke") { "same batch id must replay" }
        probe.shutdown()
        runCatching { probe.listPage(null, 1u) }
            .onSuccess { error("shutdown probe must reject calls") }
    }

    private fun runSafDocumentsProviderSmoke() {
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
        val payload = "batch-smoke\n"
        contentResolver.openOutputStream(createdId, "wt")?.use { stream ->
            stream.write(payload.toByteArray(Charsets.UTF_8))
        } ?: error("unable to open created document for write")

        val readBytes =
            contentResolver.openInputStream(createdId)?.use { it.readBytes() }
                ?: error("unable to open created document for read")
        check(readBytes.toString(Charsets.UTF_8) == payload) { "SAF document bytes must round-trip" }
        val digest = sha256Hex(readBytes)
        check(digest.length == 64) { "digest must be sha-256 hex" }

        // Metadata page: child listing of the root must include the created document.
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
            check(ids.any { it.endsWith("batch-smoke.txt") }) {
                "metadata page must list created document: $ids"
            }
        }

        // Replace via truncate write, then rename, then delete.
        contentResolver.openOutputStream(createdId, "wt")?.use { stream ->
            stream.write("replaced\n".toByteArray(Charsets.UTF_8))
        } ?: error("unable to replace document")
        val renamedId =
            DocumentsContract.renameDocument(contentResolver, createdId, "batch-smoke-renamed.txt")
                ?: error("renameDocument returned null")
        DocumentsContract.deleteDocument(contentResolver, renamedId)

        // Capability-style private temp file used for platform-batch recovery metadata.
        val batchDir = getExternalFilesDir("saf-batch") ?: filesDir
        val tokenFile = File(batchDir, "batch-smoke.token")
        tokenFile.parentFile?.mkdirs()
        tokenFile.writeText("batch-smoke:$digest")
        check(tokenFile.readText().startsWith("batch-smoke:")) { "batch token must round-trip" }
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
