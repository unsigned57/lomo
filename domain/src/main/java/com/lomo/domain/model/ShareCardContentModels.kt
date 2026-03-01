package com.lomo.domain.model

data class ShareCardTextInput(
    val content: String,
    val sourceTags: List<String>,
)

data class ShareCardContent(
    val tags: List<String>,
    val bodyText: String,
)
