package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.AppUpdateFetchException
import com.lomo.domain.model.AppUpdateFetchFailure
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.IOException

/*
 * Behavior Contract:
 * - Unit under test: AppUpdateRepositoryImpl.fetchLatestRelease
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: a manual update check distinguishes success, HTTP rejection, transport failure
 *   and malformed payload as typed failures — never a silent null. The release check shares the
 *   single Ktor transport stack owned by AppUpdateTransportOwner (via AppUpdateHttpClientProvider).
 *
 * Scenarios:
 * - Given an HTTP error code, when the release is fetched, then an Http failure carries the
 *   status code.
 * - Given a transport IOException, when the release is fetched, then a Network failure is
 *   surfaced.
 * - Given an unparseable payload, when the release is fetched, then a MalformedResponse failure
 *   is surfaced.
 * - Given a well-formed payload, when the release is fetched, then the parsed release is
 *   returned with the app User-Agent sent.
 *
 * Observable outcomes: AppUpdateFetchException variants or parsed LatestAppRelease.
 *
 * TDD proof:
 * - Fails before the fix because the repository still opens java.net.HttpURLConnection through
 *   a connector lambda and cannot be driven by a Ktor MockEngine.
 * Excludes: real network, APK download, install verification, client lease lifecycle.
  * Test Change Justification:
 * - Reason category: transport implementation migration — java.net.HttpURLConnection replaced
 *   by the single Ktor client stack (AppUpdateHttpClientProvider).
 * - Old behavior/assertion being replaced: the stubbed URLConnection harness driving the
 *   connector lambda.
 * - Why old assertion is no longer correct: the repository no longer opens HttpURLConnection;
 *   responses are driven through a Ktor MockEngine.
 * - Coverage preserved by: the identical outcome matrix — HTTP error code, transport failure,
 *   malformed payload, success with User-Agent — asserted as the same typed failures.
 * - Why this is not fitting the test to the implementation: only the stubbing mechanism moved;
 *   the asserted failure taxonomy is unchanged.
*/
class AppUpdateRepositoryImplTest : DataFunSpec() {
    init {
        test("given http error when fetching release then http failure carries the status code") {
            runTest {
                val repository =
                    repositoryWith(
                        MockEngine {
                            respond(
                                content = ByteReadChannel("unavailable".encodeToByteArray()),
                                status = HttpStatusCode.ServiceUnavailable,
                            )
                        },
                    )

                val failure = fetchFailure(repository)

                failure.shouldBeInstanceOf<AppUpdateFetchFailure.Http>()
                failure.code shouldBe 503
            }
        }

        test("given io exception when fetching release then network failure is surfaced") {
            runTest {
                val repository =
                    repositoryWith(
                        MockEngine { throw IOException("airplane mode") },
                    )

                val failure = fetchFailure(repository)

                failure.shouldBeInstanceOf<AppUpdateFetchFailure.Network>()
                failure.diagnostic shouldBe "airplane mode"
            }
        }

        test("given malformed payload when fetching release then malformed failure is surfaced") {
            runTest {
                val repository =
                    repositoryWith(
                        MockEngine {
                            respond(
                                content = ByteReadChannel("{ not json".encodeToByteArray()),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    )

                val failure = fetchFailure(repository)

                failure.shouldBeInstanceOf<AppUpdateFetchFailure.MalformedResponse>()
            }
        }

        test("given well formed payload when fetching release then parsed release is returned") {
            runTest {
                var observedUserAgent: String? = null
                val repository =
                    repositoryWith(
                        MockEngine { request ->
                            observedUserAgent = request.headers[HttpHeaders.UserAgent]
                            respond(
                                content =
                                    ByteReadChannel(
                                        """{"tag_name":"v1.2.0","html_url":"https://example.com/r","body":"notes","assets":[]}"""
                                            .encodeToByteArray(),
                                    ),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    )

                val release = repository.fetchLatestRelease()

                release.tagName shouldBe "v1.2.0"
                observedUserAgent shouldBe AppUpdateHttpDownloader.USER_AGENT
            }
        }
    }
}

private suspend fun fetchFailure(repository: AppUpdateRepositoryImpl): AppUpdateFetchFailure =
    try {
        repository.fetchLatestRelease()
        error("expected AppUpdateFetchException")
    } catch (error: AppUpdateFetchException) {
        error.failure
    }

private fun repositoryWith(engine: MockEngine): AppUpdateRepositoryImpl {
    val client = HttpClient(engine)
    return AppUpdateRepositoryImpl(
        httpClientProvider =
            object : AppUpdateHttpClientProvider {
                override suspend fun <T> withHttpClient(block: suspend (HttpClient) -> T): T =
                    block(client)
            },
    )
}
