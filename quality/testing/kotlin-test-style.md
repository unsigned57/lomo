# Kotlin Tests

Use [Meaningful tests](meaningful-tests.md) to decide what evidence a change needs. These rules apply
to Kotlin host specs; device UI flows live in [Android UI test](../android-uitest.md).

## Prescribed stack and spec shape

Use the manifest-pinned Kotest stack (`kotest-runner-junit5`, assertions, property, engine) plus
MockK, `kotlinx-coroutines-test`, and Turbine when needed. Author host specs using Kotest APIs on
JUnit Platform.

Enforced by `ForbiddenTestStackImports`: do **not** import `org.junit.*` (JUnit4 or JUnit5 APIs),
`kotlin.test.*`, `org.mockito.*`, `strikt.*`, `assertk.*`, or `org.assertj.*`, and do not add
Power-Assert or another test runner.

Use one `FunSpec({ ... })` constructor block or one `init { ... }` on a project base spec (multiple
per-test `init` blocks are rejected by `NoPerTestInitBlock`). Name setup, action, and outcome in
natural language. Reuse the owning spec with small input tables for equivalent cases; give distinct
state transitions separate names.

```kotlin
class QueryTest : FunSpec({
    test("given stored memos when queried then the matching page is returned") {
        val repository = FakeMemoRepository(storedMemos)
        QueryUseCase(repository)(filter) shouldBe expectedPage
    }
})
```

Construct fresh fixtures per root test (`AppFunSpec` and sibling module bases use `InstancePerRoot`;
on plain `FunSpec`, construct collaborators inside each test or reset in `beforeTest`).

## Collaborators and asynchronous behavior

- **Fakes for stateful collaborators**: use real values, small fixtures, or module `testing/fakes/`
  implementations for `*Dao`, `*Repository`, `*DataSource`, stores, and flows (`MockedStatefulCollaborator`
  rejects `mockk` on `Dao`/`Repository`/`DataSource`). Give new fakes only the behavior needed.
- **MockK for stateless seams and failures**: use MockK for stateless/framework seams, failure
  injection, or required call ordering. Always assert an observable outcome (`NoInteractionOnlyTest`
  rejects verify-only tests). `NoRelaxedMockk` forbids `mockk(relaxed = true)` and `@RelaxedMockK`
  for product collaborators, allowing them only for Android framework stubs (`Context`,
  `ContentResolver`, `Uri`, `WorkerParameters`, `Application`, `Intent`, `android.*`, `androidx.*`).
- **Coroutines and ViewModels**: use `runTest` for coroutine scopes, schedulers, and virtual time.
  For ViewModels, reuse `apps/android/app/test/testing/MainDispatcherExtension.kt` and share the
  test scheduler. Never call `Thread.sleep` (`NoThreadSleepInTests`) or duplicate
  `Dispatchers.setMain`/`resetMain` setup (`NoDispatchersSetMainInTests`).
- **Flow assertions**: inspect `StateFlow.value` after the action when only the settled state matters.
  Use Turbine (`test { awaitItem() ... }`) when emissions, order, or events matter.
  `NoFlowFirstForStateSequence` forbids `.first()` on `uiState`/`state`/`event`/`events` in
  `*ViewModelTest` specs; reserve `.first()` for genuine single-shot flows elsewhere.

## Assertions and input selection

| Unit | Observable assertion |
| --- | --- |
| Use case or parser | returned value, classified failure, parsed structure, or bytes |
| ViewModel / coordinator | emitted state/event sequence and user-visible ordering |
| Paging | items, continuation, position, or request bound through the real paging path |
| Repository / platform adapter | persisted state, cancellation propagation, mapped failure, or ordering |
| Detekt / architecture policy | findings or CLI outcome on negative and positive fixtures |

Prefer `shouldBeInstanceOf<T>()` (or exact `shouldBeTypeOf<T>()`) over `(x is T) shouldBe true`
(`ShouldBeInstanceOfAssertion`). Use `assertSoftly` for independent properties of one result and
`withClue` for parameterized tables.

Use property tests (`checkAll`/`forAll`) for parsers, codecs, normalization, and algebraic laws.
State machines and repository orchestration require explicit scenario matrices first; any generated
event trace must include a reference model, bounded input, reproducible seed, and invariant oracle.

`NoSourceStringBehaviorTest` forbids asserting on `.kt`/`.kts` source strings outside architecture
specs; legitimate architectural checks belong under `/architecture/` or carry
`// architectural-boundary-check` in the first 30 lines.

## Focused local runs

```bash
./kotlin test --include-module=<module> --include-classes=<fqcn>
```

Repeat `--include-classes` within one module; run separate commands for distinct modules. For
platform-independent `domain` tests, add `--platform=jvm` during iteration and omit it when
validating all host targets under [Quality](../README.md#gate-selection-and-evidence).
