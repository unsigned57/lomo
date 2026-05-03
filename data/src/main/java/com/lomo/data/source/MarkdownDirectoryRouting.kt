package com.lomo.data.source

internal inline fun <T> routeMarkdownDirectory(
    directory: MemoDirectoryType,
    onMain: () -> T,
    onTrash: () -> T,
): T =
    when (directory) {
        MemoDirectoryType.MAIN -> onMain()
        MemoDirectoryType.TRASH -> onTrash()
    }
