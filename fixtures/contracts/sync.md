# Sync Contract

Capability: plan, apply, verify and recover remote synchronization through `lomo-sync` as the sole planner.

- Given local and remote snapshots, When a cycle is planned, Then provider-neutral intents preserve expected revisions, tombstones and conflict state.
- Given stale receipts, missing secret leases, unverified publishes or open conflicts, When apply is requested, Then the cycle fails closed before baseline advancement.

Observable outcomes: the cases in `fixtures/baselines/sync-safe-behavior.v1.json` and durable recovery state.
Excludes: credentials, Android scheduling, provider discovery and UI composition.
