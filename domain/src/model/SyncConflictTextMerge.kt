package com.lomo.domain.model

object SyncConflictTextMerge {
    private const val DEFAULT_MAX_LINE_COUNT = 1_000
    private const val DEFAULT_MAX_COMPARISON_CELLS = 250_000L

    data class Policy(
        val maxLineCount: Int = DEFAULT_MAX_LINE_COUNT,
        val maxComparisonCells: Long = DEFAULT_MAX_COMPARISON_CELLS,
    ) {
        init {
            require(maxLineCount >= 0) { "maxLineCount must be non-negative" }
            require(maxComparisonCells >= 0L) { "maxComparisonCells must be non-negative" }
        }
    }

    fun merge(
        localText: String?,
        remoteText: String?,
        localLastModified: Long? = null,
        remoteLastModified: Long? = null,
        policy: Policy = Policy(),
    ): String? =
        when {
            localText.isNullOrEmpty() -> remoteText
            remoteText.isNullOrEmpty() -> localText
            localText == remoteText -> localText
            else ->
                mergeNonEmptyDistinctText(
                    localText = localText,
                    remoteText = remoteText,
                    localLastModified = localLastModified,
                    remoteLastModified = remoteLastModified,
                    policy = policy,
                )
        }

    private fun mergeNonEmptyDistinctText(
        localText: String,
        remoteText: String,
        localLastModified: Long?,
        remoteLastModified: Long?,
        policy: Policy,
    ): String? {
        val localLines = localText.toMergeLines()
        val remoteLines = remoteText.toMergeLines()
        if (!policy.allowsLcs(localLines.size, remoteLines.size)) return null

        val anchors =
            computeAnchors(localLines, remoteLines)
                .filter { (localAnchor, _) -> localLines[localAnchor].isNotBlank() }
        val anchoredMerge = mergeAnchoredSegments(localLines, remoteLines, anchors) ?: return null

        val tail =
            mergeSegment(
                localLines = localLines.subList(anchoredMerge.localCursor, localLines.size),
                remoteLines = remoteLines.subList(anchoredMerge.remoteCursor, remoteLines.size),
            )
        return when {
            tail != null -> (anchoredMerge.lines + tail).joinToString("\n")
            anchors.isNotEmpty() -> null
            else ->
                mergeDisjointMemoContent(
                    localText = localText,
                    remoteText = remoteText,
                    localLines = localLines,
                    remoteLines = remoteLines,
                    localLastModified = localLastModified,
                    remoteLastModified = remoteLastModified,
                )
        }
    }

    private fun mergeAnchoredSegments(
        localLines: List<String>,
        remoteLines: List<String>,
        anchors: List<Pair<Int, Int>>,
    ): AnchoredMerge? {
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

        return AnchoredMerge(
            lines = mergedLines,
            localCursor = localCursor,
            remoteCursor = remoteCursor,
        )
    }

    private data class AnchoredMerge(
        val lines: List<String>,
        val localCursor: Int,
        val remoteCursor: Int,
    )

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

        // When both sides carry memo headers that share a timestamp, the divergence is the SAME memo
        // edited on each side, not two independent notes. Align blocks by (timestamp, ordinal) and keep
        // the newer version so an edited memo is never duplicated into two concatenated blocks.
        mergeSharedTimestampMemoBlocks(localText, remoteText, localLastModified, remoteLastModified)
            ?.let { return it }

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

    private fun Policy.allowsLcs(
        localLineCount: Int,
        remoteLineCount: Int,
    ): Boolean {
        if (localLineCount > maxLineCount || remoteLineCount > maxLineCount) return false
        return (localLineCount.toLong() + 1L) * (remoteLineCount.toLong() + 1L) <= maxComparisonCells
    }

}

private fun String.toMergeLines(): List<String> = if (isEmpty()) emptyList() else split('\n')

/**
 * Aligns two memo-shard texts by memo identity (header timestamp + ordinal within that timestamp).
 * Returns null when either side has no memo headers or the sides share no memo identity, leaving the
 * caller to union genuinely distinct notes. When a memo is present on both sides, the newer file's
 * version wins, so editing a memo never produces a duplicate block.
 */
private fun mergeSharedTimestampMemoBlocks(
    localText: String,
    remoteText: String,
    localLastModified: Long?,
    remoteLastModified: Long?,
): String? {
    val localBlocks = splitMemoBlocks(localText) ?: return null
    val remoteBlocks = splitMemoBlocks(remoteText) ?: return null
    val localKeys = localBlocks.mapTo(mutableSetOf(), MemoBlock::key)
    val remoteKeys = remoteBlocks.mapTo(mutableSetOf(), MemoBlock::key)
    if (localKeys.intersect(remoteKeys).isEmpty()) return null

    val localIsNewer =
        localLastModified == null || remoteLastModified == null || localLastModified >= remoteLastModified
    val olderBlocks = if (localIsNewer) remoteBlocks else localBlocks
    val newerBlocks = if (localIsNewer) localBlocks else remoteBlocks
    val newerByKey = newerBlocks.associateBy(MemoBlock::key)

    val emittedKeys = mutableSetOf<MemoBlockKey>()
    val merged = mutableListOf<String>()
    olderBlocks.forEach { block ->
        if (emittedKeys.add(block.key)) {
            merged += (newerByKey[block.key] ?: block).text
        }
    }
    newerBlocks.forEach { block ->
        if (emittedKeys.add(block.key)) {
            merged += block.text
        }
    }
    return merged.joinToString("\n\n")
}

private fun splitMemoBlocks(text: String): List<MemoBlock>? {
    val blocks = mutableListOf<MemoBlock>()
    val ordinalByTimestamp = mutableMapOf<String, Int>()
    val preamble = mutableListOf<String>()
    var currentKey: MemoBlockKey? = null
    var currentLines = mutableListOf<String>()

    fun flush() {
        val key = currentKey ?: return
        blocks += MemoBlock(key = key, text = currentLines.joinToString("\n").trim())
    }

    text.split('\n').forEach { line ->
        val header = StorageTimestampFormats.parseMemoHeaderLine(line)
        if (header != null) {
            flush()
            val ordinal = ordinalByTimestamp.getOrDefault(header.timePart, 0)
            ordinalByTimestamp[header.timePart] = ordinal + 1
            currentKey = MemoBlockKey(timestamp = header.timePart, ordinal = ordinal)
            currentLines = mutableListOf(line)
        } else if (currentKey != null) {
            currentLines += line
        } else {
            preamble += line
        }
    }
    flush()

    if (blocks.isEmpty()) return null
    // A non-blank preamble cannot be attributed to any memo block; decline alignment so the caller
    // unions the whole texts instead of silently dropping it.
    if (preamble.any(String::isNotBlank)) return null
    return blocks
}

private data class MemoBlockKey(
    val timestamp: String,
    val ordinal: Int,
)

private data class MemoBlock(
    val key: MemoBlockKey,
    val text: String,
)
