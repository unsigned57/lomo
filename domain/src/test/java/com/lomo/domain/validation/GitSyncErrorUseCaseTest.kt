package com.lomo.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitSyncErrorUseCaseTest {
    private val policy = GitSyncErrorUseCase()

    @Test
    fun `sanitize keeps conflict message`() {
        val raw = "rebase STOPPED: resolve conflicts manually"

        val sanitized = policy.sanitizeUserFacingMessage(raw, fallbackMessage = "fallback")

        assertEquals(raw, sanitized)
        assertTrue(policy.isConflictMessage(raw))
    }

    @Test
    fun `sanitize keeps direct-path-required message`() {
        val raw = "Git sync requires direct path mode to run"

        val sanitized = policy.sanitizeUserFacingMessage(raw, fallbackMessage = "fallback")

        assertEquals(raw, sanitized)
        assertEquals(GitSyncErrorUseCase.ErrorKind.DIRECT_PATH_REQUIRED, policy.classify(raw))
    }

    @Test
    fun `sanitize falls back for technical details`() {
        val raw = "java.net.SocketTimeoutException: timeout\n\tat okhttp3.RealCall.execute"

        val sanitized = policy.sanitizeUserFacingMessage(raw, fallbackMessage = "fallback")

        assertEquals("fallback", sanitized)
        assertTrue(policy.looksTechnicalMessage(raw))
        assertEquals(GitSyncErrorUseCase.ErrorKind.TECHNICAL, policy.classify(raw))
    }

    @Test
    fun `classify returns user-facing for simple message`() {
        val raw = "Push rejected: remote ref was updated during push."

        assertEquals(GitSyncErrorUseCase.ErrorKind.USER_FACING, policy.classify(raw))
    }
}
