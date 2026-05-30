package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
import com.lomo.domain.model.Memo

@Composable
fun MemoInteractionHost(
    menuCommandHandler: MemoMenuCommandHandler,
    editorSurface: MemoEditorSurface,
    controller: MemoEditorController = rememberMemoEditorController(),
    content: @Composable (
        showMenu: (MemoMenuSelection) -> Unit,
        openEditor: (Memo) -> Unit,
    ) -> Unit,
) {
    MemoMenuBinder(commandHandler = menuCommandHandler) { showMenu ->
        content(showMenu, controller::openForEdit)

        MemoEditorSheetHost(
            controller = controller,
            surface = editorSurface,
        )
    }
}
