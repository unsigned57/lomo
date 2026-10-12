package com.lomo.data.media

/*
 * Behavior Contract:
 * - Unit under test: AudioPlaybackUriResolverImpl.
 * - Owning layer: data Android capability edge.
 * - Priority tier: P0.
 * - Capability: audio attachment destinations resolve under the workspace
 *   directory law — qualified paths join the workspace root verbatim; bare
 *   basenames pool every verified claimant (root file plus same-named files at
 *   any depth under the managed attachment roots media/images/voice) and
 *   resolve only on a unique claim.
 *
 * Scenarios:
 * - Given a qualified destination, when resolved, then it joins the workspace
 *   root verbatim and never relocates under the voice base.
 * - Given a bare basename and one file under a managed root, when resolved,
 *   then that canonical path answers.
 * - Given a bare basename claimed by two managed locations, when resolved,
 *   then resolution fails closed — no directory wins by default.
 * - Given a bare basename with no claimants, when resolved, then null — the
 *   missing name never borrows another location.
 *
 * Observable outcomes: resolved absolute path or null (Resolve failure surface).
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.media.AudioPlaybackUriResolverImplTest'
 * - RED: bare basenames were hard-routed onto the voice base (resolveBaseDir
 *   isJustFilename → voiceLocation), silently picking voice over media and
 *   missing nested managed files.
 * - GREEN: resolution pools verified claimants and fails closed on ambiguity.
 *
 * Excludes: SAF content:// trees (DocumentFile is host-verified; root claims
 *   are the only verifiable SAF lane and are exercised by the same code path).
 */

import android.content.Context
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.StorageLocation
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory

class AudioPlaybackUriResolverImplTest : DataFunSpec() {
    private val context: Context = mockk(relaxed = true)

    private fun resolver(
        root: File?,
        voice: File? = null,
    ): AudioPlaybackUriResolverImpl =
        AudioPlaybackUriResolverImpl(context).apply {
            setRootLocation(root?.let { StorageLocation(it.absolutePath) })
            setVoiceLocation(voice?.let { StorageLocation(it.absolutePath) })
        }

    init {
        test("qualified destination joins the workspace root verbatim") {
            val root = createTempDirectory("lomo-audio-qualified-").toFile()
            try {
                File(root, "media/2026").mkdirs()
                val file = File(root, "media/2026/take.m4a").apply { writeBytes(byteArrayOf(1)) }
                val voice = createTempDirectory("lomo-audio-voice-").toFile()
                try {
                    runTest {
                        resolver(root, voice).resolve("media/2026/take.m4a") shouldBe
                            file.absolutePath
                    }
                } finally {
                    voice.deleteRecursively()
                }
            } finally {
                root.deleteRecursively()
            }
        }

        test("bare basename resolves the unique nested managed file") {
            val root = createTempDirectory("lomo-audio-nested-").toFile()
            try {
                File(root, "voice/2026").mkdirs()
                val file = File(root, "voice/2026/take.m4a").apply { writeBytes(byteArrayOf(1)) }

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

        test("same basename in voice and media fails closed instead of choosing voice") {
            val root = createTempDirectory("lomo-audio-ambiguous-").toFile()
            try {
                File(root, "voice").mkdirs()
                File(root, "media").mkdirs()
                File(root, "voice/take.m4a").writeBytes(byteArrayOf(1))
                File(root, "media/take.m4a").writeBytes(byteArrayOf(2))

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
                File(root, "voice/.lomo-media-stage").mkdirs()
                File(root, "voice/.lomo-media-stage/take.m4a").writeBytes(byteArrayOf(1))

                runTest {
                    resolver(root).resolve("take.m4a") shouldBe null
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
