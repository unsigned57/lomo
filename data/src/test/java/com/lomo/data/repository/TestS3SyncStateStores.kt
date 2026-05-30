package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: test-source S3 sync-state collaborators.
 * - Owning layer: data test boundary for S3 sync-state orchestration.
 * - Priority tier: P0 support.
 * - Capability: provide explicit test-only disabled and in-memory collaborators after production fallback
 *   implementations are removed.
 *
 * Scenarios:
 * - Given a test needs disabled sync-state behavior, when it constructs an S3 sync component, then the
 *   disabled collaborator is supplied from test source explicitly.
 * - Given a test needs stateful sync metadata behavior, when it exercises S3 planning or conflict code,
 *   then the in-memory collaborator records and returns observable state.
 *
 * Observable outcomes:
 * - Production source cannot import these helpers, while test call sites compile only when they pass
 *   store dependencies explicitly.
 *
 * TDD proof:
 * - RED command: `./gradlew --no-daemon --no-configuration-cache --console=plain :data:compileDebugKotlin :data:compileDebugUnitTestKotlin`.
 * - RED symptom: test compilation failed at S3 constructors/helpers that still omitted generation-scoped
 *   store parameters after production defaults were removed.
 *
 * Excludes:
 * - Room DAO behavior, remote transport behavior, and production DI bindings.
 */

import java.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object DisabledS3SyncProtocolStateStore : S3SyncProtocolStateStore {
    override val incrementalSyncEnabled: Boolean = false

    override suspend fun read(): S3SyncProtocolState? = null

    override suspend fun write(state: S3SyncProtocolState) = Unit

    override suspend fun clear() = Unit
}

internal class InMemoryS3SyncProtocolStateStore(
    override val incrementalSyncEnabled: Boolean = true,
) : S3SyncProtocolStateStore {
    private val mutex = Mutex()
    private var state: S3SyncProtocolState? = null

    override suspend fun read(): S3SyncProtocolState? = mutex.withLock { state }

    override suspend fun write(state: S3SyncProtocolState) {
        mutex.withLock {
            this.state = state
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            state = null
        }
    }
}

internal object DisabledS3LocalChangeJournalStore : S3LocalChangeJournalStore {
    override val incrementalSyncEnabled: Boolean = false

    override suspend fun read(): Map<String, S3LocalChangeJournalEntry> = emptyMap()

    override suspend fun upsert(entry: S3LocalChangeJournalEntry) = Unit

    override suspend fun remove(ids: Collection<String>) = Unit

    override suspend fun clear() = Unit
}

internal class InMemoryS3LocalChangeJournalStore(
    override val incrementalSyncEnabled: Boolean = true,
) : S3LocalChangeJournalStore {
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, S3LocalChangeJournalEntry>()

    override suspend fun read(): Map<String, S3LocalChangeJournalEntry> =
        mutex.withLock { entries.toMap() }

    override suspend fun upsert(entry: S3LocalChangeJournalEntry) {
        mutex.withLock {
            entries[entry.id] = entry
        }
    }

    override suspend fun remove(ids: Collection<String>) {
        mutex.withLock {
            ids.forEach(entries::remove)
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            entries.clear()
        }
    }
}

internal object NoOpS3LocalChangeRecorder : S3LocalChangeRecorder {
    override suspend fun recordMemoUpsert(filename: String) = Unit

    override suspend fun recordMemoDelete(filename: String) = Unit

    override suspend fun recordImageUpsert(filename: String) = Unit

    override suspend fun recordImageDelete(filename: String) = Unit

    override suspend fun recordVoiceUpsert(filename: String) = Unit

    override suspend fun recordVoiceDelete(filename: String) = Unit
}

internal object NoOpS3SyncTransactionRunner : S3SyncTransactionRunner {
    override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
}

internal object DisabledS3RemoteIndexStore : S3RemoteIndexStore {
    override val remoteIndexEnabled: Boolean = false

    override suspend fun readAllRelativePaths(): List<String> = emptyList()

    override suspend fun readPresentCount(): Int = 0

    override suspend fun readByRelativePaths(relativePaths: Collection<String>): List<S3RemoteIndexEntry> = emptyList()

