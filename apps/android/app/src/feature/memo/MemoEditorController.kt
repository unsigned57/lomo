package com.lomo.app.feature.memo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.repository.MemoEditDraftRepository
import com.lomo.ui.component.input.InputEditorActionBadge
import com.lomo.ui.component.input.InputEditorCapabilities
import com.lomo.ui.component.input.InputEditorCommand
import com.lomo.ui.component.input.InputEditorCommandHandler
import com.lomo.ui.component.input.InputEditorDisplayMode
import com.lomo.ui.component.input.InputEditorRecordingState
import com.lomo.ui.component.input.InputEditorSurfaceState
import com.lomo.ui.component.input.InputSheetCallbacks
import com.lomo.ui.component.input.InputSheetState
import com.lomo.ui.icon.LomoIcons
import kotlinx.collections.immutable.toImmutableList
import org.koin.compose.koinInject

enum class MemoEditorMode {
    Compact,
    Expanded,
}

internal const val MEMO_EDITOR_PREVIEW_DEBOUNCE_MILLIS = 250L

internal fun shouldRenderMemoEditorPreview(mode: InputEditorDisplayMode): Boolean =
    mode == InputEditorDisplayMode.Preview

@OptIn(ExperimentalFoundationApi::class)
@Stable
class MemoEditorController
internal constructor() {
    val inputState = TextFieldState()
    internal val submission = MemoEditorSubmissionGate(onCommitted = ::resetSession)

    var isVisible by mutableStateOf(false)
        private set

    var focusRequestToken by mutableLongStateOf(0L)
        private set

    var editingSession: MemoEditSession? by mutableStateOf(null)
        private set

    /** The verified full snapshot of the memo being edited, if a session is open. */
    val editingMemo: Memo?
        get() = editingSession?.run { snapshot.memo }

    var inputValue: TextFieldValue
        get() = TextFieldValue(inputState.text.toString(), inputState.selection, inputState.composition)
        private set(value) {
            inputState.edit {
                replace(0, length, value.text)
                selection = value.selection
            }
        }

    var mode by mutableStateOf(MemoEditorMode.Compact)
        private set

    var displayMode by mutableStateOf(InputEditorDisplayMode.Edit)
        private set

    internal val backfillSelection = MemoBackfillSelectionState()

    val canUndo: Boolean
        get() = inputState.undoState.canUndo

    val canRedo: Boolean
        get() = inputState.undoState.canRedo

    fun openForCreate(initialText: String = "") {
        submission.reset()
        editingSession = null
        inputValue = TextFieldValue(initialText, TextRange(initialText.length))
        inputState.undoState.clearHistory()
        mode = MemoEditorMode.Compact
        displayMode = InputEditorDisplayMode.Edit
        backfillSelection.clear()
        isVisible = true
        focusRequestToken += 1L
    }

    /** Opens an edit session, optionally replacing only the editor buffer with a validated
     * durable draft bound to that session's CAS baseline. */
    internal fun openForEditContent(session: MemoEditSession, content: String) {
        submission.reset()
        editingSession = session
        inputValue = TextFieldValue(content, TextRange(content.length))
        inputState.undoState.clearHistory()
        mode = MemoEditorMode.Compact
        displayMode = InputEditorDisplayMode.Edit
        backfillSelection.clear()
        isVisible = true
        focusRequestToken += 1L
    }

    fun updateInputValue(value: TextFieldValue) {
        val processedValue = autoContinueListMarker(inputValue, value) ?: value
        inputValue = processedValue
    }

    fun undo() {
        inputState.undoState.undo()
    }

    fun redo() {
        inputState.undoState.redo()
    }

    fun ensureVisible() {
        isVisible = true
        focusRequestToken += 1L
    }

    fun setExpanded(expanded: Boolean) {
        if (expanded) {
            if (mode == MemoEditorMode.Expanded) return
            mode = MemoEditorMode.Expanded
        } else {
            if (mode == MemoEditorMode.Compact) return
            mode = MemoEditorMode.Compact
            displayMode = InputEditorDisplayMode.Edit
        }
    }

    fun updateDisplayMode(mode: InputEditorDisplayMode) {
        if (displayMode == mode) return
        val previousDisplayMode = displayMode
        displayMode = mode
        val isRestoringEditFromPreview =
            isVisible &&
                previousDisplayMode == InputEditorDisplayMode.Preview &&
                mode == InputEditorDisplayMode.Edit
        if (isRestoringEditFromPreview) {
            focusRequestToken += 1L
        }
    }

    fun close(): Boolean {
        if (!submission.canDismiss) return false
        resetSession()
        return true
    }

    private fun resetSession() {
        isVisible = false
        editingSession = null
        inputValue = TextFieldValue("")
        inputState.undoState.clearHistory()
        mode = MemoEditorMode.Compact
        displayMode = InputEditorDisplayMode.Edit
        backfillSelection.clear()
        submission.reset()
    }
}

