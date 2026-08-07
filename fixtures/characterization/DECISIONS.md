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
| rclone ciphertext vectors | **external contract** | `status=verified` vectors from rclone crypt (standard filename encryption + directory name encryption); regenerate with the documented passwords only |
| markdown semantic counters (storage-visible) | **external contract (storage helpers)** | tags/attachments via `MemoTextProcessor` + regex under `characterization/semantic/` |
| UI semantic parser (`parseMarkdownSemanticDocument`) | **external contract (UI)** | under `characterization/semantic-ui/` |
| room **query results** | **external contract (P0-04)** | language-neutral capability goldens under `room-query/` — **no Entity/DAO type names** |
| room schema-surface inventory | **internal inventory only** | entity/DAO/`@Query` names for developers; **not** the P0-04 golden exit |
| storage double-parse stability | **external contract** | second parse keeps id/content/tags/spans |
| unedited write-back (open-file bytes) | **external contract (P0-07)** | identity rewrite preserves BOM + CRLF/LF bytes |
| SAF DocumentsProvider in native-smoke | **tooling-only** | create/read/replace/rename/**move**/delete |
| `markdown/*` UI plain-text colon tokens + wiki | **stage-2 decision (P2-03)** | JetBrains tokenizer drops `:` as a non-text token and treats `[[wiki]]` as a short reference link. Rust `RenderDocumentV1` preserves colon characters in plain text and projects `[[target]]` as typed `WikiReference` (plain text = target). `semantic-ui` fingerprints/link counts updated under this decision; storage goldens unchanged. SoftBreak projects as `\n` (pulldown), not raw CRLF white-space tokens. |
| `semantic-ui/dst-edge.json` plain fingerprint | **stage-2 decision (P2-03)** | Recomputed from the same block/plain algorithm as the rest of the corpus (`list` item inlines joined by `\n`, SoftBreak = `\n`). Prior fingerprint did not match any JetBrains-compatible projection of the current `dst-edge.md` bytes; updated to the deterministic Rust/UI-compatible value without changing block kinds/counts. |

When a suspected defect appears during characterization: stop, add a row here, and either fix the
parser or mark the fixture as non-contract with an explicit exclusion. Never commit a golden that
silently freezes incorrect behavior as required compatibility.
