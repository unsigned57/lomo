package com.lomo.app.feature.gallery

import kotlinx.collections.immutable.toPersistentList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val GALLERY_MAX_BAND_TILE_COUNT = 24
private const val GALLERY_LOOKAHEAD_TILE_COUNT = 6
private const val GALLERY_MIN_COLUMN_SPAN = 4
private const val GALLERY_MIN_ROW_SPAN = 3
private const val GALLERY_SPAN_4 = 4
private const val GALLERY_SPAN_5 = 5
private const val GALLERY_SPAN_6 = 6
private const val GALLERY_SPAN_7 = 7
private const val GALLERY_SPAN_8 = 8
private const val GALLERY_SPAN_9 = 9
private const val GALLERY_SPAN_10 = 10
private const val GALLERY_SPAN_11 = 11
private const val GALLERY_SPAN_12 = 12
private const val GALLERY_MAX_COLUMN_SPAN = 12
private const val GALLERY_MAX_ROW_SPAN = 12
private const val GALLERY_SINGLE_STEP = 1
private const val GALLERY_AREA_CANDIDATE_SMALL_DELTA = 6
private const val GALLERY_AREA_CANDIDATE_LARGE_DELTA = 12
private const val GALLERY_ASPECT_SCORE_SCALE = 100
private const val GALLERY_SEALED_GAP_PENALTY = 10_000
private const val GALLERY_OPEN_GAP_PENALTY = 8
private const val GALLERY_HEIGHT_PENALTY = 42
private const val GALLERY_ROW_PENALTY = 22
private const val GALLERY_LOOKAHEAD_PENALTY = 88
private const val GALLERY_SHAPE_SCORE_WEIGHT = 3
private const val GALLERY_ASPECT_PROTECTED_MISMATCH_PENALTY = 1_000
private const val GALLERY_ASPECT_PROTECTED_MAX_DELTA = 0.2f
private const val GALLERY_SEALED_ROW_REWARD = 150
private const val GALLERY_RECENT_SHAPE_PENALTY = 28
private const val GALLERY_REPEAT_SHAPE_PENALTY = 64
private const val GALLERY_COLUMN_TIE_BREAKER = 2
private const val GALLERY_PREFERRED_PORTRAIT_AREA = 22
private const val GALLERY_PREFERRED_TALL_PORTRAIT_AREA = 42
private const val GALLERY_PREFERRED_EXTREME_PORTRAIT_AREA = 54
private const val GALLERY_PREFERRED_LANDSCAPE_AREA = 26
private const val GALLERY_PREFERRED_WIDE_LANDSCAPE_AREA = 42
private const val GALLERY_PREFERRED_EXTREME_LANDSCAPE_AREA = 60
private const val GALLERY_PREFERRED_SQUARE_AREA = 18
private const val GALLERY_LARGE_SQUARE_AREA = 25
private const val GALLERY_HERO_SQUARE_AREA = 36
private const val GALLERY_HERO_SQUARE_CADENCE = 11
private const val GALLERY_LARGE_SQUARE_CADENCE = 5
private const val GALLERY_AREA_SCORE_WEIGHT = 5
private const val GALLERY_ORIENTATION_MISMATCH_PENALTY = 140
private const val GALLERY_MIN_ASPECT_FOR_SHAPE = 0.45f
private const val GALLERY_MAX_ASPECT_FOR_SHAPE = 2.35f
private const val GALLERY_MAX_LAYOUT_ASPECT_DELTA = 0.45f
private const val GALLERY_TALL_PORTRAIT_ASPECT_MAX = 0.62f
private const val GALLERY_EXTREME_PORTRAIT_ASPECT_MAX = 0.52f
private const val GALLERY_WIDE_LANDSCAPE_ASPECT_MIN = 1.75f
private const val GALLERY_EXTREME_LANDSCAPE_ASPECT_MIN = 2.15f

