# TUI reading Behavior Contract

Capability: navigate a single memo feed, full-text reader and recoverable capture input;
owning layer: `apps/tui`; priority: P1. This document describes reading behavior across its related
specifications. Common test rules remain in [Meaningful Tests](../../../quality/testing/ai-meaningful-tests.md).

## Scenarios:

- Given a filtered feed with more than one page, when reading, refreshing or returning from a memo,
  then the semantic reading anchor and matching results remain associated with the current query.
- Given stale hydration or search replies, when the current version or filter has changed, then old
  replies cannot replace current content or search evidence.
- Given a Unicode capture draft, when editing or retrying a save, then text and durable operation
  identity survive; editing an existing memo uses the external editor and preserves conflict checks.
- Given narrow or resized terminals, when rendering feed and reader content, then text structure
  and reading position remain observable.

Observable outcomes: returned reading anchors, current reply identity, complete pagination, Unicode
draft contents, identity-bound confirmations, conflict results and terminal media lifecycle.
Excludes: remote sync, Android UI, and platform behavior not exercised by the selected host tests.

## Test Change Justification:

- Reason category: the recorded product contract change and structural migration to one feed.
- Old behavior/assertion being replaced: list/preview/navigation panes, Tab focus cycles, generic
  `ListRow`, independent preview strings, and inline prohibition for new memo capture.
- Why old assertion is no longer correct: those assertions contradict the selected single-column
  reading and quick-capture behavior described above.
- Coverage preserved by: typed memo/task/attachment views, real application-backed mutations,
  narrow/80/wide rendering, reader return, Unicode input and identity-bound confirmations; Markdown
  writes, task toggles, pin/trash/restore, reminders, clipboard/editor failures and stale-version
  rejection remain covered by their owning specifications.
- Why this is not fitting the test to the implementation: assertions describe observable reading
  behavior and durable outcomes, not private field layout.

## TDD proof:

The following RED observations are historical migration evidence. They are not a current GREEN
claim; each task must record its own applicable commands and results.

Observed RED with targeted Cargo tests:

- `reading_flow_contract`: stale hydration overwrote the current version; search evidence disappeared;
  clear filters lost memo-12; deletion jumped to memo-0; resize moved from token 047 to 021.
- `markdown_view_contract`: hard breaks and table rows collapsed, quote hierarchy was lost.
- `pagination_contract`: deep refresh returned 48 of 320 records; search total was absent;
  loading a body removed the match highlight.
- `search_task_query_contract`: restored fuzzy cursors failed, oversized fuzzy queries were accepted,
  and pinyin matches could not be traced to source characters.

## Architecture Impact

Reading state and input are presented by the TUI. Durable transactions, document semantics and
filesystem execution retain the owners in [Architecture](../../../ARCHITECTURE.md).
Historical approval of the product design is not authorization for unrelated agent actions.
