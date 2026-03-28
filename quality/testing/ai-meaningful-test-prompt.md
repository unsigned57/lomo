# AI Meaningful Test Prompt Template

Use this template when asking AI to add tests in this repository.

```text
Write meaningful tests for <TARGET_CLASS>.

Repository rules:
- First read quality/testing/ai-meaningful-tests.md in full, then follow it.
- Prefer behavior-bearing tests over line coverage.
- Refuse to add tests if the target is render-only or wiring-only; recommend a logic extraction instead.
- For new features, bug fixes, and contract changes, write or modify the test before any related production edit.
- For new features, bug fixes, and contract changes, prove the new test is red before applying the fix.
- Treat existing tests as locked behavior contracts by default.
- When an existing test fails after a production edit, do not rewrite the test to match the implementation by default. Prove why the test is wrong before touching it.
- Do not delete, weaken, or narrow an existing test just to make a new implementation pass.

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
8. Red-phase proof:
   - exact command to run first
   - expected failing assertion, exception, or symptom
   - or explicit statement that no production change is intended
9. File header comment in this format:
   Test Contract:
   Observable outcomes:
   Red phase:
   Excludes:

Required assertions:
- observable return value, emitted state, side-effect ordering, or mapped error
- at least one regression-prone branch
- no private implementation assertions
- no mock-only verification without an outcome assertion
- if production code will change, explain why the test should fail pre-fix
- no rewriting of an old test into a weaker or narrower scenario without a documented contract change
```

## Good Targets

- `domain/usecase` classes with branching
- `data/repository` orchestration classes
- `app` ViewModels with user-visible state

## Bad Targets

- pure Compose rendering files
- generated or DI glue
- trivial wrappers with no branch-heavy behavior
