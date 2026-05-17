package com.lomo.app.feature.gallery

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/*
 * Test Contract:
 * - Unit under test: GalleryGridLayout planner.
 * - Behavior focus: aspect classification boundaries, organic mosaic rect planning, and local lookahead integrity.
 * - Observable outcomes: planned aspect kind, selected first image URL, tile rect spans, sealed-row occupancy, memo ID set.
 * - Red phase: Fails before the fix because the planner forces later images into unreadably narrow fallback tiles
 *   and keeps extreme-aspect images in small containers that visibly crop content.
 * - Excludes: Compose custom-layout measurement, image decoding, pager gestures, shared-transition animation.
 *
 * Test Change Justification:
 * - Reason category: Product contract changed.
 * - Old behavior/assertion being replaced: fixed every-eighth square memo highlight cadence, fixed bento section grouping, and hard full-bottom fill.
 * - Why old assertion is no longer correct: the gallery now prioritizes left/right fill, then aspect-shaped organic placement, while allowing a ragged bottom edge.
 * - Coverage preserved by: boundary/fallback tests plus rect-shape, sealed-row packing, trailing-item, and ID-integrity tests.
 * - Why this is not fitting the test to the implementation: these tests define the user-requested planner contract before production edits.
 */
class GalleryGridLayoutTest : AppFunSpec() {
    init {
        test("planGalleryMosaicLayout classifies aspect boundaries inclusively") {
            val memos =
                listOf(
                    galleryLayoutInput("portrait", "/images/portrait.jpg"),
                    galleryLayoutInput("square-low", "/images/square-low.jpg"),
                    galleryLayoutInput("square-high", "/images/square-high.jpg"),
                    galleryLayoutInput("landscape", "/images/landscape.jpg"),
                )

            val layout =
                planGalleryMosaicLayout(
                    memos = memos,
                    aspectByMemoId =
                        mapOf(
                            "portrait" to 0.69f,
                            "square-low" to 0.7f,
                            "square-high" to 1.4f,
                            "landscape" to 1.41f,
                        ),
                )
            val tileById = layout.tiles().associateBy { tile -> tile.memoId }

            (tileById.getValue("portrait").aspectKind) shouldBe (GalleryAspectKind.Portrait)
            (tileById.getValue("portrait").aspectRatio) shouldBe (0.69f)
            (tileById.getValue("portrait").firstImageUrl) shouldBe ("/images/portrait.jpg")
            (tileById.getValue("square-low").aspectKind) shouldBe (GalleryAspectKind.Square)
            (tileById.getValue("square-high").aspectKind) shouldBe (GalleryAspectKind.Square)
            (tileById.getValue("landscape").aspectKind) shouldBe (GalleryAspectKind.Landscape)
        }
    }

    init {
        test("planGalleryMosaicLayout gives portrait landscape and square inputs distinct aspect-biased shapes") {
            val memos =
                listOf(
                    galleryLayoutInput("portrait", "/images/portrait.jpg"),
                    galleryLayoutInput("landscape", "/images/landscape.jpg"),
                    galleryLayoutInput("square", "/images/square.jpg"),
                )

            val layout =
                planGalleryMosaicLayout(
                    memos = memos,
                    aspectByMemoId =
                        mapOf(
                            "portrait" to 0.55f,
                            "landscape" to 1.9f,
                            "square" to 1f,
                        ),
                )
            val tileById = layout.tiles().associateBy { tile -> tile.memoId }

            ((tileById.getValue("portrait").columnSpan < tileById.getValue("portrait").rowSpan)) shouldBe true
            ((tileById.getValue("landscape").columnSpan > tileById.getValue("landscape").rowSpan)) shouldBe true
            ((tileById.getValue("square").columnSpan >= tileById.getValue("square").rowSpan)) shouldBe true
        }
    }

