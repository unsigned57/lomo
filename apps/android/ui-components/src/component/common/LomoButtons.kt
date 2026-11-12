package com.lomo.ui.component.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Lomo canonical action buttons.
 *
 * The Material 3 Expressive `shapes:` overloads are required parameters on the platform
 * composables, so a bare `IconButton(`/`Button(` silently resolves to the legacy static-shape
 * overload and loses press morphing. These wrappers fix the expressive shapes as the default so
 * call sites cannot opt out accidentally. Deliberate non-morphing shapes (e.g. connected
 * button-group tiles that must keep positional corners) still pass an explicit [shapes] value.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LomoIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: IconButtonShapes = IconButtonDefaults.shapes(),
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) = IconButton(
    onClick = onClick,
    shapes = shapes,
    modifier = modifier,
    enabled = enabled,
    colors = colors,
    interactionSource = interactionSource,
    content = content,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LomoFilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: IconButtonShapes = IconButtonDefaults.shapes(),
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) = FilledIconButton(
    onClick = onClick,
    shapes = shapes,
    modifier = modifier,
    enabled = enabled,
    colors = colors,
    interactionSource = interactionSource,
    content = content,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LomoFilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: IconButtonShapes = IconButtonDefaults.shapes(),
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) = FilledTonalIconButton(
    onClick = onClick,
    shapes = shapes,
    modifier = modifier,
    enabled = enabled,
    colors = colors,
    interactionSource = interactionSource,
    content = content,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LomoOutlinedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: IconButtonShapes = IconButtonDefaults.shapes(),
    colors: IconButtonColors = IconButtonDefaults.outlinedIconButtonColors(),
    border: BorderStroke? = IconButtonDefaults.outlinedIconButtonBorder(enabled),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) = OutlinedIconButton(
    onClick = onClick,
    shapes = shapes,
    modifier = modifier,
    enabled = enabled,
    colors = colors,
    border = border,
    interactionSource = interactionSource,
    content = content,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LomoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: ButtonShapes = ButtonDefaults.shapes(),
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = Button(
    onClick = onClick,
    shapes = shapes,
    modifier = modifier,
    enabled = enabled,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LomoTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: ButtonShapes = ButtonDefaults.shapes(),
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = TextButton(
    onClick = onClick,
    shapes = shapes,
    modifier = modifier,
    enabled = enabled,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LomoFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: ButtonShapes = ButtonDefaults.shapes(),
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = FilledTonalButton(
    onClick = onClick,
    shapes = shapes,
    modifier = modifier,
    enabled = enabled,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LomoOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: ButtonShapes = ButtonDefaults.shapes(),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(
    onClick = onClick,
    shapes = shapes,
    modifier = modifier,
    enabled = enabled,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LomoElevatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: ButtonShapes = ButtonDefaults.shapes(),
    colors: ButtonColors = ButtonDefaults.elevatedButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.elevatedButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = ElevatedButton(
    onClick = onClick,
    shapes = shapes,
    modifier = modifier,
    enabled = enabled,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)
