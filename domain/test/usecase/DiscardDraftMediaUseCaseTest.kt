package com.lomo.domain.usecase

/*
 * Behavior Contract:
 * - Unit under test: DiscardDraftMediaUseCase
 * - Owning layer: domain
 * - Priority tier: P1
 * - Capability: best-effort removal of tracked draft media basenames.
 *
 * Scenarios:
 * - Given filenames, when invoke runs, then removeImage is called for each basename.
 *
 * Observable outcomes: FakeMediaRepository remove call counts.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=domain --include-classes='com.lomo.domain.usecase.DiscardDraftMediaUseCaseTest'
 * - RED: DiscardMemoDraftAttachmentsUseCase assumed Kotlin attachment ownership after stage model cutover.
 * - GREEN: DiscardDraftMediaUseCase removes each tracked draft basename through MediaRepository.
 *
 * Excludes: filesystem / Rust stage discard.
 */

import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMediaRepository
import io.kotest.matchers.shouldBe

class DiscardDraftMediaUseCaseTest : DomainFunSpec() {
    private val mediaRepository = FakeMediaRepository()
    private val useCase = DiscardDraftMediaUseCase(mediaRepository)

    init {
        test("removes each tracked draft basename") {
            useCase(listOf("a.jpg", "b.png"))
            mediaRepository.removedImageIds.size shouldBe 2
        }
    }
}
