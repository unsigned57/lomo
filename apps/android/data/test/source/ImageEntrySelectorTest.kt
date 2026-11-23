package com.lomo.data.source

import com.lomo.data.testing.fakes.FakeAttachmentNameClassifier
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.nativebridge.MediaClass
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class ImageEntrySelectorTest :
    FunSpec({
        fun select(classifier: AttachmentNameClassifier, names: List<String>): List<String> {
            val selector = ImageEntrySelector<String>(classifier) { it }
            names.forEach(selector::offer)
            return selector.finish()
        }

        test("given no entries when finished then nothing is classified") {
            val classifier = FakeAttachmentNameClassifier()
            select(classifier, emptyList()).shouldBeEmpty()
            classifier.batches.shouldBeEmpty()
        }

        test("given exactly one batch of names when selected then one call classifies them all") {
            val classifier = FakeAttachmentNameClassifier()
            val names = List(256) { index -> "photo-$index.png" }
            select(classifier, names) shouldBe names
            classifier.batches.map { it.size } shouldBe listOf(256)
        }

        test("given one name past the count limit when selected then it travels in a second batch") {
            val classifier = FakeAttachmentNameClassifier()
            val names = List(257) { index -> "photo-$index.png" }
            select(classifier, names) shouldBe names
            classifier.batches.map { it.size } shouldBe listOf(256, 1)
        }

        test("given mixed media names when selected then only images survive in the order offered") {
            val classifier = FakeAttachmentNameClassifier(AttachmentNameBatchLimits(maxNames = 2, maxUtf8Bytes = 1_024))
            val names = listOf("z.png", "voice.m4a", "clip.mp4", "a.jpg", "notes.txt", "m.webp")
            select(classifier, names) shouldBe listOf("z.png", "a.jpg", "m.webp")
            classifier.batches shouldBe names.chunked(2)
        }

        test("given multi-byte names when selected then batches split on encoded bytes, not characters") {
            // Each name is 3 characters and 9 UTF-8 bytes; two fit a 20-byte budget, three do not.
            val classifier = FakeAttachmentNameClassifier(AttachmentNameBatchLimits(maxNames = 256, maxUtf8Bytes = 20))
            val names = listOf("图片一", "图片二", "图片三", "图片四", "图片五")
            select(classifier, names).shouldBeEmpty()
            classifier.batches shouldBe listOf(names.subList(0, 2), names.subList(2, 4), names.subList(4, 5))
        }

        test("given names that fill the byte budget exactly when selected then they share one batch") {
            val classifier = FakeAttachmentNameClassifier(AttachmentNameBatchLimits(maxNames = 256, maxUtf8Bytes = 10))
            select(classifier, listOf("12345", "67890", "x"))
            classifier.batches shouldBe listOf(listOf("12345", "67890"), listOf("x"))
        }

        test("given one name over the byte budget when offered then the walk is refused as a resource limit") {
            val classifier = FakeAttachmentNameClassifier(AttachmentNameBatchLimits(maxNames = 256, maxUtf8Bytes = 8))
            val selector = ImageEntrySelector<String>(classifier) { it }
            selector.offer("ok.png")
            val failure = shouldThrow<EngineCommandFailureException> { selector.offer("图片图片图片.png") }
            failure.failure.category shouldBe EngineFailureCategory.RESOURCE_LIMIT
            failure.failure.code shouldBe "attachment_name_too_large"
            classifier.batches.shouldBeEmpty()
        }

        test("given a response with a different count when selected then no entry is admitted") {
            val short =
                object : AttachmentNameClassifier {
                    override fun limits() = AttachmentNameBatchLimits(maxNames = 256, maxUtf8Bytes = 65_536)

                    override fun classify(names: List<String>) = listOf(MediaClass.IMAGE)
                }
            val failure = shouldThrow<EngineCommandFailureException> { select(short, listOf("a.png", "b.png")) }
            failure.failure.category shouldBe EngineFailureCategory.INTERNAL
            failure.failure.code shouldBe "attachment_classification_count_mismatch"
        }

        test("utf8ByteLength equals the encoded size the bindings put on the wire") {
            listOf("", "a", "é", "图", "🙂", "a\uD83D", "\uDE42b", "mixed-图片-🙂.png").forEach { value ->
                withClue(value) { utf8ByteLength(value) shouldBe value.toByteArray(Charsets.UTF_8).size }
            }
            checkAll(Arb.string()) { value ->
                utf8ByteLength(value) shouldBe value.toByteArray(Charsets.UTF_8).size
            }
        }
    })
