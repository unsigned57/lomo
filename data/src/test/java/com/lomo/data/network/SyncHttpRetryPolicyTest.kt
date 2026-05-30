/*
 * Behavior Contract:
 * - Unit under test: SyncHttpRetryPolicy
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: decide sync HTTP retry eligibility, attempt budget, delay, Retry-After precedence,
 *   and blocking retry interruption semantics for shared WebDAV/S3 transport clients.
 *
 * Scenarios:
 * - Given a non-retryable HTTP method, when a retryable status is observed, then no retry is scheduled.
 * - Given default retryable sync methods and 429/5xx status, when attempts remain, then retry is scheduled,
 *   including WebDAV REPORT.
 * - Given a retryable sync method and non-retryable status, when attempts remain, then no retry is scheduled.
 * - Given Retry-After seconds, when it is valid and bounded, then it overrides exponential backoff delay.
 * - Given Retry-After seconds above the retry budget, when delay is computed, then it is capped.
 * - Given Retry-After HTTP-date, when it is in the future, past, or above the cap, then delay is parsed
 *   from the fixed clock, floored at zero, or capped before exponential backoff is considered.
 * - Given a retryable method hits a transport IOException, when attempts remain, then the request is retried
 *   within the same attempt budget.
 * - Given a retryable method hits a transport IOException after OkHttp cancellation, when the interceptor catches it,
 *   then the original cancellation failure is propagated without retry or backoff.
 * - Given a retryable method keeps hitting transport IOExceptions until the retry budget is exhausted, when the
 *   interceptor stops, then the last IOException is propagated without fabricating an HTTP response.
 * - Given retryable status repeats until the attempt budget is exhausted, when the interceptor receives the
 *   final retryable response, then earlier retryable response bodies are closed and the final HTTP response is
 *   returned with status/body/header taxonomy intact.
 * - Given the blocking retry delay is interrupted, when the interceptor waits, then the interrupt flag is restored
 *   and IOException is thrown.
 *
 * Observable outcomes:
 * - Retry decisions by HTTP/WebDAV method, next delay values, attempt budget exhaustion, captured sleep durations,
 *   restored thread interruption flag, retryable response body close state, returned HTTP status/header taxonomy,
 *   OkHttp call cancellation state, and thrown IOException type/message/cause for transport failures.
 *
 * TDD proof:
 * - RED command: `./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.network.SyncHttpRetryPolicyTest'`.
 * - RED symptom: canceled transport IOException is retried, so the test observes one sleep and a second
 *   `chain.proceed` call instead of immediate propagation.
 *
 * Excludes:
 * - OkHttp connection pooling, authentication headers, provider-specific dispatcher sizing, and upper sync retry
 *   orchestration.
 */
package com.lomo.data.network

