# Domain Module Index

This module defines the platform-free core of the app: domain models, use cases, validators, and repository interfaces. New business behavior should start here before it is wired into `app` and implemented in `data`.

## What Belongs Here

- pure business rules
- domain models shared across layers
- repository interfaces that describe required capabilities
- validators and policy objects that do not depend on Android or storage details

## What Does Not Belong Here

- Android framework types
- Compose, Lifecycle, Room, Hilt/Dagger, Ktor, JGit
- concrete repository implementations
- file-system, network, or database glue

## Start Here

- `src/main/java/com/lomo/domain/repository/`
  - Contract definitions such as `MemoRepository.kt`.
- `src/main/java/com/lomo/domain/usecase/`
  - Business rules such as `ApplyMainMemoFilterUseCase.kt`.
- `src/main/java/com/lomo/domain/model/`
  - Shared state and value types passed across layers.

## Key Subdirectories

- `model`
  - Memo data, sync state, configuration enums, and other shared domain types.
- `repository`
  - Abstract capabilities consumed by the app layer and implemented by `data`.
- `usecase`
  - Business actions and policies that compose repositories and pure rules.

## Common Tasks

### Add a new business rule

Start in `usecase/` if the rule transforms inputs, applies ordering/filtering, decides fallback behavior, or combines repository calls into a user-visible behavior.

### Add a new repository capability

Start in `repository/`. Define the capability in terms of observable behavior, not storage implementation. Then wire it in `data` and consume it in `app`.

### Decide whether code belongs here or in `app`

If the logic is:

- platform-free
- reusable across screens
- testable without Android or storage infrastructure

then it probably belongs here.

If the logic mainly shapes UI state, event consumption, or navigation flow, it probably belongs in `app`.

### Decide whether code belongs here or in `data`

If the logic requires:

- Room
- file-system access
- network calls
- Android storage integration
- concrete sync implementation details

then it belongs in `data`, not `domain`.

## Concrete Entry Examples

- `repository/MemoRepository.kt`
  - Core memo contract split into query, mutation, search, and trash capabilities.
- `usecase/ApplyMainMemoFilterUseCase.kt`
  - Good example of pure sorting/filter/date-range logic that belongs in the domain layer.

## High-Risk Areas

- use cases that encode sorting, filtering, conflict handling, or fallback behavior
- repository interfaces that can accidentally leak implementation detail
- shared model changes that cascade into both `app` and `data`

## How This Module Connects To Other Modules

- `app` consumes its use cases and repository interfaces.
- `data` implements its repository interfaces.
- `domain` must remain free of `data` and Android-specific concerns.

## Rules For AI Contributors

- Keep this module platform-free.
- Prefer expressing capability and policy, not implementation mechanism.
- When adding a new behavior, decide here first whether the contract itself needs to change.
- Do not introduce `@Suppress`, `@SuppressLint`, or `@SuppressWarnings` in new or modified code.

## When To Read Deeper Files

- Read deeper when the task is about business rules, ordering/filtering decisions, domain contracts, or shared models.
- Stop descending once you identify the owning use case, model, or interface.
- If the requested change requires actual persistence or sync behavior, continue in `data/README.md`.