    init {
        test("planGalleryMosaicLayout packs mixed aspects into one interlocked band with sealed rows filled") {
            val memos =
                listOf(
                    galleryLayoutInput("landscape-a", "/images/landscape-a.jpg"),
                    galleryLayoutInput("portrait-a", "/images/portrait-a.jpg"),
                    galleryLayoutInput("square-a", "/images/square-a.jpg"),
                    galleryLayoutInput("portrait-b", "/images/portrait-b.jpg"),
                    galleryLayoutInput("landscape-b", "/images/landscape-b.jpg"),
                    galleryLayoutInput("square-b", "/images/square-b.jpg"),
                )

            val layout =
                planGalleryMosaicLayout(
                    memos = memos,
                    aspectByMemoId =
                        mapOf(
                            "landscape-a" to 1.8f,
                            "portrait-a" to 0.55f,
                            "square-a" to 1f,
                            "portrait-b" to 0.6f,
                            "landscape-b" to 1.7f,
                            "square-b" to 1f,
                        ),
            )

            (layout.size) shouldBe (1)
            ((layout.single().hasEmptySealedGridCells())) shouldBe false
            (layout.tiles().map { tile -> tile.memoId }.toSet()) shouldBe (memos.map { input -> input.memoId }.toSet())
        }
    }

    init {
        test("planGalleryMosaicLayout avoids fixed full width row templates") {
            val memos =
                listOf(
                    galleryLayoutInput("wide-a", "/images/wide-a.jpg"),
                    galleryLayoutInput("square-a", "/images/square-a.jpg"),
                    galleryLayoutInput("tall-a", "/images/tall-a.jpg"),
                    galleryLayoutInput("wide-b", "/images/wide-b.jpg"),
                    galleryLayoutInput("tall-b", "/images/tall-b.jpg"),
                    galleryLayoutInput("square-b", "/images/square-b.jpg"),
                    galleryLayoutInput("square-c", "/images/square-c.jpg"),
                    galleryLayoutInput("wide-c", "/images/wide-c.jpg"),
                    galleryLayoutInput("tall-c", "/images/tall-c.jpg"),
                )

            val layout =
                planGalleryMosaicLayout(
                    memos = memos,
                    aspectByMemoId =
                        mapOf(
                            "wide-a" to 2.1f,
                            "square-a" to 1f,
                            "tall-a" to 0.52f,
                            "wide-b" to 1.7f,
                            "tall-b" to 0.58f,
                            "square-b" to 1f,
                            "square-c" to 1f,
                            "wide-c" to 1.9f,
                            "tall-c" to 0.62f,
                        ),
                ).single()

            ((layout.usesOnlyFixedFullWidthRows())) shouldBe false
            ((layout.hasEmptySealedGridCells())) shouldBe false
        }
    }

    init {
        test("planGalleryMosaicLayout keeps left and right edges filled until natural bottom") {
            val memos =
                listOf(
                    galleryLayoutInput("square-a", "/images/square-a.jpg"),
                    galleryLayoutInput("portrait-a", "/images/portrait-a.jpg"),
                    galleryLayoutInput("landscape-a", "/images/landscape-a.jpg"),
                    galleryLayoutInput("portrait-b", "/images/portrait-b.jpg"),
                    galleryLayoutInput("square-b", "/images/square-b.jpg"),
                    galleryLayoutInput("landscape-b", "/images/landscape-b.jpg"),
                    galleryLayoutInput("portrait-c", "/images/portrait-c.jpg"),
                    galleryLayoutInput("square-c", "/images/square-c.jpg"),
                    galleryLayoutInput("landscape-c", "/images/landscape-c.jpg"),
                    galleryLayoutInput("portrait-d", "/images/portrait-d.jpg"),
                    galleryLayoutInput("square-d", "/images/square-d.jpg"),
                    galleryLayoutInput("landscape-d", "/images/landscape-d.jpg"),
                )

            val layout =
                planGalleryMosaicLayout(
                    memos = memos,
                    aspectByMemoId =
                        mapOf(
                            "square-a" to 1f,
                            "portrait-a" to 0.56f,
                            "landscape-a" to 1.95f,
                            "portrait-b" to 0.62f,
                            "square-b" to 1f,
                            "landscape-b" to 1.75f,
                            "portrait-c" to 0.5f,
                            "square-c" to 1f,
                            "landscape-c" to 2.05f,
                            "portrait-d" to 0.58f,
                            "square-d" to 1f,
                            "landscape-d" to 1.65f,
                        ),
                ).single()

            ((layout.hasTilesStartingBelowFirstWidthGap())) shouldBe false
        }
    }

