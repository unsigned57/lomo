package com.lomo.app.feature.update

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalResources

@Composable
internal fun UpdateProgressBackdrop(modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val backdropMode =
        remember(resources) {
            resolveUpdateProgressBackdropMode(Build.VERSION.SDK_INT) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    probeUpdateProgressShader(resources)
                }
            }
        }
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        backdropMode == UpdateProgressBackdropMode.Shader
    ) {
        ShaderUpdateProgressBackdrop(modifier = modifier)
    } else {
        FallbackUpdateProgressBackdrop(modifier = modifier)
    }
}

@Composable
internal fun FallbackUpdateProgressBackdrop(modifier: Modifier = Modifier) {
    val colors = progressBackdropColors().asList()
    val timeSeconds by animatedBackdropTimeSeconds()

    Canvas(
        modifier =
            modifier.background(
                MaterialTheme.colorScheme.surface.copy(alpha = FALLBACK_SURFACE_OVERLAY_ALPHA),
            ),
    ) {
        val offsets =
            FALLBACK_BLOBS.map { blob ->
                blob.offsetAt(size.width, size.height, timeSeconds)
            }
        offsets.forEachIndexed { index, center ->
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                colors[index].copy(alpha = FALLBACK_BLOB_ALPHA),
                                colors[index].copy(alpha = Color.Transparent.alpha),
                            ),
                        center = center,
                        radius = size.minDimension * FALLBACK_BLOB_RADIUS_FACTOR,
                    ),
                radius = size.minDimension * FALLBACK_BLOB_RADIUS_FACTOR,
                center = center,
            )
        }
    }
}

@Composable
internal fun progressBackdropColors(): BackdropColors {
    val scheme = MaterialTheme.colorScheme
    val toAnchor: (Color) -> Color = { color ->
        if (scheme.background.luminance() < BACKDROP_DARK_THEME_LUMINANCE_THRESHOLD) {
            lerp(color, Color.Black, BACKDROP_COLOR_MIX_RATIO)
        } else {
            lerp(color, Color.White, BACKDROP_COLOR_MIX_RATIO)
        }
    }
    return BackdropColors(
        colorA = toAnchor(scheme.primary),
        colorB = toAnchor(scheme.secondary),
        colorC = toAnchor(scheme.tertiary),
        colorD = toAnchor(scheme.inversePrimary),
    )
}

@Composable
internal fun animatedBackdropTimeSeconds() =
    produceState(initialValue = INITIAL_TIME_SECONDS) {
        var startNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (startNanos == 0L) {
                    startNanos = now
                }
                value = (now - startNanos) / NANOS_PER_SECOND
            }
        }
    }

private data class BackdropBlob(
    val xBase: Float,
    val xAmplitude: Float,
    val xFrequency: Float,
    val xUsesSine: Boolean,
    val yBase: Float,
    val yAmplitude: Float,
    val yFrequency: Float,
    val yUsesSine: Boolean,
) {
    fun offsetAt(width: Float, height: Float, timeSeconds: Float): Offset =
        Offset(
            x = width * (xBase + xAmplitude * wave(timeSeconds, xFrequency, xUsesSine)),
            y = height * (yBase + yAmplitude * wave(timeSeconds, yFrequency, yUsesSine)),
        )
}

internal data class BackdropColors(
    val colorA: Color,
    val colorB: Color,
    val colorC: Color,
    val colorD: Color,
) {
    fun asList(): List<Color> = listOf(colorA, colorB, colorC, colorD)
}

private fun wave(timeSeconds: Float, frequency: Float, usesSine: Boolean): Float =
    if (usesSine) {
        kotlin.math.sin(timeSeconds * frequency)
    } else {
        kotlin.math.cos(timeSeconds * frequency)
    }

private const val FALLBACK_SURFACE_OVERLAY_ALPHA = 0.0f
private const val FALLBACK_BLOB_ALPHA = 0.52f
private const val FALLBACK_BLOB_RADIUS_FACTOR = 0.42f
private const val BACKDROP_COLOR_MIX_RATIO = 0.20f
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val INITIAL_TIME_SECONDS = 0f
private const val BACKDROP_DARK_THEME_LUMINANCE_THRESHOLD = 0.5f

private val FALLBACK_BLOBS =
    listOf(
        BackdropBlob(
            xBase = 0.25f,
            xAmplitude = 0.10f,
            xFrequency = 0.35f,
            xUsesSine = true,
            yBase = 0.22f,
            yAmplitude = 0.08f,
            yFrequency = 0.27f,
            yUsesSine = false,
        ),
        BackdropBlob(
            xBase = 0.76f,
            xAmplitude = 0.08f,
            xFrequency = 0.23f,
            xUsesSine = false,
            yBase = 0.28f,
            yAmplitude = 0.10f,
            yFrequency = 0.31f,
            yUsesSine = true,
        ),
        BackdropBlob(
            xBase = 0.34f,
            xAmplitude = 0.08f,
            xFrequency = 0.19f,
            xUsesSine = false,
            yBase = 0.76f,
            yAmplitude = 0.07f,
            yFrequency = 0.22f,
            yUsesSine = true,
        ),
        BackdropBlob(
            xBase = 0.76f,
            xAmplitude = 0.06f,
            xFrequency = 0.17f,
            xUsesSine = true,
            yBase = 0.72f,
            yAmplitude = 0.06f,
            yFrequency = 0.25f,
            yUsesSine = false,
        ),
    )
