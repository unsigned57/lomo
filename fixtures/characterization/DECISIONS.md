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
| `git/EVIDENCE.md` | **evidence boundary** | Host bare + smart-HTTP partial (DER pin, credentials); device/runtime still open — see STAGE0-STATUS |
| rclone ciphertext vectors | **external contract** | `status=verified` vectors from rclone crypt (standard filename encryption + directory name encryption); regenerate with the documented passwords only |
| markdown semantic counters (storage-visible) | **external contract (storage helpers)** | tags/attachments via `MemoTextProcessor` + regex under `characterization/semantic/` |
| UI semantic parser (`parseMarkdownSemanticDocument`) | **external contract (UI)** | under `characterization/semantic-ui/` |
| room **query results** | **external contract (P0-04)** | language-neutral capability goldens under `room-query/` — **no Entity/DAO type names** |
| room schema-surface inventory | **internal inventory only** | entity/DAO/`@Query` names for developers; **not** the P0-04 golden exit |
| storage double-parse stability | **external contract** | second parse keeps id/content/tags/spans |
| unedited write-back (open-file bytes) | **external contract (P0-07)** | identity rewrite preserves BOM + CRLF/LF bytes |
| SAF DocumentsProvider in native-smoke | **tooling-only** | create/read/replace/rename/**move**/delete |

When a suspected defect appears during characterization: stop, add a row here, and either fix the
parser or mark the fixture as non-contract with an explicit exclusion. Never commit a golden that
silently freezes incorrect behavior as required compatibility.
