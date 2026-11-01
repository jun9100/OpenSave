# OpenSave Android 掌机体验优化方案（修订版）

> 适用版本：OpenSave Android 0.3.16（`com.jun9100.opensave`），计划升到 0.4.x
> 目标设备：AYN Odin2（Android 13 / API 33，1920×1080，Magisk Root，内置手柄）。主要横屏使用，偶尔竖屏。
> 原则：在现有 Android/PC 代码上改进，不重写；与 PC 端（Go + Wails）和 game-save 协议保持一致。
> 说明：本方案来自只读代码调研和 Odin2 实机截图，没有改代码，也没有构建。行号以当前 `main` 分支为准；修订时重新核对过关键行号。

路径简写约定：

- `A/` = `/Volumes/Date/Coding/OpenSave/android/app/src/main/java/com/jun9100/opensave/`
- `Res/` = `/Volumes/Date/Coding/OpenSave/android/app/src/main/res/`
- `FE/` = `/Volumes/Date/Coding/OpenSave/cmd/opensave-app/frontend/src/`
- `Go/` = `/Volumes/Date/Coding/OpenSave/internal/`

---

## 0. 一页摘要

| 问题 | 根因 | 处理方式 |
|---|---|---|
| 横屏一屏只能看到约 2 款游戏 | 系统状态栏和手势条约 48dp，顶栏 64dp，底栏 80dp，再加一个会被滚走的约 255dp 头部，每张卡约 244dp | 横屏改用 NavigationRail，去掉顶栏。工具条固定在列表上方。列表改为 56–60dp 的紧凑行，右侧放详情（双栏）。状态条只在批量任务运行时出现 |
| 手柄难用 | 代码里没有任何焦点或按键处理；一张卡有 4 个焦点停靠点；看不到焦点；没有返回栈 | 每行只有一个焦点，加焦点环。A、B 和方向键交给系统和 Compose 处理（B 由 BackHandler 接住）；X/Y/L/R/Start/Select 在 Activity 层统一分发。按键语义沿用 PC 的 `controller.js` |
| 最常用的"备份有变更"太难找 | 没有去重，也没有"有变更"的判断；一次只能默认全选后批量备份 | 去重提前到阶段 1。工具条常驻"备份有变更 (N)"按钮，Start 键直达，作为核心流程 F0 |
| 逻辑不清楚 | 概念多（条目、款、候选、工作区、head）；不存在的来源也默认勾选；状态提示在页面之间串扰；危险操作没有确认 | 一级页面收成 游戏库 / 来源 / 设置 三个，游戏详情和"同步与任务"作为二级页。术语与 PC 统一。危险操作改为"一次确认，事后可撤销" |
| 批量能力弱 | 只有"批量备份"一个操作：默认全选，不能取消，没有持久报告，不去重 | 加显式选择模式和状态筛选。首批 4 种批量操作；进度可取消；最近报告持久保存；失败项可一键重试 |
| 两端不一致 | PC 把 Switch 存档写进 `pc` 命名空间，Android 写进 `switch`；非 Switch 游戏在 Android 上的 gameId 混入了来源路径哈希 | 这是协议层的问题，**单独立项（PC 修复为主）**，不算进 0.4.0 的里程碑和验收；本方案只记录对齐要求 |

---

## 1. 背景与目标

### 1.1 用户反馈要点

1. 掌机上操作不方便：手柄和 D-pad 基本用不起来，主要按钮离得远。
2. 逻辑不够清晰：概念多，页面职责混在一起，很难知道当前状态和下一步该做什么。
3. 横屏和竖屏都要进一步优化：横屏一屏只能看到一行卡片，竖屏密度也低。
4. 批量操作能力差：只能"批量备份全部已勾选"，没有其他批量动作，也没有进度管理。
5. 约束：在现有 Android/PC 版本上优化，两端保持一致，不推倒重写。

### 1.2 成功标准（在 Odin2 默认显示大小下实机测量）

**高度预算（横屏，按截图推算的 835×470dp）**：

| 项 | 高度 |
|---|---|
| 状态栏 | 约 24dp |
| 底部手势条（截图底部 y≈588→618 的浅色条） | 约 23–24dp |
| App 可用高度 | 约 422dp |
| 固定工具条 | 52dp |
| 列表区，无批量任务时 | 约 370dp |
| 列表区，批量状态条出现时（34dp） | 约 336dp |

如果实际窗口是 960×540dp，可用高度约 492dp。

| # | 指标 | 现状 | 目标 | 测量方法 |
|---|---|---|---|---|
| S1 | 横屏可滚动列表区高度 | 约 230dp | ≥ 340dp（批量状态条出现时也要满足）；开沉浸模式时 ≥ 380dp | 截图量像素，用 `wm density` 换算 |
| S2 | 横屏一屏完整可见的游戏行 | 约 2 | ≥ 5（行高 56–60dp） | 在 90 款的真实库上截图计数 |
| S3 | 竖屏一屏完整可见的游戏行 | 1–1.5 | ≥ 7 | 同上 |
| S4 | 横屏每款游戏的 D-pad 焦点停靠数 | 4（复选框、展开、详情、备份） | 1 | 从第 1 款按 ↓ 到第 10 款，按键数 ≤ 9 |
| S5 | 核心流程可操作性 | 横屏手柄 0/8 | 横屏只用手柄完成 F0–F7（8/8）；竖屏只用触屏完成 F0–F5 | 见下方 F0–F7 |
| S6 | 批量操作种类 | 1（备份） | 首批 4 种：备份所选/有变更、上传待传、检查云端更新、安全恢复云端较新；另加每游戏自动备份开关。后续扩展：恢复本地最新、下载云端、清理旧快照 | 功能清单 |
| S7 | 批量任务健壮性 | 不能取消，没有报告（截取 6 行塞进 error） | 可以取消；最近 20 份报告持久保存；失败项一键重试；中断后重跑一次即可补齐（去重保证幂等），不会重复生成快照 | 跑到中途取消，或执行 `kill -9`，然后重跑，对比快照数 |
| S8 | 横竖屏切换后状态保留 | 选择、筛选、展开状态全丢 | 选择集、筛选、当前游戏、滚动位置都保留 | 旋转测试 |
| S9 | 误操作保护 | 写回工作区、移除授权都是单击即执行 | 破坏性操作有一次确认（默认焦点在"取消"），恢复后提供"撤销恢复"；备份类操作不弹确认，但进度可取消 | 走查 |
| S10 | （跨端跟进，不计入 0.4.0 验收）跨端一致性 | 同一个 Switch TitleID 在两端落在不同 scope | 仅限 Root 来源：两端读写同一个 `switch/switch-<tid>` scope | 在 game-save 上列 scope 验证 |

核心流程（S5）：

- **F0：一键备份全部有变更的游戏**（按 Start，或工具条上的"备份有变更 (N)"），查看结果。
- F1：在 游戏库 / 来源 / 设置 之间切换（L1/R1）。
- F2：切换类型筛选，找到某款游戏。
- F3：选中游戏，在右栏备份这款游戏。
- F4：进入选择模式，选 5 款，批量备份，查看报告。
- F5：从详情的"版本"里恢复某个版本（一次确认），然后撤销这次恢复。
- F6：从游戏库横幅进入，处理一条"跨设备更新"。
- F7：修改 game-save 地址并测试连接（或从剪贴板导入配置）。

---

## 2. 现状问题

### 2.1 布局与横竖屏

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| L1 | 窗口尺寸假设可能不对。截图宽 1100px，对应实际 1920px，推算密度约 2.3，窗口约 835×470dp；任务给的是 960×540dp | 状态栏、TopAppBar、NavigationBar 三处分别推算，都得到约 2.3。`GridCells.Adaptive(280.dp)`（`A/MainActivity.kt:277`）在 960dp 宽下应排 3 列，实际只有 2 列（截图 03/04） | 高。断点不能写死，要在实机确认 |
| L2 | 横屏纵向空间被外壳占掉 | 系统状态栏约 24dp，底部手势条约 24dp。TopAppBar 在 `MainActivity.kt:181-204`（64dp），NavigationBar 在 `:207-220`（80dp）。加起来约 192dp，App 内容区只剩约 278dp | 高 |
| L3 | 游戏库头部会跟着列表滚走，并占掉大半个首屏 | 头部是网格的第一个 span item（`:283-419`），包含标题统计、来源明细、PC 容器统计、筛选 chip（`:391-396, :496-511`）、`BatchSelectionToolbar`（`:398-414`）、action 文本（`:415-417`），合计约 255dp（截图 03 中 y=116→450） | 高 |
| L4 | 游戏卡太高，信息重复 | `GameLibraryCard`（`:1058-1231`）横屏约 244dp。标题区 4–6 行（`:1239-1276`）；"详情"按钮（`:1154-1157`）和展开箭头（`:1292-1297`）功能重复；每张卡都有一个通栏的"备份游戏存档"（`:1159-1168`）；卡片本身不能点 | 高 |
| L5 | 详情只能在网格里原地展开 | expanded 分支在 `:1170-1228`，最多 24 个位置（`:1398`）。展开后可高达上千 dp，同一行的另一张卡会留出大片空白 | 高 |
| L6 | 现有的横屏适配只换了封面 | `:1068` 用 `orientation == LANDSCAPE` 判断，只是把 96dp 通栏封面换成 56dp 缩略图（`:1086-1112`），其他结构没动 | 中 |
| L7 | 竖屏是单列，每卡约 300dp，首屏约 1 张 | 470dp 宽下 Adaptive(280) 只能排 1 列 | 中 |
| L8 | 设置页是一根长条 | `SourceManagementPage` 是 `Column.verticalScroll`，竖着堆 6 张卡（`:572-624`）。来源行约 53dp，共约 29 个来源，总长约 1500dp | 中 |
| L9 | 系统栏是浅色的，夹在深色界面中间 | `Res/values/styles.xml` 继承 `Theme.Material.Light.NoActionBar`，写死 `#F6F8FA`，`windowLightStatusBar=true`；`OpenSaveTheme.kt:27` 跟随系统深色；`MainActivity.onCreate`（`:93-100`）没有调用 `enableEdgeToEdge()`；targetSdk=35 | 低 |
| L10 | UI 全在一个 1399 行的文件里，没有预览和 Compose UI 测试 | `MainActivity.kt` 包含外壳、两个页面、9 张卡和 2 个对话框；代码里 `@Preview` 有 0 个；`androidTest` 只有 `AppSmokeTest.kt` | 低（但影响改造效率） |

### 2.2 手柄操作

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| I1 | 没有任何焦点或按键代码 | 在 `A/` 下 grep `focusable / onKeyEvent / onPreviewKeyEvent / FocusRequester / BackHandler / KEYCODE_ / dispatchKeyEvent`，结果为 0。现在还能用，靠的是 Compose 默认的二维焦点搜索，加上 AOSP Generic.kcm 的回退：A→DPAD_CENTER，B→BACK；按 Generic.kcm，X/Start 也回退成 DPAD_CENTER，Y→BACK，Select→MENU，以实测为准 | 高 |
| I2 | 看不到焦点 | 只有 M3 默认约 10% 不透明度的状态层，深色主题下几乎看不见。PC 端对照：`FE/app.css:653-662` 在 pad 模式下显示 2px 焦点环 | 高 |
| I3 | 一张卡有 4 个焦点，90 款游戏约 360 个 | `Card(modifier=…)`（`:1083`）不可聚焦；焦点依次落在 Checkbox `:1287-1291`、展开 `:1292-1297`、详情 `:1154`、备份 `:1159`。bringIntoView 只把小控件滚进视口，卡片会被裁掉 | 高 |
| I4 | 底栏和刷新按钮与列表隔着 90 张卡 | NavigationBar `:206-221` 和刷新 `:192-203` 都在网格外，lazy 的 beyond-bounds 搜索要先走完整个网格 | 高 |
| I5 | 没有返回栈；按返回会直接离开应用，正在进行的操作可能断在中途 | 没有 BackHandler；页面只是 `state.page` 枚举（`SourceViewModel.kt:217`）。Android 12 及以上在根 Activity 按返回是把任务移到后台，不会 finish，ViewModel 还在。真正的风险是：回到桌面后进程可能被杀，正在跑的恢复（`:937-964`）或批量（`:549` 的 `viewModelScope.launch`）会中断。另外，一旦加了竖屏全屏详情页，没有 BackHandler 就无法返回列表 | 高（体验，加上 B1 的数据安全） |
| I6 | 没有初始焦点，busy 结束后焦点也不恢复 | 所有按钮都是 `enabled=!state.busy`（如 `:318, :402, :534, :586, :590, :1163`）。批量期间焦点被清掉，之后从不可预期的位置重新开始；而且此时没有任何节点有焦点，内容树里的按键处理收不到事件 | 高 |
| I7 | 文本框会把 D-pad 用户拖进输入法；同步密钥明文显示 | `GameSaveCard` 的地址框在 `:803-810`，密钥框在 `:812-818`（截图 02 明文显示），Root 路径框在 `:651-658`；都没有 `KeyboardOptions`/`imeAction`，横屏时会弹全屏输入法。Odin2 没有实体键盘，手动填地址和密钥很费劲 | 中 |
| I8 | 来源行只有 Checkbox 能聚焦；"全选/全不选"在列表顶部 | `:751-777`、`:742-749` | 中 |
| I9 | 对话框没有初始焦点，长文本没法滚动 | 备份确认对话框 `:112-152` 的滚动区里只有不可聚焦的 Text（`:128-134`）；恢复对话框 `:154-177` 的默认焦点不在"取消"；`ReportCard` `:1007-1055` 用 D-pad 到不了 | 中 |
| I10 | Manifest 缺少相关声明 | 没有 `uses-feature`、`configChanges`、`windowSoftInputMode`；手柄插拔或切模式会触发 keyboard/navigation 配置变化，导致 Activity 重建 | 低 |

