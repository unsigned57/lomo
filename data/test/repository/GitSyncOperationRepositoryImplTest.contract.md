Test Contract

- Unit under test: `GitSyncOperationRepositoryImpl`.
- Behavior focus: sync guard short-circuiting, disabled or not-configured propagation, and delegation to init/status/maintenance executors after the version-history cleanup.

Observable outcomes

- `sync()` returns executor results and blocks overlapping runs with a visible "already in progress" success message.
- Failures release the sync guard so a later sync can run.
- Status, connection-test, and maintenance APIs delegate to the correct executor and expose their returned domain results.

Red phase

- Before the cleanup, this contract did not fully cover the post-version-history repository shape; the focused repository test failed when the operation facade still depended on removed history-facing wiring instead of the remaining executors and sync guard behavior.

Excludes

- Git engine internals.
- SAF mirror behavior.
- Repository wiring outside this operation facade.