import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class SyncHttpRetryPolicyTest : DataFunSpec() {
    init {
        test("given non retryable method when retryable status is observed then retry is not scheduled") {
            val policy = SyncHttpRetryPolicy()

            val decision = policy.nextRetry(
                method = "POST",
                statusCode = 503,
                attemptNumber = 1,
                retryAfterHeader = null,
            )

            decision shouldBe SyncHttpRetryDecision.DoNotRetry
        }

        test("given retryable method and 429 or server error when attempts remain then retry is scheduled") {
            val policy = SyncHttpRetryPolicy()

            val tooManyRequests = policy.nextRetry(
                method = "GET",
                statusCode = 429,
                attemptNumber = 1,
                retryAfterHeader = null,
            )
            val serverError = policy.nextRetry(
                method = "PROPFIND",
                statusCode = 500,
                attemptNumber = 2,
                retryAfterHeader = null,
            )

            assertSoftly {
                tooManyRequests shouldBe SyncHttpRetryDecision.RetryAfter(delayMillis = 200L)
                serverError shouldBe SyncHttpRetryDecision.RetryAfter(delayMillis = 400L)
            }
        }

        test("given default retryable sync methods when too many requests is observed then each method schedules retry") {
            val policy = SyncHttpRetryPolicy()

            val decisions =
                listOf("GET", "HEAD", "PROPFIND", "OPTIONS", "REPORT")
                    .map { method ->
                        method to
                            policy.nextRetry(
                                method = method,
                                statusCode = 429,
                                attemptNumber = 1,
                                retryAfterHeader = null,
                            )
                    }

            decisions shouldContainExactly
                listOf(
                    "GET" to SyncHttpRetryDecision.RetryAfter(delayMillis = 200L),
                    "HEAD" to SyncHttpRetryDecision.RetryAfter(delayMillis = 200L),
                    "PROPFIND" to SyncHttpRetryDecision.RetryAfter(delayMillis = 200L),
                    "OPTIONS" to SyncHttpRetryDecision.RetryAfter(delayMillis = 200L),
                    "REPORT" to SyncHttpRetryDecision.RetryAfter(delayMillis = 200L),
                )
        }

        test("given retryable method and non retry status when attempts remain then retry is not scheduled") {
            val policy = SyncHttpRetryPolicy()

            val decision = policy.nextRetry(
                method = "REPORT",
                statusCode = 404,
                attemptNumber = 1,
                retryAfterHeader = null,
            )

            decision shouldBe SyncHttpRetryDecision.DoNotRetry
        }

        test("given retryable status when attempt budget is exhausted then retry is not scheduled") {
            val policy = SyncHttpRetryPolicy()

            val decision = policy.nextRetry(
                method = "HEAD",
                statusCode = 503,
                attemptNumber = 3,
                retryAfterHeader = null,
            )

            decision shouldBe SyncHttpRetryDecision.RetryExhausted
        }

        test("given retry after seconds when delay is computed then header value overrides backoff") {
            val policy = SyncHttpRetryPolicy()

            val decision = policy.nextRetry(
                method = "OPTIONS",
                statusCode = 429,
                attemptNumber = 2,
                retryAfterHeader = "1",
            )

            decision shouldBe SyncHttpRetryDecision.RetryAfter(delayMillis = 1_000L)
        }

        test("given retry after seconds above budget when delay is computed then delay is capped") {
            val policy = SyncHttpRetryPolicy()

            val decision = policy.nextRetry(
                method = "GET",
                statusCode = 503,
                attemptNumber = 1,
                retryAfterHeader = "120",
            )

            decision shouldBe SyncHttpRetryDecision.RetryAfter(delayMillis = 5_000L)
        }

        test("given retry after http date when delay is computed then fixed now determines delay") {
            val policy = SyncHttpRetryPolicy(now = { Instant.parse("2026-05-23T00:00:00Z") })

            val decision = policy.nextRetry(
                method = "GET",
                statusCode = 503,
                attemptNumber = 1,
                retryAfterHeader = "Sat, 23 May 2026 00:00:03 GMT",
            )

            decision shouldBe SyncHttpRetryDecision.RetryAfter(delayMillis = 3_000L)
        }

        test("given retry after http date in the past when delay is computed then delay is floored at zero") {
            val policy = SyncHttpRetryPolicy(now = { Instant.parse("2026-05-23T00:00:00Z") })

            val decision = policy.nextRetry(
                method = "GET",
                statusCode = 503,
                attemptNumber = 1,
                retryAfterHeader = "Fri, 22 May 2026 23:59:59 GMT",
            )

            decision shouldBe SyncHttpRetryDecision.RetryAfter(delayMillis = 0L)
        }

        test("given retry after http date above budget when delay is computed then delay is capped") {
            val policy = SyncHttpRetryPolicy(now = { Instant.parse("2026-05-23T00:00:00Z") })

            val decision = policy.nextRetry(
                method = "GET",
                statusCode = 503,
                attemptNumber = 1,
                retryAfterHeader = "Sat, 23 May 2026 00:01:00 GMT",
            )

            decision shouldBe SyncHttpRetryDecision.RetryAfter(delayMillis = 5_000L)
        }

        test("given sleeper is interrupted when retry waits then interrupt flag is restored and IOException is thrown") {
            val sleeper = FakeRetrySleeper(interrupted = true)
            val waiter = SyncHttpRetryWaiter(sleeper)
            Thread.interrupted()

            val failure = shouldThrow<IOException> {
                waiter.waitBeforeRetry(250L)
            }

            assertSoftly {
                failure.message shouldBe "Retry interrupted"
                Thread.currentThread().isInterrupted shouldBe true
                sleeper.delays shouldContainExactly listOf(250L)
            }
            Thread.interrupted()
        }

        test("given interceptor retry delay is interrupted when retry waits then IOException is thrown") {
            val request = request(method = "GET")
            val sleeper = FakeRetrySleeper(interrupted = true)
            val chain = FakeRetryChain(
                request = request,
                outcomes = listOf(FakeRetryOutcome.ResponseResult(response(request = request, code = 503))),
            )
            val interceptor = SyncHttpRetryInterceptor(
                policy = SyncHttpRetryPolicy(),
                waiter = SyncHttpRetryWaiter(sleeper),
            )
            Thread.interrupted()

            val failure = shouldThrow<IOException> {
                interceptor.intercept(chain)
            }

            assertSoftly {
                failure.message shouldBe "Retry interrupted"
                Thread.currentThread().isInterrupted shouldBe true
                chain.proceedCount shouldBe 1
                sleeper.delays shouldContainExactly listOf(200L)
            }
            Thread.interrupted()
        }

        test("given retryable method and transport failure when attempts remain then request is retried") {
            val request = request(method = "GET")
            val sleeper = FakeRetrySleeper(interrupted = false)
            val chain = FakeRetryChain(
                request = request,
                outcomes = listOf(
                    FakeRetryOutcome.Failure(IOException("socket reset")),
                    FakeRetryOutcome.ResponseResult(response(request = request, code = 200)),
                ),
            )
            val interceptor = SyncHttpRetryInterceptor(
                policy = SyncHttpRetryPolicy(),
                waiter = SyncHttpRetryWaiter(sleeper),
            )

            val finalResponse = interceptor.intercept(chain)

            assertSoftly {
                finalResponse.code shouldBe 200
                chain.proceedCount shouldBe 2
                sleeper.delays shouldContainExactly listOf(200L)
            }
        }

        test("given retryable method and canceled call IOException when intercepted then failure is not retried") {
            val request = request(method = "GET")
            val cancellation = IOException("Canceled")
            val sleeper = FakeRetrySleeper(interrupted = false)
            val chain = FakeRetryChain(
                request = request,
                outcomes = listOf(
                    FakeRetryOutcome.Failure(cancellation),
                    FakeRetryOutcome.ResponseResult(response(request = request, code = 200)),
                ),
                canceled = true,
            )
            val interceptor = SyncHttpRetryInterceptor(
                policy = SyncHttpRetryPolicy(),
                waiter = SyncHttpRetryWaiter(sleeper),
            )

            val failure = shouldThrow<IOException> {
                interceptor.intercept(chain)
            }

            assertSoftly {
                failure shouldBe cancellation
                chain.proceedCount shouldBe 1
                sleeper.delays shouldBe emptyList()
            }
        }

        test("given retryable method and repeated transport failures when attempts exhaust then last IOException is thrown") {
            val request = request(method = "PROPFIND")
            val firstFailure = IOException("connect reset")
            val secondFailure = IOException("route broken")
            val finalFailure = IOException("socket closed")
            val sleeper = FakeRetrySleeper(interrupted = false)
            val chain = FakeRetryChain(
                request = request,
                outcomes = listOf(
                    FakeRetryOutcome.Failure(firstFailure),
                    FakeRetryOutcome.Failure(secondFailure),
                    FakeRetryOutcome.Failure(finalFailure),
                ),
            )
            val interceptor = SyncHttpRetryInterceptor(
                policy = SyncHttpRetryPolicy(),
                waiter = SyncHttpRetryWaiter(sleeper),
            )

            val failure = shouldThrow<IOException> {
                interceptor.intercept(chain)
            }

            assertSoftly {
                failure shouldBe finalFailure
                chain.proceedCount shouldBe 3
                sleeper.delays shouldContainExactly listOf(200L, 400L)
            }
        }

        test("given retryable status repeats until budget is exhausted when intercepted then final response is returned") {
            val request = request(method = "GET")
            val sleeper = FakeRetrySleeper(interrupted = false)
            val retryableResponses =
                listOf(
                    closeRecordingResponse(request = request, code = 503),
                    closeRecordingResponse(request = request, code = 503),
                    closeRecordingResponse(request = request, code = 503, retryAfterHeader = "9"),
                )
            val chain = FakeRetryChain(
                request = request,
                outcomes = retryableResponses.map { FakeRetryOutcome.ResponseResult(it.response) },
            )
            val interceptor = SyncHttpRetryInterceptor(
                policy = SyncHttpRetryPolicy(),
                waiter = SyncHttpRetryWaiter(sleeper),
            )

            val finalResponse = interceptor.intercept(chain)

            assertSoftly {
                finalResponse.code shouldBe 503
                finalResponse.header("Retry-After") shouldBe "9"
                chain.proceedCount shouldBe 3
                sleeper.delays shouldContainExactly listOf(200L, 400L)
                retryableResponses.map { it.body.closed } shouldContainExactly listOf(true, true, false)
            }
        }
    }
}

