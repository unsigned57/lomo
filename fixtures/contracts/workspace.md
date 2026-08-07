# Workspace Contract

Capability: parse, render, scan and patch workspace documents through `lomo-workspace` as the sole semantic owner.

- Given bounded source bytes and a valid workspace-relative path, When a document is parsed and rendered, Then identity, spans, links and rendered structure are deterministic.
- Given invalid UTF-8, traversal, or exceeded limits, When input reaches the owner boundary, Then it is rejected before mutation.

Observable outcomes: byte-stable plans, typed errors, and no Kotlin Markdown authority.
Excludes: Android storage access and presentation-only text collapse.
