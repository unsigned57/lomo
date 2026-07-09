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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import kotlinx.collections.immutable.ImmutableList
import coil3.request.ImageRequest
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.component.image.rememberRetainedSuccessPainter
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.*
import com.lomo.ui.theme.LomoTheme
import com.lomo.ui.util.SynchronizedLruStore
import com.lomo.ui.util.LocalAnimatedVisibilityScope
import com.lomo.ui.util.LocalSharedTransitionScope
import kotlinx.coroutines.flow.collectLatest

internal object MarkdownImageCache {
    private const val MAX_CACHE_SIZE = 200
    private val cache = SynchronizedLruStore<String, Float>(MAX_CACHE_SIZE)

    fun get(url: String): Float? = cache.get(url)

    fun put(
        url: String,
        ratio: Float,
    ) {
        cache.put(url, ratio)
    }
}

internal fun resolveMarkdownImageSharedElementKey(
    destination: String,
    hasNavigationTarget: Boolean,
    namespace: String? = null,
): String? {
    if (!hasNavigationTarget) return null
    val normalizedDestination = destination.trim()
    if (normalizedDestination.isEmpty()) return null
    val normalizedNamespace = namespace?.trim().orEmpty()
    return if (normalizedNamespace.isEmpty()) {
        normalizedDestination
    } else {
        "$normalizedNamespace|$normalizedDestination"
    }
}

