package com.lomo.app.feature.memo

import android.net.Uri
import com.lomo.app.feature.media.MemoImageWorkflow
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.UpdateMemoUseCase
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class MemoActionDelegate
    @Inject
    constructor(
        private val deleteMemoUseCase: DeleteMemoUseCase,
        private val updateMemoUseCase: UpdateMemoUseCase,
        private val imageWorkflow: MemoImageWorkflow,
    ) {
        suspend fun deleteMemo(memo: Memo): Result<Unit> =
            runAction {
                deleteMemoUseCase(memo)
            }

        suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ): Result<Unit> =
            runAction {
                updateMemoUseCase(memo, newContent)
            }

        suspend fun saveImage(uri: Uri): Result<String> =
            runAction {
                imageWorkflow.saveImageAndSync(uri)
            }

        private suspend inline fun <T> runAction(crossinline block: suspend () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
    }
