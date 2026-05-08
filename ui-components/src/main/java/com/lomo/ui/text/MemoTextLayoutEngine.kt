package com.lomo.ui.text

import kotlin.math.ceil

internal interface MemoTextMeasurer {
    fun measureText(
        text: String,
        start: Int,
        end: Int,
    ): Float
}

internal data class MemoTextLayoutInput(
    val text: String,
    val maxWidthPx: Float,
    val baseLetterSpacingPx: Float = 0f,
    val maxLines: Int = Int.MAX_VALUE,
    val lineHeightPx: Float = 0f,
    val baselinePx: Float = 0f,
    val protectedRanges: List<MemoTextProtectedRange> = emptyList(),
    val ellipsizeLastVisibleLine: Boolean = false,
)

internal data class MemoTextProtectedRange(
    val start: Int,
    val end: Int,
) {
    fun containsInteriorBoundary(boundaryOffset: Int): Boolean = boundaryOffset in (start + 1) until end
}

internal data class MemoTextLayout(
    val text: String,
    val lines: List<MemoTextLine>,
    val heightPx: Float,
) {
    val isEmpty: Boolean
        get() = lines.isEmpty()
}

internal data class MemoTextLine(
    val runs: List<MemoTextRun>,
    val expansionSlots: List<MemoTextExpansionSlot>,
    val ellipsis: MemoTextEllipsis?,
    val naturalWidthPx: Float,
    val visualWidthPx: Float,
    val topPx: Float,
    val baselinePx: Float,
    val bottomPx: Float,
    val isJustified: Boolean,
    val wasForcedBreak: Boolean,
)

internal data class MemoTextEllipsis(
    val xPx: Float,
    val widthPx: Float,
)

internal data class MemoTextRun(
    val start: Int,
    val end: Int,
    val text: String,
    val xPx: Float,
    val naturalWidthPx: Float,
    val extraAfterWidthPx: Float,
    val kind: MemoTextRunKind,
) {
    val isWhitespace: Boolean
        get() = kind == MemoTextRunKind.Whitespace
}

internal enum class MemoTextRunKind {
    Cjk,
    Latin,
    Whitespace,
    Punctuation,
    Other,
}

internal data class MemoTextExpansionSlot(
    val boundaryOffset: Int,
    val kind: MemoTextExpansionSlotKind,
    val extraWidthPx: Float,
    val leftText: String,
    val rightText: String,
    val afterRunIndex: Int,
)

internal enum class MemoTextExpansionSlotKind {
    CjkCharacter,
    Space,
    CjkLatinBoundary,
}

