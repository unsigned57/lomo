package com.lomo.data.share

internal object ShareAttachmentReferenceRemapper {
    fun remapMarkdownTargets(
        content: String,
        attachmentMappings: Map<String, String>,
    ): String {
        val lookup = AttachmentMappingLookup.from(attachmentMappings)
        return if (lookup.isEmpty) {
            content
        } else {
            ShareAttachmentMarkdownRemapSession(content = content, lookup = lookup).remap()
        }
    }
}

private class ShareAttachmentMarkdownRemapSession(
    private val content: String,
    private val lookup: AttachmentMappingLookup,
) {
    private val remapped = StringBuilder(content.length)
    private val blockState = MarkdownBlockScanState()
    private var index = 0

    fun remap(): String {
        while (index < content.length) {
            val lineEnd = content.indexOf('\n', startIndex = index).takeIf { it >= 0 } ?: content.length
            appendRemappedLine(content.substring(index, lineEnd))
            appendLineBreakIfNeeded(lineEnd)
            index = if (lineEnd < content.length) lineEnd + 1 else content.length
        }
        return remapped.toString()
    }

    private fun appendRemappedLine(line: String) {
        val scan = blockState.scan(line)
        val lineText =
            if (scan.preservesOriginalLine) {
                line
            } else {
                ShareAttachmentLineRemapper.remap(line = line, lookup = lookup)
            }
        remapped.append(lineText)
        blockState.advance(line = line, scan = scan)
    }

    private fun appendLineBreakIfNeeded(lineEnd: Int) {
        if (lineEnd < content.length) {
            remapped.append('\n')
        }
    }
}

private class MarkdownBlockScanState {
    private var activeFence: MarkdownFence? = null
    private var previousBlockContext = MarkdownBlockContext.Blank
    private var listContentIndent: Int? = null

    fun scan(line: String): MarkdownLineScan {
        val lineIsBlank = MarkdownBlockParser.isMarkdownBlankLine(line)
        val fence = MarkdownBlockParser.parseFenceLine(line)
        val currentFence = activeFence
        val fenceLine = isFenceLine(currentFence = currentFence, fence = fence)
        val listIndentedCodeLine = isListIndentedCodeLine(line, lineIsBlank, fenceLine, currentFence)
        val indentedCodeLine = isTopLevelIndentedCodeLine(line, lineIsBlank, fenceLine, currentFence)
        return MarkdownLineScan(
            fence = fence,
            insideFence = currentFence != null,
            fenceLine = fenceLine,
            lineIsBlank = lineIsBlank,
            listIndentedCodeLine = listIndentedCodeLine,
            indentedCodeLine = indentedCodeLine,
        )
    }

    fun advance(
        line: String,
        scan: MarkdownLineScan,
    ) {
        when {
            scan.insideFence -> closeFenceIfNeeded(scan)
            scan.fenceLine -> openFence(scan.fence)
            scan.lineIsBlank -> afterBlankLine()
            scan.listIndentedCodeLine -> previousBlockContext = MarkdownBlockContext.ListContainer
            scan.indentedCodeLine -> previousBlockContext = MarkdownBlockContext.IndentedCode
            else -> updateBlockContext(line)
        }
    }

    private fun isFenceLine(
        currentFence: MarkdownFence?,
        fence: MarkdownFence?,
    ): Boolean =
        if (currentFence == null) {
            fence != null
        } else {
            fence?.canClose(currentFence) == true
        }

    private fun isListIndentedCodeLine(
        line: String,
        lineIsBlank: Boolean,
        fenceLine: Boolean,
        currentFence: MarkdownFence?,
    ): Boolean =
        currentFence == null &&
            !fenceLine &&
            !lineIsBlank &&
            previousBlockContext == MarkdownBlockContext.ListContainer &&
            MarkdownBlockParser.isListItemIndentedCodeBlockLine(line, listContentIndent)