    init {
        test("planGalleryMosaicLayout preserves readable tile width while filling rows") {
            val memos =
                listOf(
                    galleryLayoutInput("milk", "/images/milk.jpg"),
                    galleryLayoutInput("notes-a", "/images/notes-a.jpg"),
                    galleryLayoutInput("desk", "/images/desk.jpg"),
                    galleryLayoutInput("notes-b", "/images/notes-b.jpg"),
                    galleryLayoutInput("receipt", "/images/receipt.jpg"),
                    galleryLayoutInput("starstruck", "/images/starstruck.jpg"),
                    galleryLayoutInput("code-a", "/images/code-a.jpg"),
                    galleryLayoutInput("gemini", "/images/gemini.jpg"),
                    galleryLayoutInput("product", "/images/product.jpg"),
                    galleryLayoutInput("code-b", "/images/code-b.jpg"),
                )
            val aspectByMemoId =
                mapOf(
                    "milk" to 1.15f,
                    "notes-a" to 0.82f,
                    "desk" to 1.72f,
                    "notes-b" to 0.78f,
                    "receipt" to 0.58f,
                    "starstruck" to 0.47f,
                    "code-a" to 1.42f,
                    "gemini" to 0.58f,
                    "product" to 1.05f,
                    "code-b" to 1.45f,
                )

            val layout =
                planGalleryMosaicLayout(
                    memos = memos,
                    aspectByMemoId = aspectByMemoId,
                ).single()

            ((layout.tiles.all { tile -> tile.columnSpan >= GALLERY_MIN_READABLE_COLUMN_SPAN })) shouldBe true
            ((layout.tiles.all { tile ->
                    val sourceAspect = aspectByMemoId.getValue(tile.memoId)
                    abs(tile.layoutAspectRatio - sourceAspect.coerceIn(0.45f, 2.35f)) <= GALLERY_MAX_ASPECT_DELTA
                })) shouldBe true
            ((layout.hasTilesStartingBelowFirstWidthGap())) shouldBe false
        }
    }

    init {
        test("planGalleryMosaicLayout uses larger containers for extreme aspect images") {
            val memos =
                listOf(
                    galleryLayoutInput("panorama", "/images/panorama.jpg"),
                    galleryLayoutInput("receipt", "/images/receipt.jpg"),
                    galleryLayoutInput("code", "/images/code.jpg"),
                    galleryLayoutInput("note", "/images/note.jpg"),
                    galleryLayoutInput("product", "/images/product.jpg"),
                    galleryLayoutInput("desk", "/images/desk.jpg"),
                )
            val aspectByMemoId =
                mapOf(
                    "panorama" to 2.35f,
                    "receipt" to 0.45f,
                    "code" to 1.75f,
                    "note" to 0.52f,
                    "product" to 1.05f,
                    "desk" to 1.9f,
                )

            val layout =
                planGalleryMosaicLayout(
                    memos = memos,
                    aspectByMemoId = aspectByMemoId,
                ).single()
            val tileById = layout.tiles.associateBy { tile -> tile.memoId }
            val panorama = tileById.getValue("panorama")
            val receipt = tileById.getValue("receipt")

            ((panorama.columnSpan >= GALLERY_EXTREME_LANDSCAPE_MIN_COLUMN_SPAN)) shouldBe true
            ((panorama.rowSpan >= GALLERY_EXTREME_LANDSCAPE_MIN_ROW_SPAN)) shouldBe true
            ((receipt.columnSpan >= GALLERY_EXTREME_PORTRAIT_MIN_COLUMN_SPAN)) shouldBe true
            ((receipt.rowSpan >= GALLERY_EXTREME_PORTRAIT_MIN_ROW_SPAN)) shouldBe true
            ((listOf(panorama, receipt).all { tile ->
                    val sourceAspect = aspectByMemoId.getValue(tile.memoId)
                    abs(tile.layoutAspectRatio - sourceAspect) <= GALLERY_EXTREME_MAX_ASPECT_DELTA
                })) shouldBe true
            ((layout.hasTilesStartingBelowFirstWidthGap())) shouldBe false
        }
    }

    init {
        test("planGalleryMosaicLayout keeps incomplete trailing items positioned") {
            val memos =
                listOf(
                    galleryLayoutInput("wide", "/images/wide.jpg"),
                    galleryLayoutInput("tail", "/images/tail.jpg"),
                )

            val layout =
                planGalleryMosaicLayout(
                    memos = memos,
                    aspectByMemoId =
                        mapOf(
                            "wide" to 2.2f,
                            "tail" to 1f,
                        ),
                )
            val tiles = layout.tiles()

            (tiles.map { tile -> tile.memoId }) shouldBe (listOf("wide", "tail"))
            ((tiles.all { tile -> tile.columnSpan > 0 && tile.rowSpan > 0 })) shouldBe true
            ((tiles.all { tile -> tile.row >= 0 && tile.column in 0 until GALLERY_MOSAIC_COLUMN_COUNT })) shouldBe true
        }
    }

