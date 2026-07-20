package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: WorkspaceWriteAuthority.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: shared write choke reports Ready+!freeze only; requireWritable fails closed otherwise.
 *
 * Scenarios:
 * - Given Ready and unfrozen, when isWritable/requireWritable run, then writable succeeds.
 * - Given freeze, when requireWritable runs, then it fails closed.
 * - Given ReadOnlyRecovery, when requireWritable runs, then it fails closed.
 *
 * Observable outcomes: boolean gate + exception messages.
 * TDD proof: fails before WorkspaceWriteAuthority exists.
 * Excludes: storage backend I/O.
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.model.EngineReadiness
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class WorkspaceWriteAuthorityTest : DataFunSpec() {
    init {
        test("given Ready and unfrozen when isWritable then true and requireWritable succeeds") {
            val authority =
                WorkspaceWriteAuthority(
                    engineReadinessRepository = FakeEngineReadinessRepository(),
                    writeFreezeRepository = ProcessWriteFreezeRepository(),
                )
            authority.isWritable() shouldBe true
            authority.requireWritable()
        }

        test("given write freeze when requireWritable then fails closed") {
            val freeze = ProcessWriteFreezeRepository()
            freeze.begin()
            val authority =
                WorkspaceWriteAuthority(
                    engineReadinessRepository = FakeEngineReadinessRepository(),
                    writeFreezeRepository = freeze,
                )
            authority.isWritable() shouldBe false
            shouldThrow<IllegalStateException> {
                authority.requireWritable()
            }.message.shouldContain("switch is in progress")
        }

        test("given non-Ready engine when requireWritable then fails closed") {
            val authority =
                WorkspaceWriteAuthority(
                    engineReadinessRepository =
                        FakeEngineReadinessRepository(
                            EngineReadiness.Opening,
                        ),
                    writeFreezeRepository = ProcessWriteFreezeRepository(),
                )
            authority.isWritable() shouldBe false
            shouldThrow<IllegalStateException> {
                authority.requireWritable()
            }.message.shouldContain("opening")
        }
    }
}
