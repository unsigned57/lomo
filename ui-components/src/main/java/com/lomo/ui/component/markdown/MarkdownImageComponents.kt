package com.lomo.ui.component.markdown

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.lomo.ui.util.LocalAnimatedVisibilityScope
import com.lomo.ui.util.LocalSharedTransitionScope
import org.commonmark.node.Image

internal object MarkdownImageCache {
    private const val MAX_CACHE_SIZE = 200
    private val lock = Any()
    private val cache =
        object : LinkedHashMap<String, Float>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Float>): Boolean = size > MAX_CACHE_SIZE
        }

    fun get(url: String): Float? =
        synchronized(lock) {
            cache[url]
        }

    fun put(
        url: String,
        ratio: Float,
    ) {
        synchronized(lock) {
            cache[url] = ratio
        }
    }
}

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

    var aspectRatio by remember(destination) { mutableStateOf(MarkdownImageCache.get(destination)) }

    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

    @OptIn(ExperimentalSharedTransitionApi::class)
    val sharedModifier =
        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier.sharedElement(
                    rememberSharedContentState(key = destination),
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
        } else {
            Modifier
        }

    val ratio = aspectRatio
    val modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp)
            .let { base -> if (ratio != null) base.aspectRatio(ratio) else base }
            .let { clickable -> if (onImageClick != null) clickable.clickable { onImageClick(destination) } else clickable }
            .then(sharedModifier)

    SubcomposeAsyncImage(
        model = model,
        contentDescription = image.title ?: "Image",
        modifier = modifier,
        contentScale = ContentScale.FillWidth,
    ) {
        val state by painter.state.collectAsState()

        val newAspectRatio =
            (state as? AsyncImagePainter.State.Success)?.painter?.intrinsicSize?.let { size ->
                if (size.width > 0f && size.height > 0f) size.width / size.height else null
            }

        LaunchedEffect(destination, newAspectRatio) {
            if (newAspectRatio != null && newAspectRatio != aspectRatio) {
                MarkdownImageCache.put(destination, newAspectRatio)
                aspectRatio = newAspectRatio
            }
        }

        when (val current = state) {
            is AsyncImagePainter.State.Loading -> {
                ImageLoadingPlaceholder()
            }

            is AsyncImagePainter.State.Success -> {
                if (current.result.dataSource != DataSource.MEMORY_CACHE) {
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
