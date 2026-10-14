# OpenSave Android 移植与扩展计划

## 1. 项目状态

- 上游项目：`https://github.com/Liquid-co/OpenSave`
- Fork：`https://github.com/jun9100/OpenSave`
- 本地仓库：`/Volumes/Data/Coding/游戏存档管理`
- `origin` 指向 Fork，`upstream` 指向上游仓库。
- 当前基线：OpenSave `v2.4.1`，提交 `346d9bda0749fb57b48fd266f13e535af80f0285`。

本计划针对 Android 客户端，不改变现有 Windows、macOS、Linux 和 Steam Deck 客户端的默认行为。第一阶段以“Android 本地存档管理 + WebDAV 云端备份”为目标，之后再扩展跨设备 P2P 同步。

## 2. 目标与边界

### 2.1 目标

Android 版需要覆盖以下场景：

1. 普通 Android 游戏的共享存储存档。
2. PPSSPP、Dolphin、RetroArch、DuckStation、PCSX2/AetherSX2/NetherSX2、Citra/Azahar、Vita3K 等模拟器。
3. Switch 模拟器，包括 Eden、Yuzu 系分支、Suyu、Sudachi、Citron，以及可识别的 Ryujinx/Ryubing 布局。
4. GameHub/盖世游戏中的本地 Android 游戏和本地模拟器存档。
5. 通过群晖 WebDAV 或兼容 WebDAV 服务进行快照上传、浏览、下载、恢复和历史版本管理。
6. 与现有 PC OpenSave 的 WebDAV 快照保持兼容，至少可以在 PC 与 Android 之间迁移同一游戏的存档。

### 2.2 明确不承诺的范围

- 不绕过 Android 的应用沙箱，不默认要求 Root。
- 不承诺读取 `/data/user/0/<package>` 等其他应用私有目录。
- 不承诺读取所有 Android 版本上的 `Android/data` 和 `Android/obb`；这些目录必须以系统文件选择器实际授予的权限为准。
- 不备份 ROM、固件、prod.keys、title.keys、着色器缓存、日志和临时文件，除非用户明确手动加入。
- 不把云游戏或远程串流当成本地存档。存档在远端电脑或云服务器时，由对应端的 OpenSave 负责备份。
- 第一阶段不实现 Android 常驻实时文件监控，也不假设能在另一个游戏进程退出的瞬间收到可靠回调。

## 3. 当前代码复用评估

### 3.1 可以复用的核心

- `internal/snapshot`：快照 ZIP、恢复、保留策略和历史记录；先拆出不依赖桌面路径和注册表的移动端核心，再由 Android 适配文件来源。
- `internal/cloud`：WebDAV、Local Folder、Webhook 等云端提供者；WebDAV 请求、认证和快照命名可以复用，但当前实现直接 PUT 到最终文件名，Android 版本必须增加临时对象、完整性校验和发布保护。
- `internal/delta`：内容哈希和增量同步逻辑。
- `internal/switchtitle`：Switch TitleID 判断和游戏识别基础。
- `internal/presets`：现有 PC 模拟器和 Switch 模拟器规则，作为 Android 规则的参考和可移植部分。
- `internal/store`：现有 SQLite 数据模型和迁移机制，作为快照元数据合同；Android 端不直接依赖桌面数据目录，需要 Room/SQLite 适配层。
- `internal/p2p`：后续在 Android 上实现设备间同步时再接入。

当前桌面端已经有 Eden、Yuzu、Suyu、Sudachi、Citron、Ryujinx、Ryubing 等 Switch 预设。特别是 `internal/presets/switchsave.go` 已处理跨设备 profile ID 不一致的问题：同步时应以具体 TitleID 存档目录为边界，不能把整个 `nand/user/save` 根目录连同本机 profile ID 一起复制。

### 3.2 必须隔离或重写的部分

