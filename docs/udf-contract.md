# UDF 契约（ViewModel 单向数据流权威文档）

> **本文件是 detekt 规则错误信息所引用的契约权威**：每条规则的豁免标记、理由要求、判定标准以本文件为准。
> 架构不变量仍以 `ARCHITECTURE.md` 为唯一权威；本文件不承载架构边界，只承载 ViewModel 状态层契约。

## 1. 分类总纲

每个 ViewModel 属于且仅属于以下两类之一，归类判据唯一：**是否持有屏幕可见的会话状态
（列表、编辑器、结果页等 UI 直接渲染的状态机）**。

- **契约 A —— 屏幕型**：持有屏幕可见会话状态，必须遵守第 2 节全部六条，无整体豁免。
- **契约 B —— 会话门面型**：包装平台会话（录音、更新检查、同步冲突广播等），无屏幕状态机可言，
  按第 3 节登记豁免。

归类存疑时以「是否持有屏幕可见会话状态」为唯一判准，争议结论回写本文件第 6 节清单。

## 2. 契约 A：屏幕型 ViewModel（六条，全部强制）

1. **单一状态机**：至多一条**在 VM 内构建**的 `combine(...) → sealed ScreenState` 的 `StateFlow`
   （`stateIn`/`asStateFlow` 暴露均计为构建）；把构造依赖自有的 `StateFlow<XxxState>` 原样再暴露
   （`= coordinator.state`）是单源委托卫星，不计入机器数（它不产生任何 VM 内碎片）。范本：
   `MainViewModel.uiState` 的 `MainScreenState`。共享上游用 `shareIn` 去重（audit-01 F8）。
2. **卫星流只许派生**：除状态机外的一切流只允许 `stateIn`/`combine` 派生（统一用
   `appWhileSubscribed()`）；禁止独立可变的业务卫星流；`Flow<PagingData<*>>` 必须 `cachedIn`
   （audit-01 F10、audit-07 D6）。
3. **事件只走确认式队列**：一次性事件只准经 `PendingUiEvent<T>` + `UiEventQueueCoordinator<T>`
   （按 id `consume` 的确认式队列）。禁止裸 `MutableStateFlow<Event/Effect/Request>`，禁止一切
   `Channel`/`MutableSharedFlow` 效果面（SharedFlow 无重放保障、Channel 无消费确认，均为弱语义，
   见 UDF_CONTRACT_PLAN.md 第 2 节框架判定）。
4. **封装**：`Mutable*` 类型不得暴露出类；`var` 业务属性禁止（`Job?` 取消句柄除外）。
5. **动作面唯一**：lambda 型 `val handler` 是唯一意图入口；禁止引入 `onIntent` 等中间层。
6. **共享策略**：`SharingStarted` 只准 `WhileSubscribed`（`NoUnboundedFlowSharing` 执行）；
   `appWhileSubscribed()` 是标准惯用法，确需特例（Lazily/Eagerly）用 behavior-contract 登记。

## 3. 契约 B：会话门面型 ViewModel（登记豁免）

门面型 ViewModel 不持屏幕状态机，契约 A 的部分条款结构上不适用（如无 `ScreenState` 可建模）。
采用**逐类登记豁免**：

```kotlin
// behavior-contract: session-facade-ok: <理由>
class RecordingViewModel(...) : ViewModel()
```

- 标记拼写固定为 `session-facade-ok`，理由必须成立（说明"包装哪个平台会话、为何无屏幕状态"）。
- **无理由豁免视为违规**：理由缺失、空洞（"历史原因""先这样"）或与门面判定标准不符的，按
  屏幕型处理并按契约 A 违规上报。
- 豁免解除即重归类：一旦门面型 VM 开始持有屏幕可见会话状态，立即按契约 A 重写并删除标记。

## 4. 规则 ↔ 豁免标记 ↔ 审计编号映射

