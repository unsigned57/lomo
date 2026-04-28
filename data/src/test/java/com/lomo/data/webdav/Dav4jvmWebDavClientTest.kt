/*
 * Test Contract:
 * - Unit under test: Dav4jvmWebDavClient
 * - Behavior focus: client creation, caching, and cache invalidation after credential changes.
 * - Observable outcomes: cached client reuse, new client after password change, invalidate evicts old client.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: actual HTTP transport, WebDAV protocol details.
 */
package com.lomo.data.webdav

import com.lomo.data.network.SyncHttpClientProvider
import com.lomo.data.repository.DisabledSyncPerformanceTuner
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class Dav4jvmWebDavClientTest {
    @Test
    fun `client disables automatic redirects for dav4jvm`() {
        val client = Dav4jvmWebDavClient("https://dav.example.com/root/", "alice", "secret")

        val field = Dav4jvmWebDavClient::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        val httpClient = field.get(client) as OkHttpClient

        assertFalse(httpClient.followRedirects)
        assertFalse(httpClient.followSslRedirects)
    }

    @Test
    fun `relative path resolves absolute encoded href`() {
        val client = Dav4jvmWebDavClient("https://dav.example.com/root/", "alice", "secret")

        assertEquals(
            "测试 memo.md",
            invokeRelativeToRoot(client, "https://dav.example.com/root/%E6%B5%8B%E8%AF%95%20memo.md"),
        )
    }

    @Test
    fun `relative path resolves path only href`() {
        val client = Dav4jvmWebDavClient("https://dav.example.com/root/", "alice", "secret")

        assertEquals("memo.md", invokeRelativeToRoot(client, "/root/memo.md"))
    }

    @Test
    fun `relative path rejects other hosts`() {
        val client = Dav4jvmWebDavClient("https://dav.example.com/root/", "alice", "secret")

        assertNull(invokeRelativeToRoot(client, "https://other.example.com/root/memo.md"))
    }

    // --- Factory cache invalidation ---

    @Test
    fun `factory returns cached client on repeated create with same credentials`() {
        val factory = Dav4jvmWebDavClientFactory(SyncHttpClientProvider(), DisabledSyncPerformanceTuner)

        val first = factory.create("https://dav.example.com/", "alice", "secret")
        val second = factory.create("https://dav.example.com/", "alice", "secret")

        assertSame(first, second)
    }

    @Test
    fun `factory returns fresh client after invalidate for same endpoint and username`() {
        val factory = Dav4jvmWebDavClientFactory(SyncHttpClientProvider(), DisabledSyncPerformanceTuner)
        val stale = factory.create("https://dav.example.com/", "alice", "oldpass")

        factory.invalidate("https://dav.example.com/", "alice")

        val fresh = factory.create("https://dav.example.com/", "alice", "newpass")
        assertNotSame(stale, fresh)
    }

    @Test
    fun `factory invalidate does not affect clients for other usernames`() {
        val factory = Dav4jvmWebDavClientFactory(SyncHttpClientProvider(), DisabledSyncPerformanceTuner)
        val bobClient = factory.create("https://dav.example.com/", "bob", "pass")
        factory.create("https://dav.example.com/", "alice", "pass")

        factory.invalidate("https://dav.example.com/", "alice")

        val bobAfter = factory.create("https://dav.example.com/", "bob", "pass")
        assertSame(bobClient, bobAfter)
    }

    @Test
    fun `factory invalidate does not affect clients for other endpoints`() {
        val factory = Dav4jvmWebDavClientFactory(SyncHttpClientProvider(), DisabledSyncPerformanceTuner)
        val otherEndpoint = factory.create("https://other.example.com/", "alice", "pass")
        factory.create("https://dav.example.com/", "alice", "pass")

        factory.invalidate("https://dav.example.com/", "alice")

        val otherAfter = factory.create("https://other.example.com/", "alice", "pass")
        assertSame(otherEndpoint, otherAfter)
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
