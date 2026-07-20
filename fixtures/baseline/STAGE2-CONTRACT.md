# Stage-2 workspace and unified Markdown behavior contract

> Status: **locked; production cutover complete on the workspace owner; dual-stack forbidden**
>
> This document fixes the behavior and evidence required to close stage 2. It is not evidence that
> every exit bar is green. Actual RED/GREEN commands and results are recorded in
> `STAGE2-EVIDENCE.md` alongside the implementation that produces them.
>
> Stage-1 formal exit remains a hard entry gate (`STAGE1-EVIDENCE.md` **P1 formal exit closed for
> P2 entry**). Primary Kotlin/JetBrains Markdown owners are deleted; production DI binds the Rust
> workspace session. Residual exit gates (device-smoke, full perf, `just ci`) are recorded only when
> observed.

## Behavior Contract

- **Unit under test:** `lomo-workspace` document model, same-parse storage + `RenderDocumentV1`
  projection, byte-stable unedited serialize, local document patch, BoltFFI facade conversion, and
  Kotlin presentation that consumes typed IR without re-parsing Markdown.
- **Owning layer:** future/production `lomo-workspace` for Markdown semantics, memo identity, source
  span, content analysis, Render IR, and document patch; `lomo-core` for engine/job/write sequence;
  `lomo-native` for FFI conversion only; Kotlin `data` for Android platform actions and the sole
  generated-binding adapter; `ui-components` for Compose presentation of typed IR.
- **Priority tier:** P0 for ownership/invariant packages; P1 for presentation and performance.
- **Capability:** parse one UTF-8 source once into a constrained workspace document; project storage
  memo facts and `RenderDocumentV1` from that same parse; reject invalid path/UTF-8/schema/resource
  limits at the boundary; production consumers use domain IR / document commands only.

## Fundamental invariants

1. One source byte sequence produces one memo identity set, one source-span set, one content-analysis
   result, and one `RenderDocumentV1`. Storage analysis and Render IR must not come from two parsers
   or a second regex pass over body text.
2. Unedited serialize is byte-stable: parse → serialize without mutation returns the original bytes.
   A patch may change only the verified target byte span (plus neighborhood newline chosen by an
   explicit rule); all other bytes remain identical.
3. Memo identity defaults to exact compatibility with
   `${dateKey}_${timePart}_${ordinal}`. `ordinal` is the zero-based file-order index among blocks
   that share the same `(dateKey, timePart)`. Body edits must not change identity. Identity rule
   changes are a separate product migration, not a stage-2 side effect.
4. Kotlin may decide layout, style, selection, click, gallery, animation, and media loading. Kotlin
   must not re-identify tags, reminders, wiki references, task items, attachments, or links once
   production is switched.
5. Invalid path, capability escape, non-UTF-8 bytes, unknown schema, resource over-limit, stale
   fingerprint, or non-lossless write-back fail closed with structured `LomoError`. No empty-document
   fallback, truncated IR, pure-text demotion, or silent replacement of bad bytes.
6. Production dual-stack is forbidden: DI must not bind Kotlin and Rust parsers together; no feature
   flag keeps both authorities live; no progressive consumer-by-consumer production switch. After
   cutover only `lomo-workspace` (via data adapters) is the production Markdown authority.
7. `pulldown-cmark` is pinned at **0.13.4** with **`default-features = false`**. Only `lomo-workspace`
   (production owner) and approved feasibility tooling may depend on it. Facade and non-owner crates
   must not re-implement Markdown rules.

## Resource limits

| Surface | Limit | Failure |
| --- | --- | --- |
| Inline `RenderRequest` UTF-8 | 1 MiB | `resource_limit` |
| Editable memo body | `MemoConstraints.MAX_MEMO_LENGTH = 100000` | `resource_limit` / validation |
| Single `RenderDocumentV1` node count | 8192 | `resource_limit` |
| Semantic nesting depth | 64 | `resource_limit` |
| Single IR string | 256 KiB UTF-8 | `resource_limit` |
| Workspace scan page | 256 items | `resource_limit` |
| Relative path | 4096 UTF-8 bytes; segment 255 bytes | validation |
| Opaque protocol ids | 1..=128 protocol alphabet | validation |

Over-limit handling never clamps, truncates, or returns partial success.

## Scenarios

GWT form below uses explicit Given / When / Then tokens so architecture locks can verify the
scenario contract without relying on prose alone.

### Stage entry and scaffolding

