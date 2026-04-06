Test Contract

- Unit under test: `emptyImageMapProvider` test helper and its fake `MediaRepository` wiring.
- Behavior focus: provide a deterministic `ImageMapProvider` backed by either a copied fake repository or a caller-supplied repository.

Observable outcomes

- Tests that depend on this helper receive empty image locations by default.
- Tests can override the image-location flow without touching production storage wiring.
- Passing a non-fake repository preserves the caller-supplied implementation.

Red phase

- Not applicable - test-only coverage helper; no production change.

Excludes

- Production media repository behavior.
- Real storage refresh/import/remove flows.
- UI rendering or coordinator state transitions that consume this helper.
