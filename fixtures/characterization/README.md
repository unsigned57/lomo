# External-behavior characterization goldens

Language-neutral golden outputs for Kotlin characterization (and later Rust probe diffs).

| Path | Contract |
| --- | --- |
| `markdown/*.json` | Storage parse of `fixtures/markdown/*` — memo ids, content, tags, attachments, source spans |
| `DECISIONS.md` | External contract vs internal defect decisions |

## Schema (`schema_version = 1`)

- **User-visible / open format**: content text, tags, attachment paths, stable memo ids, source line spans, fixture byte length
- **Excluded**: Compose styles, Room entities, DAO types, JetBrains Markdown AST, absolute epoch timestamps (zone-dependent)

## Update goldens

```bash
LOMO_UPDATE_CHARACTERIZATION=1 just test
# or the single characterization suite once wired through the Kotlin toolchain
```

Only update when the external contract intentionally changes; never to paper over a parser bug without a DECISIONS entry.
