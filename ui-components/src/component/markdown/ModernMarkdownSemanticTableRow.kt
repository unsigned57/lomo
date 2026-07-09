package com.lomo.ui.component.markdown

internal data class ModernMarkdownSemanticTableRow(
    val cells: List<MarkdownSemanticTableCell>,
)

internal fun MarkdownSemanticBlock.Table.toModernMarkdownTableRows(): List<ModernMarkdownSemanticTableRow> =
    (listOf(header) + rows)
        .filter { row -> row.isNotEmpty() }
        .map { row -> ModernMarkdownSemanticTableRow(row) }

internal fun List<ModernMarkdownTableRow>.toSemanticTableRows(): List<ModernMarkdownSemanticTableRow> =
    map { row ->
        ModernMarkdownSemanticTableRow(
            cells =
                row.cells.map { cell ->
                    MarkdownSemanticTableCell(
                        inlines = listOf(MarkdownSemanticInline.Text(cell)),
                    )
                },
        )
    }