    private fun isTopLevelIndentedCodeLine(
        line: String,
        lineIsBlank: Boolean,
        fenceLine: Boolean,
        currentFence: MarkdownFence?,
    ): Boolean =
        currentFence == null &&
            previousBlockContext != MarkdownBlockContext.ListContainer &&
            !fenceLine &&
            !lineIsBlank &&
            MarkdownBlockParser.isIndentedCodeBlockLine(line) &&
            previousBlockContext.allowsIndentedCodeBlockStart()

    private fun closeFenceIfNeeded(scan: MarkdownLineScan) {
        if (scan.fenceLine) {
            activeFence = null
            previousBlockContext = MarkdownBlockContext.Fence
            listContentIndent = null
        }
    }

    private fun openFence(fence: MarkdownFence?) {
        activeFence = fence
        previousBlockContext = MarkdownBlockContext.Fence
        listContentIndent = null
    }

    private fun afterBlankLine() {
        previousBlockContext = previousBlockContext.afterBlankLine()
        if (previousBlockContext != MarkdownBlockContext.ListContainer) {
            listContentIndent = null
        }
    }

    private fun updateBlockContext(line: String) {
        val parsedListContentIndent = MarkdownBlockParser.parseListContentIndent(line)
        previousBlockContext =
            MarkdownBlockParser.resolveBlockContext(
                line = line,
                previousBlockContext = previousBlockContext,
                listContentIndent = listContentIndent,
                parsedListContentIndent = parsedListContentIndent,
            )
        listContentIndent =
            when (previousBlockContext) {
                MarkdownBlockContext.ListContainer -> parsedListContentIndent ?: listContentIndent
                else -> null
            }
    }
}

private data class MarkdownLineScan(
    val fence: MarkdownFence?,
    val insideFence: Boolean,
    val fenceLine: Boolean,
    val lineIsBlank: Boolean,
    val listIndentedCodeLine: Boolean,
    val indentedCodeLine: Boolean,
) {
    val preservesOriginalLine: Boolean =
        insideFence || fenceLine || listIndentedCodeLine || indentedCodeLine
}

private object ShareAttachmentLineRemapper {
    fun remap(
        line: String,
        lookup: AttachmentMappingLookup,
    ): String {
        parseReferenceDefinition(line, lookup)?.let { definition -> return definition }

        val remapped = StringBuilder(line.length)
        var index = 0
        while (index < line.length) {
            val codeSpanEnd = findCodeSpanEndIfPresent(line = line, index = index)
            when {
                codeSpanEnd == CODE_SPAN_UNCLOSED -> {
                    remapped.append(line.substring(index))
                    index = line.length
                }
                codeSpanEnd != null -> {
                    remapped.append(line.substring(index, codeSpanEnd))
                    index = codeSpanEnd
                }
                else -> {
                    val parsed = parseInlineTargetAt(line = line, index = index, lookup = lookup)
                    if (parsed == null) {
                        remapped.append(line[index])
                        index += 1
                    } else {
                        remapped.append(parsed.text)
                        index = parsed.endExclusive
                    }
                }
            }
        }
        return remapped.toString()
    }

    private fun findCodeSpanEndIfPresent(
        line: String,
        index: Int,
    ): Int? =
        if (line[index] == '`') {
            MarkdownInlineScanner.findCodeSpanEnd(line, index)
        } else {
            null
        }

    private fun parseInlineTargetAt(
        line: String,
        index: Int,
        lookup: AttachmentMappingLookup,
    ): ParsedInlineTarget? {
        val bracketIndex = linkBracketIndexAt(line = line, index = index) ?: return null
        return parseInlineTarget(
            line = line,
            linkStart = index,
            bracketIndex = bracketIndex,
            lookup = lookup,
        )
    }

    private fun linkBracketIndexAt(
        line: String,
        index: Int,
    ): Int? =
        when {
            line[index] == '!' && line.getOrNull(index + 1) == '[' -> index + 1
            line[index] == '[' -> index
            else -> null
        }