- `cmd/opensave-app` 使用 Wails 桌面壳，不能直接作为 Android APK 的 UI。
- `fsnotify` 依赖桌面文件系统事件，不能作为 SAF 文档树的通用实时监控方案。
- `internal/config/paths.go` 依赖 `os.UserHomeDir`、绝对路径和本地文件系统，需要抽象 Android 应用数据目录与用户授权 URI。
- 系统托盘、桌面自动启动、Windows 注册表、桌面通知和文件拖放不能照搬到 Android。
- 现有模拟器路径以 Windows/Linux 为主，需要增加 Android 的“用户授权树 + 相对路径规则”，而不是硬编码一个所有设备都相同的绝对路径。
- WebDAV 上传需要先做移动端可用性和可靠性加固：临时文件名、远端完整性验证、断点/重试、认证错误区分和失败后的清理。

### 3.3 v2.4.1 源码事实

- `internal/cloud/provider_webdav.go` 已实现 Basic Auth、`PUT`、`PROPFIND`、`GET`、`DELETE` 和上传后的 `HEAD` 大小检查，但没有临时对象发布和远端 SHA-256 校验。
- `internal/cloud` 的 `Service` 已提供流式上传/下载和大文件传输入口，Android 端优先复用这些无界面能力，避免把整个存档读入内存。
- `internal/snapshot` 目前以本地文件路径和 `store.Store` 为中心，并包含 Windows 注册表兼容入口；Android 只复用 ZIP/manifest/恢复算法，路径解析、注册表和桌面数据库必须隔离。
- `internal/watcher` 依赖 `fsnotify`，只能保留为桌面端实现；Android 自动备份使用前台检查和 WorkManager。
- 现有 CI 只有桌面 Go、前端和 Decky 检查；Android 工程加入后要新增独立的 Gradle/lint/unit/instrumentation job，不能把 Android 构建塞进桌面 job。

## 4. 推荐架构

### 4.1 总体选择

采用 **Kotlin/Jetpack Compose Android UI + 可复用 Go 核心 + Android 原生存储适配层**：

```text
Android Compose UI
        |
        v
Android Coordinator / WorkManager / Foreground Service
        |
        +-- SAF URI Storage Adapter
        |       负责 Android 文件选择、读取、写回和持久化权限
        |
        +-- OpenSave Mobile Bridge
                负责调用 Go 的快照、哈希、WebDAV、恢复和保留策略
```

不要尝试把 Wails 桌面窗口直接改成 Android 窗口。先把平台无关逻辑抽成稳定接口，再由 Android 端负责 URI 权限和后台任务。

### 4.2 Go 核心边界

第一阶段新增一个明确的移动端 API 层，例如：

```text
internal/mobilecore/
android/
  app/
  core-bridge/
```

Go 层只接触 Android 应用私有目录中的临时工作区和结构化参数，不直接假设自己能够读取 `content://` URI。Android 层先把用户授权的文档树复制到工作区，Go 层完成快照或上传，恢复时再由 Android 层把结果写回授权目录。

首个技术闸门是验证 `gomobile bind` 能否稳定生成 AAR：

- 能生成并通过真机测试：复用 Go 核心。
- 不能生成，或 `modernc.org/sqlite`、网络库、依赖树导致 AAR 不可维护：保留桌面 Go 核心不动，Android 端以 Kotlin 实现薄适配层，严格复用现有快照和 WebDAV 文件格式，并把可复用逻辑逐步迁移到无平台依赖包。

无论采用哪种桥接方式，Android UI、SAF、WorkManager 和权限处理都由 Kotlin 原生实现。

### 4.3 移动端核心接口

先定义稳定的结构化接口，再决定每个接口由 Go bridge 还是 Kotlin 实现：

| 接口 | 输入 | 输出/要求 |
| --- | --- | --- |
| `inspectSource` | 授权来源、相对路径、模拟器类型 | 候选游戏、TitleID、文件清单和排除原因 |
| `buildManifest` | 已复制到工作区的目录或文件 | 排序稳定的 manifest、文件哈希和总大小 |
| `createSnapshot` | 来源 ID、manifest、备注 | OpenSave 兼容 ZIP、快照 ID、校验值 |
| `restoreSnapshot` | 快照、目标工作区、映射参数 | 恢复前安全快照、原子写回结果和警告 |
| `listCloud` | WebDAV 配置 | 已完成快照列表；过滤 `.part`/临时对象 |
| `uploadCloud` | 本地 ZIP、远端名称 | 上传状态、远端大小/校验结果和可重试错误 |

