package com.lomo.data.local

import android.content.Context
import androidx.room3.useReaderConnection
import com.lomo.data.util.throwIfFatal
import com.lomo.domain.repository.DatabaseInitializationRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseInitializer internal constructor(
    private val databasePath: String,
    private val ioDispatcher: CoroutineDispatcher,
    private val performOpen: suspend () -> Unit,
) : DatabaseInitializationRepository {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        databaseProvider: Lazy<MemoDatabase>,
    ) : this(
        databasePath = context.getDatabasePath(DatabaseTransitionStrategy.DATABASE_NAME).path,
        ioDispatcher = Dispatchers.IO,
        performOpen = {
            val database = databaseProvider.get()
            try {
                probeMemoDatabaseReadiness(database)
            } catch (error: Exception) {
                try {
                    database.close()
                } catch (_: Exception) {
                }
                throw error
            }
        },
    )

    sealed interface DbReadiness {
        data object Uninitialized : DbReadiness

        data object Opening : DbReadiness

        data object Ready : DbReadiness

        data class Failure(
            val error: IllegalStateException,
        ) : DbReadiness
    }

    private val openMutex = Mutex()
    private var cachedOpenResult: Result<Unit>? = null
    private val _readyFlow = MutableStateFlow<DbReadiness>(DbReadiness.Uninitialized)
    val readyFlow: StateFlow<DbReadiness> = _readyFlow.asStateFlow()

    override suspend fun ensureReady() {
        ensureOpen()
    }

    suspend fun ensureOpen() {
        cachedOpenResult?.getOrThrow()
        val result =
            openMutex.withLock {
                cachedOpenResult?.let { return@withLock it }
                _readyFlow.value = DbReadiness.Opening
                val openResult =
                    try {
                        withContext(ioDispatcher) {
                            performOpen()
                        }
                        Result.success(Unit)
                    } catch (error: CancellationException) {
                        _readyFlow.value = DbReadiness.Uninitialized
                        throw error
                    } catch (error: Exception) {
                        throwIfFatal(error)
                        Result.failure<Unit>(preservedDatabaseFailure(error))
                    }
                cachedOpenResult = openResult
                _readyFlow.value =
                    openResult.fold(
                        onSuccess = { DbReadiness.Ready },
                        onFailure = { DbReadiness.Failure(it as IllegalStateException) },
                    )
                openResult
            }
        result.getOrThrow()
    }

    private fun preservedDatabaseFailure(error: Throwable): IllegalStateException =
        IllegalStateException(
            "Database open/migration failed; existing database preserved at $databasePath",
            error,
        )
}

private suspend fun probeMemoDatabaseReadiness(database: MemoDatabase) {
    database.useReaderConnection { connection ->
        connection.usePrepared("SELECT 1") { statement ->
            statement.step()
        }
    }
}
