
package com.lomo.ui.component.markdown

import androidx.compose.runtime.Immutable
import org.commonmark.node.Node

@Immutable
data class ImmutableNode(
    val node: Node,
)
