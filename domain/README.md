# Domain Module Index

> Responsibilities and boundaries: see [ARCHITECTURE.md](../ARCHITECTURE.md). Contribution rules: see [AGENTS.md](../AGENTS.md).
> This file is a task map for locating code, not a source of truth — verify paths and APIs against the codebase before acting.

This module defines the platform-free core of the app: domain models, use cases, validators, and repository interfaces. New business behavior should start here before it is wired into `app` and implemented in `data`.

## Start Here

- `src/repository/`
  - Contract definitions such as `MemoRepository.kt`.
- `src/usecase/`
  - Business rules such as `ApplyMainMemoFilterUseCase.kt`.
- `src/model/`
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

then it belongs in `data`, not `domain`. Continue in `data/README.md` when the change is really persistence or sync behavior.

## Concrete Entry Examples

- `repository/MemoRepository.kt`
  - Core memo contract split into query, mutation, search, and trash capabilities.
- `usecase/ApplyMainMemoFilterUseCase.kt`
  - Good example of pure sorting/filter/date-range logic that belongs in the domain layer.

## High-Risk Areas

- use cases that encode sorting, filtering, conflict handling, or fallback behavior
- repository interfaces that can accidentally leak implementation detail
- shared model changes that cascade into both `app` and `data`