    override suspend fun readByRelativePrefix(relativePrefix: String?): List<S3RemoteIndexEntry> = emptyList()

    override suspend fun readOutsideScanBuckets(
        excludedBuckets: Collection<String>,
    ): List<S3RemoteIndexEntry> = emptyList()

    override suspend fun readReconcileCandidates(limit: Int): List<S3RemoteIndexEntry> = emptyList()

    override suspend fun upsert(entries: Collection<S3RemoteIndexEntry>) = Unit

    override suspend fun deleteByRelativePaths(relativePaths: Collection<String>) = Unit

    override suspend fun deleteOutsideScanEpoch(scanEpoch: Long) = Unit

    override suspend fun replaceAll(entries: Collection<S3RemoteIndexEntry>) = Unit

    override suspend fun clear() = Unit
}

internal class InMemoryS3RemoteIndexStore(
    override val remoteIndexEnabled: Boolean = true,
) : S3RemoteIndexStore {
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, S3RemoteIndexEntry>()

    override suspend fun readAllRelativePaths(): List<String> = mutex.withLock { entries.keys.toList() }

    override suspend fun readPresentCount(): Int =
        mutex.withLock { entries.values.count { entry -> !entry.missingOnLastScan } }

    override suspend fun readByRelativePaths(relativePaths: Collection<String>): List<S3RemoteIndexEntry> =
        mutex.withLock { relativePaths.mapNotNull(entries::get) }

    override suspend fun readByRelativePrefix(relativePrefix: String?): List<S3RemoteIndexEntry> =
        mutex.withLock {
            val normalizedPrefix = relativePrefix?.trim()?.trim('/')?.takeIf(String::isNotBlank)
            entries.values.filter { entry ->
                normalizedPrefix == null ||
                    entry.relativePath == normalizedPrefix ||
                    entry.relativePath.startsWith("$normalizedPrefix/")
            }
        }

    override suspend fun readOutsideScanBuckets(excludedBuckets: Collection<String>): List<S3RemoteIndexEntry> =
        mutex.withLock {
            val excluded = excludedBuckets.toSet()
            entries.values.filterNot { entry -> entry.scanBucket in excluded }
        }

    override suspend fun readReconcileCandidates(limit: Int): List<S3RemoteIndexEntry> =
        mutex.withLock {
            entries.values
                .sortedWith(
                    compareByDescending<S3RemoteIndexEntry> { it.dirtySuspect }
                        .thenByDescending { it.missingOnLastScan }
                        .thenByDescending { it.scanPriority }
                        .thenBy { it.lastVerifiedAt ?: 0L }
                        .thenBy { it.lastSeenAt },
                ).take(limit)
        }

    override suspend fun upsert(entries: Collection<S3RemoteIndexEntry>) {
        if (entries.isEmpty()) return
        mutex.withLock {
            entries.forEach { entry -> this.entries[entry.relativePath] = entry }
        }
    }

    override suspend fun deleteByRelativePaths(relativePaths: Collection<String>) {
        if (relativePaths.isEmpty()) return
        mutex.withLock {
            relativePaths.forEach(entries::remove)
        }
    }

    override suspend fun deleteOutsideScanEpoch(scanEpoch: Long) {
        mutex.withLock {
            entries.entries.removeIf { (_, value) -> value.scanEpoch != scanEpoch }
        }
    }

    override suspend fun replaceAll(entries: Collection<S3RemoteIndexEntry>) {
        mutex.withLock {
            this.entries.clear()
            entries.forEach { entry -> this.entries[entry.relativePath] = entry }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            entries.clear()
        }
    }
}

internal object DisabledS3RemoteShardStateStore : S3RemoteShardStateStore {
    override val remoteShardStateEnabled: Boolean = false

    override suspend fun readAll(): List<S3RemoteShardState> = emptyList()

    override suspend fun readByBucketId(bucketId: String): S3RemoteShardState? = null

    override suspend fun readByBucketIds(bucketIds: Collection<String>): List<S3RemoteShardState> = emptyList()

    override suspend fun readMostSpecificAncestor(relativePrefix: String?): S3RemoteShardState? = null

