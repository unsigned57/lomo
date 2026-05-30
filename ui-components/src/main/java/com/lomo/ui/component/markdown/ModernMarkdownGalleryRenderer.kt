package com.lomo.ui.component.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList

@Composable
internal fun ModernMarkdownGalleryItem(
    images: ImmutableList<ModernMarkdownImage>,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)? = null,
) {
    val effectiveMediaPresentationResolver = mediaPresentationResolver.takeIf { mediaContent != null }
    val resolvedMedia =
        remember(images, effectiveMediaPresentationResolver) {
            images.map { image -> image to effectiveMediaPresentationResolver?.invoke(image) }
        }

    if (resolvedMedia.none { (_, presentation) -> presentation != null }) {
        MDImageGallery(
            images = images,
            onImageClick = onImageClick,
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        resolvedMedia.forEach { (image, presentation) ->
            if (presentation != null) {
                mediaContent?.invoke(presentation)
            } else {
                MDImage(
                    image = image,
                    onImageClick = onImageClick,
                )
            }
        }
    }
}