### 2.3 流程与逻辑

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| P1 | 首次启动没有引导：直接弹 su，然后扫描全部预设 | `SourceViewModel.kt:109-112, 207-214, 337`。来源数 = 24 个预设（`SourceSelection.kt:35-60`）+ 每个已安装游戏 2 个（`InstalledGameCatalog.kt:76-91`）+ 每个候选应用 2 个（`:98-116`）。空状态文案是固定的（`MainActivity.kt:421-426`）。Worker 在后台调用 su 时 Magisk 弹不出授权框，所以必须引导用户选"永久授权" | 高 |
| P2 | 不存在的来源仍然勾选；每勾选一项都全量重扫 | `selected = idSeed !in disabledSourceIds && (enabledByDefault \|\| idSeed in explicitlyEnabledSourceIds)`（`SourceSelection.kt:112-113`），扫描之后才标记"目录不存在"（`SourceViewModel.kt:407-415`）。截图 01 中 Yuzu、Yuzu EA、Suyu、Sudachi 仍是勾选状态。每次勾选都会触发重扫（`:463-473`），用手柄连按会扫很多次。`enableFullDeviceDiscoveryOnce` 会清掉用户之前的停用设置（`SourceSelection.kt:175-189`） | 高 |
| P3 | "系统授权/Root 路径"分段按钮看上去是全局模式，实际只决定整来源快照的目标 | `selectStorageMode` 只改 `storageMode`/`activeSourceId`（`:219-239`），扫描不受它影响（`:336-341`, `:1311-1322`）；`requiredSource`（`:1249-1272`）依赖这个隐藏状态 | 高 |
| P4 | "把工作区写回来源目录"没有确认，不留安全副本，目标可能是还没保存的输入 | `MainActivity.kt:596-599` 直接调用 `restoreSource`；`SourceViewModel.kt:488-497` 绕过 `pendingRestore`；`RootStorage.kt:261-287` 用 `mv` 替换后再 `rm -rf`。Root 模式下 `requiredSource` 用的是还没持久化的 `state.rootPath`（`:241`）。SAF 和 Root 共用工作区 `source-workspace`（`SafStorage.kt:466`, `RootStorage.kt:346`） | 高（数据安全） |
| P5 | 计数和术语混乱："款"实际数的是"来源×游戏"条目 | `:292` 写"个存档条目 · 款默认勾选"，`:528` 写"已选 x/y 款"，而 key 是 `sourceId::gameKey`（`GameLibrary.kt:39-40`）；另外还有"候选/已识别/PC 容器"等说法（`:126-147, :364-377, :1133`） | 高 |
| P6 | 云端记录要到设置页手动加载，卡片上的"云端 0"会误导 | `remoteSnapshots` 只在 `listCloud`（`:801-818`）和 `checkCloudUpdates`（`:821-873`）里赋值；卡片提示用户去设置页列举（`MainActivity.kt:1220-1226`） | 中 |
| P7 | 本地/云端历史和跨设备更新分散在三个地方，冲突时只能"恢复" | 卡片内 `:1170-1228`、设置页底部 `:619-620, :936-1004`、`CloudUpdateCard` `:867-933`（只存在内存里）；`REVIEW_REQUIRED` 和 `AUTO_RESTORE` 都只有一个恢复图标（`:921-928`）；恢复确认框只有通用文字（`:154-177`） | 高 |
| P8 | 自动备份开关分在两张卡里，存在隐式依赖；后台结果用户看不到 | "Wi-Fi 自动备份"在 `:605-609`，自动上传在 `:824-825`（点"保存"后才持久化，`SourceViewModel.kt:284-304`）；云端没启用时 Worker 什么都不做（`AutoBackup.kt:91-92`）；Deferred/UploadFailed 的结果被丢掉（`:131-136`）；原生协议只会推迟（`:224-232`），旧 head 协议却会在后台直接覆盖（`:253-269`），两套行为不一致 | 高 |
| P9 | 状态和错误只有一个全局通道，会在页面间串扰；配置口径不一致 | `action`/`error` 都是单字段（`:74-75`），设置页的"连接成功"会出现在游戏库（截图 03）；批量失败信息拼成 6 行塞进 error（`:591`）；刷新会用已保存的配置覆盖草稿（`:197-200`）。"测试连接"（`:307`）和"检查跨设备更新"（`:823`，`gameSaveConfigFromState()`）用的是草稿，备份用的是已保存的配置（`:678`） | 中 |
| P10 | 三条备份路径的范围和输入各不相同，导致 manifestHash 不同，产生重复快照 | **单游戏备份**：包含 `android-app` 位置（`:598`），传入的是用 Steam 元数据补全过 presentation 的游戏（`MainActivity.kt:1161`）。**批量备份**：通过 `batchBackupLocations` 排除 `android-app`（`GameLibrary.kt:93-94`），用的是没补全的 `game`（`SourceViewModel.kt:563`）。**自动备份**：`eligibleGames` 排除 `android-app`，还额外排除了 `relativePath == "."`（`AutoBackup.kt:56-60`）。另外，清单里的 `gameName`（`GameSnapshotManifest.kt:23,48`）会进入指纹（`AutoBackup.kt:77`），名字不同哈希就不同 | 中 |
| P11 | 没有 PC 端那种"跟踪"模型 | PC 是"扫描结果→跟踪→游戏库"（zh-CN.json 中的 `home.scan.track/exclude`）；Android 扫描到就直接入库，并默认勾选（`MainActivity.kt:247-251`） | 中 |

### 2.4 批量操作

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| B1 | 只有一个批量动作；不能取消，没有持久报告；进程被杀就会中断 | `requestBatchGameBackup`（`SourceViewModel.kt:539-594`）在 `viewModelScope.launch`（`:549`）里顺序执行，Job 没有保存下来；进度只是文本（`:559`）；汇总塞进 error，只取前 6 条（`:586-592`）。注意：现在**已经是逐项加锁**（`:571-573`，`withLock` 在 `forEachIndexed` 循环里）。没有 FOREGROUND_SERVICE/POST_NOTIFICATIONS 权限 | 高 |
| B2 | 默认全选；每次扫描或旋转都重置选择 | `remember(state.scanRevision, batchEligibleKeys){ … toSet() }`（`MainActivity.kt:248-251`），截图 03 显示 87/87；`selectedTypeId` 同样用 `remember`（`:259`）；`scanRevision` 每次 +1（`SourceViewModel.kt:456`） | 高 |
| B3 | 手动和批量备份不去重 | `captureGameSnapshotLocked`（`:632-750`）每次都生成新 ZIP；`AutoBackupWorker` 会复用 manifestHash 相同的记录（`AutoBackup.kt:283-293`），整来源模式也会去重（`:1157-1176`）。但即使把去重逻辑搬过来，因为 P10 的差异（gameName、位置集合），三条路径之间也很难命中 | 高 |
| B4 | 没有删除和保留策略；历史全局只取 40 条，计数失真 | `SnapshotRepository.kt:24-121` 只有 list/add/markUploaded；`currentSnapshots()` 用 `take(MAX_HISTORY=40)`（约 `:1293-1296, :1356`）；`remote-*.zip` 缓存从不清理；`GameSaveClient.delete`（`:169-174`）实现了但没有调用方 | 高 |
| B5 | "检查跨设备更新"只能全量跑，整段持锁，结果只显示在设置页 | `:821-873` 不管当前勾选，全部在 `StorageOperationLock.withLock`（`:838-854`）里跑；每款都要复制并计算哈希（`:761-799`）；`CloudUpdateCard` 最多显示 16 条 | 中 |
| B6 | 没有批量上传、批量恢复、批量下载 | 上传失败的快照没有补传入口（`:697-709`）；`pendingRestore` 同时只能有一个（`:91`, `:931-966`） | 高 |
| B7 | 自动备份只有全局开关 | `AutoBackupScheduler.ENABLED_KEY`（`AutoBackup.kt:18-27`）；Worker 处理全部游戏（`:117`）。PC 端对照：`Go/store/games.go:35` 中的 `AutoSync` | 中 |
| B8 | 没有每款游戏的同步状态，所以没法按状态筛选 | 卡片只显示"本地 N · 云端 N"（`MainActivity.kt:1146-1153`）；需要的数据其实都已存在（SnapshotRecord、`GameSaveHeadStore`、cloudUpdates、sourceAccess） | 高 |
| B9 | 自动备份 Worker 整次运行都持全局锁，有被系统中途停掉的风险，UI 也没有提示 | `StorageOperationLock.kt:7-11` 是进程内 Mutex；Worker 整次运行持锁（`AutoBackup.kt:88`），UI 卡在"批量备份 1/N"。Worker 没有前台服务，处理约 90 款时可能超过约 10 分钟的执行上限，被系统停止，而且是在持锁中途被取消。这个风险**现在就存在** | 中 |
| B10 | 每张卡的大号备份按钮喧宾夺主，批量工具条又会被滚走 | `:1159-1168`、`:398-414` | 中 |
| B11 | 快照索引写入不是原子的，存在两个实例并发写 | `writeRecords`（`SnapshotRepository.kt:60-72`）先 `delete()` 旧索引再 `renameTo`，进程在两步之间被杀，整个索引就丢了。ViewModel（`SourceViewModel.kt:102`）和 Worker（`AutoBackup.kt:97`）各自 new 一个实例，`@Synchronized` 只锁实例本身 | 高（数据安全） |
| B12 | Root 命令的超时和中断残留 | `RootCommand.execute` 每条命令单独起一个 `su -mm -c` 进程，超时 60 秒（`RootStorage.kt:16-42`），大存档的 `cp -a` 可能超时；`destroyForcibly` 只杀 su 客户端，Magisk 那边的 shell 可能还在执行，随后清理 prepared 的 `rm -rf`（`:229-235`）会和它竞争。进程被杀时，模拟器目录里可能残留 `.opensave-game-new-*` 或 `.opensave-game-old-*`（`:177-178`）。脚本只做了 chown/chmod（`:199-200`），没有处理 SELinux 上下文 | 中 |

