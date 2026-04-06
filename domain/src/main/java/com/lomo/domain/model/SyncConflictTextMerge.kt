package com.lomo.domain.model

object SyncConflictTextMerge {
    fun merge(
        localText: String?,
        remoteText: String?,
    ): String? {
        if (localText == null || remoteText == null) return null
        if (localText == remoteText) return localText

        val localLines = localText.toMergeLines()
        val remoteLines = remoteText.toMergeLines()
        val anchors = computeAnchors(localLines, remoteLines)
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
            ) ?: return null
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

    private fun String.toMergeLines(): List<String> = if (isEmpty()) emptyList() else split('\n')
}
