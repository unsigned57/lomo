// architectural-boundary-check
package com.lomo.app.feature.common

import com.lomo.app.feature.main.MainMemoListStateHolder
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.main.SidebarViewModel
import com.lomo.app.feature.review.DailyReviewViewModel
import com.lomo.app.feature.search.SearchViewModel
import com.lomo.app.feature.tag.TagFilterViewModel
import com.lomo.app.feature.trash.TrashViewModel
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.widget.WidgetEntryPoint
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.MemoStatisticsUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.SyncConflictResolutionUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/*
 * Behavior Contract:
 * - Unit under test: memo repository injection boundary.
 * - Owning layer: domain ports with app injection surfaces.
 * - Priority tier: P0
 * - Capability: production-facing memo use cases and app consumers expose narrow repository ports
 *   instead of aggregate memo facades.
 *
 * Scenarios:
 * - Given a memo use case constructor is inspected, when it declares repository needs, then it
 *   must not accept the aggregate MemoRepository facade.
 * - Given a production app memo consumer is inspected, when Hilt resolves dependencies, then it
 *   must not accept MemoUiCoordinator or a replacement all-capability app memo facade.
 * - Given production app source files are inspected, when a class owns memo ports, then no app
 *   class may gather query, mutation, search, statistics, and trash ports.
 * - Given test fake source files are inspected, when a fake owns memo ports, then no test fake may
 *   gather query, mutation, search, statistics, and trash ports.
 *
 * Observable outcomes:
 * - Reflection-visible constructor parameter types, entry-point return types, and source-fixture
 *   matches for broad app memo facades and all-port memo test fakes.
 *
 * TDD proof:
 * - RED before deleting the app facade because MemoUiCoordinator still existed and owned all memo
 *   ports behind one injectable app boundary.
 *
 * Excludes:
 * - Data-layer repository implementation details, Room SQL behavior, and UI rendering.
 */