- Given stage 1 formal exit is unrecorded, When implementers claim stage-2 GREEN, Then architecture
  tests fail closed.
- Given `fixtures/baseline/STAGE2-CONTRACT.md` or `STAGE2-EVIDENCE.md` is missing, When architecture
  tests run, Then they fail with a named missing invariant.
- Given the real `lomo-workspace` owner crate is missing, empty, or not a workspace member, When
  architecture tests run, Then they fail.
- Given a non-owner production crate depends on `pulldown-cmark`, When dependency graphs are
  inspected, Then architecture tests fail.
- Given dark-build sources exist, When production Kotlin (outside conversion-only data adapter) is
  inspected before P2-09, Then it must not consume workspace IR as a second production authority;
  `lomo-native` may depend on `lomo-workspace` for conversion-only DTO mapping only.

### Constrained source and path types (P2-01)

- Given a workspace-relative path that is absolute, contains `.` / `..`, empty segments, backslash,
  NUL/control, or exceeds byte limits, When constructed, Then validation fails without normalization.
- Given non-UTF-8 bytes, When `SourceBytes` is constructed, Then decode fails; no replacement
  characters or empty document are returned.
- Given a byte span outside source bounds or with `end < start`, When constructed, Then validation
  fails.
- Given source bytes, When a fingerprint is computed, Then it is a stable SHA-256 of the exact byte
  sequence including BOM when present.

### Document model, identity, and same-parse projections (P2-02 / P2-03)

- Given any unedited UTF-8 fixture, When parse / render / serialize run, Then output bytes equal the
  source bytes.
- Given Lomo, Thino, or plain Markdown, When parsed, Then memo/header/body spans are byte offsets,
  not `String.lines()` authority.
- Given two blocks with the same timestamp, When identities are assigned, Then ordinals are zero-based
  in file order and remain stable across body edits.
- Given GFM table/task/link/image/highlight/wiki/reminder content, When storage analysis and
  `RenderDocumentV1` are projected, Then both projections reference the same node facts from one
  parse.
- Given raw HTML or unknown syntax, When the document is unedited, Then original bytes are preserved.

### Patch and external edit (P2-04 / P2-05)

- Given BOM, CRLF, LF, CR, or trailing blank lines, When one memo is patched, Then non-target bytes
  remain identical.
- Given a file changed after read, When a patch with the old fingerprint is submitted, Then the
  result is `stale_snapshot` and the file is unchanged.
- Given a non-unique identity target, When patch is planned, Then it fails closed without content or
  timestamp fallback.

### Production switch and tail deletion (P2-09 / P2-10)

- Given all prior packages GREEN, When P2-09 lands, Then storage parse, content analysis, document
  patch, and every UI Markdown consumer switch in the same change.
- Given an old Kotlin parser, JetBrains AST consumer, or semantic regex is reintroduced after switch,
  When architecture tests run, Then the build fails.

## Observable outcomes

- Constrained path / UTF-8 / span / fingerprint / limit construction results and structured error
  category/code.
- Memo identity strings in `${dateKey}_${timePart}_${ordinal}` form.
- Byte-identical unedited serialize output.
- Same-parse agreement between storage analysis and `RenderDocumentV1` for tags, attachments, links,
  tasks, and spans.
- Architecture-test failures that name missing STAGE2 files, missing owner crate, non-owner
  `pulldown-cmark`, or stage-1 exit absence.
- Device-smoke and performance numbers only when the corresponding package claims them; no fictional
  GREEN.

## RED/GREEN evidence format

Every implementation package must record in `STAGE2-EVIDENCE.md`:

1. **RED command** — narrowest command that should fail before the capability exists.
2. **Observed RED** — exact assertion/error text.
3. **Why it proves absence** — what capability is missing.
4. **GREEN command** — same or strengthened command after the fix.
5. **GREEN result** — real pass counts / exit status. First-run GREEN without a prior RED is invalid
   and must be strengthened.

Do not claim GREEN from compilation alone when behavior tests exist. Do not claim stage-2 closed,
P2-09 switched, or production dual-stack GREEN from dark-build packages.

## Device policy

- Product `minSdk` / NDK API remain **26**.
- Hard device gate for stage-2 product claims that require smoke: **API ≥ 26 arm64**
  `just device-smoke` (real device accepted; current stage-1 evidence used API 36 arm64).
