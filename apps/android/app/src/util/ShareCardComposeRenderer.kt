package com.lomo.app.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.createBitmap
import com.lomo.app.R
import com.lomo.app.presentation.sharecard.ShareCardDisplayFormatter
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.domain.usecase.PrepareShareCardContentUseCase
import com.lomo.ui.theme.LomoStaticTheme
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Offscreen Compose share-card renderer reusing [com.lomo.ui.component.markdown.MarkdownRenderer]
 * and [LomoStaticTheme] so exported images match the live memo card Markdown rendering.
 */
class ShareCardComposeRenderer(
    private val prepareShareCardContentUseCase: PrepareShareCardContentUseCase,
    private val shareCardDisplayFormatter: ShareCardDisplayFormatter,
    private val markdownWorkspaceRepository: MarkdownWorkspaceRepository,
    private val dispatcherProvider: DispatcherProvider,
) {
    private val shareCardTimeFormatter = DateTimeFormatter.ofPattern(SHARE_CARD_TIME_PATTERN)

    suspend fun render(context: Context, content: String, title: String?, options: ShareCardRenderOptions): Bitmap {
        val cardWidthPx =
            (
                context.resources.displayMetrics.widthPixels.coerceAtLeast(MIN_SHARE_CARD_CANVAS_WIDTH_PX) *
                    SHARE_CARD_CANVAS_WIDTH_RATIO
                ).roundToInt()
        val prepared = withContext(dispatcherProvider.io) {
            prepareRenderData(
                context = context,
                content = content,
                title = title,
                options = options,
                cardWidthPx = cardWidthPx,
            )
        }
        return try {
            withContext(dispatcherProvider.main) {
                renderComposeViewToBitmap(
                    context = context,
                    widthPx = cardWidthPx,
                    options = options,
                    uiState = prepared.uiState,
                )
            }
        } finally {
            prepared.decodedBitmaps.forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun prepareRenderData(
        context: Context,
        content: String,
        title: String?,
        options: ShareCardRenderOptions,
        cardWidthPx: Int,
    ): PreparedShareCardRender {
        // Same body bytes as the list/card path; Rust owns all Markdown structure parsing.
        val document = markdownWorkspaceRepository.renderMarkdown(content)
        val imageDestinations = collectShareCardImageDestinations(document)
        val hasImages = imageDestinations.isNotEmpty() || options.resolvedImagePaths.isNotEmpty()
        val shareCardContent = prepareShareCardContentUseCase(document = document, sourceTags = options.tags)
        val displayTags = shareCardDisplayFormatter.formatTagsForDisplay(shareCardContent.tags)
        val createdAtText = resolveShareCardCreatedAtText(
            createdAtText = options.createdAtText,
            timestampMillis = options.timestampMillis,
            formatter = shareCardTimeFormatter,
        )
        val footer = buildShareCardFooterContent(
            showTime = options.showTime,
            showSignature = options.showSignature,
            signatureText = options.signatureText,
            createdAtText = createdAtText,
        )
        val imagesByDestination = loadShareImagesByDestination(
            context = context,
            imageDestinations = imageDestinations,
            resolvedImagePaths = options.resolvedImagePaths,
            targetWidth = cardWidthPx,
        )
        val uiState = ShareCardUiState(
            document = document,
            displayTags = displayTags,
            title = title?.run { trim().takeIf(String::isNotEmpty) },
            isBlankFallback = document.blocks.isEmpty() && document.plainText.isBlank(),
            emptyFallbackText = context.getString(R.string.app_name),
            centeredBody = shouldUseCenteredShareCardBody(document, shareCardContent.bodyText, hasImages),
            imagesByDestination = imagesByDestination,
            imagePlaceholder = context.getString(R.string.share_card_placeholder_image),
            audioPlaceholder = context.getString(R.string.share_card_placeholder_audio),
            footer = footer,
        )
        return PreparedShareCardRender(
            uiState = uiState,
            decodedBitmaps = imagesByDestination.values.distinct(),
        )
    }

    private suspend fun renderComposeViewToBitmap(
        context: Context,
        widthPx: Int,
        options: ShareCardRenderOptions,
        uiState: ShareCardUiState,
    ): Bitmap {
        val activity = context.findHostActivity()
            ?: error("Share card Compose rendering requires an Activity Context")
        val decorView = activity.window.decorView as ViewGroup
        val container = FrameLayout(activity).apply {
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LomoStaticTheme(
                    themeMode = com.lomo.ui.theme.ThemeMode.fromStorageValue(options.themeMode.value),
                    colorSource = options.colorSource,
                    contrastLevel = options.contrastLevel,
                    pureBlackDark = options.pureBlackDark,
                    fontFamily = options.fontFamily,
                    typographyScales = options.typographyScales,
                ) {
                    ShareCardContent(state = uiState)
                }
            }
        }
        container.addView(
            composeView,
            FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        decorView.addView(
            container,
            ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return try {
            repeat(COMPOSE_SETTLE_FRAMES) {
                withFrameNanos {}
            }
            val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
            val maxHeightSpec = View.MeasureSpec.makeMeasureSpec(MAX_SHARE_BITMAP_HEIGHT_PX, View.MeasureSpec.AT_MOST)
            container.measure(widthSpec, maxHeightSpec)
            val measuredHeight = container.measuredHeight.coerceIn(MIN_RENDER_DIMENSION_PX, MAX_SHARE_BITMAP_HEIGHT_PX)
            container.layout(0, 0, widthPx, measuredHeight)
            val outputBitmap = createBitmap(widthPx, measuredHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outputBitmap)
            composeView.draw(canvas)
            outputBitmap
        } finally {
            composeView.disposeComposition()
            (container.parent as? ViewGroup)?.removeView(container)
        }
    }

    private data class PreparedShareCardRender(val uiState: ShareCardUiState, val decodedBitmaps: List<Bitmap>)

    private companion object {
        const val COMPOSE_SETTLE_FRAMES = 2
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
