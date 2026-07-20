package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: AndroidPlatformActionAccess.
 * - Owning layer: data Android capability edge.
 * - Priority tier: P0.
 * - Capability: execute one Rust-authored platform action against a capability-bound SAF tree and
 *   application-private exchange files, returning typed outputs with independently verifiable
 *   length/digest/fingerprint evidence and AlreadySatisfied only after postcondition match.
 *
 * Scenarios:
 * - Given a registered capability and present document, when Stat runs, then metadata carries
 *   target/kind/length/digest/fingerprint without content bytes.
 * - Given expected fingerprint Match and current evidence equals it, when Delete/Write is replayed,
 *   then AlreadySatisfied is returned without a second side effect.
 * - Given expected fingerprint Match and current evidence differs, when Write runs, then a structured
 *   conflict failure is returned and no write occurs.
 * - Given a revoked/unknown capability, when any action runs, then permission failure is returned
 *   before gateway access.
 * - Given ReadToExchange, when executed, then source streams into the exchange file and artifact
 *   digest/length match the written bytes.
 * - Given an escaped exchange token or workspace path, when executed, then validation fails closed.
 *
 * Observable outcomes:
 * - ActionOutcome variants, gateway call counts, exchange file bytes, evidence fields.
 *
 * TDD proof:
 * - RED: AndroidPlatformActionAccess does not exist.
 *
 * Excludes:
 * - Real ContentResolver/DocumentsContract (faked via PlatformDocumentsGateway).
 * - Batch orchestration (AndroidPlatformActionExecutor) and Rust job advancement.
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.nativebridge.ActionOutcome
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.ExpectedFingerprint
import com.lomo.nativebridge.PlatformAction
import com.lomo.nativebridge.PlatformActionOutput
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import com.lomo.nativebridge.ActionEvidence
import com.lomo.nativebridge.ExchangeArtifact
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.MessageDigest

