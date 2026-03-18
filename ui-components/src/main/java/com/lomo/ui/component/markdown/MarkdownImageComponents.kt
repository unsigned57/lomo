package com.lomo.ui.component.markdown

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.R
import com.lomo.ui.theme.LomoTheme
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
    sharedElementKey: String = image.destination,
) {
    val destination = image.destination
    val context = LocalContext.current
    val defaultContentDescription = stringResource(R.string.markdown_image_content_description)

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
                    rememberSharedContentState(key = sharedElementKey),
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

    val painter = rememberAsyncImagePainter(model)
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

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is AsyncImagePainter.State.Loading -> {
                ImageLoadingPlaceholder(Modifier.fillMaxSize())
            }

            is AsyncImagePainter.State.Success -> {
                Image(
                    painter = painter,
                    contentDescription = image.title ?: defaultContentDescription,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is AsyncImagePainter.State.Error -> {
                ImageErrorPlaceholder(Modifier.fillMaxSize())
            }

            else -> {
                ImageEmptyPlaceholder(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
internal fun MarkdownImagePager(
    images: List<Image>,
    onImageClick: ((String) -> Unit)? = null,
) {
    if (images.isEmpty()) return
    if (images.size == 1) {
        MarkdownImageBlock(
            image = images.first(),
            onImageClick = onImageClick,
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { images.size })
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val current = images[page]
            Box(modifier = Modifier.fillMaxSize()) {
                MarkdownImageBlock(
                    image = current,
                    onImageClick = onImageClick,
                )
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            images.forEachIndexed { index, _ ->
                val isActive = pagerState.currentPage == index
                Box(
                    modifier =
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (isActive) 8.dp else 6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (isActive) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                                },
                            ),
                )
            }
        }
    }
}

@Composable
private fun ImageLoadingPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveLoadingIndicator(
            modifier = Modifier.size(24.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun ImageErrorPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.markdown_image_load_failed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ImageEmptyPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                    RoundedCornerShape(8.dp),
                ),
    )
}

@Preview(showBackground = true)
@Composable
private fun MarkdownImagePlaceholdersPreview() {
    LomoTheme {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            ImageLoadingPlaceholder()
            ImageErrorPlaceholder()
            ImageEmptyPlaceholder()
        }
    }
}
