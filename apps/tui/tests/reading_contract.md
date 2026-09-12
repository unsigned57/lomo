# Behavior Contract and Test Change Justification

The Linux TUI now presents one memo body stream, a separate reader and one input receiver.
Tests cover semantic reading anchors, stale replies, filtered pagination beyond 256 memos,
Unicode drafts, durable retry identity, external editor conflicts and terminal media lifecycle.

## Test Change Justification

- Reason category: confirmed product contract change and structural migration.
- Old assertions replaced: list/preview/navigation panes, Tab focus cycles, generic `ListRow`,
  independent preview strings, and inline prohibition for new memo capture.
- Those assertions contradict the approved single-column reading and quick-capture design.
- Coverage preserved by: typed memo/task/attachment views, real application-backed mutation tests,
  rendering at narrow/80/wide sizes, reader return, Unicode input and identity-bound confirmations.
- Regression tests for Markdown writes, task toggles, pin/trash/restore, reminders, clipboard failures,
  external editor failures and stale version rejection remain active.
- These tests follow the specified user-visible behavior, not the internal field layout.

## TDD proof

Observed RED with targeted Cargo tests:

- `reading_flow_contract`: stale hydration overwrote the current version; search evidence disappeared;
  clear filters lost memo-12; deletion jumped to memo-0; resize moved from token 047 to 021.
- `markdown_view_contract`: hard breaks and table rows collapsed, quote hierarchy was lost.
- `pagination_contract`: deep refresh returned 48 of 320 records; search total was absent;
  loading a body removed the match highlight.
- `search_task_query_contract`: restored fuzzy cursors failed, oversized fuzzy queries were accepted,
  and pinyin matches could not be traced to source characters.

## Architecture Impact

`apps/tui` owns presentation, input and private capture drafts. `lomo-application` continues to own
all durable memo transactions and search rules. `lomo-workspace` supplies the sole Markdown parse;
`lomo-platform-fs` executes capability-bound workspace reads and writes. New memo quick capture is
the only change to application input ownership; existing memo editing stays external.
