/*
 * Behavior Contract:
 * - Unit under test: ShareRoutePayloadStore.
 * - Owning layer: app navigation.
 * - Priority tier: P1.
 * - Capability: keep share route arguments small while allowing memo payload recovery after route
 *   restoration.
 *
 * Scenarios:
 * - Given memo content is stored for a share route, when the key is consumed in the same process, then
 *   the content is returned once.
 * - Given a share route key survives but the process-memory registry is gone, when the store is backed
 *   by a persistent cache, then consuming the key restores the memo content once.
 * - Given memo content has already been consumed from the persistent cache, when process-memory
 *   consumption state is lost again, then the same key cannot restore the old payload a second time.
 * - Given memo content is consumed from process memory while a persistent cache entry also exists,
 *   when process-memory state is lost afterward, then the same key cannot restore the old payload
 *   from disk.
 * - Given an unknown key, when it is consumed, then no content is returned.
 *
 * Observable outcomes:
 * - returned memo content and null results after one-time consumption or missing keys.
 *
 * TDD proof:
 * - The memory-hit one-time-consumption scenario fails before implementation because consuming the
 *   in-memory payload returns before invalidating the matching on-disk payload.
 *
 * Excludes:
 * - NavHost rendering, route serialization, LAN transfer, and app backup policy.
 */

package com.lomo.app.navigation

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ShareRoutePayloadStoreTest : AppFunSpec() {
    init {
        afterTest {
            ShareRoutePayloadStore.clearForTest()
        }

        test("given memo content is stored when consumed then content is returned once") {
            val key = ShareRoutePayloadStore.putMemoContent("memo body")

            ShareRoutePayloadStore.consumeMemoContent(key) shouldBe "memo body"
            ShareRoutePayloadStore.consumeMemoContent(key) shouldBe null
        }

        test("given process registry is cleared when persistent cache is configured then content is restored once") {
            val cacheDir = Files.createTempDirectory("share-route-payload-store-test").toFile()
            ShareRoutePayloadStore.configurePersistentCacheForTest(cacheDir)
            val key = ShareRoutePayloadStore.putMemoContent("memo body after process restart")

            ShareRoutePayloadStore.clearMemoryForTest()

            ShareRoutePayloadStore.consumeMemoContent(key) shouldBe "memo body after process restart"
            ShareRoutePayloadStore.consumeMemoContent(key) shouldBe null
            cacheDir.deleteRecursively()
        }

        test("given persistent payload was consumed when memory state is cleared again then old payload is unavailable") {
            val cacheDir = Files.createTempDirectory("share-route-payload-store-repeat-test").toFile()
            ShareRoutePayloadStore.configurePersistentCacheForTest(cacheDir)
            val key = ShareRoutePayloadStore.putMemoContent("memo body after restart")

            ShareRoutePayloadStore.clearMemoryForTest()
            ShareRoutePayloadStore.consumeMemoContent(key) shouldBe "memo body after restart"

            ShareRoutePayloadStore.clearMemoryForTest()

            ShareRoutePayloadStore.consumeMemoContent(key) shouldBe null
            cacheDir.deleteRecursively()
        }

        test("given memory payload was consumed when memory state is cleared then old disk payload is unavailable") {
            val cacheDir = Files.createTempDirectory("share-route-payload-store-memory-repeat-test").toFile()
            ShareRoutePayloadStore.configurePersistentCacheForTest(cacheDir)
            val key = ShareRoutePayloadStore.putMemoContent("memo body from memory")

            ShareRoutePayloadStore.consumeMemoContent(key) shouldBe "memo body from memory"

            ShareRoutePayloadStore.clearMemoryForTest()

            ShareRoutePayloadStore.consumeMemoContent(key) shouldBe null
            cacheDir.deleteRecursively()
        }

        test("given unknown key when consumed then null is returned") {
            ShareRoutePayloadStore.consumeMemoContent("missing") shouldBe null
        }
    }
}