    private fun parseReferenceDefinition(
        line: String,
        lookup: AttachmentMappingLookup,
    ): String? {
        val indentEnd = line.indexOfFirst { it != ' ' && it != '\t' }.takeUnless { it == -1 } ?: return null
        if (line.getOrNull(indentEnd) != '[') return null

        val labelEnd = MarkdownInlineScanner.findClosingBracket(line, indentEnd + 1)
        if (labelEnd == CODE_SPAN_UNCLOSED || line.getOrNull(labelEnd + 1) != ':') return null

        val bodyStart = labelEnd + 2
        val parsedBody = parseDestinationWithOptionalTitle(line, bodyStart, lookup) ?: return null
        val remappedDestination = parsedBody.destination.remapMarkdownDestination(lookup) ?: return line
        return line.substring(0, parsedBody.destination.start) +
            remappedDestination +
            line.substring(parsedBody.destination.endExclusive)
    }

    private fun parseInlineTarget(
        line: String,
        linkStart: Int,
        bracketIndex: Int,
        lookup: AttachmentMappingLookup,
    ): ParsedInlineTarget? {
        val labelEnd = MarkdownInlineScanner.findClosingBracket(line, bracketIndex + 1)
        if (labelEnd == CODE_SPAN_UNCLOSED || line.getOrNull(labelEnd + 1) != '(') return null

        val targetStart = labelEnd + 2
        val targetEnd = MarkdownInlineScanner.findClosingTarget(line, targetStart)
        if (targetEnd == CODE_SPAN_UNCLOSED) return null

        val targetLine = line.substring(0, targetEnd)
        val parsedTarget = parseDestinationWithOptionalTitle(targetLine, targetStart, lookup) ?: return null
        val remappedTarget = parsedTarget.destination.remapMarkdownDestination(lookup) ?: parsedTarget.destination.raw
        return ParsedInlineTarget(
            text =
                line.substring(linkStart, parsedTarget.destination.start) +
                    remappedTarget +
                    line.substring(parsedTarget.destination.endExclusive, targetEnd) +
                    ")",
            endExclusive = targetEnd + 1,
        )
    }

    private fun parseDestinationWithOptionalTitle(
        line: String,
        start: Int,
        lookup: AttachmentMappingLookup,
    ): ParsedDestinationWithTitle? {
        val destinationStart = MarkdownInlineScanner.indexOfFirstFrom(line, start) { !it.isWhitespace() }
        if (destinationStart == CODE_SPAN_UNCLOSED) return null

        val destination =
            if (line[destinationStart] == '<') {
                val destinationEnd = MarkdownInlineScanner.findAngleDestinationEnd(line, destinationStart + 1)
                if (destinationEnd == CODE_SPAN_UNCLOSED) return null
                MarkdownDestination(
                    raw = line.substring(destinationStart, destinationEnd + 1),
                    start = destinationStart,
                    endExclusive = destinationEnd + 1,
                    enclosedInAngles = true,
                )
            } else {
                findMappedBareDestination(line, destinationStart, lookup)
                    ?: MarkdownDestination(
                        raw = line.substring(
                            startIndex = destinationStart,
                            endIndex = MarkdownInlineScanner.findBareDestinationEnd(line, destinationStart),
                        ),
                        start = destinationStart,
                        endExclusive = MarkdownInlineScanner.findBareDestinationEnd(line, destinationStart),
                        enclosedInAngles = false,
                    )
            }
        if (!MarkdownInlineScanner.hasValidOptionalTitle(line, destination.endExclusive)) return null
        return ParsedDestinationWithTitle(destination = destination)
    }

