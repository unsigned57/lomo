# Fixtures

Current repository fixtures are grouped by responsibility:

This is the fixture index. [Architecture](../ARCHITECTURE.md) owns module boundaries;
[Meaningful Tests](../quality/testing/ai-meaningful-tests.md) owns test evidence. Markdown corpus
files are byte-sensitive test inputs, not agent instructions or prose to reformat.

| Directory | Purpose |
| --- | --- |
| `contracts/` | Stable capability contracts consumed by architecture checks |
| `baselines/` | Executable safe-behavior and performance inputs |
| `markdown/` | Source-format Markdown corpus |
| `remote/` | Provider layout and crypt interoperability vectors |
| `characterization/markdown/` | Current Markdown characterization outputs |
| `characterization/semantic-ui/` | Presentation-semantic characterization outputs |

Historical migration evidence is available from Git history and is not an active fixture input.

Capability contracts describe [workspace semantics](contracts/workspace.md),
[SQLite projections](contracts/store.md), [sync recovery](contracts/sync.md),
[LAN consent and transfer](contracts/lan.md), and [command outcomes](contracts/shell.md).
Use [characterization](characterization/README.md) for golden structure and update procedure;
its [decisions](characterization/DECISIONS.md) explain individual expected behaviors.
