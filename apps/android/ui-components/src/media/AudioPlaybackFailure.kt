package com.lomo.ui.media

/**
 * Classified playback failure. Each variant names the stage that produced it so UI can render
 * feedback instead of a silently dead play button.
 */
sealed interface AudioPlaybackFailure {
    /** The logical source the user tapped (a markdown media path), not the resolved URI. */
    val source: String

    /** The source could not be resolved to a playable URI (missing file, stale mapping). */
    data class Resolve(
        override val source: String,
    ) : AudioPlaybackFailure

    /** The player failed while preparing/starting the resolved media. */
    data class Start(
        override val source: String,
    ) : AudioPlaybackFailure

    /** The player reported an error mid-playback. */
    data class Playback(
        override val source: String,
    ) : AudioPlaybackFailure

    /** Access was denied (permission revoked or protected storage). */
    data class PermissionDenied(
        override val source: String,
    ) : AudioPlaybackFailure
}

fun classifyPlaybackStartFailure(
    source: String,
    error: Throwable,
): AudioPlaybackFailure =
    if (error is SecurityException) {
        AudioPlaybackFailure.PermissionDenied(source)
    } else {
        AudioPlaybackFailure.Start(source)
    }
