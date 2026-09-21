package com.lomo.data.worker

/*
 * Behavior Contract:
 * Capability: persist deferred remote-sync WorkData across process death without secrets;
 * owning layer: data; priority: P0.
 * Scenarios:
 * - Given saved WorkData, when take() runs, then the same keys return and the file is consumed.
 * - Given an empty store, when take() runs, then null.
 * - Given a file larger than WorkManager's Data byte budget, when take() runs, then the read is
 *   rejected as IOException before decoding and the poison file is removed.
 * Observable outcomes: Data keys/values, subsequent take() emptiness, typed oversize failure.
 * TDD proof: ./kotlin test --include-module=data --include-classes='com.lomo.data.worker.FileDeferredLockWorkStoreTest'
 * Excludes: WorkManager enqueue, security session machine.
 */

import androidx.work.Data
import androidx.work.workDataOf
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files

class FileDeferredLockWorkStoreTest : DataFunSpec() {
    init {
        test("given saved work data when taken then payload is restored once") {
            val file = Files.createTempFile("deferred-lock", ".bin").toFile()
            val store = FileDeferredLockWorkStore(file)
            val input =
                workDataOf(
                    RustSyncWorkRequest.INPUT_WORKSPACE_ROOT to "/ws",
                    RustSyncWorkRequest.INPUT_BACKEND_KIND to "webdav",
                    RustSyncWorkRequest.INPUT_IDENTITY_FIELD_KEY to "WEBDAV_USERNAME",
                )

            store.save(input)
            val restored = store.take()

            restored?.getString(RustSyncWorkRequest.INPUT_WORKSPACE_ROOT) shouldBe "/ws"
            restored?.getString(RustSyncWorkRequest.INPUT_BACKEND_KIND) shouldBe "webdav"
            restored?.getString(RustSyncWorkRequest.INPUT_IDENTITY_FIELD_KEY) shouldBe "WEBDAV_USERNAME"
            store.take().shouldBeNull()
        }

        test("given an oversized deferred file when taken then the budget is rejected and the file is removed") {
            val file = Files.createTempFile("deferred-lock-oversize", ".bin").toFile()
            val store = FileDeferredLockWorkStore(file)
            file.writeBytes(ByteArray(Data.MAX_DATA_BYTES + 1))

            val error = shouldThrow<IOException> { store.take() }

            error.message shouldBe "deferred lock work exceeds its byte budget"
            file.exists() shouldBe false
        }
    }
}