private val galleryPortraitSizeCandidates =
    listOf(
        GalleryMosaicSize(GALLERY_SPAN_4, GALLERY_SPAN_6),
        GalleryMosaicSize(GALLERY_SPAN_4, GALLERY_SPAN_7),
        GalleryMosaicSize(GALLERY_SPAN_4, GALLERY_SPAN_8),
        GalleryMosaicSize(GALLERY_SPAN_5, GALLERY_SPAN_7),
        GalleryMosaicSize(GALLERY_SPAN_5, GALLERY_SPAN_8),
        GalleryMosaicSize(GALLERY_SPAN_6, GALLERY_SPAN_8),
        GalleryMosaicSize(GALLERY_SPAN_7, GALLERY_SPAN_8),
        GalleryMosaicSize(GALLERY_SPAN_5, GALLERY_SPAN_9),
        GalleryMosaicSize(GALLERY_SPAN_5, GALLERY_SPAN_10),
        GalleryMosaicSize(GALLERY_SPAN_5, GALLERY_SPAN_11),
        GalleryMosaicSize(GALLERY_SPAN_6, GALLERY_SPAN_10),
        GalleryMosaicSize(GALLERY_SPAN_6, GALLERY_SPAN_11),
        GalleryMosaicSize(GALLERY_SPAN_6, GALLERY_SPAN_12),
    )

private val gallerySquareSizeCandidates =
    listOf(
        GalleryMosaicSize(GALLERY_SPAN_4, GALLERY_SPAN_4),
        GalleryMosaicSize(GALLERY_SPAN_5, GALLERY_SPAN_5),
        GalleryMosaicSize(GALLERY_SPAN_6, GALLERY_SPAN_6),
        GalleryMosaicSize(GALLERY_SPAN_7, GALLERY_SPAN_7),
        GalleryMosaicSize(GALLERY_SPAN_8, GALLERY_SPAN_8),
    )

private val galleryLandscapeSizeCandidates =
    listOf(
        GalleryMosaicSize(GALLERY_SPAN_4, GALLERY_MIN_ROW_SPAN),
        GalleryMosaicSize(GALLERY_SPAN_5, GALLERY_MIN_ROW_SPAN),
        GalleryMosaicSize(GALLERY_SPAN_5, GALLERY_SPAN_4),
        GalleryMosaicSize(GALLERY_SPAN_6, GALLERY_MIN_ROW_SPAN),
        GalleryMosaicSize(GALLERY_SPAN_6, GALLERY_SPAN_4),
        GalleryMosaicSize(GALLERY_SPAN_7, GALLERY_SPAN_4),
        GalleryMosaicSize(GALLERY_SPAN_8, GALLERY_SPAN_4),
        GalleryMosaicSize(GALLERY_SPAN_8, GALLERY_SPAN_5),
        GalleryMosaicSize(GALLERY_SPAN_9, GALLERY_SPAN_5),
        GalleryMosaicSize(GALLERY_SPAN_10, GALLERY_SPAN_4),
        GalleryMosaicSize(GALLERY_SPAN_10, GALLERY_SPAN_5),
        GalleryMosaicSize(GALLERY_SPAN_11, GALLERY_SPAN_5),
        GalleryMosaicSize(GALLERY_SPAN_12, GALLERY_SPAN_5),
        GalleryMosaicSize(GALLERY_SPAN_12, GALLERY_SPAN_6),
    )

fun planGalleryMosaicLayout(
    memos: List<GalleryLayoutInput>,
    aspectByMemoId: Map<String, Float>,
): List<GalleryMosaicLayout> {
    val remainingTiles =
        memos
            .mapIndexed { index, memo ->
                memo.toGalleryMosaicPlanningTile(aspectByMemoId, index)
            }.toMutableList()
    val layouts = mutableListOf<GalleryMosaicLayout>()

    while (remainingTiles.isNotEmpty()) {
        val layout = planGalleryMosaicBand(remainingTiles)
        if (layout.tiles.isNotEmpty()) {
            layouts += layout
        }
    }

    return layouts
}

private fun planGalleryMosaicBand(remainingTiles: MutableList<GalleryMosaicPlanningTile>): GalleryMosaicLayout {
    val occupancy = GalleryMosaicOccupancy(GALLERY_MOSAIC_COLUMN_COUNT)
    val plannedTiles = mutableListOf<GalleryMosaicTile>()

    while (remainingTiles.isNotEmpty() && plannedTiles.size < GALLERY_MAX_BAND_TILE_COUNT) {
        val placement =
            occupancy.bestPlacement(
                remainingTiles = remainingTiles,
                plannedTiles = plannedTiles,
            ) ?: break
        occupancy.place(
            column = placement.column,
            row = placement.row,
            size = placement.size,
        )
        remainingTiles.removeAt(placement.remainingIndex)
        plannedTiles +=
            placement.tile.toGalleryMosaicTile(
                column = placement.column,
                row = placement.row,
                columnSpan = placement.size.columnSpan,
                rowSpan = placement.size.rowSpan,
            )
    }

    return GalleryMosaicLayout(
        tiles = plannedTiles.toPersistentList(),
        rowCount = occupancy.rowCount,
    )
}