所有接口都必须返回可区分的“未授权、来源不存在、存档正在变化、凭据错误、网络暂时失败、数据损坏、用户取消”状态，不能把失败统一显示成“备份成功”。

### 4.4 Android 数据层

新增 Android 本地数据库或现有 SQLite 的 Android 适配，至少记录：

| 数据 | 说明 |
| --- | --- |
| `source_id` | 存档来源唯一 ID |
| `source_type` | Android 游戏、模拟器、Switch 模拟器、GameHub、手动目录 |
| `display_name` | 用户看到的游戏名 |
| `package_name` | 可选的 Android 包名 |
| `emulator_id` | 例如 `eden`、`retroarch`、`ppsspp` |
| `title_id` | Switch 游戏 TitleID，可为空 |
| `tree_uri` | SAF 授权的根 URI，凭据以加密形式保存 |
| `relative_path` | 相对于授权根的路径 |
| `snapshot_policy` | 手动、前台检查、定时任务 |
| `exclude_rules` | 缓存、日志和设备专属文件排除规则 |
| `last_manifest_hash` | 判断内容是否真的发生变化 |

WebDAV 用户名、密码、令牌和自定义证书配置必须使用 Android Keystore 保护，不能明文写入普通数据库或日志。

## 5. 存档识别与映射方案

### 5.1 普通 Android 游戏

优先级从高到低：

1. 用户通过 `ACTION_OPEN_DOCUMENT_TREE` 选择存档目录。
2. 用户从游戏详情页添加单个文件。
3. 在已经授权的目录中按文件名、最近修改时间和大小发现候选项。
4. 由用户确认游戏名称和存档范围后开始跟踪。

不通过“全盘扫描”猜测应用私有目录。普通 Android 游戏如果只把数据放在私有目录，必须使用游戏自带导出、Root/Shizuku 或官方云端接口。

### 5.2 常见模拟器

为每个模拟器定义：

- 可选的显示名称和包名。
- 需要用户授权的根目录。
- 存档相对路径规则。
- 快速存档、即时存档和内存卡文件的分类。
- 默认排除项，例如 shader cache、日志、截图和临时锁文件。

初始预设建议包括：

- PPSSPP：游戏存档和 `SAVEDATA` 目录。
- Dolphin：GameCube/Wii 存档、内存卡和用户配置可分开选择。
- RetroArch：按核心、游戏和存档目录区分，默认不备份核心安装包。
- DuckStation：记忆卡、即时存档和游戏配置分开处理。
- PCSX2/AetherSX2/NetherSX2：记忆卡和即时存档分开处理。
- Citra/Azahar：`Nintendo 3DS` 下的存档数据，按 TitleID 识别。
- Vita3K：用户存档目录，避免把整个模拟器安装目录上传。

### 5.3 Eden 和 Switch 模拟器

Switch 模拟器必须按游戏拆分，而不是将整个 NAND 目录作为一个游戏：

```text
<授权根>/nand/user/save/<account>/<profile>/<titleId>/
```

Android 端处理规则：

1. 用户授权 Eden/Yuzu 系模拟器的数据根目录。
2. 识别 `nand/user/save`，向下寻找 16 位十六进制 TitleID 目录。
3. 每个 TitleID 作为独立游戏来源。
4. 读取 Eden/Yuzu 系缓存中的名称和图标；读取失败时显示 TitleID，允许用户重命名。
5. 快照中保留 TitleID 下的文件，不把本机 profile ID 作为跨设备同步内容。
6. 恢复时使用目标设备当前的 profile/account 映射；无法唯一确定时必须让用户选择，不能静默写入错误 profile。
7. 默认排除 shader cache、pipeline cache、日志、锁文件、密钥和固件。

