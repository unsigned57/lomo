package com.lomo.ui.component.markdown

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
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

private const val IMAGE_CACHE_LOAD_FACTOR = 0.75f
private val MARKDOWN_IMAGE_CORNER_RADIUS = 8.dp
private val MARKDOWN_IMAGE_VERTICAL_PADDING = 4.dp
private val MARKDOWN_IMAGE_INDICATOR_ACTIVE_SIZE = 8.dp
private val MARKDOWN_IMAGE_INDICATOR_INACTIVE_SIZE = 6.dp
private val MARKDOWN_IMAGE_INDICATOR_SHAPE_RADIUS = 999.dp
private const val MARKDOWN_IMAGE_INDICATOR_ACTIVE_ALPHA = 0.85f
private const val MARKDOWN_IMAGE_INDICATOR_INACTIVE_ALPHA = 0.65f

internal object MarkdownImageCache {
    private const val MAX_CACHE_SIZE = 200
    private val lock = Any()
    private val cache =
        object : LinkedHashMap<String, Float>(MAX_CACHE_SIZE, IMAGE_CACHE_LOAD_FACTOR, true) {
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
        rememberSharedImageModifier(
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedElementKey = sharedElementKey,
        )

    val ratio = aspectRatio
    val modifier =
        rememberImageBlockModifier(
            ratio = ratio,
            destination = destination,
            onImageClick = onImageClick,
            sharedModifier = sharedModifier,
        )

    val painter = rememberAsyncImagePainter(model)
    val state by painter.state.collectAsState()

    val newAspectRatio = state.resolvedAspectRatio()

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
                PagerIndicatorDot(isActive = pagerState.currentPage == index)
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun rememberSharedImageModifier(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String,
): Modifier =
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

@Composable
private fun rememberImageBlockModifier(
    ratio: Float?,
    destination: String,
    onImageClick: ((String) -> Unit)?,
    sharedModifier: Modifier,
): Modifier {
    val baseModifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MARKDOWN_IMAGE_CORNER_RADIUS))
            .padding(vertical = MARKDOWN_IMAGE_VERTICAL_PADDING)
    val ratioModifier = ratio?.let { baseModifier.aspectRatio(it) } ?: baseModifier
    val clickableModifier =
        onImageClick?.let { clickHandler ->
            ratioModifier.clickable { clickHandler(destination) }
        } ?: ratioModifier
    return clickableModifier.then(sharedModifier)
}

private fun AsyncImagePainter.State.resolvedAspectRatio(): Float? =
    (this as? AsyncImagePainter.State.Success)?.painter?.intrinsicSize?.let { size ->
        if (size.width > 0f && size.height > 0f) {
            size.width / size.height
        } else {
            null
        }
    }

@Composable
private fun PagerIndicatorDot(isActive: Boolean) {
    val indicatorSize =
        if (isActive) MARKDOWN_IMAGE_INDICATOR_ACTIVE_SIZE else MARKDOWN_IMAGE_INDICATOR_INACTIVE_SIZE
    val indicatorColor =
        if (isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = MARKDOWN_IMAGE_INDICATOR_ACTIVE_ALPHA)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = MARKDOWN_IMAGE_INDICATOR_INACTIVE_ALPHA)
        }

    Box(
        modifier =
            Modifier
                .padding(horizontal = 3.dp)
                .size(indicatorSize)
                .clip(RoundedCornerShape(MARKDOWN_IMAGE_INDICATOR_SHAPE_RADIUS))
                .background(indicatorColor),
    )
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
