# AI Kotlin Test Style

Use this guide after reading `quality/testing/ai-meaningful-tests.md`. It narrows the approved Kotlin test stack and the default authoring patterns for BDD+TDD tests in this repository.

## Stack

- Kotest 6.x:
  - `kotest-runner-junit5`
  - `kotest-assertions-core`
  - `kotest-property`
  - `kotest-framework-engine`
- MockK
- `kotlinx-coroutines-test`
- `app.cash.turbine`

`src/test` files should use Kotest `FunSpec` by default. JUnit Vintage was never adopted; the migration to Kotest is complete and JUnit4 dependencies are removed. Do not introduce new JUnit4 tests, direct JUnit5 APIs, Mockito, Strikt, AssertK, Kotlin Power-Assert, or alternate runners.

## Spec Form

Put **all** tests in a single `FunSpec({ ... })` constructor block, or in a
single `init { ... }` block. Do **not** open a fresh `init` block per test:
multiple `init` blocks all run, but the resulting file reads as a mechanical
JUnit4 translation rather than a Kotest spec.

```kotlin
// Anti-pattern (do not write this)
class XTest : DomainFunSpec() {
    init { test("a") { ... } }
    init { test("b") { ... } }
    init { test("c") { ... } }
}

// Preferred - constructor block
class XTest : FunSpec({
    test("a") { ... }
    test("b") { ... }
    test("c") { ... }
})

// Acceptable when extending a project base class - single init block
class XTest : DomainFunSpec() {
    init {
        test("a") { ... }
        test("b") { ... }
        test("c") { ... }
    }
}
```

When the spec needs shared `lateinit var` or extension registration, declare
them inside the single `init` block alongside `beforeTest`/`afterTest`.

## Default BDD Spec Style

Use `FunSpec` unless the task explicitly needs a different Kotest style. Keep the BDD shape in the test name and assertion, not in a new framework:

```kotlin
class MemoStatisticsUseCaseTest : FunSpec({
    val expectedStats = MemoStatistics(totalMemos = 3, totalWords = 12, totalCharacters = 48)
    val fakeRepository = FakeMemoRepository(statistics = expectedStats)

    test("given stored memos when statistics are requested then counts are returned by status") {
        val useCase = MemoStatisticsUseCase(repository = fakeRepository)

        useCase() shouldBe expectedStats
    }
})
```

## Fake-First Collaborators

Use hand-written `Fake*` implementations for collaborators that hold meaningful state, emulate storage, expose flows, or would otherwise need repeated stubbing:

- DAOs
- Repositories
- DataSources
- file-system providers
- preference stores
- stateful use cases or coordinators

The fake's in-memory behavior is part of the contract. Prefer MockK only for stateless collaborators such as loggers, one-shot publishers, Android/framework wrappers, external SDK seams, failure injection, or coordinator-boundary ordering checks where ordering is itself the behavior.

Reject tests that only call `verify { ... }` without also asserting an observable outcome.

Shared fakes live in each owning module's test tree, usually under `.../testing/fakes/`. Before writing a new fake, search the owning module and app/domain shared test helpers.

Avoid `mockk(relaxed = true)`. A relaxed mock hides missing behavior and often turns a BDD scenario into a scripted interaction. If MockK is retained, the test should make the allowed reason obvious from the collaborator type or local setup.

## Coroutine Scaffolding

Use the shared Kotest extension in `app/src/test/java/com/lomo/app/testing/MainDispatcherExtension.kt` instead of repeating `Dispatchers.setMain` / `resetMain` in each file.

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest : FunSpec({
    val mainDispatcher = MainDispatcherExtension()
    val expectedStats = MemoStatistics(totalMemos = 2, totalWords = 4, totalCharacters = 20)
    val fakeRepository = FakeStatisticsRepository(result = expectedStats)
    extension(mainDispatcher)

    test("updates state after refresh") {
        runTest {
            val viewModel = StatisticsViewModel(repository = fakeRepository)

            viewModel.refresh()
            advanceUntilIdle()

            viewModel.uiState.value shouldBe StatisticsUiState.Loaded(expectedStats)
        }
    }
})
```

## Test Isolation

`AppFunSpec` (and module-equivalent base classes) overrides Kotest's default
isolation mode to `InstancePerRoot`. A fresh spec instance is created per root
`test("...")` block, so per-test `lateinit var` fields and `beforeTest` setup
do not leak across tests.

When extending the module base class, you get this for free:

```kotlin
class MyViewModelTest : AppFunSpec() {
    private lateinit var fakeRepo: FakeMemoRepository

