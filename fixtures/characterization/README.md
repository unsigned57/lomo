# External-behavior characterization goldens

This file owns the current golden structure and update procedure. Paths below are relative to this
directory; source corpus paths are relative to `fixtures/`. Module authority remains in
[Architecture](../../ARCHITECTURE.md), and [DECISIONS](DECISIONS.md) records individual semantic choices.

| Path | Contract and current consumer |
| --- | --- |
| `markdown/*.json` | Stored memo identity/content/tags/attachments/spans, checked by [document_model_contract](../../crates/lomo-workspace/tests/document_model_contract.rs) |
| `semantic-ui/*.json` | Rust render-document projections, checked by [render_document_contract](../../crates/lomo-workspace/tests/render_document_contract.rs) |
| `DECISIONS.md` | Active expected behavior and separately labeled historical rationale |

## Schema (`schema_version = 1`)

User-visible/open-format expectations include content text, tags, attachment paths, stable memo ids,
source line spans, fixture byte length and render projections. Compose styling, storage-engine types
and timezone-dependent absolute epoch values are not golden authority.

## Update procedure

Missing or malformed goldens fail closed. Normal test runs never invent or overwrite expected data.
There is no implemented `LOMO_UPDATE_CHARACTERIZATION` regeneration switch in the current tree.

For an intentional external-contract change, first describe the expected semantic difference and
its independently checked oracle in DECISIONS. Update only the affected expectations from that
contract, not by copying unexplained parser output. Then run the current consumers from the repo root:

```bash
cargo test -p lomo-workspace --test document_model_contract --test render_document_contract --locked
```

This validates existing expectations; it is not an update command. Inspect the fixture diff and run
the applicable [delivery checks](../../quality/README.md#gate-selection-and-evidence).
If an unexpected output may be a bug, stop accepting that affected golden, investigate the owner and
continue independent work. Do not freeze a defect as compatibility or exclude a real contract merely
to pass a test. Historical Kotlin/Room inventories are not current generators or required inputs.
