package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.AppUpdateFetchException
import com.lomo.domain.model.AppUpdateFetchFailure
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * Behavior Contract:
 * - Unit under test: AppUpdateRepositoryImpl.fetchLatestRelease
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: a manual update check distinguishes success, HTTP rejection, transport failure
 *   and malformed payload as typed failures — never a silent null.
 *
 * Scenarios:
 * - Given an HTTP error code, when the release is fetched, then an Http failure carries the
 *   status code.
 * - Given a transport IOException, when the release is fetched, then a Network failure is
 *   surfaced.
 * - Given an unparseable payload, when the release is fetched, then a MalformedResponse failure
 *   is surfaced.
 * - Given a well-formed payload, when the release is fetched, then the parsed release is
 *   returned.
 *
 * Observable outcomes: AppUpdateFetchException variants or parsed LatestAppRelease.
 *
 * TDD proof:
 * - Fails before the fix because the contract surface under test did not exist.
 * Excludes: real network, APK download, install verification.
 */
class AppUpdateRepositoryImplTest : DataFunSpec() {
    init {
        test("given http error when fetching release then http failure carries the status code") {
            runTest {
                val connection = stubbedConnection(statusCode = 503)

                val failure = fetchFailure { connection }

                failure.shouldBeInstanceOf<AppUpdateFetchFailure.Http>()
                failure.code shouldBe 503
            }
        }

        test("given io exception when fetching release then network failure is surfaced") {
            runTest {
                val failure = fetchFailure { throw IOException("airplane mode") }

                failure.shouldBeInstanceOf<AppUpdateFetchFailure.Network>()
                failure.diagnostic shouldBe "airplane mode"
            }
        }

        test("given malformed payload when fetching release then malformed failure is surfaced") {
            runTest {
                val connection = stubbedConnection(body = "{ not json")

                val failure = fetchFailure { connection }

                failure.shouldBeInstanceOf<AppUpdateFetchFailure.MalformedResponse>()
            }
        }

        test("given well formed payload when fetching release then parsed release is returned") {
            runTest {
                val connection =
                    stubbedConnection(
                        body =
                            """{"tag_name":"v1.2.0","html_url":"https://example.com/r","body":"notes","assets":[]}""",
                    )
                val repository = AppUpdateRepositoryImpl(connector = { connection })

                val release = repository.fetchLatestRelease()

                release.tagName shouldBe "v1.2.0"
            }
        }
    }
}

private suspend fun fetchFailure(connector: (String) -> HttpURLConnection): AppUpdateFetchFailure {
    val repository = AppUpdateRepositoryImpl(connector = connector)
    return try {
        repository.fetchLatestRelease()
        error("expected AppUpdateFetchException")
    } catch (error: AppUpdateFetchException) {
        error.failure
    }
}

private fun stubbedConnection(
    statusCode: Int = HttpURLConnection.HTTP_OK,
    body: String = "",
): HttpURLConnection = FakeHttpURLConnection(statusCode, body)

private class FakeHttpURLConnection(
    private val statusCode: Int,
    body: String,
) : HttpURLConnection(URL("https://example.com/releases")) {
    private val bodyBytes = body.toByteArray()

    override fun connect() = Unit

    override fun disconnect() = Unit

    override fun usingProxy() = false

    override fun getResponseCode() = statusCode

    override fun getInputStream() = ByteArrayInputStream(bodyBytes)
}
