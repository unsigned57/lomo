/*
 * Behavior Contract:
 * - Unit under test: Throwable.toUserMessage.
 * - Owning layer: app.
 * - Priority tier: P0.
 * - Capability: turn a failure into a user-visible message that still identifies the cause; a typed
 *   engine rejection must never be reduced to the bare fallback text.
 *
 * Scenarios:
 * - Given a throwable with a message, when mapped, then the message (optionally prefixed) is used.
 * - Given a throwable with a blank message, when mapped, then the fallback is used.
 * - Given a sanitizer, when mapped, then the sanitized text wins.
 * - Given a typed engine rejection, when mapped, then the stable failure code is part of the message
 *   so the user and the log both identify which rejection happened.
 * - Given a typed engine rejection, when mapped, then the raw diagnostic is not pasted into the
 *   user-facing message (it may name workspace paths or memo text).
 *
 * Observable outcomes: returned message string.
 * TDD proof: fails before the mapper understands EngineCommandFailureException — a store rejection
 *   previously arrived with a null message and rendered as the bare fallback ("Failed to delete
 *   memo") with no way to tell which rule refused.
 * Excludes: localization of individual codes, snackbar presentation, diagnostics channel.
 *
 * Test Change Justification:
 * - Reason category: typed engine command failure message mapping.
 * - Old behavior/assertion being replaced: mapping without EngineCommandFailureException code extraction.
 * - Why old assertion is no longer correct: typed engine failure codes must be surfaced cleanly in user messages.
 * - Coverage preserved by: all generic exception mapping and typed engine failure scenarios remain fully tested.
 * - Why this is not fitting the test to the implementation: verifies safe user-facing error message presentation.
 */

package com.lomo.app.feature.common

