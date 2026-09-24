package com.lomo.data.engine

import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.nativebridge.EngineConfig
import com.lomo.nativebridge.LomoEngine
import com.lomo.nativebridge.PlatformActionBatch
import com.lomo.nativebridge.PlatformBatchHost
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
     * The port is handed to [RustEngineAdapter.acquire] in the same expression that creates it, so
     * there is no statement in between where a failure could strand an open engine and its
     * workspace lock with nothing left holding a reference to close them.
     */
    fun openAdapter(
        request: NativeEngineOpenRequest,
        executor: AndroidPlatformActionExecutor,
        invalidation: StoreInvalidationBus,
    ): RustEngineAdapter {
        val port = openPort(request)
        if (request.workspace != null) {
            port.openWorkspaceSession(
                host = SessionPlatformBatchHost(executor),
                timeZone = ZoneId.systemDefault().id,
                mediaStageRoot = request.mediaStageRoot.absolutePath,
            )
        }
        return RustEngineAdapter.acquire(
            native = port,
            platformBatchRunner = PlatformBatchRunner(native = port, executor = executor),
            invalidation = invalidation,
        )
    }

    /**
     * Named callback so the generated-binding reachability contract records an explicit
     * `PlatformBatchHost.execute` override edge; a SAM lambda is invisible to the symbol model.
     */
    private class SessionPlatformBatchHost(
        private val executor: AndroidPlatformActionExecutor,
    ) : PlatformBatchHost {
        override fun execute(batch: PlatformActionBatch) = executor.execute(batch)
    }

    fun openPort(
        request: NativeEngineOpenRequest,
    ): BoltFfiNativeEnginePort {
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
 * Application-private engine roots under `filesDir/lomo-engine/v1/`.
 *
 * Matches the stage-1 journal placement contract (control + exchange outside workspace vault).
 */
internal data class NativeEngineOpenRequest(
    val controlRoot: File,
    val exchangeRoot: File,
    val mediaStageRoot: File,
    val workspace: NativeWorkspaceSelection? = null,
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
    data class Direct(
        val grant: DirectCapabilityGrant,
    ) : NativeWorkspaceSelection {
        val rootPath: File
            get() = grant.canonicalRoot

        val capabilityToken: String
            get() = grant.capabilityToken

        val stableWorkspaceId: StableWorkspaceId
            get() = grant.stableWorkspaceId
    }

    data class Saf(
        val grant: SafCapabilityGrant,
    ) : NativeWorkspaceSelection {
        val stableWorkspaceId: StableWorkspaceId
            get() = grant.stableWorkspaceId

        val capabilityToken: String
            get() = grant.capabilityToken
    }
}

private fun NativeWorkspaceSelection.toBridge(): WorkspaceDescriptor =
    when (this) {
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
