package com.lomo.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitRemoteUrlUseCaseTest {
    private val policy = GitRemoteUrlUseCase()

    @Test
    fun `isValid accepts blank for clearing config`() {
        assertTrue(policy.isValid(""))
        assertTrue(policy.isValid("   "))
    }

    @Test
    fun `isValid accepts https remote with repository path`() {
        assertTrue(policy.isValid("https://github.com/unsigned57/lomo.git"))
    }

    @Test
    fun `isValid rejects non-https or missing repo path`() {
        assertFalse(policy.isValid("http://github.com/unsigned57/lomo.git"))
        assertFalse(policy.isValid("https://github.com"))
    }

    @Test
    fun `normalize trims and removes trailing slash`() {
        assertEquals("https://example.com/org/repo.git", policy.normalize(" https://example.com/org/repo.git/ "))
    }
}