class MemoRepositoryBoundaryContractTest : AppFunSpec() {
    init {
        test("memo use cases and app entry points do not expose aggregate memo repository") {
            val constructorLeaks =
                MEMO_USE_CASE_CONSTRUCTORS.flatMap { owner ->
                    owner.declaredConstructors
                        .filter { constructor ->
                            constructor.parameterTypes.any { parameter ->
                                parameter.name == AGGREGATE_MEMO_REPOSITORY
                            }
                        }.map { constructor -> "${owner.simpleName}${constructor.parameterTypes.toList()}" }
                }

            val entryPointLeaks =
                WidgetEntryPoint::class.java.methods
                    .filter { method ->
                        method.returnType.name == AGGREGATE_MEMO_REPOSITORY ||
                            method.parameterTypes.any { parameter ->
                                parameter.name == AGGREGATE_MEMO_REPOSITORY
                            }
                    }.map { method -> "WidgetEntryPoint.${method.name}" }

            val aggregateTypeLeak = existingTypeLeak(AGGREGATE_MEMO_REPOSITORY)

            withClue("Aggregate MemoRepository must not be a production-facing memo boundary") {
                (constructorLeaks + entryPointLeaks + listOfNotNull(aggregateTypeLeak)).shouldBeEmpty()
            }
        }

        test("production app memo consumers cannot inject a broad app memo facade") {
            val staleFacadeTypeLeak = existingTypeLeak(MEMO_UI_COORDINATOR)

            val directFacadeInjections =
                PRODUCTION_APP_MEMO_CONSUMERS.flatMap { consumer ->
                    consumer.declaredConstructors
                        .filter { constructor ->
                            constructor.parameterTypes.any { parameter ->
                                parameter.name == MEMO_UI_COORDINATOR
                            }
                        }.map { constructor -> "${consumer.name}${constructor.parameterTypes.toList()}" }
                }

            val replacementFacadeInjections =
                PRODUCTION_APP_MEMO_CONSUMERS.flatMap { consumer ->
                    consumer.declaredConstructors.flatMap { constructor ->
                        constructor.parameterTypes
                            .filter { parameter ->
                                parameter.name.startsWith(APP_PACKAGE_PREFIX) &&
                                    parameter.constructorParameterTypeNames().containsAll(ALL_MEMO_CAPABILITY_PORTS)
                            }.map { parameter ->
                                "${consumer.name} injects all-capability app memo facade ${parameter.name}"
                            }
                    }
                }

            val sourceFixtureLeaks = productionAppFilesGatheringAllMemoPorts()

            withClue("App memo consumers must inject narrow ports/use cases, not app-wide memo facades") {
                (
                    listOfNotNull(staleFacadeTypeLeak) +
                        directFacadeInjections +
                        replacementFacadeInjections +
                        sourceFixtureLeaks
                ).shouldBeEmpty()
            }
        }

        test("memo test fakes cannot gather every memo repository capability") {
            val sourceFixtureLeaks = testFakeFilesGatheringAllMemoPorts()

            withClue("Memo test fakes must model narrow capability ports, not the removed aggregate repository") {
                sourceFixtureLeaks.shouldBeEmpty()
            }
        }

        test("memo fake scanner catches direct property nested factory and delegated all-port aggregation") {
            val source =
                """
                package com.lomo.app.testing.fakes

                class DirectAllPorts(
                    private val query: MemoQueryRepository,
                    private val mutation: MemoMutationRepository,
                    private val search: MemoSearchRepository,
                    private val statistics: MemoStatisticsRepository,
                    private val trash: MemoTrashRepository,
                )

                val memoRepository =
                    object {
                        val queryRepository: MemoQueryRepository = query()
                        val mutationRepository: MemoMutationRepository = mutation()
                        val searchRepository: MemoSearchRepository = search()
                        val statisticsRepository: MemoStatisticsRepository = statistics()
                        val trashRepository: MemoTrashRepository = trash()
                    }

                object NestedFixture {
                    object AllPorts {
                        val queryRepository: MemoQueryRepository = query()
                        val mutationRepository: MemoMutationRepository = mutation()
                        val searchRepository: MemoSearchRepository = search()
                        val statisticsRepository: MemoStatisticsRepository = statistics()
                        val trashRepository: MemoTrashRepository = trash()
                    }
                }

                fun fakeMemoRepositories(): Any {
                    val queryRepository: MemoQueryRepository = query()
                    val mutationRepository: MemoMutationRepository = mutation()
                    val searchRepository: MemoSearchRepository = search()
                    val statisticsRepository: MemoStatisticsRepository = statistics()
                    val trashRepository: MemoTrashRepository = trash()
                    return listOf(queryRepository, mutationRepository, searchRepository, statisticsRepository, trashRepository)
                }

                class DelegatedAllPorts(
                    queryRepository: MemoQueryRepository,
                    mutationRepository: MemoMutationRepository,
                    searchRepository: MemoSearchRepository,
                    statisticsRepository: MemoStatisticsRepository,
                    trashRepository: MemoTrashRepository,
                ) : MemoQueryRepository by queryRepository,
                    MemoMutationRepository by mutationRepository,
                    MemoSearchRepository by searchRepository,
                    MemoStatisticsRepository by statisticsRepository,
                    MemoTrashRepository by trashRepository

                class MethodFamilyAllPorts {
                    fun getAllMemosList(): Any = query()
                    fun getMemosPage(): Any = query()
                    fun getMemoById(): Any = query()
                    fun saveMemo(): Any = mutation()
                    fun deleteMemo(): Any = mutation()
                    fun getMemosByTagPagingSource(): Any = search()
                    fun getActiveDayCount(): Any = statistics()
                    fun getDeletedMemosPage(): Any = trash()
                    fun restoreMemo(): Any = trash()
                }
                """.trimIndent()

            val detectedScopes = source.memoCapabilityAggregationScopes().map { scope -> scope.name }

            detectedScopes.shouldContainAll(
                "DirectAllPorts",
                "memoRepository",
                "AllPorts",
                "fakeMemoRepositories",
                "DelegatedAllPorts",
                "MethodFamilyAllPorts",
            )
        }
    }