| detekt 规则 | 豁免标记 | 审计依据 | 执法内容 |
|---|---|---|---|
| `ViewModelSingleStateFlow` | `session-facade-ok` | 契约 A 全文 | 至多一条在 VM 内构建的屏幕状态机流（委托再暴露不计）；卫星流只许派生；不暴露 `Mutable*`；禁 `var` 业务属性（`Job?` 除外）；门面型整体豁免 |
| `NoEventInStateFlow` | `state-event-ok` | 契约 A 第 3 条 | 事件只准走确认式队列；裸 `MutableStateFlow<Event/Request>` 违规；队列协调器产物（`StateFlow<List<PendingUiEvent<T>>>`）合法 |
| `NoMultipleEffectChannels` | `multiple-channels-ok` | 契约 A 第 3 条 | 禁止一切平行效果面（`Channel`/`SharedFlow`）；唯一合法效果面是确认式队列 |
| `NoUnboundedFlowSharing` | `unbounded-flow-ok`（及既有 `lazy-flow-ok`/`eager-flow-ok`） | 契约 A 第 6 条 | `SharingStarted` 只准 `WhileSubscribed`（既有规则，不动） |
| `PagingDataCachedIn` | `uncached-paging-ok`（仅限私有中间态；公开面必须 `cachedIn` 收口） | audit-01 F10、audit-07 D6 | `Flow<PagingData<*>>` 属性/返回链上无 `cachedIn` 即违规 |
| `NoWriteOnlyStateFlow` | `write-only-flow-ok` | audit-04 RF4 | 类内 `MutableStateFlow` 只有写入无读取即违规；跨文件读取场景登记豁免 |
| `NoCollaboratorDefaultArg` | `collaborator-default-ok` | audit-03 Q6 | Bus/Registry/Coordinator 等协作体构造参数禁默认值（防孤儿总线）；名单放模块配置 |
| `NoInSituRevisionBypass` | `in-situ-read-ok` | audit-06 U2 | 变更前就地重读快照做 CAS 基线即违规（既有规则，不动） |

规则错误信息必须引用本文件的契约名与上表标记拼写；标记正则的落地以
`apps/android/quality/detekt-rules/src/` 为实现事实，改名须同一 change 内同步本表。

## 5. 具名骨架（唯一可抓的模式）

AI/人类写屏幕型 ViewModel 时只允许复用以下具名骨架，禁止自造平行实现：

- **确认式事件队列**：`UiEventQueueCoordinator<T>` / `PendingUiEvent<T>`，位于
  `app/src/feature/common/UiEventQueueCoordinator.kt`（自 `MainEventQueueCoordinator` 提升更名；
  同一 change 内旧声明已删除）。语义：state-backed `StateFlow<List<PendingUiEvent<T>>>`，
  `enqueue` 单调自增 id，`takeLast(maxSize)` 有界，`consume(eventId)` 按 id 确认消费。
- **共享策略惯用法**：`appWhileSubscribed()`，位于 `app/src/feature/common/AppFlowSharing.kt`
  （`WhileSubscribed(5s)`）。
- **状态机范本**：`MainViewModel.uiState` / `MainScreenState`——一条 `combine` 链收拢全部屏幕
  分支为 `sealed interface`，消费侧 `when` 穷尽。

## 6. 初始归类清单（Phase 2 迁移时逐个按第 1 节判准确认，允许修正）

- **屏幕型（建状态机，契约 A 全量）**：Main、Search、Trash、TagFilter、DailyReview、
  Statistics、SyncCenter、Settings、Share、MemoEditor（有 `draftText`/`submissionState`
  屏幕状态，判屏幕型）、Tasks、Sidebar（持有侧栏可见会话状态 `SidebarUiState`，从门面型
  修正为屏幕型）、SettingsStorageFeature（`WorkspaceRootOperationState` 可见）、
  SettingsMigrationFeature（`SettingsMigrationOperationState` 可见）。
- **门面型（登记 `session-facade-ok`）**：Recording、AppUpdate、SyncConflict、
  SyncConflictState、LanShareAvailability、SettingsFeatureViewModels 内无屏幕状态机的
  动作门面（Display/ShareCard/Snapshot/Interaction/System/LanShare/Git/WebDav/S3/RemoteProvider）。

归类修正记录（迁移期间追加）：

| ViewModel | 修正 | 理由 | 日期 |
|---|---|---|---|
| SidebarViewModel | 门面型 → 屏幕型 | 持有侧栏可见会话状态（stats/tags/calendar），已有 `combine → SidebarUiState` | 2026-09-11 |
| TasksViewModel | 新增屏幕型 | 任务列表页持有 `TasksScreenState` | 2026-09-11 |
| SettingsStorageFeatureViewModel | 门面候选 → 屏幕型 | 持有可见 `WorkspaceRootOperationState`，单机合法 | 2026-09-11 |
| SettingsMigrationFeatureViewModel | 门面候选 → 屏幕型 | 持有可见 `SettingsMigrationOperationState`，单机合法 | 2026-09-11 |

## 7. 免责条款

1. **豁免必须写理由**：所有 behavior-contract 标记（`session-facade-ok` 及第 4 节全部标记）必须
   附成立理由；无理由、理由空洞或理由与判定标准不符的豁免一律视为违规，不受标记保护。
2. **契约权威**：本文件是 detekt 自研规则错误信息引用的契约权威；规则文本、标记拼写、契约条目
   三者不一致时，以本文件为裁定基准并同 change 修复偏差。
3. **禁止平行实现**：契约条款只允许经本文件登记的具名骨架满足；任何绕过骨架的自造模式
   （第二套队列、第二套共享惯用法、`onIntent` 中间层）即违规，不因"功能等价"而豁免。
