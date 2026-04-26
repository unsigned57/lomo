package com.lomo.data.util

internal class IndexedTextLines private constructor(
    private val content: String,
    private val lineStarts: IntArray,
    private val lineEnds: IntArray,
) : AbstractList<String>() {
    override val size: Int
        get() = lineStarts.size

    override fun get(index: Int): String = content.substring(lineStarts[index], lineEnds[index])

    companion object {
        fun of(content: String): IndexedTextLines {
            if (content.isEmpty()) {
                return IndexedTextLines(content, intArrayOf(0), intArrayOf(0))
            }

            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            var lineStart = 0
            var index = 0
            while (index < content.length) {
                val current = content[index]
                if (current == '\n' || current == '\r') {
                    starts += lineStart
                    ends += index
                    if (current == '\r' && index + 1 < content.length && content[index + 1] == '\n') {
                        index++
                    }
                    lineStart = index + 1
                }
                index++
            }
            starts += lineStart
            ends += content.length
            return IndexedTextLines(
                content = content,
                lineStarts = starts.toIntArray(),
                lineEnds = ends.toIntArray(),
            )
        }
    }
}