需要覆盖的 Switch 模拟器规则：

| 模拟器族 | 主要布局 | 处理方式 |
| --- | --- | --- |
| Eden、Yuzu、Suyu、Sudachi、Citron | `nand/user/save/.../<TitleID>` | 复用现有 TitleID/profile 处理逻辑 |
| Ryujinx、Ryubing | `bis/user/save/.../<TitleID>` | 独立布局识别，不能误判为 NAND |
| GameHub 内置 Switch 模拟器 | 版本和厂商可能变化 | 先由用户授权，再按目录特征识别；保留手动路径入口 |

### 5.4 GameHub/盖世游戏

GameHub 官网描述同时包含本地 Android 游戏、PC/复古模拟器、Steam/EPIC 接入、云游戏和串流，因此必须先区分来源：

- **本地 Android 游戏**：只有共享存储或用户可导出的目录才可备份。
- **本地模拟器**：如果授权后能看到 Eden/Yuzu/其他模拟器目录，按对应模拟器规则处理。
- **本地导入 ROM**：只备份与 ROM 对应的存档目录，不默认上传 ROM。
- **云游戏/串流**：存档在远端电脑或云端，Android OpenSave 只显示“不可本地读取”，不创建伪备份。

第一版不依赖 GameHub 私有 API，也不把 `com.xiaoji.egggame` 的私有目录视为可访问目录。GameHub 专用适配必须通过真实设备测试确认路径和授权结果。

## 6. 自动备份设计

Android 上不能假设 SAF 目录存在可靠的实时文件事件，因此自动备份分三级：

1. **手动立即备份**：任何来源都必须支持，作为验收基线。
2. **应用回到前台时检查**：扫描已授权来源，等待文件大小和修改时间稳定后生成快照。
3. **WorkManager 定时任务**：默认仅在 Wi-Fi、充电或电量充足时运行，最小周期遵守 Android 系统限制。

可选的前台服务只用于用户主动开启的“持续监控模式”，必须显示通知并允许关闭。不能通过无障碍服务、后台拉起或隐蔽进程规避系统限制。

每次备份流程：

```text
读取授权 URI
  -> 复制到私有临时目录
  -> 文件稳定检查和锁文件过滤
  -> 生成 manifest/hash
  -> 与上次快照比较
  -> 无变化则跳过
  -> 生成 OpenSave 兼容快照
  -> 本地原子落盘
  -> 加入 WebDAV 上传队列
  -> 上传、校验、记录结果
```

游戏可能正在写文件时，必须使用延迟、重试和稳定性检查，不能直接复制半写入存档。

## 7. WebDAV 云端方案

### 7.1 兼容性目标

- 兼容 OpenSave 当前 WebDAV 快照命名和浏览逻辑。
- 兼容群晖 WebDAV Server 的 HTTPS 端口。
- 支持 Basic Auth；凭据用 Keystore 保护。
- 支持连接测试、目录列举、上传、下载、删除和恢复。
- 支持断网重试、指数退避、任务取消和失败原因展示。
- 上传前后显示本地大小、远端大小和校验状态。

### 7.2 上传安全要求

不能直接把未完成的数据写到最终快照文件名。推荐流程：

1. 上传到唯一的临时名称，例如 `.opensave/<snapshot>.part.<uuid>`。
2. 通过 HEAD 或 GET 校验远端大小；若服务支持，增加 SHA-256 校验。
3. 使用 WebDAV `MOVE` 发布为最终文件名，并设置不覆盖策略；不支持原子发布时保守失败。
4. 失败时清理临时对象，不能覆盖已有完整备份。
5. Browse cloud 只展示完整 `.zip` 快照。

### 7.3 PC/Android 互通

- PC OpenSave 上传的快照可以在 Android 端浏览。
- Android 端仅在能映射到已授权本地来源时允许恢复。
- Eden 等 Switch 存档恢复时重新映射到 Android 设备自己的 profile，不直接套用 PC 的绝对路径和 profile ID。
- Android 创建的快照上传到 WebDAV 后，PC 端可以下载；若 PC 端没有对应游戏路径，先导入或手动配置路径。

