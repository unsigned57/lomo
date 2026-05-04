package com.lomo.ui.component.navigation

import androidx.compose.runtime.snapshots.SnapshotStateList

internal object SidebarDrawerTagTree

internal const val TAG_DRAG_SCALE = 1.02f
internal const val TAG_DRAG_ALPHA = 0.92f

internal data class TagNode(
    val name: String,
    val fullPath: String,
    val count: Int? = null,
    val children: List<TagNode> = emptyList(),
)

internal data class VisibleTagRow(
    val node: TagNode,
    val level: Int,
)

internal fun buildTagTree(tags: List<SidebarTag>, rootOrder: List<String> = emptyList()): List<TagNode> {
    val rootNodes = mutableListOf<MutableTagNode>()
    val tagMap = tags.associate { it.name to it.count }

    tags.sortedBy { it.name }.forEach { tag ->
        insertTagPath(rootNodes, tag, tagMap)
    }

    val nodes = rootNodes.map { it.toImmutable() }
    if (rootOrder.isEmpty()) return nodes

    val orderIndex = rootOrder.withIndex().associate { (index, name) -> name to index }
    return nodes.sortedBy { node -> orderIndex[node.name] ?: (rootOrder.size + nodes.indexOf(node)) }
}

internal fun visibleTagRows(
    tagTree: List<TagNode>,
    expandedNodePaths: Set<String>,
): List<VisibleTagRow> =
    buildList {
        tagTree.forEach { node ->
            addVisibleTagRows(
                node = node,
                level = 0,
                expandedNodePaths = expandedNodePaths,
            )
        }
    }

private fun MutableList<VisibleTagRow>.addVisibleTagRows(
    node: TagNode,
    level: Int,
    expandedNodePaths: Set<String>,
) {
    add(VisibleTagRow(node = node, level = level))
    if (node.fullPath !in expandedNodePaths) return

    node.children.forEach { child ->
        addVisibleTagRows(
            node = child,
            level = level + 1,
            expandedNodePaths = expandedNodePaths,
        )
    }
}

internal fun applyReorderableTagMove(
    tagTree: SnapshotStateList<TagNode>,
    fromKey: Any?,
    toKey: Any?,
): Boolean {
    val fromPath = fromKey as? String ?: return false
    val toPath = toKey as? String ?: return false
    val fromIndex = tagTree.indexOfFirst { it.fullPath == fromPath }
    val toIndex = tagTree.indexOfFirst { it.fullPath == toPath }
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return false
    tagTree.add(toIndex, tagTree.removeAt(fromIndex))
    return true
}

private fun insertTagPath(
    rootNodes: MutableList<MutableTagNode>,
    tag: SidebarTag,
    tagMap: Map<String, Int>,
) {
    var currentLevelNodes = rootNodes
    var currentPath = ""

    tag.name.split("/").forEachIndexed { index, part ->
        if (index > 0) {
            currentPath += "/"
        }
        currentPath += part

        val existingNode = currentLevelNodes.find { it.name == part }
        val node =
            existingNode ?: MutableTagNode(
                name = part,
                fullPath = currentPath,
                count = resolveTagNodeCount(currentPath, tag, tagMap),
            ).also(currentLevelNodes::add)

        if (currentPath == tag.name) {
            node.count = tag.count
        }
        currentLevelNodes = node.children
    }
}

private fun resolveTagNodeCount(
    currentPath: String,
    tag: SidebarTag,
    tagMap: Map<String, Int>,
): Int? = if (currentPath == tag.name) tag.count else tagMap[currentPath]

private class MutableTagNode(
    val name: String,
    val fullPath: String,
    var count: Int? = null,
    val children: MutableList<MutableTagNode> = mutableListOf(),
) {
    fun toImmutable(): TagNode = TagNode(name, fullPath, count, children.map { it.toImmutable() })
}
