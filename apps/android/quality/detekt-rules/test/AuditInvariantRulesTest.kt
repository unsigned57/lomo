package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: audit-invariant rules (NoMintedIdentity, NoErrorMessageControlFlow,
 *   NoPlaceholderCollaborator, NoCapabilitySeam, NoSecretInWorkPayload, NoNamePredicateDelete,
 *   NoCorruptionEmptyReset, NoDomainClock, NoConstantStatusValue)
 * - Owning layer: quality (first-principles audit invariants)
 * - Priority tier: P0
 *
 * Capability:
 * - Identity fields must not be forged at call sites: UUID/Random/clock mints into
 *   identity fields, and content-hash mints into sequencing identity (token/epoch/generation/
 *   fence/revision/version/nonce). Content-addressed digests into id/digest fields are measured
 *   identity and allowed.
 *   Id/Token/Epoch/Generation/Fence/Revision/Nonce-named targets or Request/Command/
 *   Intent/Envelope constructor arguments.
 * - Control flow must not decide on diagnostic message text.
 * - Capability collaborators must not be placeholder objects or optional seams.
 * - WorkManager payloads must not carry secret values.
 * - Destructive operations must not select targets by fuzzy name predicates.
 * - Corruption handlers must not silently reset to empty state.
 * - domain must not read the platform wall clock inline.
 * - Measured status fields must not be fed literal constants.
 *
 * Scenarios:
 * - Given each violation shape in a compiled fixture, when linted, then a finding is reported.
 * - Given the owner-minted, injected-seam, exact-match, typed-error and measured-value shapes,
 *   when linted, then no finding is reported.
 *
 * Observable outcomes:
 * - Registered rule presence, finding counts and finding messages.
 *
 * TDD proof:
 * - Fails before the rules exist because the fixture shapes compile without findings.
 *
 * Excludes:
 * - Rust-side equivalents (lomo-architecture-tests policy module) and runtime behavior.
 */
