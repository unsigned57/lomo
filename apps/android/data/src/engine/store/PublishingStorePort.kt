package com.lomo.data.engine.store

import com.lomo.data.repository.StoreProjectionObserver

/**
 * Store write adapter that observes owner stamps onto the single projection publication bus.
 *
 * Callers still receive the command result for reminder/media side effects; they must not publish.
 */
internal class PublishingStorePort(private val delegate: StorePort, private val observer: StoreProjectionObserver) :
    StorePort by delegate {
    /**
     * Every stamp — acceptance, withdrawal and the durable commit — publishes: the committed row
     * replaces the pending one and the commit revision stales every acceptance-revision cursor.
     */
    override fun applyMemoCommand(
        command: StoreMemoCommand,
        onPublication: (StoreMemoCommandPublication) -> Unit,
    ): StoreMemoCommit {
        val commit =
            delegate.applyMemoCommand(command) { publication ->
                observer.observeCommit(publication.commit)
                onPublication(publication)
            }
        observer.observeCommit(commit)
        return commit
    }

    override fun permanentDeleteMany(operationId: String, targets: List<StoreMemoDeleteTarget>): StoreMemoBatchCommit {
        val commit = delegate.permanentDeleteMany(operationId, targets)
        observer.observeBatch(commit)
        return commit
    }

    override fun emptyTrash(operationId: String, isCancelled: () -> Boolean): StoreEmptyTrashResult {
        val result = delegate.emptyTrash(operationId, isCancelled)
        result.batches.forEach(observer::observeBatch)
        return result
    }

    override fun startRebuild(batchSize: Int): StoreRebuildResult {
        val result = delegate.startRebuild(batchSize)
        observer.observeRebuild(result)
        return result
    }
}
