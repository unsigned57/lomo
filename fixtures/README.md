# Format fixtures

Repository-level golden assets for open workspace formats and remote layout contracts.
Both Rust probes and Kotlin characterization consume these paths.

Large seeded corpora are generated under gitignored `build/corpora/`.

## Layout

| Path | Purpose |
| --- | --- |
| `markdown/` | Lomo / Thino / plain Markdown edge cases |
| `remote/` | S3 / WebDAV layout and rclone crypt golden vectors |
| `git/` | Git scenario descriptors (materialized by the generator, not large histories) |
| `characterization/` | Language-neutral storage/layout golden outputs + DECISIONS |
| `baseline/` | Exact package/native size baseline + P0-11 candidate matrix (time metrics pending emulator) |
| `git/EVIDENCE.md` | P0-09 libgit2 evidence boundary (bare vs smart-HTTP) |

## Generate synthetic corpora

```bash
cargo run --manifest-path rust/Cargo.toml --locked -p lomo-feasibility -- \
  generate --mode quick --seed 1 --out build/corpora/quick \
  --fixtures fixtures
```

Modes:

- `quick` — contract-size corpus (default for CI)
- `scale` — 100,000 memos + 10,000 remote changes (metadata + sparse material files)
- `capacity` — 20 GiB **logical** attachments via stream digests (no 20 GiB checkout)

Same seed must produce identical `CorpusManifestV1` JSON digests.
