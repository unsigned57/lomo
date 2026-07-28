package com.lomo.data.engine.media

import java.util.concurrent.ConcurrentHashMap

/**
 * Draft-scoped staged media held between import (stage+verify) and memo save (promote).
 *
 * Keys are workspace-relative final paths (`media/...`) and basenames so markdown destinations
 * match regardless of whether the editor embeds the full relative path or basename only.
 * Promote plans are sealed only when [StoreMemoCommand.pendingPromotes] carries them under the
 * same operation-id as the memo command (D4). Sync recorders must not observe these entries.
 */
class PendingMediaStageRegistry {
    private val byKey = ConcurrentHashMap<String, MediaStagedFacts>()

    fun put(staged: MediaStagedFacts) {
        val finalRel = staged.suggestedFinalRelativePath.trim()
        require(finalRel.isNotEmpty()) { "staged media must carry suggestedFinalRelativePath" }
        byKey[finalRel] = staged
        byKey[finalRel.substringAfterLast('/')] = staged
    }

    fun get(key: String): MediaStagedFacts? = byKey[key.trim()]

    fun remove(key: String): MediaStagedFacts? {
        val normalized = key.trim()
        val staged = byKey.remove(normalized) ?: return null
        val finalRel = staged.suggestedFinalRelativePath
        byKey.remove(finalRel)
        byKey.remove(finalRel.substringAfterLast('/'))
        return staged
    }

    /**
     * Builds promote plans for destinations referenced by memo body content.
     * Each plan uses [operationId] so Rust can enforce same-operation promote (D4).
     * Matching keys are removed from the registry (caller owns commit / failure recovery).
     */
    fun takePlansForDestinations(
        destinations: Collection<String>,
        operationId: String,
    ): List<MediaPromotePlan> {
        val plans = ArrayList<MediaPromotePlan>()
        val seenDigests = HashSet<String>()
        destinations
            .asSequence()
            .map { raw -> raw.trim() }
            .filter { key -> key.isNotEmpty() }
            .mapNotNull { key -> resolveStaged(key)?.let { key to it } }
            .forEach { (key, staged) ->
                if (seenDigests.add(staged.digest)) {
                    val finalRel =
                        staged.suggestedFinalRelativePath.ifBlank {
                            if (key.contains('/')) key else "media/$key"
                        }
                    plans +=
                        MediaPromotePlan(
                            operationId = operationId,
                            staged = staged,
                            finalRelativePath = finalRel,
                        )
                }
            }
        return plans
    }

    private fun resolveStaged(key: String): MediaStagedFacts? =
        remove(key) ?: remove(key.substringAfterLast('/'))

    fun clear() {
        byKey.clear()
    }

    fun snapshot(): Map<String, MediaStagedFacts> = byKey.toMap()
}
