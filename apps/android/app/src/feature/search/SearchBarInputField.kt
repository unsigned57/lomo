package com.lomo.app.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.LocalAppHapticFeedback

/** Material 3 Expressive pill search input field with integrated leading Back and trailing Clear/Filter controls. */
@Composable
internal fun SearchBarInputField(
    query: String,
    inputState: TextFieldState,
    inputScroll: ScrollState,
    expanded: Boolean,
    interactive: Boolean,
    onQueryChanged: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenFilter: () -> Unit = {},
    isFilterActive: Boolean = false,
) {
    val haptic = LocalAppHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val canClear = interactive && query.isNotEmpty()
    TextField(
        state = inputState,
        inputTransformation = InputTransformation { onQueryChanged(asCharSequence().toString()) },
        lineLimits = TextFieldLineLimits.SingleLine,
        scrollState = inputScroll,
        readOnly = !interactive,
        modifier = modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        },
        placeholder = {
            Text(
                stringResource(R.string.search_hint),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            if (expanded) {
                LomoIconButton(onClick = {
                    haptic.medium()
                    onBack()
                }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                }
            } else {
                Icon(Icons.Rounded.Search, null)
            }
        },
        trailingIcon = {
            SearchBarTrailingIconsContent(
                canClear = canClear,
                isFilterActive = isFilterActive,
                onClear = {
                    haptic.light()
                    inputState.edit { replace(0, length, "") }
                    onQueryChanged("")
                },
                onOpenFilter = onOpenFilter,
            )
        },
        shape = AppShapes.Full,
        colors =
        TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SearchBarTrailingIconsContent(
    canClear: Boolean,
    isFilterActive: Boolean,
    onClear: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    val componentMotion = LocalLomoMotion.current.component
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            visible = canClear,
            enter = fadeIn(componentMotion.fastEffectsSpec()),
            exit = fadeOut(componentMotion.fastEffectsSpec()),
        ) {
            LomoIconButton(
                onClick = onClear,
                modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.SEARCH_CLEAR),
            ) {
                Icon(Icons.Rounded.Close, stringResource(R.string.cd_clear_search))
            }
        }
        Box {
            LomoIconButton(onClick = onOpenFilter) {
                Icon(
                    imageVector = Icons.Rounded.FilterList,
                    contentDescription =
                    stringResource(
                        if (isFilterActive) {
                            R.string.search_filter_active_cd
                        } else {
                            R.string.search_filter_open
                        },
                    ),
                    tint =
                    if (isFilterActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (isFilterActive) {
                Box(
                    modifier =
                    Modifier
                        .padding(top = 8.dp, end = 8.dp)
                        .size(7.dp)
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}
