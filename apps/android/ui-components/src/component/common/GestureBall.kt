package com.lomo.ui.component.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.QuickBallGesture
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableMap

/** A fixed-footprint Material FAB with directional gestures and keyboard/accessibility actions. */
@Composable
fun GestureBall(
    labels: ImmutableMap<QuickBallGesture, String>,
    icons: ImmutableMap<QuickBallGesture, ImageVector>,
    onGesture: (QuickBallGesture) -> Unit,
    modifier: Modifier = Modifier,
    appearanceFraction: Float = 1f,
) {
    val haptic = LocalAppHapticFeedback.current
    val latestHaptic by rememberUpdatedState(haptic)
    val latestOnGesture by rememberUpdatedState(onGesture)
    val commitGesture: (QuickBallGesture) -> Unit = { gesture ->
        latestHaptic.medium()
        latestOnGesture(gesture)
    }
    val latestCommitGesture by rememberUpdatedState(commitGesture)
    var candidate by remember { mutableStateOf<QuickBallGesture?>(null) }
    var pressed by remember { mutableStateOf(false) }
    var displacement by remember { mutableStateOf(Offset.Zero) }

    val motion = LocalLomoMotion.current.component
    val ballShape = FloatingActionButtonDefaults.shape
    val maximumTravel = with(LocalDensity.current) { 24.dp.toPx() }
    val scale by animateFloatAsState(
        targetValue = if (pressed) BALL_PRESSED_SCALE else 1f,
        animationSpec = motion.fastSpatialSpec(),
        label = "FabPressScale",
    )
    val travel by animateOffsetAsState(
        targetValue = Offset(
            displacement.x.coerceIn(-maximumTravel, maximumTravel),
            displacement.y.coerceIn(-maximumTravel, maximumTravel),
        ),
        animationSpec = motion.fastSpatialSpec(),
        label = "FabGestureTravel",
    )
    Box(modifier.size(56.dp), contentAlignment = Alignment.Center) {
        FloatingActionButton(
            onClick = { latestCommitGesture(QuickBallGesture.TAP) },
            modifier = Modifier
                .size(56.dp)
                .semantics {
                    role = Role.Button
                    onLongClick(labels.getValue(QuickBallGesture.LONG_PRESS)) {
                        latestCommitGesture(QuickBallGesture.LONG_PRESS)
                        true
                    }
                    customActions = QuickBallGesture.entries.filter { it != QuickBallGesture.TAP }.map { gesture ->
                        CustomAccessibilityAction(labels.getValue(gesture)) {
                            latestCommitGesture(gesture)
                            true
                        }
                    }
                }
                .pointerInput(Unit) {
                    recognizeBallGestures(
                        onPress = { pressed = it },
                        onDisplacement = { displacement = it },
                        onCandidate = { next ->
                            if (next != null && next != candidate) {
                                if (next == QuickBallGesture.LONG_PRESS) {
                                    latestHaptic.longPress()
                                } else {
                                    latestHaptic.light()
                                }
                            }
                            candidate = next
                        },
                        onGesture = { latestCommitGesture(it) },
                    )
                }
                .graphicsLayer {
                    val opacity = appearanceFraction.coerceIn(0f, 1f)
                    shadowElevation = 6.dp.toPx() * opacity
                    shape = ballShape
                    alpha = opacity
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    scaleX = scale * (BALL_ENTER_SCALE + (1f - BALL_ENTER_SCALE) * appearanceFraction)
                    scaleY = scaleX
                    translationX = travel.x * BALL_DRAG_FOLLOW_FRACTION
                    translationY = travel.y * BALL_DRAG_FOLLOW_FRACTION
                },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp,
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.animation.Crossfade(
                    targetState = candidate ?: QuickBallGesture.TAP,
                    animationSpec = motion.fastEffectsSpec(),
                    label = "FabGestureIcon",
                ) { gesture ->
                    Icon(
                        icons.getValue(gesture),
                        contentDescription = labels.getValue(QuickBallGesture.TAP),
                    )
                }
            }
        }
    }
}

private const val BALL_ENTER_SCALE = 0.8f

private suspend fun PointerInputScope.recognizeBallGestures(
    onPress: (Boolean) -> Unit,
    onDisplacement: (Offset) -> Unit,
    onCandidate: (QuickBallGesture?) -> Unit,
    onGesture: (QuickBallGesture) -> Unit,
) {
    val threshold = maxOf(24.dp.toPx(), viewConfiguration.touchSlop)
    awaitEachGesture {
        // Own physical input before Surface's clickable; its keyboard/a11y actions stay intact.
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        down.consume()
        val timeout = viewConfiguration.longPressTimeoutMillis
        val session = GestureBallSession(viewConfiguration.touchSlop, threshold, timeout)
        var elapsed = 0L
        var displacement = Offset.Zero
        var timerElapsed = false
        onPress(true)
        try {
            while (true) {
                val event = if (timerElapsed) {
                    awaitPointerEvent(PointerEventPass.Initial)
                } else {
                    withTimeoutOrNull((timeout - elapsed).coerceAtLeast(1L)) {
                        awaitPointerEvent(PointerEventPass.Initial)
                    }
                }
                if (event == null) {
                    timerElapsed = true
                    elapsed = timeout
                    onCandidate(session.update(displacement.x, displacement.y, elapsed))
                    continue
                }
                val change = event.changes.singleOrNull()
                if (change == null || change.id != down.id || change.isConsumed) {
                    session.cancel()
                    return@awaitEachGesture
                }
                elapsed = maxOf(elapsed, change.uptimeMillis - down.uptimeMillis)
                displacement = change.position - down.position
                onDisplacement(displacement)
                onCandidate(session.update(displacement.x, displacement.y, elapsed))
                change.consume()
                if (!change.pressed) {
                    session.release()?.let(onGesture)
                    return@awaitEachGesture
                }
            }
        } finally {
            session.cancel()
            onPress(false)
            onDisplacement(Offset.Zero)
            onCandidate(null)
        }
    }
}

private const val BALL_PRESSED_SCALE = 0.94f
private const val BALL_DRAG_FOLLOW_FRACTION = 0.4f