internal class MemoTextLayoutEngine(
    private val measurer: MemoTextMeasurer,
) {
    fun layout(input: MemoTextLayoutInput): MemoTextLayout {
        if (input.text.isEmpty() || input.maxWidthPx <= 0f || input.maxLines <= 0) {
            return MemoTextLayout(text = input.text, lines = emptyList(), heightPx = 0f)
        }

        val tokens = tokenize(input.text, input.baseLetterSpacingPx)
        if (tokens.isEmpty()) {
            return MemoTextLayout(text = input.text, lines = emptyList(), heightPx = 0f)
        }

        val naturalLines = buildNaturalLines(tokens, input)
        val shouldJustifyParagraph = input.text.isCjkDominant()
        val lineHeight = input.lineHeightPx.coerceAtLeast(0f)
        val baselineOffset = input.baselinePx.coerceAtLeast(0f)
        val visibleLines = naturalLines.take(input.maxLines)
        val positioned =
            visibleLines
                .mapIndexed { index, line ->
                    val isTruncatedFinalLine =
                        input.ellipsizeLastVisibleLine &&
                            index == visibleLines.lastIndex &&
                            naturalLines.size > visibleLines.size
                    val isLastVisibleLine = index == naturalLines.lastIndex || index == input.maxLines - 1
                    line
                        .maybeEllipsized(input = input, shouldEllipsize = isTruncatedFinalLine)
                        .toPositionedLine(
                            input = input,
                            topPx = index * lineHeight,
                            baselinePx = index * lineHeight + baselineOffset,
                            bottomPx = (index + 1) * lineHeight,
                            shouldJustify =
                                shouldJustifyParagraph &&
                                    !isTruncatedFinalLine &&
                                    !isLastVisibleLine &&
                                    !line.endsWithHardBreak,
                        )
                }
        val height =
            if (positioned.isEmpty()) {
                0f
            } else if (lineHeight > 0f) {
                lineHeight * positioned.size
            } else {
                positioned.size.toFloat()
            }
        return MemoTextLayout(
            text = input.text,
            lines = positioned,
            heightPx = ceil(height),
        )
    }

    private fun tokenize(
        text: String,
        baseLetterSpacingPx: Float,
    ): List<LayoutToken> {
        val tokens = mutableListOf<LayoutToken>()
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val charCount = Character.charCount(codePoint)
            when {
                codePoint == NEWLINE_CODE_POINT -> {
                    tokens +=
                        LayoutToken(
                            source = text,
                            start = index,
                            end = index + charCount,
                            kind = MemoTextRunKind.Whitespace,
                            widthPx = 0f,
                            hardBreak = true,
                        )
                    index += charCount
                }

                Character.isWhitespace(codePoint) -> {
                    val start = index
                    index += charCount
                    while (index < text.length) {
                        val next = Character.codePointAt(text, index)
                        if (next == NEWLINE_CODE_POINT || !Character.isWhitespace(next)) break
                        index += Character.charCount(next)
                    }
                    tokens += createToken(text, start, index, MemoTextRunKind.Whitespace, baseLetterSpacingPx)
                }

                codePoint.isCjkLayoutCodePoint() -> {
                    tokens += createToken(text, index, index + charCount, MemoTextRunKind.Cjk, baseLetterSpacingPx)
                    index += charCount
                }

                codePoint.isMemoLayoutPunctuation() -> {
                    tokens +=
                        createToken(
                            text = text,
                            start = index,
                            end = index + charCount,
                            kind = MemoTextRunKind.Punctuation,
                            baseLetterSpacingPx = baseLetterSpacingPx,
                        )
                    index += charCount
                }

                codePoint.isLatinMemoTextCodePoint() -> {
                    val start = index
                    index += charCount
                    while (index < text.length) {
                        val next = Character.codePointAt(text, index)
                        if (!next.isLatinMemoTextCodePoint()) break
                        index += Character.charCount(next)
                    }
                    tokens += createToken(text, start, index, MemoTextRunKind.Latin, baseLetterSpacingPx)
                }

                else -> {
                    tokens += createToken(text, index, index + charCount, MemoTextRunKind.Other, baseLetterSpacingPx)
                    index += charCount
                }
            }
        }
        return tokens
    }

    private fun createToken(
        text: String,
        start: Int,
        end: Int,
        kind: MemoTextRunKind,
        baseLetterSpacingPx: Float,
    ): LayoutToken {
        val graphemeCount = countCodePoints(text, start, end)
        val spacing = (graphemeCount - 1).coerceAtLeast(0) * baseLetterSpacingPx
        return LayoutToken(
            source = text,
            start = start,
            end = end,
            kind = kind,
            widthPx = measurer.measureText(text, start, end) + spacing,
            hardBreak = false,
        )
    }

    private fun buildNaturalLines(
        tokens: List<LayoutToken>,
        input: MemoTextLayoutInput,
    ): List<NaturalLine> {
        val lines = mutableListOf<NaturalLine>()
        val current = mutableListOf<LayoutToken>()
        var currentWidth = 0f
        var index = 0

        fun flush(
            hardBreak: Boolean,
            forcedBreak: Boolean,
        ) {
            trimTrailingWhitespace(current)
            if (current.isNotEmpty() || hardBreak) {
                lines +=
                    NaturalLine(
                        tokens = current.toList(),
                        forcedBreak = forcedBreak,
                        endsWithHardBreak = hardBreak,
                    )
            }
            current.clear()
            currentWidth = 0f
        }

        while (index < tokens.size) {
            val token = tokens[index]
            if (token.hardBreak) {
                flush(hardBreak = true, forcedBreak = false)
                index += 1
                continue
            }

            val tokenFitsEmptyLine = token.widthPx <= input.maxWidthPx
            val candidateWidth = currentWidth + token.widthWithLeadingBoundary(current, input.baseLetterSpacingPx)
            when {
                current.isEmpty() && !tokenFitsEmptyLine -> {
                    val forced = splitOversizedToken(token, input)
                    lines += forced.lines
                    current.clear()
                    currentWidth = 0f
                    forced.remainder?.let { remainder ->
                        current += remainder
                        currentWidth = remainder.widthPx
                    }
                }

                current.isEmpty() || candidateWidth <= input.maxWidthPx -> {
                    current += token
                    currentWidth = candidateWidth
                }

                else -> {
                    flush(hardBreak = false, forcedBreak = false)
                    if (!token.isWhitespace) {
                        current += token
                        currentWidth = token.widthPx
                    }
                }
            }
            index += 1
        }

        flush(hardBreak = false, forcedBreak = false)
        return lines
    }

    private fun splitOversizedToken(
        token: LayoutToken,
        input: MemoTextLayoutInput,
    ): ForcedSplitResult {
        val lines = mutableListOf<NaturalLine>()
        val current = mutableListOf<LayoutToken>()
        var currentWidth = 0f
        var index = token.start
        while (index < token.end) {
            val codePoint = Character.codePointAt(input.text, index)
            val next = index + Character.charCount(codePoint)
            val piece = createToken(input.text, index, next, token.kind, input.baseLetterSpacingPx)
            val candidateWidth = currentWidth + piece.widthWithLeadingBoundary(current, input.baseLetterSpacingPx)
            if (current.isNotEmpty() && candidateWidth > input.maxWidthPx) {
                lines += NaturalLine(tokens = current.toList(), forcedBreak = true, endsWithHardBreak = false)
                current.clear()
                currentWidth = 0f
            }
            current += piece
            currentWidth += piece.widthWithLeadingBoundary(current.dropLast(1), input.baseLetterSpacingPx)
            index = next
        }
        return ForcedSplitResult(lines = lines, remainder = current.singleOrWhole())
    }

    private fun NaturalLine.toPositionedLine(
        input: MemoTextLayoutInput,
        topPx: Float,
        baselinePx: Float,
        bottomPx: Float,
        shouldJustify: Boolean,
    ): MemoTextLine {
        val ellipsisWidth = input.ellipsisWidthPx.takeIf { hasEllipsis }
        val naturalWidth =
            tokens.sumOf { it.widthPx.toDouble() }.toFloat() +
                input.baseLetterSpacingPx * (tokens.size - 1).coerceAtLeast(0) +
                if (ellipsisWidth != null) {
                    ellipsisWidth +
                        if (tokens.isNotEmpty()) {
                            input.baseLetterSpacingPx
                        } else {
                            0f
                        }
                } else {
                    0f
                }
        val extra = (input.maxWidthPx - naturalWidth).coerceAtLeast(0f)
        val selectedSlots =
            if (shouldJustify && extra > 0f) {
                selectExpansionSlots(tokens, input.protectedRanges)
            } else {
                emptyList()
            }
        val extraPerSlot =
            if (selectedSlots.isNotEmpty()) {
                extra / selectedSlots.size
            } else {
                0f
            }
        val slotsWithExtra = selectedSlots.map { it.copy(extraWidthPx = extraPerSlot) }
        val extrasByRunIndex = slotsWithExtra.associate { it.afterRunIndex to it.extraWidthPx }
        val runs = mutableListOf<MemoTextRun>()
        var x = 0f
        tokens.forEachIndexed { index, token ->
            val extraAfter = extrasByRunIndex[index] ?: 0f
            val baseSpacingAfter =
                if (index < tokens.lastIndex || ellipsisWidth != null) {
                    input.baseLetterSpacingPx
                } else {
                    0f
                }
            runs +=
                MemoTextRun(
                    start = token.start,
                    end = token.end,
                    text = input.text.substring(token.start, token.end),
                    xPx = x,
                    naturalWidthPx = token.widthPx,
                    extraAfterWidthPx = baseSpacingAfter + extraAfter,
                    kind = token.kind,
                )
            x += token.widthPx + baseSpacingAfter + extraAfter
        }
        val ellipsis =
            ellipsisWidth?.let { width ->
                MemoTextEllipsis(
                    xPx = x,
                    widthPx = width,
                )
            }
        val visualWidth =
            if (slotsWithExtra.isNotEmpty()) {
                input.maxWidthPx
            } else {
                naturalWidth
            }
        return MemoTextLine(
            runs = runs,
            expansionSlots = slotsWithExtra,
            ellipsis = ellipsis,
            naturalWidthPx = naturalWidth,
            visualWidthPx = visualWidth,
            topPx = topPx,
            baselinePx = baselinePx,
            bottomPx = bottomPx,
            isJustified = slotsWithExtra.isNotEmpty(),
            wasForcedBreak = forcedBreak,
        )
    }

    private fun NaturalLine.maybeEllipsized(
        input: MemoTextLayoutInput,
        shouldEllipsize: Boolean,
    ): NaturalLine =
        if (!shouldEllipsize) {
            this
        } else {
            copy(
                tokens = tokens.fitBeforeEllipsis(input),
                forcedBreak = false,
                hasEllipsis = true,
            )
        }

    private fun List<LayoutToken>.fitBeforeEllipsis(input: MemoTextLayoutInput): List<LayoutToken> {
        val ellipsisWidth = input.ellipsisWidthPx
        if (ellipsisWidth > input.maxWidthPx) return emptyList()

        val fitted = mutableListOf<LayoutToken>()
        var width = 0f
        var shouldFitMoreTokens = true
        for (token in this) {
            if (shouldFitMoreTokens) {
                val tokenWidth = token.widthWithLeadingBoundary(fitted, input.baseLetterSpacingPx)
                if (canFitTokenBeforeEllipsis(width, tokenWidth, fitted + token, input, ellipsisWidth)) {
                    fitted += token
                    width += tokenWidth
                } else {
                    width = fitTokenPiecesBeforeEllipsis(token, input, fitted, width)
                    shouldFitMoreTokens = false
                }
            }
        }
        return fitted
    }

    private fun canFitTokenBeforeEllipsis(
        currentWidthPx: Float,
        tokenWidthPx: Float,
        tokensWithCandidate: List<LayoutToken>,
        input: MemoTextLayoutInput,
        ellipsisWidthPx: Float,
    ): Boolean =
        currentWidthPx + tokenWidthPx +
            ellipsisSpacingAfter(tokensWithCandidate, input.baseLetterSpacingPx) +
            ellipsisWidthPx <= input.maxWidthPx

    private fun fitTokenPiecesBeforeEllipsis(
        token: LayoutToken,
        input: MemoTextLayoutInput,
        fitted: MutableList<LayoutToken>,
        initialWidth: Float,
    ): Float {
        var width = initialWidth
        var index = token.start
        while (index < token.end) {
            val codePoint = Character.codePointAt(input.text, index)
            val next = index + Character.charCount(codePoint)
            val piece = createToken(input.text, index, next, token.kind, input.baseLetterSpacingPx)
            val pieceWidth = piece.widthWithLeadingBoundary(fitted, input.baseLetterSpacingPx)
            val widthWithEllipsis =
                width + pieceWidth + ellipsisSpacingAfter(fitted + piece, input.baseLetterSpacingPx) +
                    input.ellipsisWidthPx
            if (widthWithEllipsis > input.maxWidthPx) break
            fitted += piece
            width += pieceWidth
            index = next
        }
        return width
    }

    private val MemoTextLayoutInput.ellipsisWidthPx: Float
        get() = measurer.measureText(MEMO_TEXT_ELLIPSIS, 0, MEMO_TEXT_ELLIPSIS.length)

    private fun selectExpansionSlots(
        tokens: List<LayoutToken>,
        protectedRanges: List<MemoTextProtectedRange>,
    ): List<MemoTextExpansionSlot> {
        val candidates =
            tokens
                .windowed(size = 2, partialWindows = false)
                .mapIndexedNotNull { index, pair ->
                    val left = pair[0]
                    val right = pair[1]
                    val boundaryOffset = left.end
                    val kind = expansionKind(left, right) ?: return@mapIndexedNotNull null
                    if (protectedRanges.any { range -> range.containsInteriorBoundary(boundaryOffset) }) {
                        return@mapIndexedNotNull null
                    }
                    MemoTextExpansionSlot(
                        boundaryOffset = boundaryOffset,
                        kind = kind,
                        extraWidthPx = 0f,
                        leftText = leftText(left),
                        rightText = rightText(right),
                        afterRunIndex = index,
                    )
                }
        val preferredKind =
            MemoTextExpansionSlotKind.entries.firstOrNull { kind ->
                candidates.any { it.kind == kind }
            } ?: return emptyList()
        return candidates.filter { it.kind == preferredKind }
    }

    private companion object {
        private const val NEWLINE_CODE_POINT = '\n'.code
    }
}
