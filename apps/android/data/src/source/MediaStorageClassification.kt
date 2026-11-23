package com.lomo.data.source

import com.lomo.data.engine.engineCommandFailure
import com.lomo.data.engine.withEngineFailureConversion
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.nativebridge.MediaClass
import com.lomo.nativebridge.attachmentClassificationLimits
import com.lomo.nativebridge.attachmentMediaClass
import com.lomo.nativebridge.classifyAttachmentNames

/**
 * Asks the Rust workspace media owner whether a storage file name is an image attachment.
 *
 * Storage enumeration uses the same owner classifier as render and query facts; Kotlin never
 * inspects MIME prefixes or extensions itself. This single-name entry serves point lookups; an
 * inventory walk goes through [ImageEntrySelector] instead.
 */
internal fun isImageAttachmentFileName(name: String): Boolean = attachmentMediaClass(name) == MediaClass.IMAGE

/** The most names and UTF-8 name bytes one classification batch may carry. */
internal data class AttachmentNameBatchLimits(val maxNames: Int, val maxUtf8Bytes: Int)

/** Batch access to the Rust workspace media owner's file-name classifier. */
internal interface AttachmentNameClassifier {
    /** The owner's batch bound; a walk asks once. */
    fun limits(): AttachmentNameBatchLimits

    /** One class per name, in request order. [names] must fit [limits]. */
    fun classify(names: List<String>): List<MediaClass>
}

internal object NativeAttachmentNameClassifier : AttachmentNameClassifier {
    override fun limits(): AttachmentNameBatchLimits = attachmentClassificationLimits().let { limits ->
        AttachmentNameBatchLimits(maxNames = limits.maxNames.toInt(), maxUtf8Bytes = limits.maxUtf8Bytes.toInt())
    }

    override fun classify(names: List<String>): List<MediaClass> = withEngineFailureConversion {
        classifyAttachmentNames(names)
    }
}

/**
 * Keeps the image entries of a storage listing.
 *
 * Entries are offered one at a time as the listing is walked. They are classified in batches
 * that stay inside the owner's name-count and UTF-8 byte limits, so a walk costs one native call
 * per batch instead of one per file, and holds at most one batch of undecided entries.
 */
internal class ImageEntrySelector<T>(
    private val classifier: AttachmentNameClassifier,
    private val nameOf: (T) -> String,
) {
    private val limits = classifier.limits()
    private val pending = ArrayList<T>(limits.maxNames)
    private val pendingNames = ArrayList<String>(limits.maxNames)
    private var pendingBytes = 0
    private val images = ArrayList<T>()

    fun offer(entry: T) {
        val name = nameOf(entry)
        val bytes = utf8ByteLength(name)
        if (bytes > limits.maxUtf8Bytes) {
            throw engineCommandFailure(
                category = EngineFailureCategory.RESOURCE_LIMIT,
                code = "attachment_name_too_large",
                retryDisposition = EngineRetryDisposition.NEVER,
                diagnostic = "A storage file name of $bytes UTF-8 bytes exceeds the classification batch limit",
            )
        }
        if (pending.size == limits.maxNames || pendingBytes + bytes > limits.maxUtf8Bytes) {
            classifyPending()
        }
        pending += entry
        pendingNames += name
        pendingBytes += bytes
    }

    /** Classifies whatever is still undecided and returns every image, in the order offered. */
    fun finish(): List<T> {
        classifyPending()
        return images
    }

    private fun classifyPending() {
        if (pending.isEmpty()) return
        val classes = classifier.classify(pendingNames.toList())
        if (classes.size != pending.size) {
            throw engineCommandFailure(
                category = EngineFailureCategory.INTERNAL,
                code = "attachment_classification_count_mismatch",
                retryDisposition = EngineRetryDisposition.NEVER,
                diagnostic = "Classified ${classes.size} of ${pending.size} storage file names",
            )
        }
        for (index in pending.indices) {
            if (classes[index] == MediaClass.IMAGE) images += pending[index]
        }
        pending.clear()
        pendingNames.clear()
        pendingBytes = 0
    }
}

/**
 * The number of bytes [value] occupies on the wire, without encoding it.
 *
 * Matches the JVM UTF-8 encoder the generated bindings use, including its one-byte replacement
 * for an unpaired surrogate.
 */
internal fun utf8ByteLength(value: String): Int {
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val char = value[index]
        val pairs = char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()
        bytes += when {
            pairs -> SUPPLEMENTARY_UTF8_BYTES
            char.isSurrogate() -> 1
            char.code < TWO_BYTE_UTF8_START -> 1
            char.code < THREE_BYTE_UTF8_START -> 2
            else -> THREE_UTF8_BYTES
        }
        index += if (pairs) 2 else 1
    }
    return bytes
}

private const val TWO_BYTE_UTF8_START = 0x80
private const val THREE_BYTE_UTF8_START = 0x800
private const val THREE_UTF8_BYTES = 3
private const val SUPPLEMENTARY_UTF8_BYTES = 4
