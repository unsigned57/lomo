package com.lomo.domain.model

/**
 * Terminal result of one app-update check. A check always lands on exactly one of three
 * outcomes: a verified newer release is installable, the current build is current, or the check
 * itself failed. HTTP status, transport and parse failures are never folded into "up to date".
 */
sealed interface AppUpdateCheckOutcome {
    data class Available(
        val update: AppUpdateInfo,
    ) : AppUpdateCheckOutcome

    data object UpToDate : AppUpdateCheckOutcome

    data class Failed(
        val failure: AppUpdateFetchFailure,
    ) : AppUpdateCheckOutcome
}

/** Why fetching the latest release failed; each variant preserves the diagnostic a user can act on. */
sealed interface AppUpdateFetchFailure {
    val diagnostic: String

    /** The release endpoint answered with a non-OK status. */
    data class Http(
        val code: Int,
        override val diagnostic: String,
    ) : AppUpdateFetchFailure

    /** The request never produced a response (offline, timeout, DNS, TLS). */
    data class Network(
        override val diagnostic: String,
    ) : AppUpdateFetchFailure

    /** The endpoint answered but the payload was not a well-formed release document. */
    data class MalformedResponse(
        override val diagnostic: String,
    ) : AppUpdateFetchFailure
}

/** Typed carrier for a failed release fetch; callers map [failure] into [AppUpdateCheckOutcome.Failed]. */
class AppUpdateFetchException(
    val failure: AppUpdateFetchFailure,
    cause: Throwable? = null,
) : Exception(failure.diagnostic, cause)