import com.lomo.app.feature.settings.DiagnosticsEnvironmentSnapshot
import com.lomo.app.feature.settings.recentDiagnosticsReport
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeEngineDiagnosticsRecorder
import com.lomo.domain.model.EngineCommandFailure
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.WorkspaceFailureStage
import com.lomo.domain.model.sanitizeDiagnosticText
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ThrowableMessageMapperTest : AppFunSpec() {
    init {
        test("maps throwable message with optional prefix") {
            val throwable = IllegalStateException("boom")

            (throwable.toUserMessage()) shouldBe ("boom")
            (throwable.toUserMessage("Failed")) shouldBe ("Failed: boom")
        }

        test("falls back when throwable message is blank") {
            val throwable = IllegalStateException("")

            (throwable.toUserMessage("Failed")) shouldBe ("Failed: unexpected_error")
            (throwable.toUserMessage()) shouldBe ("unexpected_error")
        }

        test("sanitizer path is preferred when provided") {
            val throwable = IllegalStateException("socket timeout")

            val mapped =
                throwable.toUserMessage("Failed to sync") { raw, fallback ->
                    if (raw?.contains("timeout", ignoreCase = true) == true) {
                        fallback
                    } else {
                        raw.orEmpty()
                    }
                }

            (mapped) shouldBe ("Failed to sync")
        }

        test("given a typed engine rejection then the failure code reaches the user message") {
            val rejection =
                EngineCommandFailureException(
                    EngineCommandFailure(
                        category = EngineFailureCategory.CONFLICT,
                        code = "stale_snapshot",
                        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                        operationId = "op-3",
                        jobId = null,
                        diagnostic = "memo /storage/emulated/0/Notes/2026_08_11.md changed",
                    ),
                )

            (rejection.toUserMessage("Failed to delete memo")) shouldBe
                ("Failed to delete memo: stale_snapshot")
            (rejection.toUserMessage()) shouldBe ("stale_snapshot")
        }

        test("given a typed engine rejection then the raw diagnostic stays out of the user message") {
            val rejection =
                EngineCommandFailureException(
                    EngineCommandFailure(
                        category = EngineFailureCategory.STORAGE,
                        code = "sqlite_error",
                        retryDisposition = EngineRetryDisposition.NEVER,
                        operationId = null,
                        jobId = null,
                        diagnostic = "disk I/O error at /storage/emulated/0/Notes/.lomo/store.db",
                    ),
                )

            val message = rejection.toUserMessage("Failed to refresh memos")

            message shouldContain "sqlite_error"
            message shouldNotContain "/storage/emulated/0"
        }

        test("a wrapped workspace refusal keeps its code, stage, redacted detail and cause in the copied report") {
            val recorder = FakeEngineDiagnosticsRecorder()
            val rejection = EngineCommandFailureException(
                EngineCommandFailure(
                    category = EngineFailureCategory.PERMISSION,
                    code = "saf_grant_revoked",
                    retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                    operationId = "private-operation",
                    jobId = "private-job",
                    diagnostic =
                    "The folder content://example/tree/private under /storage/emulated/0/Notes " +
                        "was revoked token=secret123",
                    workspaceStage = WorkspaceFailureStage.PROBE,
                ),
            )
            val error = IllegalStateException("Opening failed", rejection)

            recorder.recordApplicationFailure("settings.storage", error)

            val environment = DiagnosticsEnvironmentSnapshot(
                appVersion = "2.0.2",
                androidSdk = 37,
                androidRelease = "17",
                device = "Google Pixel 7",
                abis = "arm64-v8a",
                locale = "en-US",
                timeZoneId = "Asia/Shanghai",
                timeZoneOffset = "+08:00",
                workspaceKind = "saf",
                imageStorageKind = "follows_notes",
                voiceStorageKind = "follows_notes",
                filenameFormat = "yyyy_MM_dd",
                timestampFormat = "HH:mm:ss",
                engineReadiness = "ready",
                projectionFreshness = "verified(rev=4)",
            )
            error.toUserMessage("Could not open folder") shouldBe "Could not open folder: saf_grant_revoked"
            val report = recentDiagnosticsReport(recorder.events.value, environment)
            report shouldContain "lomo_diagnostics_v2"
            report shouldContain "app_version=2.0.2"
            report shouldContain "android_sdk=37"
            report shouldContain "time_zone=Asia/Shanghai"
            report shouldContain "time_zone_offset=+08:00"
            report shouldContain "[events errors=1 total=1]"
            report shouldContain
                "settings.storage · rejected · saf_grant_revoked · permission · " +
                "retry=after_user_action · stage=workspace.probe"
            report shouldContain
                "detail=The folder content://example/<redacted> under <path> was revoked token=<redacted>"
            report shouldContain
                "cause=IllegalStateException: Opening failed <- EngineCommandFailureException: saf_grant_revoked"
            report shouldNotContain "tree/private"
            report shouldNotContain "/storage/emulated/0"
            report shouldNotContain "secret123"
        }

        test("sanitizes bearer tokens, url credentials, saf URIs, and paths while keeping timestamp prefix") {
            val sanitized = sanitizeDiagnosticText(
                "https://alice:hunter2@sync.example.com Bearer abcd.efg content://doc.auth/tree/secret /data/user/0/app/db",
            )
            sanitized shouldNotContain "hunter2"
            sanitized shouldNotContain "abcd.efg"
            sanitized shouldNotContain "tree/secret"
            sanitized shouldNotContain "/data/user/0"
            sanitized shouldContain "https://<redacted>@sync.example.com"
            sanitized shouldContain "Bearer <redacted>"
            sanitized shouldContain "content://doc.auth/<redacted>"
            sanitized shouldContain "<path>"

            val stamped = EngineDiagnosticEvent.Stalled(
                label = "workspace.open",
                durationMillis = 1500L,
                recordedAtMillis = 1_700_000_000_123L,
            )
            val environment = DiagnosticsEnvironmentSnapshot(
                appVersion = "2.0.2",
                androidSdk = 37,
                androidRelease = "17",
                device = "Pixel",
                abis = "arm64-v8a",
                locale = "en-US",
                timeZoneId = "UTC",
                timeZoneOffset = "Z",
                workspaceKind = "direct",
                imageStorageKind = "follows_notes",
                voiceStorageKind = "follows_notes",
                filenameFormat = "yyyy_MM_dd",
                timestampFormat = "HH:mm:ss",
                engineReadiness = "opening",
                projectionFreshness = "unavailable",
            )
            val stampedReport = recentDiagnosticsReport(listOf(stamped), environment)
            Regex("""(?m)^\[\d\d:\d\d:\d\d\.\d\d\d] workspace\.open · 1500ms · stalled""").containsMatchIn(
                stampedReport,
            ) shouldBe true
        }
    }
}
