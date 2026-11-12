package com.lomo.app.feature.gallery

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentMapOf

/*
 * Behavior Contract:
 * Capability: entry geometry survives scrolling beyond the image dimension cache;
 * owning layer: app presentation; priority: P1.
 * Scenarios:
 * - Given partial entry dimensions, when resolved, then entry stays Resolving.
 * - Given complete entry dimensions, when resolved, then Ready retains the exact aspects.
 * - Given Ready geometry, when decoder entries evict or more pages arrive, then entry stays Ready.
 * Observable outcomes: geometry state and retained entry aspect map.
 * TDD proof: gallery-red.log establishes the blocking display gate; this regression additionally
 * locks its session lifetime independently of the decoder cache.
 * Excludes: Compose frame timing and actual image bytes.
 */
class GalleryEntryGeometryTest : AppFunSpec() {
    init {
        test("given entry dimensions when partial then entry waits and when complete then exact geometry is retained") {
            val urls = listOf("portrait", "landscape")
            resolveGalleryEntryGeometry(GalleryEntryGeometry.Resolving, urls, mapOf("portrait" to 0.5f)) shouldBe
                GalleryEntryGeometry.Resolving
            resolveGalleryEntryGeometry(
                GalleryEntryGeometry.Resolving,
                urls,
                mapOf("portrait" to 0.5f, "landscape" to 2f),
            ) shouldBe
                GalleryEntryGeometry.Ready(persistentMapOf("portrait" to 0.5f, "landscape" to 2f))
        }
        test("given ready entry when old dimensions evict and pages append then entry retains its geometry") {
            val ready = GalleryEntryGeometry.Ready(persistentMapOf("portrait" to 0.5f))
            resolveGalleryEntryGeometry(ready, listOf("portrait", "later-page"), emptyMap()) shouldBe ready
            resolveGalleryEntryGeometry(GalleryEntryGeometry.Resolving, emptyList(), emptyMap()) shouldBe
                GalleryEntryGeometry.Resolving
        }
    }
}
