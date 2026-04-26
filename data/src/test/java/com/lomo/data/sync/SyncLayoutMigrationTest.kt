package com.lomo.data.sync

import com.lomo.data.webdav.WebDavClient
import com.lomo.data.webdav.WebDavRemoteResource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: SyncLayoutMigration
 * - Behavior focus: legacy WebDAV/Git layout migration decisions, move targets, and failure-tolerant progression.
 * - Observable outcomes: migrated path writes/deletes on WebDAV and moved/retained files in local Git working tree.
 * - Excludes: WebDAV transport implementation details, logging behavior, and file-system primitive internals.
 */
class SyncLayoutMigrationTest {
    @Test
    fun `migrateWebDavRemote moves legacy memo and media files into lomo layout`() {
        val client = mockk<WebDavClient>(relaxed = true)
        val layout =
            SyncDirectoryLayout(
                memoFolder = "memos",
                imageFolder = "images_new",
                voiceFolder = "voice_new",
                allSameDirectory = false,
            )
        every { client.list("") } returns
            listOf(
                dir("images"),
                dir("voice"),
                file("2026_03_24.md"),
            )
        every { client.list("images") } returns listOf(file("images/a.png"))
        every { client.list("voice") } returns listOf(file("voice/v1.m4a"))

        SyncLayoutMigration.migrateWebDavRemote(client, layout)

        verify(exactly = 1) { client.ensureDirectory("lomo") }
        verify(exactly = 1) { client.ensureDirectory("lomo/memos") }
        verify(exactly = 1) { client.ensureDirectory("lomo/images_new") }
        verify(exactly = 1) { client.ensureDirectory("lomo/voice_new") }
        verify(exactly = 1) { client.move("2026_03_24.md", "lomo/memos/2026_03_24.md", false) }
        verify(exactly = 1) { client.move("images/a.png", "lomo/images_new/a.png", false) }
        verify(exactly = 1) { client.move("voice/v1.m4a", "lomo/voice_new/v1.m4a", false) }
        verify(exactly = 1) { client.delete("images") }
        verify(exactly = 1) { client.delete("voice") }
    }

    @Test
    fun `migrateWebDavRemote exits without migration when lomo root already exists`() {
        val client = mockk<WebDavClient>(relaxed = true)
        val layout =
            SyncDirectoryLayout(
                memoFolder = "memos",
                imageFolder = "images_new",
                voiceFolder = "voice_new",
                allSameDirectory = false,
            )
        every { client.list("") } returns
            listOf(
                dir("lomo"),
                dir("images"),
                file("2026_03_24.md"),
            )

        SyncLayoutMigration.migrateWebDavRemote(client, layout)

        verify(exactly = 0) { client.ensureDirectory(any()) }
        verify(exactly = 0) { client.list("images") }
        verify(exactly = 0) { client.move(any(), any(), any()) }
        verify(exactly = 0) { client.delete(any()) }
    }

    @Test
    fun `migrateWebDavRemote returns immediately when root listing fails`() {
        val client = mockk<WebDavClient>(relaxed = true)
        val layout =
            SyncDirectoryLayout(
                memoFolder = "memos",
                imageFolder = "images_new",
                voiceFolder = "voice_new",
                allSameDirectory = false,
            )
        every { client.list("") } throws IllegalStateException("unavailable")

        SyncLayoutMigration.migrateWebDavRemote(client, layout)

        verify(exactly = 1) { client.list("") }
        verify(exactly = 0) { client.ensureDirectory(any()) }
        verify(exactly = 0) { client.move(any(), any(), any()) }
        verify(exactly = 0) { client.delete(any()) }
    }