private fun GalleryMosaicOccupancy.bestPlacement(
    remainingTiles: List<GalleryMosaicPlanningTile>,
    plannedTiles: List<GalleryMosaicTile>,
): GalleryMosaicPlacement? {
    val currentSealedRows = metrics().sealedRowCount
    val placementComparator =
        compareBy<GalleryMosaicPlacement> { placement -> placement.score }
            .thenBy { placement -> placement.row }
            .thenBy { placement -> placement.column }
            .thenBy { placement -> placement.remainingIndex }
    val lookaheadTileCount =
        if (remainingTiles.firstOrNull()?.requiresAspectProtectedPlacement == true) {
            GALLERY_SINGLE_STEP
        } else {
            GALLERY_LOOKAHEAD_TILE_COUNT
        }

    return remainingTiles
        .asSequence()
        .take(lookaheadTileCount)
        .flatMapIndexed { remainingIndex, tile ->
            val completesCurrentBand =
                remainingTiles.size == GALLERY_SINGLE_STEP ||
                    plannedTiles.size + GALLERY_SINGLE_STEP >= GALLERY_MAX_BAND_TILE_COUNT
            tile
                .sizeCandidates()
                .asSequence()
                .flatMap { size ->
                    placements(
                        size = size,
                        allowUnreadableOpenGaps = completesCurrentBand,
                    ).asSequence().map { candidate ->
                        candidate.toGalleryMosaicPlacement(
                            tile = tile,
                            remainingIndex = remainingIndex,
                            size = size,
                            currentSealedRows = currentSealedRows,
                            plannedTiles = plannedTiles,
                        )
                    }
                }
        }.minWithOrNull(placementComparator)
}

private fun GalleryMosaicOccupancyPlacement.toGalleryMosaicPlacement(
    tile: GalleryMosaicPlanningTile,
    remainingIndex: Int,
    size: GalleryMosaicSize,
    currentSealedRows: Int,
    plannedTiles: List<GalleryMosaicTile>,
): GalleryMosaicPlacement {
    val sealedRowsGained = max(0, metrics.sealedRowCount - currentSealedRows)
    val shapeScore = tile.shapeScore(size)
    val recentShapeCount =
        plannedTiles
            .takeLast(GALLERY_LOOKAHEAD_TILE_COUNT)
            .count { plannedTile ->
                plannedTile.columnSpan == size.columnSpan && plannedTile.rowSpan == size.rowSpan
            }
    val repeatShapePenalty =
        if (plannedTiles.lastOrNull()?.let { tile ->
                tile.columnSpan == size.columnSpan && tile.rowSpan == size.rowSpan
            } == true
        ) {
            GALLERY_REPEAT_SHAPE_PENALTY
        } else {
            0
        }

    return GalleryMosaicPlacement(
        tile = tile,
        remainingIndex = remainingIndex,
        column = column,
        row = row,
        size = size,
        score =
            metrics.sealedGapCount * GALLERY_SEALED_GAP_PENALTY +
                metrics.unreadableOpenGapCount * GALLERY_SEALED_GAP_PENALTY +
                metrics.openGapCount * GALLERY_OPEN_GAP_PENALTY +
                metrics.rowCount * GALLERY_HEIGHT_PENALTY +
                row * GALLERY_ROW_PENALTY +
                remainingIndex * GALLERY_LOOKAHEAD_PENALTY +
                shapeScore * GALLERY_SHAPE_SCORE_WEIGHT +
                tile.aspectProtectedMismatchPenalty(size) +
                recentShapeCount * GALLERY_RECENT_SHAPE_PENALTY +
                repeatShapePenalty +
                column * GALLERY_COLUMN_TIE_BREAKER -
                sealedRowsGained * GALLERY_SEALED_ROW_REWARD,
    )
}