    override suspend fun readScheduleTelemetry(
        now: Long,
        reconcileInterval: Duration,
        endpointProfile: S3EndpointProfile,
    ): S3RemoteShardScheduleTelemetry =
        S3RemoteShardScheduleTelemetry(
            shardCount = 0,
            oldestScanAt = null,
            hasElevatedChangePressure = false,
            hasHighVerificationUncertainty = false,
        )

    override suspend fun upsert(states: Collection<S3RemoteShardState>) = Unit

    override suspend fun clear() = Unit
}

internal class InMemoryS3RemoteShardStateStore(
    override val remoteShardStateEnabled: Boolean = true,
) : S3RemoteShardStateStore {
    private val mutex = Mutex()
    private val states = linkedMapOf<String, S3RemoteShardState>()

    override suspend fun readAll(): List<S3RemoteShardState> = mutex.withLock { states.values.toList() }

    override suspend fun readByBucketId(bucketId: String): S3RemoteShardState? = mutex.withLock { states[bucketId] }

    override suspend fun readByBucketIds(bucketIds: Collection<String>): List<S3RemoteShardState> =
        mutex.withLock { bucketIds.mapNotNull(states::get) }

    override suspend fun readMostSpecificAncestor(relativePrefix: String?): S3RemoteShardState? =
        mutex.withLock {
            val normalizedPrefix = relativePrefix?.trim()?.trim('/')?.takeIf(String::isNotBlank) ?: return@withLock null
            states.values
                .filter { state ->
                    val candidatePrefix = state.relativePrefix?.trim()?.trim('/')
                    candidatePrefix != null &&
                        (normalizedPrefix == candidatePrefix || normalizedPrefix.startsWith("$candidatePrefix/"))
                }.maxByOrNull { state ->
                    state.relativePrefix?.length ?: 0
                }
        }

    override suspend fun readScheduleTelemetry(
        now: Long,
        reconcileInterval: Duration,
        endpointProfile: S3EndpointProfile,
    ): S3RemoteShardScheduleTelemetry =
        mutex.withLock { states.values.toList().toScheduleTelemetry(now, reconcileInterval, endpointProfile) }

    override suspend fun upsert(states: Collection<S3RemoteShardState>) {
        if (states.isEmpty()) return
        mutex.withLock {
            states.forEach { state -> this.states[state.bucketId] = state }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            states.clear()
        }
    }
}

private fun List<S3RemoteShardState>.toScheduleTelemetry(
    now: Long,
    reconcileInterval: Duration,
    endpointProfile: S3EndpointProfile,
): S3RemoteShardScheduleTelemetry {
    val recentChangeWindowMillis = reconcileInterval.toMillis() / S3_RECENT_CHANGE_WINDOW_DIVISOR
    val uncertaintyWindowMillis = reconcileInterval.toMillis()
    return S3RemoteShardScheduleTelemetry(
        shardCount = size,
        oldestScanAt = minOfOrNull(S3RemoteShardState::lastScannedAt),
        hasElevatedChangePressure =
            any { state ->
                state.idleScanStreak == 0 &&
                    state.scanAgeMillis(now) <= recentChangeWindowMillis &&
                    state.changeRate() >= endpointProfile.changePressureThreshold
            },
        hasHighVerificationUncertainty =
            any { state ->
                state.scanAgeMillis(now) <= uncertaintyWindowMillis &&
                    state.lastVerificationAttemptCount >= endpointProfile.minUncertaintyAttempts &&
                    state.lastVerificationFailureCount >= endpointProfile.minUncertaintyFailures &&
                    state.verificationFailureRate() >= endpointProfile.verificationFailureThreshold
            },
    )
}

private fun S3RemoteShardState.scanAgeMillis(now: Long): Long = (now - lastScannedAt).coerceAtLeast(0L)

private fun S3RemoteShardState.changeRate(): Double =
    lastChangeCount.toDouble() / lastObjectCount.coerceAtLeast(1).toDouble()

private fun S3RemoteShardState.verificationFailureRate(): Double =
    lastVerificationFailureCount.toDouble() / lastVerificationAttemptCount.coerceAtLeast(1).toDouble()
