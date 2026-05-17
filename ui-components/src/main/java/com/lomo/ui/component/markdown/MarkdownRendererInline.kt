package com.lomo.ui.component.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import com.lomo.ui.text.MemoParagraphText
import com.lomo.ui.text.MemoTextSelectionRegistrar
import com.lomo.ui.theme.memoBodyTextStyle
import kotlinx.collections.immutable.ImmutableList

@Composable
internal fun MDText(
    text: AnnotatedString,
    style: TextStyle?,
    enableTextSelection: Boolean = false,
    blockKey: Any? = null,
    selectionRegistrar: MemoTextSelectionRegistrar? = null,
    onTapFeedback: (() -> Unit)? = null,
    onBodyClick: (() -> Unit)? = null,
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
        blockKey = blockKey,
        selectionRegistrar = selectionRegistrar,
        onTapFeedback = onTapFeedback,
        onBodyClick = onBodyClick,
        onDoubleClick = onDoubleClick,
    )
}

@Composable
internal fun MDImage(
    image: ModernMarkdownImage,
    onImageClick: ((String) -> Unit)? = null,
) {
    MarkdownImageBlock(
        image = image,
        onImageClick = onImageClick,
    )
}

@Composable
internal fun MDImageGallery(
    images: ImmutableList<ModernMarkdownImage>,
    onImageClick: ((String) -> Unit)? = null,
) {
    MarkdownImagePager(
        images = images,
        onImageClick = onImageClick,
    )
}
