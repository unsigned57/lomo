/*
 * Behavior Contract:
 * - Unit under test: NavigationPayloadRegistry.
 * - Owning layer: app navigation.
 * - Priority tier: P1.
 * - Capability: route payload stores share one transient registry for keyed payloads.
 *
 * Scenarios:
 * - Given a stored payload, when it is read, then reads are non-consuming.
 * - Given a stored payload, when it is removed, then it is returned once and absent afterwards.
 * - Given a payload older than the registry TTL, when a lookup runs, then the payload is pruned.
 * - Given the registry is at capacity and one entry is recently read, when another payload is stored,
 *   then the least recently used unread entry is evicted.
 *
 * Observable outcomes:
 * - returned payload values, null lookups for removed or expired keys, and capacity eviction order.
 *
 * TDD proof:
 * - Fails before implementation because NavigationPayloadRegistry is not defined.
 *
 * Excludes:
 * - Navigation graph rendering, route serialization, process-death recovery, and payload sanitization.
 */

package com.lomo.app.navigation

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class NavigationPayloadRegistryTest : AppFunSpec() {
    private var nowMillis = 0L
    private var nextKey = 0
    private lateinit var registry: NavigationPayloadRegistry<String>

    init {
        beforeTest {
            nowMillis = 0L
            nextKey = 0
            registry =
                NavigationPayloadRegistry(
                    maxEntries = 2,
                    ttlMillis = 1_000L,
                    clock = { nowMillis },
                    keyFactory = { "payload-${nextKey++}" },
                )
        }

        test("given stored payload when read repeatedly then get is non-consuming") {
            val key = registry.put("memo body")

            registry.get(key) shouldBe "memo body"
            registry.get(key) shouldBe "memo body"
        }

        test("given stored payload when removed then it is returned once and absent afterwards") {
            val key = registry.put("share body")

            registry.remove(key) shouldBe "share body"
            registry.remove(key) shouldBe null
            registry.get(key) shouldBe null
        }

        test("given expired payload when lookup runs then the payload is pruned") {
            val key = registry.put("old image list")

            nowMillis = 1_001L

            registry.get(key) shouldBe null
        }

        test("given registry at capacity when recently used entry exists then least recently used key is evicted") {
            val first = registry.put("first")
            val second = registry.put("second")
            registry.get(first) shouldBe "first"

            val third = registry.put("third")

            registry.get(first) shouldBe "first"
            registry.get(second) shouldBe null
            registry.get(third) shouldBe "third"
        }
    }
}
