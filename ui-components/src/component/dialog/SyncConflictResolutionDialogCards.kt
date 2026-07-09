package com.lomo.ui.component.dialog

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictAutoResolutionAdvisor
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncReviewAutoResolutionAdvisor
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

@Composable
internal fun ConflictFileCard(
    source: SyncBackendType,
    file: SyncConflictFile,
    choice: SyncConflictResolutionChoice?,
    suggestedChoice: SyncConflictResolutionChoice?,
    supportsSkip: Boolean,
    isExpanded: Boolean,
    reviewMessage: String?,
    onChoiceChanged: (SyncConflictResolutionChoice) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    val mergedText =
        remember(file.localContent, file.remoteContent, file.isBinary) {
            SyncConflictAutoResolutionAdvisor.mergedText(file)
        }
    val mergeAvailable = mergedText != null && mergedText != file.localContent && mergedText != file.remoteContent

    ConflictReviewCard(choiceSelected = choice != null, label = "cardColor") {
        ConflictFileHeader(source, file, isExpanded, suggestedChoice, onToggleExpanded)
        reviewMessage?.let { message ->
            Spacer(modifier = Modifier.height(AppSpacing.Small))
            ReviewMessage(message = message)
        }
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
        CustomChoiceToggle(source, choice, mergeAvailable, supportsSkip, onChoiceChanged)
        ConflictDiffSection(file, isExpanded, mergedText, mergeAvailable)
    }
}

@Composable
internal fun ReviewFileCard(
    source: SyncBackendType,
    item: SyncReviewItem,
    choice: SyncReviewResolutionChoice?,
    suggestedChoice: SyncReviewResolutionChoice?,
    supportsSkip: Boolean,
    isExpanded: Boolean,
    onChoiceChanged: (SyncReviewResolutionChoice) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    val mergedText =
        remember(item.localContent, item.incomingContent, item.isBinary) {
            SyncReviewAutoResolutionAdvisor.mergedText(item)
        }
    val mergeAvailable = mergedText != null && mergedText != item.localContent && mergedText != item.incomingContent

    ConflictReviewCard(choiceSelected = choice != null, label = "reviewCardColor") {
        ReviewFileHeader(source, item, isExpanded, suggestedChoice, onToggleExpanded)
        item.message?.let { message ->
            Spacer(modifier = Modifier.height(AppSpacing.Small))
            ReviewMessage(message = message)
        }
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
        ReviewChoiceToggle(source, choice, mergeAvailable, supportsSkip, onChoiceChanged)
        ReviewDiffSection(item, isExpanded, mergedText, mergeAvailable)
    }
}

@Composable
private fun ConflictReviewCard(
    choiceSelected: Boolean,
    label: String,
    content: @Composable () -> Unit,
) {
    val cardColor by animateColorAsState(
        targetValue = if (choiceSelected) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = label,
    )

    Card(
        shape = AppShapes.ExtraLarge,
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.animateContentSize(),
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Medium)) {
            content()
        }
    }
}