### 2.5 与 PC 端的一致性

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| C1 | Switch 命名空间不一致：PC 大多写入 `pc`，Android 写入 `switch` | PC：`Go/daemon/daemon.go:762-767` 只改了 gameId，没设 SaveType；`Go/store/games.go:90-92` 默认值是 `pc`；`migrations/0038_game_save_type.sql:3`；`FE/views/home/ScanDialog.svelte:42,198` 默认 `'pc'`；`AddGameCard.svelte:97-104`；retrack、offered、import、CLI 入口（`daemon.go:1317`、`routes_offered_games.go:69-75`、`routes_backup.go:843-845`、`cliapp.go:621`）。Android：`GameSaveTypes.kt:69, 96-97, 157-158`。scope 由类型决定（`Go/cloud/game_save_scopes.go:66-69`、`GameSaveClient.kt:348-349`）。结论：**同一个 TitleID 跨端同步不通** | 高（单独立项） |
| C2 | Android 上非 Switch 游戏的 gameId 把来源路径算了进去 | 非 desktopCompatible 时 gameId = `android-<family>-` + `sha256("$sourceId\|${game.gameKey}")` 的前 16 位（`GameSaveTypes.kt:157-166`），sourceId 来自路径或 SAF URI。后果：两台 Android 设备之间也不能互通；同一款游戏的来源路径一变（例如 Eden 内部数据和外部数据），gameId 也跟着变。`portableSource` 只认 Root 来源（`GameSaveTypes.kt:140-141`），所以 SAF 来源的 Switch 存档也不会是 desktopCompatible。PC 用的是名称 slug（`SlugifyGameID`，`games.go:20-24`），前端只有 eden/yuzu/ryujinx/generic 几种类型。多位置归档的布局也不同（`.opensave-locations/` 对 `location-NNNN/`，见 `zip_roots.go:26`、`GameSnapshotManifest.kt:42,70`） | 高 |
| C3 | 同一 TitleID 出现在多个 Android 来源时，会共用一份 head 状态 | 状态键是 `saveType+gameId`（`GameSaveHeadSync.kt:444-445`），会互相判定为 CHANGED 或冲突（`GameSaveNativeSync.kt:123-127, 225-229`）；PC 遇到这种情况会明确拒绝（`Go/p2p/switchmatch.go:45-52`） | 中 |
| C4 | 自动拉取不对称：PC 默认自动恢复，Android 从不自动恢复 | PC：`0026_cloud_heads.sql:34`、`Go/daemon/game_save_native.go:560-575`、`cloudsync.go:501-516`；Android：`AutoBackup.kt:225-231`、`GameSaveNativeSync.kt:144` | 中 |
| C5 | 冲突副本：PC 能看到但没法解决，Android 根本不显示 | PC：`Go/daemon/game_save_native.go:374-381`、`CloudTab.svelte:251-267`，没有 resolve 路由；Android：`GameSaveClient.kt:268` 解析了冲突，但 UI 没用 | 中 |
| C6 | 保留策略：PC 有自动 20 份、手动、固定三类，Android 从不清理 | `daemon.go:796-806`、`store/snapshots.go:20-23,132`；`SnapshotRepository.kt` | 中 |
| C7 | 术语和中文化：PC 很多界面还是英文，两端叫法不同 | `Sidebar.svelte:40-45`、`GameDetail.svelte:25-28`、`cloudproviders.js:27-44`；zh-CN.json 与 Android 的说法对比见 §3.2 | 中 |
| C8 | 批量和手柄模型：PC 有选择模式和 controller.js，Android 都没有 | `FE/views/home/LibraryGrid.svelte:133-226`、`FE/lib/controller.js`、`spatial.js`。PC 读取了 `back`（Select）键（`controller.js:30, :109`），但没有分支处理 | 中 |
| C9 | 游戏名来源和指纹：Android 只有 13 条硬编码名称，而且指纹里包含游戏名 | `GameCatalog.kt:166-180`；`AutoBackupPolicy.fingerprint`（`AutoBackup.kt:62-79`）在 `:77` 把 `opensave-game.json` 也算进去，清单里有 `gameName`（`GameSnapshotManifest.kt:23`）。`manifestHash` 同时是 head 状态的比较依据（`GameSaveNativeSync.kt:124-125`、`GameSaveHeadSync.kt:359-360`），不能直接改算法。PC 读模拟器自己的缓存（`Go/presets/switchtitles.go:17-40`） | 中（会阻塞去重） |
| C10 | 设备名、URL 规范化、分支有细节差异；两边实现没有共享的测试数据 | `GameSaveHeadSync.kt:528`（Build.MODEL）；`GameSaveClient.kt:386-388` 对比 `provider_game_save.go:53-57`；branch 固定为 main（`GameSaveNativeSync.kt:69,103`）；`mobilecore/mobilecore.go:16-98` 没被 Android 用上 | 低 |

---

## 3. 目标信息架构

### 3.1 页面与导航

```
一级导航（横屏 NavigationRail / 竖屏 NavigationBar；手柄 L1/R1 循环切换）
├─ 游戏库   已跟踪游戏；类型筛选 chip、排序；"备份有变更 (N)"常驻按钮；选择模式和批量操作栏；
│          顶部横幅"3 款云端有新版本 · 1 个冲突 · 2 个上传失败 ▸"（A 进入后自动按这些状态筛选）；
│          "新发现 N 款"横幅
├─ 来源     Root/SAF 状态 chip；横屏双栏：左边分组（可用 / 需授权 / 不存在（折叠，禁止勾选）/ 候选），右边行；
│          添加 SAF 目录 / 添加 Root 路径；改动先暂存，统一"应用并重新扫描"
└─ 设置     game-save 账户（摘要行 + 编辑对话框 + 从剪贴板导入）；自动化（后台备份 + 自动上传）；手柄；
           保留策略；同步与任务 ▸；高级（整目录快照和写回，默认折叠）；关于/更新

二级页
├─ 游戏详情     横屏：右栏；竖屏：全屏页（B/返回键回列表）
│              头部（封面、名称、来源、访问方式、同步状态徽标）+ 主操作 [备份] [恢复…]
│              两个标签：版本（本地和云端合并成一条时间线，标出设备、时间、是否安全快照、是否冲突副本）| 存档位置（包含/排除）
│              对应 PC GameDetail.svelte:24-30 的 Snapshots+Cloud / Configuration
└─ 同步与任务   连接状态；待处理项（云端有新版本 / 冲突 / 上传失败 / 等待远端），每项三个选项；
               刷新云端；最近 20 份批量报告；动态日志
```

分阶段落地：

- 阶段 1–2：在现有的两个页面上加自适应外壳、详情面板和返回栈。
- 阶段 4：把"备份与设置"拆成 来源 / 设置，再加"同步与任务"二级页。外壳只改一次。

### 3.2 术语表（Android strings 和 PC `zh-CN.json` 共用）

| 统一术语 | 含义 | 替换掉的 Android 说法 | 替换掉的 PC 说法 |
|---|---|---|---|
| 游戏 | 列表里的一行。0.4.x 中仍是"来源 × 游戏"，同一游戏出现在多个来源时显示来源标签；A2 落地后再合并成一行 | 存档条目、款 | — |
| 存档位置 | 一款游戏下的一个具体目录 | 位置 | Save location |
| 来源 | 模拟器、启动器或授权目录 | 来源 | — |
| 已确认位置 / 待确认位置 | 能确定是存档 / 可能是存档，默认不备份 | 已识别 / 候选 | — |
| 版本 | 一个可恢复的存档版本（本地快照或云端归档） | 游戏存档快照 | 快照 |
| 备份 | 创建版本，并在启用时上传；内容没变就跳过 | 备份游戏存档 | Snapshot + Sync |
| 云端当前版本 | game-save scope 的 head | 云端当前存档 | 云端权威 head |
| 安全快照 | 恢复或覆盖之前自动生成的版本，可用来"撤销恢复" | 恢复前安全副本 | 安全快照 |
| 冲突副本 | 推进 head 失败时保留下来的版本 | 云端冲突副本 | 保留的冲突副本 |
| 跨设备更新 | 其他设备推上来的新版本 | 跨设备更新 | 同步 |
| 同步状态 | 已同步 / 待上传 / 云端有新版本 / 冲突 / 仅本地 / 未备份 / 离线（复用 `game.cloud.state.*`，`zh-CN.json:192-200`） | 本地 N · 云端 N | — |
| 类型 | PC 游戏 / Switch 游戏 / <模拟器名> / 其他模拟器 | 同左 | PC game / Switch / Other emulator |
| 工作区、head、PC 容器 | **不在普通用户界面出现**，只留在"高级"或日志里 | — | — |

### 3.3 自适应断点

新增 `A/ui/AdaptiveLayoutInfo.kt`：

- 在外壳最外层用 `BoxWithConstraints` 测实际可用尺寸，再通过 CompositionLocal 往下传。不用 `LocalConfiguration.screenHeightDp`：它在 Android 13 上不含系统栏，开了 `enableEdgeToEdge()` 后和实际可用高度对不上。
- 不用 `calculateWindowSizeClass`：它需要额外依赖 `material3-window-size-class`，`android/app/build.gradle.kts` 里没有。
- 删除 `MainActivity.kt:1068` 的 orientation 判断。

| 模式 | 条件 | 外壳 | 游戏库 | 设置类页面 | 主输入方式 |
|---|---|---|---|---|---|
| Compact（竖屏） | 宽 < 600dp | NavigationBar + 随滚动收起的 TopAppBar | 单列紧凑行，吸顶工具条，详情走全屏页 | 单列，分组可折叠 | **触屏**：不显示按键提示；触控目标 ≥ 48dp；长按进入选择模式，选择模式下可滑动连选 |
| HandheldLandscape（Odin2 横屏） | 宽 ≥ 600dp 且高 < 600dp | NavigationRail（80dp），没有 TopAppBar | 列表（360–400dp）+ 详情（weight 1f） | 左边分类，右边内容 | **手柄** |
| Expanded（平板/外接屏） | 宽 ≥ 840dp 且高 ≥ 600dp | NavigationRail | 列表 + 更宽的详情 | 同上 | 触屏/键鼠 |

> 竖屏按触屏设计。Odin2 竖着拿时手柄在侧边，Android 不会随屏幕旋转重新映射 D-pad，按"↓"在物理上是往侧面按。
> 断点对 835×470dp 和 960×540dp 两种实际窗口都适用。

### 3.4 线框

**横屏 · 游戏库（浏览模式，HandheldLandscape）**

```
┌────┬─────────────────────────────────────────────────────────────────────────────┐
│ OS │ [全部][PC][Switch][模拟器▾] │ 87 款 · 3 待确认 │ ⚠3 新版本·1 冲突 ▸ │[备份有变更 (12)]│ ← 固定工具条 52dp
│    ├──────────────────────────────┬──────────────────────────────────────────────┤
│ 🎮 │ 塞尔达传说 王国之泪           │ [封面] 塞尔达传说 王国之泪                      │
│游戏│ Eden·Switch  10-30 · 本地3·已传│ Eden · Switch · Root 可读写   ● 已同步       │
│ 库 │──────────────────────────────│ [ 备份 ]  [ 恢复… ]                          │
│ 📁 │▶宝可梦 朱                     │ ─ 版本 ─┬─ 存档位置 ─                       │
│来源│ Eden·Switch  10-29 · 本地2·未传│ 10-30 21:49  本地·已上传  Odin2             │
│ ⚙  │──────────────────────────────│ 10-29 18:02  云端  来自 Windows-PC  ★推荐   │
│设置│ Beholder                      │ 10-28 09:11  安全快照                       │
│    │ GameHub·PC   从未备份         │ …                                           │
│    │ … （每行 56–60dp，≥5 行）     │                                              │
│ Ⓐ打开│                              │                                              │
│ Ⓧ菜单├──────────────────────────────┴──────────────────────────────────────────────┤
│ Start│ 批量备份 12/87 · 宝可梦 朱 ▓▓▓░░░░  [取消]                    （仅运行时出现） │ ← 34dp
└────┴─────────────────────────────────────────────────────────────────────────────┘
  Rail 80dp（底部放按键提示，不进 D-pad 焦点链）   列表 360–400dp     详情 weight(1f)
```

- 阶段 1 的行副行只用现有数据：最近备份时间，加 `本地 N · 已上传/未上传`（来自 `SnapshotRecord.uploaded`）。
- 阶段 3 有了 `GameStatusResolver` 后，再换成 ● 已同步 / ▲ 待上传 / ⚠ 云端较新 这类状态徽标。

**横屏 · 选择模式**（工具条换成上下文操作栏）

```
│ ✕ 已选 5 · Ⓧ选择菜单 │ [批量备份] [上传待传] [检查更新] [安全恢复较新…]            │
│ ☑ 塞尔达传说 王国之泪   ● 已同步      │ （右栏显示所选摘要：5 款 · 2 有变更 · 1 待上传） │
│ ☐ 宝可梦 朱            ▲ 待上传      │                                                 │
```

**横屏 · 设置类页面（双栏）**

```
│ ⚙ │ game-save 云端    ▸ │ 服务地址  192.168.x.x:8788 · 已连接 ✓  [编辑…] [从剪贴板导入] [测试] │
│   │ 自动化            │ 设备名称  Odin2                             [修改…]               │
│   │ 手柄              │ 同步密钥  ••••••••                         [显示]                 │
│   │ 保留策略          │                                   （内容限宽 560dp）              │
│   │ 同步与任务 ▸      │                                                                   │
│   │ 高级（折叠）      │                                                                   │
```