## 8. 开发阶段与验收标准

### 阶段 0：基线、分支和 CI

交付：

- 保持 `main` 与上游基线可追踪。
- 新增 `upstream` 远程。
- 建立 Android 专用分支策略和 GitHub Actions 检查。
- 记录 Go、JDK、Android Gradle Plugin、Node 和 Android SDK 版本。

验收：桌面端现有测试通过，Android 空壳可以在 CI 生成 debug APK。

### 阶段 1：Android 技术验证

交付：

- `android/` Gradle 工程。
- Jetpack Compose 空白应用和中文/英文基础导航。
- SAF 选择目录、持久化 URI 权限、读取文件、写回文件。
- `gomobile bind` AAR 验证；或完成 Kotlin fallback 决策。
- 真机日志和崩溃收集基础设施。

验收：在至少两台不同 Android 设备上选择一个测试目录，能读取、生成快照并恢复，重启应用后权限仍有效。

### 阶段 2：核心快照和本地库

交付：

- 将快照、manifest、哈希和恢复逻辑接入移动端。
- 游戏来源、快照历史、备注、置顶、保留数量。
- 文件排除规则和预览。
- 大文件流式复制，禁止一次性加载整个存档到内存。

验收：普通目录、单文件存档、多文件存档、空目录、文件名含中文和特殊字符均能正确备份和恢复。

### 阶段 3：WebDAV

交付：

- WebDAV 配置页和连接测试。
- HTTPS、认证、目录检查、上传队列、重试和错误提示。
- 临时文件发布和远端完整性验证。
- Browse cloud、下载、恢复和删除。
- 群晖 WebDAV Server 实测记录。

验收：正常上传、重复上传、断网中断、服务端拒绝、错误密码、空间不足、远端已有同名完整文件等场景都不会造成静默成功或破坏旧备份。

### 阶段 4：模拟器和 Switch 支持

交付：

- 模拟器预设和手动目录入口。
- Eden/Yuzu 系 TitleID 识别、名称显示和 profile 映射。
- Ryujinx/Ryubing `bis/user/save` 识别。
- 即时存档、内存卡、配置和缓存的分类/排除。

验收：Eden 至少准备两个 TitleID、两个 profile；完成备份、删除本机存档、恢复、启动模拟器验证存档可读。至少覆盖一个普通模拟器和一个 GameHub 本地模拟器目录。

### 阶段 5：普通 Android 游戏和 GameHub

交付：

- 用户授权目录下的 Android 游戏来源管理。
- GameHub 本地目录手动导入和特征识别。
- 对不可访问私有目录提供明确原因和替代方案。
- 云游戏/串流来源的不可备份状态展示。

验收：至少一个共享存储 Android 游戏、一个 GameHub 本地模拟器游戏和一个不可访问私有目录完成实测，并且 UI 不把不可访问目录误报为已备份。

### 阶段 6：自动任务和跨设备

交付：

- 前台恢复检查。
- WorkManager 定时备份。
- Wi-Fi/充电约束。
- 上传队列、失败重试、通知和历史日志。
- 与 PC OpenSave 通过同一 WebDAV 目录互相浏览和恢复。

验收：修改模拟器存档后重新打开 OpenSave 能检测变化；网络断开后任务进入待重试状态；网络恢复后只上传缺失快照；PC 和 Android 的恢复结果经过哈希或游戏内实际读取验证。

### 阶段 7：发布与维护

交付：

- Release APK/AAB、SHA-256 校验值和安装说明。
- Android 权限隐私说明。
- 已知限制列表，特别是 `Android/data`、私有目录和 GameHub 云游戏。
- GitHub Actions：单元测试、Android lint、instrumentation smoke test、debug/release 构建。
- 崩溃和上传错误的可导出诊断日志，不包含密码和存档内容。

验收：新设备按文档从安装到 WebDAV 配置、首次备份、恢复全流程可完成。

## 9. 测试矩阵

### Android 系统

