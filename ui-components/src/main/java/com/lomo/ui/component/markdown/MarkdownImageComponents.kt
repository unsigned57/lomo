package com.lomo.ui.component.markdown

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.decode.DataSource
import coil3.request.ImageRequest
import org.commonmark.node.Image

/**
 * Extracted image-related components from MarkdownRenderer.kt
 * to improve code organization and reduce file size.
 *
 * NOTE: These components are provided as alternatives/replacements for the
 * inline implementations in MarkdownRenderer.kt. To fully adopt these,
 * the MarkdownRenderer.kt file should be updated to use these extracted components.
 */

// ========== Image Ratio Cache ==========
// Renamed to avoid conflict with inline implementation in MarkdownRenderer.kt

internal object MarkdownImageCache {
    private const val MAX_CACHE_SIZE = 200
    private val cache =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Float>(MAX_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, Float>): Boolean = size > MAX_CACHE_SIZE
            },
        )

    fun get(url: String): Float? = cache[url]

    fun put(
        url: String,
        ratio: Float,
    ) {
        cache[url] = ratio
    }
}

// ========== Markdown Image Component ==========
// Renamed to avoid conflict with inline implementation in MarkdownRenderer.kt

@Composable
internal fun MarkdownImageBlock(
    image: Image,
    onImageClick: ((String) -> Unit)? = null,
) {
    val destination = image.destination
    val context = LocalContext.current

    val model =
        remember(destination, context) {
            ImageRequest.Builder(context).data(destination).build()
        }

    val aspectRatio = MarkdownImageCache.get(destination)
    val modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp)
            .let { if (aspectRatio != null) it.aspectRatio(aspectRatio) else it }
            .let { if (onImageClick != null) it.clickable { onImageClick(destination) } else it }

    SubcomposeAsyncImage(
        model = model,
        contentDescription = image.title ?: "Image",
        modifier = modifier,
        contentScale = ContentScale.FillWidth,
    ) {
        val state by painter.state.collectAsState()

        when (state) {
            is AsyncImagePainter.State.Loading -> {
                ImageLoadingPlaceholder()
            }

            is AsyncImagePainter.State.Success -> {
                val successState = state as AsyncImagePainter.State.Success
                val size = successState.painter.intrinsicSize
                if (size.width > 0 && size.height > 0) {
                    MarkdownImageCache.put(destination, size.width / size.height)
                }

                if (successState.result.dataSource != DataSource.MEMORY_CACHE) {
                    ImageWithFadeIn(painter, image.title)
                } else {
                    Image(
                        painter = painter,
                        contentDescription = image.title ?: "Image",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            is AsyncImagePainter.State.Error -> {
                ImageErrorPlaceholder()
            }

            else -> {
                ImageEmptyPlaceholder()
            }
        }
    }
}

@Composable
private fun ImageLoadingPlaceholder() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun ImageErrorPlaceholder() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "⚠ Image failed to load",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ImageEmptyPlaceholder() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                    RoundedCornerShape(8.dp),
                ),
    )
}

@Composable
private fun ImageWithFadeIn(
    painter: coil3.compose.AsyncImagePainter,
    title: String?,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(300)),
        exit = fadeOut(),
    ) {
        Image(
            painter = painter,
            contentDescription = title ?: "Image",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
