package com.lomo.app.util

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.MediaClass
import com.lomo.ui.component.markdown.MarkdownPresentationPolicy
import com.lomo.ui.component.markdown.MarkdownRenderer
import com.lomo.ui.theme.memoBodyTextStyle

private val OuterPadding = 24.dp
private val CardPadding = 30.dp
private val CardCornerRadius = 24.dp
private val CardMinHeight = 330.dp
private val TagCornerRadius = 8.dp
private val TagHorizontalPadding = 8.dp
private val TagVerticalPadding = 4.dp
private val TagSpacing = 6.dp
private val TagBottomSpacing = 16.dp
private val TitleBottomSpacing = 12.dp
private val MediaCornerRadius = 12.dp
private val MediaMaxHeight = 360.dp
private val FooterTopSpacing = 20.dp
private val FooterRowTopSpacing = 14.dp
private val CenteredBodyMinHeight = 180.dp

@Composable
internal fun ShareCardContent(state: ShareCardUiState, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val backdropBrush = remember(colorScheme.surfaceContainerLowest, colorScheme.surfaceContainerLow) {
        Brush.verticalGradient(
            colors = listOf(colorScheme.surfaceContainerLowest, colorScheme.surfaceContainerLow),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(backdropBrush)
            .padding(OuterPadding),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(CardCornerRadius),
            color = colorScheme.surface,
            border = BorderStroke(1.dp, colorScheme.outlineVariant),
            shadowElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = CardMinHeight)
                    .padding(CardPadding),
            ) {
                if (state.displayTags.isNotEmpty()) {
                    ShareCardTagRow(tags = state.displayTags)
                    Spacer(modifier = Modifier.height(TagBottomSpacing))
                }
                val titleText = state.title
                if (!titleText.isNullOrBlank()) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(TitleBottomSpacing))
                }
                ShareCardBody(state = state)
                val footerRow = state.footer.row
                if (state.footer.showFooter && footerRow != null) {
                    Spacer(modifier = Modifier.height(FooterTopSpacing))
                    HorizontalDivider(thickness = 1.dp, color = colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(FooterRowTopSpacing))
                    ShareCardFooter(row = footerRow)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareCardTagRow(tags: List<String>) {
    val colorScheme = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(TagSpacing),
        verticalArrangement = Arrangement.spacedBy(TagSpacing),
    ) {
        tags.forEach { tag ->
            Surface(
                shape = RoundedCornerShape(TagCornerRadius),
                color = colorScheme.secondaryContainer,
                contentColor = colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = "#$tag",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = TagHorizontalPadding, vertical = TagVerticalPadding),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ShareCardBody(state: ShareCardUiState) {
    val bodyModifier =
        if (state.centeredBody) {
            Modifier.fillMaxWidth().defaultMinSize(minHeight = CenteredBodyMinHeight)
        } else {
            Modifier.fillMaxWidth()
        }
    val alignment = if (state.centeredBody) Alignment.CenterStart else Alignment.TopStart
    Box(modifier = bodyModifier, contentAlignment = alignment) {
        if (state.isBlankFallback) {
            Text(
                text = state.emptyFallbackText,
                style = MaterialTheme.typography.memoBodyTextStyle(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            MarkdownRenderer(
                document = state.document,
                modifier = Modifier.fillMaxWidth(),
                presentationPolicy = MarkdownPresentationPolicy.DEFAULT,
                softWrapCode = true,
                imageContent = { image ->
                    val bitmap = state.imagesByDestination[image.destination]
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = image.description ?: state.imagePlaceholder,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = MediaMaxHeight)
                                .clip(RoundedCornerShape(MediaCornerRadius)),
                        )
                    } else {
                        val fallbackLabel = image.description?.takeIf(String::isNotBlank) ?: state.imagePlaceholder
                        ShareCardMediaPlaceholder(label = fallbackLabel)
                    }
                },
                mediaPresentationResolver = { image ->
                    if (image.mediaClass == MediaClass.AUDIO) {
                        com.lomo.ui.component.markdown.MarkdownMediaPresentation(
                            source = image.destination,
                            description = image.description,
                            kind = "audio",
                        )
                    } else {
                        null
                    }
                },
                mediaContent = { presentation ->
                    val label = presentation.description?.takeIf(String::isNotBlank) ?: state.audioPlaceholder
                    ShareCardMediaPlaceholder(label = label)
                },
            )
        }
    }
}

@Composable
private fun ShareCardMediaPlaceholder(label: String) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MediaCornerRadius),
        color = colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ShareCardFooter(row: ShareCardFooterRow) {
    val footerColor = MaterialTheme.colorScheme.onSurfaceVariant
    val footerStyle = MaterialTheme.typography.labelSmall
    Box(modifier = Modifier.fillMaxWidth()) {
        if (row.startText.isNotBlank()) {
            Text(
                text = row.startText,
                style = footerStyle,
                color = footerColor,
                modifier = Modifier.align(Alignment.CenterStart),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.centerText.isNotBlank()) {
            Text(
                text = row.centerText,
                style = footerStyle,
                color = footerColor,
                modifier = Modifier.align(Alignment.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.endText.isNotBlank()) {
            Text(
                text = row.endText,
                style = footerStyle,
                color = footerColor,
                modifier = Modifier.align(Alignment.CenterEnd),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