class AndroidPlatformActionAccessTest : DataFunSpec() {
    init {
        test("given present file when Stat runs then typed metadata evidence is returned") {
            val fixture = Fixture()
            fixture.registry.register(CAPABILITY, TREE_URI)
            fixture.gateway.seedFile("memo.md", content = "hello", documentId = "doc-memo")

            val outcome = fixture.access.execute(
                PlatformAction.Stat("action-stat", CAPABILITY, WorkspaceTarget.Relative("memo.md")),
            )

            val applied = outcome.shouldBeInstanceOf<ActionOutcome.Applied>()
            val output = applied.output.shouldBeInstanceOf<PlatformActionOutput.Stat>()
            output.metadata.target shouldBe WorkspaceTarget.Relative("memo.md")
            output.metadata.kind shouldBe DocumentKind.FILE
            output.metadata.evidence.length shouldBe 5uL
            output.metadata.evidence.digest shouldBe sha256Hex("hello".toByteArray())
            output.metadata.evidence.fingerprint shouldBe
                PlatformActionEvidence.fingerprint(
                    documentId = "doc-memo",
                    lastModifiedEpochMillis = fixture.gateway.lastModified("memo.md"),
                    length = 5uL,
                )
            fixture.gateway.sideEffectCount shouldBe 0
        }

        test("given target already absent when delete is replayed then AlreadySatisfied without side effect") {
            val fixture = Fixture()
            fixture.registry.register(CAPABILITY, TREE_URI)
            // Side effect already completed: path is gone. Replay verifies absence only.
            fixture.gateway.exists("trash/memo.md") shouldBe false

            val outcome =
                fixture.access.execute(
                    PlatformAction.Delete(
                        "action-delete",
                        CAPABILITY,
                        "trash/memo.md",
                        ExpectedFingerprint.Absent,
                    ),
                )

            outcome.shouldBeInstanceOf<ActionOutcome.AlreadySatisfied>()
            fixture.gateway.sideEffectCount shouldBe 0
            fixture.gateway.exists("trash/memo.md") shouldBe false
        }

        test("given present target with matching preimage when delete runs then Applied removes the file") {
            val fixture = Fixture()
            fixture.registry.register(CAPABILITY, TREE_URI)
            fixture.gateway.seedFile("trash/memo.md", content = "gone", documentId = "doc-trash")
            val evidence =
                fixture.currentEvidence(
                    path = "trash/memo.md",
                    content = "gone",
                    documentId = "doc-trash",
                )

            val outcome =
                fixture.access.execute(
                    PlatformAction.Delete(
                        "action-delete",
                        CAPABILITY,
                        "trash/memo.md",
                        ExpectedFingerprint.Match(evidence),
                    ),
                )

            outcome.shouldBeInstanceOf<ActionOutcome.Applied>()
            fixture.gateway.sideEffectCount shouldBe 1
            fixture.gateway.exists("trash/memo.md") shouldBe false
        }

        test("given mismatched expected fingerprint when write runs then conflict without side effect") {
            val fixture = Fixture()
            fixture.registry.register(CAPABILITY, TREE_URI)
            fixture.gateway.seedFile("memo.md", content = "old", documentId = "doc-memo")
            val exchange = fixture.resolver.resolveFile("exchange-write")
            exchange.writeBytes("new-bytes".toByteArray())
            val mismatched =
                ActionEvidence(
                    length = 3uL,
                    digest = sha256Hex("old".toByteArray()),
                    fingerprint = "not-the-real-fingerprint",
                )

            val outcome =
                fixture.access.execute(
                    PlatformAction.WriteFromExchange(
                        "action-write",
                        CAPABILITY,
                        ExchangeArtifact(
                            token = "exchange-write",
                            length = "new-bytes".length.toULong(),
                            digest = sha256Hex("new-bytes".toByteArray()),
                        ),
                        "memo.md",
                        WriteMode.REPLACE,
                        ExpectedFingerprint.Match(mismatched),
                    ),
                )

            val failed = outcome.shouldBeInstanceOf<ActionOutcome.Failed>()
            failed.failure.category shouldBe "conflict"
            failed.failure.code shouldBe "platform_postcondition_mismatch"
            fixture.gateway.sideEffectCount shouldBe 0
            fixture.gateway.read("memo.md") shouldBe "old".toByteArray()
        }

        test("given unknown capability when action runs then permission fails before gateway access") {
            val fixture = Fixture()
            fixture.gateway.seedFile("memo.md", content = "x", documentId = "doc")

            val outcome =
                fixture.access.execute(
                    PlatformAction.Stat("action-stat", CAPABILITY, WorkspaceTarget.Relative("memo.md")),
                )

            val failed = outcome.shouldBeInstanceOf<ActionOutcome.Failed>()
            failed.failure.category shouldBe "permission"
            failed.failure.code shouldBe "unknown_capability_token"
            fixture.gateway.statCount shouldBe 0
        }

        test("given readable source when ReadToExchange runs then exchange artifact matches stream") {
            val fixture = Fixture()
            fixture.registry.register(CAPABILITY, TREE_URI)
            fixture.gateway.seedFile("memo.md", content = "stream-me", documentId = "doc-memo")

            val outcome =
                fixture.access.execute(
                    PlatformAction.ReadToExchange(
                        "action-read",
                        CAPABILITY,
                        "memo.md",
                        "exchange-read",
                        ExpectedFingerprint.Absent,
                    ),
                )

            val applied = outcome.shouldBeInstanceOf<ActionOutcome.Applied>()
            val output = applied.output.shouldBeInstanceOf<PlatformActionOutput.ReadToExchange>()
            output.artifact.token shouldBe "exchange-read"
            output.artifact.length shouldBe "stream-me".length.toULong()
            output.artifact.digest shouldBe sha256Hex("stream-me".toByteArray())
            fixture.resolver.resolveFile("exchange-read").readBytes() shouldBe "stream-me".toByteArray()
        }

        test("given escaped exchange token when ReadToExchange runs then validation fails closed") {
            val fixture = Fixture()
            fixture.registry.register(CAPABILITY, TREE_URI)
            fixture.gateway.seedFile("memo.md", content = "x", documentId = "doc")

            val outcome =
                fixture.access.execute(
                    PlatformAction.ReadToExchange(
                        "action-read",
                        CAPABILITY,
                        "memo.md",
                        "../escape",
                        ExpectedFingerprint.Absent,
                    ),
                )

            val failed = outcome.shouldBeInstanceOf<ActionOutcome.Failed>()
            failed.failure.category shouldBe "validation"
            failed.failure.code shouldBe "invalid_exchange_token"
            fixture.gateway.readCount shouldBe 0
        }
    }

    private class Fixture {
        val registry = CapabilityRegistry()
        val exchangeRoot = kotlin.io.path.createTempDirectory("lomo-exchange-access").toFile()
        val resolver = ExchangeResolver(exchangeRoot)
        val gateway = FakePlatformDocumentsGateway()
        val access =
            AndroidPlatformActionAccess(
                registry = registry,
                exchange = resolver,
                documents = gateway,
            )

        fun currentEvidence(
            path: String,
            content: String,
            documentId: String,
        ): ActionEvidence =
            ActionEvidence(
                length = content.length.toULong(),
                digest = sha256Hex(content.toByteArray()),
                fingerprint =
                    PlatformActionEvidence.fingerprint(
                        documentId = documentId,
                        lastModifiedEpochMillis = gateway.lastModified(path),
                        length = content.length.toULong(),
                    ),
            )
    }

