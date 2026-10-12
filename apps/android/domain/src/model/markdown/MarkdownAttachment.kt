package com.lomo.domain.model.markdown

import com.lomo.domain.model.MediaClass

/**
 * One attachment fact from the Rust-owned render document: the destination plus its owner
 * media classification. Kotlin consumers read [mediaClass] directly and never re-derive it.
 */
data class MarkdownAttachment(
    val destination: String,
    val mediaClass: MediaClass,
)