- Android 8/9：验证基本 SAF、网络和后台限制兼容性。
- Android 11/12：验证 `Android/data` 限制和持久 URI 权限。
- Android 13/14/15 及当前稳定版本：验证通知权限、前台服务和电池限制。
- 至少一台 Pixel/AOSP 设备和一台国产厂商设备，验证后台任务差异。

### 存档来源

- 普通共享目录：单文件和多文件。
- PPSSPP、Dolphin、RetroArch、DuckStation。
- Eden：至少两个 Switch 游戏和两个 profile。
- Yuzu 系至少一个分支。
- Ryujinx/Ryubing 至少一个版本。
- GameHub 本地模拟器和本地 Android 游戏。

### 网络和数据完整性

- 群晖 WebDAV HTTP/HTTPS。
- 正确/错误凭据。
- 自签名或不匹配证书的明确错误。
- 断网、超时、服务端 401/403/404/409/507。
- 上传中杀进程、重复上传、同名冲突、旧快照恢复。
- 1 MB、100 MB、1 GB 以上快照的内存、耗时和剩余空间。
- 恢复前后 SHA-256 和游戏实际读取结果。

## 10. 分支与提交策略

建议使用以下分支，保持每个分支可审查：

```text
main
  feat/android-foundation
  refactor/platform-neutral-core
  feat/android-saf-storage
  feat/android-webdav
  feat/android-emulators
  feat/android-switch-eden
  feat/android-gamehub
  feat/android-background-jobs
  hardening/webdav-integrity
```

提交原则：

- 平台抽象、Android UI、模拟器规则、WebDAV 修复分开提交。
- 每个行为变化配单元测试或 instrumentation 测试。
- 不把真实 WebDAV 密码、设备目录、私有 APK 或存档放进仓库。
- 合并前分别运行桌面测试和 Android 测试，不能以 Android 编译成功替代功能验收。

## 11. 主要风险与处理

| 风险 | 处理 |
| --- | --- |
| Wails 无 Android 目标 | 使用 Compose 原生 Android 壳 |
| Go AAR 依赖无法稳定构建 | 阶段 1 设置技术闸门，必要时使用 Kotlin fallback |
| SAF 目录读取慢 | 只扫描用户授权根目录，使用增量 manifest 和流式 IO |
| Android 后台任务被系统杀死 | WorkManager + 可见通知的可选前台服务，任务可恢复 |
| `Android/data` 受系统限制 | 明确提示，依赖用户授权、Shizuku 或 Root，不伪造成功 |
| GameHub 私有目录变化 | 先支持手动导入和公开/授权目录，不依赖私有 API |
| Switch profile ID 不一致 | 只同步 TitleID 内容，恢复时使用本机 profile 映射 |
| WebDAV 服务不支持原子 MOVE | 保留旧文件，临时上传失败即失败，不覆盖最终文件 |
| 云游戏没有本地存档 | 标记为不可本地备份，引导在 PC/云端执行 OpenSave |
| 快照格式变化 | 增加跨版本 fixture，发布前用 PC v2.4.1 和最新版本互测 |

## 12. Definition of Done

只有满足以下条件才算 Android MVP 完成：

1. Android APK 可重复构建，安装后无 Root 也能运行。
2. 用户能通过 SAF 授权至少一个普通目录和一个模拟器目录。
3. 能对普通 Android 游戏、至少一个传统模拟器和 Eden Switch 存档完成备份/恢复。
4. Eden 存档按 TitleID 处理，不复制错误的 PC/profile 根路径。
5. GameHub 本地可访问目录能手动加入；不可访问目录有准确错误状态。
6. 快照能通过群晖 WebDAV 上传、浏览、下载和恢复。
7. 上传中断、认证失败和网络断开不会被显示为成功，也不会覆盖已有完整备份。
8. PC OpenSave 和 Android OpenSave 能通过同一 WebDAV 目录互相读取至少一组测试快照。
9. 自动任务在 Android 后台限制下可恢复，且用户能查看失败原因。
10. 测试、权限说明、隐私说明、已知限制和发布校验值齐全。

