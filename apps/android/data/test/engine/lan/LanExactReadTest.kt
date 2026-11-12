package com.lomo.data.engine.lan

/*
 * Behavior Contract:
 * - Unit under test: InputStream.readExactly.
 * - Owning layer: data LAN attachment sender.
 * - Priority tier: P0.
 * - Capability: read one planned attachment chunk with APIs present on minSdk devices
 *   (`InputStream.readNBytes` is API 33 and crashed LAN sends on Android 12).
 *
 * Scenarios:
 * - Given a source that returns short reads, when a chunk is read, then the chunk is filled
 *   exactly and the remaining bytes stay in the stream for the next chunk.
 * - Given a source shorter than the planned size, when a chunk is read, then the transfer fails
 *   instead of sending a truncated chunk.
 *
 * Observable outcomes: chunk bytes, remaining stream bytes and the explicit failure message.
 * TDD proof: device RED was NoSuchMethodError for readNBytes on API 31 surfaced by Android Lint
 * NewApi once lint analyzed data sources; this test guards the replacement's semantics.
 * Excludes: LAN protocol framing, encryption and acknowledgement windows.
 */

import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream

class LanExactReadTest : DataFunSpec() {
    init {
        test("given short reads when a chunk is read then it is filled exactly and the rest remains") {
            val source = OneByteReads(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))

            source.readExactly(3) shouldBe byteArrayOf(1, 2, 3)
            source.readExactly(2) shouldBe byteArrayOf(4, 5)
            source.read() shouldBe -1
        }

        test("given a source shorter than planned when a chunk is read then the transfer fails") {
            val failure =
                shouldThrow<IllegalStateException> {
                    ByteArrayInputStream(byteArrayOf(1, 2)).readExactly(3)
                }

            failure.message shouldBe "LAN attachment source ended before its planned size"
        }
    }
}

private class OneByteReads(source: InputStream) : FilterInputStream(source) {
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(length, 1))
}
