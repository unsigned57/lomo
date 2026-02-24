package com.lomo.app.feature.memo

import android.net.Uri
import com.lomo.app.feature.media.MemoImageWorkflow
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.validation.MemoContentValidator
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class MemoActionDelegate
    @Inject
    constructor(
        private val memoRepository: MemoRepository,
        private val memoContentValidator: MemoContentValidator,
        private val imageWorkflow: MemoImageWorkflow,
    ) {
        suspend fun deleteMemo(memo: Memo): Result<Unit> =
            runAction {
                memoRepository.deleteMemo(memo)
            }

        suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ): Result<Unit> =
            runAction {
                memoContentValidator.validateForUpdate(newContent)
                memoRepository.updateMemo(memo, newContent)
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
