# External-behavior characterization goldens

Language-neutral golden outputs for Kotlin characterization (and later Rust probe diffs).

| Path | Contract |
| --- | --- |
| `markdown/*.json` | Storage parse of `fixtures/markdown/*` — memo ids, content, tags, attachments, source spans |
| `semantic/*.json` | Storage-visible counters (`MemoTextProcessor` + regex) |
| `semantic-ui/*.json` | Real UI `parseMarkdownSemanticDocument` fingerprints |
| `room/schema-surface.v1.json` | Entities, DAOs, `@Query` methods, DB version, schema export |
| `DECISIONS.md` | External contract vs internal defect decisions |

## Schema (`schema_version = 1`)

- **User-visible / open format**: content text, tags, attachment paths, stable memo ids, source line spans, fixture byte length
- **Excluded**: Compose styles, Room entities, DAO types, JetBrains Markdown AST, absolute epoch timestamps (zone-dependent)

## Update goldens

Missing goldens **fail closed**. Tests never invent a golden on a normal run.

```bash
LOMO_UPDATE_CHARACTERIZATION=1 just test
# or the single characterization suite once wired through the Kotlin toolchain
```

Only set the update flag when the external contract intentionally changes; never to paper over a
parser bug without a DECISIONS entry. Review the diff before committing.