    init {
        test("planGalleryMosaicLayout does not collapse unknown square inputs into uniform three column grid") {
            val memos = (0 until 8).map { index -> galleryLayoutInput("memo-$index", "/images/$index.jpg") }

            val layout =
                planGalleryMosaicLayout(
                    memos = memos,
                    aspectByMemoId = emptyMap(),
                )
            val tiles = layout.tiles()
            val distinctSizes = tiles.map { tile -> tile.columnSpan to tile.rowSpan }.toSet()

            ((tiles.all { tile -> tile.aspectKind == GalleryAspectKind.Square })) shouldBe true
            ((tiles.all { tile -> tile.columnSpan == tile.rowSpan })) shouldBe true
            ((distinctSizes.size > 1)) shouldBe true
            ((tiles.any { tile -> tile.columnSpan > GALLERY_THREE_COLUMN_SPAN })) shouldBe true
            ((tiles.all { tile -> tile.columnSpan == GALLERY_THREE_COLUMN_SPAN })) shouldBe false
            ((layout.single().hasEmptySealedGridCells())) shouldBe false
        }
    }

    init {
        test("planGalleryMosaicLayout local lookahead does not drop or duplicate memo ids") {
            val memos =
                listOf(
                    galleryLayoutInput("wide-a", "/images/wide-a.jpg"),
                    galleryLayoutInput("tall-a", "/images/tall-a.jpg"),
                    galleryLayoutInput("tall-b", "/images/tall-b.jpg"),
                    galleryLayoutInput("square-a", "/images/square-a.jpg"),
                    galleryLayoutInput("wide-b", "/images/wide-b.jpg"),
                    galleryLayoutInput("square-b", "/images/square-b.jpg"),
                    galleryLayoutInput("tall-c", "/images/tall-c.jpg"),
                )

            val layout =
                planGalleryMosaicLayout(
                    memos = memos,
                    aspectByMemoId =
                        mapOf(
                            "wide-a" to 2.1f,
                            "tall-a" to 0.52f,
                            "tall-b" to 0.58f,
                            "square-a" to 1f,
                            "wide-b" to 1.8f,
                            "square-b" to 1f,
                            "tall-c" to 0.6f,
                        ),
                )
            val plannedIds = layout.tiles().map { tile -> tile.memoId }

            (plannedIds.size) shouldBe (memos.size)
            (plannedIds.toSet()) shouldBe (memos.map { input -> input.memoId }.toSet())
        }
    }

    init {
        test("planGalleryMosaicLayout keeps long mixed galleries placeable") {
            val aspectCycle =
                listOf(
                    2.2f,
                    0.52f,
                    1f,
                    1.7f,
                    0.61f,
                    1.35f,
                    2.05f,
                    0.48f,
                    0.9f,
                    1.85f,
                    0.58f,
                    1.18f,
                )
            val memos =
                (0 until 72).map { index ->
                    galleryLayoutInput("memo-$index", "/images/$index.jpg")
                }

            val layout =
                planGalleryMosaicLayout(
                    memos = memos,
                    aspectByMemoId =
                        memos.associate { input ->
                            val index = input.memoId.removePrefix("memo-").toInt()
                            input.memoId to aspectCycle[index % aspectCycle.size]
                        },
                )
            val plannedIds = layout.tiles().map { tile -> tile.memoId }

            (plannedIds.size) shouldBe (memos.size)
            (plannedIds.toSet()) shouldBe (memos.map { input -> input.memoId }.toSet())
        }
    }

    init {
        test("planGalleryMosaicLayout falls back to square aspect for unresolved and invalid images") {
            val layout =
                planGalleryMosaicLayout(
                    memos =
                        listOf(
                            galleryLayoutInput("unresolved", "/images/unresolved.jpg"),
                            galleryLayoutInput("invalid", "/images/invalid.jpg"),
                        ),
                    aspectByMemoId = mapOf("invalid" to Float.NaN),
                )
            val tiles = layout.tiles()

            (tiles.map { tile -> tile.aspectKind }) shouldBe (listOf(GalleryAspectKind.Square, GalleryAspectKind.Square))
            (tiles.map { tile -> tile.aspectRatio }) shouldBe (listOf(1f, 1f))
            ((tiles.all { tile -> tile.columnSpan == tile.rowSpan })) shouldBe true
        }
    }

