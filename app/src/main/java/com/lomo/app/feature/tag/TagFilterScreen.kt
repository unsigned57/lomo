package com.lomo.app.feature.tag

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.ui.component.card.MemoCard
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.menu.MemoMenuHost
import com.lomo.ui.util.formatAsDateTime
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TagFilterScreen(
    tagName: String,
    onBackClick: () -> Unit,
    onNavigateToImage: (String) -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    viewModel: TagFilterViewModel = hiltViewModel(),
) {
    val memos by viewModel.uiMemos.collectAsStateWithLifecycle()
    val dateFormat by viewModel.dateFormat.collectAsStateWithLifecycle()
    val timeFormat by viewModel.timeFormat.collectAsStateWithLifecycle()
    val shareCardStyle by viewModel.shareCardStyle.collectAsStateWithLifecycle()
    val shareCardShowTime by viewModel.shareCardShowTime.collectAsStateWithLifecycle()
    val doubleTapEditEnabled by viewModel.doubleTapEditEnabled.collectAsStateWithLifecycle()
    val activeDayCount by viewModel.activeDayCount.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDir.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val context = LocalContext.current

    var showInputSheet by remember { mutableStateOf(false) }
    var editingMemo by remember { mutableStateOf<com.lomo.domain.model.Memo?>(null) }
    var inputText by remember {
        mutableStateOf(
            androidx.compose.ui.text.input
                .TextFieldValue(""),
        )
    }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun appendImageMarkdown(path: String) {
        val markdown = "![image]($path)"
        val current = inputText.text
        val newText = if (current.isEmpty()) markdown else "$current\n$markdown"
        inputText =
            androidx.compose.ui.text.input
                .TextFieldValue(
                    newText,
                    androidx.compose.ui.text
                        .TextRange(newText.length),
                )
    }

    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { viewModel.saveImage(it, ::appendImageMarkdown) }
        }

    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { isSuccess ->
            val file = pendingCameraFile
            val uri = pendingCameraUri
            if (isSuccess && uri != null) {
                viewModel.saveImage(
                    uri = uri,
                    onResult = { path ->
                        appendImageMarkdown(path)
                        runCatching { file?.delete() }
                        pendingCameraFile = null
                        pendingCameraUri = null
                    },
                    onError = {
                        runCatching { file?.delete() }
                        pendingCameraFile = null
                        pendingCameraUri = null
                    },
                )
            } else {
                runCatching { file?.delete() }
                pendingCameraFile = null
                pendingCameraUri = null
            }
        }

    MemoMenuHost(
        onEdit = { state ->
            val memo = state.memo as? com.lomo.domain.model.Memo
            if (memo != null) {
                editingMemo = memo
                inputText =
                    androidx.compose.ui.text.input
                        .TextFieldValue(
                            memo.content,
                            androidx.compose.ui.text
                                .TextRange(memo.content.length),
                        )
                showInputSheet = true
            }
        },
        onDelete = { state ->
            val memo = state.memo as? com.lomo.domain.model.Memo
            if (memo != null) {
                viewModel.deleteMemo(memo)
            }
        },
        onShare = { state ->
            val memo = state.memo as? com.lomo.domain.model.Memo
            com.lomo.app.util.ShareUtils.shareMemoAsImage(
                context = context,
                content = state.content,
                style = shareCardStyle,
                showTime = shareCardShowTime,
                timestamp = memo?.timestamp,
                tags = memo?.tags.orEmpty(),
                activeDayCount = activeDayCount,
            )
        },
        onLanShare = { state ->
            val memo = state.memo as? com.lomo.domain.model.Memo
            if (memo != null) {
                onNavigateToShare(memo.content, memo.timestamp)
            }
        },
    ) { showMenu ->
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Tag,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(tagName)
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                haptic.medium()
                                onBackClick()
                            },
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                )
            },
        ) { padding ->
            if (memos.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                ) {
                    EmptyState(
                        icon = Icons.Outlined.Tag,
                        title = "No memos with #$tagName",
                        description = "Try adding this tag to some memos",
                    )
                }
            } else {
                LazyColumn(
                    contentPadding =
                        PaddingValues(
                            top = padding.calculateTopPadding() + 16.dp,
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 16.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = memos,
                        key = { it.memo.id },
                        contentType = { "memo" },
                    ) { uiModel ->
                        val memo = uiModel.memo

                        Box(
                            modifier =
                                Modifier
                                    .animateItem(
                                        fadeInSpec =
                                            keyframes {
                                                durationMillis = 1000
                                                0f at 0
                                                0f at com.lomo.ui.theme.MotionTokens.DurationLong2
                                                1f at 1000 using com.lomo.ui.theme.MotionTokens.EasingEmphasizedDecelerate
                                            },
                                        fadeOutSpec = snap(),
                                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    ),
                        ) {
                            MemoCard(
                                content = memo.content,
                                processedContent = uiModel.processedContent,
                                precomputedNode = uiModel.markdownNode,
                                timestamp = memo.timestamp,
                                dateFormat = dateFormat,
                                timeFormat = timeFormat,
                                tags = uiModel.tags,
                                onDoubleClick =
                                    if (doubleTapEditEnabled) {
                                        {
                                            editingMemo = memo
                                            inputText =
                                                androidx.compose.ui.text.input.TextFieldValue(
                                                    memo.content,
                                                    androidx.compose.ui.text
                                                        .TextRange(memo.content.length),
                                                )
                                            showInputSheet = true
                                        }
                                    } else {
                                        null
                                    },
                                onImageClick = onNavigateToImage,
                                onMenuClick = {
                                    showMenu(
                                        com.lomo.ui.component.menu.MemoMenuState(
                                            wordCount = memo.content.length,
                                            createdTime = memo.timestamp.formatAsDateTime(dateFormat, timeFormat),
                                            content = memo.content,
                                            memo = memo,
                                        ),
                                    )
                                },
                                menuContent = {},
                            )
                        }
                    }
                }
            }
        }

        if (showInputSheet) {
            com.lomo.ui.component.input.InputSheet(
                inputValue = inputText,
                onInputValueChange = { inputText = it },
                onDismiss = {
                    showInputSheet = false
                    inputText =
                        androidx.compose.ui.text.input
                            .TextFieldValue("")
                    editingMemo = null
                },
                onSubmit = { content ->
                    editingMemo?.let { viewModel.updateMemo(it, content) }
                    showInputSheet = false
                    inputText =
                        androidx.compose.ui.text.input
                            .TextFieldValue("")
                    editingMemo = null
                },
                onImageClick = {
                    if (imageDirectory == null) {
                        Toast
                            .makeText(
                                context,
                                context.getString(com.lomo.app.R.string.settings_not_set),
                                Toast.LENGTH_SHORT,
                            ).show()
                    } else {
                        imagePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    }
                },
                onCameraClick = {
                    if (imageDirectory == null) {
                        Toast
                            .makeText(
                                context,
                                context.getString(com.lomo.app.R.string.settings_not_set),
                                Toast.LENGTH_SHORT,
                            ).show()
                    } else {
                        runCatching {
                            val (file, uri) =
                                com.lomo.app.util.CameraCaptureUtils
                                    .createTempCaptureUri(context)
                            pendingCameraFile = file
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        }.onFailure {
                            runCatching { pendingCameraFile?.delete() }
                            pendingCameraFile = null
                            pendingCameraUri = null
                        }
                    }
                },
                availableTags = emptyList(),
            )
        }
    }
}
