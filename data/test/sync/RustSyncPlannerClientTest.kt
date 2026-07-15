package com.lomo.data.sync

import com.lomo.data.repository.RemoteSyncDirection
import com.lomo.data.repository.RemoteSyncLocalSnapshot
import com.lomo.data.repository.RemoteSyncReason
import com.lomo.data.repository.RemoteSyncRemoteAbsenceVerification
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: RustSyncPlannerClient
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: cross the UniFFI envelope boundary without exposing generated binding types to repository callers.
 *
 * Scenarios:
 * - Given a provider-neutral local-only request, when the native planner returns the shared upload plan,
 *   then the client returns the decoded RemoteSyncPlan and sends the canonical request bytes.
 * - Given the native boundary rejects a request, when planning runs, then the rejection remains observable.
 *
 * Observable outcomes:
 * - Captured request envelope, returned RemoteSyncPlan, and propagated RustSyncNativePlanningException.
 *
 * TDD proof:
 * - RED before implementation: RustSyncPlannerClient and RustSyncEnvelopePlanner do not exist.
 *
 * Excludes:
 * - Loading the Android shared library, S3/WebDAV action execution, Room state, and network transport.
 */
class RustSyncPlannerClientTest : DataFunSpec() {
    init {
        test("given local-only request when native planner returns upload then client crosses canonical envelope") {
            val native = FakeRustSyncEnvelopePlanner(
                response = goldenBytes("local-only-upload-plan.hex"),
            )
            val client = RustSyncPlannerClient(native)
            val request = emptyRequest().copy(
                localFiles = listOf(RemoteSyncLocalSnapshot(path = "memo.md", lastModified = 20L)),
            )

            val plan = client.plan(request)

            native.capturedInput?.toList() shouldBe goldenBytes("local-only-s3-request.hex").toList()
            plan.actions.single().direction shouldBe RemoteSyncDirection.UPLOAD
            plan.actions.single().reason shouldBe RemoteSyncReason.LOCAL_ONLY
        }

        test("given native rejection when planning runs then typed failure remains observable") {
            val client = RustSyncPlannerClient(
                FakeRustSyncEnvelopePlanner(failure = RustSyncNativePlanningException("invalid protocol")),
            )

            val error = shouldThrowExactly<RustSyncNativePlanningException> { client.plan(emptyRequest()) }

            error.reason shouldBe "invalid protocol"
        }
    }

    private fun emptyRequest() =
        RustSyncPlannerRequest(
            backend = RustSyncBackend.S3,
            timestampToleranceMs = 0L,
            localFiles = emptyList(),
            remoteFiles = emptyList(),
            metadata = emptyList(),
            preResolvedActions = emptyList(),
            suppressedPaths = emptyList(),
            missingRemoteVerification = emptyList(),
            defaultMissingRemoteVerification = RemoteSyncRemoteAbsenceVerification.VERIFIED_ABSENT,
        )

    private fun goldenBytes(name: String): ByteArray {
        val hex =
            checkNotNull(javaClass.getResourceAsStream("/rust-sync/$name")) {
                "Missing shared Rust sync golden vector: $name"
            }.bufferedReader().use { it.readText().trim() }
        return hex.chunked(2).map { Integer.parseInt(it, 16).toByte() }.toByteArray()
    }
}

private class FakeRustSyncEnvelopePlanner(
    private val response: ByteArray = byteArrayOf(),
    private val failure: RustSyncNativePlanningException? = null,
) : RustSyncEnvelopePlanner {
    var capturedInput: ByteArray? = null

    override fun plan(input: ByteArray): ByteArray {
        capturedInput = input
        failure?.let { throw it }
        return response
    }
}
