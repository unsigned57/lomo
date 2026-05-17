# AI Meaningful Test Prompt Template

Use this template when asking AI to add tests in this repository.

```text
Write meaningful tests for <TARGET_CLASS>.

Repository rules:
- Read `AGENTS.md` first, then open `quality/testing/ai-meaningful-tests.md` only after confirming the task is actually test-related.
- Follow `quality/testing/ai-meaningful-tests.md` in full (target priority, test-first policy, locked-tests policy, forbidden test shape, review standard) and `quality/testing/ai-kotlin-test-style.md` (Kotest spec form, coroutine scaffolding, type-narrowing, Fake* collaborators).

Target:
- Layer owner: <app/domain/data/ui-components>
- File: <path>
- Collaborators: <list>
- Why this target matters: <business/sync/state risk>
- Production change expected: <yes/no/unknown>

Required output order:
1. Preflight summary proving you read quality/testing/ai-meaningful-tests.md:
   - target class and owning layer
   - why it is meaningful under P0/P1/P2
   - what you will not test
   - whether this is test-only work or a production-changing task
   - whether any existing tests are contract locks that must stay unchanged
2. Scenario matrix:
    - Happy path
    - Boundary path
    - Failure/cancellation/conflict path
    - Must-not-happen path
3. Short rationale for why each scenario is meaningful
4. Existing test impact check:
   - which current tests already cover adjacent behavior
   - which tests stay unchanged
   - whether any old test must be corrected and why
   - whether there is any risk of “changing the test to fit the implementation”
5. Test Change Justification, only if modifying an existing test:
   - reason category
   - old behavior/assertion being replaced
   - why the old assertion is no longer correct
   - what retained or new test preserves the original coverage
   - why this is not fitting the test to the implementation
6. Reproducer design:
   - which scenario should fail before a production fix
   - why that failure is observable
7. Test code
    - prefer adding a new companion reproducer before editing any old test
    - use Kotest `FunSpec` in constructor-block form: `class XTest : FunSpec({ test("...") { ... } })`. Do NOT open multiple `init { test {} }` blocks.
    - use `shouldBe` / `shouldThrow` / `shouldBeInstanceOf<T>()` / Turbine assertions. Do not write `(x is T) shouldBe true` followed by an `as T` cast.
    - use `MainDispatcherExtension` for app coroutine tests; register via `extension(...)` inside the spec init.
    - prefer hand-written `Fake*` over `mockk(relaxed = true)` for collaborators with meaningful state (DAOs, repositories, data sources, file-system providers).
    - when asserting multiple properties of one result, wrap with `assertSoftly { ... }`.
8. Red-phase proof:
    - exact command to run first
    - expected failing assertion, exception, or symptom
   - or explicit statement that no production change is intended
9. File header comment in this exact format (the script enforces presence of all sections; Scenario matrix is required for newly added files):
    Test Contract:
    - Unit under test: <FQN>
    - Owning layer: <domain | data | app | ui-components>
    - Priority tier: <P0 | P1 | P2>
    Scenario matrix:
    - Happy: <one line>
    - Boundary: <one line>
    - Failure: <one line>
    - Must-not-happen: <one line>
    Observable outcomes:
    - <returned value | emitted state | side-effect ordering | mapped error>
    Red phase:
    - <Fails before fix when ...> OR <Not applicable - test-only coverage; no production change.>
    Excludes:
    - <what is intentionally not asserted>

Required assertions:
- observable return value, emitted state, side-effect ordering, or mapped error
- at least one regression-prone branch
- no private implementation assertions
- no mock-only verification without an outcome assertion
- no source-string assertions outside the documented architecture-boundary exception
- if production code will change, explain why the test should fail pre-fix
- no rewriting of an old test into a weaker or narrower scenario without a documented contract change
- prefer shouldBeInstanceOf<T>() over (x is T) shouldBe true
- when asserting multiple properties of one object, wrap them in assertSoftly
- consider kotest-property `checkAll` for parser/util/policy classes whose input is naturally a generator
```

## Good Targets

- `domain/usecase` classes with branching
- `data/repository` orchestration classes
- `app` ViewModels with user-visible state

## Bad Targets

- pure Compose rendering files
- generated or DI glue
- trivial wrappers with no branch-heavy behavior