    private fun existingTypeLeak(typeName: String): String? =
        try {
            "Stale boundary type still exists: ${Class.forName(typeName).name}"
        } catch (_: ClassNotFoundException) {
            null
        }

    private fun Class<*>.constructorParameterTypeNames(): Set<String> =
        declaredConstructors
            .flatMap { constructor -> constructor.parameterTypes.map(Class<*>::getName) }
            .toSet()

    private fun productionAppFilesGatheringAllMemoPorts(): List<String> {
        val mainSourceRoot = resolveModuleRoot("app").resolve("src/main/java")
        return mainSourceRoot
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .mapNotNull { file ->
                val text = file.readText()
                if (ALL_MEMO_CAPABILITY_PORT_SIMPLE_NAMES.all(text::contains)) {
                    "all memo ports gathered in ${file.relativeTo(mainSourceRoot).path}"
                } else {
                    null
                }
            }.toList()
    }

    private fun testFakeFilesGatheringAllMemoPorts(): List<String> {
        val moduleRoots = listOf(resolveModuleRoot("domain"), resolveModuleRoot("app"))
        return moduleRoots.flatMap { moduleRoot ->
            val testFakesRoot = moduleRoot.resolve("src/test/java")
            testFakesRoot
                .walkTopDown()
                .filter { file -> file.isFile && file.extension == "kt" && "fakes" in file.relativeTo(testFakesRoot).path }
                .flatMap { file ->
                    val text = file.readText()
                    text.memoCapabilityAggregationScopes()
                        .map { scope ->
                            "all memo ports gathered by ${scope.kind} ${scope.name} at " +
                                "${moduleRoot.name}/${file.relativeTo(testFakesRoot).path}:${scope.lineNumber}"
                        }
                }.toList()
        }
    }

    private fun String.memoCapabilityAggregationScopes(): List<SourceScope> =
        (listOf(fileScope()) + declarationScopes() + propertyDeclarationScopes())
            .filter { scope ->
                ALL_MEMO_CAPABILITY_PORT_SIMPLE_NAMES.all(scope.text::contains) ||
                    MEMO_CAPABILITY_METHOD_FAMILIES.all { methodFamily ->
                        methodFamily.any { methodName -> scope.declaredFunctionNames.contains(methodName) }
                    }
            }

    private fun String.fileScope(): SourceScope =
        SourceScope(
            kind = "file",
            name = "<file>",
            lineNumber = 1,
            text = this,
        )

    private val SourceScope.declaredFunctionNames: Set<String>
        get() =
            FUNCTION_NAME_PATTERN
                .findAll(text)
                .map { match -> match.groupValues[1] }
                .toSet()

    private fun String.declarationScopes(): List<SourceScope> =
        DECLARATION_PATTERN
            .findAll(this)
            .map { match ->
                val declarationStart = match.range.first
                val indentedEnd =
                    declarationScopeEnd(
                        declarationStart = declarationStart,
                        declarationIndent = leadingWhitespaceAt(declarationStart),
                    )
                val openBrace =
                    indexOf('{', startIndex = declarationStart)
                        .takeIf { index -> index >= 0 && index < indentedEnd }
                val scopeEnd = openBrace?.let { braceIndex -> findMatchingBrace(braceIndex) }?.plus(1) ?: indentedEnd
                SourceScope(
                    kind = match.groupValues[1],
                    name = match.groupValues[2],
                    lineNumber = lineNumberAt(declarationStart),
                    text = substring(declarationStart, scopeEnd),
                )
            }.toList()

    private fun String.leadingWhitespaceAt(offset: Int): Int {
        val lineStart = lastIndexOf('\n', startIndex = offset).takeIf { index -> index >= 0 }?.plus(1) ?: 0
        return substring(lineStart, offset).takeWhile(Char::isWhitespace).length
    }

