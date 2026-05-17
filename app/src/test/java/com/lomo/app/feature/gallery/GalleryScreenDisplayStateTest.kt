package com.lomo.app.feature.gallery

import com.lomo.app.feature.main.GalleryUiMemosState
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/*
 * Test Contract:
 * - Unit under test: GalleryScreen display-state reducer.
 *
 * Scenario matrix:
 * - Happy: standard happy path for GalleryScreenDisplayStateTest.
 * - Boundary: boundary and edge cases for GalleryScreenDisplayStateTest.
 * - Failure: failure and error scenarios for GalleryScreenDisplayStateTest.
 * - Must-not-happen: invariants are never violated for GalleryScreenDisplayStateTest.
 * - Behavior focus: gallery entry must distinguish initial loading, true empty data, and image-dimension
 *   loading so transient empty lists or unresolved dimensions do not flash a real empty gallery or blank page.
 * - Observable outcomes: resolved GalleryScreenDisplayState for loading, loaded-empty, dimensions-loading,
 *   and grid-ready inputs.
 * - Red phase: Fails before the fix because GalleryScreenContent only receives an empty memo list and
 *   nullable dimensions, so it cannot distinguish initial loading from a true empty gallery and renders a
 *   blank page while dimensions are unresolved.
 * - Excludes: Compose rendering, image decoding, ContentResolver I/O, and navigation wiring.
 */
class GalleryScreenDisplayStateTest : AppFunSpec() {
    init {
        test("initial gallery loading is not treated as true empty") {
            (resolveGalleryScreenDisplayState(
                    galleryState = GalleryUiMemosState.Loading,
                    aspectByMemoId = null,
                )) shouldBe (GalleryScreenDisplayState.Loading)
        }
    }

    init {
        test("loaded empty gallery is the only true empty state") {
            (resolveGalleryScreenDisplayState(
                    galleryState = GalleryUiMemosState.Loaded(emptyList()),
                    aspectByMemoId = null,
                )) shouldBe (GalleryScreenDisplayState.Empty)
        }
    }

    init {
        test("loaded memos wait in loading state until image dimensions resolve") {
            val memos = persistentListOf(galleryMemo("memo-with-image", "images/photo.jpg"))

            (resolveGalleryScreenDisplayState(
                    galleryState = GalleryUiMemosState.Loaded(memos),
                    aspectByMemoId = null,
                )) shouldBe (GalleryScreenDisplayState.Loading)
        }
    }

    init {
        test("loaded memos with dimensions resolve to grid") {
            val memos = persistentListOf(galleryMemo("memo-with-image", "images/photo.jpg"))
            val aspectByMemoId = persistentMapOf("memo-with-image" to 1.2f)

            (resolveGalleryScreenDisplayState(
                    galleryState = GalleryUiMemosState.Loaded(memos),
                    aspectByMemoId = aspectByMemoId,
                )) shouldBe (GalleryScreenDisplayState.Grid(
                    memos = memos,
                    aspectByMemoId = aspectByMemoId,
                ))
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
