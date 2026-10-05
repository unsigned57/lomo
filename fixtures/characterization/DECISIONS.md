# Characterization decisions

This is the semantic decision record for golden data, not an agent workflow or migration task list.
Paths are relative to `fixtures/`. The [characterization README](README.md) owns current consumers
and validation commands; [Architecture](../../ARCHITECTURE.md) owns module authority.

## Active expectations

A fixture locks an external contract, not an internal implementation defect.

| Fixture | Decision | Rationale |
| --- | --- | --- |
| `markdown/*` storage parse (utf-8) | **external contract** | Open Markdown bytes + memo identity/content/tags/attachments must stay compatible |
| `markdown/invalid-utf8.bin` | **external contract** | Invalid UTF-8 must fail closed with an explicit error class, not empty success |
| `markdown/empty.md` | **external contract** | Empty file yields zero memos (no synthetic body) |
| `markdown/dst-edge.md` | **external contract (logical times only)** | Absolute epoch depends on host zone; goldens lock time headers + ids, not epoch millis |
| `remote/*` | **external contract** | Path layout rules only; no live network |
| rclone ciphertext vectors | **external contract** | `status=verified` vectors from rclone crypt (standard filename encryption + directory name encryption); regenerate with the documented passwords only |
| Rust `RenderDocumentV1` semantic projection | **external contract (UI)** | under `characterization/semantic-ui/` |
| storage double-parse stability | **external contract** | second parse keeps id/content/tags/spans |
| unedited write-back (open-file bytes) | **external contract (P0-07)** | identity rewrite preserves BOM + CRLF/LF bytes |
| `markdown/*` UI plain-text colon tokens + wiki | **active contract; stage-2 rationale (P2-03)** | JetBrains tokenizer drops `:` as a non-text token and treats `[[wiki]]` as a short reference link. Rust `RenderDocumentV1` preserves colon characters in plain text and projects `[[target]]` as typed `WikiReference` (plain text = target). `semantic-ui` fingerprints/link counts updated under this decision; storage goldens unchanged. SoftBreak projects as `\n` (pulldown), not raw CRLF white-space tokens. |
| `semantic-ui/dst-edge.json` plain fingerprint | **active contract; stage-2 rationale (P2-03)** | Recomputed from the same block/plain algorithm as the rest of the corpus (`list` item inlines joined by `\n`, SoftBreak = `\n`). Prior fingerprint did not match any JetBrains-compatible projection of the current `dst-edge.md` bytes; updated to the deterministic Rust/UI-compatible value without changing block kinds/counts. |

## Historical rationale

The following entries preserve the original decisions for retired inventories or tooling. Their
paths and implementation names are historical references, not missing inputs to recreate, current
module authority, or proof that those commands still exist.

| Historical fixture/tool | Original decision | Rationale |
| --- | --- | --- |
| `git/scenarios.json` | **external contract** | Scenario ids/kinds for corpus materialization |
| markdown semantic counters (storage-visible) | **external contract (storage helpers)** | tags/attachments via `MemoTextProcessor` + regex under `characterization/semantic/` |
| room **query results** | **external contract (P0-04)** | language-neutral capability goldens under `room-query/` — **no Entity/DAO type names** |
| room schema-surface inventory | **internal inventory only** | entity/DAO/`@Query` names for developers; **not** the P0-04 golden exit |
| SAF DocumentsProvider (removed native-smoke host) | **historical tooling** | create/read/replace/rename/**move**/delete was proven there; production SAF is owned by `apps/android/data` |

When a suspected defect appears, stop accepting or updating the affected golden, record the evidence
here, and investigate the parser or projection owner. Continue unrelated work. Correct a demonstrated
bug; classify a fixture as non-contract only with evidence that it does not represent required
external behavior. Never commit a golden that silently freezes incorrect behavior as compatibility.