    private fun findMappedBareDestination(
        line: String,
        start: Int,
        lookup: AttachmentMappingLookup,
    ): MarkdownDestination? {
        var end = line.length
        while (end > start) {
            if (!line[end - 1].isWhitespace()) {
                val raw = line.substring(start, end)
                if (lookup.remap(raw) != null && MarkdownInlineScanner.hasValidOptionalTitle(line, end)) {
                    return MarkdownDestination(
                        raw = raw,
                        start = start,
                        endExclusive = end,
                        enclosedInAngles = false,
                    )
                }
            }
            end -= 1
        }
        return null
    }

    private fun MarkdownDestination.remapMarkdownDestination(
        lookup: AttachmentMappingLookup,
    ): String? {
        val target =
            if (enclosedInAngles && raw.length > ANGLE_DESTINATION_WRAPPER_LENGTH) {
                raw.substring(1, raw.lastIndex)
            } else {
                raw
            }
        val remapped = lookup.remap(target) ?: return null
        return if (enclosedInAngles) "<$remapped>" else remapped
    }
}

private object MarkdownInlineScanner {
    fun findAngleDestinationEnd(
        line: String,
        start: Int,
    ): Int {
        var index = start
        while (index < line.length) {
            when (line[index]) {
                '\\' -> index += 1
                '>' -> return index
                '\n', '\r' -> return CODE_SPAN_UNCLOSED
            }
            index += 1
        }
        return CODE_SPAN_UNCLOSED
    }

    fun findBareDestinationEnd(
        line: String,
        start: Int,
    ): Int {
        var index = start
        var depth = 0
        while (index < line.length) {
            when (line[index]) {
                '\\' -> index += 1
                '(' -> depth += 1
                ')' -> {
                    if (depth == 0) return index
                    depth -= 1
                }
                ' ', '\t', '\r', '\n' -> if (depth == 0) return index
            }
            index += 1
        }
        return index
    }

    fun hasValidOptionalTitle(
        line: String,
        start: Int,
    ): Boolean {
        val titleStart = indexOfFirstFrom(line, start) { !it.isWhitespace() }
        if (titleStart == CODE_SPAN_UNCLOSED) return true
        val titleEnd =
            when (line[titleStart]) {
                '"', '\'' -> findQuotedTitleEnd(line, titleStart)
                '(' -> findParenthesizedTitleEnd(line, titleStart)
                else -> return false
            }
        if (titleEnd == CODE_SPAN_UNCLOSED) return false
        return line.substring(titleEnd + 1).all { it.isWhitespace() }
    }

    fun findQuotedTitleEnd(
        line: String,
        start: Int,
    ): Int {
        val quote = line[start]
        var index = start + 1
        while (index < line.length) {
            when (line[index]) {
                '\\' -> index += 1
                quote -> return index
            }
            index += 1
        }
        return CODE_SPAN_UNCLOSED
    }

    fun findParenthesizedTitleEnd(
        line: String,
        start: Int,
    ): Int {
        var index = start + 1
        while (index < line.length) {
            when (line[index]) {
                '\\' -> index += 1
                ')' -> return index
            }
            index += 1
        }
        return CODE_SPAN_UNCLOSED
    }

    inline fun indexOfFirstFrom(
        line: String,
        start: Int,
        predicate: (Char) -> Boolean,
    ): Int {
        var index = start
        while (index < line.length) {
            if (predicate(line[index])) return index
            index += 1
        }
        return CODE_SPAN_UNCLOSED
    }