**竖屏 · 游戏库 / 详情 / 多选（触屏优先）**

```
┌──────────────────────────┐   ┌──────────────────────────┐   ┌──────────────────────────┐
│ OpenSave        ⟳  ⋮     │收起│ ← 塞尔达传说 王国之泪     │   │ ✕ 已选 3        全选     │
│[全部][PC][Switch][模拟器]│吸顶│ [封面]  Eden · Switch     │   │ ☑ 塞尔达…   ● 已同步     │
│ ⚠ 3 新版本 ▸  [备份有变更]│   │ ● 已同步   Root 可读写     │   │ ☑ 宝可梦…   ▲ 待上传     │
│ 塞尔达…    ● 已同步      │   │ [  备份  ] [  恢复…  ]    │   │ ☐ Beholder  ○ 未备份     │
│ 宝可梦…    ▲ 待上传      │   │ 版本 | 存档位置            │   │ …（长按进入，滑动连选）  │
│ Beholder   ○ 未备份      │   │ 10-30 21:49 本地·已上传    │   ├──────────────────────────┤
│ …（≥7 行）               │   │ …                          │   │ [批量备份][上传][更多▾]  │ ← 上下文 BottomAppBar
├──────────────────────────┤   └──────────────────────────┘   └──────────────────────────┘
│  游戏库    来源    设置  │     全屏详情页（返回键回列表）     多选时替换 NavigationBar
└──────────────────────────┘
```

---

## 4. 手柄按键映射与焦点规则（横屏）

### 4.1 映射表（按键语义沿用 PC `FE/lib/controller.js:29, 114-155`，再按 Android 补充）

| 按键 | 由谁处理 | 浏览模式 | 选择模式 | 对话框 / 面板 | 与 PC 是否一致 |
|---|---|---|---|---|---|
| D-pad / 左摇杆 | 系统和 Compose（**不拦截**） | 移动焦点。左摇杆和 HAT 由 ViewRootImpl 合成为 DPAD 键，连发节奏由系统决定，只在列表层做节流 | 同左；按住 Select 再按 ↑/↓ 可连续扩展选区 | 同左 | 语义一致；PC 的 380/110ms 连发不照搬 |
| A | 系统和 Compose（**不拦截**，靠 Generic.kcm 回退成 DPAD_CENTER） | 在列表行上：把焦点移到右栏主操作 [备份]（竖屏则进全屏详情）；在按钮上：激活 | 切换当前项的勾选 | 确认当前按钮 | 一致 |
| B | 系统（**不拦截**，回退成 BACK），由 BackHandler 接住 | 依次：关浮层 → 从右栏回到原来那一行 → 退出详情 → 回游戏库 → 再按一次退到后台（提示"再按一次返回退出"） | 退出选择模式（选择集保留，可撤销） | 取消 | 一致 |
| X | Activity 层分发 | 当前游戏的操作菜单：备份 / 恢复… / 关联云端游戏 / 自动备份开关 | **选择菜单**（默认焦点在"全选当前筛选"）：全选当前筛选 / 反选 / 按状态选 / 清空 | — | 一致（PC 用右键菜单） |
| Y | Activity 层分发 | 命令面板：扫描、检查云端更新、刷新云端、按状态筛选、排序（名称/最近备份）、搜索、进入选择 | 批量操作面板 | — | 一致（PC 的命令面板） |
| Start | Activity 层分发 | **备份有变更**（直接开始，显示可取消的进度） | 对所选执行批量备份 | — | Android 新增 |
| Select | Activity 层分发 | 进入选择模式并勾选当前项 | 退出选择模式；按住加 D-pad 可扩展选区 | — | Android 新增（PC 读了 back 键但没有处理） |
| L1 / R1 | Activity 层分发 | 切换一级页面（循环，共 3 个） | 同左（选择集保留） | 详情里切换标签 | 一致（LB/RB） |
| L2 / R2 | Activity 层分发 | 列表整屏上翻 / 下翻 | 同左 | 长文本整屏翻页 | Android 新增 |
| 右摇杆 | `dispatchGenericMotionEvent` | 滚动详情 / 报告 / 对话框里的长文本 | 同左 | 同左 | Android 新增 |

- **快速备份当前游戏**：在列表行上按 A（焦点移到右栏 [备份]），再按 A。两次按键，不需要改 X/Y 的语义（原 Q4 关闭）。
- **双栏联动**：在列表里移动焦点，右栏就跟着切到当前游戏。加约 150ms 防抖，只读本地数据，不发网络请求。
- **按键提示**：常驻在 Rail 底部（或工具条右侧），内容随当前模式变化。
- **设置项**："手柄导航：自动 / 开 / 关"，对齐 PC 的 `CONTROLLER_MODES`。
- **按键图标风格（Xbox / Nintendo）**：只改提示里显示的图标，**不改实际映射**，默认跟随"自动"。Odin2 系统已有布局切换，App 内再换映射会造成双重互换。
- **振动反馈**：批量完成或失败时短振；危险确认框出现时振一下。

### 4.2 实现要点（新增 `A/ui/input/HandheldInput.kt`、`A/ui/input/HandheldFocus.kt`）

1. **全局键在 Activity 层分发**：在 `MainActivity` 重写 `dispatchKeyEvent`（现在只有 `onCreate`，`:93-100`）。只拦截 X/Y/Start/Select/L1/R1/L2/R2，并且 **Down 和 Up 都要消费**，否则会触发 kcm 回退：按 Generic.kcm，X/Start 会回退成 DPAD_CENTER 误点当前按钮，Y 会回退成 BACK，Select 会回退成 MENU。具体以阶段 0 的实测为准。拦下来的键发到 `SharedFlow<HandheldCommand>`，由 `OpenSaveScreen` 按当前页面和模式消费。
   - 不挂在 Compose 根节点：没有任何节点有焦点时（刚从触屏切到手柄，或 busy 期间焦点被清掉），内容树里的 `onPreviewKeyEvent` 收不到事件。
   - **A、B 和方向键一律放行**：Compose 1.7 的 `clickable` 只认 DPAD_CENTER/ENTER/NUMPAD_ENTER；A 能用，靠的是没被消费时 Generic.kcm 生成的回退键。B 回退成 BACK 后走 `OnBackPressedDispatcher`。返回逻辑只有 `BackHandler` 这一条链。
   - 只处理非方向键 `repeatCount == 0` 的事件。文本框有焦点时全部放行，等同于 PC `shortcuts.js:36-40` 的 isTyping。
2. **右摇杆**：重写 `dispatchGenericMotionEvent`，读 `AXIS_Z/AXIS_RZ`（轴号要实机确认），和按键走同一个 SharedFlow，交给当前活动的 `ScrollState`/`LazyListState` 去 `scrollBy`。如果 L2/R2 同时上报 `AXIS_LTRIGGER/RTRIGGER`，需要去抖。
3. **输入模式判断**：阶段 0 先用最小 Demo 实测，Compose 收到手柄按键后 `LocalInputModeManager.current.inputMode` 会不会切到 `Keyboard`。如果不会，自己维护"最近一次输入来自 `SOURCE_GAMEPAD`/`SOURCE_DPAD`"的状态，对齐 PC `controller.js` 的 `padUsed`。初始焦点（R3）和焦点环都依赖这个判断。
4. **焦点环**：`Modifier.handheldFocusRing(shape)`，只在手柄模式下显示。3dp primary 描边，加 surfaceVariant 提亮，可选 1.03 倍放大。等同于 PC 的 `data-input='pad'`。深色主题单独调一个高对比色。

### 4.3 焦点规则

| 规则 | 做法 | 对应问题 |
|---|---|---|
| R1 每个列表项只有一个焦点 | 游戏行用 `ListItem`/`Card(onClick)`；来源行用 `Modifier.toggleable(role=Checkbox)`；行内次要控件在手柄模式下用 `focusProperties { canFocus = false }` 移出焦点链，功能并入 X 菜单或详情 | I3、I8 |
| R2 分区 | 工具条、列表、详情三个区域各自包 `focusGroup()` 和 `focusRestorer`；←/→ 在列表和详情之间切换，↑ 从列表第一行上到工具条；**Rail 在手柄模式下 `canFocus=false`**，页面只用 L1/R1 切 | I4 |
| R3 初始焦点 | 每页一个 `FocusRequester`：游戏库是第一行（空库时是"扫描"），设置是第一个分类。进入手柄模式时调用 `requestFocus()`（判断依据见 §4.2 第 3 点） | I6 |
| R4 busy 时不禁用 | 去掉大面积的 `enabled = !state.busy`，改为点击时检查冲突并提示"正在批量备份，稍后再试"，焦点不会丢。结束后按 `lastFocusedKey` 恢复焦点 | I6、B 系列 |
| R5 返回栈 | `BackHandler` 按优先级叠加（§4.1 B 键那一行），**阶段 1 就要做**；manifest 加 `android:enableOnBackInvokedCallback="true"` | I5 |
| R6 对话框 | 破坏性对话框（恢复、写回、删除）初始焦点在"取消"；普通确认框在主操作上；长内容区可聚焦，D-pad 或右摇杆可以滚动。命令面板和菜单用 `BasicAlertDialog`/`Dialog` 加 FocusRequester，**不用 `ModalBottomSheet`**：M3 1.3 的底部弹层在 D-pad 下初始焦点和焦点困住的问题不稳定 | I9 |
| R7 文本框 | 连接配置平时只显示摘要行，A 打开编辑对话框；`KeyboardOptions(imeAction=Next/Done)`；密钥用 `PasswordVisualTransformation` 加显示切换；Root 路径提供预设列表；优先用"从剪贴板导入 `opensave://` 配置串"（见 A15） | I7 |
| R8 最小目标尺寸 | 所有可交互控件 ≥ 48dp，去掉 `Modifier.size(32.dp)`（`MainActivity.kt:304-313`） | — |
| R9 滚动可见 | 焦点移动时把整行滚进视口，并留出 1 行余量（BringIntoViewSpec），不能裁掉 | I3 |

---

## 5. 批量操作设计

### 5.1 选择模型

- **key 先定下来（阶段 0 决定，阶段 1 落地）**：0.4.x 内部继续用 `libraryKey = sourceId::gameKey`（`GameLibrary.kt:39-40`）作为选择集、自动备份排除表和报告的 key，不合并卡片。合并成"一款游戏多个存档位置"要等 A2 身份规则落地，届时通过一张 key 映射表一次性迁移。这样 SavedStateHandle 和排除表只需要迁移一次。
- **状态位置**：把 `selectedGameKeys`（`MainActivity.kt:249`）、`selectedTypeId`（`:259`）、`sourceDetailsExpanded`（`:267`）、卡片 `expanded`（`:1070`）移到 `SourceViewModel`。新增 `selection: Set<libraryKey>`、`selectionMode: Boolean`、`filter: LibraryFilter(type, sourceId, status)`、`selectedGameKey`（详情面板的唯一数据来源），用 `SavedStateHandle` 持久化。
- **默认不选**（阶段 0 就改）。进入选择模式的方式：Select 键、长按、工具条上的"选择"。
- **重新扫描不重置**：`selection = selection ∩ 新的 eligibleKeys`。
- **快捷选择**：在选择模式下按 X 打开选择菜单，包括 全选当前筛选 / 反选 / 清空 / 按状态选（有变更 / 从未备份 / 待上传 / 云端较新 / 冲突 / 自动备份已关）。按住 Select 再按 D-pad 可以连续扩展选区。
- **不可选的项要显示原因**，例如"仅有待确认位置"、"只读来源"。
- **"备份有变更 (N)"** 常驻在工具条上（Start 键直达），是最常用的一键操作，不需要先进选择模式。
- 头部从"87 款默认勾选"改为"已选 X · 有变更 Y · 待上传 Z"。

### 5.2 状态模型

**阶段 1：先用现有数据**。行上显示最近备份时间，以及 `本地 N · 已上传/未上传`。阶段 1/2 加"启动后在后台刷新云端"（配置了云端时执行，节流 5 分钟，只读 list），先让"云端 0"这类误导消失。

**阶段 3：新增纯函数** `A/library/GameStatusResolver.kt`：`resolve(entry, records, headState, cloudUpdate, lastSignature) → GameSyncStatus`。输出：未备份 / 有变更 / 待上传 / 已同步 / 云端较新（可安全恢复）/ 冲突需确认 / 远端未就绪 / 只读 / 不可访问。

