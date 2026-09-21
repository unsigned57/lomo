# Kotlin Tests

Read `ai-meaningful-tests.md` first. This file owns Kotlin test conventions, not gate commands.

## Approved stack and spec shape

Use Kotest 6.x (`kotest-runner-junit5`, assertions, property and engine), MockK where appropriate,
`kotlinx-coroutines-test`, and Turbine. Do not add direct JUnit APIs, JUnit Vintage/JUnit4 tests,
Mockito, AssertK, Strikt, AssertJ, Power-Assert or another runner. JUnit Platform is the Kotest host,
not an instruction to author JUnit tests.

Use exactly one `FunSpec({ ... })` constructor block or one `init { ... }` in a project base spec.
Test names describe Given/When/Then behavior; no separate BDD framework is needed.

```kotlin
class QueryTest : FunSpec({
    test("given stored memos when queried then the matching page is returned") {
        val repository = FakeMemoRepository(storedMemos)
        QueryUseCase(repository)(filter) shouldBe expectedPage
    }
})
```

Use fresh fixtures per root test. `AppFunSpec` and equivalent module bases use `InstancePerRoot`;
with plain `FunSpec`, construct stateful collaborators inside the test or reset them in `beforeTest`.

## Collaborators and asynchronous behavior

- Use stateful `Fake*` implementations for repositories, stores, data sources, filesystem providers,
  preferences and collaborators that expose flows. Search the owning test tree before duplicating one.
- Use MockK for stateless/framework seams, failure injection or an explicit ordering contract.
  Never use `mockk(relaxed = true)` or verification-only tests without an observable outcome.
- Use `runTest` and virtual time. Reuse `app/test/testing/MainDispatcherExtension.kt` for Main;
  do not repeat `Dispatchers.setMain/resetMain` or use `Thread.sleep`.
- Use Turbine when later emissions matter. `first()` is appropriate only when the contract is the
  initial value or a single result. Assert cancellation, replacement and failure emissions explicitly.

```kotlin
runTest {
    val model = makeModel(fakeRepository)
    model.uiState.test {
        awaitItem() shouldBe Initial
        model.refresh()
        awaitItem() shouldBe Loading
        awaitItem() shouldBe Loaded(expected)
        cancelAndConsumeRemainingEvents()
    }
}
```

## Assertions and input selection

| Unit | Observable assertion |
| --- | --- |
| Use case or parser | result, classified failure, parsed structure or bytes |
| ViewModel/coordinator | emitted state/event sequence and user-visible ordering |
| Repository/platform adapter | persisted state, cancellation propagation, mapped failure or ordering |
| Detekt/architecture policy | findings or command outcome on negative and positive fixtures |

Prefer `shouldBeInstanceOf<T>()` (or exact `shouldBeTypeOf<T>()`) over Boolean type assertions and
manual casts. Use `assertSoftly` for several independent properties of one result, and `withClue`
for parameterized scenarios whose failure would otherwise lack context.

Use property tests for parsers, codecs, normalization and algebraic laws. State machines and
repository orchestration need explicit scenario matrices first. A generated event trace additionally
needs a reference model, bounded input, reproducible seed and an observable invariant; random
orchestration without an oracle is not a useful property test.

Do not test Kotlin source strings as a proxy for behavior, private fields, or pure rendering details.
Architecture-rule fixtures are the documented exception: source is the actual policy input.
Keep contract metadata and RED/GREEN evidence according to the common test guide.
