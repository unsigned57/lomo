package com.lomo.domain.model

/** Identity of one microphone capture, independent of the draft that will receive its media. */
@JvmInline
value class RecordingCaptureId(val value: String) {
    init {
        require(value.isNotBlank()) { "Recording capture identity must be nonblank" }
    }

    companion object {
        fun mint(): RecordingCaptureId = RecordingCaptureId(java.util.UUID.randomUUID().toString())
    }
}