private class FakeRetrySleeper(
    private val interrupted: Boolean,
) : RetrySleeper {
    private val mutableDelays = mutableListOf<Long>()
    val delays: List<Long> = mutableDelays

    override fun sleep(delayMillis: Long) {
        mutableDelays += delayMillis
        if (interrupted) {
            throw InterruptedException("stop")
        }
    }
}

private class FakeRetryChain(
    private val request: Request,
    private val outcomes: List<FakeRetryOutcome>,
    private val canceled: Boolean = false,
) : Interceptor.Chain {
    var proceedCount: Int = 0
        private set

    override fun request(): Request = request

    override fun proceed(request: Request): Response =
        when (val outcome = outcomes[proceedCount++]) {
            is FakeRetryOutcome.Failure -> throw outcome.failure
            is FakeRetryOutcome.ResponseResult -> outcome.response
        }

    override fun connection(): Connection? = null

    override fun call(): Call = FakeCall(request = request, canceled = canceled)

    override fun connectTimeoutMillis(): Int = 0

    override fun withConnectTimeout(
        timeout: Int,
        unit: TimeUnit,
    ): Interceptor.Chain = this

    override fun readTimeoutMillis(): Int = 0

    override fun withReadTimeout(
        timeout: Int,
        unit: TimeUnit,
    ): Interceptor.Chain = this

    override fun writeTimeoutMillis(): Int = 0

    override fun withWriteTimeout(
        timeout: Int,
        unit: TimeUnit,
    ): Interceptor.Chain = this
}