- 数据源都已存在：`SnapshotRecord.uploaded/manifestHash`（`SnapshotRepository.kt:15`）、`GameSaveHeadStore`（`GameSaveHeadSync.kt:383-441`）、`cloudUpdates`、`sourceAccess`。
- **"有变更"只用廉价签名做提示，最终是否去重以完整内容哈希为准**：
  - Root：优先用 `su -c "find … -printf '%s %T@ %p\n'"`。Android 13 的 toybox `find` 不一定支持 `-printf`，要实机验证，不支持就退回 `stat -c '%s %Y %n'`。
  - SAF：不要逐个调用 `DocumentFile.length/lastModified`（每次都是一次 ContentResolver 查询），改用 `DocumentsContract.buildChildDocumentsUriUsingTree` 一次查询，只取 `COLUMN_SIZE/COLUMN_LAST_MODIFIED/COLUMN_DOCUMENT_ID`。
- 单元测试仿照现有的 GameLibraryTest 写。

### 5.3 去重的前提（阶段 1 单独先做）

去重要提前做，但要先解决 P10/C9。否则即使搬过 `AutoBackup.kt:283-293` 的复用逻辑，也很难命中。

1. **统一三条备份路径的输入**：抽出 `GameLibrary.backupLocations(game, mode)`，单游戏、批量、自动三条路径共用同一个位置集合（是否包含 `android-app` 和 `relativePath == "."` 作为显式参数，默认值三者一致）。传给清单的 presentation 也统一：要么都补全，要么都不补全。
2. **新增 `contentHash`（v2）专门用于去重**：只对存档数据文件计算哈希，**排除 `opensave-game.json`**。`SnapshotRecord` 增加 `contentHash` 和 `hashVersion` 字段，旧记录在首次需要时重算或惰性补齐。**`manifestHash` 保持原算法不变**，因为它同时是 head 状态的比较依据（`GameSaveNativeSync.kt:124-125`、`GameSaveHeadSync.kt:359-360`），直接改会让所有游戏变成 UNKNOWN/CHANGED，可能被判成需要确认。
3. **把 `AutoBackup.kt:283-293` 提取成 `findReusableRecord(sourceId, gameKey, contentHash)`**。命中时结果为 `SKIPPED("无变化")`；如果命中的记录还没上传，只补上传。"强制新建版本"是一个显式选项。
4. **head 协议的哈希什么时候换**：放在阶段 5 跟进，带版本号，并对 `GameSaveHeadStore` 里的旧状态做一次重算迁移。

### 5.4 批量操作清单

首批（阶段 3）做以下 4 种，加每游戏的自动备份开关：

| 操作 | 作用范围 | 危险级别 | 确认方式 | 复用或改造的现有代码 |
|---|---|---|---|---|
| 备份所选 / 备份有变更 | 所选，或"有变更"的全部 | 低（有去重，可重跑） | **不弹确认**，只显示可取消的进度 | `captureGameSnapshotLocked`（`SourceViewModel.kt:632-750`），加 §5.3 的去重 |
| 上传待上传版本 | `uploaded=false` 的记录 | 中（会推进 head） | 一次计划确认 | 只有"最新且等于当前内容"的那条走 `native.publishSnapshot`（`GameSaveNativeSync.kt:185`），旧记录只做归档上传或跳过 |
| 检查云端更新 | 所选或当前筛选（不再固定全量） | 无 | 不需要 | `checkCloudUpdates`（`:821-873`），改为逐项加锁、显示 i/N、使用已保存的配置 |
| 安全恢复云端较新 | `AUTO_RESTORE` 的项 | 高 | 恢复计划页，一次确认（默认焦点在"取消"）；`REVIEW_REQUIRED` 不进入批量，只能在"同步与任务"里逐个处理；`WAIT_FOR_REMOTE` 和只读来源自动跳过；完成后报告里每项都有"撤销恢复" | `restoreGameRequestLocked`（`:976-1114`，自带安全快照） |
| 启用/停用自动备份 | 所选 | 低 | 可以撤销 | 新增 `autoBackupExcludedGameKeys`，放在 `SourceSelectionStore`（`SourceSelection.kt:139-175`）旁边；在 `AutoBackup.kt:117` 处过滤 |

延后（0.4.x 后续或 0.5）：

| 操作 | 说明 |
|---|---|
| 恢复本地最新 / 恢复到某时间点（批量） | 与安全恢复共用计划页 |
| 下载云端最新到本机 | `GameSaveClient.download`（`:143-167`）。注意 Switch desktopCompatible 归档是以 saveRoot 为根打包的，不含清单（`SourceViewModel.kt:655-658`）。登记成 `SnapshotRecord` 之前，必须按本地同样的方式解包并重算 `contentHash`/`manifestHash`，否则状态会一直显示"有变更" |
| 清理旧版本 | 先给 PrunePlan 预览（数量和可释放空间）；新增 `SnapshotRepository.removeAll(ids)` |

### 5.5 执行引擎

```
GameOperations（新增 A/ops/GameOperations.kt）
  capture / restore / inspect / uploadPending / download / prune
  ← 从 SourceViewModel 的私有 *Locked 函数和 AutoBackupWorker 里重复的
     createSnapshot / remoteCacheFile / saveSafetySnapshot（AutoBackup.kt:380-456）抽出来
        ▲                    ▲                       ▲
 SourceViewModel     AutoBackupWorker        BatchWorker（新增，含网络操作时才用）
                                                     │
                                      BatchReportStore（新增，最近 20 份报告）
```

1. **锁**：`StorageOperationLock` 保持全局 Mutex。原因是单游戏工作区 `GAME_WORKSPACE_DIRECTORY`（`SourceViewModel.kt:1355`，在 `:641` 使用）和 `AUTO_GAME_WORKSPACE_DIRECTORY`（`AutoBackup.kt:506`）都是固定目录，只要还共用这两个目录，就不能细分锁。批量备份已经是逐项加锁（`:571-573`），需要改的是 `checkCloudUpdates`（`:838-854`）和 `AutoBackupWorker`（`AutoBackup.kt:88`）的整段持锁，改为每款游戏单独 `withLock`。
   - **Mutex 不可重入**：规定 `*Locked` 函数内部绝不再次加锁，只由最外层调用方加锁。`restoreGameRequest`（`:968-974`）就是"外层加锁、内层 Locked"的写法，抽取 `GameOperations` 时不要嵌套调用。
   - `StorageOperationLock` 增加 `owner`，并通过 StateFlow 暴露，UI 显示"等待自动备份完成…"。
   - Worker 改成按游戏加锁后，它在 `AutoBackup.kt:103` 一次性拉取的 `remoteSnapshots` 会和批量任务交错，数据可能过期。每款游戏在做决策前要重新读 head。
   - 恢复中"写回 + markRemoteRestore"这一段放进 `withContext(NonCancellable)`。
2. **执行方式（简化）**：
   - 纯本地批量（备份、检查变更）：在 ViewModel 里用一个保存下来的协程 Job 执行，项与项之间 `ensureActive()`，可以取消。
   - 中断后的处理：备份因为去重是幂等的，用户"再跑一次"即可补齐，**不做持久化队列，也不弹"继续/放弃"提示**。
   - 含网络的批量（上传、恢复云端较新）：用 `BatchWorker`（`enqueueUniqueWork("opensave_batch", KEEP)`），进程被杀后由 WorkManager 自动续跑，UI 只展示进度，不再询问。`KEEP` 会静默丢掉第二个请求，所以已有任务在跑时，UI 必须明确提示"已有批量任务在运行"并拒绝新请求。
