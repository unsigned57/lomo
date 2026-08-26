package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MediaReferenceIndex.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: bind a Markdown attachment reference to exactly one provider-backed media file.
 *
 * Scenarios:
 * - Given an exact provider display name, when the reference is resolved, then its location wins.
 * - Given a provider changed legacy separators, when one normalized candidate exists, then the
 *   reference resolves to that unique readable location.
 * - Given two provider files normalize to the same reference, when resolved, then the result is
 *   ambiguous and no location is selected.
 * - Given no provider file matches, when resolved, then absence remains explicit.
 *
 * Observable outcomes:
 * - Exact, uniquely compatible, ambiguous, and missing resolution values.
 *
 * TDD proof:
 * - RED on 2026-08-26 because media refresh exposed only exact display-name keys, so a SAF file
 *   named `img 123.png` could not satisfy the Markdown reference `img_123.png`.
 *
 * Excludes:
 * - SAF enumeration, image decoding, Compose rendering, and media mutation.
 */
class MediaReferenceIndexTest : DataFunSpec() {
    init {
        test("given an exact provider name when resolving then exact location wins") {
            val index =
                MediaReferenceIndex.build(
                    listOf(
                        ProviderMediaLocation("img_123.png", "content://images/exact"),
                        ProviderMediaLocation("img 123.png", "content://images/compatible"),
                    ),
                )

            index.resolve("img_123.png") shouldBe
                MediaReferenceResolution.Resolved("content://images/exact")
        }

        test("given one separator-compatible provider name when resolving then it binds uniquely") {
            val index =
                MediaReferenceIndex.build(
                    listOf(ProviderMediaLocation("img 123.png", "content://images/123")),
                )

            index.resolve("../Assets/img_123.png") shouldBe
                MediaReferenceResolution.Resolved("content://images/123")
        }

        test("given compatible provider names collide when resolving then ambiguity is explicit") {
            val index =
                MediaReferenceIndex.build(
                    listOf(
                        ProviderMediaLocation("img 123.png", "content://images/space"),
                        ProviderMediaLocation("img_123.png", "content://images/underscore"),
                    ),
                )

            index.resolve("folder/img-123.png") shouldBe MediaReferenceResolution.Ambiguous
        }

        test("given no provider file matches when resolving then missing is explicit") {
            val index = MediaReferenceIndex.build(emptyList())

            index.resolve("missing.png") shouldBe MediaReferenceResolution.Missing
        }
    }
}
