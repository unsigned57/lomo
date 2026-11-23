package com.lomo.data.media

/*
 * Behavior Contract:
 * Capability: voice/ mounts dedicated storage while other qualified recordings retain workspace identity; owner: data; P1.
 * Scenarios:
 * - Given a selected voice root, when voice/ or a bare name resolves, then the selected recording answers without an old-root fallback.
 * - Given media/ recordings and a voice selection, when resolved, then the exact workspace file answers.
 * - Given no selected root, when managed names are ambiguous, then playback remains unresolved.
 * - Given escaping paths, when resolved, then files outside the grant cannot answer.
 * Observable outcomes: exact locations or explicit absence.
 * TDD proof: target/lomo/reports/manual/directory-layout/data-red.log (MixedDirectoryAudioPlaybackTest) returns null for an existing workspace media/ recording;
 * GREEN: target/lomo/reports/manual/directory-layout/data-green.log.
 * Test Change Justification:
 * Reason category: directory identity contract correction.
 * Old behavior/assertion being replaced: a voice selection rebased media/ paths into the selected directory.
 * Why old assertion is no longer correct: media/ is a qualified workspace identity in the native owner; only voice/ is a selected mount.
 * Coverage preserved by: selected voice/ hit/miss, bare-name selection, qualified workspace hit/miss, traversal and ambiguity cases.
 * Why this is not fitting the test to the implementation: the new assertions agree with native routing and preserve existing media/ layouts.
 * Excludes: Android codec playback and real provider permission checks.
 */

import android.content.Context
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAttachmentDirectories
import com.lomo.domain.model.WorkspaceMount
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory

class AudioPlaybackUriResolverImplTest : DataFunSpec() {
    private val context: Context = mockk()

    private fun resolver(root: File?, voice: File? = null): AudioPlaybackUriResolverImpl =
        AudioPlaybackUriResolverImpl(context, { audioMount(root?.absolutePath, voice?.absolutePath) })