private class GalleryMosaicOccupancy(
    private val columnCount: Int,
) {
    private val rows = mutableListOf<BooleanArray>()

    val rowCount: Int
        get() = rows.indexOfLast { row -> row.any { isOccupied -> isOccupied } } + GALLERY_SINGLE_STEP

    fun placements(
        size: GalleryMosaicSize,
        allowUnreadableOpenGaps: Boolean,
    ): List<GalleryMosaicOccupancyPlacement> {
        val gap = firstGap()
        val metrics = metricsAfter(gap.column, gap.row, size)
        return if (
            canPlace(gap.column, gap.row, size) &&
            (allowUnreadableOpenGaps || metrics.unreadableOpenGapCount == 0)
        ) {
            listOf(
                GalleryMosaicOccupancyPlacement(
                    column = gap.column,
                    row = gap.row,
                    metrics = metrics,
                ),
            )
        } else {
            emptyList()
        }
    }

    fun place(
        column: Int,
        row: Int,
        size: GalleryMosaicSize,
    ) {
        ensureRows(row + size.rowSpan)
        for (targetRow in row until row + size.rowSpan) {
            for (targetColumn in column until column + size.columnSpan) {
                rows[targetRow][targetColumn] = true
            }
        }
    }

    fun metrics(): GalleryMosaicOccupancyMetrics =
        metricsAfter(
            column = 0,
            row = 0,
            size = GalleryMosaicSize(columnSpan = 0, rowSpan = 0),
        )

    private fun canPlace(
        column: Int,
        row: Int,
        size: GalleryMosaicSize,
    ): Boolean {
        if (column + size.columnSpan > columnCount) return false
        for (targetRow in row until row + size.rowSpan) {
            for (targetColumn in column until column + size.columnSpan) {
                if (isOccupied(targetRow, targetColumn)) return false
            }
        }
        return true
    }

    private fun firstGap(): GalleryMosaicCell {
        for (row in 0..rowCount) {
            for (column in 0 until columnCount) {
                if (!isOccupied(row, column)) {
                    return GalleryMosaicCell(column = column, row = row)
                }
            }
        }
        return GalleryMosaicCell(column = 0, row = rowCount)
    }

    private fun metricsAfter(
        column: Int,
        row: Int,
        size: GalleryMosaicSize,
    ): GalleryMosaicOccupancyMetrics {
        val nextRowCount = max(rowCount, row + size.rowSpan)
        val columnHeights =
            IntArray(columnCount) { targetColumn ->
                var lastOccupiedRow = -1
                for (targetRow in 0 until nextRowCount) {
                    if (isOccupiedAfter(column, row, size, targetColumn, targetRow)) {
                        lastOccupiedRow = targetRow
                    }
                }
                lastOccupiedRow + GALLERY_SINGLE_STEP
            }
        val sealedRowCount = columnHeights.minOrNull() ?: 0
        var sealedGapCount = 0
        var openGapCount = 0
        var unreadableOpenGapCount = 0

        for (targetRow in 0 until nextRowCount) {
            var openRunLength = 0
            for (targetColumn in 0 until columnCount) {
                if (!isOccupiedAfter(column, row, size, targetColumn, targetRow)) {
                    if (targetRow < sealedRowCount) {
                        sealedGapCount += GALLERY_SINGLE_STEP
                    } else {
                        openGapCount += GALLERY_SINGLE_STEP
                        openRunLength += GALLERY_SINGLE_STEP
                    }
                } else {
                    unreadableOpenGapCount += openRunLength.unreadableOpenGapCount()
                    openRunLength = 0
                }
            }
            unreadableOpenGapCount += openRunLength.unreadableOpenGapCount()
        }

        return GalleryMosaicOccupancyMetrics(
            rowCount = nextRowCount,
            sealedRowCount = sealedRowCount,
            sealedGapCount = sealedGapCount,
            openGapCount = openGapCount,
            unreadableOpenGapCount = unreadableOpenGapCount,
        )
    }

    private fun isOccupiedAfter(
        placementColumn: Int,
        placementRow: Int,
        size: GalleryMosaicSize,
        targetColumn: Int,
        targetRow: Int,
    ): Boolean =
        isOccupied(targetRow, targetColumn) ||
            (
                targetColumn in placementColumn until placementColumn + size.columnSpan &&
                    targetRow in placementRow until placementRow + size.rowSpan
            )

    private fun isOccupied(
        row: Int,
        column: Int,
    ): Boolean =
        row in rows.indices && rows[row][column]

    private fun ensureRows(rowCount: Int) {
        while (rows.size < rowCount) {
            rows += BooleanArray(columnCount)
        }
    }
}