@Composable
internal fun MarkdownImageBlock(
    image: ModernMarkdownImage,
    onImageClick: ((String) -> Unit)? = null,
    sharedElementKey: String? =
        resolveMarkdownImageSharedElementKey(
            destination = image.destination,
            hasNavigationTarget = onImageClick != null,
        ),
) {
    val destination = image.destination
    val context = LocalContext.current
    val defaultContentDescription = stringResource(Res.string.markdown_image_content_description)
    val sizeResolver = rememberConstraintsSizeResolver()

    val model =
        remember(destination, context, sizeResolver) {
            ImageRequest
                .Builder(context)
                .data(destination)
                .memoryCacheKey(destination)
                .placeholderMemoryCacheKey(destination)
                .size(sizeResolver)
                .build()
        }

    var aspectRatio by remember(destination) { mutableStateOf(MarkdownImageCache.get(destination)) }

    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

    @OptIn(ExperimentalSharedTransitionApi::class)
    val sharedModifier =
        Modifier.rememberSharedImageModifier(
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedElementKey = sharedElementKey,
        )

    val painter = rememberAsyncImagePainter(model)
    val state by painter.state.collectAsState()
    val retainedSuccessPainter = rememberRetainedSuccessPainter(modelKey = destination, state = state)

    val newAspectRatio = state.resolvedAspectRatio()
    val successPainter = state.successPainter()
    val ratio = resolveMarkdownImageLayoutRatio(cachedRatio = aspectRatio, freshRatio = newAspectRatio)
    val modifier =
        Modifier.rememberImageBlockModifier(
            ratio = ratio,
            destination = destination,
            onImageClick = onImageClick,
            sharedModifier = sharedModifier,
        ).then(sizeResolver)

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
        when (
            resolveMarkdownImagePresentation(
                loadState = state.toMarkdownImageLoadState(),
                hasRetainedSuccess = retainedSuccessPainter != null,
            )
        ) {
            MarkdownImagePresentation.LoadingPlaceholder -> {
                ImageLoadingPlaceholder(Modifier.fillMaxSize())
            }

            MarkdownImagePresentation.Success -> {
                Image(
                    painter = successPainter ?: painter,
                    contentDescription = image.title ?: defaultContentDescription,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            MarkdownImagePresentation.RetainedSuccess -> {
                retainedSuccessPainter?.let { retainedPainter ->
                    Image(
                        painter = retainedPainter,
                        contentDescription = image.title ?: defaultContentDescription,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } ?: ImageLoadingPlaceholder(Modifier.fillMaxSize())
            }

            MarkdownImagePresentation.ErrorPlaceholder -> {
                ImageErrorPlaceholder(Modifier.fillMaxSize())
            }

            MarkdownImagePresentation.EmptyPlaceholder -> {
                ImageEmptyPlaceholder(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
internal fun MarkdownImagePager(
    images: ImmutableList<ModernMarkdownImage>,
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
    var currentIndicatorPage by remember { mutableIntStateOf(pagerState.currentPage) }
    LaunchedEffect(pagerState, images.size) {
        val lastIndex = images.lastIndex
        if (lastIndex >= 0 && pagerState.currentPage > lastIndex) {
            pagerState.scrollToPage(lastIndex)
        }
        currentIndicatorPage = pagerState.currentPage
        snapshotFlow { pagerState.currentPage }
            .collectLatest { page -> currentIndicatorPage = page }
    }
    val indicatorState =
        resolveMarkdownImagePagerIndicatorState(
            currentPage = currentIndicatorPage,
            pageCount = images.size,
        )
    Column(
        modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = MarkdownImageTokens.VerticalPadding),
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
                    .padding(top = MarkdownImageTokens.PagerTopPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            indicatorState?.let { state ->
                repeat(state.pageCount) { index ->
                    PagerIndicatorDot(isActive = state.currentPage == index)
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.rememberSharedImageModifier(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?,
): Modifier =
    if (sharedTransitionScope != null && animatedVisibilityScope != null && sharedElementKey != null) {
        with(sharedTransitionScope) {
            this@rememberSharedImageModifier.sharedElement(
                rememberSharedContentState(key = sharedElementKey),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    } else {
        this
    }

@Composable
private fun Modifier.rememberImageBlockModifier(
    ratio: Float,
    destination: String,
    onImageClick: ((String) -> Unit)?,
    sharedModifier: Modifier,
): Modifier {
    val baseModifier =
        this
            .fillMaxWidth()
            .clip(MarkdownImageTokens.Shape)
            .padding(vertical = MarkdownImageTokens.VerticalPadding)
    val ratioModifier = baseModifier.aspectRatio(ratio)
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
        if (isActive) MarkdownImageTokens.IndicatorActiveSize else MarkdownImageTokens.IndicatorInactiveSize
    val indicatorColor =
        if (isActive) {
            MarkdownImageTokens.activeIndicatorColor(MaterialTheme.colorScheme)
        } else {
            MarkdownImageTokens.inactiveIndicatorColor(MaterialTheme.colorScheme)
        }

    Box(
        modifier =
            Modifier
                .padding(horizontal = MarkdownImageTokens.IndicatorHorizontalPadding)
                .size(indicatorSize)
                .clip(MarkdownImageTokens.IndicatorShape)
                .background(indicatorColor),
    )
}

@Composable
private fun ImageLoadingPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MarkdownImageTokens.LoadingMinHeight)
                .background(
                    MarkdownImageTokens.loadingContainerColor(MaterialTheme.colorScheme),
                    MarkdownImageTokens.Shape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveLoadingIndicator(
            modifier = Modifier.size(MarkdownImageTokens.LoadingIndicatorSize),
            color = MarkdownImageTokens.loadingIndicatorColor(MaterialTheme.colorScheme),
        )
    }
}

@Composable
private fun ImageErrorPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(MarkdownImageTokens.ErrorHeight)
                .background(
                    MarkdownImageTokens.errorContainerColor(MaterialTheme.colorScheme),
                    MarkdownImageTokens.Shape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.markdown_image_load_failed),
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
                .heightIn(min = MarkdownImageTokens.PlaceholderMinHeight)
                .background(
                    MarkdownImageTokens.emptyContainerColor(MaterialTheme.colorScheme),
                    MarkdownImageTokens.Shape,
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
                    .padding(MarkdownImageTokens.PlaceholderContentPadding),
        ) {
            ImageLoadingPlaceholder()
            ImageErrorPlaceholder()
            ImageEmptyPlaceholder()
        }
    }
}
