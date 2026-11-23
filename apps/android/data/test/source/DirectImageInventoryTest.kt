package com.lomo.data.source

import com.lomo.data.testing.fakes.FakeAttachmentNameClassifier
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.URI
import kotlin.io.path.createTempDirectory

class DirectImageInventoryTest :
    FunSpec({
        fun workspace(paths: List<String>): File = createTempDirectory("lomo-direct-images-").toFile().also { root ->
            paths.forEach { path ->
                root.resolve(path).apply {
                    requireNotNull(parentFile).mkdirs()
                    writeText("image")
                }
            }
        }

        test("given nested and private images when scanned then public paths retain exact filesystem identity") {
            runTest {
                val visible = listOf("root.png", "media/photo #1.png", "image/nested/old.png")
                val hidden = listOf(".lomo-media-stage/staged.png", ".lomo/history/old.png", ".git/object.png")
                val root = workspace(visible + hidden)
                try {
                    val files =
                        DirectMediaStorageBackendDelegate(root, classifier = FakeAttachmentNameClassifier())
                            .listImageFiles()
                            .toMap()
                    files.keys shouldBe visible.toSet()
                    files.forEach { (path, uri) -> URI(uri).path shouldBe root.resolve(path).path }
                } finally {
                    root.deleteRecursively()
                }
            }
        }

        test("given an empty directory when scanned then no name is classified") {
            runTest {
                val root = workspace(emptyList())
                try {
                    val classifier = FakeAttachmentNameClassifier()
                    DirectMediaStorageBackendDelegate(
                        root,
                        classifier = classifier,
                    ).listImageFiles() shouldBe emptyList()
                    classifier.batches shouldBe emptyList()
                } finally {
                    root.deleteRecursively()
                }
            }
        }

        test("given a full batch of mixed media when scanned then one call classifies base names and keeps images") {
            runTest {
                val images = List(100) { index -> "media/图片-$index.png" }
                val others = List(
                    78,
                ) { index -> "media/voice-$index.m4a" } + List(78) { index -> "media/clip-$index.mp4" }
                val root = workspace(images + others)
                try {
                    val classifier = FakeAttachmentNameClassifier()
                    val files = DirectMediaStorageBackendDelegate(root, classifier = classifier).listImageFiles()
                    files.map { it.first }.toSet() shouldBe images.toSet()
                    classifier.batches.map { it.size } shouldBe listOf(256)
                    classifier.batches.single().toSet() shouldBe (images + others).map {
                        it.substringAfter(
                            '/',
                        )
                    }.toSet()
                } finally {
                    root.deleteRecursively()
                }
            }
        }

        test("given a scan cancelled during its first batch when it resumes then no further batch is classified") {
            runTest {
                val root = workspace(List(5) { index -> "photo-$index.png" })
                try {
                    lateinit var scan: Deferred<List<Pair<String, String>>>
                    val classifier =
                        FakeAttachmentNameClassifier(AttachmentNameBatchLimits(maxNames = 2, maxUtf8Bytes = 1_024)) {
                            scan.cancel()
                        }
                    scan = async { DirectMediaStorageBackendDelegate(root, classifier = classifier).listImageFiles() }
                    shouldThrow<CancellationException> { scan.await() }
                    classifier.batches.size shouldBe 1
                } finally {
                    root.deleteRecursively()
                }
            }
        }
    })