private fun GalleryMosaicPlanningTile.sizeCandidates(): List<GalleryMosaicSize> {
    val aspect = aspectRatio.coerceIn(GALLERY_MIN_ASPECT_FOR_SHAPE, GALLERY_MAX_ASPECT_FOR_SHAPE)
    val areaCandidates =
        listOf(
            preferredArea - GALLERY_AREA_CANDIDATE_SMALL_DELTA,
            preferredArea,
            preferredArea + GALLERY_AREA_CANDIDATE_SMALL_DELTA,
            preferredArea + GALLERY_AREA_CANDIDATE_LARGE_DELTA,
        )
    val formulaCandidates =
        areaCandidates.map { area ->
            val safeArea = max(GALLERY_MIN_COLUMN_SPAN * GALLERY_MIN_ROW_SPAN, area)
            GalleryMosaicSize(
                columnSpan =
                    sqrt(safeArea * aspect)
                        .roundToInt()
                        .coerceIn(GALLERY_MIN_COLUMN_SPAN, GALLERY_MAX_COLUMN_SPAN),
                rowSpan =
                    sqrt(safeArea / aspect)
                        .roundToInt()
                        .coerceIn(GALLERY_MIN_ROW_SPAN, GALLERY_MAX_ROW_SPAN),
            )
        }
    val shapedCandidates =
        when (aspectKind) {
            GalleryAspectKind.Portrait -> galleryPortraitSizeCandidates
            GalleryAspectKind.Square -> gallerySquareSizeCandidates
            GalleryAspectKind.Landscape -> galleryLandscapeSizeCandidates
        }

    return (formulaCandidates + shapedCandidates)
        .asSequence()
        .filter { size -> size.matchesAspectKind(aspectKind) }
        .filter { size -> abs(aspect - size.aspectRatio) <= GALLERY_MAX_LAYOUT_ASPECT_DELTA }
        .distinct()
        .sortedBy { size -> shapeScore(size) }
        .toList()
}

private fun GalleryMosaicSize.matchesAspectKind(aspectKind: GalleryAspectKind): Boolean =
    when (aspectKind) {
        GalleryAspectKind.Portrait -> columnSpan < rowSpan
        GalleryAspectKind.Square -> columnSpan == rowSpan
        GalleryAspectKind.Landscape -> columnSpan > rowSpan
    }

private fun GalleryMosaicPlanningTile.shapeScore(size: GalleryMosaicSize): Int {
    val aspectScore =
        (abs(aspectRatio.coerceIn(GALLERY_MIN_ASPECT_FOR_SHAPE, GALLERY_MAX_ASPECT_FOR_SHAPE) - size.aspectRatio) *
            GALLERY_ASPECT_SCORE_SCALE).roundToInt()
    val areaScore = abs(size.area - preferredArea) * GALLERY_AREA_SCORE_WEIGHT
    val orientationPenalty =
        if (size.matchesAspectKind(aspectKind)) {
            0
        } else {
            GALLERY_ORIENTATION_MISMATCH_PENALTY
        }
    return aspectScore + areaScore + orientationPenalty
}

private fun GalleryMosaicPlanningTile.aspectProtectedMismatchPenalty(size: GalleryMosaicSize): Int =
    if (
        requiresAspectProtectedPlacement &&
        abs(aspectRatio.coerceIn(GALLERY_MIN_ASPECT_FOR_SHAPE, GALLERY_MAX_ASPECT_FOR_SHAPE) - size.aspectRatio) >
        GALLERY_ASPECT_PROTECTED_MAX_DELTA
    ) {
        GALLERY_ASPECT_PROTECTED_MISMATCH_PENALTY
    } else {
        0
    }

