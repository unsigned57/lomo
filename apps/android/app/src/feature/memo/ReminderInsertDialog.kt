package com.lomo.app.feature.memo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.Recurrence
import com.lomo.domain.repository.ReminderTokenFactory
import com.lomo.ui.component.common.LomoFilledIconButton
import com.lomo.ui.component.common.LomoFilledTonalButton
import com.lomo.ui.component.common.LomoStateContent
import com.lomo.ui.component.picker.ExpressiveDatePickerSurface
import com.lomo.ui.component.picker.ExpressivePickerStep
import com.lomo.ui.component.picker.ExpressiveSteppedPickerDialog
import com.lomo.ui.component.picker.ExpressiveTimePickerSurface
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.persistentListOf
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

private const val REMINDER_REPEAT_MIN = 1
private const val REMINDER_REPEAT_MAX = 9
private val REMINDER_INTERVAL_PRESETS = listOf(1, 5, 10, 15, 30)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderInsertDialog(
    onDismiss: () -> Unit,
    onConfirm: (token: String) -> Unit,
    tokenFactory: ReminderTokenFactory = org.koin.compose.koinInject(),
) {
    val now = remember { LocalDateTime.now().plusMinutes(5) }
    val datePickerState =
        rememberDatePickerState(
            initialSelectedDateMillis =
            now.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
    val timePickerState =
        rememberTimePickerState(
            initialHour = now.hour,
            initialMinute = now.minute,
            is24Hour = true,
        )
    var repeatCount by remember { mutableIntStateOf(REMINDER_REPEAT_MIN) }
    var recurrence by remember { mutableStateOf(Recurrence.NONE) }
    var intervalMinutes by remember { mutableIntStateOf(tokenFactory.defaultIntervalMinutes) }

    ExpressiveSteppedPickerDialog(
        title = stringResource(R.string.reminder_dialog_title),
        advanceLabel = stringResource(R.string.action_next),
        confirmLabel = stringResource(R.string.action_confirm),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = {
            val dateMillis = datePickerState.selectedDateMillis ?: return@ExpressiveSteppedPickerDialog
            val localDate = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
            onConfirm(
                buildReminderToken(
                    date = localDate,
                    hour = timePickerState.hour,
                    minute = timePickerState.minute,
                    repeatCount = repeatCount,
                    intervalMinutes = intervalMinutes,
                    recurrence = recurrence,
                    tokenFactory = tokenFactory,
                ),
            )
        },
        onDismiss = onDismiss,
        steps =
        persistentListOf(
            ExpressivePickerStep { ExpressiveDatePickerSurface(state = datePickerState) },
            ExpressivePickerStep { ExpressiveTimePickerSurface(state = timePickerState) },
            ExpressivePickerStep {
                ReminderRepeatPage(
                    repeatCount = repeatCount,
                    onRepeatCountChange = { repeatCount = it },
                    recurrence = recurrence,
                    onRecurrenceChange = { recurrence = it },
                    intervalMinutes = intervalMinutes,
                    onIntervalMinutesChange = { intervalMinutes = it },
                )
            },
        ),
    )
}

@Composable
private fun ReminderRepeatPage(
    repeatCount: Int,
    onRepeatCountChange: (Int) -> Unit,
    recurrence: Recurrence,
    onRecurrenceChange: (Recurrence) -> Unit,
    intervalMinutes: Int,
    onIntervalMinutesChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.reminder_dialog_recurrence_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Start),
        )
        val options = listOf(
            Recurrence.NONE to R.string.reminder_dialog_recurrence_once,
            Recurrence.DAILY to R.string.reminder_dialog_recurrence_daily,
            Recurrence.WEEKLY to R.string.reminder_dialog_recurrence_weekly,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, labelRes) ->
                SegmentedButton(
                    selected = recurrence == value,
                    onClick = { onRecurrenceChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(labelRes))
                }
            }
        }

        RepeatStepper(value = repeatCount, onValueChanged = onRepeatCountChange)

        val layoutMotion = LocalLomoMotion.current.layout
        AnimatedVisibility(
            visible = repeatCount > 1,
            enter = expandVertically(layoutMotion.defaultSpatialSpec()) + fadeIn(layoutMotion.defaultEffectsSpec()),
            exit = shrinkVertically(layoutMotion.defaultSpatialSpec()) + fadeOut(layoutMotion.fastEffectsSpec()),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.reminder_dialog_interval_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Start),
                )
                val intervalLayoutSpec = ReminderIntervalChoiceLayoutPolicy.spec()
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                    Arrangement.spacedBy(
                        space = intervalLayoutSpec.horizontalSpacing,
                        alignment = Alignment.CenterHorizontally,
                    ),
                    verticalArrangement = Arrangement.spacedBy(intervalLayoutSpec.verticalSpacing),
                ) {
                    REMINDER_INTERVAL_PRESETS.forEach { minutes ->
                        ReminderIntervalChoiceButton(
                            minutes = minutes,
                            selected = intervalMinutes == minutes,
                            layoutSpec = intervalLayoutSpec,
                            onClick = { onIntervalMinutesChange(minutes) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReminderIntervalChoiceButton(
    minutes: Int,
    selected: Boolean,
    layoutSpec: ReminderIntervalChoiceLayoutSpec,
    onClick: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    val componentMotion = LocalLomoMotion.current.component
    val containerColor by animateColorAsState(
        targetValue =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "ReminderIntervalContainer",
    )
    val contentColor by animateColorAsState(
        targetValue =
        if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "ReminderIntervalContent",
    )

    LomoFilledTonalButton(
        onClick = {
            haptic.medium()
            onClick()
        },
        shapes = ButtonShapes(shape = AppShapes.Small, pressedShape = AppShapes.ExtraSmall),
        colors =
        ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(width = layoutSpec.choiceWidth, height = layoutSpec.choiceHeight),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.reminder_dialog_interval_unit, minutes),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun RepeatStepper(value: Int, onValueChanged: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(AppSpacing.Medium),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        Text(
            text = stringResource(R.string.reminder_dialog_repeat_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            LomoFilledIconButton(
                onClick = { onValueChanged((value - 1).coerceAtLeast(REMINDER_REPEAT_MIN)) },
                enabled = value > REMINDER_REPEAT_MIN,
            ) {
                LomoIcon(LomoIcons.Remove, contentDescription = null)
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = AppShapes.Small,
                modifier = Modifier.size(width = 72.dp, height = 48.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    LomoStateContent(
                        state = value,
                        contentKey = { it },
                    ) { count ->
                        Text(
                            text = "$count",
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                        )
                    }
                }
            }
            LomoFilledIconButton(
                onClick = { onValueChanged((value + 1).coerceAtMost(REMINDER_REPEAT_MAX)) },
                enabled = value < REMINDER_REPEAT_MAX,
            ) {
                LomoIcon(LomoIcons.Add, contentDescription = null)
            }
        }
        Text(
            text = stringResource(R.string.reminder_dialog_repeat_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(0.dp))
    }
}

internal fun buildReminderInsertionValue(inputValue: TextFieldValue, token: String): TextFieldValue {
    val cursor = inputValue.selection.start.coerceIn(0, inputValue.text.length)
    val prefix = inputValue.text.substring(0, cursor)
    val suffix = inputValue.text.substring(cursor)
    val needsLeadingSpace = prefix.isNotEmpty() && !prefix.last().isWhitespace()
    val insertion = if (needsLeadingSpace) " $token" else token
    val newText = prefix + insertion + suffix
    val cursorTarget = cursor + insertion.length
    return TextFieldValue(newText, TextRange(cursorTarget))
}

internal fun buildReminderToken(
    date: LocalDate,
    hour: Int,
    minute: Int,
    repeatCount: Int,
    intervalMinutes: Int,
    recurrence: Recurrence,
    tokenFactory: ReminderTokenFactory,
): String = tokenFactory.buildInsertToken(
    dueAt = LocalDateTime.of(date, LocalTime.of(hour, minute)),
    repeatCount = repeatCount,
    intervalMinutes = intervalMinutes,
    recurrence = recurrence,
)