    fun findClosingBracket(
        line: String,
        start: Int,
    ): Int {
        var index = start
        var depth = 1
        while (index < line.length) {
            when (line[index]) {
                '\\' -> index += 1
                '[' -> depth += 1
                ']' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
            index += 1
        }
        return CODE_SPAN_UNCLOSED
    }

    fun findClosingTarget(
        line: String,
        start: Int,
    ): Int {
        var index = start
        var depth = 1
        while (index < line.length) {
            when (line[index]) {
                '\\' -> index += 1
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
            index += 1
        }
        return CODE_SPAN_UNCLOSED
    }

    fun findCodeSpanEnd(
        line: String,
        start: Int,
    ): Int {
        val runLength = countBacktickRun(line, start)
        var index = start + runLength
        while (index < line.length) {
            if (line[index] == '`' && countBacktickRun(line, index) >= runLength) {
                return index + runLength
            }
            index += 1
        }
        return CODE_SPAN_UNCLOSED
    }

    fun countBacktickRun(
        line: String,
        start: Int,
    ): Int {
        var index = start
        while (line.getOrNull(index) == '`') {
            index += 1
        }
        return index - start
    }
}

private object MarkdownBlockParser {
    fun isIndentedCodeBlockLine(line: String): Boolean {
        var spaces = 0
        for (character in line) {
            when (character) {
                '\t' -> return true
                ' ' -> {
                    spaces += 1
                    if (spaces >= INDENTED_CODE_BLOCK_SPACES) return true
                }
                else -> return false
            }
        }
        return false
    }

    fun resolveBlockContext(
        line: String,
        previousBlockContext: MarkdownBlockContext,
        listContentIndent: Int?,
        parsedListContentIndent: Int?,
    ): MarkdownBlockContext =
        when {
            startsReferenceDefinition(line) -> MarkdownBlockContext.Reference
            isAtxHeadingLine(line) -> MarkdownBlockContext.Heading
            parsedListContentIndent != null -> MarkdownBlockContext.ListContainer
            isListContinuation(previousBlockContext, line, listContentIndent) -> MarkdownBlockContext.ListContainer
            else -> MarkdownBlockContext.TopLevelParagraph
        }

    fun isAtxHeadingLine(line: String): Boolean {
        val markerStart = MarkdownListParser.countLeadingSpaces(line)
            .takeIf { it <= MAX_MARKDOWN_BLOCK_INDENT_SPACES } ?: return false
        var markerEnd = markerStart
        while (line.getOrNull(markerEnd) == '#') {
            markerEnd += 1
        }
        val markerLength = markerEnd - markerStart
        return markerLength in MIN_HEADING_MARKER_LENGTH..MAX_HEADING_MARKER_LENGTH &&
            line.getOrNull(markerEnd).let { it == null || it == ' ' || it == '\t' || it == '\r' }
    }

    fun parseListContentIndent(line: String): Int? {
        val markerStart = MarkdownListParser.countLeadingSpaces(line)
            .takeIf { it <= MAX_MARKDOWN_BLOCK_INDENT_SPACES } ?: return null
        val marker = line.getOrNull(markerStart) ?: return null
        val markerEnd =
            when {
                MarkdownListParser.isUnorderedListMarker(line, markerStart, marker) -> markerStart + 1
                marker.isDigit() -> MarkdownListParser.orderedListMarkerEnd(line, markerStart) ?: return null
                else -> return null
            }
        val paddingEnd = MarkdownListParser.listMarkerPaddingEnd(line, markerEnd)
        return paddingEnd.takeIf { it > markerEnd } ?: (markerEnd + 1)
    }

    fun isListContinuationLine(
        line: String,
        listContentIndent: Int?,
    ): Boolean =
        listContentIndent != null &&
            MarkdownListParser.leadingIndentColumn(line) >= listContentIndent

    fun isListItemIndentedCodeBlockLine(
        line: String,
        listContentIndent: Int?,
    ): Boolean =
        listContentIndent != null &&
            MarkdownListParser.leadingIndentColumn(line) >= listContentIndent + INDENTED_CODE_BLOCK_SPACES

    fun startsReferenceDefinition(line: String): Boolean {
        val indentEnd = line.indexOfFirst { it != ' ' && it != '\t' }.takeUnless { it == -1 } ?: return false
        if (indentEnd > MAX_MARKDOWN_BLOCK_INDENT_SPACES || line.getOrNull(indentEnd) != '[') return false

        val labelEnd = MarkdownInlineScanner.findClosingBracket(line, indentEnd + 1)
        return labelEnd != CODE_SPAN_UNCLOSED && line.getOrNull(labelEnd + 1) == ':'
    }

    fun isMarkdownBlankLine(line: String): Boolean =
        line.all { it == ' ' || it == '\t' || it == '\r' }

    fun parseFenceLine(line: String): MarkdownFence? {
        val normalizedLine = line.trimEnd('\r')
        val markerStart = fenceMarkerStart(normalizedLine)
        val marker = markerStart?.let(normalizedLine::getOrNull)
        val markerEnd = marker?.takeIf { fenceMarker ->
            fenceMarker == BACKTICK_FENCE_MARKER || fenceMarker == TILDE_FENCE_MARKER
        }?.let { fenceMarker ->
            MarkdownListParser.markerRunEnd(line = normalizedLine, start = markerStart, marker = fenceMarker)
        }
        val markerLength = markerEnd?.minus(markerStart)
        return if (
            marker == null ||
            markerEnd == null ||
            markerLength == null ||
            markerLength < MIN_FENCE_MARKER_LENGTH
        ) {
            null
        } else {
            MarkdownFence(
                marker = marker,
                markerLength = markerLength,
                markerEnd = markerEnd,
                line = normalizedLine,
            )
        }
    }

    fun fenceMarkerStart(line: String): Int? {
        var markerStart = 0
        while (line.getOrNull(markerStart) == ' ') {
            markerStart += 1
            if (markerStart > MAX_MARKDOWN_BLOCK_INDENT_SPACES) return null
        }
        return markerStart
    }

    private fun isListContinuation(
        previousBlockContext: MarkdownBlockContext,
        line: String,
        listContentIndent: Int?,
    ): Boolean =
        previousBlockContext == MarkdownBlockContext.ListContainer &&
            isListContinuationLine(line = line, listContentIndent = listContentIndent)
}

private object MarkdownListParser {
    fun isUnorderedListMarker(
        line: String,
        markerStart: Int,
        marker: Char,
    ): Boolean =
        marker in UNORDERED_LIST_MARKERS &&
            line.getOrNull(markerStart + 1).let { it == null || it == ' ' || it == '\t' || it == '\r' }

    fun orderedListMarkerEnd(
        line: String,
        markerStart: Int,
    ): Int? {
        var markerEnd = markerStart
        while (line.getOrNull(markerEnd)?.isDigit() == true) {
            markerEnd += 1
        }
        if (markerEnd - markerStart > MAX_ORDERED_LIST_MARKER_DIGITS) return null
        if (line.getOrNull(markerEnd) != '.' && line.getOrNull(markerEnd) != ')') return null
        return (markerEnd + 1)
            .takeIf { line.getOrNull(it).let { next -> next == null || next == ' ' || next == '\t' || next == '\r' } }
    }

    fun listMarkerPaddingEnd(
        line: String,
        markerEnd: Int,
    ): Int {
        var index = markerEnd
        while (line.getOrNull(index) == ' ' || line.getOrNull(index) == '\t') {
            index += 1
        }
        return index
    }

    fun leadingIndentColumn(line: String): Int {
        var column = 0
        var index = 0
        while (index < line.length) {
            when (line[index]) {
                ' ' -> column += 1
                '\t' -> column += INDENTED_CODE_BLOCK_SPACES
                else -> return column
            }
            index += 1
        }
        return column
    }

    fun countLeadingSpaces(line: String): Int {
        var spaces = 0
        while (line.getOrNull(spaces) == ' ') {
            spaces += 1
        }
        return spaces
    }

    fun markerRunEnd(
        line: String,
        start: Int,
        marker: Char,
    ): Int {
        var index = start
        while (line.getOrNull(index) == marker) {
            index += 1
        }
        return index
    }
}

private data class ParsedInlineTarget(
    val text: String,
    val endExclusive: Int,
)

private data class ParsedDestinationWithTitle(
    val destination: MarkdownDestination,
)

private data class MarkdownDestination(
    val raw: String,
    val start: Int,
    val endExclusive: Int,
    val enclosedInAngles: Boolean,
)

private data class MarkdownFence(
    val marker: Char,
    val markerLength: Int,
    val markerEnd: Int,
    val line: String,
) {
    fun canClose(openingFence: MarkdownFence): Boolean =
        marker == openingFence.marker &&
            markerLength >= openingFence.markerLength &&
            line.substring(markerEnd).all { it == ' ' || it == '\t' }
}

private data class AttachmentMappingLookup(
    private val exactTargets: Map<String, String>,
    private val uniqueBasenames: Map<String, String>,
) {
    val isEmpty: Boolean = exactTargets.isEmpty() && uniqueBasenames.isEmpty()

    fun remap(target: String): String? {
        if (isExternalTarget(target)) return null
        val normalizedTarget = normalizeAttachmentReference(target) ?: return null
        return exactTargets[normalizedTarget]
            ?: uniqueBasenames[normalizedTarget.substringAfterLast('/')]
    }

    companion object {
        fun from(attachmentMappings: Map<String, String>): AttachmentMappingLookup {
            val exactTargets = linkedMapOf<String, String>()
            val basenameValues = linkedMapOf<String, MutableSet<String>>()
            attachmentMappings.forEach { (originalName, storedName) ->
                val normalizedOriginal = normalizeAttachmentReference(originalName) ?: return@forEach
                exactTargets[normalizedOriginal] = storedName
                basenameValues.getOrPut(normalizedOriginal.substringAfterLast('/')) { linkedSetOf() } += storedName
            }
            val uniqueBasenames =
                basenameValues
                    .mapNotNull { (basename, storedNames) ->
                        storedNames.singleOrNull()?.let { basename to it }
                    }.toMap()
            return AttachmentMappingLookup(exactTargets = exactTargets, uniqueBasenames = uniqueBasenames)
        }

        private fun normalizeAttachmentReference(reference: String): String? {
            var normalized = reference.trim().replace('\\', '/')
            while (normalized.startsWith("./")) {
                normalized = normalized.removePrefix("./")
            }
            return normalized.takeIf(String::isNotBlank)
        }

        private fun isExternalTarget(target: String): Boolean =
            target.startsWith("#") ||
                target.startsWith("//") ||
                URI_SCHEME_PATTERN.matchesAt(target, 0)

        private val URI_SCHEME_PATTERN = Regex("""[A-Za-z][A-Za-z0-9+.-]*:""")
    }
}

private enum class MarkdownBlockContext {
    Blank,
    TopLevelParagraph,
    ListContainer,
    Heading,
    Reference,
    Fence,
    IndentedCode,
    ;

    fun allowsIndentedCodeBlockStart(): Boolean =
        when (this) {
            Blank,
            Heading,
            Reference,
            Fence,
            IndentedCode,
            -> true

            TopLevelParagraph,
            ListContainer,
            -> false
        }

    fun afterBlankLine(): MarkdownBlockContext =
        when (this) {
            ListContainer -> ListContainer
            IndentedCode -> IndentedCode
            else -> Blank
        }
}

private const val CODE_SPAN_UNCLOSED = -1
private const val ANGLE_DESTINATION_WRAPPER_LENGTH = 2
private const val INDENTED_CODE_BLOCK_SPACES = 4
private const val MAX_MARKDOWN_BLOCK_INDENT_SPACES = 3
private const val MIN_FENCE_MARKER_LENGTH = 3
private const val MIN_HEADING_MARKER_LENGTH = 1
private const val MAX_HEADING_MARKER_LENGTH = 6
private const val MAX_ORDERED_LIST_MARKER_DIGITS = 9
private const val BACKTICK_FENCE_MARKER = '`'
private const val TILDE_FENCE_MARKER = '~'
private val UNORDERED_LIST_MARKERS = setOf('-', '+', '*')
