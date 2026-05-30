package com.lomo.app.feature.gallery

import com.lomo.app.feature.main.GalleryUiMemosState
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/*
 * Behavior Contract:
 * - Unit under test: resolveGalleryScreenDisplayState (GalleryScreen display-state reducer)
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: decide gallery loading vs empty vs grid so the mosaic only renders once tile aspect ratios
 *   and first-screen image loads are final.
 *
 * Scenarios:
 * - Given the gallery is still loading memos, when reduced, then the state is Loading (not a true empty gallery).
 * - Given memos loaded but the list is empty, when reduced, then the state is Empty.
 * - Given loaded memos with no aspect map yet, when reduced, then the state stays Loading.
 * - Given loaded memos whose aspect ratios are not all resolved, when reduced, then the state stays Loading so the
 *   mosaic does not render-then-reflow.
 * - Given loaded memos whose first-screen images are not loaded yet, when reduced, then the state stays Loading so
 *   cells do not appear blank after the loading indicator leaves.
 * - Given loaded memos with all dimensions and first-screen images ready, when reduced, then the state is Grid.
 *
 * Observable outcomes:
 * - The returned GalleryScreenDisplayState (Loading | Empty | Grid) for each input combination.
 *
 * TDD proof:
 * - RED: before the aspectsReady and initialImagesReady gates, loaded memos with a present aspect map reduced straight
 *   to Grid, so the "stay loading until every tile/image resolves" scenarios failed (Grid instead of Loading).
 * - RED command: `./gradlew :app:testDebugUnitTest --tests 'com.lomo.app.feature.gallery.GalleryScreenDisplayStateTest'`.
 * - GREEN: the reducer keeps Loading until aspectsReady is true.
 *
 * Excludes:
 * - Compose rendering, image decoding, ContentResolver I/O, and navigation wiring.
 *
 * Test Change Justification:
 * - Reason category: production contract change (added aspectsReady gate).
 * - Old behavior/assertion being replaced: "loaded memos with dimensions resolve to grid" treated a present
 *   (possibly defaulted) aspect map as grid-ready.
 * - Why old assertion is no longer correct: a present-but-not-fully-resolved aspect map renders square tiles that
 *   reflow as ratios decode; the grid must wait for all aspects.
 * - Coverage preserved by: splitting into "defaulted aspects stay loading" and "all resolved resolves to grid".
 * - Why this is not fitting the test to the implementation: the scenarios encode the intended no-reflow entry
 *   behavior, asserting Loading vs Grid by readiness rather than mirroring the reducer's branches.
 */
class GalleryScreenDisplayStateTest : AppFunSpec() {
    init {
        test("initial gallery loading is not treated as true empty") {
            resolveGalleryScreenDisplayState(
                galleryState = GalleryUiMemosState.Loading,
                aspectByMemoId = null,
                aspectsReady = false,
                initialImagesReady = false,
            ) shouldBe GalleryScreenDisplayState.Loading
        }

        test("loaded empty gallery is the only true empty state") {
            resolveGalleryScreenDisplayState(
                galleryState = GalleryUiMemosState.Loaded(emptyList()),
                aspectByMemoId = null,
                aspectsReady = false,
                initialImagesReady = false,
            ) shouldBe GalleryScreenDisplayState.Empty
        }

        test("loaded memos wait in loading state until image dimensions resolve") {
            val memos = persistentListOf(galleryMemo("memo-with-image", "images/photo.jpg"))

            resolveGalleryScreenDisplayState(
                galleryState = GalleryUiMemosState.Loaded(memos),
                aspectByMemoId = null,
                aspectsReady = false,
                initialImagesReady = false,
            ) shouldBe GalleryScreenDisplayState.Loading
        }

        test("loaded memos stay loading until every tile aspect is resolved") {
            val memos = persistentListOf(galleryMemo("memo-with-image", "images/photo.jpg"))
            // Defaulted (square) aspect map is present, but resolution has not completed.
            val aspectByMemoId = persistentMapOf("memo-with-image" to 1.0f)

            resolveGalleryScreenDisplayState(
                galleryState = GalleryUiMemosState.Loaded(memos),
                aspectByMemoId = aspectByMemoId,
                aspectsReady = false,
                initialImagesReady = true,
            ) shouldBe GalleryScreenDisplayState.Loading
        }

        test("loaded memos stay loading until first-screen images are loaded") {
            val memos = persistentListOf(galleryMemo("memo-with-image", "images/photo.jpg"))
            val aspectByMemoId = persistentMapOf("memo-with-image" to 1.2f)

            resolveGalleryScreenDisplayState(
                galleryState = GalleryUiMemosState.Loaded(memos),
                aspectByMemoId = aspectByMemoId,
                aspectsReady = true,
                initialImagesReady = false,
            ) shouldBe GalleryScreenDisplayState.Loading
        }

        test("loaded memos with all dimensions resolved resolve to grid") {
            val memos = persistentListOf(galleryMemo("memo-with-image", "images/photo.jpg"))
            val aspectByMemoId = persistentMapOf("memo-with-image" to 1.2f)

            resolveGalleryScreenDisplayState(
                galleryState = GalleryUiMemosState.Loaded(memos),
                aspectByMemoId = aspectByMemoId,
                aspectsReady = true,
                initialImagesReady = true,
            ) shouldBe GalleryScreenDisplayState.Grid(
                memos = memos,
                aspectByMemoId = aspectByMemoId,
            )
        }
    }

    private fun galleryMemo(
        id: String,
        imageUrl: String,
    ): MemoUiModel =
        MemoUiModel(
            memo =
                Memo(
                    id = id,
                    timestamp = 1L,
                    content = "![image]($imageUrl)",
                    rawContent = "![image]($imageUrl)",
                    dateKey = "2026_05_13",
                    imageUrls = listOf(imageUrl),
                ),
            processedContent = id,
            precomputedRenderPlan = null,
            tags = persistentListOf(),
            imageUrls = persistentListOf(imageUrl),
        )
}
