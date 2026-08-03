# Stage-7 behavior contract (Kotlin shell convergence and recovery)

> Version: v1 (2026-08-01)
>
> This contract locks the final production owner boundary. RED/GREEN evidence lives in
> `fixtures/baseline/STAGE7-EVIDENCE.md`; `ROADMAP.MD` section 15 and `ARCHITECTURE.md` remain the
> architecture sources of truth. Environment-only gates stay `pending_env` until a real run is
> recorded.

## Capability

Rust is the sole production authority for Markdown, reminders, the derived SQLite index, media
metadata, workspace archive v2, remote sync and LAN v2. Kotlin is a shell over typed engine ports:
Compose/UI state plus Android SAF, Keystore, WorkManager, notifications, NSD/network, media codec,
recording/playback, preferences and application update capabilities.

When engine open fails, the app exposes a read-only recovery surface. A user may rebuild only a
known-damaged derived SQLite index or export a bounded diagnostic report containing typed facts.

## Fundamental invariant

There is exactly one production authority for every migrated rule, and a recovery action cannot
mutate durable user truth. Markdown, media and `.lomo` history/tombstone/conflict state survive an
index rebuild. Exported diagnostics never contain raw native diagnostics, workspace paths, memo
text, remote secrets, credentials or capability tokens.

## Axiom violation

- Transitional Kotlin contracts and planners can silently become a second business authority.
- A raw native diagnostic is untrusted text and may include provider responses, local paths, user
  content, secrets or tokens.
- Treating every recovery failure as rebuildable can destroy non-derived `.lomo` state.
- A stale generated baseline profile can retain deleted owner classes in packaged optimization
  metadata even when source ownership has moved.

## Rebuild from truth

- `EngineReadiness.ReadOnlyRecovery` is the only recovery state and exposes typed category, code
  and retry disposition.
- Domain maps that state to a canonical `RecoveryDiagnosticReport` with fixed schema, filename and
  byte ceiling; the raw diagnostic is deliberately absent.
- Domain permits rebuild only for the canonical SQLite failure codes. `ManagedEngineSession`
  opens a restricted candidate for the same workspace, invokes the Rust rebuild, closes it, then
  promotes only a freshly opened `Ready` candidate.
- Architecture tests and regenerated artifacts lock the unique owner boundary.

## Edge enforcement

- Invalid or unbounded recovery codes fail report construction instead of being copied to output.
- Android document export receives only `RecoveryDiagnosticReport.content`.
- Rebuild is rejected unless readiness is `ReadOnlyRecovery` with a rebuildable SQLite code.
- A repair candidate never becomes the active production owner; only the post-rebuild `Ready`
  candidate may be atomically promoted.
- Failure to open, rebuild, close or reopen remains observable and leaves the session read-only.

## Given/When/Then scenarios

- Given native load or schema failure, When the app starts, Then it shows read-only recovery and
  performs no workspace mutation.
- Given a known SQLite integrity failure, When the user rebuilds, Then only the derived index is
  rebuilt and the same workspace becomes Ready only after a successful reopen.
- Given `.lomo` corruption, When recovery is shown, Then index rebuild is unavailable and durable
  history/conflict data is not cleared.
- Given a diagnostic containing a path, memo body, remote secret or capability token, When the user
  exports diagnostics, Then none of those raw values appear in the exported file.
- Given SAF permission revocation, When permission is restored, Then the original job either
  resumes through its stable operation identity or terminates with a typed outcome.
- Given Kotlin source or dependencies reintroduce a migrated owner, When `just ci` runs, Then the
  architecture gate fails.

## Observable outcomes

- Recovery actions are visible only when permitted by typed readiness state.
- Rebuild returns bounded counts/revision facts and readiness returns to `Ready` only after reopen.
- Diagnostic export has canonical filename/schema and is at most 4096 UTF-8 bytes.
- Source, dependency and generated-profile checks contain no Room/JGit/AWS Kotlin/Kotlin Markdown
  parser or remote-sync planner production authority.

## Owner matrix

| Capability | Sole production owner | Retained Kotlin boundary |
| --- | --- | --- |
| Markdown/reminder/store | Rust engine | Compose presentation, AlarmManager execution |
| Media metadata/archive | `lomo-media` / `lomo-store` | Android codec, picker and SAF actions |
| Remote sync | `lomo-sync` + `lomo-git` adapter | config, Keystore secret lease, WorkManager |
| LAN v2 | `lomo-lan` | NSD/network/multicast/Keystore signing/UI |
| Recovery policy | typed domain contract + managed Rust session | document creation and user feedback |
| Preferences/update | Kotlin | explicit non-migrated peripheral capability |

## Tail deletion

- Delete differential runtime entries, migration flags, compatibility overloads and old schema
  implementations after their atomic cutover.
- Delete residual Kotlin core planners/repositories/use cases rather than retaining no-op or
  unsupported defaults.
- Regenerate the baseline profile from fresh Kotlin build output; do not hand-delete stale rules.
- Delete obsolete ownership claims and migration TODOs from current documentation.
- Keep no feature flag, dual write, fallback engine, sentinel owner or raw-diagnostic export path.

## Non-claims and environment gates

Host tests do not prove real SAF provider grant restoration, real provider credentials,
100,000 memo / 20 GB / 10,000-change capacity, four-ABI shipping size, cold-start budget or a long
soak. Linux `/dev/full` proves the atomic Markdown ENOSPC boundary only; it is not a physical-device
quota test. Those untested environment, capacity and performance claims remain `pending_env` or OPEN
until their real commands and observed outputs are recorded.
