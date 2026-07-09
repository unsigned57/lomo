package com.lomo.app.presentation.markdown

import androidx.compose.runtime.Composable
import com.lomo.domain.model.MediaFileExtensions
import com.lomo.ui.component.markdown.MarkdownMediaPresentation
import com.lomo.ui.component.markdown.MarkdownMediaPresentationAdapter
import com.lomo.ui.component.markdown.MarkdownMediaPresentationResolver
import com.lomo.ui.component.media.AudioPlayerCard

private const val MEMO_MARKDOWN_AUDIO_KIND = "audio"

val MemoMarkdownMediaPresentationResolver: MarkdownMediaPresentationResolver = { image ->
    if (MediaFileExtensions.hasAudioExtension(image.destination)) {
        MarkdownMediaPresentation(
            source = image.destination,
            description = image.title,
            kind = MEMO_MARKDOWN_AUDIO_KIND,
        )
    } else {
        null
    }
}

val MemoMarkdownMediaAdapter =
    MarkdownMediaPresentationAdapter(
        resolver = MemoMarkdownMediaPresentationResolver,
        content = { presentation -> MemoMarkdownMediaContent(presentation) },
    )

@Composable
fun MemoMarkdownMediaContent(presentation: MarkdownMediaPresentation) {
    when (presentation.kind) {
        MEMO_MARKDOWN_AUDIO_KIND -> AudioPlayerCard(relativeFilePath = presentation.source)
    }
}
