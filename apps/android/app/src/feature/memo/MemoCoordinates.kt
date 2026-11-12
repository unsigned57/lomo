package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.math.BigDecimal

private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

internal sealed interface MemoCoordinateInput {
    data object Incomplete : MemoCoordinateInput
    data object InvalidLatitude : MemoCoordinateInput
    data object InvalidLongitude : MemoCoordinateInput
    data class Valid(val coordinates: MemoCoordinates) : MemoCoordinateInput
}

/** Only finite WGS84 coordinates can cross the editor insertion boundary. */
internal class MemoCoordinates private constructor(val latitude: Double, val longitude: Double) {
    val latitudeText: String get() = BigDecimal.valueOf(latitude).stripTrailingZeros().toPlainString()
    val longitudeText: String get() = BigDecimal.valueOf(longitude).stripTrailingZeros().toPlainString()
    val markdown: String get() = "[$latitudeText, $longitudeText](geo:$latitudeText,$longitudeText)"

    companion object {
        fun parse(latitude: String, longitude: String): MemoCoordinateInput {
            if (latitude.isBlank() || longitude.isBlank()) return MemoCoordinateInput.Incomplete
            val lat = latitude.trim().toDoubleOrNull()
            if (lat == null || !lat.isFinite() || lat !in -MAX_LATITUDE..MAX_LATITUDE) {
                return MemoCoordinateInput.InvalidLatitude
            }
            val lon = longitude.trim().toDoubleOrNull()
            if (lon == null || !lon.isFinite() || lon !in -MAX_LONGITUDE..MAX_LONGITUDE) {
                return MemoCoordinateInput.InvalidLongitude
            }
            return MemoCoordinateInput.Valid(MemoCoordinates(lat, lon))
        }
    }
}

internal fun buildMemoCoordinateInsertionValue(value: TextFieldValue, coordinates: MemoCoordinates): TextFieldValue {
    val start = value.selection.min
    val token = coordinates.markdown
    return TextFieldValue(
        value.text.replaceRange(start, value.selection.max, token),
        TextRange(start + token.length),
    )
}
