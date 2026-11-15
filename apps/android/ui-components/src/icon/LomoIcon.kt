package com.lomo.ui.icon

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.material_symbols_rounded
import dev.tclement.fonticons.ExperimentalFontIconsApi
import dev.tclement.fonticons.FontIcon
import dev.tclement.fonticons.VariableIconFont
import dev.tclement.fonticons.rememberVariableIconFont

/**
 * Descriptor for an icon rendered from the bundled Google Material Symbols Rounded variable font.
 *
 * @property glyphName Official Material Symbols ligature name in `material_symbols_rounded.ttf`.
 * @property name Canonical Kotlin property name for diagnostics and tests.
 * @property filled Whether to select the `FILL = 1` font variation axis.
 * @property autoMirror Whether to mirror horizontally in RTL layouts.
 */
@Immutable
data class LomoIconSpec(
    val glyphName: String,
    val name: String,
    val filled: Boolean = false,
    val autoMirror: Boolean = false,
)

object LomoIconDefaults {
    val OutlinedWeight: FontWeight = FontWeight(350)
    val FilledWeight: FontWeight = FontWeight.Normal

    val SupportedWeights: Array<FontWeight> =
        arrayOf(
            FontWeight(300),
            FontWeight(350),
            FontWeight.Normal,
            FontWeight.Medium,
            FontWeight.SemiBold,
            FontWeight.Bold,
        )

    const val VARIATION_FILL_OUTLINED = 0f
    const val VARIATION_FILL_FILLED = 1f
    const val VARIATION_GRADE_DEFAULT = 0f
}

@Immutable
class LomoIconFontSet(val outlined: VariableIconFont, val filled: VariableIconFont)

val LocalLomoIconFontSet = staticCompositionLocalOf<LomoIconFontSet?> { null }

@OptIn(ExperimentalFontIconsApi::class)
@Composable
fun rememberLomoIconFontSet(): LomoIconFontSet {
    val outlined =
        rememberVariableIconFont(
            fontResource = Res.font.material_symbols_rounded,
            weights = LomoIconDefaults.SupportedWeights,
            fontVariationSettings =
            FontVariation.Settings(
                FontVariation.Setting("FILL", LomoIconDefaults.VARIATION_FILL_OUTLINED),
                FontVariation.Setting("GRAD", LomoIconDefaults.VARIATION_GRADE_DEFAULT),
            ),
        )
    val filled =
        rememberVariableIconFont(
            fontResource = Res.font.material_symbols_rounded,
            weights = LomoIconDefaults.SupportedWeights,
            fontVariationSettings =
            FontVariation.Settings(
                FontVariation.Setting("FILL", LomoIconDefaults.VARIATION_FILL_FILLED),
                FontVariation.Setting("GRAD", LomoIconDefaults.VARIATION_GRADE_DEFAULT),
            ),
        )
    return remember(outlined, filled) {
        LomoIconFontSet(outlined = outlined, filled = filled)
    }
}

/**
 * Renders a [LomoIconSpec] using Google Material Symbols Rounded variable font axes.
 */
@Composable
fun LomoIcon(
    icon: LomoIconSpec,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    weight: FontWeight? = null,
) {
    val fontSet = LocalLomoIconFontSet.current ?: rememberLomoIconFontSet()
    val targetFont = if (icon.filled) fontSet.filled else fontSet.outlined
    val resolvedWeight =
        weight ?: if (icon.filled) LomoIconDefaults.FilledWeight else LomoIconDefaults.OutlinedWeight
    val shouldMirror = icon.autoMirror && LocalLayoutDirection.current == LayoutDirection.Rtl
    val resolvedModifier =
        if (shouldMirror) {
            modifier.scale(scaleX = -1f, scaleY = 1f)
        } else {
            modifier
        }

    FontIcon(
        iconName = icon.glyphName,
        contentDescription = contentDescription,
        modifier = resolvedModifier,
        tint = tint,
        weight = resolvedWeight,
        iconFont = targetFont,
    )
}