3. **Android 13 前台服务约束**：
   - `setForeground(ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))` 必须在 `doWork` 一开始、应用还在前台时调用；通知渠道要先创建。
   - WorkManager 在后台重新拉起时调用 `setForeground` 会抛 `ForegroundServiceStartNotAllowedException`。要 catch 住，降级为非前台运行；这时受约 10 分钟执行上限约束，被停掉后会重排，因为幂等可以接着跑。
   - 本地备份批次**不加** `NetworkType.CONNECTED` 约束；网络类批次的网络失败作为单项结果记录。
   - 用户拒绝 `POST_NOTIFICATIONS` 时前台服务照样能跑，只是通知不显示，但它仍会出现在 Android 13 的"活跃应用"面板里，用户可以在那里停止应用。报告里要能体现"被中断"，重跑时可以补齐。
   - manifest 增加 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`，并用 `tools:node="merge"` 给 `androidx.work.impl.foreground.SystemForegroundService` 声明 `foregroundServiceType="dataSync"`。
4. **AutoBackupWorker 让路**：发现批量任务在跑时，直接 `Result.success()` 跳过本轮，不用 `retry()`，因为周期任务的 retry 会走指数退避并消耗 `MAX_RETRIES`。Worker 自己处理约 90 款游戏时也要分片：每轮最多处理一批，或者同样走前台服务，避免超过 10 分钟上限、在持锁中途被系统停掉（B9）。
5. **错误策略**：单项失败就继续下一项；401/403 中止整个批次；网络失败只记入报告，不暂停整批。
6. **数据安全前提（阶段 0 就做）**：
   - `SnapshotRepository` 改为进程单例（ViewModel 和 Worker 共用一个实例），`writeRecords` 改用 `AtomicFile`，或直接 `renameTo` 覆盖（POSIX 上 rename 是原子的），去掉先 `delete()` 再 rename 的写法。`BatchReportStore` 照同样方式写。
   - 启动时扫描模拟器目录下的 `.opensave-game-new-*`/`.opensave-game-old-*` 残留（`RootStorage.kt:177-178`），能回滚的回滚，否则提示用户。
   - 大存档的 Root 复制按体积放宽超时，或拆分命令；超时后先确认远端 shell 已经结束，再做清理。

### 5.6 进度、取消与汇总

- **BatchProgressBar**（新增）：只在任务运行时出现。横屏在底部 34dp 状态条，竖屏在 NavigationBar 上方。显示任务名、i/N、当前项、LinearProgressIndicator 和可聚焦的"取消"。点开后是逐项状态列表。
- **BatchReportSheet**（新增，用 Dialog 实现）：按 成功 / 无变化跳过 / 待确认（syncNotice，不再放进红色 error）/ 失败 / 被中断 分组；提供"重试失败项"、"只看失败"、"复制报告"；失败项可以跳到对应游戏的详情。最近 20 份报告在"同步与任务"里可以查到。
- 瞬时结果用 Snackbar（带"查看详情"或"撤销恢复"），并且**只在发起操作的那个页面显示**。
- 批量运行期间，浏览、筛选、打开详情都可以用，只禁用和正在运行的项冲突的操作。

### 5.7 危险操作确认清单（一次确认，事后可撤销）

| 操作 | 现状 | 改为 |
|---|---|---|
| 备份 / 批量备份 / 备份有变更 | 单击就执行（`MainActivity.kt:532-539`），会产生重复快照 | 不弹确认（有去重，非破坏性）；进度可取消 |
| 恢复（单项/批量） | 通用文案（`:154-177`） | 一次确认：写明将被替换的位置、所用版本（时间/设备/大小），以及"会先生成安全快照"；初始焦点在"取消"。完成后用 Snackbar 提供"撤销恢复"（用安全快照恢复回去） |
| 工作区写回来源目录 | 没有确认，不留副本（`:596-599`，`SourceViewModel.kt:488-497`） | 移到"高级"；确认页写明目标路径和文件数；先做安全副本；工作区记录来源 sourceId，不一致就拒绝；只允许已保存的来源 |
| 移除 SAF 授权目录 | 单击（`:699-704`） | 确认 |
| 清理版本 / 云端删除 | 现在没有这个功能 | 先给 PrunePlan 预览；云端只删除本设备上传的、且不是当前 head 的归档 |
| 手柄快捷键 | — | 单键直达的只能是非破坏性操作（Start = 备份有变更）；恢复和删除一律经过对话框 |

---

## 6. 与 PC 端 / game-save 对齐项

> A1、A2、A3、A13 属于跨端协议修复，**单独开 issue/PR，以 PC 端为主推进，不算进 0.4.0 的里程碑和验收**。Android 0.4.x 只做不依赖它们的部分，并且保证不往错误的命名空间写入。

| # | 对齐项 | 改哪一端 | 做法 | 优先级 |
|---|---|---|---|---|
| A1 | Switch 命名空间 | **PC 为主**，Android 兜底（单独立项） | PC：在 `TrackGame` 中，只要 `switchtitle.FromSavePath` 不为空就强制 `SaveType="switch"`（`Go/daemon/daemon.go:763` 附近，offered/import/retrack/CLI 都经过这里）；`ScanDialog.svelte` 按 TitleID 自动判定，对 TitleID 存档隐藏 emulator-eden/yuzu/ryujinx；新增迁移，把匹配 switchtitle 的游戏改为 `save_type='switch'`，并清空 `cloud_heads.native_head`（否则 `Go/daemon/game_save_native.go:149-153` 会拒绝发布），旧 scope 按"需要确认"处理。Android：`switch` scope 为空时只读探测一次 `pc/<同 gameId>`，有内容就提示"PC 端仍在用旧命名空间"，**绝不跨命名空间写入**。只适用于 Root 来源（SAF 来源不是 desktopCompatible，`GameSaveTypes.kt:140-141`） | 跨端 P0 |
| A2 | 游戏身份规则 | 两端 | 写一份共享的《游戏身份规则》：Switch = `switch/switch-<tid小写>`；模拟器按存档自身标识（PPSSPP 用 SAVEDATA 目录名，Dolphin 用游戏代码，RetroArch 用 ROM 基名…），**不再把 sourceId 算进 gameId**；PC 游戏不自动猜，改为显式"关联"（PC 已有 `Go/store/aliases.go`、`LinkPicker.svelte`，Android 新增"关联到云端游戏"选择器）。多位置归档统一用 PC 的 `.opensave-locations/<name>/` 布局。**Android 端需要做 gameId 迁移**：旧的 `android-*-<hash>` scope 映射到新 gameId（记录别名，旧 scope 只读，不删除） | 跨端 P1 |
| A3 | 模拟器类型列表 | PC | PC 前端补齐 Android 的 `emulator-*`（`GameSaveTypes.kt:21-35`），按 preset ID 自动带出 saveType | 跨端 P1 |
| A4 | 重复 TitleID 和卡片合并 | Android | 先做（不依赖 A2）：每个 (saveType, gameId) 只有一个"同步来源"（默认选 Root、非候选、最近修改的那个），其他来源只做本地备份，避免 C3 的互相误判。卡片合并：Switch（desktopCompatible）在 A1 后可以合并；非 Switch 游戏**在 A2 落地前不能按 gameId 合并**（gameId 含 sourceId，本来就不会相同），合并时通过 key 映射表迁移选择集、排除表和历史 | P1（同步来源）/ A2 之后（合并） |
| A5 | 自动拉取 | Android | 新增与 PC 同名的设置"自动恢复其他设备的续接更新"，默认值对齐 PC，先只对 Root 单目录 Switch 生效；恢复前生成安全快照，写入动态日志并发通知；旧 head 协议的后台覆盖行为与原生协议统一（见 Q7） | P2 |
| A6 | 冲突解决 | 两端 + game-save | 共同设计"解决冲突"流程：选定保留的版本，以当前 head 为 parent 重新发布；game-save 新增 `POST /scopes/{type}/{id}/conflicts/{cid}/resolve`（待确认）。Android 详情的"版本"标签读 scope，区分当前版本、历史和冲突副本，并显示来源设备 | P2 |
| A7 | 保留策略 | Android | `SnapshotRecord` 增加 `auto/manual/safety/pinned` 字段（旧格式记录要能正常解码），默认值与 PC 相同（自动 20 份、手动不限、固定的不删）；服务端保留由 game-save 统一负责 | P2 |
| A8 | 术语和中文化 | 两端 | 采用 §3.2 的术语表；PC 优先补 `Sidebar`、`GameDetail` 标签、`saveTypeLabel`、`ScanDialog`/`AddGameCard` 的 zh-CN | P1 |
| A9 | 批量和选择模型 | 两端 | Android 采用 PC 的选择模式（§5.1）；筛选对齐"全部 / PC / Switch / 模拟器"（`Home.svelte:118-121`），模拟器内部再细分；汇总参照 PC 的 bulkPartial。PC 可以补"批量检查跨设备更新"（`/api/cloud/check` 已存在）和任务面板 | P1 |
| A10 | 手柄语义 | 两端 | §4.1 的映射表；设置项同名。PC 可以考虑同样给 Start 绑定"备份有变更" | P1 |
| A11 | 游戏名和指纹 | Android | 去重用的 `contentHash` 排除清单文件（**阶段 1 随去重一起做**，见 §5.3）；head 协议的 `manifestHash` 换算法放到阶段 5，带版本号并迁移旧状态；移植 `Go/presets/switchtitles.go:17-40` 的缓存读取逻辑 | P1（contentHash）/ P2（其余） |
| A12 | 设备名、URL、分支 | Android | game-save 卡增加可编辑的"设备名称"（默认 Build.MODEL）；URL 规范化补上去掉 `/opensave` 结尾；明确原生同步只同步 main | P3 |
| A13 | 共享测试数据 | 两端 | 仓库新增 `testdata/identity-fixtures.json`（路径或游戏 → saveType/gameId/archiveName/parent 判定），PC 的 `go test` 和 Android 的单元测试都读它（Gradle 里把仓库根目录的 `testdata/` 配成 test resources），在 CI 里保证两端一致 | 跨端 P1 |
| A14 | 安全快照是否上传 | 两端 | 建议两端都上传，带 safety 标记，不推进 head（见 Q9） | P3 |
| A15 | 配置导入 | 两端 | PC 端提供"复制 Android 配置"，生成一段 `opensave://config?...` 配置串（含地址、密钥，提醒用户不要外传）；Android 端"从剪贴板导入"一步完成。局域网发现需要 game-save 支持，列为待确认（Q19） | P1 |

---

## 7. 分阶段实施计划

排序原则：

1. 先做低成本、高收益的修复和数据安全热修。
2. 然后做横屏布局和返回栈，作为第一个可见成果。
3. 再做手柄，最后做批量引擎和信息架构。
4. 跨端协议修复单独立项，由 PC 端并行推进。

### 阶段 0：测量、决策与热修（2–3 天）

| 项 | 内容 |
|---|---|
| 实测 | ① 密度、窗口、系统栏和手势条高度；② 每个按键的上报方式：D-pad 是 KEYCODE 还是 HAT，L2/R2 是按键还是轴，右摇杆是 Z/RZ 还是 RX/RY，以及 X/Y/Start/Select 的 kcm 回退；③ Select/Start 是否已被系统热键（截图、性能模式等）占用，Odin2 有没有独立的 Back/Home 键；④ 用最小 Demo 测 Compose 收到手柄键后 `InputMode` 会不会切到 Keyboard；⑤ toybox `find -printf` 是否可用 |
| 决策 | 选择 key 模型（§5.1：0.4.x 保持 `sourceId::gameKey`）；Compose BOM 升级（Q6）在阶段 1 前完成 |
| 热修 | ① 工具条上的选择改为**默认不选**（`MainActivity.kt:248-251`）；② 状态提示和错误**只在发起操作的页面显示**（`SourceViewModel.kt:74-75` 拆成按页的消息）；③ 工作区写回加确认和安全副本，只允许已保存的来源；④ 同步密钥加遮罩；⑤ 不存在的来源不再显示为勾选，并且禁止勾选；勾选来源改为暂存，用 300ms 防抖或"应用"按钮触发扫描，不再每次都全量重扫（`:463-473`）；⑥ `SnapshotRepository` 改为进程单例，写入改为原子操作（B11）；⑦ manifest 加 `configChanges="keyboard\|keyboardHidden\|navigation"`、`windowSoftInputMode="adjustResize"`、`uses-feature gamepad/touchscreen required=false`（**不加** leanback） |
| 涉及文件 | `A/MainActivity.kt`（`:112-177, :248-251, :596-599, :812-818, :751-777`）、`A/SourceViewModel.kt`（`:74-75, :463-473, :488-497, :1249-1272`）、`A/SourceSelection.kt:112-113`、`A/SnapshotRepository.kt:60-72`、`A/AutoBackup.kt:97`、`AndroidManifest.xml` |
| 验收 | 游戏库进入时显示"已选 0"；游戏库里不再出现"game-save 连接成功"；写回必须经过对话框，并会生成安全快照；截图中密钥显示为 •••；"目录不存在"的来源不可勾选；连按 5 次来源复选框只触发 1 次扫描；在 `writeRecords` 期间 `kill -9` 进程，索引不丢 |
| Odin2 测试 | `adb shell wm density; adb shell wm size; adb shell dumpsys window \| grep -E 'mAppBounds\|mCurrentAppOrientation'`；`adb shell getevent -lt` 依次按全部按键并记录键码；`adb shell dumpsys input \| grep -A30 -i odin`；`adb shell "su -c 'find /sdcard -maxdepth 1 -printf \"%s %T@ %p\n\"'"` 验证 `-printf`；结果记录到 `android/docs/odin2-input.md` |

### 阶段 1：自适应外壳、横屏布局、返回栈与去重（约 1.5 周）：第一个可见成果

| 项 | 内容 |
|---|---|
| 前置 | Compose BOM 从 2024.12.01 升级到 1.8 以上（`focusRestorer` 在 1.8 改为 `fallback: FocusRequester` 签名，`LocalBringIntoViewSpec` 在 1.7 还是 Experimental）；先补 `@Preview` 和基础 Compose 测试，再拆分 `MainActivity.kt`（这部分已算进工期） |
| 范围 | `AdaptiveLayoutInfo`（`BoxWithConstraints`）三种模式；横屏用 NavigationRail，去掉 TopAppBar；固定工具条（筛选 / 统计 / 横幅 / "备份有变更 (N)" / 选择 / 扫描）移到网格之外；底部状态条只在任务运行时出现，按键提示放在 Rail 底部；`GameLibraryCard` 改为 56–60dp 紧凑行（标题一行；副行显示来源、最近备份时间、`本地 N · 已上传/未上传`；去掉每卡的备份按钮和重复的"详情"按钮）；横屏双栏，焦点跟随详情（150ms 防抖），详情先复用 `:1170-1228` 的内容，标签为"版本 / 存档位置"；竖屏用触屏优先的单列紧凑行、吸顶工具条、全屏详情页；**BackHandler 返回栈**；`enableEdgeToEdge()`，系统栏跟随主题；UI 状态迁到 ViewModel 和 SavedStateHandle；**§5.3 的去重（统一三条路径的输入，加 `contentHash`，加 `findReusableRecord`）**；后台只读刷新云端（节流 5 分钟） |
| 涉及文件 | 拆分 `A/MainActivity.kt`，业务逻辑不动：新增 `A/ui/AdaptiveLayoutInfo.kt`、`A/ui/AdaptiveShell.kt`、`A/ui/library/LibraryToolbar.kt`、`A/ui/library/GameListRow.kt`、`A/ui/library/GameDetailPane.kt`、`A/ui/StatusStrip.kt`；修改 `A/SourceViewModel.kt`（`SourceUiState` 增加 `selectedGameKey/selection/selectionMode/filter`；`:598, :632-750`）、`A/GameLibrary.kt:93-94`、`A/AutoBackup.kt:56-60, :62-79, :283-293`、`A/SnapshotRepository.kt`（`contentHash/hashVersion` 字段）、`A/OpenSaveTheme.kt`、`Res/values/styles.xml`。不引入 adaptive 依赖，手写 Rail 和双栏（Q5） |
| 验收 | S1（≥ 340dp）、S2（≥ 5）、S3（≥ 7）、S8 达标；工具条在任何滚动位置都可见；竖屏详情按返回键回到列表；系统栏颜色和应用主题一致；字体缩放 1.3 时紧凑行单行省略、不换行；**内容不变时连续两次"备份有变更"，第二次全部是"无变化跳过"，快照数不变**；单游戏、批量、自动三条路径对同一内容得到相同的 `contentHash`；旧记录能正常解码 |
| 自动化 | `@Preview` 覆盖 835×470、960×540、470×835，各配 fontScale 1.0 和 1.3；新增 `androidTest/AdaptiveShellTest.kt`（`createAndroidComposeRule`，断言横屏有 Rail、工具条一直存在、旋转后 `selectedGameKey` 保留）；单元测试覆盖 `contentHash` 和 `findReusableRecord` |
| Odin2 测试 | `adb install -r app-debug.apk`（如果用 `deviceTest` 构建类型，包名是 `com.jun9100.opensave.test`，见 `android/app/build.gradle.kts:27`）；锁定横屏：`adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1`（竖屏用 0）；截图：`adb exec-out screencap -p > land.png`，数出可见行数；字体：`adb shell settings put system font_scale 1.3`；深色：`adb shell cmd uimode night yes`；在真实的 90 款游戏库上横竖屏来回切 10 次，确认选择和滚动位置不丢；快照数对比：`adb shell "su -c 'ls /data/data/com.jun9100.opensave/files/snapshots \| wc -l'"` |

