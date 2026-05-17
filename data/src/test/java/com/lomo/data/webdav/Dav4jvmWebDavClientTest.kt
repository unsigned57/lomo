/*
 * Test Contract:
 * - Unit under test: Dav4jvmWebDavClient
 * - Behavior focus: client creation, caching, and cache invalidation after credential changes.
 * - Observable outcomes: cached client reuse, new client after password change, invalidate evicts old client.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: actual HTTP transport, WebDAV protocol details.
 */
package com.lomo.data.webdav


import com.lomo.data.network.SyncHttpClientProvider
import com.lomo.data.repository.DisabledSyncPerformanceTuner
import okhttp3.OkHttpClient
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull

class Dav4jvmWebDavClientTest : DataFunSpec() {
    init {
        test("client disables automatic redirects for dav4jvm") { `client disables automatic redirects for dav4jvm`() }

        test("relative path resolves absolute encoded href") { `relative path resolves absolute encoded href`() }

        test("relative path resolves path only href") { `relative path resolves path only href`() }

        test("relative path rejects other hosts") { `relative path rejects other hosts`() }

        test("factory returns cached client on repeated create with same credentials") { `factory returns cached client on repeated create with same credentials`() }

        test("factory returns fresh client after invalidate for same endpoint and username") { `factory returns fresh client after invalidate for same endpoint and username`() }

        test("factory invalidate does not affect clients for other usernames") { `factory invalidate does not affect clients for other usernames`() }

        test("factory invalidate does not affect clients for other endpoints") { `factory invalidate does not affect clients for other endpoints`() }
    }


    private fun `client disables automatic redirects for dav4jvm`() {
        val client = Dav4jvmWebDavClient("https://dav.example.com/root/", "alice", "secret")

        val field = Dav4jvmWebDavClient::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        val httpClient = field.get(client) as OkHttpClient

        (httpClient.followRedirects).shouldBeFalse()
        (httpClient.followSslRedirects).shouldBeFalse()
    }

    private fun `relative path resolves absolute encoded href`() {
        val client = Dav4jvmWebDavClient("https://dav.example.com/root/", "alice", "secret")

        invokeRelativeToRoot(client, "https://dav.example.com/root/%E6%B5%8B%E8%AF%95%20memo.md") shouldBe "测试 memo.md"
    }

    private fun `relative path resolves path only href`() {
        val client = Dav4jvmWebDavClient("https://dav.example.com/root/", "alice", "secret")

        invokeRelativeToRoot(client, "/root/memo.md") shouldBe "memo.md"
    }

    private fun `relative path rejects other hosts`() {
        val client = Dav4jvmWebDavClient("https://dav.example.com/root/", "alice", "secret")

        invokeRelativeToRoot(client, "https://other.example.com/root/memo.md").shouldBeNull()
    }

    // --- Factory cache invalidation ---

    private fun `factory returns cached client on repeated create with same credentials`() {
        val factory = Dav4jvmWebDavClientFactory(SyncHttpClientProvider(), DisabledSyncPerformanceTuner)

        val first = factory.create("https://dav.example.com/", "alice", "secret")
        val second = factory.create("https://dav.example.com/", "alice", "secret")

        (second === first).shouldBeTrue()
    }

    private fun `factory returns fresh client after invalidate for same endpoint and username`() {
        val factory = Dav4jvmWebDavClientFactory(SyncHttpClientProvider(), DisabledSyncPerformanceTuner)
        val stale = factory.create("https://dav.example.com/", "alice", "oldpass")

        factory.invalidate("https://dav.example.com/", "alice")

        val fresh = factory.create("https://dav.example.com/", "alice", "newpass")
        (fresh !== stale).shouldBeTrue()
    }

    private fun `factory invalidate does not affect clients for other usernames`() {
        val factory = Dav4jvmWebDavClientFactory(SyncHttpClientProvider(), DisabledSyncPerformanceTuner)
        val bobClient = factory.create("https://dav.example.com/", "bob", "pass")
        factory.create("https://dav.example.com/", "alice", "pass")

        factory.invalidate("https://dav.example.com/", "alice")

        val bobAfter = factory.create("https://dav.example.com/", "bob", "pass")
        (bobAfter === bobClient).shouldBeTrue()
    }

    private fun `factory invalidate does not affect clients for other endpoints`() {
        val factory = Dav4jvmWebDavClientFactory(SyncHttpClientProvider(), DisabledSyncPerformanceTuner)
        val otherEndpoint = factory.create("https://other.example.com/", "alice", "pass")
        factory.create("https://dav.example.com/", "alice", "pass")

        factory.invalidate("https://dav.example.com/", "alice")

        val otherAfter = factory.create("https://other.example.com/", "alice", "pass")
        (otherAfter === otherEndpoint).shouldBeTrue()
    }

    private fun invokeRelativeToRoot(
        client: Dav4jvmWebDavClient,
        href: String,
    ): String? {
        val method = Dav4jvmWebDavClient::class.java.getDeclaredMethod("relativeToRoot", String::class.java)
        method.isAccessible = true
        return method.invoke(client, href) as String?
    }
}
