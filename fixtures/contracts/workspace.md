# Workspace Contract

Capability: parse, render, scan and patch workspace documents through `lomo-workspace` as the sole semantic owner.

- Given bounded source bytes and a valid workspace-relative path, When a document is parsed and rendered, Then identity, spans, links and rendered structure are deterministic.
- Given invalid UTF-8, traversal, or exceeded limits, When input reaches the owner boundary, Then it is rejected before mutation.

- Given existing Markdown and attachments with discarded private state, When the workspace opens, Then documents remain readable without importing an external `.lomo` tree.
- Given a deleted memo, When deletion settles, Then `.trash` contains a readable Markdown recovery document; restoring it retains the body even when private identity bindings have been reset.
- Recovery filenames start with the original document stem (up to 32 Unicode characters, normally its date) and retain a SHA-256 identity suffix to distinguish same-day memos. Earlier digest-only names are admitted through the same checked recovery workflow: write the verified dated snapshot before deleting its old name; different existing facts reject admission without overwriting either copy.
- Given older daily Markdown already under `.trash`, When the workspace is scanned, Then each recoverable memo is durably admitted before its old container is removed; source CAS rejects concurrent edits and the first admission time records the previously unknown deletion time.

Observable outcomes: byte-stable plans, typed errors, and no Kotlin Markdown authority.
Excludes: Android storage access and presentation-only text collapse.
