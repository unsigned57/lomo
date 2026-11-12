package com.lomo.data.engine

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.net.Uri
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException

/*
 * Behavior Contract:
 * Capability: one SAF operation owns one provider lease through its final verification; data; P0.
 * Scenarios:
 * Given a provider connection, when two reads run, then both use the leased resolver and finish
 * before it closes; a failed operation also closes the client and preserves the original error.
 * Given an unavailable provider, when acquisition runs, then an explicit IOException surfaces.
 * Observable outcomes: bytes read while the connection is live, release state and errors.
 * TDD proof: RED with the original unleased resolver path: reads succeed but the provider lease
 * is not owned/released and an unavailable provider reaches domain work instead of IOException;
 * GREEN: ./kotlin test --include-module=data --include-classes='com.lomo.data.engine.SafProviderConnectionTest'.
 * Excludes: Binder latency and Android 26-28, which use the original resolver operations.
 */
class SafProviderConnectionTest : DataFunSpec() {
    init {
        afterTest { unmockkStatic(ContentResolver::class) }

        test("given a leased provider when reads complete then release follows the last read") {
            val fixture = ProviderConnectionFixture()
            val result = withDocumentProviderConnection(fixture.resolver, fixture.tree) { connected ->
                checkNotNull(connected.openInputStream(fixture.tree)).use { it.read() } +
                    checkNotNull(connected.openInputStream(fixture.tree)).use { it.read() }
            }
            result shouldBe 14
            fixture.reads shouldBe 2
            fixture.closed shouldBe true
        }

        test("given a failed operation when its lease unwinds then the provider closes and the error survives") {
            val fixture = ProviderConnectionFixture()
            val failure = IOException("provider write failed")
            shouldThrow<IOException> {
                withDocumentProviderConnection(fixture.resolver, fixture.tree) { connected ->
                    checkNotNull(connected.openInputStream(fixture.tree)).use { it.read() }
                    throw failure
                }
            } shouldBe failure
            fixture.closed shouldBe true
        }

        test("given an unavailable provider when acquisition runs then it fails explicitly") {
            val fixture = ProviderConnectionFixture()
            every { fixture.resolver.acquireContentProviderClient(fixture.tree) } returns null
            shouldThrow<IOException> {
                withDocumentProviderConnection(fixture.resolver, fixture.tree) { error("unreachable") }
            }.message shouldBe "Document provider is unavailable: documents.test"
            fixture.closed shouldBe false
        }
    }
}

private class ProviderConnectionFixture {
    val resolver = mockk<ContentResolver>()
    val tree = mockk<Uri>()
    var closed = false
    var reads = 0

    init {
        val client = mockk<ContentProviderClient>()
        val connected = mockk<ContentResolver>()
        mockkStatic(ContentResolver::class)
        every { tree.authority } returns "documents.test"
        every { resolver.acquireContentProviderClient(tree) } returns client
        every { ContentResolver.wrap(client) } returns connected
        every { resolver.openInputStream(tree) } answers { byteArrayOf(7).inputStream() }
        every { connected.openInputStream(tree) } answers {
            check(!closed) { "provider closed before the read" }
            reads += 1
            byteArrayOf(7).inputStream()
        }
        every { client.close() } answers { closed = true }
    }
}
