package com.lomo.data.engine.media

/**
 * Post P5-13 media to sync edge.
 *
 * Remote sync ownership is lomo-sync via durable .lomo/sync/v1 + local store ports. Committed
 * media events no longer journal into Kotlin provider change recorders (deleted with the S3/WebDAV
 * business owners). The adapter remains as a no-op call site so media/store edges keep a single
 * hook without dual-stack journals.
 */
class MediaSyncEdgeAdapter {
    suspend fun onCommittedMediaUpsert(relativeOrFilename: String) {
        // Remote sync baseline/tombstone is owned by lomo-sync; no Kotlin journal.
        relativeOrFilename.trim()
    }

    suspend fun onCommittedMediaDelete(relativeOrFilename: String) {
        relativeOrFilename.trim()
    }
}