- Fixed API 26 x86_64 AVD is **non-claim** / `pending_env` when absent. It must never be marked
  GREEN without a real run and does not block stage-2 dark-build entry after stage-1 formal exit.
- Host unit tests are not a substitute for the arm64 hard device gate when a package claims device
  behavior.

## Production ownership (post cutover)

| Required | Forbidden |
| --- | --- |
| `lomo-workspace` sole Markdown semantic owner | Kotlin `MarkdownParser` / `MemoTextProcessor` / JetBrains AST |
| Production DI binds workspace projector/repository | Production dual-stack Markdown DI / feature flags |
| Conversion-only `lomo-native` → `lomo-workspace` | Facade re-implementing Markdown rules / pulldown |
| Storage analysis from same parse as Render IR | Second semantic regex / line-assembler write-back authority |
| Domain IR consumers in app/ui | Progressive dual-authority residual consumers |

## TDD proof

- **Current evidence:** see `STAGE2-EVIDENCE.md`. Only entries with an observed GREEN result are
  implemented claims.
- Characterization fixtures under `fixtures/markdown`, `fixtures/characterization/markdown`,
  `semantic`, and `semantic-ui` remain the default external behavior lock unless a decision is
  recorded in `fixtures/characterization/DECISIONS.md`.
- Existing Kotlin/JetBrains Markdown behavior is production truth until P2-09; dark-build Rust must
  match or explicitly decide differences before any production switch.

## Excludes

- Room schema, query, Paging, full memo CRUD/transaction ownership (stage 3).
- Media lifecycle ownership, WorkManager product orchestration, Keystore, sync backend redesign.
- Production dual-stack, progressive consumer migration, and temporary render-only FFI aliases such
  as `render_memo_text_v1`.
- Exposing JetBrains AST, `pulldown-cmark` events, Rust internal traits, Compose styles, Android
  `Uri`, or database entities inside public `RenderDocumentV1`.
- Fictional GREEN, empty marker crates, and silencing architecture RED without real types/tests.

## Exit evidence required (stage 2 close)

- Stage-1 entry gates remain GREEN under their own contract.
- `lomo-workspace` is the only Markdown semantic owner in production.
- Storage analysis and Render IR come from the same parse.
- All production Markdown consumers use Rust IR / document commands.
- Direct + SAF round-trip, local patch, external-edit rejection, and crash recovery are repeatable.
- Golden, property, fuzz, Compose semantic, and API ≥ 26 arm64 smoke gates are green with recorded
  commands.
- 100,000-memo performance and memory gates pass.
- Four-ABI, APK size, and BoltFFI generation gates pass.
- Old Kotlin parsers, JetBrains AST production use, duplicate semantic regexes, differential
  production entries, and unused parser dependencies are deleted.
- `just check`, `just ci`, and applicable device/perf gates have real command output in evidence.
- `ARCHITECTURE.md` and this contract match code facts.

## Production Markdown consumer inventory (post-cutover audit lock)

Primary Kotlin/JetBrains owners are **deleted**. Live production surfaces consume domain IR /
workspace adapters only. Path-level evidence is in `STAGE2-EVIDENCE.md`.

### Storage / analysis / write-back (`data`)

- `MarkdownWorkspaceContentProjector` — free-content analysis via one owner `renderMarkdown`.
- `MemoWorkspaceProjector` — scan summary → Room facts (hasTodo/hasUrl/tags/attachments) without
  second body render.
- `MemoRefreshParserWorker`, `MemoWorkspaceStore`, `MemoSavePlanFactory`, `MemoMutationHandler` —
  orchestration over workspace document commands / scan.
- Direct/SAF Markdown storage delegates under `data/src/source/*Markdown*`.
- DI: `MemoRepositoryModule` binds `MarkdownWorkspaceContentProjector` (not `MarkdownParser`).

### UI render (`ui-components`)

- `MarkdownIrRenderer` / `buildMarkdownIrPresentationPlan` — typed domain IR only.
- No JetBrains AST, `org.intellij.markdown`, or multiplatform-markdown-renderer production dep.

### App consumers

- `MemoUiMapper` / `MemoVersionHistoryUiMapper` — `MarkdownWorkspaceRepository.renderMarkdown`.
- Share-card body lines from owner IR image/attachment nodes (no production MD/wiki image regex).
- `MarkdownCleanupFormatter` — spacing helpers on already-projected plain text.
- `AppUpdateDialog`, gallery reel, input preview — IR renderer path.

Any new production Markdown parser after this lock fails architecture ownership review.