## 13. 下一步执行顺序

1. 建立 Android 构建空壳和最小 Compose 页面。
2. 完成 SAF 目录授权、复制、恢复和持久权限测试。
3. 做 `gomobile bind` 技术验证并记录依赖问题。
4. 抽取平台无关的快照/WebDAV 接口。
5. 实现普通目录的手动备份/恢复，再接入 WebDAV。
6. 接入 Eden TitleID 识别和 profile 映射。
7. 接入其他模拟器和 GameHub 手动目录。
8. 最后实现后台任务、跨设备互测和发布流程。

## 14. GitHub 执行与交付节奏

### 14.1 Milestone

建议在 Fork 中建立以下 Milestone，所有 Issue 必须绑定一个验收标准：

| Milestone | 范围 | 完成标志 |
| --- | --- | --- |
| `Android Foundation` | Gradle/Compose、SAF、持久权限、基础 CI | Debug APK 可构建，真机能授权、读取和写回测试目录 |
| `Android Local Backup` | 工作区、manifest、快照、恢复、本地历史 | 普通目录和单文件存档可离线备份/恢复 |
| `Android WebDAV` | WebDAV 配置、队列、重试、校验、云端浏览 | 群晖 WebDAV 的成功和失败场景均有实测证据 |
| `Android Emulator Support` | PPSSPP 等传统模拟器、Eden/Switch、GameHub 本地目录 | 至少一个传统模拟器、Eden 和一个 GameHub 本地来源完成恢复验收 |
| `Android Automation` | 前台检查、WorkManager、通知、PC/Android 互通 | 断网可重试，恢复网络后能继续并完成跨设备验证 |
| `Android Release` | 隐私说明、签名构建、发布包、回归测试 | 新设备按文档完成安装、配置、备份和恢复 |

### 14.2 Issue 顺序

按以下顺序创建 Issue，后一个 Issue 不应绕过前一个阶段的验收：

1. `docs: record Android support boundary and test devices`
2. `build: add Android Compose shell and Gradle CI`
3. `feat: implement SAF tree permission and workspace adapter`
4. `test: add Android permission persistence and file round-trip tests`
5. `refactor: extract platform-neutral manifest and snapshot core`
6. `feat: add mobile source database and manual backup/restore`
7. `hardening: make WebDAV upload publish atomically and verify integrity`
8. `feat: add Android WebDAV browser, queue, retry and restore`
9. `feat: add emulator presets and manual source mapping`
10. `feat: add Eden TitleID discovery and profile mapping`
11. `feat: add GameHub local-source handling and inaccessible states`
12. `feat: add foreground check and WorkManager scheduling`
13. `test: run PC/Android WebDAV interoperability matrix`
14. `release: produce signed APK/AAB and privacy documentation`

### 14.3 分支和 PR 规则

- `main` 只保留可回滚基线和已验收合并；所有实现从 `feat/`、`refactor/`、`hardening/` 或 `test/` 分支提交。
- 一个 PR 只解决一个可验收主题；平台抽象、Android UI、WebDAV 加固和模拟器规则不得混成一个大 PR。
- PR 必须同时附上变更说明、测试命令、设备/Android 版本、失败场景结果和已知限制。
- 任何涉及真实 WebDAV 的测试使用临时测试目录和测试账号；仓库、Issue、CI 日志和截图不得包含密码、Token、真实存档或私有目录。
- Android 功能合并前必须通过桌面回归测试；桌面测试通过不能替代真机验收。

### 14.4 每个阶段的固定验收记录

每个 Milestone 关闭前保存一份不含敏感信息的记录：

- Git commit、构建产物和工具链版本。
- Android 设备型号、系统版本、目标模拟器版本。
- 输入目录类型、文件数量、总大小、manifest/hash 摘要。
- WebDAV 响应类别、上传后远端大小/校验结果和重试次数。
- 恢复前后哈希、应用可读性或游戏内实际读取结果。
- 未覆盖的 Android 版本、厂商限制和不可访问目录。
