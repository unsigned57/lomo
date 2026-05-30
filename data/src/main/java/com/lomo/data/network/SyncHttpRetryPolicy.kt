package com.lomo.data.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant

internal class SyncHttpRetryPolicy(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseBackoffMillis: Long = DEFAULT_BASE_BACKOFF_MILLIS,
    private val maxRetryAfterMillis: Long = DEFAULT_MAX_RETRY_AFTER_MILLIS,
    private val now: () -> Instant = { Instant.now() },
) {
    fun nextRetry(
        method: String,
        statusCode: Int,
        attemptNumber: Int,
        retryAfterHeader: String?,
    ): SyncHttpRetryDecision {
        if (method !in RETRYABLE_METHODS || !statusCode.isRetryableStatus()) {
            return SyncHttpRetryDecision.DoNotRetry
        }
        if (attemptNumber >= maxAttempts) {
            return SyncHttpRetryDecision.RetryExhausted
        }
        return SyncHttpRetryDecision.RetryAfter(
            delayMillis = retryDelayMillis(attemptNumber = attemptNumber, retryAfterHeader = retryAfterHeader),
        )
    }

    fun nextFailureRetry(
        method: String,
        attemptNumber: Int,
    ): SyncHttpRetryDecision {
        if (method !in RETRYABLE_METHODS || attemptNumber >= maxAttempts) {
            return SyncHttpRetryDecision.DoNotRetry
        }
        return SyncHttpRetryDecision.RetryAfter(delayMillis = exponentialBackoffMillis(attemptNumber))
    }

    private fun retryDelayMillis(
        attemptNumber: Int,
        retryAfterHeader: String?,
    ): Long = retryAfterMillis(retryAfterHeader) ?: exponentialBackoffMillis(attemptNumber)

    private fun exponentialBackoffMillis(attemptNumber: Int): Long =
        baseBackoffMillis shl (attemptNumber - FIRST_ATTEMPT_NUMBER)

    private fun retryAfterMillis(header: String?): Long? {
        val trimmed = header?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        return retryAfterSecondsMillis(trimmed) ?: retryAfterDateMillis(trimmed)
    }

    private fun retryAfterSecondsMillis(header: String): Long? {
        if (!header.all { it in ASCII_DIGITS }) {
            return null
        }
        return header
            .toLongOrNull()
            ?.secondsToMillis()
            ?.coerceAtMost(maxRetryAfterMillis)
            ?: maxRetryAfterMillis
    }

    private fun retryAfterDateMillis(header: String): Long? =
        try {
            Duration
                .between(now(), Instant.from(RFC_1123_DATE_TIME.parse(header)))
                .toMillis()
                .coerceAtLeast(0L)
                .coerceAtMost(maxRetryAfterMillis)
        } catch (_: DateTimeException) {
            null
        }
}

internal sealed interface SyncHttpRetryDecision {
    data object DoNotRetry : SyncHttpRetryDecision

    data object RetryExhausted : SyncHttpRetryDecision

    data class RetryAfter(
        val delayMillis: Long,
    ) : SyncHttpRetryDecision
}

internal fun interface RetrySleeper {
    @Throws(InterruptedException::class)
    fun sleep(delayMillis: Long)
}

internal object ThreadRetrySleeper : RetrySleeper {
    override fun sleep(delayMillis: Long) {
        Thread.sleep(delayMillis)
    }
}

internal class SyncHttpRetryWaiter(
    private val sleeper: RetrySleeper = ThreadRetrySleeper,
) {
    @Throws(IOException::class)
    fun waitBeforeRetry(delayMillis: Long) {
        try {
            sleeper.sleep(delayMillis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Retry interrupted", interrupted)
        }
    }
}

internal class SyncHttpRetryInterceptor(
    private val policy: SyncHttpRetryPolicy = SyncHttpRetryPolicy(),
    private val waiter: SyncHttpRetryWaiter = SyncHttpRetryWaiter(),
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attemptNumber = FIRST_ATTEMPT_NUMBER
        retryLoop@ while (true) {
            val response =
                try {
                    chain.proceed(request)
                } catch (failure: IOException) {
                    val failureRetry = failureRetryAfterOrThrow(
                        chain = chain,
                        method = request.method,
                        attemptNumber = attemptNumber,
                        failure = failure,
                    )
                    waiter.waitBeforeRetry(failureRetry.delayMillis)
                    attemptNumber += 1
                    continue@retryLoop
                }
            val decision = policy.nextRetry(
                method = request.method,
                statusCode = response.code,
                attemptNumber = attemptNumber,
                retryAfterHeader = response.header(RETRY_AFTER_HEADER),
            )
            when (decision) {
                SyncHttpRetryDecision.DoNotRetry -> return response
                SyncHttpRetryDecision.RetryExhausted -> return response
                is SyncHttpRetryDecision.RetryAfter -> {
                    response.close()
                    waiter.waitBeforeRetry(decision.delayMillis)
                    attemptNumber += 1
                }
            }
        }
    }

    private fun failureRetryAfterOrThrow(
        chain: Interceptor.Chain,
        method: String,
        attemptNumber: Int,
        failure: IOException,
    ): SyncHttpRetryDecision.RetryAfter {
        if (chain.call().isCanceled()) {
            throw failure
        }
        return when (
            val failureDecision = policy.nextFailureRetry(
                method = method,
                attemptNumber = attemptNumber,
            )
        ) {
            SyncHttpRetryDecision.DoNotRetry,
            SyncHttpRetryDecision.RetryExhausted,
            -> throw failure
            is SyncHttpRetryDecision.RetryAfter -> failureDecision
        }
    }
}

private fun Int.isRetryableStatus(): Boolean = this == HTTP_TOO_MANY_REQUESTS || this in HTTP_SERVER_ERROR_RANGE

private fun Long.secondsToMillis(): Long {
    val maxSecondsWithoutOverflow = Long.MAX_VALUE / MILLIS_PER_SECOND
    return coerceAtMost(maxSecondsWithoutOverflow) * MILLIS_PER_SECOND
}

private const val DEFAULT_MAX_ATTEMPTS = 3
private const val DEFAULT_BASE_BACKOFF_MILLIS = 200L
private const val DEFAULT_MAX_RETRY_AFTER_MILLIS = 5_000L
private const val FIRST_ATTEMPT_NUMBER = 1
private const val MILLIS_PER_SECOND = 1_000L
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_START = 500
private const val HTTP_SERVER_ERROR_END = 599
private const val RETRY_AFTER_HEADER = "Retry-After"
private val ASCII_DIGITS = '0'..'9'
private val HTTP_SERVER_ERROR_RANGE = HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END
private val RETRYABLE_METHODS = setOf("GET", "HEAD", "PROPFIND", "OPTIONS", "REPORT")
private val RFC_1123_DATE_TIME = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
