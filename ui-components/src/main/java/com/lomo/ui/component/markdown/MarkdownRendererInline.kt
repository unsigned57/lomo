package com.lomo.ui.component.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import com.lomo.ui.text.MemoParagraphText
import com.lomo.ui.theme.memoBodyTextStyle
import kotlinx.collections.immutable.ImmutableList
import org.commonmark.node.Image

@Composable
internal fun MDText(
    text: AnnotatedString,
    style: TextStyle?,
    enableTextSelection: Boolean = false,
    onTapFeedback: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
) {
    if (text.isEmpty()) return

    val layoutSample: CharSequence = text
    val baseStyle = style ?: MaterialTheme.typography.memoBodyTextStyle()
    val finalStyle =
        resolveMarkdownParagraphTextStyle(
            baseStyle = baseStyle,
            fallbackColor = MaterialTheme.colorScheme.onSurface,
            text = layoutSample,
        )

    MemoParagraphText(
        text = text,
        style = finalStyle,
        modifier = Modifier.fillMaxWidth(),
        selectable = enableTextSelection,
        onTapFeedback = onTapFeedback,
        onDoubleClick = onDoubleClick,
    )
}

@Composable
internal fun MDImage(
    image: Image,
    onImageClick: ((String) -> Unit)? = null,
) {
    MarkdownImageBlock(
        image = image,
        onImageClick = onImageClick,
    )
}

@Composable
internal fun MDImageGallery(
    images: ImmutableList<Image>,
    onImageClick: ((String) -> Unit)? = null,
) {
    MarkdownImagePager(
        images = images,
        onImageClick = onImageClick,
    )
}
