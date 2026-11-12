package com.lomo.app.feature.main

import com.lomo.app.testing.fakes.FakeMarkdownWorkspaceRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.SingleDispatcherProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/*
 * Behavior Contract:
 * Capability: full memo mapping leaves the UI dispatcher responsive; owner: app; P1.
 * Scenarios:
 * - Given a caller on the UI lane, when a full memo cache miss is mapped, then rendering
 *   waits for the background lane while the caller can continue processing input.
 * - Given a changed todo body, when mapped again, then the updated body also renders there.
 * Observable outcomes: render execution lane, caller progress, and current rendered body.
 * TDD proof: RED mapper-red.log (render ran before background dispatch); GREEN app-green.log,
 * under target/lomo/reports/manual/interaction-latency/.
 * Production scope: apps/android/app/src/feature/main/MemoUiMapper.kt
 * Excludes: native parsing speed, storage latency, and physical-device frame timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoUiMapperDispatcherTest :
    FunSpec({
        test("given full memo and todo update when mapped on the UI lane then rendering runs on the background lane") {
            runTest {
                val background = StandardTestDispatcher(testScheduler)
                val rendered = mutableListOf<String>()
                val mapper = MemoUiMapper(
                    dispatcherProvider = SingleDispatcherProvider(background),
                    markdownWorkspaceRepository = FakeMarkdownWorkspaceRepository(onRender = rendered::add),
                )
                val original = Memo(
                    id = "memo",
                    timestamp = 1L,
                    content = "[ ] task",
                    rawContent = "[ ] task",
                    dateKey = "2026_11_01",
                )
                for (memo in listOf(original, original.copy(content = "[x] task", rawContent = "[x] task"))) {
                    val previousRenders = rendered.toList()
                    val mapping = async(UnconfinedTestDispatcher(testScheduler)) {
                        mapper.mapToCachedUiModel(memo, null, null, emptyMap(), emptyList())
                    }
                    // The UI caller has returned before the scheduled parse can run.
                    rendered shouldBe previousRenders
                    mapping.isCompleted shouldBe false
                    yield()
                    mapping.await().renderDocument.plainText shouldBe memo.content
                    rendered.last() shouldBe memo.content
                }
            }
        }
    })
