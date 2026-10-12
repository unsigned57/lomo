# Dependency Gap Register

已裁决 DOMAIN/GRAY-保留项的拒绝/挂账档案。[Dependency Selection](dependencies.md) 拥有
选型规则;本文件拥有其要求的佐证记录(「记录检索面与 scope」「指出具体 blocker」)。
每条目给:对象 | 候选(版本,检索面)| 缺口/blocker(具体、可证伪)| 复评触发器 | 裁决出处。
裁决论据以出处文件为准;本表登记结论与可复用的复评条件,不重复展开证据。

注意:`dependency-capabilities.toml` 是机器消费的「已选库能力分类」;未采用候选一律不入册
(混入会污染 fail-closed 校验)。登记只写本文件。

## 一览

| # | 对象 | 裁决 | 出处 |
| --- | --- | --- | --- |
| G-02 | `lomo-store` reader_pool(读连接池+代际 epoch) | DOMAIN | `audit/grey/01` §G-02 |
| G-04 | `lomo-application` calendar 严格定宽日期语法 | DOMAIN | `audit/grey/01` §G-04 |
| G-05 | `lomo-sync` s3+webdav 传输/适配器 | DOMAIN | `audit/grey/02` §G-05 |
| G-06 | `lomo-platform-fs` watcher(inotify+poll) | DOMAIN | `audit/grey/02` §G-06 |
| G-07 | `apps/tui` text_layout grapheme wrap+锚点 | DOMAIN | `audit/grey/02` §G-07 |
| G-08 | `apps/tui` input.rs TextBuffer | DOMAIN | `audit/grey/02` §G-08 |
| G-09 | `lomo-application` search_excerpt 拼音匹配 | DOMAIN | `audit/grey/02` §G-09 |
| G-10 | `lomo-media` identity magic 表 | DOMAIN | `audit/grey/02` §G-10 |
| G-11 | `lomo-feasibility` 同步 TLS fixture | DOMAIN | `audit/grey/02` §G-11 |
| G-12 | `lomo-sync` transport 临时文件命名 | GRAY-挂账 | `audit/grey/02` §G-12 |
| G-13 | `ui-components` DraggableScrollbar 族 | DOMAIN | `audit/grey/03` §G-13 |
| G-14 | `ui-components` SecondsWheelPicker 族 | DOMAIN | `audit/grey/03` §G-14 |
| G-15 | `ui-components` Calendar/Weekly 热力图族 | GRAY-需记录 | `audit/grey/03` §G-15 |
| G-16 | `domain` AppUpdateEvaluation 版本比较 | DOMAIN | `audit/grey/03` §G-16 |
| W-15 | `ui-components` 手写 Canvas 统计图 | GRAY-品牌层论证 | `audit/grey/03` §W-15 |
| W-10 | `lomo-core` WorkspaceLock 目录锁协议 | 待 spike | `audit/02` §3 W-10 |
| 启动方向 A | reconcile 移出首屏路径 | 专项裁决否决,不立项 | `audit/grey/06` §专项 |
| W8-登记 | provider_smoke 凭证 / KDF 真机 / 真机终端渲染 / Gate-modeling / post-apply | 环境·设计登记 | `audit/01` §6 |

## DOMAIN 保留项(候选不满足已证实契约)

### G-02 reader_pool.rs(245 LOC)— 读连接池 + 代际 epoch

- **候选**:`r2d2 0.8.10` / `deadpool-sqlite 0.14`(docs.rs/crates.io,API 面逐一核实)。
- **缺口/blocker**:① r2d2 `is_valid`/`has_broken` 仅在归还(checkin)时同步调用,
  无法承载 checkout 时代际拒绝;`Pool` 实测无 `retain`、无 `close`,in-flight open
  复核(`open_reserved` 重锁校验 `epoch`)两库均无钩子 —— 代际机制只能在适配层重写;
  ② 7 个错误码中 `pool_changed`/`inconsistent`/`epoch_exhausted`/`lease_consumed`
  在两库均无对应物(r2d2 单一 `r2d2::Error` 不细分超时/后端),这些码是代际语义的
  观测面;③ deadpool `Manager::create`/`recycle` 是 async fn,需 tokio runtime +
  每连接 `SyncWrapper` 线程,对 4 连接同步只读池是结构性错配。净账:通用池只置换
  ~80 LOC 标准同步机械,适配层重建全部代际逻辑,收益为负。
