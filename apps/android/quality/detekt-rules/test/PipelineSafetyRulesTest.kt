package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: NoSwallowedCancellationInPagingSourceRule
 * - Owning layer: quality (custom detekt rules)
 * - Priority tier: P0
 *
 * Capability:
 * - NoSwallowedCancellationInPagingSourceRule: ensure PagingSource.load rethrows CancellationException
 *   when handling errors rather than converting it to LoadResult.Error (Q2).
 *   (NoLazyFlowSharing was removed: NoUnboundedFlowSharing covers SharingStarted.Lazily already.)
 *
 * Scenarios:
 * - Given PagingSource.load converting errors to LoadResult.Error without rethrowing CancellationException,
 *   then 1 finding is reported.
 * - Given PagingSource.load explicitly rethrowing CancellationException before LoadResult.Error,
 *   then 0 findings are reported.
 * - Given a load that only observes a delegated Error result and uses finally for cleanup,
 *   cancellation escapes naturally and no finding is reported.
 * - Given a narrow IOException catch, cancellation is outside its handled type.
 * - Given cancellation words only in comments or an unrelated catch, swallowing still reports.
 * - Given a Result failure lambda, cancellation must propagate before error conversion.
 *
 * Observable outcomes: exact finding counts for parsed Kotlin failure boundaries.
 * TDD proof: the delegated try/finally and narrow catch initially produce false findings, while
 * a comment mentioning cancellation hides an actual swallowed exception from the text search.
 * Excludes: Android runtime, generated bindings and whole-program type resolution.
 */
class PipelineSafetyRulesTest : FunSpec({
    test("registers NoSwallowedCancellationInPagingSource in rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules
        rules[RuleName("NoSwallowedCancellationInPagingSource")].shouldNotBeNull()
    }

    test("flags PagingSource.load when CancellationException is swallowed into LoadResult.Error") {
        val findings =
            rule("NoSwallowedCancellationInPagingSource").findingsForSource(
                "data/src/engine/store/SamplePagingSource.kt",
                """
                package com.lomo.data.engine.store

                import androidx.paging.PagingSource

                class SamplePagingSource : PagingSource<Int, String>() {
                    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> {
                        return try {
                            LoadResult.Page(listOf("data"), null, null)
                        } catch (e: Exception) {
                            LoadResult.Error(e)
                        }
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Swallowed CancellationException in PagingSource.load"
    }

    test("allows PagingSource.load when CancellationException is explicitly rethrown") {
        val findings =
            rule("NoSwallowedCancellationInPagingSource").findingsForSource(
                "data/src/engine/store/SamplePagingSource.kt",
                """
                package com.lomo.data.engine.store

                import androidx.paging.PagingSource
                import kotlinx.coroutines.CancellationException

                class SamplePagingSource : PagingSource<Int, String>() {
                    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> {
                        return try {
                            LoadResult.Page(listOf("data"), null, null)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            LoadResult.Error(e)
                        }
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("allows a delegating PagingSource to observe errors and clean up in finally") {
        pagingFindings(
            """
            try {
                val result = delegate.load(params)
                if (result is LoadResult.Error) notifyError(result.throwable)
                return result
            } finally {
                notifyLoading(false)
            }
            """,
        ).shouldHaveSize(0)
    }

    test("allows narrow IOException conversion that cannot swallow coroutine cancellation") {
        pagingFindings(
            """
            return try {
                delegate.load(params)
            } catch (failure: java.io.IOException) {
                LoadResult.Error(failure)
            }
            """,
        ).shouldHaveSize(0)
    }

    test("cancellation words in a comment cannot mask a swallowing catch") {
        pagingFindings(
            """
            return try {
                delegate.load(params)
            } catch (failure: Exception) {
                // CancellationException should throw here, but this branch drops it.
                LoadResult.Error(failure)
            }
            """,
        ).shouldHaveSize(1)
    }

    test("a different catch rethrow cannot validate an unsafe error conversion") {
        pagingFindings(
            """
            try {
                checkReady()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
            }
            return try {
                delegate.load(params)
            } catch (failure: Throwable) {
                LoadResult.Error(failure)
            }
            """,
        ).shouldHaveSize(1)
    }

    test("Result failure conversion requires a guard for its own throwable") {
        pagingFindings(
            """
            return result.fold(
                onSuccess = { page -> page },
                onFailure = { failure -> LoadResult.Error(failure) },
            )
            """,
        ).shouldHaveSize(1)
        pagingFindings(
            """
            return result.fold(
                onSuccess = { page -> page },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    LoadResult.Error(failure)
                },
            )
            """,
        ).shouldHaveSize(0)
    }
})

private fun pagingFindings(body: String): List<dev.detekt.api.Finding> =
    rule("NoSwallowedCancellationInPagingSource").findingsForSource(
        "app/src/feature/common/SamplePagingSource.kt",
        """
        package com.lomo.app.feature.common
        import androidx.paging.PagingSource
        import kotlinx.coroutines.CancellationException

        class SamplePagingSource : PagingSource<Int, String>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> {
                $body
            }
        }
        """,
    )

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
): List<dev.detekt.api.Finding> {
    val tempDir = Files.createTempDirectory("lomo-detekt-pipeline-test")
    val file = tempDir.resolve(relativePath)
    file.parent.createDirectories()
    file.writeText(code.trimIndent())
    return lint(compileForTest(file))
}
