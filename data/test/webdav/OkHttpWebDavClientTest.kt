/*
 * Behavior Contract:
 * - Unit under test: OkHttpWebDavClient and OkHttpWebDavClientFactory
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: execute the WebDAV subset used by sync without packaging a JVM XML parser on Android.
 *
 * Scenarios:
 * - Given a DAV multistatus response, when a collection is listed, then successful child resources
 *   are decoded relative to the configured root with metadata and collection type preserved.
 * - Given a missing collection, when ensureDirectory runs, then it probes with PROPFIND before MKCOL.
 * - Given a conditional upload, when PUT runs, then WebDAV precondition headers are preserved.
 * - Given repeated credentials, when the factory creates clients, then cache reuse and explicit
 *   credential invalidation remain observable.
 *
 * Observable outcomes:
 * - Returned remote resources, emitted HTTP methods/headers, and factory object identity.
 *
 * TDD proof:
 * - Fails before the fix because OkHttpWebDavClient does not exist and the runtime still depends on dav4jvm/xpp3.
 *
 * Excludes:
 * - Real network transport, authentication interceptor behavior, and server-specific DAV extensions.
 */
package com.lomo.data.webdav

import com.lomo.data.network.SyncHttpClientProvider
import com.lomo.data.repository.DisabledSyncPerformanceTuner
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class OkHttpWebDavClientTest : DataFunSpec() {
    init {
        test("given DAV multistatus when collection is listed then child metadata is returned") {
            val transport =
                ScriptedWebDavTransport(
                    ScriptedResponse(
                        code = 207,
                        body = MULTISTATUS_RESPONSE,
                        contentType = "application/xml",
                    ),
                )
            val client = OkHttpWebDavClient("https://dav.example.com/root/", "alice", "secret", transport.client)

            val resources = client.list("")

            resources.shouldHaveSize(2)
            assertSoftly(resources[0]) {
                path shouldBe "测试 memo.md"
                isDirectory shouldBe false
                etag shouldBe "\"memo-etag\""
                lastModified shouldBe 1_445_412_480_000L
                size shouldBe 42L
            }
            assertSoftly(resources[1]) {
                path shouldBe "attachments"
                isDirectory shouldBe true
                etag shouldBe null
                size shouldBe null
            }
            assertSoftly(transport.requests.single()) {
                method shouldBe "PROPFIND"
                header("Depth") shouldBe "1"
            }
        }

        test("given missing collection when ensured then PROPFIND precedes MKCOL") {
            val transport =
                ScriptedWebDavTransport(
                    ScriptedResponse(code = 404),
                    ScriptedResponse(code = 201),
                )
            val client = OkHttpWebDavClient("https://dav.example.com/root/", "alice", "secret", transport.client)

            client.ensureDirectory("attachments")

            transport.requests.map { request -> request.method } shouldBe listOf("PROPFIND", "MKCOL")
            transport.requests.map { it.url.toString() } shouldBe
                listOf(
                    "https://dav.example.com/root/attachments",
                    "https://dav.example.com/root/attachments",
                )
        }

        test("given conditional upload when put runs then precondition headers are preserved") {
            val transport = ScriptedWebDavTransport(ScriptedResponse(code = 204))
            val client = OkHttpWebDavClient("https://dav.example.com/root/", "alice", "secret", transport.client)

            client.putSmallFile(
                path = "memo.md",
                bytes = "content".encodeToByteArray(),
                contentType = "text/markdown",
                lastModifiedHint = null,
                expectedEtag = "\"expected\"",
                requireAbsent = true,
            )

            assertSoftly(transport.requests.single()) {
                method shouldBe "PUT"
                header("If-Match") shouldBe "\"expected\""
                header("If-None-Match") shouldBe "*"
            }
        }

        test("factory returns cached client on repeated create with same credentials") {
            val factory = OkHttpWebDavClientFactory(SyncHttpClientProvider(), DisabledSyncPerformanceTuner)

            val first = factory.create("https://dav.example.com/", "alice", "secret")
            val second = factory.create("https://dav.example.com/", "alice", "secret")

            second.shouldBeSameInstanceAs(first)
        }

        test("factory returns fresh client after invalidate for same endpoint and username") {
            val factory = OkHttpWebDavClientFactory(SyncHttpClientProvider(), DisabledSyncPerformanceTuner)
            val stale = factory.create("https://dav.example.com/", "alice", "oldpass")

            factory.invalidate("https://dav.example.com/", "alice")

            val fresh = factory.create("https://dav.example.com/", "alice", "newpass")
            fresh.shouldNotBeSameInstanceAs(stale)
        }

        test("factory invalidate preserves clients for other credentials") {
            val factory = OkHttpWebDavClientFactory(SyncHttpClientProvider(), DisabledSyncPerformanceTuner)
            val bobClient = factory.create("https://dav.example.com/", "bob", "pass")
            val otherEndpoint = factory.create("https://other.example.com/", "alice", "pass")
            factory.create("https://dav.example.com/", "alice", "pass")

            factory.invalidate("https://dav.example.com/", "alice")

            factory.create("https://dav.example.com/", "bob", "pass").shouldBeSameInstanceAs(bobClient)
            factory.create("https://other.example.com/", "alice", "pass").shouldBeSameInstanceAs(otherEndpoint)
        }
    }
}

private class ScriptedWebDavTransport(vararg scriptedResponses: ScriptedResponse) : Interceptor {
    private val responses = ArrayDeque(scriptedResponses.toList())
    val requests = mutableListOf<Request>()
    val client = okhttp3.OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        val scripted = responses.removeFirst()
        val headers = Headers.Builder().apply {
            scripted.contentType?.let { add("Content-Type", it) }
        }.build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(scripted.code)
            .message(scripted.message)
            .headers(headers)
            .body(scripted.body.toResponseBody(scripted.contentType?.toMediaType()))
            .build()
    }
}

private data class ScriptedResponse(
    val code: Int,
    val body: String = "",
    val contentType: String? = null,
    val message: String = "scripted",
)

private const val MULTISTATUS_RESPONSE = """<?xml version="1.0" encoding="utf-8" ?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/root/</d:href>
    <d:propstat>
      <d:prop><d:resourcetype><d:collection /></d:resourcetype></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/root/%E6%B5%8B%E8%AF%95%20memo.md</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype />
        <d:getetag>"memo-etag"</d:getetag>
        <d:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</d:getlastmodified>
        <d:getcontentlength>42</d:getcontentlength>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>https://dav.example.com/root/attachments/</d:href>
    <d:propstat>
      <d:prop><d:resourcetype><d:collection /></d:resourcetype></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>https://other.example.com/root/foreign.md</d:href>
    <d:propstat>
      <d:prop><d:resourcetype /></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>
"""
