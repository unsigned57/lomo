package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.collections.shouldNotContain

/*
 * Behavior Contract:
 * - Unit under test: Memo permanent-delete mutation boundary.
 * - Owning layer: data repository lifecycle mutation pipeline.
 * - Priority tier: P0.
 * - Capability: production permanent delete is reachable only through the
 *   lifecycle command/outbox boundary.
 *
 * Scenarios:
 * - Given data-layer trash mutation operations, when callers inspect the
 *   production mutation API, then no direct permanent-delete success method is
 *   exposed outside the DB-first outbox enqueue path.
 * - Given the trash mutation handler, when callers inspect its callable
 *   boundary, then no direct permanent-delete success method is exposed outside
 *   lifecycle outbox completion.
 *
 * Observable outcomes:
 * - public mutation API member set.
 *
 * TDD proof:
 * - Fails before the fix because MemoTrashMutationOperations still exposes
 *   deletePermanently, allowing callers to bypass the lifecycle outbox command.
 *
 * Excludes:
 * - clear-trash batch semantics, app/UI callers, and transport upload.
 */
class MemoPermanentDeleteBoundaryTest : DataFunSpec() {
    init {
        test("given trash mutation operations when permanent delete is requested then only db first outbox enqueue is exposed") {
            val operationNames = MemoTrashMutationOperations::class.members.map { it.name }
            val handlerNames = MemoTrashMutationHandler::class.members.map { it.name }

            operationNames.shouldNotContain("deletePermanently")
            handlerNames.shouldNotContain("deleteFromTrashPermanently")
        }
    }
}
