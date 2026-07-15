package com.lomo.data.sync

import com.lomo.data.repository.RemoteSyncDirection
import com.lomo.data.repository.RemoteSyncLocalSnapshot
import com.lomo.data.repository.RemoteSyncReason
import com.lomo.data.repository.RemoteSyncRemoteAbsenceVerification
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: RustSyncPlannerProtocol
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: encode provider-neutral S3/WebDAV planner requests and decode strict Rust plan envelopes.
 *
 * Scenarios:
 * - Given a shared empty-S3 golden request, when Kotlin encodes the same request, then every protocol byte matches.
 * - Given a shared Rust plan golden envelope, when it is decoded, then actions and pending-change count are observable as a RemoteSyncPlan.
 * - Given an unknown protocol version, when decoding starts, then the boundary rejects it before any plan is exposed.
 * - Given a truncated or path-invalid request, when encoding/decoding starts, then the boundary rejects it explicitly.
 *
 * Observable outcomes:
 * - Encoded bytes, decoded RemoteSyncPlan, and RustSyncProtocolException.
 *
 * TDD proof:
 * - RED before implementation: RustSyncPlannerProtocol and its strict boundary exception do not exist.
 *
 * Excludes:
 * - JNI loading, native library packaging, file/Room/network execution, and planner action application.
 */
class RustSyncPlannerProtocolTest : DataFunSpec() {
    init {
        test("given shared empty S3 golden request when Kotlin encodes then every protocol byte matches") {
            val bytes = RustSyncPlannerProtocol.encodeRequest(emptyRequest(RustSyncBackend.S3))

            bytes.toList() shouldBe goldenBytes("empty-s3-request.hex").toList()
        }

        test("given Rust upload plan envelope when decoded then provider-neutral action is returned") {
            val plan = RustSyncPlannerProtocol.decodePlan(
                goldenBytes("local-only-upload-plan.hex"),
            )

            plan.actions.single().path shouldBe "memo.md"
            plan.actions.single().direction shouldBe RemoteSyncDirection.UPLOAD
            plan.actions.single().reason shouldBe RemoteSyncReason.LOCAL_ONLY
            plan.pendingChanges shouldBe 1
        }

        test("given shared local-only S3 request when Kotlin encodes then every protocol byte matches") {
            val request = emptyRequest(RustSyncBackend.S3).copy(
                localFiles = listOf(RemoteSyncLocalSnapshot(path = "memo.md", lastModified = 20L)),
            )

            RustSyncPlannerProtocol.encodeRequest(request).toList() shouldBe
                goldenBytes("local-only-s3-request.hex").toList()
        }

        test("given unknown plan version when decoded then boundary rejects it") {
            val bytes = bytes("4c4f4d4f6300")

            shouldThrow<RustSyncProtocolException> {
                RustSyncPlannerProtocol.decodePlan(bytes)
            }.reason shouldBe RustSyncProtocolError.UnsupportedVersion(99)
        }

        test("given truncated plan envelope when decoded then boundary rejects it") {
            shouldThrow<RustSyncProtocolException> {
                RustSyncPlannerProtocol.decodePlan(bytes("4c4f4d4f0100"))
            }.reason shouldBe RustSyncProtocolError.Truncated
        }

        test("given malformed UTF-8 action path when decoded then boundary rejects it") {
            shouldThrow<RustSyncProtocolException> {
                RustSyncPlannerProtocol.decodePlan(
                    bytes("4c4f4d4f01000100000001000000ff010101000000"),
                )
            }.reason shouldBe RustSyncProtocolError.InvalidString("action path")
        }

        test("given pending count mismatch when decoded then boundary rejects it") {
            shouldThrow<RustSyncProtocolException> {
                RustSyncPlannerProtocol.decodePlan(bytes("4c4f4d4f01000000000001000000"))
            }.reason shouldBe RustSyncProtocolError.PendingCountMismatch(expected = 0, actual = 1)
        }

        test("given path traversal in request when encoded then boundary rejects it") {
            val request = emptyRequest(RustSyncBackend.WebDav).copy(
                localFiles = listOf(
                    RemoteSyncLocalSnapshot(
                        path = "../memo.md",
                        lastModified = 1L,
                    ),
                ),
            )

            shouldThrow<RustSyncProtocolException> {
                RustSyncPlannerProtocol.encodeRequest(request)
            }.reason shouldBe RustSyncProtocolError.InvalidPath("../memo.md")
        }

        test("given empty plan envelope when decoded then no actions are exposed") {
            val plan = RustSyncPlannerProtocol.decodePlan(bytes("4c4f4d4f01000000000000000000"))

            plan.actions.shouldBeEmpty()
            plan.pendingChanges shouldBe 0
        }
    }

    private fun emptyRequest(backend: RustSyncBackend) =
        RustSyncPlannerRequest(
            backend = backend,
            timestampToleranceMs = 0L,
            localFiles = emptyList(),
            remoteFiles = emptyList(),
            metadata = emptyList(),
            preResolvedActions = emptyList(),
            suppressedPaths = emptyList(),
            missingRemoteVerification = emptyList(),
            defaultMissingRemoteVerification = RemoteSyncRemoteAbsenceVerification.VERIFIED_ABSENT,
        )

    private fun bytes(hex: String): ByteArray =
        hex.chunked(2).map { Integer.parseInt(it, 16).toByte() }.toByteArray()

    private fun goldenBytes(name: String): ByteArray {
        val hex =
            checkNotNull(javaClass.getResourceAsStream("/rust-sync/$name")) {
                "Missing shared Rust sync golden vector: $name"
            }.bufferedReader().use { it.readText().trim() }
        return bytes(hex)
    }
}
