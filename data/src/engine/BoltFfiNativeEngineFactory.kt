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
    data class Direct(
        val rootPath: File,
    ) : NativeWorkspaceSelection {
        init {
            rootPath.mkdirs()
            require(rootPath.isDirectory) { "direct workspace root is not a directory: $rootPath" }
        }
    }

    data class Saf(
        val capabilityToken: String,
    ) : NativeWorkspaceSelection {
        init {
            require(capabilityToken.isNotBlank()) { "SAF capability token must be non-blank" }
        }
    }
}

private fun NativeWorkspaceSelection.toBridge(): WorkspaceDescriptor =
    when (this) {
        is NativeWorkspaceSelection.Direct ->
            WorkspaceDescriptor.Direct(rootPath = rootPath.absolutePath)
        is NativeWorkspaceSelection.Saf ->
            WorkspaceDescriptor.Saf(capabilityToken = capabilityToken)
    }
