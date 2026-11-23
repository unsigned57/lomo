package com.lomo.data.testing.fakes

import com.lomo.data.source.AttachmentNameBatchLimits
import com.lomo.data.source.AttachmentNameClassifier
import com.lomo.nativebridge.MediaClass

/** Classifies by file suffix and records every batch it was asked for. */
internal class FakeAttachmentNameClassifier(
    private val limits: AttachmentNameBatchLimits = AttachmentNameBatchLimits(maxNames = 256, maxUtf8Bytes = 65_536),
    private val onBatch: (List<String>) -> Unit = {},
) : AttachmentNameClassifier {
    val batches = mutableListOf<List<String>>()

    override fun limits(): AttachmentNameBatchLimits = limits

    override fun classify(names: List<String>): List<MediaClass> {
        batches += names
        onBatch(names)
        return names.map { name ->
            when (name.substringAfterLast('.', missingDelimiterValue = "")) {
                "png", "jpg", "webp" -> MediaClass.IMAGE
                "m4a" -> MediaClass.AUDIO
                "mp4" -> MediaClass.VIDEO
                else -> MediaClass.FILE
            }
        }
    }
}