class AuditInvariantRulesTest : FunSpec({
    test("registers all audit-invariant rules in the rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules
        rules[RuleName("NoMintedIdentity")].shouldNotBeNull()
        rules[RuleName("NoErrorMessageControlFlow")].shouldNotBeNull()
        rules[RuleName("NoPlaceholderCollaborator")].shouldNotBeNull()
        rules[RuleName("NoCapabilitySeam")].shouldNotBeNull()
        rules[RuleName("NoSecretInWorkPayload")].shouldNotBeNull()
        rules[RuleName("NoNamePredicateDelete")].shouldNotBeNull()
        rules[RuleName("NoCorruptionEmptyReset")].shouldNotBeNull()
        rules[RuleName("NoDomainClock")].shouldNotBeNull()
        rules[RuleName("NoConstantStatusValue")].shouldNotBeNull()
    }

    // ---- NoMintedIdentity ---------------------------------------------------

    test("NoMintedIdentity: flags UUID minted into request operationId argument") {
        val findings =
            rule("NoMintedIdentity").findingsForSource(
                "data/src/repository/StoreMemoTaskRepository.kt",
                """
                package com.lomo.data.repository

                import java.util.UUID

                class StoreMemoTaskRepository {
                    fun toggle() {
                        submit(SessionToggleTaskRequest(operationId = UUID.randomUUID().toString()))
                    }
                    private fun submit(request: SessionToggleTaskRequest) {}
                    class SessionToggleTaskRequest(val operationId: String)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "operationId"
    }

    test("NoMintedIdentity: flags positional mint inside a Request constructor") {
        val findings =
            rule("NoMintedIdentity").findingsForSource(
                "data/src/repository/Sample.kt",
                """
                package com.lomo.data.repository

                import java.util.UUID

                class Sample {
                    fun go() {
                        PinMemoRequest(UUID.randomUUID().toString(), 3)
                    }
                    class PinMemoRequest(val requestId: String, val line: Int)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "PinMemoRequest"
    }

    test("NoMintedIdentity: flags hash-derived sequencing identity") {
        val findings =
            rule("NoMintedIdentity").findingsForSource(
                "data/src/engine/Sample.kt",
                """
                package com.lomo.data.engine

                class Sample {
                    fun fence(bytes: ByteArray) {
                        SyncFence(revisionToken = "sha256:" + bytes.sha256Hex())
                    }
                    class SyncFence(val revisionToken: String)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "revisionToken"
    }

    test("NoMintedIdentity: allows content-addressed digest") {
        val findings =
            rule("NoMintedIdentity").findingsForSource(
                "data/src/engine/Sample.kt",
                """
                package com.lomo.data.engine

                class Sample {
                    fun verify(bytes: ByteArray) {
                        val digest = bytes.sha256Hex()
                        MemoUiModel(id = "viewer:" + digest.hashCode())
                        consume(digest)
                    }
                    class MemoUiModel(val id: String)
                    private fun consume(digest: String) {}
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoMintedIdentity: flags local identity property minted from Random") {
        val findings =
            rule("NoMintedIdentity").findingsForSource(
                "data/src/engine/Sample.kt",
                """
                package com.lomo.data.engine

                import kotlin.random.Random

                class Sample {
                    fun prepare() {
                        val token = "cap-" + Random.nextLong()
                        consume(token)
                    }
                    private fun consume(token: String) {}
                }
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("NoMintedIdentity: allows canonical minting function") {
        val findings =
            rule("NoMintedIdentity").findingsForSource(
                "app/src/feature/common/MemoOperationIds.kt",
                """
                package com.lomo.app.feature.common

                import java.util.UUID

                internal fun newMemoOperationId(): String = UUID.randomUUID().toString()
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoMintedIdentity: allows injectable mint provider default") {
        val findings =
            rule("NoMintedIdentity").findingsForSource(
                "app/src/TrustedLaunchIntents.kt",
                """
                package com.lomo.app

                import java.util.UUID

                class Policy(
                    private val nonceProvider: () -> String = { UUID.randomUUID().toString() },
                )
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoMintedIdentity: allows owner-mint marker") {
        val findings =
            rule("NoMintedIdentity").findingsForSource(
                "data/src/local/LomoDataStoreDelegates.kt",
                """
                package com.lomo.data.local

                import java.util.UUID

                class Delegate {
                    fun prepare() {
                        // behavior-contract: identity-mint-ok: transition store owns transition ids
                        val transition = Transition(id = UUID.randomUUID().toString())
                        consume(transition)
                    }
                    private fun consume(transition: Transition) {}
                    class Transition(val id: String)
                }
                """,
            )

        findings shouldBe emptyList()
    }

    // ---- NoErrorMessageControlFlow -------------------------------------------

    test("NoErrorMessageControlFlow: flags contains-branch on error message") {
        val findings =
            rule("NoErrorMessageControlFlow").findingsForSource(
                "domain/src/model/Sample.kt",
                """
                package com.lomo.domain.model

                fun codeFrom(error: Throwable): Int =
                    when {
                        error.message?.contains("conflict") == true -> 1
                        else -> 0
                    }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Control flow"
    }

    test("NoErrorMessageControlFlow: flags equality check on exception message") {
        val findings =
            rule("NoErrorMessageControlFlow").findingsForSource(
                "data/src/Sample.kt",
                """
                package com.lomo.data

                class Sample {
                    fun run() {
                        try {
                            go()
                        } catch (e: Exception) {
                            if (e.message == "conflict_session_missing") {
                                recover()
                            }
                        }
                    }
                    private fun go() {}
                    private fun recover() {}
                }
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("NoErrorMessageControlFlow: flags when subject on message text") {
        val findings =
            rule("NoErrorMessageControlFlow").findingsForSource(
                "data/src/Sample.kt",
                """
                package com.lomo.data

                class Sample {
                    fun classify(error: Throwable): Int =
                        when (error.message) {
                            "a" -> 1
                            else -> 0
                        }
                }
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("NoErrorMessageControlFlow: flags decoder function name") {
        val findings =
            rule("NoErrorMessageControlFlow").findingsForSource(
                "domain/src/model/Sample.kt",
                """
                package com.lomo.domain.model

                private fun s3SyncErrorCodeFromMessage(rawMessage: String?): Int = 0
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "s3SyncErrorCodeFromMessage"
    }

    test("NoErrorMessageControlFlow: allows message rendering for diagnostics") {
        val findings =
            rule("NoErrorMessageControlFlow").findingsForSource(
                "data/src/Sample.kt",
                """
                package com.lomo.data

                class Sample {
                    fun run(): String =
                        try {
                            go()
                        } catch (e: Exception) {
                            "failed: " + (e.message ?: "unknown")
                        }
                    private fun go(): String = "ok"
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoErrorMessageControlFlow: allows sealed-type dispatch named like diagnostics") {
        val findings =
            rule("NoErrorMessageControlFlow").findingsForSource(
                "app/src/feature/settings/Sample.kt",
                """
                package com.lomo.app.feature.settings

                class Sample {
                    fun map(throwable: Throwable): Int {
                        val operationError =
                            throwable.toErrorOrNull()
                                ?: OperationError.Message(throwable.toUserMessage())
                        return when (operationError) {
                            is OperationError.Sync -> 1
                            is OperationError.Message -> 0
                        }
                    }
                    private fun Throwable.toErrorOrNull(): OperationError? = null
                    private fun Throwable.toUserMessage(): String = "x"
                    sealed interface OperationError {
                        data class Sync(val code: Int) : OperationError
                        data class Message(val text: String) : OperationError
                    }
                }
                """,
            )

        findings shouldBe emptyList()
    }

    // ---- NoPlaceholderCollaborator -------------------------------------------

    test("NoPlaceholderCollaborator: flags NoOp repository object") {
        val findings =
            rule("NoPlaceholderCollaborator").findingsForSource(
                "app/src/feature/settings/Sample.kt",
                """
                package com.lomo.app.feature.settings

                interface MemoSnapshotPreferencesRepository {
                    fun enabled(): Boolean
                }

                private object NoOpMemoSnapshotPreferencesRepository : MemoSnapshotPreferencesRepository {
                    override fun enabled() = false
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "NoOpMemoSnapshotPreferencesRepository"
    }

    test("NoPlaceholderCollaborator: flags Fake service class") {
        val findings =
            rule("NoPlaceholderCollaborator").findingsForSource(
                "app/src/feature/Sample.kt",
                """
                package com.lomo.app.feature

                interface SyncService {
                    fun sync()
                }

                class FakeSyncService : SyncService {
                    override fun sync() {}
                }
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("NoPlaceholderCollaborator: allows sensory no-op outside capability suffixes") {
        val findings =
            rule("NoPlaceholderCollaborator").findingsForSource(
                "ui-components/src/util/AppHapticFeedback.kt",
                """
                package com.lomo.ui.util

                interface AppHapticFeedback {
                    fun light()
                }

                private val NoOpAppHapticFeedback =
                    object : AppHapticFeedback {
                        override fun light() = Unit
                    }
                """,
            )

        findings shouldBe emptyList()
    }

    // ---- NoCapabilitySeam -----------------------------------------------------

    test("NoCapabilitySeam: flags nullable repository constructor parameter") {
        val findings =
            rule("NoCapabilitySeam").findingsForSource(
                "app/src/feature/settings/Sample.kt",
                """
                package com.lomo.app.feature.settings

                interface SyncInboxRepository

                class Coordinator(
                    private val syncInboxRepository: SyncInboxRepository? = null,
                )
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "syncInboxRepository"
    }

    test("NoCapabilitySeam: flags nullable capability without default") {
        val findings =
            rule("NoCapabilitySeam").findingsForSource(
                "data/src/worker/Sample.kt",
                """
                package com.lomo.data.worker

                interface SecretMaterialSource

                class InputFactory(
                    private val identityMaterial: SecretMaterialSource?,
                )
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("NoCapabilitySeam: flags NoOp default argument") {
        val findings =
            rule("NoCapabilitySeam").findingsForSource(
                "app/src/feature/settings/Sample.kt",
                """
                package com.lomo.app.feature.settings

                interface MemoSnapshotPreferencesRepository

                class Coordinator(
                    private val repo: MemoSnapshotPreferencesRepository = NoOpMemoSnapshotPreferencesRepository,
                )

                object NoOpMemoSnapshotPreferencesRepository : MemoSnapshotPreferencesRepository
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("NoCapabilitySeam: flags getOrNull inside DI module") {
        val findings =
            rule("NoCapabilitySeam").findingsForSource(
                "app/src/di/AppModule.kt",
                """
                package com.lomo.app.di

                val module = org.koin.dsl.module {
                    single {
                        Deps(
                            syncInboxRepository = getOrNull(),
                        )
                    }
                }

                class Deps(val syncInboxRepository: Any?)
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "getOrNull"
    }

    test("NoCapabilitySeam: allows non-null injected capability") {
        val findings =
            rule("NoCapabilitySeam").findingsForSource(
                "app/src/feature/settings/Sample.kt",
                """
                package com.lomo.app.feature.settings

                interface SyncInboxRepository

                class Coordinator(
                    private val syncInboxRepository: SyncInboxRepository,
                )
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoCapabilitySeam: allows optional data fields on data classes") {
        val findings =
            rule("NoCapabilitySeam").findingsForSource(
                "app/src/feature/review/Sample.kt",
                """
                package com.lomo.app.feature.review

                interface CollectionSource

                private data class LoadCursor(
                    val collectionSource: CollectionSource? = null,
                )
                """,
            )

        findings shouldBe emptyList()
    }

    // ---- NoSecretInWorkPayload ------------------------------------------------

    test("NoSecretInWorkPayload: flags secret value in workDataOf") {
        val findings =
            rule("NoSecretInWorkPayload").findingsForSource(
                "data/src/worker/Sample.kt",
                """
                package com.lomo.data.worker

                import androidx.work.workDataOf

                class Sample {
                    fun plan(secretAccessKey: String) {
                        val data = workDataOf("key" to secretAccessKey)
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "secretAccessKey"
    }

    test("NoSecretInWorkPayload: flags putString of password value") {
        val findings =
            rule("NoSecretInWorkPayload").findingsForSource(
                "data/src/worker/Sample.kt",
                """
                package com.lomo.data.worker

                import androidx.work.Data

                class Sample {
                    fun plan(password: String) {
                        val builder = Data.Builder()
                        builder.putString("password", password)
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("NoSecretInWorkPayload: allows field-name indirection") {
        val findings =
            rule("NoSecretInWorkPayload").findingsForSource(
                "data/src/worker/RustSyncWorker.kt",
                """
                package com.lomo.data.worker

                import androidx.work.Data

                class Sample {
                    fun plan(secretFieldKey: String) {
                        val builder = Data.Builder()
                        builder.putString(INPUT_SECRET_FIELD_KEY, secretFieldKey)
                    }
                    companion object {
                        const val INPUT_SECRET_FIELD_KEY = "secret_field_key"
                    }
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoSecretInWorkPayload: ignores credential stores outside WorkManager files") {
        val findings =
            rule("NoSecretInWorkPayload").findingsForSource(
                "data/src/webdav/WebDavCredentialStore.kt",
                """
                package com.lomo.data.webdav

                class Store(private val prefs: SecureStringStore) {
                    fun setPassword(password: String?) {
                        prefs.putString(KEY_PASSWORD, password)
                    }
                    companion object {
                        const val KEY_PASSWORD = "password"
                    }
                }

                interface SecureStringStore {
                    fun putString(key: String, value: String?)
                }
                """,
            )

        findings shouldBe emptyList()
    }

    // ---- NoNamePredicateDelete -------------------------------------------------

    test("NoNamePredicateDelete: flags name.contains inside removeIf") {
        val findings =
            rule("NoNamePredicateDelete").findingsForSource(
                "data/src/repository/Sample.kt",
                """
                package com.lomo.data.repository

                import java.io.File

                class Sample {
                    fun prune(key: String, files: MutableList<File>) {
                        files.removeIf { it.name.contains(key) }
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "exact identity"
    }

    test("NoNamePredicateDelete: flags name predicate inside delete-named function") {
        val findings =
            rule("NoNamePredicateDelete").findingsForSource(
                "data/src/repository/Sample.kt",
                """
                package com.lomo.data.repository

                import java.io.File

                class Sample {
                    fun deleteStaleBackups(dir: File, prefix: String) {
                        dir.listFiles()
                            ?.firstOrNull { it.name.startsWith(prefix) }
                            ?.delete()
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("NoNamePredicateDelete: allows exact name equality in delete context") {
        val findings =
            rule("NoNamePredicateDelete").findingsForSource(
                "data/src/repository/Sample.kt",
                """
                package com.lomo.data.repository

                import java.io.File

                class Sample {
                    fun deleteFile(dir: File, name: String) {
                        dir.listFiles()
                            ?.firstOrNull { it.name == name }
                            ?.delete()
                    }
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoNamePredicateDelete: allows fuzzy name match outside destructive context") {
        val findings =
            rule("NoNamePredicateDelete").findingsForSource(
                "app/src/feature/Sample.kt",
                """
                package com.lomo.app.feature

                class Sample {
                    fun search(query: String, names: List<String>): List<String> =
                        names.filter { it.contains(query) }
                }
                """,
            )

        findings shouldBe emptyList()
    }

    // ---- NoCorruptionEmptyReset -------------------------------------------------

    test("NoCorruptionEmptyReset: flags emptyPreferences corruption reset") {
        val findings =
            rule("NoCorruptionEmptyReset").findingsForSource(
                "data/src/local/datastore/LomoDataStore.kt",
                """
                package com.lomo.data.local.datastore

                import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
                import androidx.datastore.preferences.core.emptyPreferences

                val handler = ReplaceFileCorruptionHandler { emptyPreferences() }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "corruption"
    }

    test("NoCorruptionEmptyReset: allows recovery handler") {
        val findings =
            rule("NoCorruptionEmptyReset").findingsForSource(
                "data/src/local/datastore/Sample.kt",
                """
                package com.lomo.data.local.datastore

                import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
                import androidx.datastore.preferences.core.Preferences

                val handler = ReplaceFileCorruptionHandler { error -> recover(error) }

                fun recover(error: Throwable): Preferences = throw error
                """,
            )

        findings shouldBe emptyList()
    }

    // ---- NoDomainClock -----------------------------------------------------------

    test("NoDomainClock: flags System.currentTimeMillis in domain") {
        val findings =
            rule("NoDomainClock").findingsForSource(
                "domain/src/usecase/Sample.kt",
                """
                package com.lomo.domain.usecase

                class Sample {
                    fun load(): Long = System.currentTimeMillis()
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "clock"
    }

    test("NoDomainClock: flags LocalDate.now in domain body") {
        val findings =
            rule("NoDomainClock").findingsForSource(
                "domain/src/usecase/Sample.kt",
                """
                package com.lomo.domain.usecase

                import java.time.LocalDate

                class Sample {
                    fun load(): LocalDate = LocalDate.now()
                }
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("NoDomainClock: allows injected date provider default") {
        val findings =
            rule("NoDomainClock").findingsForSource(
                "domain/src/usecase/Sample.kt",
                """
                package com.lomo.domain.usecase

                import java.time.LocalDate

                class Sample(
                    private val currentDateProvider: () -> LocalDate = LocalDate::now,
                    private val dateSnapshotProvider: () -> Long = { LocalDate.now().toEpochDay() },
                )
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoDomainClock: allows wall clock outside domain") {
        val findings =
            rule("NoDomainClock").findingsForSource(
                "data/src/repository/Sample.kt",
                """
                package com.lomo.data.repository

                class Sample {
                    fun load(): Long = System.currentTimeMillis()
                }
                """,
            )

        findings shouldBe emptyList()
    }

    // ---- NoConstantStatusValue -----------------------------------------------------

    test("NoConstantStatusValue: flags literal progress argument") {
        val findings =
            rule("NoConstantStatusValue").findingsForSource(
                "data/src/engine/lan/Sample.kt",
                """
                package com.lomo.data.engine.lan

                sealed interface ShareTransferState {
                    data class Transferring(val progress: Float) : ShareTransferState
                }

                class Sample {
                    private val state = ShareTransferState.Transferring(progress = 0f)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "progress"
    }

    test("NoConstantStatusValue: flags positional literal on transferring state ctor") {
        val findings =
            rule("NoConstantStatusValue").findingsForSource(
                "data/src/engine/lan/Sample.kt",
                """
                package com.lomo.data.engine.lan

                class Sample {
                    private val state = ShareTransferState.Transferring(0f)
                }

                sealed interface ShareTransferState {
                    data class Transferring(val progress: Float) : ShareTransferState
                }
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("NoConstantStatusValue: allows measured progress value") {
        val findings =
            rule("NoConstantStatusValue").findingsForSource(
                "data/src/engine/lan/Sample.kt",
                """
                package com.lomo.data.engine.lan

                class Sample {
                    fun publish(sent: Long, total: Long) {
                        val state = ShareTransferState.Transferring(sent.toFloat() / total)
                    }
                }

                sealed interface ShareTransferState {
                    data class Transferring(val progress: Float) : ShareTransferState
                }
                """,
            )

        findings shouldBe emptyList()
    }
})

private fun rule(
    name: String,
    config: Config = Config.empty,
): Rule =
    checkNotNull(LomoArchitectureRuleSetProvider().instance().rules[RuleName(name)]) {
        "Expected rule '$name' to be registered."
    }.invoke(config)

private fun Rule.findingsForSource(
    relativePath: String,
    code: String,
): List<Finding> {
    val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
    val file = tempDir.resolve(relativePath)
    file.parent.createDirectories()
    file.writeText(code.trimIndent())
    return lint(compileForTest(file))
}
