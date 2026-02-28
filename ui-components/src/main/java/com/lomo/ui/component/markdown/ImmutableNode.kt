package com.lomo.ui.component.markdown

import org.commonmark.node.Node

/**
 * Wrapper for parsed CommonMark nodes.
 *
 * `Node` is mutable, so this type intentionally avoids Compose `@Immutable`.
 */
data class ImmutableNode(
    val node: Node,
)
