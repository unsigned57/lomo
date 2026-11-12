package com.lomo.app.feature.main

import com.lomo.app.testing.fakes.FakeMarkdownWorkspaceRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.SingleDispatcherProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: sibling fingerprint updates reuse rendered paging rows without a background round trip; owner: app; P1.
 * Scenarios:
 * - Given a cached render and a new source fingerprint, when paging maps the current memo,
 *   then mapping completes synchronously with current metadata and the same render document.
 * Observable outcomes: completion before background dispatch, current memo, render identity and one render.
 * TDD proof: target/lomo/reports/manual/four-bugs/metadata-paging-red.log observes an unfinished mapping;
 * GREEN: mapping finishes before the background lane runs.
 * Excludes: native parsing cost and physical-device frame duration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoMetadataPagingContinuityTest :
    FunSpec({
        test("given only a sibling document fingerprint changes then paging does not wait for background rendering") {
            runTest {
                val rendered = mutableListOf<String>()
                val mapper = MemoUiMapper(
                    dispatcherProvider = SingleDispatcherProvider(StandardTestDispatcher(testScheduler)),
                    markdownWorkspaceRepository = FakeMarkdownWorkspaceRepository(onRender = rendered::add),
                )
                val original = Memo(
                    id = "memo",
                    timestamp = 1L,
                    content = "body",
                    rawContent = "body",
                    dateKey = "2026_11_04",
                    fileFingerprint = "first-fingerprint",
                    contentRevision = 1L,
                )
                val before = mapper.mapToCachedUiModel(original, null, null, emptyMap(), emptyList())
                val current = original.copy(fileFingerprint = "second-fingerprint")
                val mapping = async(UnconfinedTestDispatcher(testScheduler)) {
                    mapper.peekOrMapToCachedUiModel(current, null, null, emptyMap(), emptyList())
                }
                mapping.isCompleted shouldBe true
                val after = mapping.await()
                after.memo shouldBe current
                after.renderDocument shouldBeSameInstanceAs before.renderDocument
                rendered shouldBe listOf("body")
            }
        }
    })
