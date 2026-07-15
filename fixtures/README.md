# Format fixtures

Repository-level golden assets for open workspace formats and remote layout contracts.
Both Rust probes and Kotlin characterization consume these paths.

Large seeded corpora are generated under gitignored `build/corpora/`.

## Layout

| Path | Purpose |
| --- | --- |
| `markdown/` | Lomo / Thino / plain Markdown edge cases |
| `remote/` | S3 / WebDAV layout; rclone crypt vectors (`status=verified`) |
| `git/` | Git scenario descriptors (materialized by the generator, not large histories) |
| `characterization/` | Language-neutral storage/layout golden outputs + DECISIONS (gaps remain; see STAGE0-STATUS) |
| `baseline/` | Stage-0 audit status, size baseline JSON, candidate matrix |
| `baseline/STAGE0-STATUS.md` | **Authoritative** partial map (must be committed with evidence changes) |
| `git/EVIDENCE.md` | P0-09 boundary: host bare + smart-HTTP partial; device/runtime open |

## Generate synthetic corpora

```bash
cargo run --manifest-path rust/Cargo.toml --locked -p lomo-feasibility -- \
  generate --mode quick --seed 1 --out build/corpora/quick \
  --fixtures fixtures
```

Modes:

- `quick` — contract-size corpus (default for CI)
- `scale` — **full materialization** of 100,000 memos + 10,000 remote changes under
  gitignored `build/corpora/` (used by `just perf` markdown scale metric).
- `capacity` — 20 GiB **logical** attachments via stream digests (no 20 GiB checkout)

Same seed must produce identical `CorpusManifestV1` JSON digests.
