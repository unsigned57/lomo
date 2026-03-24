# AI Meaningful Test Prompt Template

Use this template when asking AI to add tests in this repository.

```text
Write meaningful tests for <TARGET_CLASS>.

Repository rules:
- First read quality/testing/ai-meaningful-tests.md in full, then follow it.
- Prefer behavior-bearing tests over line coverage.
- Refuse to add tests if the target is render-only or wiring-only; recommend a logic extraction instead.

Target:
- Layer owner: <app/domain/data/ui-components>
- File: <path>
- Collaborators: <list>
- Why this target matters: <business/sync/state risk>

Required output order:
1. Preflight summary proving you read quality/testing/ai-meaningful-tests.md:
   - target class and owning layer
   - why it is meaningful under P0/P1/P2
   - what you will not test
2. Scenario matrix:
   - Happy path
   - Boundary path
   - Failure/cancellation/conflict path
   - Must-not-happen path
3. Short rationale for why each scenario is meaningful
4. Test code
5. File header comment in this format:
   Test Contract:
   Observable outcomes:
   Excludes:

Required assertions:
- observable return value, emitted state, side-effect ordering, or mapped error
- at least one regression-prone branch
- no private implementation assertions
- no mock-only verification without an outcome assertion
```

## Good Targets

- `domain/usecase` classes with branching
- `data/repository` orchestration classes
- `app` ViewModels with user-visible state

## Bad Targets

- pure Compose rendering files
- generated or DI glue
- trivial wrappers with no branch-heavy behavior