    init {
        beforeTest { fakeRepo = FakeMemoRepository() }
        test("emits initial state") { ... }
        test("emits loaded state") { ... } // fakeRepo is a fresh instance
    }
}
```

When writing a brand-new spec without state, prefer `class XTest : FunSpec({ ... })`
directly - no base class needed, default `SingleInstance` isolation is fine for
stateless tests.

## Flow Assertions Use Turbine

Prefer Turbine when downstream emissions are part of the contract:

```kotlin
viewModel.uiState.test {
    awaitItem() shouldBe Initial
    viewModel.onEvent(...)
    awaitItem() shouldBe Loading
    awaitItem() shouldBe Loaded(...)
    cancelAndConsumeRemainingEvents()
}
```

Do not stop at `flow.first()` when later emissions are part of the user-visible contract. Use `first()` only when the behavior is explicitly the initial value or a single-shot flow.

## Property-Based Testing

`kotest-property` is on the test classpath of every module. Use it for parsers,
formatters, validators, and pure policy functions where a representative input
class can be generated rather than enumerated.

```kotlin
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

test("storage filename round-trips for any non-empty ASCII title") {
    checkAll(Arb.string(minSize = 1, maxSize = 64, codepoints = Codepoint.ascii())) { title ->
        val encoded = StorageFilenameFormats.encode(title)
        StorageFilenameFormats.decode(encoded) shouldBe title
    }
}
```

Default `Arb` count is 1000. Drop to `checkAll(100, Arb.x()) { ... }` when the
generator is expensive. Property tests count toward Kover coverage like any
other test.

Do not use property-based testing for ViewModel state machines or repository
orchestration - the input space is not naturally a generator; use scenario
matrix tests there instead.

## What To Assert

- `domain` UseCase: returned value, thrown error type, ordering only when ordering is part of the contract
- `app` ViewModel: emitted `StateFlow` state or one-off event at the user-observable boundary
- parser/util code: input-to-output mapping, including malformed-input failure mode
- `data` repository/sync code: persisted DB/file/preference state, mapped result, propagated cancellation, conflict classification, or explicit operation ordering

## Type-Narrowing Assertions

Prefer `shouldBeInstanceOf<T>()` over `(x is T) shouldBe true`. The Kotest matcher
performs smart-cast, gives a better failure message, and removes the manual `as T`
cast that almost always follows.

```kotlin
// Discouraged
(result is MemoValidationResult.Invalid.ContentTooLong) shouldBe true
val invalid = result as MemoValidationResult.Invalid.ContentTooLong
invalid.maxLength shouldBe MemoConstraints.MAX_MEMO_LENGTH

// Preferred
val invalid = result.shouldBeInstanceOf<MemoValidationResult.Invalid.ContentTooLong>()
invalid.maxLength shouldBe MemoConstraints.MAX_MEMO_LENGTH
```

Use `result.shouldBeTypeOf<T>()` only when you need exact-type equality (no
subclasses); `shouldBeInstanceOf<T>` accepts subclasses.

## What Not To Assert

- Kotlin source file string contents
- mock call counts without an outcome assertion
- private fields or helper structure
- mixed `org.junit.Assert.*` and `io.kotest.matchers.*` imports in the same file
- implementation-only helper calls that could change without user-visible behavior changing

## Multi-Property Assertions

When asserting several properties of one result, use `assertSoftly { ... }` so
a failing earlier assertion does not mask a later one:

```kotlin
val state = viewModel.uiState.value.shouldBeInstanceOf<MainUiState.Loaded>()
assertSoftly(state) {
    memos shouldHaveSize 3
    selectedDate shouldBe LocalDate.of(2026, 5, 16)
    isRefreshing shouldBe false
}
```

When a single assertion's failure would not obviously identify the test
scenario (e.g., a loop over generated data, or a non-trivial computed value),
wrap it in `withClue`:

```kotlin
withClue("zone=$zone, today=$today") {
    stats.earliestDailyMemoTime shouldBe expected
}
```

Both helpers are framework-agnostic Kotest features; they do not require any
extension or listener.

## Naming

Use Kotest `test("...")` strings. Keep names behavior-focused and compatible with the Behavior Contract. Given/when/then naming is preferred for branch-heavy behavior; concise imperative names are fine for pure parser or policy utilities. CJK and punctuation are fine when they improve clarity.

```kotlin
test("given CJK text with spaces when tokenized then words remain searchable") { ... }
```