    private fun String.propertyDeclarationScopes(): List<SourceScope> =
        PROPERTY_DECLARATION_PATTERN
            .findAll(this)
            .filter { match -> propertyDeclarationLine(match.range.first).isMemoRepositoryPropertyCandidate() }
            .map { match ->
                SourceScope(
                    kind = match.groupValues[2],
                    name = match.groupValues[3],
                    lineNumber = lineNumberAt(match.range.first),
                    text = substring(match.range.first, propertyDeclarationEnd(match.range.first, match.groupValues[1].length)),
                )
            }.toList()

    private fun String.propertyDeclarationLine(declarationStart: Int): String {
        val lineEnd = indexOf('\n', startIndex = declarationStart).takeIf { it >= 0 } ?: length
        return substring(declarationStart, lineEnd)
    }

    private fun String.isMemoRepositoryPropertyCandidate(): Boolean =
        ALL_MEMO_CAPABILITY_PORT_SIMPLE_NAMES.any(this::contains) ||
            ALL_MEMO_CAPABILITY_PROPERTY_NAMES.any(this::contains) ||
            ALL_MEMO_FIXTURE_PROPERTY_NAMES.any(this::contains)

    private fun String.declarationScopeEnd(
        declarationStart: Int,
        declarationIndent: Int,
    ): Int =
        indentedScopeEnd(
            declarationStart = declarationStart,
            declarationIndent = declarationIndent,
        )

    private fun String.propertyDeclarationEnd(
        declarationStart: Int,
        declarationIndent: Int,
    ): Int =
        indentedScopeEnd(
            declarationStart = declarationStart,
            declarationIndent = declarationIndent,
        )

