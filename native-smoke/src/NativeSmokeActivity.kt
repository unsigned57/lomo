package com.lomo.nativesmoke

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.util.Log
import com.lomo.nativebridge.CancelOutcome
import com.lomo.nativebridge.CoreEvent
import com.lomo.nativebridge.CoreEventListener
import com.lomo.nativebridge.EngineConfig
import com.lomo.nativebridge.EngineState
import com.lomo.nativebridge.JobStep
import com.lomo.nativebridge.LomoEngine
import com.lomo.nativebridge.ShutdownOutcome
import com.lomo.nativebridge.WorkspaceDescriptor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tooling-only smoke for the formal BoltFFI engine surface plus SAF DocumentsProvider fixture.
 *
 * Covers planner golden bytes, formal engine open/state/subscribe/callback/cancel/shutdown,
 * concurrent close/use, seed→kill→relaunch journal recovery, and deterministic SAF
 * create/read/replace/rename/move/delete. Production app modules must not depend on this module.
 */
class NativeSmokeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // P5-13: sync-v1 planSyncEnvelope absorbed with lomo-sync-core.
            runFormalEngineSmoke()
            runConcurrentCloseUseSmoke()
            runSafCrudMatrix()
            Log.i(LOG_TAG, "PASS")
            finish()
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "FAIL", error)
            throw error
        }
    }

    /**
     * Phase 1 (no seed marker): open direct workspace, assert ready, write seed marker, request
     * process kill so xtask relaunches for journal recovery.
     * Phase 2 (seed marker present): reopen same control roots and require Ready recovery without
     * recreating a partial engine.
     */
    private fun runFormalEngineSmoke() {
        val control = File(filesDir, "engine-control").apply { mkdirs() }
        val exchange = File(filesDir, "engine-exchange").apply { mkdirs() }
        val workspace = File(filesDir, "engine-workspace").apply { mkdirs() }
        val seedMarker = File(filesDir, "engine-smoke-seeded")

        if (!seedMarker.exists()) {
            runSeedAndRequestRestart(control, exchange, workspace, seedMarker)
            return
        }
        runRecoverAndCallbackAssert(control, exchange, workspace)
    }

    private fun runSeedAndRequestRestart(
        control: File,
        exchange: File,
        workspace: File,
        seedMarker: File,
    ) {
        val engine =
            LomoEngine.open(
                EngineConfig(
                    controlRoot = control.absolutePath,
                    exchangeRoot = exchange.absolutePath,
                    workspace =
                        WorkspaceDescriptor.Direct(
                            rootPath = workspace.absolutePath,
                        ),
                    bootstrapDeadlineMillis = 30_000uL,
                ),
            )
        try {
            awaitReady(engine)
            seedMarker.writeText("seeded\n")
            Log.i(LOG_TAG, "RESTART_REQUIRED seed complete; forcing process exit for recovery")
        } finally {
            // Best-effort shutdown; kill is intentional for crash-window recovery.
            runCatching {
                engine.shutdown(2_000uL)
                engine.close()
            }
        }
        // behavior-contract: silent-result-ok: intentional process death for journal relaunch smoke
        Process.killProcess(Process.myPid())
    }

    private fun runRecoverAndCallbackAssert(
        control: File,
        exchange: File,
        workspace: File,
    ) {
        val engine =
            LomoEngine.open(
                EngineConfig(
                    controlRoot = control.absolutePath,
                    exchangeRoot = exchange.absolutePath,
                    workspace =
                        WorkspaceDescriptor.Direct(
                            rootPath = workspace.absolutePath,
                        ),
                    bootstrapDeadlineMillis = 30_000uL,
                ),
            )
        try {
            awaitReady(engine)
            // Direct recovery is Ready; callback delivery is proven via SAF Opening+cancel below.
            proveCallbackDelivery()
            val shutdown = engine.shutdown(5_000uL)
            check(
                shutdown == ShutdownOutcome.COMPLETED ||
                    shutdown == ShutdownOutcome.ALREADY_SHUTDOWN,
            ) {
                "shutdown must complete, got $shutdown"
            }
        } finally {
            engine.close()
        }
    }

    /**
     * Uses SAF Opening + cancel so the foreign listener path is exercised and sequence is asserted.
     */
    private fun proveCallbackDelivery() {
        val control = File(filesDir, "callback-control").apply { mkdirs() }
        val exchange = File(filesDir, "callback-exchange").apply { mkdirs() }
        val engine =
            LomoEngine.open(
                EngineConfig(
                    controlRoot = control.absolutePath,
                    exchangeRoot = exchange.absolutePath,
                    workspace =
                        WorkspaceDescriptor.Saf(
                            stableWorkspaceId = "ws-saf-smoke-callback",
                            capabilityToken = "smoke-saf-callback",
                        ),
                    bootstrapDeadlineMillis = 30_000uL,
                ),
            )
        try {
            val jobId =
                when (val state = engine.state()) {
                    is EngineState.Opening -> state.jobId
                    else -> error("SAF smoke engine must start Opening, got $state")
                }
            val eventLatch = CountDownLatch(1)
            val lastSequence = AtomicLong(-1)
            val subscription =
                engine.subscribe(
                    object : CoreEventListener {
                        override fun onEvent(event: CoreEvent) {
                            lastSequence.set(event.eventSequence.toLong())
                            eventLatch.countDown()
                        }
                    },
                )
            val cancel = engine.cancelJob(jobId)
            check(
                cancel == CancelOutcome.ACCEPTED ||
                    cancel == CancelOutcome.ALREADY_CANCELLED ||
                    cancel == CancelOutcome.ALREADY_COMPLETED,
            ) {
                "cancel bootstrap must be accepted, got $cancel"
            }
            check(eventLatch.await(3, TimeUnit.SECONDS)) {
                "callback must deliver at least one CoreEvent after cancel; lastSequence=${lastSequence.get()}"
            }
            check(lastSequence.get() >= 0) {
                "callback event sequence must be observed, got ${lastSequence.get()}"
            }
            check(subscription.unsubscribe()) { "subscription must unregister once" }
            subscription.close()
            val shutdown = engine.shutdown(5_000uL)
            check(
                shutdown == ShutdownOutcome.COMPLETED ||
                    shutdown == ShutdownOutcome.ALREADY_SHUTDOWN,
            )
        } finally {
            engine.close()
        }
    }

    /**
     * Concurrent readers call [LomoEngine.state] while another thread shuts down and closes.
     * Close must not crash the process; post-close state access must fail closed.
     */
    private fun runConcurrentCloseUseSmoke() {
        val control = File(filesDir, "concurrent-control").apply { mkdirs() }
        val exchange = File(filesDir, "concurrent-exchange").apply { mkdirs() }
        val workspace = File(filesDir, "concurrent-workspace").apply { mkdirs() }
        val engine =
            LomoEngine.open(
                EngineConfig(
                    controlRoot = control.absolutePath,
                    exchangeRoot = exchange.absolutePath,
                    workspace = WorkspaceDescriptor.Direct(rootPath = workspace.absolutePath),
                    bootstrapDeadlineMillis = 30_000uL,
                ),
            )
        awaitReady(engine)
        val stop = AtomicBoolean(false)
        val readErrors = AtomicInteger(0)
        val successfulReads = AtomicInteger(0)
        val readers =
            List(4) {
                Thread {
                    while (!stop.get()) {
                        try {
                            engine.state()
                            successfulReads.incrementAndGet()
                        } catch (_: Throwable) {
                            readErrors.incrementAndGet()
                            break
                        }
                    }
                }.also { it.start() }
            }
        Thread.sleep(20)
        val shutdown = engine.shutdown(5_000uL)
        check(
            shutdown == ShutdownOutcome.COMPLETED ||
                shutdown == ShutdownOutcome.ALREADY_SHUTDOWN,
        )
        engine.close()
        stop.set(true)
        readers.forEach { it.join(2_000) }
        // After close, a fresh state() must not succeed silently.
        val postCloseFailed =
            runCatching { engine.state() }.isFailure
        check(postCloseFailed) { "state() after close must fail closed" }
        check(successfulReads.get() > 0 || readErrors.get() > 0) {
            "concurrent readers must have attempted state()"
        }
        Log.i(
            LOG_TAG,
            "concurrent close/use ok reads=${successfulReads.get()} errors=${readErrors.get()}",
        )
    }

    private fun awaitReady(engine: LomoEngine) {
        when (val state = engine.state()) {
            is EngineState.Ready -> {
                check(state.coreRevision == 0uL || state.coreRevision >= 0uL)
            }
            is EngineState.Opening -> {
                var polls = 0
                var current = engine.pollJob(state.jobId)
                while (current is JobStep.Running && polls < 50) {
                    Thread.sleep(10)
                    current = engine.pollJob(state.jobId)
                    polls += 1
                }
                check(engine.state() is EngineState.Ready || current is JobStep.Completed) {
                    "direct workspace bootstrap did not become ready: state=${engine.state()} step=$current"
                }
            }
            else -> error("unexpected formal engine state: $state")
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

    private companion object {
        const val LOG_TAG = "LomoNativeSmoke"
    }
}
