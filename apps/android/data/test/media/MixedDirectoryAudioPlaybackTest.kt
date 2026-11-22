package com.lomo.data.media

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory

/*
 * Behavior Contract:
 * Capability: legacy and shared-root recordings preserve canonical paths; owner: data; P1.
 * Scenarios:
 * - Given dedicated voice storage, when playing media/old.m4a, then the memo workspace owns that qualified path.
 * - Given shared notes/voice storage, when playing voice/a.m4a, then the physical voice directory is retained.
 * Observable outcomes: exact playback file locations.
 * TDD proof: target/lomo/reports/manual/directory-layout/data-red.log returns a missing or rebased file before the fix;
 * GREEN: target/lomo/reports/manual/directory-layout/data-green.log.
 * Excludes: codec playback and Android audio permissions.
 */
class MixedDirectoryAudioPlaybackTest :
    FunSpec({
        test("given legacy media and shared or dedicated voice storage then qualified recordings retain identity") {
            runTest {
                val workspace = createTempDirectory("lomo-audio-layout-").toFile()
                val selected = createTempDirectory("lomo-audio-dedicated-").toFile()
                try {
                    val legacy = workspace.resolve(
                        "media/old.m4a",
                    ).apply {
                        requireNotNull(parentFile).mkdirs()
                        writeText("legacy")
                    }
                    val voice = workspace.resolve(
                        "voice/a.m4a",
                    ).apply {
                        requireNotNull(parentFile).mkdirs()
                        writeText("voice")
                    }
                    val mount = MutableStateFlow(audioMount(workspace.path, selected.path))
                    val player = AudioPlaybackUriResolverImpl(mockk(), mount::value)
                    player.resolve("media/old.m4a") shouldBe legacy.path
                    mount.value = audioMount(workspace.path)
                    player.resolve("voice/a.m4a") shouldBe voice.path
                } finally {
                    workspace.deleteRecursively()
                    selected.deleteRecursively()
                }
            }
        }
    })
