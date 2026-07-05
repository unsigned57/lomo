package com.lomo.app.navigation

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: NavRoute serialization identity.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: exposes stable typed-navigation route names that survive release minification.
 *
 * Scenarios:
 * - Given typed navigation asks for any app route identity, when the serializer descriptor is read,
 *   then it returns an app-owned stable route name instead of a minifiable Kotlin class name.
 *
 * Observable outcomes:
 * - Route serializer descriptor serial names used by Navigation Compose typed routes.
 *
 * TDD proof:
 * - RED: before stable serial names were declared, NavRoute.Main serialized as the fully qualified
 *   Kotlin class name, while release runtime class names could be minified and no longer match.
 *
 * Excludes:
 * - Navigation Compose runtime, back-stack persistence, and screen rendering.
 */
class NavRouteContractTest : AppFunSpec() {
    init {
        test("given typed routes when serialized for navigation then app owned stable route names are used") {
            NavRoute.Main.serializer().descriptor.serialName shouldBe "main"
            NavRoute.Settings.serializer().descriptor.serialName shouldBe "settings"
            NavRoute.Trash.serializer().descriptor.serialName shouldBe "trash"
            NavRoute.Search.serializer().descriptor.serialName shouldBe "search"
            NavRoute.Tag.serializer().descriptor.serialName shouldBe "tag"
            NavRoute.ImageViewer.serializer().descriptor.serialName shouldBe "imageViewer"
            NavRoute.DailyReview.serializer().descriptor.serialName shouldBe "dailyReview"
            NavRoute.Gallery.serializer().descriptor.serialName shouldBe "gallery"
            NavRoute.GalleryReel.serializer().descriptor.serialName shouldBe "galleryReel"
            NavRoute.Statistics.serializer().descriptor.serialName shouldBe "statistics"
            NavRoute.Share.serializer().descriptor.serialName shouldBe "share"
        }
    }
}