    init {
        test("resolveGalleryAspectByMemoIdOrNull fills unresolved first images with default aspects") {
            val inputs =
                listOf(
                    galleryLayoutInput("wide", "/images/wide.jpg"),
                    galleryLayoutInput("invalid", "/images/invalid.jpg"),
                )

            (resolveGalleryAspectByMemoIdOrNull(
                    layoutInputs = inputs,
                    aspectByImageUrl = mapOf("/images/wide.jpg" to 2.1f),
                )) shouldBe (mapOf(
                    "wide" to 2.1f,
                    "invalid" to GALLERY_DEFAULT_ASPECT_RATIO,
                ))
            (resolveGalleryAspectByMemoIdOrNull(
                    layoutInputs = inputs,
                    aspectByImageUrl =
                        mapOf(
                            "/images/wide.jpg" to 2.1f,
                            "/images/invalid.jpg" to Float.NaN,
                        ),
                )) shouldBe (mapOf(
                    "wide" to 2.1f,
                    "invalid" to GALLERY_DEFAULT_ASPECT_RATIO,
                ))
        }
    }

}

private fun List<GalleryMosaicLayout>.tiles(): List<GalleryMosaicTile> =
    flatMap { layout -> layout.tiles }

private const val GALLERY_THREE_COLUMN_SPAN = GALLERY_MOSAIC_COLUMN_COUNT / 3
private const val GALLERY_MIN_READABLE_COLUMN_SPAN = 4
private const val GALLERY_MAX_ASPECT_DELTA = 0.45f
private const val GALLERY_EXTREME_LANDSCAPE_MIN_COLUMN_SPAN = 10
private const val GALLERY_EXTREME_LANDSCAPE_MIN_ROW_SPAN = 4
private const val GALLERY_EXTREME_PORTRAIT_MIN_COLUMN_SPAN = 5
private const val GALLERY_EXTREME_PORTRAIT_MIN_ROW_SPAN = 9
private const val GALLERY_EXTREME_MAX_ASPECT_DELTA = 0.2f

private fun GalleryMosaicLayout.hasEmptySealedGridCells(): Boolean {
    val occupied = Array(rowCount) { BooleanArray(GALLERY_MOSAIC_COLUMN_COUNT) }
    tiles.forEach { tile ->
        for (row in tile.row until tile.row + tile.rowSpan) {
            for (column in tile.column until tile.column + tile.columnSpan) {
                occupied[row][column] = true
            }
        }
    }
    val sealedRowCount =
        (0 until GALLERY_MOSAIC_COLUMN_COUNT)
            .minOfOrNull { column ->
                occupied.indexOfLast { row -> row[column] } + 1
            } ?: 0

    return occupied
        .take(sealedRowCount)
        .any { row -> row.any { isOccupied -> !isOccupied } }
}

private fun GalleryMosaicLayout.usesOnlyFixedFullWidthRows(): Boolean {
    var row = 0
    while (row < rowCount) {
        val rowTiles = tiles.filter { tile -> tile.row == row }
        if (rowTiles.isEmpty()) return false
        if (rowTiles.sumOf { tile -> tile.columnSpan } != GALLERY_MOSAIC_COLUMN_COUNT) return false
        if (rowTiles.map { tile -> tile.rowSpan }.toSet().size != 1) return false
        row += rowTiles.first().rowSpan
    }

    return true
}

private fun GalleryMosaicLayout.hasTilesStartingBelowFirstWidthGap(): Boolean {
    val occupied = occupiedCells()
    val firstWidthGapRow =
        occupied.indexOfFirst { row ->
            row.any { isOccupied -> !isOccupied }
        }
    if (firstWidthGapRow < 0) return false

    return tiles.any { tile -> tile.row > firstWidthGapRow }
}

private fun GalleryMosaicLayout.occupiedCells(): Array<BooleanArray> {
    val occupied = Array(rowCount) { BooleanArray(GALLERY_MOSAIC_COLUMN_COUNT) }
    tiles.forEach { tile ->
        for (row in tile.row until tile.row + tile.rowSpan) {
            for (column in tile.column until tile.column + tile.columnSpan) {
                occupied[row][column] = true
            }
        }
    }
    return occupied
}

private val GalleryMosaicTile.layoutAspectRatio: Float
    get() = columnSpan.toFloat() / rowSpan.toFloat()