private class FakeCall(
    private val request: Request,
    private val canceled: Boolean,
) : Call {
    override fun request(): Request = request

    override fun execute(): Response = error("Call execution is handled by the fake interceptor chain")

    override fun enqueue(responseCallback: Callback) {
        error("Async call execution is not used by retry interceptor tests")
    }

    override fun cancel() = Unit

    override fun isExecuted(): Boolean = false

    override fun isCanceled(): Boolean = canceled

    override fun clone(): Call = FakeCall(request = request, canceled = canceled)

    override fun timeout(): Timeout = Timeout.NONE

    override fun <T : Any> tag(type: KClass<T>): T? = null

    override fun <T> tag(type: Class<out T>): T? = null

    override fun <T : Any> tag(
        type: KClass<T>,
        computeIfAbsent: () -> T,
    ): T = computeIfAbsent()

    override fun <T : Any> tag(
        type: Class<T>,
        computeIfAbsent: () -> T,
    ): T = computeIfAbsent()
}

private sealed interface FakeRetryOutcome {
    data class ResponseResult(
        val response: Response,
    ) : FakeRetryOutcome

    data class Failure(
        val failure: IOException,
    ) : FakeRetryOutcome
}

private data class CloseRecordingHttpResponse(
    val response: Response,
    val body: CloseRecordingResponseBody,
)

private class CloseRecordingResponseBody : ResponseBody() {
    var closed: Boolean = false
        private set

    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = 0L

    override fun source(): BufferedSource = Buffer()

    override fun close() {
        closed = true
        super.close()
    }
}

private fun request(method: String): Request =
    Request
        .Builder()
        .url("https://example.invalid/sync")
        .method(method, null)
        .build()

private fun closeRecordingResponse(
    request: Request,
    code: Int,
    retryAfterHeader: String? = null,
): CloseRecordingHttpResponse {
    val body = CloseRecordingResponseBody()
    val builder =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .body(body)
    if (retryAfterHeader != null) {
        builder.header("Retry-After", retryAfterHeader)
    }
    val response = builder.build()
    return CloseRecordingHttpResponse(response = response, body = body)
}

private fun response(
    request: Request,
    code: Int,
    retryAfterHeader: String? = null,
): Response {
    val builder =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .body("".toResponseBody(null))
    if (retryAfterHeader != null) {
        builder.header("Retry-After", retryAfterHeader)
    }
    return builder.build()
}
