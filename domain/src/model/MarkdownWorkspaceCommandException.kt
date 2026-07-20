package com.lomo.domain.model

class MarkdownWorkspaceCommandException(
    val code: String,
    message: String,
) : IllegalStateException(message)
