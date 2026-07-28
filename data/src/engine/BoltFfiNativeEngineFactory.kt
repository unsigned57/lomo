package com.lomo.data.engine

import com.lomo.nativebridge.EngineConfig
import com.lomo.nativebridge.LomoEngine
import com.lomo.nativebridge.WorkspaceDescriptor
import java.io.File

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
        exchangeResolver: ExchangeResolver,
        executor: AndroidPlatformActionExecutor,
    ): RustEngineAdapter {
        val port = openPort(request, exchangeResolver)
        return RustEngineAdapter.acquire(
            native = port,
            platformBatchRunner = PlatformBatchRunner(native = port, executor = executor),
        )
    }

    fun openPort(
        request: NativeEngineOpenRequest,
        exchangeResolver: ExchangeResolver,
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
        return BoltFfiNativeEnginePort(engine, exchangeResolver)
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
                workspace = null,
            )
        }
    }
}

internal sealed interface NativeWorkspaceSelection {
    /**
     * Pure description of a filesystem workspace root.
     *
     * Describing a location must never bring it into existence: creating a missing or unmounted
     * root here is what turned "my notes are gone" into a Ready empty workspace instead of typed
     * Recovery. Existence, readability and writability belong to [WorkspaceCandidateProbe], and
     * `WorkspaceDescriptor::direct` in the core rejects a root it cannot canonicalize.
     */
    data class Direct(
        val rootPath: File,
    ) : NativeWorkspaceSelection

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
            WorkspaceDescriptor.Direct(rootPath = rootPath.absolutePath)
        is NativeWorkspaceSelection.Saf ->
            WorkspaceDescriptor.Saf(
                stableWorkspaceId = stableWorkspaceId.value,
                capabilityToken = capabilityToken,
            )
    }