### 阶段 2：手柄与焦点（约 1 周）

| 项 | 内容 |
|---|---|
| 范围 | §4 全部内容：`MainActivity.dispatchKeyEvent` 只分发 X/Y/Start/Select/L1/R1/L2/R2（Down 和 Up 都消费），A/B/方向键放行；右摇杆滚动；输入模式判断（按阶段 0 的结果决定用 `InputMode` 还是 gamepad 来源）；焦点环；整行单焦点；分区 `focusGroup`/`focusRestorer`；Rail 移出焦点链；初始焦点；busy 时不禁用；对话框初始焦点；连接配置改为摘要行加编辑对话框；来源行改为 `toggleable`；X 菜单（浏览模式和选择模式各一套）；Y 命令面板（Dialog）；Start = 备份有变更；L2/R2 翻页；"手柄导航：自动/开/关"；按键图标风格（只换图标）；振动反馈；`enableOnBackInvokedCallback` |
| 涉及文件 | 新增 `A/ui/input/HandheldInput.kt`、`A/ui/input/HandheldFocus.kt`、`A/ui/CommandDialog.kt`、`A/ui/GameActionMenu.kt`；修改 `A/MainActivity.kt`（`dispatchKeyEvent`、`dispatchGenericMotionEvent`）、`AdaptiveShell.kt`、`GameListRow.kt`、设置相关卡片、`AndroidManifest.xml` |
| 验收 | S4（每款 1 个焦点）；S5 横屏 F0–F7 全程只用手柄完成；A 键在所有按钮上都能激活（没有被拦截）；X/Start 不会误点当前按钮；B 键不会从详情或二级页直接退到后台；批量或扫描结束后焦点回到原来的行；没有任何焦点时按 L1/R1/Y 也能响应；D-pad 遍历时文本框不会弹出输入法 |
| 自动化 | Compose 测试先调用 `InputModeManager.requestInputMode(InputMode.Keyboard)`，再用 `performKeyInput { pressKey(Key.DirectionDown) }` 断言焦点数，用 `pressKey(Key.DirectionCenter)` 断言按钮能被激活；断言命令面板打开后焦点在面板内 |
| Odin2 测试 | 拔掉 USB，只用手柄按 F0–F7 录屏（`adb shell screenrecord /sdcard/f.mp4`）；系统里切换 Xbox/Nintendo 布局后复测 A/B；连按 ↓ 10 次，检查焦点不会跳出列表；扫描时连续按方向键，结束后检查焦点位置；补充验证用 `adb shell input keyevent KEYCODE_BUTTON_L1` 等命令（注意 `input keyevent` 不经过手柄的 kcm 回退，A/B 必须用实体键验证） |

### 阶段 3：状态模型、选择模式与批量（约 1.5–2 周）

| 项 | 内容 |
|---|---|
| 范围 | `GameStatusResolver` 和状态徽标、状态筛选（含廉价签名）；显式选择模式加上下文操作栏；抽取 `GameOperations`（规定 `*Locked` 不再加锁）；首批 4 种批量操作和每游戏自动备份开关（§5.4）；本地批量用可取消的协程，网络批量用 `BatchWorker`（前台服务、异常降级、拒绝重复请求）；`BatchProgressBar`/`BatchReportSheet`/`BatchReportStore`；`currentSnapshots` 改为按游戏取 N 条；`checkCloudUpdates` 和 `AutoBackupWorker` 改为按游戏加锁，每款游戏重新读 head；`StorageOperationLock` 加 owner；Worker 遇到批量任务时返回 `success()` 跳过本轮，并分片处理以免超时 |
| 涉及文件 | 新增 `A/library/GameStatusResolver.kt`、`A/ops/GameOperations.kt`、`A/batch/BatchReportStore.kt`、`A/batch/BatchWorker.kt`、`A/ui/batch/BatchProgressBar.kt`、`A/ui/batch/BatchReportSheet.kt`、`A/ui/batch/BatchPlanDialog.kt`；修改 `A/SourceViewModel.kt`（`:539-594, :632-750, :821-873, :968-1114, :1293-1296`）、`A/AutoBackup.kt`（`:88, :103, :117, :380-456`）、`A/StorageOperationLock.kt`、`A/GameLibrary.kt`、`android/app/build.gradle.kts`（`androidx.work:work-testing`）、`AndroidManifest.xml` |
| 验收 | S6（首批 4 种 + 自动备份开关）、S7、S9；自动备份运行时 UI 显示"等待自动备份完成"；已有网络批量在跑时，再发起会明确提示；`GameStatusResolver`、去重、Worker 让路都有单元测试 |
| 自动化 | 用 `TestListenableWorkerBuilder` 测 `BatchWorker` 和 `AutoBackupWorker`（包括 `setForeground` 抛异常时的降级路径）；把 `RootCommand` 抽成接口，用假实现测恢复脚本的回滚分支 |
| Odin2 测试 | 对 87 款执行"备份有变更"，到 30/87 时：① 点"取消"，检查报告；② 再跑一次，中途执行 `adb shell "su -c 'kill -9 \$(pidof com.jun9100.opensave)'"`，重开后再跑一次，确认快照数没有重复增长；③ 执行"上传待传"，中途 kill，确认 WorkManager 自动续跑、UI 只展示进度；④ 关闭通知权限后跑网络批量，确认应用内进度正常；⑤ 断开 Wi-Fi 跑上传，确认失败项进入报告，可以"重试失败项"；⑥ 用 `adb shell dumpsys jobscheduler \| grep -i opensave` 查 WorkManager 的 jobId，或用 `adb shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS -p com.jun9100.opensave` 导出诊断 |

### 阶段 4：信息架构与流程梳理（约 2 周）

| 项 | 内容 |
|---|---|
| 范围 | `OpenSavePage` 改为 游戏库 / 来源 / 设置 三个一级页，加"同步与任务"二级页；游戏库顶部状态横幅；`cloudUpdates` 持久化，每项提供"用云端覆盖 / 保留本机并发布 / 稍后"，原因文案取自 `GameSaveNativeSync.kt:141-150`；来源页横屏双栏分组（不存在的折叠并禁止勾选），改动暂存后统一"应用并重新扫描"；去掉存储模式分段开关，整来源快照和写回移入"高级"；首次启动向导（3 步，含 Magisk"永久授权"引导，没有 Root 时引导走 SAF）和按原因区分的空状态；合并自动化设置并写明依赖；动态日志（Worker 结果持久化，需要确认的项发通知）；配置改为"测试并保存"一步完成，所有操作统一用已保存的配置（修 `:307, :823`），刷新不再覆盖草稿；从剪贴板导入 `opensave://` 配置（A15）；术语按 §3.2 替换；延后的批量操作（恢复本地最新、下载云端、清理旧版本）按需加入 |
| 涉及文件 | `A/SourceViewModel.kt`（拆出 `SyncInbox`、`ActivityLog`、`Selection` 三块持久状态）、`A/SourceSelection.kt`（`:112-113, :175-189`）、`A/AutoBackup.kt`（`:91-92, :131-136, :224-269`）、`A/SnapshotRepository.kt`（`removeAll`、保留字段）；新增 `A/ui/sync/SyncTasksPage.kt`、`A/ui/sources/SourcesPage.kt`、`A/ui/settings/SettingsPanes.kt`、`A/ui/onboarding/FirstRunWizard.kt`、`A/activity/ActivityLogStore.kt`；`Res/values/strings.xml`（把文案从代码迁出，方便统一术语） |
| 验收 | 冲突项在"同步与任务"里有三个明确选项；后台自动备份的每次运行都能在动态日志里查到；新用户没有 Root 时能被引导走 SAF；来源页首屏只显示可用来源；从剪贴板导入后无需手动输入就能连通 |
| Odin2 测试 | 清除数据（`adb shell pm clear com.jun9100.opensave`）走首次启动；在 Magisk 里拒绝 su 再走一遍；选"仅此一次"授权，然后触发后台 Worker，确认会提示需要永久授权；在 PC 端制造一个跨设备更新和一个冲突，到 Odin2 上处理；Worker 用 `dumpsys jobscheduler` 查到的 jobId 执行 `adb shell cmd jobscheduler run -f com.jun9100.opensave <jobId>` 触发，然后检查动态日志；Root 恢复后执行 `adb shell "su -c 'ls -lZ <目标目录>'"`，检查 owner 和 SELinux 上下文，确认模拟器能读到存档 |

### 阶段 5（单独立项，与阶段 1–4 并行）：跨端协议与体验对齐

| 项 | 内容 |
|---|---|
| 范围 | §6 的 A1（PC 优先）、A13 共享测试数据、A2/A3 身份规则、Android gameId 迁移和关联、A4 卡片合并、A8 PC 中文化、A9/A10 两端选择和手柄语义、A11 中 head 哈希的版本化迁移、A5/A6/A7/A12/A14 |
| 涉及文件 | PC：`Go/daemon/daemon.go:762-767, :1317`、`Go/daemon/game_save_native.go`、`Go/store/games.go:90-92`、新迁移 `Go/store/migrations/00xx_switch_save_type.sql`、`FE/views/home/ScanDialog.svelte:42,198,326-333`、`FE/views/home/AddGameCard.svelte:96-104`、`FE/lib/cloudproviders.js:27-44`、`FE/components/Sidebar.svelte`、`FE/views/game/GameDetail.svelte`、`FE/locales/zh-CN.json`。Android：`A/GameSaveTypes.kt:140-166`、`A/GameSaveClient.kt:386-388`、`A/GameSaveHeadSync.kt:359-360, :444-445, :528`、`A/GameSaveNativeSync.kt`、`A/GameCatalog.kt`、`A/AutoBackup.kt:62-79`。共享：`testdata/identity-fixtures.json` |
| 验收 | S10（仅 Root 来源）：Windows 和 Odin2 对同一 Switch 游戏读写同一个 `switch/switch-<tid>` scope；共享测试数据在 `go test ./...` 和 Android 单元测试里都通过；head 哈希迁移后，已同步的游戏仍显示"已同步"，不会变成"需要确认"；PC 侧栏和详情页标签显示中文 |
| 测试 | 迁移前先在 game-save 上列出所有 `pc/switch-*` 和 `*/android-*` scope 并备份；Windows 备份一次 → Odin2"检查云端更新"应出现该游戏 → 恢复 → Odin2 再备份 → Windows 自动拉取；对迁移后的旧 scope 验证会走"需要确认"，不会被静默覆盖 |

### 工作量与顺序一览

```
周    1    2    3    4    5    6    7    8
P0   ■
P1    ■■■■■■                                ← 外壳/横竖屏/返回栈/去重（0.4.0-beta）
P2          ■■■■                            ← 手柄（0.4.0）
P3              ■■■■■■■                     ← 状态与批量（0.4.x）
P4                     ■■■■■■■■             ← 信息架构（0.5.0）
跨端  ■■(A1/A13，PC 端) ··· 其他项穿插 ···   ← 单独立项，不阻塞 0.4.0
```

---

## 8. 风险与待确认问题

