package com.lomo.data.repository

internal data class ProviderMediaLocation(
    val displayName: String,
    val location: String,
)

internal sealed interface MediaReferenceResolution {
    data class Resolved(
        val location: String,
    ) : MediaReferenceResolution

    data object Ambiguous : MediaReferenceResolution

    data object Missing : MediaReferenceResolution
}

/** Binds provider display names to references without selecting through a normalized collision. */
internal class MediaReferenceIndex private constructor(
    private val exact: Map<String, String>,
    private val compatible: Map<String, MediaReferenceResolution>,
) {
    fun resolve(reference: String): MediaReferenceResolution {
        val basename = referenceBasename(reference)
        exact[basename]?.let { location -> return MediaReferenceResolution.Resolved(location) }
        return compatible[compatibleMediaKey(basename)] ?: MediaReferenceResolution.Missing
    }

    companion object {
        fun build(entries: List<ProviderMediaLocation>): MediaReferenceIndex {
            val exact = linkedMapOf<String, String>()
            val grouped = linkedMapOf<String, MutableList<String>>()
            for (entry in entries) {
                require(entry.displayName.isNotBlank()) { "Provider media display name must not be blank" }
                require(entry.location.isNotBlank()) { "Provider media location must not be blank" }
                val prior = exact.put(entry.displayName, entry.location)
                require(prior == null || prior == entry.location) {
                    "Provider returned one media display name with multiple locations"
                }
                grouped.getOrPut(compatibleMediaKey(entry.displayName), ::mutableListOf) += entry.location
            }
            val compatible =
                grouped.mapValues { (_, locations) ->
                    val distinct = locations.distinct()
                    if (distinct.size == 1) {
                        MediaReferenceResolution.Resolved(distinct.single())
                    } else {
                        MediaReferenceResolution.Ambiguous
                    }
                }
            return MediaReferenceIndex(exact = exact, compatible = compatible)
        }
    }
}

private fun referenceBasename(reference: String): String =
    reference
        .trim()
        .substringBefore('?')
        .substringBefore('#')
        .replace('\\', '/')
        .substringAfterLast('/')

private fun compatibleMediaKey(displayName: String): String =
    displayName
        .trim()
        .replace(Regex("[ _-]+"), "_")
