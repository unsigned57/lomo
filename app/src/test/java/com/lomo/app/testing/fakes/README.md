# Test Fakes

Hand-written `Fake*` collaborators for repositories and DAOs in `app/src/test`.
Prefer these over `mockk(relaxed = true)` whenever the collaborator carries
state (in-memory store, counter, sequence) or whenever multiple specs end up
stubbing the same six-plus methods per `beforeTest`.

## Current pilot

- `FakeMemoRepository` — implements [`com.lomo.domain.repository.MemoRepository`]
  with two in-memory `MutableStateFlow<List<Memo>>` (active + trash) and call
  counters for mutating methods.
- `FakeAppConfigRepository` — implements [`com.lomo.domain.repository.AppConfigRepository`]
  with one `MutableStateFlow` per preference, seeded from
  `com.lomo.domain.model.PreferenceDefaults`.

## When to add a new fake

Add a fake when *any* of the following is true:

- the collaborator has meaningful state (in-memory store, counter, sequence)
- two or more specs would otherwise repeat the same `every { ... } returns ...`
  scaffolding
- a ViewModel spec stubs six or more methods just to construct the system under
  test

## Pattern

- **File location:** `app/src/test/java/com/lomo/app/testing/fakes/Fake<Type>.kt`
- **Visibility:** `class` (top-level, `public` default). Never `private`.
- **State:** `MutableStateFlow` / `MutableSharedFlow` / `MutableMap` — never
  Mockk handles.
- **Coroutine signatures:** match the interface exactly — including suspend
  modifiers and `Flow` return types.
- **Defaults:** seed from `PreferenceDefaults` or other named-default sources;
  never magic numbers.
- **Mutation surface:** expose `set*` helpers next to backing fields so test
  code stays declarative.
- **Verification surface:** expose plain `var fooCallCount: Int` (with private
  setter) instead of MockK `coVerify`. If a spec needs argument capture, expose
  a recorded `List<Args>` snapshot.
