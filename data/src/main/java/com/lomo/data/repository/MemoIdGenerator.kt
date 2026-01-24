package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.datastore.LomoDataStore
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracted ID generation logic from MemoSynchronizer
 * to improve code organization and single responsibility.
 */
@Singleton
class MemoIdGenerator
    @Inject
    constructor(
        private val dao: MemoDao,
        private val dataStore: LomoDataStore,
    ) {
        companion object {
            private const val MAX_RETRIES = 60 // Max 60 retries = 1 minute window
        }

        /**
         * Generates a unique memo ID based on the given timestamp.
         * If a collision is detected, the timestamp is incremented by 1 second until unique.
         *
         * @param timestamp The base timestamp in milliseconds
         * @return A pair of (safeTimestamp, generatedId)
         * @throws IllegalStateException if unable to generate unique ID after max retries
         */
        suspend fun generateUniqueId(timestamp: Long): Pair<Long, String> {
            val filenameFormat = dataStore.storageFilenameFormat.first()
            val timestampFormat = dataStore.storageTimestampFormat.first()

            var safeTimestamp = timestamp
            var retryCount = 0

            while (retryCount < MAX_RETRIES) {
                val instant = Instant.ofEpochMilli(safeTimestamp)
                val zoneId = ZoneId.systemDefault()

                val filename =
                    DateTimeFormatter
                        .ofPattern(filenameFormat)
                        .withZone(zoneId)
                        .format(instant) + ".md"

                val timeString =
                    DateTimeFormatter
                        .ofPattern(timestampFormat)
                        .withZone(zoneId)
                        .format(instant)

                val potentialId = "${filename.removeSuffix(".md")}_$timeString"

                if (dao.getMemo(potentialId) == null) {
                    return Pair(safeTimestamp, potentialId)
                }

                safeTimestamp += 1000
                retryCount++
            }

            timber.log.Timber.e("generateUniqueId: Failed after $MAX_RETRIES retries")
            throw IllegalStateException("Unable to generate unique memo ID after $MAX_RETRIES attempts")
        }

        /**
         * Formats the timestamp string for a memo entry.
         */
        suspend fun formatTimestamp(timestamp: Long): String {
            val timestampFormat = dataStore.storageTimestampFormat.first()
            return DateTimeFormatter
                .ofPattern(timestampFormat)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp))
        }

        /**
         * Generates the filename for a given timestamp.
         */
        suspend fun generateFilename(timestamp: Long): String {
            val filenameFormat = dataStore.storageFilenameFormat.first()
            return DateTimeFormatter
                .ofPattern(filenameFormat)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp)) + ".md"
        }
    }
