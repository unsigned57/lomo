package com.lomo.data.sync

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.data.webdav.WebDavClient
import com.lomo.data.webdav.WebDavRemoteResource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Behavior Contract:
 * - Unit under test: SyncLayoutMigration
 * - Behavior focus: legacy WebDAV/Git layout migration decisions, move targets, and failure-tolerant progression.
 * - Observable outcomes: migrated path writes/deletes on WebDAV and moved/retained files in local Git working tree.
 * - TDD proof: Verified by asserting when migration logic is disabled.
 * - Excludes: WebDAV transport implementation details, logging behavior, and file-system primitive internals.
 */
class SyncLayoutMigrationTest : DataFunSpec() {
    init {
        test("migrateWebDavRemote moves legacy memo and media files into lomo layout") { `migrateWebDavRemote moves legacy memo and media files into lomo layout`() }

        test("migrateWebDavRemote exits without migration when lomo root already exists") { `migrateWebDavRemote exits without migration when lomo root already exists`() }

        test("migrateWebDavRemote returns immediately when root listing fails") { `migrateWebDavRemote returns immediately when root listing fails`() }

        test("migrateWebDavRemote continues migrating media when one legacy memo migration fails") { `migrateWebDavRemote continues migrating media when one legacy memo migration fails`() }

        test("migrateGitRepo moves root memos and legacy media folders when layout is split") { `migrateGitRepo moves root memos and legacy media folders when layout is split`() }

        test("migrateGitRepo does nothing when all directories are the same") { `migrateGitRepo does nothing when all directories are the same`() }

        test("migrateGitRepo must not overwrite existing target media file") { `migrateGitRepo must not overwrite existing target media file`() }
    }


    private fun `migrateWebDavRemote moves legacy memo and media files into lomo layout`() {
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

    private fun `migrateWebDavRemote exits without migration when lomo root already exists`() {
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

    private fun `migrateWebDavRemote returns immediately when root listing fails`() {
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

    private fun `migrateWebDavRemote continues migrating media when one legacy memo migration fails`() {
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

    private fun `migrateGitRepo moves root memos and legacy media folders when layout is split`() {
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

        (changed).shouldBeTrue()
        (topMemo.exists()).shouldBeFalse()
        (File(repoRoot, "memos/2026_03_24.md").exists()).shouldBeTrue()
        (File(repoRoot, "media_img/a.png").exists()).shouldBeTrue()
        (File(repoRoot, "media_voice/v1.m4a").exists()).shouldBeTrue()
        (nestedMemo.exists()).shouldBeTrue()
        (oldImage.exists()).shouldBeFalse()
        (oldVoice.exists()).shouldBeFalse()
    }

    private fun `migrateGitRepo does nothing when all directories are the same`() {
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

        (changed).shouldBeFalse()
        (topMemo.exists()).shouldBeTrue()
        (oldImage.exists()).shouldBeTrue()
        (File(repoRoot, "memos/2026_03_24.md").exists()).shouldBeFalse()
    }

    private fun `migrateGitRepo must not overwrite existing target media file`() {
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

        (changed).shouldBeFalse()
        (oldImage.exists()).shouldBeTrue()
        (newImage.exists()).shouldBeTrue()
        oldImage.readText() shouldBe "old"
        newImage.readText() shouldBe "new"
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
