package com.lomo.app.testing.fakes

import androidx.paging.PagingSource
import com.lomo.domain.model.DailyReviewCandidatePage
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.repository.MemoQueryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FakeMemoQueryRepository(private val store: FakeMemoStore) : MemoQueryRepository {
    override fun getGalleryItemsPagingSource(): PagingSource<String, com.lomo.domain.model.GalleryMemo> =
        store.galleryPagingSource()

    override suspend fun getRecentMemos(limit: Int): List<Memo> = store.recentActiveMemos(limit)

    override suspend fun getMemoCount(): Int = store.activeMemoCount()

    override fun observeListProjection(): Flow<com.lomo.domain.model.MemoProjectionPublication> =
        store.observeMemoCount().map { count ->
            com.lomo.domain.model.MemoProjectionPublication(coreRevision = count.toLong())
        }

    data class ReviewRequest(val options: com.lomo.domain.model.DailyReviewOptions, val seed: Long, val limit: Int)
    val reviewRequests = mutableListOf<ReviewRequest>()

    override suspend fun getDailyReviewPage(
        options: com.lomo.domain.model.DailyReviewOptions,
        seed: Long,
        cursor: String?,
        limit: Int,
    ): DailyReviewCandidatePage {
        reviewRequests += ReviewRequest(options, seed, limit)
        val all = store.currentActiveMemos()
        val fingerprint = (all.map { it.id to it.timestamp } to (options to seed)).hashCode().toString()
        val offset = if (cursor == null) {
            0
        } else {
            require(cursor.substringBefore(':') == fingerprint) { "stale_cursor" }
            cursor.substringAfter(':').toInt()
        }
        val filter = options.filter
        val matching = all.filter { memo ->
            val date = java.time.Instant.ofEpochMilli(
                memo.timestamp,
            ).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            (filter.startDate == null || date >= filter.startDate) &&
                (filter.endDate == null || date <= filter.endDate) &&
                (filter.hasAttachment == null || memo.imageUrls.isNotEmpty() == filter.hasAttachment) &&
                (filter.hasTodo == null || memo.content.contains("- [") == filter.hasTodo) &&
                (filter.hasUrl == null || memo.content.contains("https://") == filter.hasUrl)
        }
        fun randomKey(memo: Memo): Long {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            hash.update("lomo-review-v1".toByteArray())
            hash.update(java.nio.ByteBuffer.allocate(8).putLong(seed).array())
            hash.update(memo.id.toByteArray())
            return java.nio.ByteBuffer.wrap(hash.digest()).long
        }
        val comparator = if (options.sort == com.lomo.domain.model.DailyReviewSort.RANDOM) {
            compareBy<Memo> { randomKey(it) }.thenBy { it.timestamp }.thenBy { it.id }
        } else {
            val byMonth = compareBy<Memo> {
                java.time.Instant.ofEpochMilli(it.timestamp).atZone(java.time.ZoneId.systemDefault())
                    .let { date -> date.year * 12 + date.monthValue }
            }.thenBy { randomKey(it) }.thenBy { it.id }
            if (options.sort == com.lomo.domain.model.DailyReviewSort.RECENT) byMonth.reversed() else byMonth
        }
        val ordered = matching.sortedWith(comparator)
        val ids = ordered.drop(offset).take(limit).map { it.id }
        val next = offset + ids.size
        return DailyReviewCandidatePage(ids, if (next < ordered.size) "$fingerprint:$next" else null)
    }

    override fun getMainListPagingSource(spec: MemoQuerySpec): PagingSource<String, Memo> =
        store.mainListPagingSourceFor(spec)

    override suspend fun rankInDefaultMainList(id: String): Int? = store.rankInDefaultMainList(id)

    override suspend fun rankInMainListQuery(spec: MemoQuerySpec, id: String): Int? =
        store.rankInMainListQuery(spec, id)

    override fun reanchorMainListToIdentity(id: String) {
        store.reanchorMainListToIdentity(id)
    }

    override suspend fun getMemoById(id: String): Memo? = store.findActiveMemoById(id)

    override fun isSyncing(): Flow<Boolean> = store.observeSyncing()
}