    private fun String.indentedScopeEnd(
        declarationStart: Int,
        declarationIndent: Int,
    ): Int {
        var index = declarationStart
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var lastLineEnd = length
        while (index < length) {
            when (this[index]) {
                '(' -> parenDepth += 1
                ')' -> parenDepth -= 1
                '[' -> bracketDepth += 1
                ']' -> bracketDepth -= 1
                '{' -> braceDepth += 1
                '}' -> braceDepth -= 1
                '\n' -> {
                    lastLineEnd = index
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        val nextLineStart = index + 1
                        val nextLineEnd = indexOf('\n', startIndex = nextLineStart).takeIf { it >= 0 } ?: length
                        val nextLine = substring(nextLineStart, nextLineEnd)
                        val nextIndent = nextLine.takeWhile(Char::isWhitespace).length
                        if (nextLine.isBlank() || nextIndent <= declarationIndent && nextLine.startsNewDeclaration()) {
                            return lastLineEnd
                        }
                    }
                }
            }
            index += 1
        }
        return lastLineEnd
    }

    private fun String.startsNewDeclaration(): Boolean =
        trimStart().let { line ->
            NEW_DECLARATION_PREFIXES.any(line::startsWith)
        }

    private fun String.findMatchingBrace(openBrace: Int): Int? {
        var depth = 0
        for (index in openBrace until length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun String.lineNumberAt(offset: Int): Int =
        count { index, char -> index < offset && char == '\n' } + 1

    private inline fun String.count(predicate: (Int, Char) -> Boolean): Int {
        var count = 0
        forEachIndexed { index, char ->
            if (predicate(index, char)) {
                count += 1
            }
        }
        return count
    }

    private data class SourceScope(
        val kind: String,
        val name: String,
        val lineNumber: Int,
        val text: String,
    )

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
                currentDir.parentFile?.resolve(moduleName),
            )
                .filterNotNull()
        return checkNotNull(
            candidateRoots.firstOrNull { dir ->
                dir.name == moduleName && dir.resolve("build.gradle.kts").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }

    private companion object {
        private const val APP_PACKAGE_PREFIX = "com.lomo.app."
        private const val AGGREGATE_MEMO_REPOSITORY = "com.lomo.domain.repository.MemoRepository"
        private const val MEMO_UI_COORDINATOR = "com.lomo.app.feature.common.MemoUiCoordinator"

        private val MEMO_USE_CASE_CONSTRUCTORS =
            listOf(
                CreateMemoUseCase::class.java,
                DailyReviewQueryUseCase::class.java,
                DeleteMemoUseCase::class.java,
                MemoStatisticsUseCase::class.java,
                SyncAndRebuildUseCase::class.java,
                SyncConflictResolutionUseCase::class.java,
                ToggleMemoCheckboxUseCase::class.java,
                UpdateMemoContentUseCase::class.java,
            )

        private val PRODUCTION_APP_MEMO_CONSUMERS =
            listOf(
                MainViewModel::class.java,
                MainMemoListStateHolder::class.java,
                SearchViewModel::class.java,
                TagFilterViewModel::class.java,
                TrashViewModel::class.java,
                DailyReviewViewModel::class.java,
                SidebarViewModel::class.java,
            )

        private val ALL_MEMO_CAPABILITY_PORTS =
            setOf(
                "com.lomo.domain.repository.MemoQueryRepository",
                "com.lomo.domain.repository.MemoMutationRepository",
                "com.lomo.domain.repository.MemoSearchRepository",
                "com.lomo.domain.repository.MemoStatisticsRepository",
                "com.lomo.domain.repository.MemoTrashRepository",
            )

        private val ALL_MEMO_CAPABILITY_PORT_SIMPLE_NAMES =
            ALL_MEMO_CAPABILITY_PORTS.map { typeName -> typeName.substringAfterLast('.') }
        private val ALL_MEMO_CAPABILITY_PROPERTY_NAMES =
            listOf(
                "queryRepository",
                "mutationRepository",
                "searchRepository",
                "statisticsRepository",
                "trashRepository",
            )
        private val ALL_MEMO_FIXTURE_PROPERTY_NAMES =
            listOf(
                "memoRepository",
                "memoRepositories",
                "memoStore",
                "memoFixture",
                "memoFakes",
                "allMemoPorts",
            )

        private val DECLARATION_PATTERN =
            Regex("""\b(class|object|fun)\s+([A-Za-z_][A-Za-z0-9_]*)\b""")
        private val PROPERTY_DECLARATION_PATTERN =
            Regex("""(?m)^([ \t]*)((?:val|var))\s+([A-Za-z_][A-Za-z0-9_]*)\b""")
        private val FUNCTION_NAME_PATTERN =
            Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        private val MEMO_CAPABILITY_METHOD_FAMILIES =
            listOf(
                setOf(
                    "getAllMemosList",
                    "getMemosByDateRange",
                    "getGalleryMemosList",
                    "getRecentMemos",
                    "getMemosPage",
                    "getMemoCount",
                    "getDailyReviewCandidateBoundary",
                    "getDailyReviewCandidatePage",
                    "getDefaultMainListIndexInWindow",
                    "getMemoById",
                    "getMainListPagingSource",
                    "getMainListCountFlow",
                    "isSyncing",
                ),
                setOf(
                    "refreshMemos",
                    "saveMemo",
                    "updateMemo",
                    "deleteMemo",
                    "setMemoPinned",
                ),
                setOf(
                    "getMemosByTagPagingSource",
                ),
                setOf(
                    "getMemoCountFlow",
                    "getMemoTimestampsFlow",
                    "getMemoCountByDateFlow",
                    "getTagCountsFlow",
                    "getActiveDayCount",
                ),
                setOf(
                    "getDeletedMemosPage",
                    "getTrashMemos",
                    "restoreMemo",
                    "restoreMemoFromTrash",
                    "deletePermanently",
                    "clearTrash",
                ),
            )
        private val NEW_DECLARATION_PREFIXES =
            listOf(
                "class ",
                "object ",
                "fun ",
                "val ",
                "var ",
                "private ",
                "internal ",
                "public ",
                "protected ",
                "data ",
                "sealed ",
                "enum ",
            )
    }
}