/** Convenience edit entry kept outside the state holder so the controller remains a small state machine. */
internal fun MemoEditorController.openForEdit(session: MemoEditSession) {
    openForEditContent(session, session.snapshot.memo.content)
}

internal fun MemoEditorController.cancelBackfillSelection() {
    backfillSelection.clear()
}

private fun MemoEditorController.removeText(command: InputEditorCommand.RemoveText) {
    updateInputValue(
        com.lomo.ui.component.input.removeInputTextRange(inputValue, command.sourceText, command.range),
    )
}

@Composable
fun rememberMemoEditorController(): MemoEditorController = remember { MemoEditorController() }

@Composable
fun MemoEditorSheetHost(
    controller: MemoEditorController,
    surface: MemoEditorSurface,
    onAutoSaveCommitted: () -> Unit = {},
) {
    val memoEditDraftRepository = koinInject<MemoEditDraftRepository>()
    // Keep the persistence effect in the composition even while the sheet is hidden. This lets a
    // terminal close publish `isVisible = false` and synchronously clear the durable edit slot.
    MemoEditorDraftAutosaveEffect(
        controller = controller,
        repository = memoEditDraftRepository,
    )
    if (!controller.isVisible) return

    val session = surface.session
    val markdownWorkspaceRepository = koinInject<MarkdownWorkspaceRepository>()
    var showBackfillDialog by remember { mutableStateOf(false) }
    var showReminderDialog by remember { mutableStateOf(false) }
    val insertCoordinates = rememberMemoCoordinateInsertAction(controller)
    val onReminderRequested = rememberReminderInsertGate(onReady = { showReminderDialog = true })
    val mediaActions =
        rememberMemoEditorMediaActions(
            controller = controller,
            imageDirectory = session.imageDirectory,
            ownerDraftId = session.ownerDraftId,
            onSaveImages = surface.operations.onSaveImages,
            onImageDirectoryMissing = session.onImageDirectoryMissing,
            onCameraCaptureError = session.onCameraCaptureError,
        )
    val contentState =
        rememberMemoEditorContentState(
            controller = controller,
            repository = markdownWorkspaceRepository,
            session = session,
        )
    val sheetState =
        buildMemoEditorSheetState(
            controller = controller,
            surface = surface,
            contentState = contentState,
        )
    val editorCommandHandler =
        InputEditorCommandHandler { command ->
            when (command) {
                InputEditorCommand.Undo -> controller.undo()

                InputEditorCommand.Redo -> controller.redo()

                is InputEditorCommand.RemoveText -> controller.removeText(command)

                is InputEditorCommand.Action ->
                    when (command.id) {
                        MemoEditorToolbarActionIds.camera -> mediaActions.onCameraClick()

                        MemoEditorToolbarActionIds.image -> mediaActions.onImageClick()

                        MemoEditorToolbarActionIds.backfill -> {
                            if (shouldOpenMemoBackfillDialog(isEditingExistingMemo = controller.editingMemo != null)) {
                                showBackfillDialog = true
                            }
                        }

                        MemoEditorToolbarActionIds.clearBackfill -> controller.cancelBackfillSelection()

                        MemoEditorToolbarActionIds.reminder -> onReminderRequested()

                        MemoEditorToolbarActionIds.location -> insertCoordinates()

                        else -> surface.commands.dispatch(command)
                    }

                else -> surface.commands.dispatch(command)
            }
        }
    val sheetCallbacks =
        buildMemoEditorSheetCallbacks(
            controller = controller,
            surface = surface,
            editorCommandHandler = editorCommandHandler,
            onAutoSaveCommitted = onAutoSaveCommitted,
        )

    // The editor lock is a projection of the owning submission state. Collecting it here means a
    // torn-down acknowledgement path cannot leave the sheet locked: the next composition reads the
    // owner's current terminal state and resolves the session.
    LaunchedEffect(controller, surface.operations.submissionState) {
        surface.operations.submissionState.collect { state ->
            controller.submission.onOwnerState(state)
        }
    }

    com.lomo.ui.component.input.InputSheet(
        state = sheetState,
        callbacks = sheetCallbacks,
        benchmarkRootTag = BenchmarkAnchorContract.INPUT_SHEET_ROOT,
        benchmarkEditorTag = BenchmarkAnchorContract.INPUT_EDITOR,
        benchmarkSubmitTag = BenchmarkAnchorContract.INPUT_SUBMIT,
        benchmarkExpandTag = BenchmarkAnchorContract.INPUT_EDITOR_EXPAND,
    )

    if (showBackfillDialog) {
        MemoBackfillDateTimeDialog(
            initialTimestampMillis = controller.backfillSelection.timestampMillis,
            onDismiss = { showBackfillDialog = false },
            onConfirm = { timestampMillis ->
                controller.backfillSelection.setTimestampForCreate(
                    timestampMillis = timestampMillis,
                    isEditingExistingMemo = controller.editingMemo != null,
                )
                showBackfillDialog = false
            },
        )
    }

    if (showReminderDialog) {
        ReminderInsertDialog(
            onDismiss = { showReminderDialog = false },
            onConfirm = { token ->
                controller.updateInputValue(buildReminderInsertionValue(controller.inputValue, token))
                showReminderDialog = false
            },
        )
    }
}

