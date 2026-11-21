package com.lomo.data.engine

import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.WorkspaceFailureStage
import com.lomo.nativebridge.EngineConfig
import com.lomo.nativebridge.EngineError
import com.lomo.nativebridge.EngineFailure
import com.lomo.nativebridge.LomoEngine
import com.lomo.nativebridge.PlatformActionBatch
import com.lomo.nativebridge.PlatformBatchHost
import com.lomo.nativebridge.PlatformBatchResult
import com.lomo.nativebridge.SessionOpenResult
import com.lomo.nativebridge.WorkspaceDescriptor
import java.io.File
import java.time.ZoneId

/**
 * Opens the sole production [BoltFfiNativeEnginePort] / [RustEngineAdapter] pair.
 *
 * Generated [LomoEngine] never leaves this factory + port boundary. Callers supply filesystem
 * roots only; domain code sees [com.lomo.domain.repository.EngineReadinessRepository].
 */
internal object BoltFfiNativeEngineFactory {
    /**
     * Opens one adapter as a single ownership transaction.
     *
     * The factory owns the port through session open. [RustEngineAdapter.acquire] takes ownership
     * only after that succeeds and handles failures during adapter acquisition.
     */
    fun openAdapter(
        request: NativeEngineOpenRequest,
        executor: AndroidPlatformActionExecutor,
        invalidation: StoreInvalidationBus,
    ): RustEngineAdapter = withEngineFailureConversion(WorkspaceFailureStage.OPEN) {
        val port = openPort(request)
        val platformBatchRunner = PlatformBatchRunner(native = port, executor = executor)
        val sessionOpen =
            runCatching { openSession(request, port, executor, platformBatchRunner) }
                .getOrElse { failure ->
                    runCatching(port::close).exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
        RustEngineAdapter.acquire(
            native = port,
            platformBatchRunner = platformBatchRunner,
            invalidation = invalidation,
            sessionOpenResult = sessionOpen,
        )
    }

    private fun openSession(
        request: NativeEngineOpenRequest,
        port: BoltFfiNativeEnginePort,
        executor: AndroidPlatformActionExecutor,
        platformBatchRunner: PlatformBatchRunner,
    ): SessionOpenResult? = if (request.workspace != null) {
        // The persisted bootstrap job's deadline is measured from engine open, so its
        // capability probe must finish before the session mounts: a SAF first-mount
        // reconcile blocks openWorkspaceSession for the whole data-dependent scan and
        // would leave the probe WaitingPlatform until it expires unexecuted.
        when (val state = port.state()) {
            is NativeEngineSnapshot.Opening -> requireCompletedProbe(platformBatchRunner, state.jobId)
            is NativeEngineSnapshot.ReadOnlyRecovery -> rejectProbe(state.failure)
            else -> Unit
        }
        port.openWorkspaceSession(
            host = SessionPlatformBatchHost(executor),
            timeZone = ZoneId.systemDefault().id,
            // The configured storage filename token crosses unchanged; Rust `DateFormat`
            // is the only authority that resolves or rejects it. A workspace request that
            // lost its token is a programming error, not a recoverable preference.
            dateFormat =
            requireNotNull(request.dateFormat) {
                "Workspace session open requires a date format token"
            },
            mediaStageRoot = request.mediaStageRoot.absolutePath,
        )
    } else {
        null
    }

    private fun requireCompletedProbe(runner: PlatformBatchRunner, jobId: String) {
        when (val step = runner.drive(jobId)) {
            NativeJobStep.Completed -> Unit

            is NativeJobStep.Failed -> rejectProbe(step.failure, jobId)

            is NativeJobStep.BlockedByConflict -> rejectProbe(step.failure, jobId)

            else -> throw engineCommandFailure(
                category = EngineFailureCategory.TIMEOUT,
                code = "workspace_probe_incomplete",
                retryDisposition = EngineRetryDisposition.TRANSIENT,
                diagnostic = "Workspace capability probe did not reach a terminal result",
                jobId = jobId,
                stage = WorkspaceFailureStage.PROBE,
            )
        }
    }

    private fun rejectProbe(failure: EngineFailureSnapshot, jobId: String? = null): Nothing =
        throw EngineCommandFailureException(
            failure.toEngineCommandFailure(jobId = jobId).copy(workspaceStage = WorkspaceFailureStage.PROBE),
        )

    /**
     * Named callback so the generated-binding reachability contract records an explicit
     * `PlatformBatchHost.execute` override edge; a SAM lambda is invisible to the symbol model.
     */
    internal class SessionPlatformBatchHost(private val executor: AndroidPlatformActionExecutor) : PlatformBatchHost {
        /**
         * The generated JNI glue decodes only a wire `PlatformBatchResult`/`EngineError`: any
         * other Kotlin throwable crosses the callback as a pending exception and panics inside
         * the Rust wire decoder, so unexpected host failures are surfaced as typed engine errors.
         */
        override fun execute(batch: PlatformActionBatch): PlatformBatchResult = try {
            executor.execute(batch)
        } catch (error: EngineError) {
            throw error
        } catch (failure: Exception) {
            throw failure.asPlatformBatchHostError(batch)
        }
    }

    fun openPort(request: NativeEngineOpenRequest): BoltFfiNativeEnginePort {
        val engine =
            LomoEngine.open(
                EngineConfig(
                    controlRoot = request.controlRoot.absolutePath,
                    exchangeRoot = request.exchangeRoot.absolutePath,
                    workspace = request.workspace?.toBridge(),
                    bootstrapDeadlineMillis = request.bootstrapDeadlineMillis,
                ),
            )
        return BoltFfiNativeEnginePort(engine)
    }
}

/**
 * Wraps an unexpected host failure in the typed wire error while keeping the original exception
 * as the cause. The generated [EngineError] has no cause constructor parameter, so the cause is
 * attached through `initCause`; the wire payload still carries only the string diagnostic.
 */
private fun Exception.asPlatformBatchHostError(batch: PlatformActionBatch): EngineError = EngineError
    .Failure(
        EngineFailure(
            category = "platform",
            code = "platform_batch_host_error",
            retryDisposition = "transient",
            operationId = null,
            jobId = batch.jobId,
            diagnostic = message ?: javaClass.simpleName,
        ),
    ).apply { initCause(this@asPlatformBatchHostError) }

/**
 * Application-private engine roots under `filesDir/lomo-engine/v1/`.
 *
 * Matches the stage-1 journal placement contract (control + exchange outside workspace vault).
 */
internal data class NativeEngineOpenRequest(
    val controlRoot: File,
    val exchangeRoot: File,
    val mediaStageRoot: File,
    val workspace: NativeWorkspaceSelection? = null,
    /**
     * Configured storage filename date token, transported verbatim to `openWorkspaceSession`.
     * Null only for bootstrap requests: they never open a workspace session.
     */
    val dateFormat: String? = null,
    val bootstrapDeadlineMillis: ULong = DEFAULT_BOOTSTRAP_DEADLINE_MILLIS,
) {
    init {
        controlRoot.mkdirs()
        exchangeRoot.mkdirs()
        require(controlRoot.isDirectory) { "control root is not a directory: $controlRoot" }
        require(exchangeRoot.isDirectory) { "exchange root is not a directory: $exchangeRoot" }
    }

    companion object {
        const val DEFAULT_BOOTSTRAP_DEADLINE_MILLIS: ULong = 30_000uL

        /** Default roots for an Android application files directory. */
        fun forAppFilesDir(filesDir: File): NativeEngineOpenRequest {
            val base = File(filesDir, "lomo-engine/v1")
            return NativeEngineOpenRequest(
                controlRoot = File(base, "control"),
                exchangeRoot = File(base, "exchange"),
                mediaStageRoot =
                File(
                    filesDir,
                    com.lomo.data.engine.media.HOST_MEDIA_STAGE_ROOT_NAME,
                ),
                workspace = null,
            )
        }
    }
}

internal sealed interface NativeWorkspaceSelection {
    /**
     * Direct filesystem workspace bound to a registered root capability.
     *
     * The grant is the execution root. Constructing a selection cannot create a missing directory:
     * [CapabilityRegistry.registerDirect] fails closed when the path is not an existing directory.
     */
    data class Direct(val grant: DirectCapabilityGrant) : NativeWorkspaceSelection {
        val rootPath: File
            get() = grant.canonicalRoot

        val capabilityToken: String
            get() = grant.capabilityToken

        val stableWorkspaceId: StableWorkspaceId
            get() = grant.stableWorkspaceId
    }

    data class Saf(val grant: SafCapabilityGrant) : NativeWorkspaceSelection {
        val stableWorkspaceId: StableWorkspaceId
            get() = grant.stableWorkspaceId

        val capabilityToken: String
            get() = grant.capabilityToken
    }
}

private fun NativeWorkspaceSelection.toBridge(): WorkspaceDescriptor = when (this) {
    is NativeWorkspaceSelection.Direct ->
        WorkspaceDescriptor.Direct(
            rootPath = rootPath.absolutePath,
            capabilityToken = capabilityToken,
        )

    is NativeWorkspaceSelection.Saf ->
        WorkspaceDescriptor.Saf(
            stableWorkspaceId = stableWorkspaceId.value,
            capabilityToken = capabilityToken,
        )
}