- **复评触发器**:池规模扩大(>16 连接、连接复用跨宿主),或通用池出现
  retain/close 阶段钩子与 checkout 校验区分面。
- **裁决出处**:`audit/grey/01-deps-rust-core.md` §G-02(DOMAIN 0.7)。

### G-04 calendar.rs 严格定宽日期语法(纯解析 ~140 LOC,文件其余已委托 jiff)

- **候选**:`jiff::fmt::strtime::strptime`(vendored `jiff-0.2.31` 源码核实)。
- **缺口/blocker**:`parse_number`(parse.rs:858-909)先剥离任意前导空白再读
  `1..=max` 位数字;`parse_year`(:745-760)接受可选符号 —— `%Y` 接受
  `+2024`/`999`/` 2024-01-01`,`%m`/`%d` 接受 1-2 位变宽;`%4Y` 宽度扩展只放宽
  上限而非严格等宽。与 Kotlin `uuuu`+`ResolverStyle.STRICT` 等宽 parity
  (`StorageFilenameFormats.kt:48-52` 同款 5 模式逐字一致)正面冲突;放宽宽度即
  双端文件名/时间 token 身份判定分裂。5 格式 or-nothing 探测与错误变体分类亦由本层承载。
- **复评触发器**:jiff strptime 提供严格等宽/无符号/无空白剥离模式;或 Kotlin
  侧放宽 STRICT 契约(会改跨端身份语义,需三端评审)。
- **裁决出处**:`audit/grey/01-deps-rust-core.md` §G-04(DOMAIN 0.9)。

### G-05 lomo-sync s3+webdav 传输/适配器(~5.6K LOC)

- **候选**:`opendal 0.59.4`(crates.io/docs.rs + 上游 backend 能力表源码核实);
  `aws-sdk-s3`(async-only smithy 大栈,直接出局)。
- **缺口/blocker**:① opendal `services::Webdav` 能力表**未声明**
  `write_with_if_match`/`delete_with_if_match`(仅 0.60 起开放读侧 conditional
  read)——调用即 `ErrorKind::Unsupported`,而 WebDAV CAS 是本 crate 发布期
  fail-closed 强制契约(`RemoteCapabilities` 探针 + `remote_capability_unsupported`);
  ② 无跨进程持久 multipart 续传公共 API —— upload_id/confirmed_parts 不对外暴露,
  `MultipartSession`(`.lomo/sync/v1/multipart/*.rec` + fence + TTL + CAS-at-commit)
  无可映射面,该层只能留在自研 transport;③ 局部迁移 = multipart 与非 multipart
  写路径分裂为并行栈,dependencies.md 明文禁止。S3 侧条件写/删、commit 期 CAS、
  ETag 回读、BlockingOperator 经核实为等价面,不构成 blocker。
- **复评触发器**:上游 webdav 声明条件写能力位 + 出现可注入既有 upload_id 的
  可恢复 multipart 公共 API;或产品放弃 durable multipart resume(不建议)。
  迁移评审时补 `cargo bloat` 对 `.so` 的体积对照。
- **裁决出处**:`audit/grey/02-deps-rust-io.md` §G-05(DOMAIN 0.85);
  `audit/05` §4 不做清单同条。

### G-06 platform-fs watcher(481 LOC,inotify + poll 双后端)

- **候选**:`notify 8.2.0`(9.0.0-rc.5 预发;MSRV 1.88 < workspace 1.98;CC0-1.0)。
- **缺口/blocker**:① 模型不兼容 —— notify 是后台线程 + 回调/channel **推**模型,
  无 pull-drain API;本契约是调用方自持节奏的有界 `wait_events(timeout) -> Vec`
  (`poll_events` NONBLOCK drain + `wait_events` poll(2)+deadline);
  ② 域语义无对应 —— `ChangeKind::{Rescan,Invalidated}`、rebuild-before-publish
  顺序保证、根丢失逐 poll rebind +「事件清空后才报错」排序,notify 的 watch 错误
  经事件流上报且无重绑编排;③ 背压/去抖语义不同:notify 默认无界 mpsc,去抖在
  独立 crate(时间窗合帧 ≠ 本仓投递失败可还原的 `pending`/`observed` 累积器)。
  适配层规模≈现有实现而丢弃其契约。syscall 层已委托 rustix,保留部分是事件图管理策略。