    init {
        test("given a selected voice root when a relative path escapes it then playback cannot read the outside file") {
            val voice = createTempDirectory("lomo-voice-boundary-").toFile()
            val outside = File(voice.parentFile, "escape-${voice.name}.m4a").apply { writeBytes(byteArrayOf(1)) }
            try {
                runTest {
                    resolver(null, voice).resolve("voice/../${outside.name}") shouldBe null
                    resolver(null, voice).resolve("../${outside.name}") shouldBe null
                }
            } finally {
                outside.delete()
                voice.deleteRecursively()
            }
        }
        test(
            "given a selected voice directory when resolving a qualified recording then workspace audio is never substituted",
        ) {
            val root = createTempDirectory("lomo-audio-root-").toFile()
            val selected = createTempDirectory("lomo-audio-selected-").toFile()
            try {
                File(root, "voice").mkdirs()
                File(root, "voice/take.m4a").writeText("wrong workspace audio")
                val recording = File(selected, "take.m4a").apply { writeText("selected audio") }
                runTest {
                    val player = resolver(root, selected)
                    player.resolve("voice/take.m4a") shouldBe recording.absolutePath
                    recording.delete()
                    player.resolve("voice/take.m4a") shouldBe null
                }
            } finally {
                root.deleteRecursively()
                selected.deleteRecursively()
            }
        }
        test(
            "given unset voice directory when bare name exists only in workspace voice then resolver ignores unconfigured voice directory",
        ) {
            val root = createTempDirectory("lomo-voice-switch-").toFile()
            val selected = createTempDirectory("lomo-voice-selected-").toFile()
            try {
                File(root, "voice").mkdirs()
                File(root, "voice/take.m4a").writeBytes(byteArrayOf(1))
                val fresh = File(selected, "take.m4a").apply { writeBytes(byteArrayOf(2)) }
                runTest {
                    val mount = MutableStateFlow(audioMount(root.absolutePath))
                    val resolver = AudioPlaybackUriResolverImpl(context, mount::value)
                    resolver.resolve("take.m4a") shouldBe null
                    mount.value = audioMount(root.absolutePath, root.absolutePath)
                    resolver.resolve("take.m4a") shouldBe File(root, "voice/take.m4a").absolutePath
                    mount.value = audioMount(root.absolutePath, selected.absolutePath)
                    resolver.resolve("take.m4a") shouldBe fresh.absolutePath
                    fresh.delete()
                    resolver.resolve("take.m4a") shouldBe null
                }
            } finally {
                root.deleteRecursively()
                selected.deleteRecursively()
            }
        }

        test("given qualified media audio when voice storage changes then workspace identity is retained") {
            val root = createTempDirectory("lomo-audio-qualified-").toFile()
            try {
                File(root, "media/2026").mkdirs()
                val file = File(root, "media/2026/take.m4a").apply { writeBytes(byteArrayOf(1)) }
                val voice = createTempDirectory("lomo-audio-voice-").toFile()
                try {
                    runTest {
                        resolver(root).resolve("media/2026/take.m4a") shouldBe file.absolutePath
                        val selectedFile = File(voice, "media/2026/take.m4a").apply {
                            requireNotNull(parentFile).mkdirs()
                            writeBytes(byteArrayOf(2))
                        }
                        val selectedResolver = resolver(root, voice)
                        selectedResolver.resolve("media/2026/take.m4a") shouldBe file.absolutePath
                        file.delete()
                        selectedResolver.resolve("media/2026/take.m4a") shouldBe null
                    }
                } finally {
                    voice.deleteRecursively()
                }
            } finally {
                root.deleteRecursively()
            }
        }

        test("bare basename resolves the unique nested managed media file") {
            val root = createTempDirectory("lomo-audio-nested-").toFile()
            try {
                File(root, "media/2026").mkdirs()
                val file = File(root, "media/2026/take.m4a").apply { writeBytes(byteArrayOf(1)) }

                runTest {
                    resolver(root).resolve("take.m4a") shouldBe file.absolutePath
                }
            } finally {
                root.deleteRecursively()
            }
        }

        test("bare basename resolves through the configured voice base") {
            val root = createTempDirectory("lomo-audio-root-").toFile()
            val voice = createTempDirectory("lomo-audio-voice-").toFile()
            try {
                val file = File(voice, "take.m4a").apply { writeBytes(byteArrayOf(1)) }

                runTest {
                    resolver(root, voice).resolve("take.m4a") shouldBe file.absolutePath
                }
            } finally {
                root.deleteRecursively()
                voice.deleteRecursively()
            }
        }

        test("same basename in two managed media directories fails closed and ignores unconfigured voice") {
            val root = createTempDirectory("lomo-audio-ambiguous-").toFile()
            try {
                File(root, "media/a").mkdirs()
                File(root, "media/b").mkdirs()
                File(root, "media/a/take.m4a").writeBytes(byteArrayOf(1))
                File(root, "media/b/take.m4a").writeBytes(byteArrayOf(2))

                runTest {
                    // Fail closed: two managed claimants, no default winner.
                    resolver(root).resolve("take.m4a") shouldBe null
                }
            } finally {
                root.deleteRecursively()
            }
        }

        test("bare basename with no claimants stays missing") {
            val root = createTempDirectory("lomo-audio-missing-").toFile()
            try {
                runTest {
                    resolver(root).resolve("take.m4a") shouldBe null
                }
            } finally {
                root.deleteRecursively()
            }
        }

        test("lifecycle lane files never claim a bare basename") {
            val root = createTempDirectory("lomo-audio-lane-").toFile()
            try {
                File(root, "media/.lomo-media-stage").mkdirs()
                File(root, "media/.lomo-media-stage/take.m4a").writeBytes(byteArrayOf(1))

                runTest {
                    resolver(root).resolve("take.m4a") shouldBe null
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }
}

internal fun audioMount(root: String?, voice: String? = null): WorkspaceMount = WorkspaceMount.Opening.copy(
    location = root?.let(::StorageLocation),
    attachments = WorkspaceAttachmentDirectories(recordings = voice?.let(::StorageLocation)),
)
