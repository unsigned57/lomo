package com.lomo.data.share

import com.lomo.nativebridge.AttachmentNameMapping
import com.lomo.nativebridge.remapMarkdownAttachmentDestinations

/**
 * Platform adapter that applies engine-planned attachment destination remaps.
 *
 * Markdown structure authority is solely `lomo-workspace` via
 * [remapMarkdownAttachmentDestinations]. This object must not reintroduce a private Markdown
 * parser/scanner.
 */
internal object ShareAttachmentReferenceRemapper {
    fun remapMarkdownTargets(
        content: String,
        attachmentMappings: Map<String, String>,
    ): String {
        if (attachmentMappings.isEmpty()) {
            return content
        }
        val mappings =
            attachmentMappings.map { (original, stored) ->
                AttachmentNameMapping(original = original, stored = stored)
            }
        return remapMarkdownAttachmentDestinations(content = content, mappings = mappings)
    }
}