### 8.1 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 密度和尺寸判断错误 | 截图推算是 835×470dp，任务给的是 960×540dp，会影响行数目标 | 阶段 0 实测；断点只看"宽 ≥ 600 且高 < 600"，对两种尺寸都成立；S1/S2 按较小的 835×470 定 |
| 拦截按键导致按钮失效 | 拦截 A/B 会让 Generic.kcm 不生成 DPAD_CENTER/BACK 回退；没拦住的 X/Start 会误点按钮 | A/B/方向键放行；其余键 Down 和 Up 都消费；阶段 2 的 Compose 测试和实体键测试覆盖 |
| 一次性拆分 `MainActivity.kt` 引入回归 | 1399 行，没有预览，没有 UI 测试 | 先加预览和基础测试再拆（已算进工期）；只移动代码，`SourceViewModel` 的接口不变 |
| Compose 焦点 API 版本差异 | 1.7 中 `focusRestorer` 是 Experimental，并且 1.8 改了签名；输入模式不一定会切到 Keyboard | 阶段 1 前升级 BOM；封装在 `HandheldFocus.kt`；输入模式以阶段 0 的实测为准 |
| 前台服务在后台无法启动 | WorkManager 重新拉起时 `setForeground` 抛 `ForegroundServiceStartNotAllowedException` | catch 后降级为非前台运行；靠幂等重跑；通知渠道提前创建 |
| 去重改哈希影响 head 判定 | `manifestHash` 同时用于 head 比较 | 去重只用新的 `contentHash`；head 哈希换算法时带版本号并迁移旧状态（阶段 5） |
| Mutex 不可重入导致死锁 | 抽取 `GameOperations` 时容易出现嵌套加锁 | 约定只有最外层加锁，`*Locked` 绝不加锁；加单元测试 |
| 快照索引损坏 | 先删再 rename，加上两个实例并发写 | 阶段 0 改为单例和原子写 |
| Root 恢复中断或权限不对 | su 超时、进程被杀后留下残留目录、SELinux 标签不对 | 启动时扫描残留；按体积放宽超时；恢复后验收 `ls -lZ` |
| Magisk 后台授权 | Worker 在后台调用 su 时弹不出授权框 | 首次运行向导引导"永久授权"；授权失败写入动态日志并通知 |
| 命名空间迁移误覆盖 | PC 迁移会改变 scope，旧数据可能和新 scope 冲突 | 单独立项；迁移前列出并备份；旧 scope 只读探测，按"需要确认"处理；绝不跨命名空间写入 |
| 合并卡片影响 libraryKey | 合并会改变选择集、排除表和历史的 key | 0.4.x 不合并；A2 后通过 key 映射表一次性迁移，用共享测试数据锁定 |
| "跟踪"模型改变自动备份范围 | 老用户原来"全部自动备份"的行为会变 | 迁移时把"已备份过的游戏"标记为已跟踪，并给出一次性提示 |
| "有变更"检测的开销 | 87 款首次全量复制和哈希耗时长；SAF 遍历慢 | 用 size+mtime 签名做提示；SAF 用 `DocumentsContract` 批量查询；首次检查作为可取消的批量任务 |

### 8.2 待确认问题（需要用户决定）

| # | 问题 | 推荐 |
|---|---|---|
| Q1 | Odin2 的实际密度、窗口和系统栏高度，以及按键上报方式和 kcm 回退；Select/Start 是否被系统热键占用；有没有独立的 Back/Home 键 | 阶段 0 实测 |
| Q2 | 横屏默认是否开启沉浸模式（隐藏状态栏和手势条，多出约 48dp，但看不到电量和时间） | 默认关闭，在设置里提供开关 |
| Q3 | 竖屏详情用全屏页还是 ModalBottomSheet | 全屏页（和横屏双栏一一对应，返回逻辑简单） |
| Q4 | ~~手柄 X/Y 的语义~~ | **已关闭**：与 PC 一致（X=菜单，Y=命令面板）；快速备份通过"A 进右栏，再按 A"完成，Start = 备份有变更 |
| Q5 | 是否引入 `material3-adaptive` / `adaptive-navigation-suite` 依赖 | 先手写 Rail 和双栏；稳定后再评估 |
| Q6 | Compose BOM 是否从 2024.12.01 升级到 1.8 以上 | 需要，**在阶段 1 之前**升级 |
| Q7 | 后台自动恢复策略：旧 head 协议会在后台覆盖，原生协议只推迟。统一成哪一种？是否和 PC 一样默认自动拉取？ | 统一为"续接更新可以自动恢复（先做安全快照，写入动态日志并通知），冲突一律推迟"；默认值对齐 PC |
| Q8 | 是否引入"跟踪/排除"模型 | 采用；默认不选（阶段 0 已改），并保留"备份有变更"一键操作 |
| Q9 | 安全快照和历史快照是否上传到 game-save（会占 NAS 空间；服务端目前没有清理接口） | 安全快照上传并标 safety；历史快照默认不上传 |
| Q10 | game-save 能否新增"解决或忽略冲突"接口和服务端保留策略 | 需要，否则 PC 一旦出现冲突就会一直禁止恢复 |
| Q11 | 批量恢复里的冲突项（REVIEW_REQUIRED）能否一次确认覆盖 | 不能；只能在"同步与任务"里逐个处理 |
| Q12 | 每游戏的自动备份开关和保留数量，是只存在本机，还是经 game-save 与 PC 同步 | 先只存本机，字段名与 PC 的 `AutoSync`/`MaxSnapshots` 对齐 |
| Q13 | "整来源快照 / 复制到工作区 / 写回"还有没有实际用途 | 没有的话直接移除；有的话放进"高级"，并加确认和安全副本 |
| Q14 | 同一游戏出现在多个来源时，是合并成一行，还是保留来源维度 | 0.4.x 保留来源维度（只指定一个同步来源）；A2 落地后合并 |
| Q15 | 模拟器命名空间怎么统一：PCSX2 与 AetherSX2/NetherSX2 用 `emulator-pcsx2` 还是 `emulator-ps2`？Citra/Azahar/Lime3DS 是否统一为 `emulator-citra`？ | 按"主机平台 + 主流实现"统一，写进共享测试数据 |
| Q16 | 服务器上是否已有 PC 写入的 `pc/switch-0100…` scope，以及 Android 写入的 `*/android-*` scope？迁移方式选迁移、并存还是逐个确认？ | 先列出再决定，默认逐个确认 |
| Q17 | GameHub/Winlator 的 PC 游戏是否需要和 Windows 原生存档互通 | 只做显式关联，不自动匹配 |
| Q18 | 是否接受网络类批量任务期间常驻前台通知 | 接受（只在含网络操作时启用） |
| Q19 | game-save 是否支持局域网发现（mDNS 等），用来免输入地址 | 先做剪贴板导入；局域网发现视 game-save 的支持情况再定 |

---

## 审核记录

### handheld-ux

| # | 处理 | 说明 |
|---|---|---|
| 1 | 采纳 | 重算了高度预算（加入约 24dp 手势条，可用高度约 422dp）；状态条只在任务运行时出现，按键提示移到 Rail 底部；行高改为 56–60dp；S1 改为 ≥ 340dp（沉浸模式 ≥ 380dp）；删除了"纯网格 ≥ 9" |
| 2 | 采纳 | 新增 F0；工具条常驻"备份有变更 (N)"，Start 直达；不弹确认；去重提前到阶段 1。但不是简单搬 `findReusableRecord`：按 engineering 8，同时引入 `contentHash` 并统一三条路径的输入 |
| 3 | 采纳 | 详情改为"版本 / 存档位置"两个标签；主按钮"恢复…"带推荐版本；一次确认加"撤销恢复"；§0 和 §5.7 已同步修改 |
| 4 | 采纳 | A、B 和方向键放行，只拦截 X/Y/L/R/Start/Select；B 只走 BackHandler |
| 5 | 采纳 | 焦点跟随详情（150ms 防抖，只读本地数据）；A 进右栏，B/← 返回；Rail 移出焦点链；Q4 关闭 |
| 6 | 采纳 | 竖屏改为触屏优先；S5 横屏验收手柄，竖屏验收触屏 |
| 7 | 采纳 | 阶段 1 行内只用现有数据；后台刷新云端提前到阶段 1 |
| 8 | 采纳 | 默认不选、提示按页隔离都放进阶段 0 |
| 9 | 采纳 | L2/R2 改为整屏翻页；状态筛选、排序、搜索放进 Y 面板 |
| 10 | 采纳 | Start = 备份有变更；A/B 互换改为只换图标；阶段 0 实测系统热键和 Back/Home 键 |
| 11 | 采纳 | Select 加 D-pad 扩展选区；选择模式下 X 是单独的选择菜单 |
| 12 | 部分采纳 | 去掉了持久化队列、"继续/放弃"提示和"连续 3 次失败暂停"；首批只做 4 种，S6 下调。保留了 `BatchWorker`（只用于网络批量），因为 engineering 10/11 指出网络操作被中断后需要可靠续跑 |
| 13 | 采纳 | 一级页面收为 3 个，加游戏库横幅；"同步与任务"改为二级页 |
| 14 | 采纳 | 新增 A15 剪贴板导入配置；局域网发现列为 Q19 |
| 15 | 采纳 | 来源页横屏双栏，"不存在"的来源折叠并禁止勾选；勾选防抖并入阶段 0 第 ⑤ 项 |
| 16 | 采纳 | 阶段 0 实测输入模式，不切换时退回 gamepad 来源判断 |
| 17 | 采纳 | A1/A2/A3/A13 单独立项；S10 不计入 0.4.0 验收 |
| 18 | 采纳 | 加入振动反馈；状态条不常驻 |

### engineering

| # | 处理 | 说明 |
|---|---|---|
| 1 | 采纳 | 与 handheld 1 一起修改 |
| 2 | 采纳 | §4.2 写明 Down 和 Up 都消费，并写明 X/Start/Select 的回退（以实测为准） |
| 3 | 采纳 | 全局键改为在 `MainActivity.dispatchKeyEvent` 分发，与摇杆走同一个 SharedFlow |
| 4 | 采纳 | 左摇杆和连发改为"沿用系统连发，列表层节流" |
| 5 | 采纳 | I5 改写为"移到后台、进程被杀导致中断"，并关联 B1 |
| 6 | 采纳 | 已核对：`SourceViewModel.kt:571-573` 确实是逐项加锁，B1 和 §5.5 已更正；补充了固定工作区目录、Mutex 不可重入、`remoteSnapshots` 过期三点 |
| 7 | 采纳 | 已核对：`writeRecords` 先删后 rename，两处各自 new 实例。新增 B11，放进阶段 0 |
| 8 | 采纳 | 已核对 `AutoBackup.kt:56-60, :77`、`MainActivity.kt:1161`、`SourceViewModel.kt:563`，P10 已改写。解决方式没有把 A11 整体提前，而是新增只用于去重的 `contentHash`，head 用的 `manifestHash` 暂不改；这样既能提前去重，也避免 head 状态大面积变成 UNKNOWN |
| 9 | 采纳 | 已核对 `GameSaveTypes.kt:140-141, :165`，C2、A2、A4、S10 都已补充（gameId 迁移，S10 只限 Root） |
| 10 | 采纳 | §5.5 第 3 点补全了前台服务的约束；B9 补充了 Worker 已有的超时风险 |
| 11 | 采纳 | 选择"网络批量由 Worker 自动续跑，UI 只展示"；本地批量靠幂等重跑；`KEEP` 会丢请求，UI 要明确提示；本地批量不加网络约束 |
| 12 | 采纳 | 新增 B12、启动时扫描残留、Magisk 永久授权引导。已核对行号：残留目录名在 `RootStorage.kt:177-178`，chown/chmod 在 `:199-200`（审核意见写的是 180-181、204-205，已按实际行号更正） |
| 13 | 采纳 | BackHandler 提到阶段 1；key 模型在阶段 0 决定；BOM 升级放在阶段 1 前；去重前提放在阶段 1 |
| 14 | 采纳 | 改用 `BoxWithConstraints`，不用 `calculateWindowSizeClass` |
| 15 | 采纳 | 命令面板和菜单改用 Dialog |
| 16 | 采纳 | §5.2 补充了 `-printf` 回退、`DocumentsContract`、"签名只做提示" |
| 17 | 采纳 | 写进延后的"下载云端最新"说明 |
| 18 | 采纳 | 已核对 `SourceViewModel.kt:823`，P9 已补充 |
| 19 | 采纳 | Worker 让路改为 `Result.success()` |
| 20 | 采纳 | 测试策略分散写进阶段 1/2/3 的"自动化"一栏和 A7、A13；已核对 `@Preview` 为 0 个，`androidTest` 只有 `AppSmokeTest.kt` |
| 21 | 采纳 | 修正了 adb 命令的引号；补充 `deviceTest` 的包名后缀（`build.gradle.kts:27`）和查 jobId 的方法；另外注明 `input keyevent` 不经过 kcm 回退 |
| 22 | 采纳 | 阶段 4 的测试加入 `ls -lZ` 验收 |
| 23 | 采纳 | `game_save_native.go` 的路径改为 `Go/daemon/`；P2 改为实际逻辑；C8 补充了 PC 读了 back 键但没有处理 |

### 未采纳

- 没有整条拒绝的审核意见。
- handheld 12 只拒绝了"完全不要 Worker"这一部分，原因见上表。