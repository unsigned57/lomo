package com.lomo.domain.model

object SyncConflictTextMerge {
    fun merge(
        localText: String?,
        remoteText: String?,
        localLastModified: Long? = null,
        remoteLastModified: Long? = null,
    ): String? {
        if (localText.isNullOrEmpty()) return remoteText
        if (remoteText.isNullOrEmpty()) return localText
        if (localText == remoteText) return localText

        val localLines = localText.toMergeLines()
        val remoteLines = remoteText.toMergeLines()
        val anchors =
            computeAnchors(localLines, remoteLines)
                .filter { (localAnchor, _) -> localLines[localAnchor].isNotBlank() }
        val mergedLines = mutableListOf<String>()
        var localCursor = 0
        var remoteCursor = 0

        for ((localAnchor, remoteAnchor) in anchors) {
            val mergedSegment =
                mergeSegment(
                    localLines = localLines.subList(localCursor, localAnchor),
                    remoteLines = remoteLines.subList(remoteCursor, remoteAnchor),
                ) ?: return null
            mergedLines += mergedSegment
            mergedLines += localLines[localAnchor]
            localCursor = localAnchor + 1
            remoteCursor = remoteAnchor + 1
        }

        val tail =
            mergeSegment(
                localLines = localLines.subList(localCursor, localLines.size),
                remoteLines = remoteLines.subList(remoteCursor, remoteLines.size),
            )
        if (tail == null) {
            if (anchors.isNotEmpty()) return null
            return mergeDisjointMemoContent(
                localText = localText,
                remoteText = remoteText,
                localLines = localLines,
                remoteLines = remoteLines,
                localLastModified = localLastModified,
                remoteLastModified = remoteLastModified,
            )
        }
        mergedLines += tail
        return mergedLines.joinToString("\n")
    }

    private fun computeAnchors(
        localLines: List<String>,
        remoteLines: List<String>,
    ): List<Pair<Int, Int>> {
        val localSize = localLines.size
        val remoteSize = remoteLines.size
        val lcs = Array(localSize + 1) { IntArray(remoteSize + 1) }
        for (localIndex in localSize - 1 downTo 0) {
            for (remoteIndex in remoteSize - 1 downTo 0) {
                lcs[localIndex][remoteIndex] =
                    if (localLines[localIndex] == remoteLines[remoteIndex]) {
                        lcs[localIndex + 1][remoteIndex + 1] + 1
                    } else {
                        maxOf(lcs[localIndex + 1][remoteIndex], lcs[localIndex][remoteIndex + 1])
                    }
            }
        }

        val anchors = mutableListOf<Pair<Int, Int>>()
        var localIndex = 0
        var remoteIndex = 0
        while (localIndex < localSize && remoteIndex < remoteSize) {
            when {
                localLines[localIndex] == remoteLines[remoteIndex] -> {
                    anchors += localIndex to remoteIndex
                    localIndex++
                    remoteIndex++
                }
                lcs[localIndex + 1][remoteIndex] >= lcs[localIndex][remoteIndex + 1] -> {
                    localIndex++
                }
                else -> {
                    remoteIndex++
                }
            }
        }
        return anchors
    }

    private fun mergeSegment(
        localLines: List<String>,
        remoteLines: List<String>,
    ): List<String>? =
        when {
            localLines == remoteLines -> localLines
            localLines.isEmpty() -> remoteLines
            remoteLines.isEmpty() -> localLines
            isSubsequence(localLines, remoteLines) -> remoteLines
            isSubsequence(remoteLines, localLines) -> localLines
            else -> null
        }

    private fun isSubsequence(
        smaller: List<String>,
        larger: List<String>,
    ): Boolean {
        var smallerIndex = 0
        var largerIndex = 0
        while (smallerIndex < smaller.size && largerIndex < larger.size) {
            if (smaller[smallerIndex] == larger[largerIndex]) {
                smallerIndex++
            }
            largerIndex++
        }
        return smallerIndex == smaller.size
    }

    private fun mergeDisjointMemoContent(
        localText: String,
        remoteText: String,
        localLines: List<String>,
        remoteLines: List<String>,
        localLastModified: Long?,
        remoteLastModified: Long?,
    ): String? {
        if (!looksLikeMemoContent(localLines) || !looksLikeMemoContent(remoteLines)) return null
        if (meaningfulLineSet(localLines).intersect(meaningfulLineSet(remoteLines)).isNotEmpty()) return null

        val (olderText, newerText) =
            if (remoteShouldComeFirst(localLastModified, remoteLastModified)) {
                remoteText to localText
            } else {
                localText to remoteText
            }
        return olderText.trimEnd('\n') + "\n\n" + newerText.trimStart('\n')
    }

    private fun looksLikeMemoContent(lines: List<String>): Boolean = lines.any { it.isNotBlank() }

    private fun meaningfulLineSet(lines: List<String>): Set<String> =
        lines
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()

    private fun remoteShouldComeFirst(
        localLastModified: Long?,
        remoteLastModified: Long?,
    ): Boolean =
        when {
            localLastModified == null || remoteLastModified == null -> false
            remoteLastModified < localLastModified -> true
            remoteLastModified > localLastModified -> false
            else -> false
        }

    private fun String.toMergeLines(): List<String> = if (isEmpty()) emptyList() else split('\n')
}
