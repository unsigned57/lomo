package com.lomo.baseline

data class BaselineRule(
    val classFlags: String,
    val methodFlags: String,
    private val matcher: Regex,
) {
    fun matches(internalClassName: String): Boolean = matcher.matches(internalClassName)

    companion object {
        private val validFlags = Regex("[HSPL]+")

        fun parse(text: String): List<BaselineRule> =
            text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .map(::parseLine)
                .toList()

        private fun parseLine(line: String): BaselineRule {
            val parts = line.split(Regex("\\s+"), limit = 2)
            require(parts.size == 2) { "Invalid rule line: $line" }

            val flags = parts[0]
            require(validFlags.matches(flags)) { "Invalid flags '$flags' in rule: $line" }

            return BaselineRule(
                classFlags = if ('L' in flags) "L" else "",
                methodFlags = flags,
                matcher = compileGlob(parts[1]),
            )
        }

        private fun compileGlob(pattern: String): Regex {
            val builder = StringBuilder("^")
            var index = 0
            while (index < pattern.length) {
                when {
                    pattern.startsWith("**", index) -> {
                        builder.append(".*")
                        index += 2
                    }

                    pattern[index] == '*' -> {
                        builder.append("[^/]*")
                        index += 1
                    }

                    else -> {
                        builder.append(Regex.escape(pattern[index].toString()))
                        index += 1
                    }
                }
            }
            builder.append('$')
            return Regex(builder.toString())
        }
    }
}
