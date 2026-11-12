package com.lomo.ui.component.card

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import com.lomo.ui.text.LocalMemoTextPressFeedback
import com.lomo.ui.text.MemoTextPressFeedback
import com.lomo.ui.theme.LocalLomoMotion

@Composable
internal fun MemoCardContainer(
    isPinned: Boolean,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = if (isPinned) scheme.surfaceContainer else scheme.surfaceContainerLow,
        animationSpec = LocalLomoMotion.current.component.defaultEffectsSpec(),
        label = "MemoCardContainerColor",
    )
    val pressFeedback = remember(interactionSource) { MemoTextPressFeedback(interactionSource) }
    CompositionLocalProvider(LocalMemoTextPressFeedback provides pressFeedback) {
        Card(
            modifier = modifier.fillMaxWidth()
                .onGloballyPositioned { pressFeedback.coordinates = it }
                .clip(MemoCardTokens.ContainerShape)
                .indication(interactionSource, ripple()),
            shape = MemoCardTokens.ContainerShape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            content = content,
        )
    }
}
