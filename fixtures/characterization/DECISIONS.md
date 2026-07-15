# Characterization decisions

Records whether a fixture locks an **external contract** or documents a known **internal defect**
that must not be golden-locked as product truth.

| Fixture | Decision | Rationale |
| --- | --- | --- |
| `markdown/*` storage parse (utf-8) | **external contract** | Open Markdown bytes + memo identity/content/tags/attachments must stay compatible |
| `markdown/invalid-utf8.bin` | **external contract** | Invalid UTF-8 must fail closed with an explicit error class, not empty success |
| `markdown/empty.md` | **external contract** | Empty file yields zero memos (no synthetic body) |
| `markdown/dst-edge.md` | **external contract (logical times only)** | Absolute epoch depends on host zone; goldens lock time headers + ids, not epoch millis |
| `remote/*` | **external contract** | Path layout rules only; no live network |
| `git/scenarios.json` | **external contract** | Scenario ids/kinds for corpus materialization |
| `git/EVIDENCE.md` | **evidence boundary** | Bare local push/fetch/rebase proven; smart-HTTP/TLS deferred to stage-4 entry on same `git2` stack |
| rclone ciphertext vectors | **deferred (slot)** | Placeholder identities until verified rclone vectors land — do not lock fake ciphertext |
| markdown semantic counters | **external contract (UI-neutral)** | heading/link/image/event counts only; formal `RenderDocument` IR is stage 2 |
| SAF DocumentsProvider in native-smoke | **tooling-only** | Must not become production SAF policy; stage-1 formal platform batch replaces it |

When a suspected defect appears during characterization: stop, add a row here, and either fix the
parser or mark the fixture as non-contract with an explicit exclusion. Never commit a golden that
silently freezes incorrect behavior as required compatibility.
