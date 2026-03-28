package com.lomo.data.util

import com.lomo.data.memo.MemoContentHashPolicy
import com.lomo.domain.model.StorageTimestampFormats
import java.time.Instant
import java.time.ZoneId

internal val MEMO_BLOCK_NOT_FOUND = -1 to -1

private const val MEMO_ID_SEPARATOR = '_'
private const val MIN_MEMO_ID_PARTS = 3
private val MEMO_CONTENT_HASH_REGEX = Regex("^[0-9a-f]+$")

private data class ParsedMemoId(
    val timePart: String,
    val contentHash: String,
    val collisionIndex: Int,
)

private data class MemoBlock(
    val startIndex: Int,
    val endIndex: Int,
    val timePart: String,
    val content: String,
)

internal fun findMemoBlockByMemoId(
    lines: List<String>,
    memoId: String,
): Pair<Int, Int>? {
    val parsedId = parseMemoId(memoId) ?: return null
    val matches =
        extractMemoBlocks(lines).filter { block ->
            block.timePart == parsedId.timePart &&
                MemoContentHashPolicy.hashHex(block.content) == parsedId.contentHash
        }

    return matches.getOrNull(parsedId.collisionIndex)?.let { block ->
        block.startIndex to block.endIndex
    }
}

private fun extractMemoBlocks(lines: List<String>): List<MemoBlock> {
    val blocks = mutableListOf<MemoBlock>()
    var index = 0
    while (index < lines.size) {
        val header = StorageTimestampFormats.parseMemoHeaderLine(lines[index])
        if (header == null) {
            index++
            continue
        }

        val start = index
        var end = index
        index++
        while (index < lines.size && StorageTimestampFormats.parseMemoHeaderLine(lines[index]) == null) {
            end = index
            index++
        }

        val contentBuilder = StringBuilder(header.contentPart)
        for (lineIndex in (start + 1)..end) {
            if (lineIndex !in lines.indices) {
                continue
            }
            val line = lines[lineIndex]
            if (contentBuilder.isEmpty()) {
                contentBuilder.append(line)
            } else {
                contentBuilder.append("\n").append(line)
            }
        }

        blocks.add(
            MemoBlock(
                startIndex = start,
                endIndex = end,
                timePart = header.timePart,
                content = contentBuilder.toString().trim(),
            ),
        )
    }
    return blocks
}

private fun parseMemoId(memoId: String): ParsedMemoId? {
    val parts = memoId.trim().takeIf(String::isNotEmpty)?.split(MEMO_ID_SEPARATOR).orEmpty()
    val tail = parts.lastOrNull()
    val collisionIndex = tail?.takeIf { it.all(Char::isDigit) }?.toIntOrNull() ?: 0
    val coreParts = if (collisionIndex > 0) parts.dropLast(1) else parts
    val resolvedContentHash = coreParts.lastOrNull()?.takeIf(MEMO_CONTENT_HASH_REGEX::matches)
    val resolvedTimePart = resolvedContentHash?.let { resolveMemoIdTimePart(coreParts.dropLast(1)) }
    val isValid =
        parts.size >= MIN_MEMO_ID_PARTS &&
            coreParts.size >= MIN_MEMO_ID_PARTS &&
            resolvedContentHash != null &&
            resolvedTimePart != null

    return if (isValid) {
        ParsedMemoId(
            timePart = resolvedTimePart.orEmpty(),
            contentHash = resolvedContentHash.orEmpty(),
            collisionIndex = collisionIndex,
        )
    } else {
        null
    }
}

internal fun findMemoBlockByRawContent(
    lines: List<String>,
    rawContent: String,
): Pair<Int, Int>? {
    val rawLines = rawContent.lines().map { it.trimEnd() }.toMutableList()
    while (rawLines.isNotEmpty() && rawLines.last().isEmpty()) {
        rawLines.removeAt(rawLines.lastIndex)
    }
    val startIndex =
        if (rawLines.isEmpty() || rawLines.size > lines.size) {
            null
        } else {
            val targetSize = rawLines.size
            (0..(lines.size - targetSize)).firstOrNull { candidateStart ->
                rawLines.indices.all { offset ->
                    lines[candidateStart + offset].trimEnd() == rawLines[offset]
                }
            }
        }
    return startIndex?.let { it to findBlockEndIndex(lines, it) }
}

private fun findUniqueMemoBlockByRawContent(
    lines: List<String>,
    rawContent: String,
): Pair<Int, Int>? {
    val rawLines = rawContent.lines().map { it.trimEnd() }.toMutableList()
    while (rawLines.isNotEmpty() && rawLines.last().isEmpty()) {
        rawLines.removeAt(rawLines.lastIndex)
    }
    if (rawLines.isEmpty() || rawLines.size > lines.size) {
        return null
    }

    val matches =
        (0..(lines.size - rawLines.size)).mapNotNull { candidateStart ->
            if (
                rawLines.indices.all { offset ->
                    lines[candidateStart + offset].trimEnd() == rawLines[offset]
                }
            ) {
                candidateStart to findBlockEndIndex(lines, candidateStart)
            } else {
                null
            }
        }

    return matches.singleOrNull()
}

internal fun findDestructiveMemoBlock(
    lines: List<String>,
    rawContent: String,
    memoId: String?,
): Pair<Int, Int> =
    memoId
        ?.let { findMemoBlockByMemoId(lines, it) }
        ?: findUniqueMemoBlockByRawContent(lines, rawContent)
        ?: MEMO_BLOCK_NOT_FOUND

internal fun findMemoBlockByFirstLine(
    lines: List<String>,
    rawContent: String,
): Pair<Int, Int>? {
    val contentStartLine = rawContent.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return null
    val startIndex = lines.indexOfFirst { it.trim() == contentStartLine }
    return startIndex.takeIf { it >= 0 }?.let { it to findBlockEndIndex(lines, it) }
}

internal fun findMemoBlockByTimestamp(
    lines: List<String>,
    timestamp: Long,
): Pair<Int, Int>? {
    val zone = ZoneId.systemDefault()
    return StorageTimestampFormats.supportedPatterns
        .asSequence()
        .map { format ->
            StorageTimestampFormats
                .formatter(format)
                .withZone(zone)
                .format(Instant.ofEpochMilli(timestamp))
        }.mapNotNull { timestampPart ->
            lines.indexOfFirst { line ->
                val trimmed = line.trim()
                trimmed == "- $timestampPart" || trimmed.startsWith("- $timestampPart ")
            }.takeIf { it >= 0 }?.let { it to findBlockEndIndex(lines, it) }
        }.firstOrNull()
}

private fun resolveMemoIdTimePart(dateAndTimeParts: List<String>): String? {
    for (start in 1 until dateAndTimeParts.size) {
        val candidate =
            dateAndTimeParts
                .subList(start, dateAndTimeParts.size)
                .joinToString(MEMO_ID_SEPARATOR.toString())
        if (StorageTimestampFormats.parseOrNull(candidate) != null) {
            return candidate
        }
    }
    return null
}

private fun findBlockEndIndex(
    lines: List<String>,
    startIndex: Int,
): Int {
    var endIndex = startIndex
    for (index in (startIndex + 1) until lines.size) {
        if (StorageTimestampFormats.parseMemoHeaderLine(lines[index]) != null) {
            break
        }
        endIndex = index
    }
    return endIndex
}