private fun buildMemoEditorSheetState(
    controller: MemoEditorController,
    surface: MemoEditorSurface,
    contentState: MemoEditorContentState,
): InputSheetState {
    val session = surface.session
    val reminders = if (contentState.sourceText == controller.inputValue.text) {
        contentState.reminders
    } else {
        kotlinx.collections.immutable.persistentListOf()
    }
    return InputSheetState(
        surface = InputEditorSurfaceState(
            inputState = controller.inputState,
            inputTransformation = memoEditorInputTransformation(
                controller.inputValue.text,
                reminders.map { it.range },
            ),
            previewState = contentState.renderState,
            focusRequestToken = controller.focusRequestToken,
            isExpanded = controller.mode == MemoEditorMode.Expanded,
            displayMode = controller.displayMode,
            availableTags = session.availableTags,
            recordingState =
            InputEditorRecordingState(
                isRecording = session.isRecording,
                durationMillis = session.recordingDuration,
                amplitude = session.recordingAmplitude,
            ),
            hints = session.hints,
            toolbarOrder =
            surface.capabilities.toolbarToolOrder.mapNotNull { id ->
                com.lomo.ui.component.input.InputToolbarActionId.fromPersistedId(id)
            }.toImmutableList(),
            capabilities =
            InputEditorCapabilities(
                toolbarTools =
                memoEditorToolbarToolMetadata(
                    availableActions = surface.capabilities.toolbarActions,
                    canUndo = controller.canUndo,
                    canRedo = controller.canRedo,
                    canBackfill = controller.editingMemo == null,
                ),
            ),
            hiddenTextRanges = com.lomo.ui.component.input.mergeInputHiddenRanges(reminders.map { it.range }),
            actionBadges = buildList {
                controller.backfillSelection.timestampMillis?.let { timestampMillis ->
                    add(
                        InputEditorActionBadge(
                            text =
                            formatMemoBackfillBadgeText(
                                timestampMillis = timestampMillis,
                                dateFormat = session.dateFormat,
                                timeFormat = session.timeFormat,
                            ),
                            icon = LomoIcons.History,
                            command = InputEditorCommand.Action(MemoEditorToolbarActionIds.clearBackfill),
                            benchmarkTag = BenchmarkAnchorContract.INPUT_BACKFILL_BADGE,
                        ),
                    )
                }
                reminders.forEach { reminder ->
                    add(
                        InputEditorActionBadge(
                            text = reminder.label,
                            icon = LomoIcons.Notifications,
                            command = InputEditorCommand.RemoveText(controller.inputValue.text, reminder.range),
                            benchmarkTag = BenchmarkAnchorContract.INPUT_REMINDER_BADGE,
                        ),
                    )
                }
            }.toImmutableList(),
        ),
        ownerSubmission = controller.submission.ownerSubmission,
    )
}

private fun buildMemoEditorSheetCallbacks(
    controller: MemoEditorController,
    surface: MemoEditorSurface,
    editorCommandHandler: InputEditorCommandHandler,
    onAutoSaveCommitted: () -> Unit,
): InputSheetCallbacks = InputSheetCallbacks(
    onInputValueChange = controller::updateInputValue,
    onDismiss = {
        // Resolve the lease owner before close() clears the session: an edit session owns
        // its adopted durable draft, a create session owns the surface owner's draft.
        val dismissedDraftId = controller.editingSession?.draftId ?: surface.session.ownerDraftId
        if (controller.close()) {
            surface.operations.onDismiss?.invoke(dismissedDraftId)
        }
    },
    onExpandedChange = controller::setExpanded,
    onDisplayModeChange = controller::updateDisplayMode,
    onSubmit = { content ->
        val submissionId = controller.submission.begin()
        if (submissionId == null) {
            false
        } else {
            val committed =
                surface.operations.onSubmit(
                    submissionId,
                    controller.editingSession,
                    content,
                    controller.backfillSelection.timestampMillisForCreateSubmit(
                        isEditingExistingMemo = controller.editingMemo != null,
                    ),
                )
            if (committed) {
                controller.submission.commit(submissionId)
                true
            } else {
                controller.submission.fail(submissionId)
                false
            }
        }
    },
    commands = editorCommandHandler,
    onToolbarOrderChanged = surface.operations.onToolbarOrderChanged,
    autoSubmitOnDismiss = surface.capabilities.quickSaveOnBackEnabled && controller.editingMemo == null,
    hasDraftPersistence = controller.editingMemo == null,
    // The sheet fires this only after a submission reports a durable commit; rejected or
    // failed sends restore the editor instead and never surface "saved".
    onAutoSaveCommitted = onAutoSaveCommitted,
)
