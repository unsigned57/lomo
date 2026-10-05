# Store Contract

Capability: persist and query rebuildable SQLite projections through `lomo-store` as the sole query-projection owner. Durable document transactions remain with `lomo-application`; document semantics remain with `lomo-workspace` under [Architecture](../../ARCHITECTURE.md).

- Given a ready workspace generation, When a mutation batch is prepared and committed, Then revisions are checked atomically and projections remain rebuildable.
- Given a stale generation or revision, When a write reaches the store boundary, Then it fails closed without partial user-file mutation.

Observable outcomes: deterministic rebuild/query/FTS results and one SQLite owner.
Excludes: SAF byte execution, UI state and provider protocol policy.
