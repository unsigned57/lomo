package com.lomo.data.engine.media

/**
 * Draft-scoped staged media held between import (stage+verify) and memo save (promote).
 *
 * Keys are workspace-relative final paths (`media/...`) and basenames so markdown destinations
 * match regardless of whether the editor embeds the full relative path or basename only.
 * A plan is leased, not removed: facts leave this registry only after the owning memo operation
 * acknowledges a durable commit (or an explicit draft discard). This makes retries lossless and
 * keeps the registry a state machine rather than a destructive lookup table.
 */
class PendingMediaStageRegistry {
    private val lock = Any()
    private val byKey = mutableMapOf<String, MediaStagedFacts>()

    fun put(staged: MediaStagedFacts) {
        val finalRel = staged.suggestedFinalRelativePath.trim()
        require(finalRel.isNotEmpty()) { "staged media must carry suggestedFinalRelativePath" }
        val basename = finalRel.substringAfterLast('/')
        synchronized(lock) {
            listOf(finalRel, basename).forEach { key ->
                val previous = byKey[key]
                require(previous == null || previous.digest == staged.digest) {
                    "staged media destination is already owned by a different digest"
                }
            }
            byKey[finalRel] = staged
            byKey[basename] = staged
        }
    }

    fun get(key: String): MediaStagedFacts? = synchronized(lock) { byKey[key.trim()] }

    /** Explicitly discards one staged fact (for example when the user cancels a draft). */
    fun remove(key: String): MediaStagedFacts? {
        val normalized = key.trim()
        synchronized(lock) {
            val staged = byKey[normalized] ?: return null
            removeAliasesLocked(staged)
            return staged
        }
    }

    /**
     * Returns every currently staged fact as a candidate for one operation.
     *
     * Destination membership is deliberately not resolved here.  The Rust workspace parser is
     * the only authority allowed to decide which candidates belong to the submitted body; this
     * registry only provides a stable, de-duplicated snapshot of facts held by the process.
     */
    fun allPlans(operationId: String): List<MediaPromotePlan> {
        require(operationId.isNotBlank()) { "media promote operationId must be non-blank" }
        synchronized(lock) {
            val seenCandidates = HashSet<String>()
            return byKey.values
                .asSequence()
                .filter { staged ->
                    seenCandidates.add(
                        "${staged.digest}\u001f${staged.suggestedFinalRelativePath}",
                    )
                }
                .map { staged ->
                    MediaPromotePlan(
                        operationId = operationId,
                        staged = staged,
                        finalRelativePath = staged.suggestedFinalRelativePath,
                    )
                }.toList()
        }
    }

    /**
     * Acknowledges that [plans] were durably committed and removes only the exact staged facts
     * they leased. A newer fact that reused a path is never removed by an older acknowledgement.
     */
    fun commit(plans: Collection<MediaPromotePlan>) {
        synchronized(lock) {
            plans.forEach { plan ->
                val key = plan.staged.suggestedFinalRelativePath.trim()
                val current = byKey[key]
                if (current == plan.staged && current.digest == plan.staged.digest) {
                    removeAliasesLocked(current)
                }
            }
        }
    }

    private fun removeAliasesLocked(staged: MediaStagedFacts) {
        val finalRel = staged.suggestedFinalRelativePath.trim()
        byKey.remove(finalRel)
        byKey.remove(finalRel.substringAfterLast('/'))
    }

    fun clear() {
        synchronized(lock) { byKey.clear() }
    }

    fun snapshot(): Map<String, MediaStagedFacts> = synchronized(lock) { byKey.toMap() }
}
