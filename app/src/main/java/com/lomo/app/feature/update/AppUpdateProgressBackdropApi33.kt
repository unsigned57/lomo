package com.lomo.app.feature.update

import android.content.res.Resources
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalResources
import com.lomo.app.R

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun ShaderUpdateProgressBackdrop(modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val shaderSource =
        remember(resources) {
            resources
                .openRawResource(R.raw.update_progress_shader)
                .bufferedReader()
                .use { it.readText() }
        }
    val shader =
        remember(shaderSource) {
            runCatching {
                RuntimeShader(shaderSource)
            }.getOrNull()
        }
    Box(modifier = modifier) {
        if (shader == null) {
            FallbackUpdateProgressBackdrop(modifier = Modifier.fillMaxSize())
            return@Box
        }

        val shaderBrush = remember(shader) { ShaderBrush(shader) }
        val timeSeconds by animatedBackdropTimeSeconds()
        val colors = progressBackdropColors()

        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = SHADER_SURFACE_OVERLAY_ALPHA),
                    ),
        ) {
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("time", timeSeconds)
            shader.setFloatUniform("distortionStrength", SHADER_DISTORTION_STRENGTH)
            shader.setFloatUniform("swirlStrength", SHADER_SWIRL_STRENGTH)
            shader.setColorUniform("colorA", colors.colorA.toArgb())
            shader.setColorUniform("colorB", colors.colorB.toArgb())
            shader.setColorUniform("colorC", colors.colorC.toArgb())
            shader.setColorUniform("colorD", colors.colorD.toArgb())
            drawRect(brush = shaderBrush)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun probeUpdateProgressShader(resources: Resources) {
    val shaderSource =
        resources
            .openRawResource(R.raw.update_progress_shader)
            .bufferedReader()
            .use { it.readText() }
    RuntimeShader(shaderSource)
}

private const val SHADER_SURFACE_OVERLAY_ALPHA = 0.0f
private const val SHADER_DISTORTION_STRENGTH = 0.35f
private const val SHADER_SWIRL_STRENGTH = 0.20f