- **复评触发器**:notify 出现有界 pull-drain API;或非 Linux 真内核通知
  (FSEvents/RDCW)相对快照 diff 的保真差距成为实际问题(poll.rs 已知取舍)。
- **裁决出处**:`audit/grey/02-deps-rust-io.md` §G-06(DOMAIN 0.8)。

### G-07 apps/tui text_layout.rs(202 行)— grapheme wrap + 锚点

- **候选**:`unicode-linebreak 0.1.5`(UAX#14,Apache-2.0,2023-07 最新);
  ratatui `WordWrapper`(0.29 pin 内置)。
- **缺口/blocker**:① 模型不等价 —— unicode-linebreak 产 UAX#14 **断行机会点**
  (`BreakOpportunity::{Allowed,Mandatory}`),URL/长标识符只有稀疏断点;本契约是
  「任意 grapheme 边界、满宽即硬断」的编辑器语义(光标逐 grapheme 可达),
  采用即收窄断点集、超宽串重新溢出 = 重现 09-I6-01 缺陷;要保契约仍需机会点外
  兜底硬断,库输出沦为不生效的提示。② 坐标系不同 —— 库产字节 offset,无
  `TextAnchor{line,grapheme}` 锚点、无样式 span 合并、无 limit 提前退出。
  ③ `WordWrapper` 对不可断词溢出右缘而不硬折(0.29 行为未变),且不产
  VisualLine/anchor/limit 三要素。
- **复评触发器**:产品把折行语义改为「词边界 + 溢出省略」(非编辑器语义)。
- **裁决出处**:`audit/grey/02-deps-rust-io.md` §G-07(DOMAIN 0.85)。

### G-08 apps/tui input.rs TextBuffer(144 行)

- **候选**:`ratatui-textarea 0.9.2` / `tui-textarea 0.7.0`(GitHub/crates.io)。
- **缺口/blocker**:① 层错配 —— TextBuffer 是 TEA model 侧纯数据
  (`Msg → update → Model` 消费,不经 widget `input()`/`render` 管线);
  `TextArea` 是「存储 + 键映射 + `impl Widget`」耦合控件,换它即把渲染控件嵌入
  状态层。② 版本硬冲突 —— ratatui-textarea 0.9.2 依赖 ratatui 0.30,本仓 pin
  `=0.29.0`,双版本并存使 `Widget`/`Line` 类型不兼容;tui-textarea 0.7.0 兼容
  0.29 但 2024-10 起停更(已被 ratatui 组织 fork 取代)。③ 光标坐标系(字符
  (row,col) vs 字节下标 + grapheme 吸附)与域方法(`tag_prefix`/`complete_tag`/
  `UNDO_LIMIT=100`)需适配即重写。
- **复评触发器**:本仓 ratatui 升 0.30 且接受 widget 态入 model 层(改变 TEA
  分层,需独立架构评审)。
- **裁决出处**:`audit/grey/02-deps-rust-io.md` §G-08(DOMAIN 0.9)。

### G-09 search_excerpt.rs(260 行)— 拼音感知匹配 + 字节区间(正式拒绝记录)

- **候选**:`nucleo-matcher 0.3.1`(crates.io,helix-editor/nucleo;crate 自述
  core matcher complete;W1 同类候选此前已拒)。
- **缺口/blocker**:① `fuzzy_indices` 产 `Vec<u32>` grapheme 下标(Utf32Str 坐标,
  需 `unicode_segmentation` feature),不是源 `&str` 的 UTF-8 字节区间 —— 供给
  TUI/Kotlin 高亮契约(`Vec<Range<usize>>` 字节坐标)需另写 index→byte 映射;
  ② 无拼音音节展开机制,needle 必须与 haystack 字符直接相等,无法表达
  「一个源字 → 多候选读音 → 回映源字节区间」的 DP(该 DP 正是现有实现本体);
  ③ Smith-Waterman/fzf bonus 评分体系与本仓 proximity/三路取优(Body/Path/Pinyin)
  输出契约不等价,换库即改排序行为;④ `pinyin = "=0.10.0"` 已正确承担音节供给,
  无能力空缺。nucleo 只能顶替 direct_evidence 的平凡 lane,净收益为负。
- **复评触发器**:出现「带拼音映射且产 UTF-8 字节区间」的成熟 matcher
  (当前生态未见)。
- **裁决出处**:`audit/grey/02-deps-rust-io.md` §G-09(DOMAIN 0.9)。

### G-10 lomo-media identity.rs magic 表(:265-318,~54 行)

- **候选**:`infer 0.22.0`(crates.io,2026-07 活跃;`Infer::add` 支持自定义
  matcher)。
- **缺口/blocker**:可替换面仅 ~54 行透明查表;产品策略层(白名单 13 变体 +
  magic×扩展名冲突 `media_magic_extension_conflict` + ISO-BMFF 待证收窄)无论如何
  保留。语义不可无损映射:① infer 把 heic/heix 品牌并入 `image/heif`,本仓刻意
  区分 `ImageHeic`/`ImageHeif`;② `isom/mp41/mp42` infer 判 `video/mp4`,本仓判
  `Mp4Container` 且须 `.m4a` 见证才进音频道 —— 同一字节两结论,等价须再加收窄
  适配层;③ fail-closed 冲突矩阵是产品策略,infer 无对应概念。54 LOC 换依赖 +
  收窄层,净收益为负(能力边界不构成 blocker,lomo-media 仅 Portable 合法)。
- **复评触发器**:媒体白名单/冲突策略本身变化(产品决策)再重估。
- **裁决出处**:`audit/grey/02-deps-rust-io.md` §G-10(DOMAIN 0.8)。

### G-11 lomo-feasibility http_probe(1342 行)+ git_http(1128 行)同步 TLS fixture

- **候选**:`wiremock 0.6.5` / `mockito 1.7.2` / `httpmock` / `tiny_http 0.12`
  (crates.io/docs.rs 逐一核实)。
- **缺口/blocker**:「小型同步 TLS fixture」生态位核实为真空 ——
  ① wiremock async-only(tokio/async_std runtime),HTTP-only 无 TLS,注入 runtime
  与「同步 fixture 不内嵌 async 栈」隔离目标正面冲突;② mockito sync API 是内嵌
  async server pool 的阻塞门面(docs.rs 明示 `Cannot block…within a runtime`),
  HTTP/1+2 但无 TLS;③ httpmock 同类 async 栈无 TLS;④ tiny_http 0.12 有 TLS
  (`ssl-rustls` feature、`Method::NonStandard` 可覆盖 PROPFIND/MKCOL),但 pin
  rustls **0.20**,与 workspace `rustls = "=0.23.45"` 形成双版本栈,rcgen 签出的
  0.23 系证书类型不互通;⑤ 无候选提供「按请求 id 记录服务端写失败」钩子
  (`x-lomo-stream-id` + `failed_stream_ids`,取消证据机制)与证书拒绝对、
  WebDAV/SigV4/multipart 受控路由 —— 属探针仪器本体。可复用切片仅 ~150 行。
- **复评触发器**:出现同步 + rustls 0.23 + 可注入证书的 mock server 库。
- **裁决出处**:`audit/grey/02-deps-rust-io.md` §G-11(DOMAIN 0.85)。

## GRAY-保留 / 挂账(记录复评条件)

### G-12 lomo-sync transport 临时文件命名(s3 :398 / webdav :336,~5 行 ×2)

- **候选**:`tempfile = "=3.27.0"`(workspace 已 pin;`Builder::tempfile_in` +
  `keep()` 语义已核实逐字对齐「已知路径 + 落盘存续 + 手动回收」)。
- **缺口/blocker**:唯一 blocker 是 owner 能力边界 —— lomo-sync 能力表仅
  `[Portable, Network]`(rust_graph.rs:67-71),`tempfile` 分类 `filesystem`;
  为 ~10 LOC 生产 edge 扩架构边界成本倒挂(非技术不匹配;tempfile RNG + 原子计数
  命名严格无碰撞,优于仅纳秒拼接)。不为此单独申请边界变更。
- **复评触发器**:lomo-sync 若因其他任务获准 `filesystem` 能力,顺手采纳 —
  两处 `temp_path` 换 `Builder::tempfile_in` + `keep()`,删纳秒拼接。
- **裁决出处**:`audit/grey/02-deps-rust-io.md` §G-12(GRAY-需记录 0.75);
  `audit/05` §4 不做清单同条。

### G-13 ui-components DraggableScrollbar 族(876 LOC)

- **候选**:`LazyColumnScrollbar 2.2.0`(`com.github.nanihadesuka`,JitPack 渠道;
  2024-06 停发,master 合并 PR 未发版;9 个未结 issue 中 #40「items are not of
  uniform size」2024-08 至今未解,正是本实现核心能力)、
  `com.composables:composeunstyled-scrollbars:2.10.0`(Maven Central,KMP)、
  `io.github.oikvpqya.compose.fastscroller:fastscroller-core:0.3.2`。
- **缺口/blocker**:四条硬契约无候选覆盖 —— ① 变高条目像素分数正/逆映射 +
  边界钉住(候选 #40 即社区未解证据;两新候选均为 index/平均高度近似映射);
  ② 分页 `totalItemsCount`/`scrollTargetItemsCount` 双计数 override;
  ③ `ScrollState`/`LazyListState`/View 三态适配器;④ M3 MotionScheme/haptic/
  `systemGestureExclusion` token 集成。官方侧:compose-foundation 1.9 起
  `ScrollIndicatorState` 上 Android(状态原语免费),但可拖拽 Thumb 仍只在
  skikoMain(Desktop/iOS/web);wear `ScrollIndicator` 非拖拽 —— 视觉+拖拽+映射
  仍是真缺口。
- **复评触发器**:官方为 Android 发布 thumb/indicator 组件;或候选提供
  pixel-accurate 变高映射 + count-override 契约。可选优化:LazyList 变体用
  `LazyListState.scrollIndicatorState` 供给正向分数(逆映射/拖拽仍自研,收益小)。
- **裁决出处**:`audit/grey/03-deps-android.md` §G-13(DOMAIN 0.85)。

### G-14 ui-components SecondsWheelPicker 族(484 LOC)

- **候选**:`com.swmansion.kmpwheelpicker:kmp-wheel-picker:0.3.0`(Maven Central,
  MIT,KMP;Software Mansion + vico 作者;0.x 活跃但 2025-10 创建);备选
  zj565061763 wheel(Android-only、单人)更弱。
- **缺口/blocker**:① M3 形态缺口成立 —— 移动端 `androidx.compose.material3.
  TimePicker`/`TimePickerState` 仅 hour/minute 无秒列,秒支持只在 wear artifact
  (`TimePickerType.*Seconds*`);② kmp-wheel-picker 公开 API/README 无任何
  accessibility progress 语义(`ProgressBarRangeInfo`/`setProgress`/`steps`)——
  TalkBack setProgress clamp、外值同步来源去重、reduce-motion、本地化 state
  description 四项测试钉死契约全需外层适配,可删核心仅 ~200/484 LOC 且引 0.x
  供应链风险。
- **复评触发器**:kmp-wheel-picker ≥1.0 且明确暴露 semantics/拖拽状态钩子;
  或官方移动端 M3 增秒列(wear artifact 已证形态存在)。
- **裁决出处**:`audit/grey/03-deps-android.md` §G-14(DOMAIN 0.8)。

### G-15 ui-components CalendarHeatmap + WeeklyHourHeatmap 族(1,182 LOC)

- **候选**:`compose-git-grass 1.2.0`(`io.github.ois0886`,Apache-2.0,Maven
  Central;1.1.0→1.2.0 后能力重叠显著上升:长按/受控选中/a11y/本地化/首组合滚到
  最新均已覆盖);`com.fleeys:heatmap:1.0.5`(16 star 单人,无长按)同样不足。
- **缺口/blocker**:否决理由是**信誉 + 半覆盖**而非无候选 —— ① git-grass
  0 star/0 fork/0 issue/单作者/2026-02 创建/41 commits,credible-candidate 尺度下
  供应链信誉证据不足;② WeeklyHourHeatmap(387 行,7×24 矩阵)两候选均不覆盖,
  迁移最多删一半;③ 未证实等价项:跟随滚动位置的年份标签 + misty fade(git-grass
  为静态 `formatYearLabel`)、月标签碰撞避让排版(文档未披露需 spike)、popup
  气泡体系(本地化复数 + 锚点 + 动画,与 Weekly 图共享
  `HeatmapPopupPositionProvider`,迁移后共享层仍须保留)、`scrollState` 未暴露。
- **复评触发器**:compose-git-grass 存活 ≥1 年、出现外部采用者/多维护者,或达成
  vendored 共识(Apache-2.0 ~8 源文件);迁移评审前先做 1h spike:fixture 渲染对比
  月标签避让/自动滚到底/长按回调/本地化。
- **裁决出处**:`audit/grey/03-deps-android.md` §G-15(GRAY-需记录 0.7)。

### G-16 domain AppUpdateEvaluation 版本比较(~20 LOC)

- **候选**:`io.github.z4kn4fein:semver:3.1.0`(Maven Central,KMP,MIT,2026-05
  活跃;`toVersion(strict=false)` 覆盖 `v` 前缀与 `1`/`1.2` 变宽输入)。
- **缺口/blocker**:容错契约不兼容 —— 库宽松模式仍拒绝四段版本(`1.2.3.4`)与
  数字位上的非数字段(`1.2.x`/`3-beta` → 本实现 `toIntOrNull() ?: 0` 静默归 0);
  「不可比视为有更新」兜底(`remoteVersion != localVersion`,仅在 versionCode
  缺失时生效,测试钉死为旧环境兼容路径)换库后需重新钉义失败策略。20 LOC 兜底
  路径换一新依赖,边际收益为负。
- **复评触发器**:版本字符串上游形态变化或容错语义被产品重新定义。
- **裁决出处**:`audit/grey/03-deps-android.md` §G-16(DOMAIN 0.9)。

### W-15 ui-components 手写 Canvas 统计图(HourlyActivityChart 292 + TagDistributionChart 101 = 393 LOC)

- **候选**:`com.patrykandpatrick.vico:compose:3.3.1`(Maven Central,Apache-2.0,
  Compose-first KMP,活跃;交互映射已核实:`CartesianMarker`+`rememberToggleOnTap`
  ≈点击气泡切换、`ColumnCartesianLayer` 自定义 column provider 可做四级色阶、
  `HorizontalAxis` valueFormatter 覆盖 `Nh` 标签 —— 能力上可承载)。
- **缺口/blocker**:能力缺口不成立,**成本/品牌层否决** —— 净删约 150-200/393
  LOC vs 引入整套 charting 引擎 + ~100-150 LOC 适配层(色阶 provider、空轨背景条
  需第二 series、选中描边无 marker 直接对应物);popup 视觉与 Calendar/Weekly
  热力图共享 `Popup`+`HeatmapPopupPositionProvider`,换 vico 后统计页出现两套
  气泡机制需重新对账;TagDistributionChart(Row+weight 标签条)无对应物必保留;
  现有实现已 token 化、测试钉死、无正确性债。
- **复评触发器**:统计页新增图表形态(折线/趋势/第二屏)— 届时直接立项 vico
  迁移(交互已证实可映射),不再新写 Canvas 图。
- **裁决出处**:`audit/grey/03-deps-android.md` §关联裁决 W-15(GRAY-品牌层
  0.65);`audit/05` §4 不做清单同条。

## 待 spike / 专项裁决项

### W-10 lomo-core WorkspaceLock 目录锁协议(~400 LOC)— 待 spike,未裁决

- **候选**:① `std::fs::File::{lock,try_lock}`(std 1.89 稳定;仓内已有先例
  `lomo-application/src/lock.rs:36`、`lomo-platform-fs/src/lock.rs:105`);
  ② `fd-lock 4.0.4`(Apache-2.0,活跃);`fs2`/`fslock` 备选。
- **缺口/blocker**:迁移前置事实未证实 —— ① control_root 恒在支持 flock 的
  本地 FS(应用私有目录大概率成立,须排除 NFS/SAF 目标);② 30s 陈旧锁 reclaim +
  `/proc/self/stat` 存活检查语义不被多进程测试工装依赖。若两点证实:内核锁随进程
  死亡自动释放,可整体消除存活检查(Linux-only `/proc` 依赖)与 reclaim 机制,
  owner JSON 留作诊断(pid/version/engine_id),`lock_reclaim_seconds` knob 随之删。
- **复评触发器**:flock 可用性 spike 立项并证实上述两点 → 正式迁移立项。
- **裁决出处**:`audit/02-自造轮子与依赖调研报告.md` §3 W-10;`audit/05` §4
  不做清单/§13 W8 登记。

### 启动方向 A — reconcile 移出首屏(专项裁决否决,不立项)

- **候选(提案)**:`WorkspaceSession::open` 先返回「可信投影+未认证」,
  `list_recursive`/digest/scoped apply 移到 `RuntimeReady` 之后 Maint lane
  (`audit/tui-startup.md:256-262`)。
- **缺口/blocker**:① 等效性论证不成立 —— `open` 内 `recover_files_for_mount`
  (session.rs:97 pending-journal 扫描)/clock floor 恢复/`rebuild_locked`/
  `acknowledge_mounted_operations` 是**恢复语义**而非对账;未鉴权投影缺
  pending-journal 已提交证据与外部删除/trash/purge 抑制,不是 watcher-miss
  能产生的陈旧形态。② 收益结构错位 —— warm open 27-38ms 无税可省;clean cold
  ~670ms 大头是 store/journal/fs 首触;dirty open 才由全扫 dominate,而 dirty
  恰是投影最不可信、最不该展示的窗口。③ `open` 成功语义从「已鉴权投影」变为
  「可读未鉴权」,外溢 lomo-native FFI/TUI lanes/Android mount readiness
  (`admitsProjectionReads`)三端,属跨语言生命周期变更。
- **复评触发器**:立项前置(全部满足再经 ARCHITECTURE 级评审):会话就绪契约
  `ProjectionAuth::{Reconciling,Authenticated}` 双态 + 未鉴权期合法操作集 +
  恢复顺序证明(pending replay/ack 先于任何可见读或并入未鉴权语义)+ open 各
  阶段计时分解否定「省 listing+digest≈全 TTI」+ 未鉴权窗口 mutation gate/rebase
  方案。便宜等价替代:Loading shell + `BootPhase` 细化到 reconcile 阶段、
  `bootstrap_model` feed/sidebar 加载并行化 —— 已覆盖干净路径全部收益。
- **裁决出处**:`audit/grey/06-perf-suspects.md` §专项裁决(置信度 高);
  `audit/05` §4 不做清单首条。

## 环境 / 设计登记项(非依赖候选,等条件成熟)

| 项 | 类型 | 缺口/blocker | 关闭条件 | 出处 |
| --- | --- | --- | --- | --- |
| `provider_smoke` `#[ignore]`(4~6 项) | 环境依赖 | 需真实 S3/WebDAV 凭证,门禁外 | 真实凭证到位 | `audit/01` §6;`audit/05` §13 W8 |
| KDF 600k 真机验证 | 环境依赖 | 低端机 KDF 时延未实测;AEAD 不区分密码错与篡改为登记的设计边界 | 真机 CI / 手动验证清单执行 | `audit/01` §6;`audit/05` §13 W8 |
| 真机终端渲染(kitty/sixel/tmux/小终端) | 环境依赖 | 仅契约测试 + 注入应答,真实终端形态未验 | 真机测试用例执行 | `audit/01` §6;`audit/05` §13 W8 |
| Gate-modeling 边界 | 设计登记 | 跨文件同包遮蔽(over-report)/通配压制(strict)/声明 vs 分支粒度;登记为不收敛方向,均向 fail-closed/strict 侧倒 | ARCHITECTURE 级评审决议 | `audit/01` §6;`audit/05` §13 W8 |
| post-apply 边界 | 设计登记 | post-apply 自证 claim-only / 同路径多 claim 序敏感,结构性边界 | ARCHITECTURE 级评审决议 | `audit/01` §6;`audit/05` §13 W8 |