private val GalleryMosaicPlanningTile.preferredArea: Int
    get() =
        when (aspectKind) {
            GalleryAspectKind.Portrait ->
                when {
                    aspectRatio <= GALLERY_EXTREME_PORTRAIT_ASPECT_MAX -> GALLERY_PREFERRED_EXTREME_PORTRAIT_AREA
                    aspectRatio <= GALLERY_TALL_PORTRAIT_ASPECT_MAX -> GALLERY_PREFERRED_TALL_PORTRAIT_AREA
                    else -> GALLERY_PREFERRED_PORTRAIT_AREA
                }
            GalleryAspectKind.Landscape ->
                when {
                    aspectRatio >= GALLERY_EXTREME_LANDSCAPE_ASPECT_MIN -> GALLERY_PREFERRED_EXTREME_LANDSCAPE_AREA
                    aspectRatio >= GALLERY_WIDE_LANDSCAPE_ASPECT_MIN -> GALLERY_PREFERRED_WIDE_LANDSCAPE_AREA
                    else -> GALLERY_PREFERRED_LANDSCAPE_AREA
                }
            GalleryAspectKind.Square ->
                when {
                    inputIndex % GALLERY_HERO_SQUARE_CADENCE == 0 -> GALLERY_HERO_SQUARE_AREA
                    inputIndex % GALLERY_LARGE_SQUARE_CADENCE == 0 -> GALLERY_LARGE_SQUARE_AREA
                    else -> GALLERY_PREFERRED_SQUARE_AREA
                }
        }

private val GalleryMosaicPlanningTile.requiresAspectProtectedPlacement: Boolean
    get() =
        aspectRatio <= GALLERY_TALL_PORTRAIT_ASPECT_MAX ||
            aspectRatio >= GALLERY_WIDE_LANDSCAPE_ASPECT_MIN

private val GalleryMosaicSize.area: Int
    get() = columnSpan * rowSpan

private val GalleryMosaicSize.aspectRatio: Float
    get() = columnSpan.toFloat() / rowSpan.toFloat()

private fun GalleryLayoutInput.toGalleryMosaicPlanningTile(
    aspectByMemoId: Map<String, Float>,
    inputIndex: Int,
): GalleryMosaicPlanningTile {
    val resolvedAspect = sanitizeGalleryAspect(aspectByMemoId[memoId])
    return GalleryMosaicPlanningTile(
        memoId = memoId,
        firstImageUrl = firstImageUrl,
        aspectKind = galleryAspectKindFor(resolvedAspect),
        aspectRatio = resolvedAspect,
        inputIndex = inputIndex,
    )
}

private fun GalleryMosaicPlanningTile.toGalleryMosaicTile(
    column: Int,
    row: Int,
    columnSpan: Int,
    rowSpan: Int,
): GalleryMosaicTile =
    GalleryMosaicTile(
        memoId = memoId,
        firstImageUrl = firstImageUrl,
        aspectKind = aspectKind,
        aspectRatio = aspectRatio,
        column = column,
        row = row,
        columnSpan = columnSpan,
        rowSpan = rowSpan,
    )

private data class GalleryMosaicPlanningTile(
    val memoId: String,
    val firstImageUrl: String,
    val aspectKind: GalleryAspectKind,
    val aspectRatio: Float,
    val inputIndex: Int,
)

private data class GalleryMosaicSize(
    val columnSpan: Int,
    val rowSpan: Int,
)

private data class GalleryMosaicPlacement(
    val tile: GalleryMosaicPlanningTile,
    val remainingIndex: Int,
    val column: Int,
    val row: Int,
    val size: GalleryMosaicSize,
    val score: Int,
)

private data class GalleryMosaicCell(
    val column: Int,
    val row: Int,
)

private data class GalleryMosaicOccupancyPlacement(
    val column: Int,
    val row: Int,
    val metrics: GalleryMosaicOccupancyMetrics,
)

private data class GalleryMosaicOccupancyMetrics(
    val rowCount: Int,
    val sealedRowCount: Int,
    val sealedGapCount: Int,
    val openGapCount: Int,
    val unreadableOpenGapCount: Int,
)

private fun Int.unreadableOpenGapCount(): Int =
    if (this in GALLERY_SINGLE_STEP until GALLERY_MIN_COLUMN_SPAN) {
        this
    } else {
        0
    }
