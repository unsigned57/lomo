package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.repository.AppUpdateTransportLifecycleRepository
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: AppUpdateTransportOwner
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: app update HTTP transport has an explicit app-graph lifecycle owner.
 *
 * Scenarios:
 * - Given an app-scoped update HttpClient, when the transport owner is closed, then the owned Ktor client is closed.
 * - Given the downloader is built from the owner, when DI graph teardown closes the owner, then the downloader cannot silently keep an unowned raw client alive.
 * - Given app code can only depend on domain contracts, when it closes the update transport lifecycle, then the same app-scoped owner closes the owned Ktor client.
 * - Given an already-created downloader and a closed owner, when a later download starts, then the owner supplies a rebuilt live client instead of the permanently closed raw client.
 * - Given a download is in progress, when the lifecycle close path runs, then the owner defers closing the active client until the download releases it.
 *
 * Observable outcomes:
 * - Ktor client coroutine context activity, downloaded file bytes, request paths served by rebuilt clients, and the domain lifecycle close path reaching the owner.
 *
 * TDD proof:
 * - Fails before the fix because the owner stores one raw HttpClient permanently, so a singleton downloader created before close cannot obtain a rebuilt live client for later downloads and close cannot defer while a client is active.
 *
 * Excludes:
 * - APK byte streaming, timeout policy, release metadata discovery, and package-installer state transitions.
 */
class AppUpdateTransportOwnerTest : DataFunSpec() {
    init {
        test("given app update transport owner when closed then owned ktor client is closed") {
            val httpClient =
                HttpClient(
                    MockEngine {
                        respond(
                            content = ByteReadChannel.Empty,
                            status = HttpStatusCode.OK,
                        )
                    },
                )
            val owner = AppUpdateTransportOwner(httpClient)

            owner.close()

            httpClient.coroutineContext.isActive.shouldBeFalse()
        }

        test("given existing downloader when owner closes then later download uses a rebuilt live client") {
            runTest {
                var nextClientId = 0
                val requestedPaths = mutableListOf<String>()
                val clients = mutableListOf<HttpClient>()
                val owner =
                    AppUpdateTransportOwner(
                        httpClientFactory = {
                            val clientId = nextClientId++
                            HttpClient(
                                MockEngine { request ->
                                    requestedPaths += "$clientId:${request.url.encodedPath}"
                                    respond(
                                        content = ByteReadChannel("apk-$clientId".encodeToByteArray()),
                                        status = HttpStatusCode.OK,
                                        headers = headersOf(HttpHeaders.ContentLength, "5"),
                                    )
                                },
                            ).also(clients::add)
                        },
                    )
                val downloader = owner.createDownloader()
                val firstOutputFile = Files.createTempFile("first-update-client", ".apk").toFile()
                val secondOutputFile = Files.createTempFile("rebuilt-update-client", ".apk").toFile()

                downloader.download(
                    downloadUrl = "https://updates.example/first.apk",
                    outputFile = firstOutputFile,
                    onProgress = {},
                )
                owner.closeUpdateTransport()
                downloader.download(
                    downloadUrl = "https://updates.example/second.apk",
                    outputFile = secondOutputFile,
                    onProgress = {},
                )

                clients.size shouldBe 2
                clients.first().coroutineContext.isActive.shouldBeFalse()
                clients.last().coroutineContext.isActive.shouldBeTrue()
                requestedPaths shouldBe listOf("0:/first.apk", "1:/second.apk")
                firstOutputFile.readText() shouldBe "apk-0"
                secondOutputFile.readText() shouldBe "apk-1"
            }
        }

        test("given active download when owner closes then client remains live until the download finishes") {
            runTest {
                val responseAllowed = CompletableDeferred<Unit>()
                val requestStarted = CompletableDeferred<Unit>()
                val httpClient =
                    HttpClient(
                        MockEngine {
                            requestStarted.complete(Unit)
                            responseAllowed.await()
                            respond(
                                content = ByteReadChannel("apk".encodeToByteArray()),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentLength, "3"),
                            )
                        },
                    )
                val owner = AppUpdateTransportOwner(httpClient)
                val downloader = owner.createDownloader()
                val outputFile = Files.createTempFile("active-update-client", ".apk").toFile()

                val downloadJob =
                    async {
                        downloader.download(
                            downloadUrl = "https://updates.example/active.apk",
                            outputFile = outputFile,
                            onProgress = {},
                        )
                    }
                requestStarted.await()

                owner.closeUpdateTransport()

                httpClient.coroutineContext.isActive.shouldBeTrue()
                responseAllowed.complete(Unit)
                downloadJob.await()
                httpClient.coroutineContext.isActive.shouldBeFalse()
                outputFile.readText() shouldBe "apk"
            }
        }

        test("given domain lifecycle contract when closed then it closes the same update transport owner") {
            val httpClient =
                HttpClient(
                    MockEngine {
                        respond(
                            content = ByteReadChannel.Empty,
                            status = HttpStatusCode.OK,
                        )
                    },
                )
            val owner: AppUpdateTransportLifecycleRepository = AppUpdateTransportOwner(httpClient)

            owner.closeUpdateTransport()

            httpClient.coroutineContext.isActive.shouldBeFalse()
        }
    }
}
