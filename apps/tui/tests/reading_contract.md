# TUI Reading Behavioral Specification

Capability: navigate a single memo feed, full-text reader, and recoverable quick-capture draft;
owning layer: `apps/tui`. Common test rules live in
[Meaningful tests](../../../quality/testing/meaningful-tests.md).

## Scenarios

- Given a filtered feed with more than one page, when reading, refreshing, or returning from a
  memo, then the semantic reading anchor and matching results stay associated with the active query.
- Given stale hydration or search replies, when the current version or filter has changed, then old
  replies cannot overwrite current content or search evidence.
- Given a Unicode capture draft, when editing or retrying a save, then text and durable operation
  identity survive; editing an existing memo uses the external editor and preserves conflict checks.
- Given narrow or resized terminals, when rendering feed and reader content, then text structure
  and reading position remain observable.

Observable outcomes: returned reading anchors, current reply identity, complete pagination, Unicode
draft contents, identity-bound confirmations, conflict results, and terminal media lifecycle.
Excludes: remote sync, Android UI, and platform behavior outside the selected host tests.

## Architecture Impact

Reading state and input are presented by the TUI; durable transactions, document semantics, and
filesystem execution retain their owners in [Architecture](../../../ARCHITECTURE.md).