    @Test
    fun `migrateWebDavRemote continues migrating media when one legacy memo migration fails`() {
        val client = mockk<WebDavClient>(relaxed = true)
        val layout =
            SyncDirectoryLayout(
                memoFolder = "memos",
                imageFolder = "images_new",
                voiceFolder = "voice_new",
                allSameDirectory = false,
            )
        every { client.list("") } returns
            listOf(
                dir("images"),
                file("2026_03_24.md"),
                file("2026_03_25.md"),
            )
        every { client.list("images") } returns listOf(file("images/a.png"))
        every { client.list("voice") } returns emptyList()
        every { client.move("2026_03_24.md", "lomo/memos/2026_03_24.md", false) } throws IllegalStateException("memo move failed")

        SyncLayoutMigration.migrateWebDavRemote(client, layout)

        verify(exactly = 1) { client.move("2026_03_25.md", "lomo/memos/2026_03_25.md", false) }
        verify(exactly = 1) { client.move("images/a.png", "lomo/images_new/a.png", false) }
        verify(exactly = 1) { client.delete("images") }
    }

    @Test
    fun `migrateGitRepo moves root memos and legacy media folders when layout is split`() {
        val repoRoot = Files.createTempDirectory("lomo-git-migration").toFile()
        val topMemo = File(repoRoot, "2026_03_24.md").apply { writeText("memo") }
        val nestedMemo = File(repoRoot, "nested/keep.md").apply { parentFile?.mkdirs(); writeText("keep") }
        val oldImage = File(repoRoot, "images/a.png").apply { parentFile?.mkdirs(); writeText("img") }
        val oldVoice = File(repoRoot, "voice/v1.m4a").apply { parentFile?.mkdirs(); writeText("voice") }
        val layout =
            SyncDirectoryLayout(
                memoFolder = "memos",
                imageFolder = "media_img",
                voiceFolder = "media_voice",
                allSameDirectory = false,
            )

        val changed = SyncLayoutMigration.migrateGitRepo(repoRoot, layout)

        assertTrue(changed)
        assertFalse(topMemo.exists())
        assertTrue(File(repoRoot, "memos/2026_03_24.md").exists())
        assertTrue(File(repoRoot, "media_img/a.png").exists())
        assertTrue(File(repoRoot, "media_voice/v1.m4a").exists())
        assertTrue(nestedMemo.exists())
        assertFalse(oldImage.exists())
        assertFalse(oldVoice.exists())
    }

    @Test
    fun `migrateGitRepo does nothing when all directories are the same`() {
        val repoRoot = Files.createTempDirectory("lomo-git-no-migration").toFile()
        val topMemo = File(repoRoot, "2026_03_24.md").apply { writeText("memo") }
        val oldImage = File(repoRoot, "images/a.png").apply { parentFile?.mkdirs(); writeText("img") }
        val layout =
            SyncDirectoryLayout(
                memoFolder = "memos",
                imageFolder = "images_new",
                voiceFolder = "voice_new",
                allSameDirectory = true,
            )

        val changed = SyncLayoutMigration.migrateGitRepo(repoRoot, layout)

        assertFalse(changed)
        assertTrue(topMemo.exists())
        assertTrue(oldImage.exists())
        assertFalse(File(repoRoot, "memos/2026_03_24.md").exists())
    }

    @Test
    fun `migrateGitRepo must not overwrite existing target media file`() {
        val repoRoot = Files.createTempDirectory("lomo-git-existing-target").toFile()
        val oldImage = File(repoRoot, "images/dup.png").apply { parentFile?.mkdirs(); writeText("old") }
        val newImage = File(repoRoot, "media_img/dup.png").apply { parentFile?.mkdirs(); writeText("new") }
        val layout =
            SyncDirectoryLayout(
                memoFolder = "memos",
                imageFolder = "media_img",
                voiceFolder = "media_voice",
                allSameDirectory = false,
            )

        val changed = SyncLayoutMigration.migrateGitRepo(repoRoot, layout)

        assertFalse(changed)
        assertTrue(oldImage.exists())
        assertTrue(newImage.exists())
        assertEquals("old", oldImage.readText())
        assertEquals("new", newImage.readText())
    }

    private fun file(path: String): WebDavRemoteResource =
        WebDavRemoteResource(
            path = path,
            isDirectory = false,
            etag = null,
            lastModified = null,
        )

    private fun dir(path: String): WebDavRemoteResource =
        WebDavRemoteResource(
            path = path,
            isDirectory = true,
            etag = null,
            lastModified = null,
        )
}
