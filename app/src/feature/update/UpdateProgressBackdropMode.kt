package com.lomo.app.feature.update

import android.os.Build

internal enum class UpdateProgressBackdropMode {
    Shader,
    Fallback,
}

internal inline fun resolveUpdateProgressBackdropMode(
    sdkInt: Int,
    shaderProbe: () -> Unit,
): UpdateProgressBackdropMode {
    if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
        return UpdateProgressBackdropMode.Fallback
    }
    if (sdkInt >= BACKDROP_SHADER_DISABLED_SDK) {
        return UpdateProgressBackdropMode.Fallback
    }
    return runCatching(shaderProbe)
        .fold(
            onSuccess = { UpdateProgressBackdropMode.Shader },
            onFailure = { UpdateProgressBackdropMode.Fallback },
        )
}

private const val BACKDROP_SHADER_DISABLED_SDK = 36
