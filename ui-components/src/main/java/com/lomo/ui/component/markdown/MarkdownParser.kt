package com.lomo.ui.component.markdown

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

object MarkdownParser {
    private val parser: Parser by lazy {
        Parser
            .builder()
            .extensions(
                listOf(
                    StrikethroughExtension.create(),
                    TablesExtension.create(),
                    AutolinkExtension.create(),
                    TaskListItemsExtension.create(),
                ),
            ).includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build()
    }

    fun parse(content: String): ImmutableNode {
        val root = parser.parse(content)
        return ImmutableNode(root)
    }
}