    private companion object {
        const val CAPABILITY = "saf-root-1"
        const val TREE_URI = "content://com.lomo.nativesmoke.documents/tree/root"
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

/**
 * In-memory SAF tree collaborator. Records side-effecting mutations so replay tests can prove
 * AlreadySatisfied does not re-execute Android writes.
 */
internal class FakePlatformDocumentsGateway : PlatformDocumentsGateway {
    private data class Node(
        val documentId: String,
        val kind: DocumentKind,
        val mimeType: String?,
        val bytes: ByteArray,
        val lastModified: Long,
    )

    private val files = linkedMapOf<String, Node>()
    var sideEffectCount: Int = 0
        private set
    var statCount: Int = 0
        private set
    var readCount: Int = 0
        private set

    fun seedFile(
        path: String,
        content: String,
        documentId: String,
        lastModified: Long = 1_700_000_000_000L,
    ) {
        files[path] =
            Node(
                documentId = documentId,
                kind = DocumentKind.FILE,
                mimeType = "text/markdown",
                bytes = content.toByteArray(),
                lastModified = lastModified,
            )
    }

    fun exists(path: String): Boolean = files.containsKey(path)

    fun read(path: String): ByteArray? = files[path]?.bytes?.copyOf()

    fun lastModified(path: String): Long = files.getValue(path).lastModified

    override fun stat(
        treeUri: String,
        target: WorkspaceTarget,
    ): PlatformDocumentSnapshot? {
        statCount += 1
        return when (target) {
            is WorkspaceTarget.Root ->
                PlatformDocumentSnapshot(
                    target = WorkspaceTarget.Root,
                    kind = DocumentKind.DIRECTORY,
                    mimeType = null,
                    length = 0uL,
                    lastModifiedEpochMillis = 0L,
                    documentId = "root",
                    digest = EMPTY_SHA256,
                )
            is WorkspaceTarget.Relative -> files[target.path]?.toSnapshot(target)
        }
    }

    override fun listChildren(
        treeUri: String,
        target: WorkspaceTarget,
        cursor: String?,
        pageSize: UInt,
    ): PlatformMetadataPage {
        val prefix =
            when (target) {
                is WorkspaceTarget.Root -> ""
                is WorkspaceTarget.Relative -> target.path.trimEnd('/') + "/"
            }
        val items =
            files.entries
                .filter { (path, _) ->
                    if (prefix.isEmpty()) {
                        !path.contains('/')
                    } else {
                        path.startsWith(prefix) && !path.removePrefix(prefix).contains('/')
                    }
                }.map { (path, node) -> node.toSnapshot(WorkspaceTarget.Relative(path)) }
                .take(pageSize.toInt())
        return PlatformMetadataPage(items = items, nextCursor = null)
    }

    override fun ensureDirectory(
        treeUri: String,
        path: String,
    ): PlatformDocumentSnapshot {
        sideEffectCount += 1
        val node =
            files.getOrPut(path) {
                Node(
                    documentId = "dir-$path",
                    kind = DocumentKind.DIRECTORY,
                    mimeType = null,
                    bytes = ByteArray(0),
                    lastModified = 1L,
                )
            }
        return node.toSnapshot(WorkspaceTarget.Relative(path))
    }

    override fun openRead(
        treeUri: String,
        path: String,
    ): PlatformReadHandle {
        readCount += 1
        val node = files[path] ?: error("missing $path")
        return PlatformReadHandle(
            snapshot = node.toSnapshot(WorkspaceTarget.Relative(path)),
            bytes = node.bytes.copyOf(),
        )
    }

    override fun writeFromExchange(
        treeUri: String,
        path: String,
        bytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot {
        sideEffectCount += 1
        val existing = files[path]
        if (mode == WriteMode.CREATE && existing != null) {
            error("create refused over existing path")
        }
        val node =
            Node(
                documentId = existing?.documentId ?: "doc-$path",
                kind = DocumentKind.FILE,
                mimeType = mimeType ?: "application/octet-stream",
                bytes = bytes.copyOf(),
                lastModified = (existing?.lastModified ?: 0L) + 1L,
            )
        files[path] = node
        return node.toSnapshot(WorkspaceTarget.Relative(path))
    }

    override fun move(
        treeUri: String,
        source: String,
        target: String,
    ): PlatformDocumentSnapshot {
        sideEffectCount += 1
        val node = files.remove(source) ?: error("missing source")
        files[target] = node.copy(documentId = "doc-$target", lastModified = node.lastModified + 1)
        return files.getValue(target).toSnapshot(WorkspaceTarget.Relative(target))
    }

    override fun delete(
        treeUri: String,
        path: String,
    ) {
        sideEffectCount += 1
        files.remove(path)
    }

    private fun Node.toSnapshot(target: WorkspaceTarget): PlatformDocumentSnapshot =
        PlatformDocumentSnapshot(
            target = target,
            kind = kind,
            mimeType = mimeType,
            length = bytes.size.toULong(),
            lastModifiedEpochMillis = lastModified,
            documentId = documentId,
            digest = sha256Hex(bytes),
        )
}

private val EMPTY_SHA256 = sha256Hex(ByteArray(0))
