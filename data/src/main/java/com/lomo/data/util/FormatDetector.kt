package com.lomo.data.util

import javax.inject.Inject

class FormatDetector
    @Inject
    constructor() {
        fun detectFormats(
            filenames: List<String>,
            fileContents: List<String>, // First lines or full content
        ): Pair<String?, String?> {
            val detectedFilename = detectFilenameFormat(filenames)
            val detectedTimestamp = detectTimestampFormat(fileContents)
            return detectedFilename to detectedTimestamp
        }

        private fun detectFilenameFormat(filenames: List<String>): String? {
            val patterns =
                mapOf(
                    "yyyy_MM_dd" to Regex("^\\d{4}_\\d{2}_\\d{2}\\.md$"),
                    "yyyy-MM-dd" to Regex("^\\d{4}-\\d{2}-\\d{2}\\.md$"),
                    "yyyy.MM.dd" to Regex("^\\d{4}\\.\\d{2}\\.\\d{2}\\.md$"),
                    "yyyyMMdd" to Regex("^\\d{8}\\.md$"),
                    "MM-dd-yyyy" to Regex("^\\d{2}-\\d{2}-\\d{4}\\.md$"),
                    "dd-MM-yyyy" to
                        Regex(
                            "^\\d{2}-\\d{2}-\\d{4}\\.md$",
                        ), // Regex same as above, logic priority might matter
                )

            val counts = mutableMapOf<String, Int>()

            filenames.forEach { name ->
                patterns.forEach { (format, regex) ->
                    if (name.matches(regex)) {
                        counts[format] = (counts[format] ?: 0) + 1
                    }
                }
            }

            return counts.maxByOrNull { it.value }?.key
        }

        private fun detectTimestampFormat(firstLines: List<String>): String? {
            // Counters
            var countHHMMSS = 0
            var countHHMM = 0
            var countISO = 0
            var count12h = 0

            val isoPattern = Regex("\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}(:\\d{2})?")
            val time12hPattern = Regex("\\d{1,2}:\\d{2}\\s?[AaPp][Mm]")
            val time24hSeconds = Regex("\\d{2}:\\d{2}:\\d{2}")
            val time24hMinutes = Regex("\\d{2}:\\d{2}")

            firstLines.forEach { line ->
                when {
                    line.contains(isoPattern) -> countISO++
                    line.contains(time12hPattern) -> count12h++
                    line.contains(time24hSeconds) -> countHHMMSS++
                    line.contains(time24hMinutes) -> countHHMM++
                }
            }

            return when {
                countISO > 0 &&
                    countISO >= countHHMMSS &&
                    countISO >= countHHMM &&
                    countISO >= count12h -> "yyyy-MM-dd HH:mm:ss"

                count12h > 0 && count12h >= countHHMMSS && count12h >= countHHMM -> "hh:mm a"

                countHHMMSS > countHHMM -> "HH:mm:ss"

                countHHMM > 0 -> "HH:mm"

                else -> null
            }
        }
    }
