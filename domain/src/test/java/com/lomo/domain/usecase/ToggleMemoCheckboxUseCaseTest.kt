package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.validation.MemoContentValidator
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToggleMemoCheckboxUseCaseTest {
    private val repository: MemoRepository = mockk(relaxed = true)
    private val useCase = ToggleMemoCheckboxUseCase(repository, MemoContentValidator())

    @Test
    fun `updates markdown checkbox line when checking`() =
        runTest {
            val memo =
                memo(
                    content = "- [ ] task\ntext",
                )

            val changed = useCase(memo = memo, lineIndex = 0, checked = true)

            assertTrue(changed)
            coVerify(exactly = 1) { repository.updateMemo(memo, "- [x] task\ntext") }
        }

    @Test
    fun `supports uppercase X when unchecking`() =
        runTest {
            val memo = memo(content = "- [X] done")

            val changed = useCase(memo = memo, lineIndex = 0, checked = false)

            assertTrue(changed)
            coVerify(exactly = 1) { repository.updateMemo(memo, "- [ ] done") }
        }

    @Test
    fun `does not toggle when checkbox marker only appears in body text`() =
        runTest {
            val memo = memo(content = "prefix - [ ] not a list item")

            val changed = useCase(memo = memo, lineIndex = 0, checked = true)

            assertFalse(changed)
            coVerify(exactly = 0) { repository.updateMemo(any(), any()) }
        }

    @Test
    fun `supports ordered list checkbox markers`() =
        runTest {
            val memo = memo(content = "1. [ ] numbered task")

            val changed = useCase(memo = memo, lineIndex = 0, checked = true)

            assertTrue(changed)
            coVerify(exactly = 1) { repository.updateMemo(memo, "1. [x] numbered task") }
        }

    private fun memo(content: String): Memo =
        Memo(
            id = "memo",
            timestamp = 1L,
            content = content,
            rawContent = content,
            dateKey = "2026_02_24",
        )
}
