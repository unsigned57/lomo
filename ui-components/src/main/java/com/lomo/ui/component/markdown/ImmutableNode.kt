package com.lomo.ui.component.markdown

import org.intellij.markdown.ast.ASTNode

data class ImmutableNode(
    val node: ASTNode,
    val content: String,
)
