package com.lomo.data.engine.store

import com.lomo.data.repository.StoreProjectionObserver
import com.lomo.domain.model.MemoDocumentMutation

/**
 * Store write adapter that observes owner stamps onto the single projection publication bus.
 *
 * Callers still receive the command result for reminder/media side effects; they must not publish.
 */
internal class PublishingStorePort(
    private val delegate: StorePort,
    private val observer: StoreProjectionObserver,
) : StorePort by delegate {
    override fun applyMemoCommand(
        command: StoreMemoCommand,
        onPublication: (StoreMemoCommit) -> Unit,
    ): StoreMemoCommit {
        var midFlight = false
        val commit =
            delegate.applyMemoCommand(command) { pending ->
                midFlight = true
                observer.observeCommit(pending)
                onPublication(pending)
            }
        observer.observeCommit(commit, midFlightAlreadyPublished = midFlight)
        return commit
    }

    override fun permanentDeleteMany(
        operationId: String,
        targets: List<StoreMemoDeleteTarget>,
    ): StoreMemoBatchCommit {
        val commit = delegate.permanentDeleteMany(operationId, targets)
        observer.observeBatch(commit)
        return commit
    }

    override fun commitDocumentMutation(mutation: MemoDocumentMutation): StoreMemoCommit {
        val commit = delegate.commitDocumentMutation(mutation)
        observer.observeCommit(commit)
        return commit
    }

    override fun startRebuild(batchSize: Int): StoreRebuildResult {
        val result = delegate.startRebuild(batchSize)
        observer.observeRebuild(result)
        return result
    }
}
